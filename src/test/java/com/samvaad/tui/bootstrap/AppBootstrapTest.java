package com.samvaad.tui.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.samvaad.tui.cli.CliOptions;
import org.junit.jupiter.api.Test;

class AppBootstrapTest {

    @Test
    void returnsZeroWhenOptionsProvided() {
        FakeConsoleIO io = new FakeConsoleIO();
        io.setPassword("s3cret".toCharArray());
        AppBootstrap bootstrap = new AppBootstrap(io);
        int exit = bootstrap.run(new CliOptions("http://localhost:8080", "alice"));
        assertEquals(0, exit);
    }

    @Test
    void promptsForMissingValues() {
        FakeConsoleIO io = new FakeConsoleIO();
        io.addLine("http://localhost:8080");
        io.addLine("alice");
        io.setPassword("s3cret".toCharArray());
        AppBootstrap bootstrap = new AppBootstrap(io);
        int exit = bootstrap.run(new CliOptions(null, null));
        assertEquals(0, exit);
    }

    @Test
    void returnsTwoOnInvalidServerUrl() {
        FakeConsoleIO io = new FakeConsoleIO();
        io.setPassword("s3cret".toCharArray());
        AppBootstrap bootstrap = new AppBootstrap(io);
        int exit = bootstrap.run(new CliOptions("localhost:8080", "alice"));
        assertEquals(2, exit);
    }

    @Test
    void returnsTwoWhenInputEnds() {
        AppBootstrap bootstrap = new AppBootstrap(new FakeConsoleIO());
        int exit = bootstrap.run(new CliOptions(null, null));
        assertEquals(2, exit);
    }
}
