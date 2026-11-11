package vn.nhom11.bittorrent.peer.service;

import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.random.RandomGenerator;

/** A real trailing ten-second contribution window, sampled by the scheduler. */
public final class TitForTatManager {
    private record Sample(Instant time, long bytes) {}
    private final Map<String, Deque<Sample>> contributions = new HashMap<>();
    private final Map<String, Long> receivedTotals = new HashMap<>(), reportedTotals = new HashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> uploaded = new ConcurrentHashMap<>();
    private final Clock clock;
    private final RandomGenerator random;
    private Set<String> unchoked = Set.of();
    private String optimistic;
    private Instant lastRotation;
    private final Map<String,Double> reputations = new HashMap<>();
    public synchronized void reputation(String peerId,Double score) {
        if (score != null && Double.isFinite(score) && score >= 0 && score <= 100) reputations.put(peerId,score);
        else reputations.remove(peerId);
    }

    public TitForTatManager(Clock clock, RandomGenerator random) { this.clock = clock; this.random = random; }
    public synchronized void registerPeer(String peerId) { contributions.computeIfAbsent(peerId, k -> new ArrayDeque<>()); }
    public synchronized void recordContribution(String peerId, long bytes) {
        if (bytes < 0) throw new IllegalArgumentException("Negative contribution");
        registerPeer(peerId);
        long received = receivedTotals.getOrDefault(peerId, 0L);
        long reported = reportedTotals.getOrDefault(peerId, 0L);
        long updated = Long.MAX_VALUE - received < bytes ? Long.MAX_VALUE : received + bytes;
        receivedTotals.put(peerId, updated);
        credit(peerId, Math.max(updated, reported) - Math.max(received, reported));
    }
    public synchronized void recordReportedContribution(String peerId, long cumulativeBytes) {
        if (cumulativeBytes < 0) throw new IllegalArgumentException("Negative contribution");
        registerPeer(peerId);
        long received = receivedTotals.getOrDefault(peerId, 0L);
        long previous = reportedTotals.getOrDefault(peerId, 0L);
        long updated = Math.max(previous, cumulativeBytes);
        reportedTotals.put(peerId, updated);
        credit(peerId, Math.max(received, updated) - Math.max(received, previous));
    }
    private void credit(String peerId, long bytes) {
        if (bytes > 0) contributions.get(peerId).addLast(new Sample(clock.instant(), bytes));
    }
    public synchronized long contribution(String peerId) {
        Deque<Sample> samples = contributions.get(peerId);
        if (samples == null) return 0;
        Instant cutoff = clock.instant().minusSeconds(10);
        while (!samples.isEmpty() && !samples.peekFirst().time().isAfter(cutoff)) samples.removeFirst();
        long sum = 0;
        for (var sample : samples) {
            if (Long.MAX_VALUE - sum < sample.bytes()) return Long.MAX_VALUE;
            sum += sample.bytes();
        }
        return sum;
    }
    public synchronized void recalculate() {
        Map<String, Long> scores = new HashMap<>();
        for (String peer : contributions.keySet()) scores.put(peer, contribution(peer));
        List<String> bySpeed = scores.keySet().stream()
                .sorted(Comparator.<String>comparingLong(scores::get).reversed().thenComparing(p -> p)).toList();
        List<String> ranked = new ArrayList<>();
        for (int start=0; start<bySpeed.size();) {
            int end=start+1;
            double minimum=scores.get(bySpeed.get(start))*0.9;
            while(end<bySpeed.size() && scores.get(bySpeed.get(end))>=minimum) end++;
            var equivalent=new ArrayList<>(bySpeed.subList(start,end));
            equivalent.sort(Comparator.<String>comparingDouble(p -> reputations.getOrDefault(p,0.0)).reversed()
                    .thenComparing(Comparator.<String>comparingLong(scores::get).reversed()).thenComparing(p -> p));
            ranked.addAll(equivalent); start=end;
        }
        unchoked = Set.copyOf(ranked.stream().limit(4).toList());
        Instant now = clock.instant();
        if (optimistic == null || unchoked.contains(optimistic) || lastRotation == null
                || Duration.between(lastRotation, now).getSeconds() >= 30) {
            List<String> candidates = new ArrayList<>(ranked.stream().filter(p -> !unchoked.contains(p)).toList());
            if (candidates.size() > 1) candidates.remove(optimistic);
            optimistic = candidates.isEmpty() ? null : candidates.get(random.nextInt(candidates.size()));
            lastRotation = now;
        }
    }
    public synchronized Set<String> unchokedPeers() { return unchoked; }
    public synchronized String optimisticPeer() { return optimistic; }
    public synchronized boolean mayUpload(String peerId, boolean completeSeeder) {
        return completeSeeder || unchoked.contains(peerId) || Objects.equals(optimistic, peerId);
    }
    public void recordUpload(String peerId, long bytes) {
        uploaded.computeIfAbsent(peerId, k -> new AtomicLong()).addAndGet(bytes);
    }
    public long uploadedTo(String peerId) {
        var count = uploaded.get(peerId); return count == null ? 0 : count.get();
    }
}
