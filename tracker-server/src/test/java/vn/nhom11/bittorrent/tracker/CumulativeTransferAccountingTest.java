package vn.nhom11.bittorrent.tracker;
import vn.nhom11.bittorrent.tracker.service.TransferStatsWriter;
import vn.nhom11.bittorrent.tracker.service.TransferStatsBuffer;
import vn.nhom11.bittorrent.tracker.service.CumulativeTransferAccounting;

import org.junit.jupiter.api.Test;
import vn.nhom11.bittorrent.shared.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class CumulativeTransferAccountingTest {
    static final String FILE="a".repeat(64);
    static class Totals extends TransferStatsBuffer {
        final Map<Long,long[]> totals=new ConcurrentHashMap<>();
        Totals(){super(mock(TransferStatsWriter.class),Clock.systemUTC());}
        @Override public synchronized void record(long user,String file,long up,long down){var t=totals.computeIfAbsent(user,k->new long[4]);t[0]+=up;t[1]+=down;}
        @Override public synchronized void recordVerified(long user,String file,long up,long down){var t=totals.computeIfAbsent(user,k->new long[4]);t[2]+=up;t[3]+=down;}
        long[] get(long user){return totals.getOrDefault(user,new long[4]);}
    }
    final Totals stats=new Totals();
    final CumulativeTransferAccounting accounting=new CumulativeTransferAccounting(stats,65536,0.05);
    final Map<String,Long> owners=Map.of("A",1L,"B",2L);

    HeartbeatRequest report(String peer,String session,long sequence,long uploaded,long downloaded,TransferReport... transfers){
        return new HeartbeatRequest(peer,peer,"127.0.0.1",8000,FILE,Set.of(),null,uploaded,downloaded,session,sequence,List.of(transfers));
    }
    @Test void accumulatesDeltasAndTreatsCounterResetAsNewNonnegativeBytes(){
        accounting.record(1,report("A","s",1,100,50),owners);
        accounting.record(1,report("A","s",2,150,70),owners);
        accounting.record(1,report("A","s",3,10,5),owners);
        accounting.record(1,report("A","s",3,10,5),owners);
        accounting.record(1,report("A","s",2,150,70),owners);
        assertArrayEquals(new long[]{160,75,0,0},stats.get(1));
    }
    @Test void mismatchesDoNotEarnRatioAndLateMatchingCreditsOnlyOnce(){
        accounting.record(1,report("A","s",1,0,1_000_000,new TransferReport("B",0,1_000_000)),owners);
        accounting.record(2,report("B","t",1,100_000,0,new TransferReport("A",100_000,0)),owners);
        assertEquals(0,stats.get(1)[3]);assertEquals(0,stats.get(2)[2]);
        accounting.record(2,report("B","t",2,990_000,0,new TransferReport("A",990_000,0)),owners);
        assertEquals(990_000,stats.get(1)[3]);assertEquals(990_000,stats.get(2)[2]);
        accounting.record(2,report("B","t",3,1_000_000,0,new TransferReport("A",1_000_000,0)),owners);
        accounting.record(1,report("A","s",2,0,1_000_000,new TransferReport("B",0,1_000_000)),owners);
        assertEquals(1_000_000,stats.get(1)[3]);assertEquals(1_000_000,stats.get(2)[2]);
    }
    @Test void sessionsAndCounterpartOwnershipAreIsolatedAndUnknownPeersEarnNothing(){
        accounting.record(1,report("A","s",1,10,0,new TransferReport("B",10,0)),Map.of());
        accounting.record(2,report("B","t",1,0,10,new TransferReport("A",0,10)),owners);
        assertEquals(0,stats.get(2)[3]);
        accounting.record(1,report("A","s",2,10,0,new TransferReport("B",10,0)),owners);
        assertEquals(10,stats.get(1)[2]);
        accounting.record(1,report("A","new-session",1,5,0,new TransferReport("B",5,0)),owners);
        accounting.record(2,report("B","t",2,0,15,new TransferReport("A",0,15)),owners);
        assertArrayEquals(new long[]{15,0,15,0},stats.get(1));
    }
    @Test void concurrentOutOfOrderReportsNeverLoseOrDuplicateTotals()throws Exception{
        try(var pool=Executors.newFixedThreadPool(8)){
            List<Future<?>> jobs=new ArrayList<>();
            for(int i=1;i<=300;i++){int n=i;jobs.add(pool.submit(()->accounting.record(1,report("A","s",n,n,0),owners)));}
            for(var job:jobs)job.get(10,TimeUnit.SECONDS);
        }
        assertArrayEquals(new long[]{300,0,0,0},stats.get(1));
    }
    @Test void rejectsNegativeAndCounterpartClaimsExceedingRawTotals(){
        assertThrows(IllegalArgumentException.class,()->accounting.record(1,report("A","s",1,-1,0),owners));
        assertThrows(IllegalArgumentException.class,()->accounting.record(1,report("A","s",1,10,0,new TransferReport("B",11,0)),owners));
        assertArrayEquals(new long[4],stats.get(1));
    }
}
