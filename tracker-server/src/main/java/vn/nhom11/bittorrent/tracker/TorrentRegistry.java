package vn.nhom11.bittorrent.tracker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import vn.nhom11.bittorrent.shared.TorrentMetadata;

@Service
public class TorrentRegistry {
 private final TorrentRepository torrents; private final ObjectMapper json;
 public TorrentRegistry(TorrentRepository torrents,ObjectMapper json){this.torrents=torrents;this.json=json;}
 public synchronized TorrentMetadata register(TorrentMetadata metadata) {
   var existing=torrents.findById(metadata.fileId());
   if(existing.isPresent()) {
     if(!decode(existing.get()).equals(metadata)) throw new ResponseStatusException(HttpStatus.CONFLICT,"Conflicting metadata for fileId");
     return metadata;
   }
   try { torrents.saveAndFlush(new TorrentEntity(metadata.fileId(),json.writeValueAsString(metadata))); return metadata; }
   catch(JsonProcessingException e){throw new IllegalStateException(e);}
 }
 public TorrentMetadata get(String id) {
   return decode(torrents.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Torrent not found")));
 }
 public List<TorrentMetadata> list(){return torrents.findAll().stream().map(this::decode).sorted(java.util.Comparator.comparing(TorrentMetadata::fileName)).toList();}
 private TorrentMetadata decode(TorrentEntity entity) {
   try{return json.readValue(entity.getMetadataJson(),TorrentMetadata.class);}
   catch(JsonProcessingException e){throw new IllegalStateException("Stored metadata is invalid",e);}
 }
}
