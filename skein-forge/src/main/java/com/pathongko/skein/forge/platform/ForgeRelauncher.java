// Compiles against Forge 47.3.0 / MC 1.20.1 (verified via :skein-forge:build). In-game runtime not yet exercised.
package com.pathongko.skein.forge.platform;

import com.pathongko.skein.platform.ClientRelauncher;

/** Relaunches the current Minecraft JVM with the same executable and arguments. */
public final class ForgeRelauncher implements ClientRelauncher {
    private final String executable;
    private final String[] arguments;

    public ForgeRelauncher() {
        ProcessHandle.Info info = ProcessHandle.current().info();
        this.executable = info.command().orElse(null);
        this.arguments = info.arguments().orElseGet(() -> new String[0]);
    }

    @Override
    public boolean canRelaunch() {
        return executable != null && !executable.isBlank() && arguments.length > 0;
    }

    @Override
    public void relaunch() {
        if (!canRelaunch()) throw new IllegalStateException("Minecraft launch command is unavailable");
        Thread launcher = new Thread(() -> {
            try {
                Thread.sleep(2500L); // let Minecraft close files and release the game directory
                java.util.List<String> command = new java.util.ArrayList<>(arguments.length + 1);
                command.add(executable);
                java.util.Collections.addAll(command, arguments);
                new ProcessBuilder(command)
                        .directory(new java.io.File(System.getProperty("user.dir", ".")))
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (java.io.IOException e) {
                System.err.println("[Skein] could not relaunch Minecraft: " + e.getMessage());
            }
        }, "skein-game-relauncher");
        launcher.setDaemon(false);
        launcher.start();
    }
}
