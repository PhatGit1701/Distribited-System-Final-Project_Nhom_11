package vn.nhom11.bittorrent.tracker.service;

import vn.nhom11.bittorrent.tracker.repository.RedisAccountingCheckpoint;

public final class AccountingFixtures {
    private AccountingFixtures() {}
    public static CumulativeTransferAccounting withCheckpoint(TransferStatsBuffer stats, RedisAccountingCheckpoint checkpoint) {
        return new CumulativeTransferAccounting(stats, 0, 0, checkpoint);
    }
}
