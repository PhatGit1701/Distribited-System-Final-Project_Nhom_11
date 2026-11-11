package vn.nhom11.bittorrent.tracker.service;
import vn.nhom11.bittorrent.tracker.repository.TorrentRepository;
import vn.nhom11.bittorrent.tracker.repository.UserRepository;
import vn.nhom11.bittorrent.tracker.model.TorrentEntity;
import vn.nhom11.bittorrent.shared.exception.*;

import org.springframework.stereotype.Service;
import org.springframework.dao.DataIntegrityViolationException;
import java.util.List;
import vn.nhom11.bittorrent.shared.TorrentMetadata;

@Service
public class TorrentRegistry {
    private final TorrentRepository torrents;
    private final UserRepository users;
    private final org.springframework.context.ApplicationEventPublisher events;
    public TorrentRegistry(TorrentRepository torrents, UserRepository users, org.springframework.context.ApplicationEventPublisher events) {
        this.torrents=torrents; this.users=users; this.events=events;
    }
    public synchronized TorrentMetadata register(TorrentMetadata metadata, long ownerId) {
        if (metadata.fileSize() <= 0) throw new IllegalArgumentException("File must not be empty");
        var existing=torrents.findById(metadata.fileId());
        if (existing.isPresent()) return checkExisting(existing.get(), metadata);
        var owner=users.findById(ownerId).orElseThrow(() ->
                new AppException(ErrorCode.AUTHENTICATION_REQUIRED, "User no longer exists"));
        try { torrents.saveAndFlush(new TorrentEntity(metadata,owner)); }
        catch (DataIntegrityViolationException e) {
            var concurrent=torrents.findById(metadata.fileId());
            if (concurrent.isPresent()) return checkExisting(concurrent.get(),metadata);
            throw e;
        }
        events.publishEvent(new SwarmMetadataCache.Registered(metadata));
        return metadata;
    }
    private TorrentMetadata checkExisting(TorrentEntity existing, TorrentMetadata metadata) {
        if (!existing.toMetadata().equals(metadata))
            throw new AppException(ErrorCode.CONFLICT, "Conflicting metadata for fileId");
        events.publishEvent(new SwarmMetadataCache.Registered(metadata));
        return metadata;
    }
    public TorrentMetadata get(String id) {
        return torrents.findById(id).orElseThrow(() ->
                new AppException(ErrorCode.TORRENT_NOT_FOUND, "Torrent not found")).toMetadata();
    }
    public List<TorrentMetadata> list() {
        return torrents.findAll().stream().map(TorrentEntity::toMetadata)
                .sorted(java.util.Comparator.comparing(TorrentMetadata::fileName)).toList();
    }
}
