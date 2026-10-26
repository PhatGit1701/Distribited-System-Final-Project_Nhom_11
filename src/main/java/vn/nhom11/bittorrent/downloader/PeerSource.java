package vn.nhom11.bittorrent.downloader;

import vn.nhom11.bittorrent.model.Manifest;
import vn.nhom11.bittorrent.model.PeerEndpoint;

import java.io.IOException;
import java.util.List;

/** Called by the coordinator; a future tracker adapter can resolve each chunk here. */
@FunctionalInterface
public interface PeerSource {
    List<PeerEndpoint> sourcesFor(Manifest manifest, int chunkIndex) throws IOException;

    static PeerSource fixed(List<PeerEndpoint> peers) {
        List<PeerEndpoint> snapshot = List.copyOf(peers);
        return (manifest, index) -> snapshot;
    }
}
