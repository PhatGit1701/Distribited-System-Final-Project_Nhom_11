package vn.nhom11.bittorrent.tracker.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Volatile batching: a process crash can lose the unflushed interval. */
@Service
public class TransferStatsBuffer {
    private static final Logger log=LoggerFactory.getLogger(TransferStatsBuffer.class);
    public record Key(long userId,String fileId) {}
    public record Batch(UUID id,Key key,long uploaded,long downloaded,long verifiedUploaded,long verifiedDownloaded,
                        Instant startedAt,Instant completedAt,Instant stoppedAt,Map<String,Instant> suspiciousUntil) {}
    private final ConcurrentHashMap<Key,Batch> pending=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Key,Batch> retry=new ConcurrentHashMap<>();
    private final Object[] flushLocks=new Object[64];
    private final TransferStatsWriter writer;
    private final Clock clock;

    public TransferStatsBuffer(TransferStatsWriter writer,Clock clock) {
        this.writer=writer;this.clock=clock;
        Arrays.setAll(flushLocks,i->new Object());
    }

    public void record(long userId,String fileId,long uploaded,long downloaded) {
        if(uploaded<0||downloaded<0) throw new IllegalArgumentException("Transfer bytes must be nonnegative");
        var key=key(userId,fileId);
        if(uploaded==0&&downloaded==0)return;
        add(new Batch(UUID.randomUUID(),key,uploaded,downloaded,0,0,null,null,null,Map.of()));
    }

    public void recordVerified(long userId,String fileId,long uploaded,long downloaded) {
        if(uploaded<0||downloaded<0)throw new IllegalArgumentException("Verified bytes must be nonnegative");
        var key=key(userId,fileId);
        if(uploaded==0&&downloaded==0)return;
        add(new Batch(UUID.randomUUID(),key,0,0,uploaded,downloaded,null,null,null,Map.of()));
    }

    public void markSuspicious(long userId,String fileId,String peerId) {
        add(new Batch(UUID.randomUUID(),key(userId,fileId),0,0,0,0,null,null,null,
                Map.of(peerId,clock.instant().plusSeconds(600))));
    }

    public void started(long userId,String fileId) {event(userId,fileId,"started");}
    public void completed(long userId,String fileId) {event(userId,fileId,"completed");}
    public void stopped(long userId,String fileId) {event(userId,fileId,"stopped");}

    private void event(long userId,String fileId,String event) {
        var key=key(userId,fileId);
        var now=clock.instant();
        add(new Batch(UUID.randomUUID(),key,0,0,0,0,event.equals("started")?now:null,
                event.equals("completed")?now:null,event.equals("stopped")?now:null,Map.of()));
        flush(key);
    }

    private static Key key(long userId,String fileId) {
        if(userId<=0||fileId==null||!fileId.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid statistics identity");
        return new Key(userId,fileId);
    }

    private void add(Batch delta) {
        pending.merge(delta.key(),delta,(a,b)->new Batch(a.id(),a.key(),
                Math.addExact(a.uploaded(),b.uploaded()),Math.addExact(a.downloaded(),b.downloaded()),
                Math.addExact(a.verifiedUploaded(),b.verifiedUploaded()),Math.addExact(a.verifiedDownloaded(),b.verifiedDownloaded()),
                latest(a.startedAt(),b.startedAt()),latest(a.completedAt(),b.completedAt()),latest(a.stoppedAt(),b.stoppedAt()),
                mergeSuspicion(a.suspiciousUntil(),b.suspiciousUntil())));
    }

    private static Map<String,Instant> mergeSuspicion(Map<String,Instant> a,Map<String,Instant> b) {
        var merged=new HashMap<>(a);b.forEach((peer,until)->merged.merge(peer,until,TransferStatsBuffer::latest));
        return Map.copyOf(merged);
    }

    private static Instant latest(Instant a,Instant b) {return a==null?b:b==null?a:a.isAfter(b)?a:b;}

    @Scheduled(fixedDelay=45000,initialDelay=45000)
    public void flushPending() {
        Set<Key> keys=new HashSet<>(retry.keySet());
        keys.addAll(pending.keySet());
        for(var key:keys) {
            try {flush(key);}
            catch(RuntimeException failure) {log.warn("Transfer statistics flush failed; batch retained for retry",failure);}
        }
    }

    private void flush(Key key) {
        // Serialize only flushes; heartbeat recording never waits for PostgreSQL.
        synchronized(flushLocks[(key.hashCode()&Integer.MAX_VALUE)%flushLocks.length]) {
            Batch previous=retry.get(key);
            if(previous!=null) {
                writer.write(previous);
                retry.remove(key,previous);
            }
            Batch batch=pending.remove(key);
            if(batch==null)return;
            // Publish the stable retry identity before attempting any I/O.
            retry.put(key,batch);
            writer.write(batch);
            retry.remove(key,batch);
        }
    }
}
