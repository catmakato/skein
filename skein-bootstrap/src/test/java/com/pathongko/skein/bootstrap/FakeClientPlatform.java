package com.pathongko.skein.bootstrap;

import com.pathongko.skein.platform.ClientPlatform;
import com.pathongko.skein.platform.ClientRelauncher;
import com.pathongko.skein.platform.GameThreadExecutor;
import com.pathongko.skein.platform.InstalledMod;
import com.pathongko.skein.platform.LoaderInfo;
import com.pathongko.skein.platform.LoaderType;
import com.pathongko.skein.platform.PlatformPaths;
import com.pathongko.skein.platform.net.ControlChannel;
import com.pathongko.skein.platform.net.PlayerRef;
import java.nio.file.Path;
import java.util.List;

/** A test double for {@link ClientPlatform}: real paths under a temp dir, everything else stubbed. */
final class FakeClientPlatform implements ClientPlatform {

    private final PlatformPaths paths;
    private final ControlChannel controlChannel;

    FakeClientPlatform(Path gameDir) {
        this(gameDir, null);
    }

    FakeClientPlatform(Path gameDir, ControlChannel controlChannel) {
        this.controlChannel = controlChannel;
        this.paths = new PlatformPaths() {
            @Override
            public Path gameDir() {
                return gameDir;
            }

            @Override
            public Path configDir() {
                return gameDir.resolve("config");
            }

            @Override
            public Path modsDir() {
                return gameDir.resolve("mods");
            }

            @Override
            public Path skeinDir() {
                return gameDir.resolve(".skein");
            }
        };
    }

    @Override
    public LoaderInfo loaderInfo() {
        return new LoaderInfo() {
            @Override
            public LoaderType loader() {
                return LoaderType.NEOFORGE;
            }

            @Override
            public String minecraftVersion() {
                return "1.20.1";
            }

            @Override
            public String skeinVersion() {
                return "0.1.0";
            }

            @Override
            public List<InstalledMod> installedMods() {
                return List.of();
            }
        };
    }

    @Override
    public PlatformPaths paths() {
        return paths;
    }

    @Override
    public GameThreadExecutor gameThread() {
        return Runnable::run;
    }

    @Override
    public ControlChannel controlChannel() {
        if (controlChannel != null) {
            return controlChannel;
        }
        return new ControlChannel() {
            @Override
            public void sendToServer(byte[] payload) {
                // no-op
            }

            @Override
            public void sendToPlayer(PlayerRef player, byte[] payload) {
                // no-op
            }

            @Override
            public void onReceive(InboundHandler handler) {
                // no-op
            }
        };
    }

    @Override
    public ClientRelauncher relauncher() {
        return new ClientRelauncher() {
            @Override
            public boolean canRelaunch() {
                return false;
            }

            @Override
            public void relaunch() {
                throw new UnsupportedOperationException("relaunch not supported by fake platform");
            }
        };
    }
}
