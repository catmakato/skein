package com.pathongko.skein.server;

import com.pathongko.skein.core.domain.FileCategory;
import com.pathongko.skein.core.domain.HashAlgorithm;
import com.pathongko.skein.core.domain.PackVersion;
import com.pathongko.skein.core.hash.MessageDigestHashService;
import com.pathongko.skein.core.manifest.JsonManifestCodec;
import com.pathongko.skein.core.port.HashService;
import com.pathongko.skein.server.admin.AdminSurface;
import com.pathongko.skein.server.http.SkeinHttpServer;
import com.pathongko.skein.server.manage.ControlPlane;
import com.pathongko.skein.server.manage.ManagedPack;
import com.pathongko.skein.server.manage.ServerNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AdminSurfaceIntegrationTest {

    private static final HashService HASH = new MessageDigestHashService();
    private static final JsonManifestCodec CODEC = new JsonManifestCodec();
    private final HttpClient http = HttpClient.newHttpClient();

    @TempDir
    Path packDir;

    private SkeinHttpServer server;
    private ControlPlane plane;
    private String base;

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static Map<FileCategory, String> dirs() {
        Map<FileCategory, String> dirs = new EnumMap<>(FileCategory.class);
        dirs.put(FileCategory.MOD, "mods");
        return dirs;
    }

    @BeforeEach
    void start() throws Exception {
        Files.createDirectories(packDir.resolve("mods"));
        Files.writeString(packDir.resolve("mods/a.jar"), "aaa");
        int port = freePort();
        ManagedPack pack = new ManagedPack(packDir, "test", PackVersion.of("1.0.0"),
                HashAlgorithm.SHA256, "http://127.0.0.1:" + port + "/files/", dirs(), HASH, CODEC);
        plane = new ControlPlane(HASH, CODEC, HashAlgorithm.SHA256, dirs());
        AdminSurface admin = new AdminSurface(plane, "secret");
        server = new SkeinHttpServer(pack::current, Optional.empty(), Optional.of(admin),
                new InetSocketAddress(port));
        server.start();
        plane.register(new ServerNode("test", pack, port, Optional.empty(), server));
        base = "http://127.0.0.1:" + port;
    }

    @AfterEach
    void stop() {
        plane.stopAll();
    }

    private HttpResponse<String> post(String path, String token, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json");
        if (token != null) {
            b.header("X-Admin-Token", token);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).GET();
        if (token != null) {
            b.header("X-Admin-Token", token);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String login(String password) throws Exception {
        HttpResponse<String> res = post("/admin/api/login", null, "{\"password\":\"" + password + "\"}");
        if (res.statusCode() != 200) {
            return null;
        }
        String body = res.body();
        int i = body.indexOf("\"token\":\"") + 9;
        return body.substring(i, body.indexOf('"', i));
    }

    @Test
    void servesTheDashboardPage() throws Exception {
        HttpResponse<String> res = get("/admin", null);
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("Pathongko");
    }

    @Test
    void wrongPasswordIsRejectedAndRightPasswordLogsIn() throws Exception {
        assertThat(login("nope")).isNull();
        assertThat(login("secret")).isNotBlank();
    }

    @Test
    void apiRequiresASession() throws Exception {
        assertThat(get("/admin/api/servers", null).statusCode()).isEqualTo(401);
        assertThat(get("/admin/api/servers", login("secret")).statusCode()).isEqualTo(200);
    }

    @Test
    void listsTheRegisteredServer() throws Exception {
        String token = login("secret");
        assertThat(get("/admin/api/servers", token).body()).contains("\"name\":\"test\"");
    }

    @Test
    void uploadAndDeleteRegeneratesThePack() throws Exception {
        String token = login("secret");
        assertThat(get("/admin/api/pack?server=test", token).body()).contains("\"totalFiles\":1");

        HttpRequest upload = HttpRequest.newBuilder(
                        URI.create(base + "/admin/api/upload?server=test&path=mods/b.jar"))
                .header("X-Admin-Token", token)
                .POST(HttpRequest.BodyPublishers.ofString("bbbb")).build();
        assertThat(http.send(upload, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        assertThat(Files.exists(packDir.resolve("mods/b.jar"))).isTrue();
        assertThat(get("/admin/api/pack?server=test", token).body()).contains("\"totalFiles\":2");

        assertThat(post("/admin/api/file/delete?server=test", token, "{\"path\":\"mods/b.jar\"}")
                .statusCode()).isEqualTo(200);
        assertThat(Files.exists(packDir.resolve("mods/b.jar"))).isFalse();
    }

    @Test
    void versionBumpIsReflectedInThePublicManifestImmediately() throws Exception {
        String token = login("secret");
        post("/admin/api/version?server=test", token, "{\"version\":\"2.0.0\"}");
        assertThat(get("/manifest/root", null).body()).contains("2.0.0");
    }

    @Test
    void pathTraversalIsRejected() throws Exception {
        String token = login("secret");
        assertThat(post("/admin/api/file/delete?server=test", token, "{\"path\":\"../../etc/passwd\"}")
                .statusCode()).isEqualTo(400);
    }

    @Test
    void canCreateAndRemoveAnotherServer() throws Exception {
        String token = login("secret");
        int port2 = freePort();
        Path dir2 = packDir.resolveSibling("pack2-" + port2);

        String createBody = "{\"name\":\"second\",\"dir\":\"" + dir2.toString().replace("\\", "\\\\")
                + "\",\"port\":" + port2 + "}";
        assertThat(post("/admin/api/servers/create", token, createBody).statusCode()).isEqualTo(200);
        assertThat(get("/admin/api/servers", token).body()).contains("\"name\":\"second\"");

        // the new server actually serves its manifest on its own port
        HttpResponse<String> root2 = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port2 + "/manifest/root")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(root2.statusCode()).isEqualTo(200);

        assertThat(post("/admin/api/servers/remove", token, "{\"name\":\"second\"}").statusCode())
                .isEqualTo(200);
        assertThat(get("/admin/api/servers", token).body()).doesNotContain("\"name\":\"second\"");
    }

    @Test
    void whitelistGateCanBeToggledAndIsReflectedInPackJson() throws Exception {
        String token = login("secret");
        assertThat(get("/admin/api/pack?server=test", token).body())
                .contains("\"whitelistGateEnabled\":false");

        HttpResponse<String> res = post(
                "/admin/api/whitelist-gate?server=test", token, "{\"enabled\":true}");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("\"whitelistGateEnabled\":true");
        assertThat(get("/admin/api/pack?server=test", token).body())
                .contains("\"whitelistGateEnabled\":true");
        assertThat(get("/admin/api/servers", token).body())
                .contains("\"whitelistGateEnabled\":true");
    }
}
