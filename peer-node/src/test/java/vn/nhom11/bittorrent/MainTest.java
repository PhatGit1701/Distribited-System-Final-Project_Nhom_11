package vn.nhom11.bittorrent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MainTest {
    @TempDir Path directory;

    @Test
    void demoWorksThroughCliAndRefusesToReuseDirectory() throws Exception {
        var bytes = new ByteArrayOutputStream();
        var console = new PrintStream(bytes);
        Path demo = directory.resolve("demo");
        assertEquals(0, Main.run(new String[]{"demo", demo.toString()}, console, console));
        assertArrayEquals(Files.readAllBytes(demo.resolve("original.bin")), Files.readAllBytes(demo.resolve("downloaded.bin")));
        assertTrue(bytes.toString().contains("COMPLETED"));
        assertEquals(1, Main.run(new String[]{"demo", demo.toString()}, console, console));
    }

    @Test
    void invalidArgumentsReturnNonzero() {
        var console = new PrintStream(new ByteArrayOutputStream());
        assertEquals(1, Main.run(new String[]{"download", "file.json", "out", "--workers"}, console, console));
        assertEquals(1, Main.run(new String[]{"unknown"}, console, console));
    }
}
