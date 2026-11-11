package vn.nhom11.bittorrent.peer;
import vn.nhom11.bittorrent.peer.service.TitForTatManager;

import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class ReputationTieTest {
    @Test void reputationBreaksSimilarSpeedTieButDoesNotOverrideClearlyFasterPeers() {
        var policy=new TitForTatManager(Clock.systemUTC(),new Random(1));
        for(String p: new String[]{"fast1","fast2","fast3"}) policy.recordContribution(p,2000);
        policy.recordContribution("a",1000);
        policy.recordContribution("b",950);
        policy.reputation("b",90.0);
        policy.recordContribution("slow",100);
        policy.reputation("slow",100.0);
        policy.recalculate();
        assertTrue(policy.unchokedPeers().contains("b"));
        assertFalse(policy.unchokedPeers().contains("a"));
        assertFalse(policy.unchokedPeers().contains("slow"));
    }
}
