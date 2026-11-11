package vn.nhom11.bittorrent.tracker.repository;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@Profile({"dev", "test"})
public class InMemorySwarmRepository implements SwarmRepository {
    private final ConcurrentHashMap<String, Integer> metadata = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Peer>> swarms = new ConcurrentHashMap<>();
    private final Clock clock;
    public InMemorySwarmRepository(Clock clock){this.clock=clock;}
    public void registerMetadata(String fileId,int count){if(count<0)throw new IllegalArgumentException("Invalid chunk count");metadata.put(fileId,count);}
    public OptionalInt chunkCount(String id){Integer count=metadata.get(id);return count==null?OptionalInt.empty():OptionalInt.of(count);}
    private boolean live(Peer peer){return peer.lastSeen().plus(PEER_TTL).isAfter(clock.instant());}
    private void owner(Peer peer,String user){if(!peer.userId().equals(user))throw new SecurityException("Peer belongs to another user");}
    private void validate(String file,Set<Integer> chunks){int count=chunkCount(file).orElseThrow(()->new IllegalArgumentException("Unknown file"));if(chunks.stream().anyMatch(i->i==null||i<0||i>=count))throw new IllegalArgumentException("Invalid chunks");}
    public Peer register(Peer peer){
        validate(peer.fileId(),peer.chunks());
        return swarms.computeIfAbsent(peer.fileId(),key->new ConcurrentHashMap<>()).compute(peer.peerId(),(id,old)->{
            var chunks=new HashSet<>(peer.chunks());
            if(old!=null&&live(old)){owner(old,peer.userId());chunks.addAll(old.chunks());}
            return new Peer(peer.fileId(),peer.peerId(),peer.userId(),peer.username(),peer.ip(),peer.port(),chunks,clock.instant());
        });
    }
    private Optional<Peer> change(String file,String id,String user,String ip,int port,Set<Integer> additions,boolean remove){
        var swarm=swarms.get(file);if(swarm==null)return Optional.empty();
        var result=new Peer[1];
        swarm.computeIfPresent(id,(key,old)->{
            if(!live(old))return old;
            owner(old,user);var chunks=new HashSet<>(old.chunks());chunks.addAll(additions);
            result[0]=new Peer(file,id,old.userId(),old.username(),ip==null?old.ip():ip,ip==null?old.port():port,chunks,remove?old.lastSeen():clock.instant());
            return remove?null:result[0];
        });
        return Optional.ofNullable(result[0]);
    }
    public Optional<Peer> heartbeat(String file,String id,String user,String ip,int port){return change(file,id,user,ip,port,Set.of(),false);}
    public Optional<Peer> updateChunks(String file,String id,String user,Set<Integer> chunks){validate(file,chunks);return change(file,id,user,null,0,chunks,false);}
    public Optional<Peer> remove(String file,String id,String user){return change(file,id,user,null,0,Set.of(),true);}
    public List<Peer> findSources(String file){var swarm=swarms.get(file);return swarm==null?List.of():swarm.values().stream().filter(this::live).sorted(Comparator.comparing(Peer::peerId)).toList();}
    public Counts countSeedersLeechers(String file){int count=chunkCount(file).orElse(0);var peers=findSources(file);int seeders=(int)peers.stream().filter(p->p.chunks().size()==count).count();return new Counts(seeders,peers.size()-seeders);}
    public List<Peer> removeExpired(){
        var removed=new ArrayList<Peer>();
        swarms.values().forEach(swarm->swarm.forEach((id,peer)->swarm.computeIfPresent(id,(key,current)->{if(!live(current)){removed.add(current);return null;}return current;})));
        return removed;
    }
}
