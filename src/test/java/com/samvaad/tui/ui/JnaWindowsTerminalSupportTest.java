package com.samvaad.tui.ui;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

/**
 * Guards the Windows TUI startup fix: Lanterna resolves its native Windows terminal
 * support reflectively through JNA, which Lanterna does not declare as a transitive
 * dependency. These tests fail if {@code net.java.dev.jna:jna} or
 * {@code net.java.dev.jna:jna-platform} ever drop off the runtime classpath.
 *
 * <p>Classes are loaded without initialization so no native library is touched;
 * the tests are safe on Linux/macOS/Windows and assert presence only, never
 * Windows-only behavior. Terminal creation itself stays with Lanterna's
 * {@code DefaultTerminalFactory} and is unchanged.
 */class JnaWindowsTerminalSupportTest {

    @Test
    void jnaIsOnTheRuntimeClasspath() {
        assertNotNull(loadWithoutInitializing("com.sun.jna.Native"),
                "net.java.dev.jna:jna must be a runtime dependency");
    }

    @Test
    void jnaPlatformIsOnTheRuntimeClasspath() {
        assertNotNull(loadWithoutInitializing("com.sun.jna.platform.win32.Kernel32"),
                "net.java.dev.jna:jna-platform must be a runtime dependency");
    }

    @Test
    void lanternaWindowsBackendIsPackaged() {
        assertNotNull(loadWithoutInitializing("com.googlecode.lanterna.terminal.win32.WindowsTerminal"),
                "Lanterna must provide the native Windows terminal backend");
    }

    private static Class<?> loadWithoutInitializing(String name) {
        return assertDoesNotThrow(
                () -> Class.forName(name, false, JnaWindowsTerminalSupportTest.class.getClassLoader()),
                "Missing runtime class: " + name);
    }
}
