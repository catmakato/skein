// UNVERIFIED: needs NeoGradle toolchain — compile on build machine
package com.pathongko.skein.neoforge.client;

import com.pathongko.skein.bootstrap.SyncProgressListener;
import com.pathongko.skein.core.error.ErrorCode;
import com.pathongko.skein.core.error.Recoverability;
import com.pathongko.skein.core.sync.SyncEvent;
import com.pathongko.skein.core.sync.SyncOutcome;
import com.pathongko.skein.core.sync.SyncState;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * The client-side progress GUI shown while {@code SkeinClientSession} runs a sync.
 *
 * <h2>Threading contract</h2>
 * This screen doubles as a {@link SyncProgressListener}. {@link #onEvent(SyncEvent)}
 * is invoked by the sync engine off the game thread (from whatever {@code
 * ExecutorProvider} the session was built with) — it must NEVER touch rendering,
 * Minecraft's GUI widgets, or anything else that isn't thread-safe. Instead it only
 * writes to the {@code volatile} fields declared below. The game thread's
 * {@link #render(GuiGraphics, int, int, float)} (called every frame by the client)
 * only ever *reads* those fields and draws the current snapshot. This is the
 * classic "off-thread writer, volatile fields, single-threaded reader" pattern —
 * it avoids needing a lock because every field is independently published and the
 * render loop never needs a consistent multi-field snapshot (a slightly stale
 * combination of, say, bytesDone/bytesTotal for one frame is harmless).
 *
 * <p>No sync decisions are made here. Every outcome this screen presents was
 * already decided by the shared {@code SkeinClientSession} / {@code
 * SyncOrchestrator} in skein-core; this class is display-only.
 */
public final class SkeinProgressScreen extends Screen implements SyncProgressListener {

    // --- fields written by onEvent() (off game thread), read by render() (game thread) ---

    private volatile SyncState currentState = SyncState.IDLE;
    private volatile String statusLine = "Preparing...";

    private volatile int diffFetches = 0;
    private volatile int diffDeletions = 0;
    private volatile long bytesToDownload = 0L;
    private volatile boolean diffKnown = false;

    private volatile long bytesDone = 0L;
    private volatile long bytesTotal = 0L;

    /** Non-null once {@link SyncEvent.SyncFinished} has arrived; render() checks this each frame. */
    private volatile SyncOutcome terminalOutcome = null;

    // --- game-thread-only state (built lazily once terminalOutcome is observed) ---

    private boolean terminalUiBuilt = false;

    // --- manual-URL fallback (auto-discovery failed): the hook asks this screen to
    // show a URL field; the submit callback re-runs a sync with the typed URL. ---

    /** Set by requestManualUrl() (any thread); render() builds the field once. */
    private volatile boolean manualUrlRequested = false;
    private volatile String manualUrlPrefill = "";
    private volatile Consumer<String> manualUrlSubmit = url -> { };
    private boolean manualUiBuilt = false;
    private EditBox urlBox;

    /**
     * Ask the screen to present a URL entry field (used when auto-discovery found no
     * Skein server). {@code onSubmit} is invoked on the game thread with the typed
     * URL when the player presses Sync; it should dispatch the actual sync onto a
     * background executor. Safe to call from any thread.
     */
    public void requestManualUrl(String prefill, Consumer<String> onSubmit) {
        this.manualUrlPrefill = prefill == null ? "" : prefill;
        this.manualUrlSubmit = onSubmit == null ? url -> { } : onSubmit;
        this.manualUrlRequested = true;
    }

    public SkeinProgressScreen() {
        // confirm on build machine: Component.literal(...) vs Component.translatable(...) — using a
        // literal here since Skein does not (yet) ship a lang file for this screen.
        super(Component.literal("Skein — Syncing Modpack"));
    }

    // =====================================================================
    // SyncProgressListener — called OFF the game thread. Volatile writes only.
    // =====================================================================

    @Override
    public void onEvent(SyncEvent event) {
        if (event instanceof SyncEvent.StateChanged changed) {
            this.currentState = changed.to();
            this.statusLine = describe(changed.to());
        } else if (event instanceof SyncEvent.ManifestResolved resolved) {
            this.statusLine = "Resolved manifest for " + resolved.packId() + " (" + resolved.version() + ")";
        } else if (event instanceof SyncEvent.DiffComputed diff) {
            this.diffFetches = diff.fetches();
            this.diffDeletions = diff.deletions();
            this.bytesToDownload = diff.bytesToDownload();
            this.diffKnown = true;
            this.bytesTotal = diff.bytesToDownload();
        } else if (event instanceof SyncEvent.ProgressUpdated progress) {
            this.bytesDone = progress.bytesDone();
            this.bytesTotal = progress.bytesTotal();
        } else if (event instanceof SyncEvent.SyncFinished finished) {
            // Published once, terminally; render() picks this up on its next frame
            // (on the game thread) and builds the terminal UI there.
            this.terminalOutcome = finished.outcome();
        }
    }

    private static String describe(SyncState state) {
        return switch (state) {
            case IDLE -> "Idle";
            case RESOLVING_MANIFEST -> "Resolving manifest...";
            case DIFFING -> "Comparing files...";
            case DOWNLOADING -> "Downloading...";
            case APPLYING -> "Applying changes...";
            case DONE -> "Done";
            case FAILED -> "Failed";
            case CANCELLED -> "Cancelled";
        };
    }

    // =====================================================================
    // Screen (game thread only) — reads volatile fields, draws current snapshot.
    // =====================================================================

    @Override
    protected void init() {
        // Buttons for the terminal states are added lazily in render() once the
        // outcome is known (see maybeBuildTerminalUi). Nothing to add up front.
        super.init();
    }

    // confirm on build machine: Screen.render signature for 1.20.1 is
    // render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick)
    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // confirm on build machine: renderBackground(GuiGraphics) vs (GuiGraphics, int, int, float) in 1.20.1
        this.renderBackground(guiGraphics);

        SyncOutcome outcome = this.terminalOutcome;
        if (outcome != null) {
            maybeBuildTerminalUi(outcome);
        } else if (manualUrlRequested) {
            maybeBuildManualUi();
        }

        int centerX = this.width / 2;
        int titleY = this.height / 2 - 40;

        // confirm on build machine: GuiGraphics#drawCenteredString(Font, Component, int, int, int)
        guiGraphics.drawCenteredString(this.font, this.title, centerX, titleY, 0xFFFFFF);
        guiGraphics.drawCenteredString(this.font, Component.literal(currentStatusText()), centerX, titleY + 16, 0xCCCCCC);

        // Progress bar only while an actual sync is running (not before, not on the
        // manual-URL prompt, not at a terminal state).
        if (outcome == null && !manualUrlRequested) {
            drawProgressBar(guiGraphics, centerX, titleY + 36);
        }

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    private String currentStatusText() {
        SyncOutcome outcome = this.terminalOutcome;
        if (outcome != null) {
            return terminalStatusText(outcome);
        }
        if (manualUrlRequested) {
            return "No Skein server found automatically — enter its URL and press Sync.";
        }
        if (diffKnown) {
            return statusLine + "  (" + diffFetches + " files, " + diffDeletions + " to remove, "
                    + formatBytes(bytesToDownload) + ")";
        }
        return statusLine;
    }

    private String terminalStatusText(SyncOutcome outcome) {
        if (outcome instanceof SyncOutcome.UpToDate) {
            return "Already up to date.";
        } else if (outcome instanceof SyncOutcome.Applied) {
            return "Sync complete.";
        } else if (outcome instanceof SyncOutcome.RestartRequired) {
            return "Downloaded updates — please restart your game.";
        } else if (outcome instanceof SyncOutcome.Failed failed) {
            return "Sync failed [" + failed.code() + "]: " + failed.detail();
        } else if (outcome instanceof SyncOutcome.Cancelled) {
            return "Sync cancelled.";
        }
        return "Unknown outcome.";
    }

    private void drawProgressBar(GuiGraphics guiGraphics, int centerX, int y) {
        int barWidth = 200;
        int barHeight = 10;
        int left = centerX - barWidth / 2;

        long total = this.bytesTotal;
        long done = this.bytesDone;
        int percent = total <= 0 ? 0 : (int) Math.min(100L, Math.max(0L, (done * 100L) / total));

        // confirm on build machine: GuiGraphics#fill(int, int, int, int, int) argb ordering
        guiGraphics.fill(left, y, left + barWidth, y + barHeight, 0xFF404040);
        int filled = (int) (barWidth * (percent / 100.0));
        guiGraphics.fill(left, y, left + filled, y + barHeight, 0xFF33AA33);

        guiGraphics.drawCenteredString(this.font, Component.literal(percent + "%"), centerX, y + barHeight + 4, 0xFFFFFF);
    }

    /**
     * Builds the terminal-state UI (buttons) exactly once, on the game thread,
     * once {@link #terminalOutcome} has become visible to render(). Never called
     * from {@link #onEvent(SyncEvent)}.
     */
    private void maybeBuildTerminalUi(SyncOutcome outcome) {
        if (terminalUiBuilt) {
            return;
        }
        terminalUiBuilt = true;

        if (outcome instanceof SyncOutcome.RestartRequired) {
            addRenderableWidget(quitButton());
        } else if (outcome instanceof SyncOutcome.Failed failed) {
            addRenderableWidget(backButton());
            logFailureDetail(failed);
        } else if (outcome instanceof SyncOutcome.UpToDate || outcome instanceof SyncOutcome.Applied) {
            // Nothing changed (or applied without needing a restart) — close and
            // let the game continue normally.
            this.onClose();
        } else if (outcome instanceof SyncOutcome.Cancelled) {
            addRenderableWidget(backButton());
        }
    }

    /**
     * Builds the manual-URL entry UI once, on the game thread: a prefilled text box
     * and a Sync button. Pressing Sync clears this UI and hands the typed URL to the
     * submit callback (which dispatches the sync onto the background executor).
     */
    private void maybeBuildManualUi() {
        if (manualUiBuilt) {
            return;
        }
        manualUiBuilt = true;

        int centerX = this.width / 2;
        int boxY = this.height / 2 + 4;
        // confirm on build machine: EditBox(Font, x, y, width, height, Component) ctor for 1.20.1
        this.urlBox = new EditBox(this.font, centerX - 130, boxY, 260, 20,
                Component.literal("ใส่ URL"));
        this.urlBox.setMaxLength(256);
        this.urlBox.setHint(Component.literal("ใส่ URL"));
        addRenderableWidget(this.urlBox);

        Button sync = Button.builder(Component.literal("Sync"), button -> {
                    String url = this.urlBox.getValue().trim();
                    if (url.isEmpty()) {
                        return;
                    }
                    // Leave the manual UI: hide the field and let progress/terminal UI drive.
                    this.manualUrlRequested = false;
                    removeWidget(this.urlBox);
                    removeWidget(button);
                    this.manualUrlSubmit.accept(url);
                })
                .bounds(centerX - 50, 52, 100, 20)
                .build();
        addRenderableWidget(sync);
    }

    private Button quitButton() {
        int centerX = this.width / 2;
        int y = this.height / 2 + 20;
        // confirm on build machine: Button.Builder API (net.minecraft.client.gui.components.Button.builder)
        // introduced mid-1.20.x line; verify exact builder signature for this MC/NeoForge version.
        return Button.builder(Component.literal("Quit"), button -> Minecraft.getInstance().stop())
                .bounds(centerX - 50, y, 100, 20)
                .build();
    }

    private Button backButton() {
        int centerX = this.width / 2;
        int y = this.height / 2 + 20;
        return Button.builder(Component.literal("Back"), button -> this.onClose())
                .bounds(centerX - 50, y, 100, 20)
                .build();
    }

    private void logFailureDetail(SyncOutcome.Failed failed) {
        ErrorCode code = failed.code();
        Recoverability recoverability = failed.recoverability();
        // confirm on build machine: wire to skein-common's logging facade instead of a bare
        // stdout print once that dependency is available to skein-neoforge.
        System.err.println("[Skein] sync failed: code=" + code + " recoverability=" + recoverability
                + " detail=" + failed.detail());
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format("%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format("%.1f MB", mb);
        }
        double gb = mb / 1024.0;
        return String.format("%.2f GB", gb);
    }

    @Override
    public boolean isPauseScreen() {
        // Keep the game paused (single-player) while syncing; confirm this is
        // desirable behavior on the build machine once multiplayer join flow is wired.
        return true;
    }

    @Override
    public void onClose() {
        // confirm on build machine: whether to explicitly return to a specific parent
        // screen (e.g. the connecting/join screen) vs. Minecraft's default close behavior.
        super.onClose();
    }
}
