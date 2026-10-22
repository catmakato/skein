package com.pathongko.skein.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.pathongko.skein.core.error.ErrorCode;
import com.pathongko.skein.core.sync.SyncEvent;
import com.pathongko.skein.core.sync.SyncOutcome;
import com.pathongko.skein.platform.net.packet.HandshakeReplyC2S;
import com.pathongko.skein.platform.net.packet.SessionGrantS2C;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientBootstrapTest {

    @Test
    void handshakeReplyIsSentToServer(@TempDir java.nio.file.Path dir) {
        RecordingChannel channel = new RecordingChannel();
        FakeClientPlatform platform = new FakeClientPlatform(dir, channel);
        ClientBootstrap boot = new ClientBootstrap(platform, Runnable::run, e -> {});
        boot.install();
        boot.sendHandshakeReply();

        HandshakeReplyC2S reply = HandshakeReplyC2S.CODEC.fromBytes(channel.toServer.get(0));
        assertThat(reply.hasMod()).isTrue();
        assertThat(reply.clientProtocolVersion()).isEqualTo(ClientBootstrap.PROTOCOL_VERSION);
    }

    @Test
    void inboundGrantRunsASession(@TempDir java.nio.file.Path dir) throws Exception {
        RecordingChannel channel = new RecordingChannel();
        FakeClientPlatform platform = new FakeClientPlatform(dir, channel);
        CountDownLatch finished = new CountDownLatch(1);
        List<Object> events = new ArrayList<>();
        ClientBootstrap boot = new ClientBootstrap(platform, Runnable::run, ev -> {
            events.add(ev);
            finished.countDown();
        });
        boot.install();

        // Deliver a grant pointing at an unreachable endpoint: the session still
        // runs to a terminal (failed) outcome, which is all we assert here.
        channel.deliver(null, SessionGrantS2C.CODEC.toBytes(
                SessionGrantS2C.granted("http://127.0.0.1:1", "", "1.0.0", "x")));

        assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(events).isNotEmpty();
    }

    @Test
    void inboundDenialReportsFailureWithoutRunningASession(@TempDir java.nio.file.Path dir) {
        RecordingChannel channel = new RecordingChannel();
        FakeClientPlatform platform = new FakeClientPlatform(dir, channel);
        List<SyncEvent> events = new ArrayList<>();
        ClientBootstrap boot = new ClientBootstrap(platform, Runnable::run, events::add);
        boot.install();

        channel.deliver(null, SessionGrantS2C.CODEC.toBytes(
                SessionGrantS2C.denied("You are not on this server's whitelist.")));

        assertThat(events).hasSize(1);
        SyncEvent.SyncFinished finished = (SyncEvent.SyncFinished) events.get(0);
        assertThat(finished.outcome()).isInstanceOf(SyncOutcome.Failed.class);
        SyncOutcome.Failed failed = (SyncOutcome.Failed) finished.outcome();
        assertThat(failed.code()).isEqualTo(ErrorCode.ACCESS_DENIED);
        assertThat(failed.detail()).isEqualTo("You are not on this server's whitelist.");
    }
}
