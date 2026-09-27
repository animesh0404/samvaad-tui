package com.samvaad.tui.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class E2eePathsTest {

    @Test
    void defaultDirEndsWithSamvaadE2ee() {
        Path dir = E2eePaths.defaultE2eeDir();
        assertNotNull(dir);
        assertTrue(dir.isAbsolute());
        assertEquals("e2ee", dir.getFileName().toString());
        assertEquals("samvaad", dir.getParent().getFileName().toString());
    }
}
