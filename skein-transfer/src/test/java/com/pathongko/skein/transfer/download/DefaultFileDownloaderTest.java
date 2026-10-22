package com.pathongko.skein.transfer.download;

import com.pathongko.skein.core.domain.DownloadSource;
import com.pathongko.skein.core.domain.FileCategory;
import com.pathongko.skein.core.domain.FileEntry;
import com.pathongko.skein.core.domain.HashAlgorithm;
import com.pathongko.skein.core.domain.RelativePath;
import com.pathongko.skein.core.domain.SyncPolicy;
import com.pathongko.skein.core.hash.MessageDigestHashService;
import com.pathongko.skein.core.port.HashService;
import com.pathongko.skein.transfer.http.HttpRangeClient;
import com.pathongko.skein.transfer.http.RangeResponse;
import com.pathongko.skein.transfer.retry.DefaultRetryPolicy;
import com.pathongko.skein.transfer.retry.ExponentialBackoff;
import com.pathongko.skein.transfer.retry.Jitter;
import com.pathongko.skein.transfer.retry.RetryPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultFileDownloaderTest {

    private static final HashService HASH = new MessageDigestHashService();

    @TempDir
    Path dir;

    private static RetryPolicy retryPolicy() {
        return new DefaultRetryPolicy(5,
                new ExponentialBackoff(Duration.ofMillis(1), Duration.ofMillis(1), Jitter.none()));
    }

    private static FileEntry entry(byte[] data, URI... uris) {
        Set<DownloadSource> sources = new LinkedHashSet<>();
        for (int i = 0; i < uris.length; i++) {
            sources.add(new DownloadSource(uris[i], i));
        }
        return new FileEntry(new RelativePath("mods/a.jar"), data.length,
                HASH.hash(data, HashAlgorithm.SHA256), FileCategory.MOD, SyncPolicy.mandatory(), sources);
    }

    @Test
    void downloadsAndVerifiesHappyPath() throws Exception {
        byte[] data = "the quick brown fox".getBytes();
        URI u = URI.create("http://h/a");
        FakeClient client = new FakeClient();
        client.content.put(u, data);
        FileDownloader dl = new DefaultFileDownloader(client, HASH, retryPolicy(), Sleeper.NONE, 8);

        DownloadResult result = dl.download(entry(data, u), dir.resolve("a.part"), ProgressListener.NOOP);

        assertThat(Files.readAllBytes(dir.resolve("a.part"))).isEqualTo(data);
        assertThat(result.verifiedHash().matches(HASH.hash(data, HashAlgorithm.SHA256))).isTrue();
        assertThat(result.bytesTransferred()).isEqualTo(data.length);
    }

    @Test
    void resumesAfterMidStreamDropWithCorrectHash() throws Exception {
        byte[] data = new byte[100];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) i;
        }
        URI u = URI.create("http://h/b");
        FakeClient client = new FakeClient();
        client.content.put(u, data);
        client.dropFirstAfter = 48;
        FileDownloader dl = new DefaultFileDownloader(client, HASH, retryPolicy(), Sleeper.NONE, 16);

        DownloadResult result = dl.download(entry(data, u), dir.resolve("b.part"), ProgressListener.NOOP);

        assertThat(client.opens).isEqualTo(2); // dropped, then resumed
        assertThat(Files.readAllBytes(dir.resolve("b.part"))).isEqualTo(data);
        assertThat(result.verifiedHash().matches(HASH.hash(data, HashAlgorithm.SHA256))).isTrue();
        assertThat(result.bytesTransferred()).isEqualTo(100 - 48); // only the resumed remainder
    }

    @Test
    void rotatesToGoodMirrorOnHashMismatch() throws Exception {
        byte[] good = "correct-content-here".getBytes();
        byte[] bad = good.clone();
        bad[0] ^= 0xFF;
        URI a = URI.create("http://a/x");
        URI b = URI.create("http://b/x");
        FakeClient client = new FakeClient();
        client.content.put(a, bad);
        client.content.put(b, good);
        FileDownloader dl = new DefaultFileDownloader(client, HASH, retryPolicy(), Sleeper.NONE, 8);

        DownloadResult result = dl.download(entry(good, a, b), dir.resolve("c.part"), ProgressListener.NOOP);

        assertThat(client.lastServed).isEqualTo(b);
        assertThat(Files.readAllBytes(dir.resolve("c.part"))).isEqualTo(good);
        assertThat(result.verifiedHash().matches(HASH.hash(good, HashAlgorithm.SHA256))).isTrue();
    }

    /** In-memory HTTP client that can simulate a one-time mid-stream drop. */
    private static final class FakeClient implements HttpRangeClient {
        final Map<URI, byte[]> content = new HashMap<>();
        int dropFirstAfter = -1;
        boolean dropped;
        int opens;
        URI lastServed;

        @Override
        public RangeResponse open(URI uri, long fromOffset) {
            opens++;
            lastServed = uri;
            byte[] data = content.get(uri);
            if (data == null) {
                return new RangeResponse(404, 0, new ByteArrayInputStream(new byte[0]));
            }
            byte[] slice = Arrays.copyOfRange(data, (int) fromOffset, data.length);
            InputStream body;
            if (dropFirstAfter >= 0 && !dropped) {
                dropped = true;
                body = new DropStream(slice, dropFirstAfter);
            } else {
                body = new ByteArrayInputStream(slice);
            }
            return new RangeResponse(fromOffset > 0 ? 206 : 200, slice.length, body);
        }
    }

    private static final class DropStream extends InputStream {
        private final byte[] data;
        private final int throwAfter;
        private int pos;

        DropStream(byte[] data, int throwAfter) {
            this.data = data;
            this.throwAfter = throwAfter;
        }

        @Override
        public int read() throws IOException {
            if (pos >= throwAfter) {
                throw new IOException("simulated drop");
            }
            return pos < data.length ? data[pos++] & 0xFF : -1;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (pos >= throwAfter) {
                throw new IOException("simulated drop");
            }
            int avail = Math.min(Math.min(len, throwAfter - pos), data.length - pos);
            if (avail <= 0) {
                return -1;
            }
            System.arraycopy(data, pos, b, off, avail);
            pos += avail;
            return avail;
        }
    }
}
