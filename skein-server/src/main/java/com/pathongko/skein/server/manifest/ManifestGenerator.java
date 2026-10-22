package com.pathongko.skein.server.manifest;

import com.pathongko.skein.common.util.Preconditions;
import com.pathongko.skein.core.domain.DownloadSource;
import com.pathongko.skein.core.domain.FileCategory;
import com.pathongko.skein.core.domain.FileEntry;
import com.pathongko.skein.core.domain.FileHash;
import com.pathongko.skein.core.domain.HashAlgorithm;
import com.pathongko.skein.core.domain.PackVersion;
import com.pathongko.skein.core.domain.RelativePath;
import com.pathongko.skein.core.domain.SyncPolicy;
import com.pathongko.skein.core.manifest.CategoryIndex;
import com.pathongko.skein.core.manifest.IndexRef;
import com.pathongko.skein.core.manifest.JsonManifestCodec;
import com.pathongko.skein.core.manifest.RootManifest;
import com.pathongko.skein.core.port.HashService;
import com.pathongko.skein.core.port.IncrementalHasher;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Scans a pack directory into a {@link GeneratedPack}: walks the managed category
 * directories, hashes every file (streaming), and produces the split manifest —
 * a root plus one index per category — with files addressed by content hash. Runs
 * headless on the server; no Minecraft involved.
 */
public final class ManifestGenerator {

    private final HashService hashService;
    private final JsonManifestCodec codec;
    private final HashAlgorithm algorithm;
    private final String fileBaseUrl;
    private final Function<FileCategory, SyncPolicy> policyStrategy;

    public ManifestGenerator(HashService hashService, JsonManifestCodec codec,
                             HashAlgorithm algorithm, String fileBaseUrl) {
        this(hashService, codec, algorithm, fileBaseUrl, category -> SyncPolicy.mandatory());
    }

    public ManifestGenerator(HashService hashService, JsonManifestCodec codec,
                             HashAlgorithm algorithm, String fileBaseUrl,
                             Function<FileCategory, SyncPolicy> policyStrategy) {
        this.hashService = Preconditions.requireNonNull(hashService, "hashService");
        this.codec = Preconditions.requireNonNull(codec, "codec");
        this.algorithm = Preconditions.requireNonNull(algorithm, "algorithm");
        this.fileBaseUrl = ensureTrailingSlash(Preconditions.requireNonBlank(fileBaseUrl, "fileBaseUrl"));
        this.policyStrategy = Preconditions.requireNonNull(policyStrategy, "policyStrategy");
    }

    /**
     * @param packRoot     the directory the pack lives in
     * @param packId       stable id of the pack
     * @param packVersion  the version to stamp on this generation
     * @param generatedAt  timestamp to record
     * @param categoryDirs which subdirectory holds each category (e.g. MOD -> "mods")
     */
    public GeneratedPack generate(Path packRoot, String packId, PackVersion packVersion,
                                  Instant generatedAt, Map<FileCategory, String> categoryDirs) {
        Path root = packRoot.toAbsolutePath().normalize();
        Map<FileCategory, byte[]> encodedIndices = new EnumMap<>(FileCategory.class);
        Map<FileHash, Path> contentByHash = new LinkedHashMap<>();
        List<IndexRef> indexRefs = new ArrayList<>();
        List<CategoryIndex> indices = new ArrayList<>();

        for (Map.Entry<FileCategory, String> mapping : categoryDirs.entrySet()) {
            FileCategory category = mapping.getKey();
            Path dir = root.resolve(mapping.getValue());
            if (!Files.isDirectory(dir)) {
                continue;
            }
            List<FileEntry> entries = scanCategory(root, dir, category, contentByHash);
            CategoryIndex index = new CategoryIndex(category, entries);
            indices.add(index);
            byte[] encoded = codec.encodeIndex(index);
            encodedIndices.put(category, encoded);
            FileHash indexHash = hashService.hash(encoded, algorithm);
            indexRefs.add(new IndexRef(category, "index/" + mapping.getValue() + ".json",
                    indexHash, entries.size()));
        }

        RootManifest rootManifest = new RootManifest(
                RootManifest.CURRENT_SCHEMA_VERSION, packId, packVersion, generatedAt,
                algorithm, indexRefs, Optional.empty());
        return new GeneratedPack(rootManifest, indices, codec.encodeRoot(rootManifest),
                encodedIndices, contentByHash);
    }

    private List<FileEntry> scanCategory(Path root, Path dir, FileCategory category,
                                         Map<FileHash, Path> contentByHash) {
        List<FileEntry> entries = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            List<Path> files = walk.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(Path::toString)) // deterministic ordering
                    .toList();
            for (Path file : files) {
                RelativePath rel = new RelativePath(root.relativize(file).toString());
                long size = Files.size(file);
                FileHash hash = hashFile(file);
                URI source = URI.create(fileBaseUrl + hash.value());
                entries.add(new FileEntry(rel, size, hash, category, policyStrategy.apply(category),
                        java.util.Set.of(DownloadSource.of(source))));
                contentByHash.putIfAbsent(hash, file);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed scanning " + dir, e);
        }
        return entries;
    }

    private FileHash hashFile(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            IncrementalHasher hasher = hashService.newHasher(algorithm);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                hasher.update(buf, 0, n);
            }
            return hasher.finish();
        } catch (IOException e) {
            throw new UncheckedIOException("failed hashing " + file, e);
        }
    }

    private static String ensureTrailingSlash(String url) {
        return url.endsWith("/") ? url : url + "/";
    }
}
