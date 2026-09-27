package com.samvaad.tui.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

class AuthPathsTest {

    @Test
    void defaultAuthDirIsSeparateFromCryptoState() {
        Path dir = AuthPaths.defaultAuthDir();

        assertTrue(dir.isAbsolute());
        assertTrue(dir.getNameCount() >= 2);
        assertEquals(Paths.get("samvaad").resolve("auth"),
                dir.subpath(dir.getNameCount() - 2, dir.getNameCount()));
    }
}
