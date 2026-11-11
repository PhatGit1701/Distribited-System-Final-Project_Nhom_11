package vn.nhom11.bittorrent.peer;
import vn.nhom11.bittorrent.peer.event.NodeEventHub;
import vn.nhom11.bittorrent.peer.model.ChunkDownloadState;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import vn.nhom11.bittorrent.shared.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NodeEventHubTest {
    @Test void snapshotsKeepSiblingChunksAndRestartResetsOldState() {
        var hub=new NodeEventHub(new DefaultListableBeanFactory().getBeanProvider(SimpMessagingTemplate.class));
        var hashes=List.of(Hashing.sha256("a"),Hashing.sha256("b"));
        var metadata=new TorrentMetadata(Hashing.fileId("sample.bin",2,1),"sample.bin",2,1,2,hashes,"http://localhost");
        hub.begin(metadata);
        for(int i=0;i<2;i++)hub.chunk(metadata.fileId(),new ChunkDownloadState(i,ChunkDownloadState.Status.COMPLETED,
                "seed","alice","127.0.0.1:6881",1,hashes.get(i),hashes.get(i),3,List.of()));
        hub.status(metadata.fileId(),"COMPLETED",null);
        var before=hub.snapshot(Map.of(),List.of(metadata));
        assertEquals(2,before.downloads().getFirst().chunks().stream().filter(c->c.status()==ChunkDownloadState.Status.COMPLETED).count());
        hub.begin(metadata);
        var after=hub.snapshot(Map.of(),List.of(metadata));
        assertTrue(after.sequence()>before.sequence());
        assertTrue(after.downloads().getFirst().chunks().stream().allMatch(c->c.status()==ChunkDownloadState.Status.PENDING));
        assertTrue(before.downloads().getFirst().chunks().stream().allMatch(c->c.status()==ChunkDownloadState.Status.COMPLETED),"snapshot must remain immutable");
        for(int i=0;i<450;i++)hub.log("LOG","entry "+i);
        assertEquals(400,hub.snapshot(Map.of(),List.of()).events().size());
    }
}
