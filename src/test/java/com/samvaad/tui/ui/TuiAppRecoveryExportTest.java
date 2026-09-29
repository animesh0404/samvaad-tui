package com.samvaad.tui.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;
import com.samvaad.tui.bootstrap.RecoveryCodesExport;
import com.samvaad.tui.model.ConversationStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Recovery-code export panel proofs: auto-open on pending export,
 * modal keyboard capture, save/save-later flow through the app action,
 * exact export semantics via the seam, and in-UI rendering without any
 * console leakage.
 */
class TuiAppRecoveryExportTest {

    private static final UUID ME = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final String STAGED = "Samvaad Recovery Codes\n"
            + "======================\n"
            + "\n"
            + "alpha-code-1\n"
            + "alpha-code-2\n";

    private static Path stage(Path e2eeDir) throws Exception {
        Files.createDirectories(e2eeDir);
        Path staged = e2eeDir.resolve("recovery-codes.pending");
        Files.writeString(staged, STAGED, StandardCharsets.UTF_8);
        return staged;
    }

    private static TuiSession session(ConversationStore store, RecoveryCodesExport export) {
        return new TuiSession("alice", "http://localhost:8080", store, null, null, null, null,
                null, null, null, null, export, null);
    }

    private static void await(CheckedBoolean check, String what) throws InterruptedException {
        long end = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < end) {
            try {
                if (check.get()) {
                    return;
                }
            } catch (RuntimeException e) {
                throw new AssertionError(e);
            }
            Thread.sleep(20);
        }
        throw new AssertionError("timed out waiting for: " + what);
    }

    private interface CheckedBoolean {
        boolean get();
    }

    private static String screenText(Screen screen, int cols, int rows) {
        StringBuilder text = new StringBuilder();
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                text.append(screen.getFrontCharacter(col, row).getCharacter());
            }
            text.append('\n');
        }
        return text.toString();
    }

    @Test
    void panelOpensWhenExportPending(@TempDir Path e2eeDir) throws Exception {
        stage(e2eeDir);
        ConversationStore store = new ConversationStore(ME, List.of());
        TuiSession session = session(store, new RecoveryCodesExport(e2eeDir));
        TuiApp app = new TuiApp(() -> 0);
        TuiState state = new TuiState();

        app.maybeShowRecoveryExport(session, state);

        assertTrue(state.recoveryExportVisible());
        assertTrue(state.recoveryExportLines().contains("alpha-code-1"));
    }

    @Test
    void panelStaysClosedWithoutPending(@TempDir Path e2eeDir) {
        ConversationStore store = new ConversationStore(ME, List.of());
        TuiApp app = new TuiApp(() -> 0);

        TuiState plain = new TuiState();
        app.maybeShowRecoveryExport(session(store, null), plain);
        assertFalse(plain.recoveryExportVisible());

        TuiState staged = new TuiState();
        app.maybeShowRecoveryExport(session(store, new RecoveryCodesExport(e2eeDir)), staged);
        assertFalse(staged.recoveryExportVisible());
    }

    @Test
    void saveLaterClosesWithoutDeleting(@TempDir Path e2eeDir) throws Exception {
        Path staged = stage(e2eeDir);
        TuiState state = new TuiState();
        state.enterRecoveryExport(List.of("alpha-code-1"));
        TuiController controller = new TuiController();

        assertEquals(TuiController.Action.CONTINUE,
                controller.handle(new KeyStroke(KeyType.Escape), state, List.of()));

        assertFalse(state.recoveryExportVisible());
        assertTrue(state.recoveryExportDismissed());
        assertTrue(Files.isRegularFile(staged), "save later must keep staging");

        // Dismissed panels are not reopened by later ticks.
        ConversationStore store = new ConversationStore(ME, List.of());
        new TuiApp(() -> 60_000).maybeShowRecoveryExport(
                session(store, new RecoveryCodesExport(e2eeDir)), state);
        assertFalse(state.recoveryExportVisible());
    }

    @Test
    void typingReachesPathNotComposer(@TempDir Path e2eeDir) throws Exception {
        stage(e2eeDir);
        TuiState state = new TuiState();
        state.enterRecoveryExport(List.of("alpha-code-1"));
        state.focusComposer();
        TuiController controller = new TuiController();

        assertEquals(TuiController.Action.CONTINUE,
                controller.handle(new KeyStroke('x', false, false), state, List.of()));

        assertEquals("x", state.recoveryPathInput());
        assertTrue(state.composer().isEmpty(), "panel input must not reach the composer");
    }

    @Test
    void enterRequestsSave(@TempDir Path e2eeDir) throws Exception {
        stage(e2eeDir);
        TuiState state = new TuiState();
        state.enterRecoveryExport(List.of("alpha-code-1"));
        TuiController controller = new TuiController();

        assertEquals(TuiController.Action.SAVE_RECOVERY_CODES,
                controller.handle(new KeyStroke(KeyType.Enter), state, List.of()));
        assertTrue(state.recoveryExportVisible(), "panel stays open until export completes");
    }

    @Test
    void successfulExportWritesDeletesAndConfirms(@TempDir Path e2eeDir, @TempDir Path dest)
            throws Exception {
        Path staged = stage(e2eeDir);
        Path destination = dest.resolve("saved.txt");
        ConversationStore store = new ConversationStore(ME, List.of());
        TuiSession session = session(store, new RecoveryCodesExport(e2eeDir));
        TuiApp app = new TuiApp(() -> 0);
        TuiState state = new TuiState();
        state.enterRecoveryExport(List.of("alpha-code-1"));
        for (char c : destination.toString().toCharArray()) {
            state.appendToRecoveryPath(c);
        }

        app.exportRecoveryCodes(session, state);

        await(() -> !state.recoveryExportVisible(), "export completion");
        assertEquals(STAGED, Files.readString(destination, StandardCharsets.UTF_8));
        assertFalse(Files.exists(staged));
        assertEquals("Recovery codes saved to: " + destination + ".", state.status());
    }

    @Test
    void failedExportKeepsStagingAndShowsError(@TempDir Path e2eeDir, @TempDir Path dest)
            throws Exception {
        Path staged = stage(e2eeDir);
        Path taken = dest.resolve("taken.txt");
        Files.writeString(taken, "existing", StandardCharsets.UTF_8);
        ConversationStore store = new ConversationStore(ME, List.of());
        TuiSession session = session(store, new RecoveryCodesExport(e2eeDir));
        TuiApp app = new TuiApp(() -> 0);
        TuiState state = new TuiState();
        state.enterRecoveryExport(List.of("alpha-code-1"));
        for (char c : taken.toString().toCharArray()) {
            state.appendToRecoveryPath(c);
        }

        app.exportRecoveryCodes(session, state);

        await(() -> !state.recoveryExportError().isEmpty(), "export error");
        assertTrue(state.recoveryExportVisible(), "panel stays open after failure");
        assertEquals(STAGED, Files.readString(staged, StandardCharsets.UTF_8));
        assertFalse(state.recoveryExportError().contains("alpha-code-1"));
        assertEquals("existing", Files.readString(taken, StandardCharsets.UTF_8));
    }

    @Test
    void emptyPathIsRejectedWithoutExport(@TempDir Path e2eeDir) throws Exception {
        Path staged = stage(e2eeDir);
        ConversationStore store = new ConversationStore(ME, List.of());
        TuiSession session = session(store, new RecoveryCodesExport(e2eeDir));
        TuiApp app = new TuiApp(() -> 0);
        TuiState state = new TuiState();
        state.enterRecoveryExport(List.of("alpha-code-1"));

        app.exportRecoveryCodes(session, state);

        assertEquals("Enter a destination path.", state.recoveryExportError());
        assertTrue(Files.isRegularFile(staged));
    }

    @Test
    void panelRendersCodesInsideLanternaOnly(@TempDir Path e2eeDir) throws IOException {        ConversationStore store = new ConversationStore(ME, List.of());
        TuiState state = new TuiState();
        state.enterRecoveryExport(List.of("alpha-code-1", "alpha-code-2"));
        for (char c : "/tmp/codes.txt".toCharArray()) {
            state.appendToRecoveryPath(c);
        }
        Screen screen = new TerminalScreen(new DefaultVirtualTerminal(new TerminalSize(100, 30)));
        screen.startScreen();
        try {
            new TuiRenderer().render(screen, state, store, "alice", "http://localhost:8080");
            screen.refresh();

            String text = screenText(screen, 100, 30);
            assertTrue(text.contains("recovery codes have been generated"),
                    "panel notice is visible");
            assertTrue(text.contains("alpha-code-1"), "codes render inside Lanterna");
            assertTrue(text.contains("Save to: /tmp/codes.txt"), "path input renders");
        } finally {
            screen.stopScreen();
        }
    }

    @Test
    void manyCodesRenderInTwoColumns() throws IOException {
        java.util.List<String> codes = new java.util.ArrayList<>();
        for (int i = 0; i < 25; i++) {
            codes.add(String.format("code-%02d", i));
        }
        ConversationStore store = new ConversationStore(ME, List.of());
        TuiState state = new TuiState();
        state.enterRecoveryExport(codes);
        Screen screen = new TerminalScreen(new DefaultVirtualTerminal(new TerminalSize(100, 40)));
        screen.startScreen();
        try {
            new TuiRenderer().render(screen, state, store, "alice", "http://localhost:8080");
            screen.refresh();

            String text = screenText(screen, 100, 40);
            assertTrue(text.contains("code-00") && text.contains("code-24"), "all codes render");
            boolean paired = text.lines()
                    .anyMatch(line -> line.contains("code-00") && line.contains("code-13"));
            assertTrue(paired, "many codes share rows in two columns");
        } finally {
            screen.stopScreen();
        }
    }
}
