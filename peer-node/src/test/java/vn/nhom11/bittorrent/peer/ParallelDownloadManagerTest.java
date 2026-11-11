package vn.nhom11.bittorrent.peer;
import vn.nhom11.bittorrent.peer.client.TrackerGateway;
import vn.nhom11.bittorrent.peer.repository.ChunkStore;
import vn.nhom11.bittorrent.peer.model.DownloadSettings;
import vn.nhom11.bittorrent.peer.model.ChunkDownloadState;
import vn.nhom11.bittorrent.peer.model.PeerIdentity;
import vn.nhom11.bittorrent.peer.service.TitForTatManager;
import vn.nhom11.bittorrent.peer.service.ParallelDownloadManager;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import vn.nhom11.bittorrent.shared.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static vn.nhom11.bittorrent.shared.WireProtocol.*;

@Timeout(20)
class ParallelDownloadManagerTest {
    @TempDir Path temp;
    static class Tracker implements TrackerGateway {
        TorrentMetadata metadata;
        volatile List<PeerNodeInfo> peers=List.of();
        final List<HeartbeatRequest> heartbeats=new CopyOnWriteArrayList<>();
        final List<String> badChunks=new CopyOnWriteArrayList<>();
        public void reportBadChunk(String fileId,String peerId,int index,String expected,String actual) {
            assertNotEquals(expected,actual);badChunks.add(peerId);
        }
        Tracker(TorrentMetadata m){metadata=m;}
        public TorrentMetadata metadata(String id){return metadata;}
        public List<PeerNodeInfo> swarm(String id){return peers;}
        public void heartbeat(HeartbeatRequest r){heartbeats.add(r);}
        public void register(TorrentMetadata m,PeerIdentity identity){}
    }
    record Fixture(byte[] bytes,TorrentMetadata metadata) {
        byte[] chunk(int i){return Arrays.copyOfRange(bytes,i*metadata.chunkSize(),i*metadata.chunkSize()+metadata.chunkLength(i));}
    }
    Fixture fixture(int size) throws Exception {
        byte[] bytes=new byte[size];new Random(11).nextBytes(bytes);
        Path file=temp.resolve("source-"+size+".bin");Files.write(file,bytes);
        return new Fixture(bytes,TorrentGenerator.generate(file,1024,"http://localhost:8080"));
    }
    interface Handler {void respond(Socket socket,WireCodec.ChunkRequest request) throws Exception;}
    static class TestPeer implements AutoCloseable {
        final ServerSocket server=new ServerSocket(0);
        final ExecutorService pool=Executors.newCachedThreadPool();
        final Set<Socket> clients=ConcurrentHashMap.newKeySet();
        final String id;
        TestPeer(String id,Handler handler) throws IOException {
            this.id=id;
            pool.submit(()->{
                while(!server.isClosed()) try {
                    Socket socket=server.accept();clients.add(socket);
                    pool.submit(()->{
                        try(socket){
                            socket.setSoTimeout(3000);
                            var in=new DataInputStream(socket.getInputStream());
                            assertInstanceOf(WireCodec.Handshake.class,WireCodec.readRequest(in));
                            WireCodec.Request request;
                            do {request=WireCodec.readRequest(in);} while(request instanceof WireCodec.UploadReport);
                            handler.respond(socket,(WireCodec.ChunkRequest)request);
                        }catch(Exception ignored){}finally{clients.remove(socket);}
                    });
                }catch(IOException e){break;}
            });
        }
        PeerNodeInfo info(int count){
            Set<Integer> chunks=new HashSet<>();for(int i=0;i<count;i++)chunks.add(i);
            return new PeerNodeInfo(id,id,"127.0.0.1",server.getLocalPort(),chunks,Instant.now());
        }
        public void close() throws Exception {
            server.close();for(Socket socket:clients)socket.close();
            pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));
        }
    }
    static void send(Socket socket,int index,byte[] bytes) throws IOException {
        var out=new DataOutputStream(socket.getOutputStream());
        WireCodec.writeResponse(out,new WireCodec.Response(OK_DATA,index,bytes));out.flush();
    }
    final PeerIdentity self=new PeerIdentity("self","alice","127.0.0.1",6889);
    DownloadSettings settings(Duration deadline) {
        return new DownloadSettings(300,500,Duration.ofMillis(200),Duration.ofMillis(100),deadline);
    }
    @ParameterizedTest @ValueSource(ints={0,1,1024,1025,8199})
    void downloadsAndAdvertisesOnlyVerifiedChunks(int size) throws Exception {
        var f=fixture(size);var tracker=new Tracker(f.metadata());
        try(var source=new TestPeer("seed",(socket,r)->send(socket,r.chunkIndex(),f.chunk(r.chunkIndex())));
            var store=new ChunkStore(temp.resolve("download-"+size));
            var manager=new ParallelDownloadManager(self,store,tracker,new TitForTatManager(Clock.systemUTC(),new Random()),settings(Duration.ofSeconds(5)),e->{})) {
            tracker.peers=List.of(source.info(f.metadata().totalChunks()));
            Path path=manager.download(f.metadata().fileId());
            assertArrayEquals(f.bytes(),Files.readAllBytes(path));
            assertEquals(f.metadata().totalChunks(),store.get(f.metadata().fileId()).availableChunks().size());
            assertEquals(f.metadata().totalChunks(),tracker.heartbeats.getLast().availableChunks().size());
            manager.advertise(store.get(f.metadata().fileId()));
            assertEquals(size, tracker.heartbeats.getLast().downloaded());
            assertEquals(1, tracker.heartbeats.stream().filter(r -> "completed".equals(r.event())).count());
        }
        assertEquals("stopped", tracker.heartbeats.getLast().event());
        assertEquals(size, tracker.heartbeats.getLast().downloaded());
    }
    @Test void respectsTrackerSourceOrderingInsteadOfSortingSeederIds() throws Exception {
        var f=fixture(100);
        var firstCalls=new AtomicInteger();
        var secondCalls=new AtomicInteger();
        try(var a=new TestPeer("a",(socket,r)->{ firstCalls.incrementAndGet();send(socket,r.chunkIndex(),f.chunk(r.chunkIndex())); });
            var b=new TestPeer("b",(socket,r)->{ secondCalls.incrementAndGet();send(socket,r.chunkIndex(),f.chunk(r.chunkIndex())); });
            var store=new ChunkStore(temp.resolve("source-order"))) {
            var tracker=new Tracker(f.metadata()) {
                @Override public SourcePlan sources(String id,String requester,Set<Integer> have,int maximum) {
                    return new SourcePlan(id,List.of(new SourcePlan.ChunkSources(0,2,List.of(b.info(1),a.info(1)))),2,0,30);
                }
            };
            try(var manager=new ParallelDownloadManager(self,store,tracker,new TitForTatManager(Clock.systemUTC(),new Random()),settings(Duration.ofSeconds(5)),e->{})) {
                assertArrayEquals(f.bytes(),Files.readAllBytes(manager.download(f.metadata().fileId())));
                assertEquals(0,firstCalls.get());
                assertEquals(1,secondCalls.get());
            }
        }
    }
    @Test void usesFiveConcurrentConnectionsAndPositionalWrites() throws Exception {
        var f=fixture(16*1024+7);var tracker=new Tracker(f.metadata());
        CountDownLatch five=new CountDownLatch(5);
        AtomicInteger active=new AtomicInteger(),max=new AtomicInteger();
        List<TestPeer> sources=new ArrayList<>();
        try(var store=new ChunkStore(temp.resolve("parallel"));
            var manager=new ParallelDownloadManager(self,store,tracker,new TitForTatManager(Clock.systemUTC(),new Random()),settings(Duration.ofSeconds(8)),e->{})) {
            for(int i=0;i<5;i++)sources.add(new TestPeer("p"+i,(socket,r)->{
                max.accumulateAndGet(active.incrementAndGet(),Math::max);
                try {five.countDown();if(!five.await(3,TimeUnit.SECONDS))throw new IOException("not parallel");
                    send(socket,r.chunkIndex(),f.chunk(r.chunkIndex()));
                } finally {active.decrementAndGet();}
            }));
            tracker.peers=sources.stream().map(p->p.info(f.metadata().totalChunks())).toList();
            assertArrayEquals(f.bytes(),Files.readAllBytes(manager.download(f.metadata().fileId())));
            assertEquals(5,max.get());
        } finally {for(var peer:sources)peer.close();}
    }
    @ParameterizedTest @ValueSource(strings={"disconnect","corrupt","choked","wrong-index","truncated"})
    void failsOverToAnotherPeerWithoutAdvertisingBadChunk(String failure) throws Exception {
        var f=fixture(100);var tracker=new Tracker(f.metadata());
        List<ChunkDownloadState> events=new CopyOnWriteArrayList<>();
        try(var bad=new TestPeer("a-bad",(socket,r)->{
                if(failure.equals("disconnect"))return;
                var out=new DataOutputStream(socket.getOutputStream());
                if(failure.equals("choked"))WireCodec.writeResponse(out,new WireCodec.Response(CHOKED,-1,new byte[0]));
                else if(failure.equals("truncated")) {out.writeByte(OK_DATA);out.writeInt(0);out.writeInt(100);out.write(new byte[3]);}
                else send(socket,failure.equals("wrong-index")?1:0,failure.equals("corrupt")?new byte[100]:f.bytes());
                out.flush();
            });
            var good=new TestPeer("b-good",(socket,r)->send(socket,r.chunkIndex(),f.chunk(r.chunkIndex())));
            var store=new ChunkStore(temp.resolve(failure));
            var manager=new ParallelDownloadManager(self,store,tracker,new TitForTatManager(Clock.systemUTC(),new Random()),settings(Duration.ofSeconds(5)),events::add)) {
            tracker.peers=List.of(bad.info(1),good.info(1));
            assertArrayEquals(f.bytes(),Files.readAllBytes(manager.download(f.metadata().fileId())));
            assertTrue(events.stream().anyMatch(e->e.status()==ChunkDownloadState.Status.FAILED_RETRYING));
            var last=manager.states().getFirst();
            assertEquals("b-good",last.sourcePeerId());assertEquals(2,last.attemptCount());
            assertFalse(last.failoverHistory().isEmpty());
            assertEquals(failure.equals("corrupt") ? List.of("a-bad") : List.of(),tracker.badChunks);
            assertEquals(1,tracker.heartbeats.stream().filter(r->r.availableChunks().contains(0)).count());
        }
    }
    @Test void refreshesTrackerWhenInitiallyNoSources() throws Exception {
        var f=fixture(10);var tracker=new Tracker(f.metadata());
        var executor=Executors.newSingleThreadScheduledExecutor();
        try(var good=new TestPeer("later",(socket,r)->send(socket,r.chunkIndex(),f.bytes()));
            var store=new ChunkStore(temp.resolve("refresh"));
            var manager=new ParallelDownloadManager(self,store,tracker,new TitForTatManager(Clock.systemUTC(),new Random()),settings(Duration.ofSeconds(3)),e->{})) {
            executor.schedule(()->tracker.peers=List.of(good.info(1)),250,TimeUnit.MILLISECONDS);
            assertArrayEquals(f.bytes(),Files.readAllBytes(manager.download(f.metadata().fileId())));
        }finally{executor.shutdownNow();}
    }
    @Test void retriesRecoveredPeerAfterCooldown() throws Exception {
        var f=fixture(10);var tracker=new Tracker(f.metadata());var attempts=new AtomicInteger();
        try(var source=new TestPeer("recover",(socket,r)->{
                if(attempts.incrementAndGet()>1)send(socket,r.chunkIndex(),f.bytes());
            });
            var store=new ChunkStore(temp.resolve("recovered"));
            var manager=new ParallelDownloadManager(self,store,tracker,new TitForTatManager(Clock.systemUTC(),new Random()),settings(Duration.ofSeconds(3)),e->{})) {
            tracker.peers=List.of(source.info(1));
            assertArrayEquals(f.bytes(),Files.readAllBytes(manager.download(f.metadata().fileId())));
            assertEquals(2,attempts.get());
        }
    }
    @Test void prioritizesRareChunkBeforeCommonChunks() throws Exception {
        var f=fixture(8*1024);var tracker=new Tracker(f.metadata());
        var firstWave=ConcurrentHashMap.<Integer>newKeySet();
        CountDownLatch barrier=new CountDownLatch(5);
        try(var rare=new TestPeer("rare",(socket,r)->send(socket,r.chunkIndex(),f.chunk(r.chunkIndex())));
            var common=new TestPeer("common",(socket,r)->send(socket,r.chunkIndex(),f.chunk(r.chunkIndex())));
            var store=new ChunkStore(temp.resolve("rarest"));
            var manager=new ParallelDownloadManager(self,store,tracker,new TitForTatManager(Clock.systemUTC(),new Random()),settings(Duration.ofSeconds(5)),event->{
                if(event.status()==ChunkDownloadState.Status.DOWNLOADING && barrier.getCount()>0) {
                    firstWave.add(event.chunkIndex());barrier.countDown();
                    try {if(!barrier.await(2,TimeUnit.SECONDS))throw new AssertionError("Five workers did not start");}
                    catch(InterruptedException e){Thread.currentThread().interrupt();}
                }
            })) {
            tracker.peers=List.of(rare.info(8),common.info(7));
            assertArrayEquals(f.bytes(),Files.readAllBytes(manager.download(f.metadata().fileId())));
            assertTrue(firstWave.contains(7),"rarest chunk must be in first wave");
        }
    }
    @Test void deadlineStopsStalledSocketsAndKeepsUnavailableChunksUnadvertised() throws Exception {
        var f=fixture(10);var tracker=new Tracker(f.metadata());
        try(var stalled=new TestPeer("stall",(socket,r)->{Thread.sleep(5000);});
            var store=new ChunkStore(temp.resolve("timeout"));
            var manager=new ParallelDownloadManager(self,store,tracker,new TitForTatManager(Clock.systemUTC(),new Random()),settings(Duration.ofMillis(600)),e->{})) {
            tracker.peers=List.of(stalled.info(1));
            assertThrows(IOException.class,()->manager.download(f.metadata().fileId()));
            assertTrue(store.get(f.metadata().fileId()).availableChunks().isEmpty());
            assertTrue(manager.awaitIdle(Duration.ofSeconds(2)));
        }
    }
    @Test void cancelClosesInFlightDownloadAndReleasesWorkers() throws Exception {
        var f=fixture(10);var tracker=new Tracker(f.metadata());
        CountDownLatch started=new CountDownLatch(1);
        var caller=Executors.newSingleThreadExecutor();
        try(var stalled=new TestPeer("stall",(socket,r)->{started.countDown();Thread.sleep(5000);});
            var store=new ChunkStore(temp.resolve("cancel"));
            var manager=new ParallelDownloadManager(self,store,tracker,new TitForTatManager(Clock.systemUTC(),new Random()),settings(Duration.ofSeconds(10)),e->{})) {
            tracker.peers=List.of(stalled.info(1));
            Future<Path> task=caller.submit(()->manager.download(f.metadata().fileId()));
            assertTrue(started.await(3,TimeUnit.SECONDS));
            manager.cancel();
            var error=assertThrows(ExecutionException.class,()->task.get(3,TimeUnit.SECONDS));
            assertInstanceOf(IOException.class,error.getCause());
            assertTrue(manager.awaitIdle(Duration.ofSeconds(1)));
            assertTrue(store.get(f.metadata().fileId()).availableChunks().isEmpty());
        }finally{caller.shutdownNow();}
    }
    @Test void cancelDuringMetadataLookupPreventsAllocationAndTransfer() throws Exception {
        var f=fixture(10);CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        Tracker tracker=new Tracker(f.metadata()) {
            @Override public TorrentMetadata metadata(String id) {
                entered.countDown();
                try {release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}
                return metadata;
            }
        };
        var caller=Executors.newSingleThreadExecutor();
        try(var store=new ChunkStore(temp.resolve("cancel-lookup"));
            var manager=new ParallelDownloadManager(self,store,tracker,new TitForTatManager(Clock.systemUTC(),new Random()),settings(Duration.ofMillis(500)),e->{})) {
            Future<Path> task=caller.submit(()->manager.download(f.metadata().fileId()));
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            manager.cancel();release.countDown();
            assertThrows(ExecutionException.class,()->task.get(2,TimeUnit.SECONDS));
            assertNull(store.get(f.metadata().fileId()));
        }finally{release.countDown();caller.shutdownNow();}
    }
    @Test void queuedCancellationIsObservedBeforeStartingMetadataLookup() throws Exception {
        var f=fixture(10);var queried=new AtomicBoolean();
        var tracker=new Tracker(f.metadata()) {
            @Override public TorrentMetadata metadata(String id) { queried.set(true);return metadata; }
        };
        try(var store=new ChunkStore(temp.resolve("cancel-queued"));
            var manager=new ParallelDownloadManager(self,store,tracker,new TitForTatManager(Clock.systemUTC(),new Random()),settings(Duration.ofSeconds(2)),e->{})) {
            assertThrows(IOException.class,()->manager.download(f.metadata().fileId(),()->true));
            assertFalse(queried.get());assertNull(store.get(f.metadata().fileId()));
        }
    }
}
