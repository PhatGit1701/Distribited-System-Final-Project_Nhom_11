package vn.nhom11.bittorrent.tracker.service;
import vn.nhom11.bittorrent.tracker.repository.RedisAccountingCheckpoint;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import vn.nhom11.bittorrent.shared.HeartbeatRequest;
import java.util.*;

/** Redis checkpoints preserve baselines and serialize updates across production tracker instances.
 * Dev/test without a checkpoint keeps state in memory.
 * A session plus sequence is required to distinguish counter resets from stale reports.
 */
@Service
public class CumulativeTransferAccounting {
    private record Actor(long userId,String peerId) {}
    private record Session(String file,Actor actor,String session) {}
    private record Direction(String file,Actor uploader,Actor downloader) {}
    private record Counters(long uploaded,long downloaded) {}
    private static class State {
        long sequence=-1,uploaded,downloaded,totalUploaded,totalDownloaded,claimedUploaded,claimedDownloaded;
        final Map<Actor,Counters> counterparts=new HashMap<>();
    }
    private static class Match {long sent,received,credited; boolean senderReported,receiverReported;}
    private record Claim(Actor other,long uploaded,long downloaded,long uploadedDelta,long downloadedDelta) {}
    private final Map<Session,State> sessions=new HashMap<>();
    private final Map<Direction,Match> matches=new HashMap<>();
    private final TransferStatsBuffer stats;
    private final long toleranceBytes;
    private final double toleranceFraction;
    private final RedisAccountingCheckpoint checkpoint;
    private final com.fasterxml.jackson.databind.ObjectMapper json=new com.fasterxml.jackson.databind.ObjectMapper();
    private record CounterRow(Actor actor,Counters counters) {}
    private record StateRow(Session session,long sequence,long uploaded,long downloaded,long totalUploaded,long totalDownloaded,
                            long claimedUploaded,long claimedDownloaded,List<CounterRow> counterparts) {}
    private record MatchRow(Direction direction,long sent,long received,long credited,boolean senderReported,boolean receiverReported) {}
    private record Snapshot(List<StateRow> sessions,List<MatchRow> matches) {}

    public CumulativeTransferAccounting(TransferStatsBuffer stats,
            @Value("${tracker.accounting.tolerance-bytes:65536}") long toleranceBytes,
            @Value("${tracker.accounting.tolerance-fraction:0.05}") double toleranceFraction) {
        this(stats,toleranceBytes,toleranceFraction,(RedisAccountingCheckpoint)null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public CumulativeTransferAccounting(TransferStatsBuffer stats,
            @Value("${tracker.accounting.tolerance-bytes:65536}") long toleranceBytes,
            @Value("${tracker.accounting.tolerance-fraction:0.05}") double toleranceFraction,
            org.springframework.beans.factory.ObjectProvider<RedisAccountingCheckpoint> checkpoints) {
        this(stats,toleranceBytes,toleranceFraction,checkpoints.getIfAvailable());
    }
    CumulativeTransferAccounting(TransferStatsBuffer stats,long toleranceBytes,double toleranceFraction,RedisAccountingCheckpoint checkpoint) {
        if(toleranceBytes<0||!Double.isFinite(toleranceFraction)||toleranceFraction<0||toleranceFraction>1)
            throw new IllegalArgumentException("Invalid transfer reconciliation tolerance");
        this.stats=stats;this.toleranceBytes=toleranceBytes;this.toleranceFraction=toleranceFraction;this.checkpoint=checkpoint;
    }

    public static void validate(HeartbeatRequest r) {
        if(r.uploaded()<0||r.downloaded()<0||r.sequence()<0)throw new IllegalArgumentException("Counters and sequence must be nonnegative");
        if(r.sessionId()!=null&&!r.sessionId().matches("[a-zA-Z0-9_-]{1,128}"))throw new IllegalArgumentException("Invalid counter session");
        if(r.transfers().size()>10000)throw new IllegalArgumentException("Too many transfer counterparts");
        long uploaded=0,downloaded=0;
        Set<String> seen=new HashSet<>();
        try {
            for(var t:r.transfers()) {
                if(t.peerId()==null||!t.peerId().matches("[a-zA-Z0-9_-]{1,128}")||t.peerId().equals(r.peerId())
                        ||t.uploadedTo()<0||t.downloadedFrom()<0||!seen.add(t.peerId()))throw new IllegalArgumentException("Invalid counterpart counters");
                uploaded=Math.addExact(uploaded,t.uploadedTo());downloaded=Math.addExact(downloaded,t.downloadedFrom());
            }
        } catch(ArithmeticException overflow){throw new IllegalArgumentException("Transfer counters overflow",overflow);}
        if(uploaded>r.uploaded()||downloaded>r.downloaded())throw new IllegalArgumentException("Counterpart claims exceed reported totals");
    }

    /** owners is a snapshot from the runtime swarm, never supplied by the requesting client. */
    public synchronized void record(long authenticatedUserId,HeartbeatRequest r,Map<String,Long> owners) {
        validate(r);
        if(checkpoint==null){apply(authenticatedUserId,r,owners).forEach(Runnable::run);return;}
        for(int attempt=0;attempt<10;attempt++) {
            String before=checkpoint.read(r.fileId());
            restore(r.fileId(),before);
            var effects=apply(authenticatedUserId,r,owners);
            if(checkpoint.save(r.fileId(),before,snapshot(r.fileId()))) {
                effects.forEach(Runnable::run);
                return;
            }
        }
        throw new IllegalStateException("Concurrent accounting updates; retry heartbeat");
    }
    private List<Runnable> apply(long authenticatedUserId,HeartbeatRequest r,Map<String,Long> owners) {
        List<Runnable> effects=new ArrayList<>();
        var actor=new Actor(authenticatedUserId,r.peerId());
        var key=new Session(r.fileId(),actor,r.sessionId()==null?"legacy":r.sessionId());
        var state=sessions.computeIfAbsent(key,k->new State());
        if(r.sessionId()!=null&&r.sequence()<=state.sequence)return effects;
        long uploaded=delta(r.uploaded(),state.uploaded),downloaded=delta(r.downloaded(),state.downloaded);
        long totalUploaded=Math.addExact(state.totalUploaded,uploaded),totalDownloaded=Math.addExact(state.totalDownloaded,downloaded);
        long claimedUploaded=state.claimedUploaded,claimedDownloaded=state.claimedDownloaded;
        List<Claim> claims=new ArrayList<>();
        for(var t:r.transfers()) {
            Long user=owners.get(t.peerId());
            if(user==null||user==authenticatedUserId)continue;
            var other=new Actor(user,t.peerId());
            var old=state.counterparts.getOrDefault(other,new Counters(0,0));
            long up=delta(t.uploadedTo(),old.uploaded()),down=delta(t.downloadedFrom(),old.downloaded());
            claimedUploaded=Math.addExact(claimedUploaded,up);claimedDownloaded=Math.addExact(claimedDownloaded,down);
            claims.add(new Claim(other,t.uploadedTo(),t.downloadedFrom(),up,down));
        }
        if(claimedUploaded>totalUploaded||claimedDownloaded>totalDownloaded)
            throw new IllegalArgumentException("Reset counterpart claims exceed cumulative session bytes");
        effects.add(()->stats.record(authenticatedUserId,r.fileId(),uploaded,downloaded));
        state.uploaded=r.uploaded();state.downloaded=r.downloaded();state.sequence=r.sequence();
        state.totalUploaded=totalUploaded;state.totalDownloaded=totalDownloaded;
        state.claimedUploaded=claimedUploaded;state.claimedDownloaded=claimedDownloaded;
        for(var claim:claims) {
            state.counterparts.put(claim.other(),new Counters(claim.uploaded(),claim.downloaded()));
            reconcile(new Direction(r.fileId(),actor,claim.other()),claim.uploadedDelta(),true,effects);
            reconcile(new Direction(r.fileId(),claim.other(),actor),claim.downloadedDelta(),false,effects);
        }
        return effects;
    }

    private static long delta(long current,long previous){return current>=previous?current-previous:current;}

    private void reconcile(Direction direction,long increment,boolean sender,List<Runnable> effects) {
        var match=matches.computeIfAbsent(direction,k->new Match());
        if(sender){match.sent=Math.addExact(match.sent,increment);match.senderReported=true;}
        else {match.received=Math.addExact(match.received,increment);match.receiverReported=true;}
        if(!match.senderReported||!match.receiverReported)return;
        long higher=Math.max(match.sent,match.received),lower=Math.min(match.sent,match.received);
        double tolerance=Math.max(toleranceBytes,higher*toleranceFraction);
        if(higher-lower>tolerance) {
            // A discrepancy is not evidence of which side lied; mark both, never ban automatically.
            effects.add(()->stats.markSuspicious(direction.uploader().userId(),direction.file(),direction.uploader().peerId()));
            effects.add(()->stats.markSuspicious(direction.downloader().userId(),direction.file(),direction.downloader().peerId()));
            return;
        }
        long verified=lower-match.credited;
        if(verified<=0)return;
        effects.add(()->stats.recordVerified(direction.uploader().userId(),direction.file(),verified,0));
        effects.add(()->stats.recordVerified(direction.downloader().userId(),direction.file(),0,verified));
        match.credited=lower;
    }
    private String snapshot(String file) {
        var states=sessions.entrySet().stream().filter(e->e.getKey().file().equals(file)).map(e->{var s=e.getValue();
            return new StateRow(e.getKey(),s.sequence,s.uploaded,s.downloaded,s.totalUploaded,s.totalDownloaded,s.claimedUploaded,s.claimedDownloaded,
                    s.counterparts.entrySet().stream().map(c->new CounterRow(c.getKey(),c.getValue())).toList());}).toList();
        var directions=matches.entrySet().stream().filter(e->e.getKey().file().equals(file)).map(e->{var m=e.getValue();
            return new MatchRow(e.getKey(),m.sent,m.received,m.credited,m.senderReported,m.receiverReported);}).toList();
        try{return json.writeValueAsString(new Snapshot(states,directions));}
        catch(Exception failure){throw new IllegalStateException("Cannot encode accounting checkpoint",failure);}
    }
    private void restore(String file,String saved) {
        sessions.keySet().removeIf(k->k.file().equals(file));matches.keySet().removeIf(k->k.file().equals(file));
        if(saved==null)return;
        try {
            var snapshot=json.readValue(saved,Snapshot.class);
            for(var row:snapshot.sessions()) {var s=new State();s.sequence=row.sequence();s.uploaded=row.uploaded();s.downloaded=row.downloaded();
                s.totalUploaded=row.totalUploaded();s.totalDownloaded=row.totalDownloaded();s.claimedUploaded=row.claimedUploaded();s.claimedDownloaded=row.claimedDownloaded();
                row.counterparts().forEach(c->s.counterparts.put(c.actor(),c.counters()));sessions.put(row.session(),s);}
            for(var row:snapshot.matches()){var m=new Match();m.sent=row.sent();m.received=row.received();m.credited=row.credited();
                m.senderReported=row.senderReported();m.receiverReported=row.receiverReported();matches.put(row.direction(),m);}
        }catch(Exception failure){throw new IllegalStateException("Invalid accounting checkpoint",failure);}
    }
}
