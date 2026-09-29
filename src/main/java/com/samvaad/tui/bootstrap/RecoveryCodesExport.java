package com.samvaad.tui.bootstrap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Filesystem side of the first-enrollment recovery-code export: reads the
 * pending staged file created by fresh enrollment and copies it verbatim
 * to a user-selected destination, deleting the staging file only after a
 * verified write.
 *
 * <p>No rendering, no key handling, no logging here: error messages carry
 * paths only, never code values. Callers (the Lanterna export flow) own
 * all user interaction.
 */
public final class RecoveryCodesExport {

    /** Default filename when the user selects an existing directory. */
    static final String DEFAULT_FILENAME = "samvaad-recovery-codes.txt";

    private final Path stagingFile;

    public RecoveryCodesExport(Path e2eeDir) {
        Objects.requireNonNull(e2eeDir, "e2eeDir");
        this.stagingFile = E2eeRuntimeFactory.recoveryCodesStagingFile(e2eeDir);
    }

    /**
     * Whether a pending export exists.
     */
    public boolean hasPending() {
        return Files.isRegularFile(stagingFile);
    }

    /**
     * Reads only the code lines for in-UI display: the staged format ends
     * with one code per line, so the trailing non-blank block is the
     * code set. Header wording above it may evolve without affecting
     * this.
     *
     * @throws E2eeException when no pending export exists or cannot be read
     */
    public List<String> readCodes() {
        List<String> lines = readStagedLines();
        List<String> codes = new ArrayList<>();
        int i = lines.size() - 1;
        while (i >= 0 && lines.get(i).isBlank()) {
            i--;
        }
        while (i >= 0 && !lines.get(i).isBlank()) {
            codes.add(lines.get(i));
            i--;
        }
        Collections.reverse(codes);
        return List.copyOf(codes);
    }

    /**
     * Reads the staged content for in-UI display. The returned lines are
     * the staged file verbatim (header plus codes); no second
     * representation is generated.
     *
     * @throws E2eeException when no pending export exists or cannot be read
     */
    public List<String> readStagedLines() {
        String content;
        try {
            content = Files.readString(stagingFile, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            throw new E2eeException("No pending recovery-code export.", e);
        } catch (IOException e) {
            throw new E2eeException("Cannot read pending recovery codes.", e);
        }
        return List.of(content.split("\n", -1));
    }

    /**
     * Copies the staged content verbatim to {@code destination} and, only
     * after a verified write, deletes the staging file.
     *
     * <p>Resolution: absolute paths are used as given; relative paths
     * resolve against the process working directory. An existing
     * directory selects {@value #DEFAULT_FILENAME} inside it. An existing
     * file is never overwritten; a missing parent directory is never
     * created — both fail closed with the staging file intact.
     *
     * @throws E2eeException with a path-only message on any failure; the
     *         staging file is left intact
     * @return the resolved destination the codes were written to
     */
    public Path exportTo(Path destination) {
        Objects.requireNonNull(destination, "destination");
        Path target = destination.isAbsolute()
                ? destination.normalize()
                : Paths.get("").toAbsolutePath().resolve(destination).normalize();
        if (Files.isDirectory(target)) {
            target = target.resolve(DEFAULT_FILENAME);
        }
        Path parent = target.getParent();
        if (parent != null && !Files.isDirectory(parent)) {
            throw new E2eeException("Destination directory does not exist: " + parent);
        }
        if (Files.exists(target)) {
            throw new E2eeException("Destination already exists: " + target);
        }
        byte[] staged;
        try {
            staged = Files.readAllBytes(stagingFile);
        } catch (NoSuchFileException e) {
            throw new E2eeException("No pending recovery-code export.", e);
        } catch (IOException e) {
            throw new E2eeException("Cannot read pending recovery codes.", e);
        }
        try {
            Files.write(target, staged, StandardOpenOption.CREATE_NEW);
        } catch (FileAlreadyExistsException e) {
            throw new E2eeException("Destination already exists: " + target, e);
        } catch (IOException e) {
            throw new E2eeException("Cannot write recovery codes to: " + target, e);
        }
        try {
            if (Files.size(target) != staged.length) {
                // Created by us moments ago (CREATE_NEW); remove the corrupt
                // copy so a retry is possible, and keep staging intact.
                try {
                    Files.deleteIfExists(target);
                } catch (IOException ignored) {
                    // Best effort; the error below reports the destination.
                }
                throw new E2eeException("Recovery-code export verification failed: " + target);
            }
            Files.delete(stagingFile);
            return target;
        } catch (NoSuchFileException e) {
            throw new E2eeException("Recovery-code export verification failed: " + target, e);
        } catch (IOException e) {
            throw new E2eeException("Cannot finish recovery-code export: " + target, e);
        }
    }
}
