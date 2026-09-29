package com.samvaad.tui.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Staged recovery-code export proofs: pending detection, verbatim
 * content, absolute/relative/directory destination resolution, closed
 * failures that keep staging intact, and verified deletion only after a
 * successful write. Nothing here prints, logs, or transmits codes.
 */
class RecoveryCodesExportTest {

    private static final String STAGED = "Samvaad Recovery Codes\n"
            + "======================\n"
            + "\n"
            + "These codes are single-use recovery credentials.\n"
            + "Store this file securely. They are not regenerated automatically.\n"
            + "\n"
            + "alpha-code-1\n"
            + "alpha-code-2\n";

    private static Path stage(Path dir) throws Exception {
        Files.createDirectories(dir);
        Path staged = dir.resolve("recovery-codes.pending");
        Files.writeString(staged, STAGED, StandardCharsets.UTF_8);
        return staged;
    }

    private static RecoveryCodesExport exportOf(Path dir) {
        return new RecoveryCodesExport(dir);
    }

    @Test
    void detectsPendingExport(@TempDir Path dir) throws Exception {
        assertFalse(exportOf(dir).hasPending());
        stage(dir);
        assertTrue(exportOf(dir).hasPending());
    }

    @Test
    void readsStagedLinesVerbatim(@TempDir Path dir) throws Exception {        stage(dir);

        List<String> lines = exportOf(dir).readStagedLines();

        assertTrue(lines.contains("alpha-code-1"));
        assertTrue(lines.contains("alpha-code-2"));
        assertEquals(List.of(STAGED.split("\n", -1)), lines);
    }

    @Test
    void exportsToAbsolutePathAndDeletesStaging(@TempDir Path dir, @TempDir Path dest)
            throws Exception {
        Path staged = stage(dir);
        Path destination = dest.resolve("my-codes.txt");

        exportOf(dir).exportTo(destination);

        assertEquals(STAGED, Files.readString(destination, StandardCharsets.UTF_8));
        assertFalse(Files.exists(staged));
        assertFalse(exportOf(dir).hasPending());
    }

    @Test
    void relativePathResolvesAgainstWorkingDirectory(@TempDir Path dir) throws Exception {
        Path staged = stage(dir);
        String userDir = System.getProperty("user.dir");
        Path relative = Path.of("recovery-export-rel-test.txt");
        Path expected = Path.of(userDir).resolve(relative).normalize();
        try {
            exportOf(dir).exportTo(relative);

            assertEquals(STAGED, Files.readString(expected, StandardCharsets.UTF_8));
            assertFalse(Files.exists(staged));
        } finally {
            Files.deleteIfExists(expected);
        }
    }

    @Test
    void existingDirectoryUsesDefaultFilename(@TempDir Path dir, @TempDir Path dest)
            throws Exception {
        Path staged = stage(dir);

        exportOf(dir).exportTo(dest);

        assertEquals(STAGED,
                Files.readString(dest.resolve("samvaad-recovery-codes.txt"), StandardCharsets.UTF_8));
        assertFalse(Files.exists(staged));
    }

    @Test
    void existingDestinationIsRefusedAndStagingSurvives(@TempDir Path dir, @TempDir Path dest)
            throws Exception {
        Path staged = stage(dir);
        Path destination = dest.resolve("taken.txt");
        Files.writeString(destination, "existing", StandardCharsets.UTF_8);

        assertThrows(E2eeException.class, () -> exportOf(dir).exportTo(destination));

        assertEquals("existing", Files.readString(destination, StandardCharsets.UTF_8));
        assertEquals(STAGED, Files.readString(staged, StandardCharsets.UTF_8));
        assertTrue(exportOf(dir).hasPending());
    }

    @Test
    void missingParentFailsCleanlyAndStagingSurvives(@TempDir Path dir) {
        // No staging file needed: the parent check runs before any read.
        Path missing = dir.resolve("no-such-dir").resolve("codes.txt");

        assertThrows(E2eeException.class, () -> exportOf(dir).exportTo(missing));
    }

    @Test
    void missingParentKeepsStagingWhenStaged(@TempDir Path dir) throws Exception {
        Path staged = stage(dir);
        Path missing = dir.resolve("no-such-dir").resolve("codes.txt");

        assertThrows(E2eeException.class, () -> exportOf(dir).exportTo(missing));

        assertEquals(STAGED, Files.readString(staged, StandardCharsets.UTF_8));
        assertTrue(exportOf(dir).hasPending());
    }

    @Test
    void exportWritesNothingToStdoutOrStderr(@TempDir Path dir, @TempDir Path dest) {
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured));
        System.setErr(new PrintStream(captured));
        try {
            stage(dir);
            exportOf(dir).exportTo(dest.resolve("quiet.txt"));
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
        String text = captured.toString(StandardCharsets.UTF_8);
        assertTrue(text.isEmpty(), "export must not print");
        assertFalse(text.contains("alpha-code-1"));
    }

    @Test
    void readsOnlyCodeLines(@TempDir Path dir) throws Exception {
        stage(dir);

        assertEquals(List.of("alpha-code-1", "alpha-code-2"), exportOf(dir).readCodes());
    }

    @Test
    void exportReturnsResolvedDestination(@TempDir Path dir, @TempDir Path dest)
            throws Exception {
        stage(dir);
        Path destination = dest.resolve("resolved.txt");

        assertEquals(destination, exportOf(dir).exportTo(destination));
    }
}
