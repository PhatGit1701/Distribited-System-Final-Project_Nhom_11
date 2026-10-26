package vn.nhom11.bittorrent;

import vn.nhom11.bittorrent.demo.LocalDemo;
import vn.nhom11.bittorrent.downloader.DownloadEvent;
import vn.nhom11.bittorrent.downloader.DownloadManager;
import vn.nhom11.bittorrent.downloader.DownloadOptions;
import vn.nhom11.bittorrent.downloader.PeerSource;
import vn.nhom11.bittorrent.model.Manifest;
import vn.nhom11.bittorrent.model.PeerEndpoint;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class Main {
    private Main() { }

    public static void main(String[] args) {
        int status = run(args, System.out, System.err);
        if (status != 0) System.exit(status);
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0 || (args.length == 1 && args[0].equals("--help"))) {
            out.println("Usage:");
            out.println("  java -jar target/bittorrent-lite.jar demo <NEW-directory>");
            out.println("  java -jar target/bittorrent-lite.jar download <manifest.json> <output-file>");
            out.println("       --peer id@host:port [--peer id@host:port ...] [--workers 1..16]");
            return 0;
        }
        long started = System.nanoTime();
        Consumer<DownloadEvent> events = event -> out.printf(
                "%8.1fms %-17s chunk=%d peer=%s request=%s %s%n",
                (System.nanoTime() - started) / 1_000_000.0, event.type(), event.chunkIndex(),
                event.peerId(), event.requestId(), event.detail());
        try {
            if (args[0].equals("demo") && args.length == 2) {
                Path output = LocalDemo.run(Path.of(args[1]), events);
                out.println("COMPLETED: " + output + " (final SHA-256 verified)");
                return 0;
            }
            if (!args[0].equals("download") || args.length < 3) {
                throw new IllegalArgumentException("Invalid command; use --help");
            }
            List<PeerEndpoint> peers = new ArrayList<>();
            int workers = 4;
            boolean workersSpecified = false;
            for (int i = 3; i < args.length; i++) {
                String option = args[i];
                if (i + 1 == args.length) throw new IllegalArgumentException("Missing value for " + option);
                String value = args[++i];
                switch (option) {
                    case "--peer" -> peers.add(PeerEndpoint.parse(value));
                    case "--workers" -> {
                        if (workersSpecified) throw new IllegalArgumentException("Duplicate --workers");
                        workersSpecified = true;
                        workers = Integer.parseInt(value);
                    }
                    default -> throw new IllegalArgumentException("Unknown option: " + option);
                }
            }
            if (peers.isEmpty()) throw new IllegalArgumentException("At least one --peer is required");
            Manifest manifest = Manifest.read(Path.of(args[1]));
            Path output = new DownloadManager(DownloadOptions.defaults(workers), events)
                    .download(manifest, PeerSource.fixed(peers), Path.of(args[2]));
            out.println("COMPLETED: " + output + " (final SHA-256 verified)");
            return 0;
        } catch (IllegalArgumentException | IOException e) {
            err.println("ERROR: " + e.getMessage());
            for (Throwable cause : e.getSuppressed()) err.println("  Source/cleanup error: " + cause.getMessage());
            return 1;
        }
    }
}
