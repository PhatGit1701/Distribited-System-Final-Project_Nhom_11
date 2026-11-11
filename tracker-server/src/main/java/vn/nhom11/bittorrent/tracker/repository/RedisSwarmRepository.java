package vn.nhom11.bittorrent.tracker.repository;

import com.fasterxml.jackson.databind.*;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;
import java.time.*;
import java.util.*;

/** Standalone Redis implementation; multi-key Lua scripts require a single Redis shard.
 * Peer JSON survives lease expiry until atomic cleanup, allowing reliable departure events.
 * Chunk strings and per-peer lease keys have independent 90-second TTLs; shared hashes never expire.
 */
@Repository
@Profile("!dev & !test")
public class RedisSwarmRepository implements SwarmRepository {
    private static final String INDEX="swarm:expirations";
    private static final String METADATA="swarm:metadata";
    private final StringRedisTemplate redis;
    private final Clock clock;
    private final boolean serverTime;
    private final ObjectMapper json=new ObjectMapper();
    private static final String SNAPSHOT="""
        local function timeMillis(value)
          if value~='SERVER' then return tonumber(value) end
          local t=redis.call('TIME')
          return tonumber(t[1])*1000+math.floor(tonumber(t[2])/1000)
        end
        local function snapshot(p, chunkKey, count)
          local bytes=redis.call('GET',chunkKey) or ''
          p.chunkBits=(bytes:gsub('.',function(c) return string.format('%02x',string.byte(c)) end))
          return cjson.encode(p)
        end
        """;
    private static final DefaultRedisScript<String> MUTATE=new DefaultRedisScript<>(SNAPSHOT+"""
        local action=ARGV[1]
        local incoming=cjson.decode(ARGV[2])
        local now=timeMillis(ARGV[3])
        local ttl=tonumber(ARGV[4])
        local count=tonumber(redis.call('HGET',KEYS[5],incoming.fileId))
        if not count then return 'UNKNOWN_FILE' end
        local additions=incoming.chunks
        for _,i in ipairs(additions) do
          if i<0 or i>=count or i~=math.floor(i) then return 'INVALID_CHUNKS' end
        end
        local raw=redis.call('HGET',KEYS[1],incoming.peerId)
        local expiry=tonumber(redis.call('ZSCORE',KEYS[4],ARGV[5]) or '0')
        local p=nil
        if raw and expiry>now and redis.call('EXISTS',KEYS[3])==1 then p=cjson.decode(raw) end
        if p and p.userId~=incoming.userId then return 'FORBIDDEN' end
        if action~='register' and not p then return '' end
        if action=='remove' then
          local result=snapshot(p,KEYS[2],count)
          redis.call('HDEL',KEYS[1],incoming.peerId)
          redis.call('DEL',KEYS[2],KEYS[3])
          redis.call('ZREM',KEYS[4],ARGV[5])
          return result
        end
        if not p then
          redis.call('DEL',KEYS[2])
          p=incoming
        elseif action=='register' or action=='heartbeat' then
          p.ip=incoming.ip
          p.port=incoming.port
        end
        p.lastSeen=now
        p.chunks=nil
        redis.call('HSET',KEYS[1],incoming.peerId,cjson.encode(p))
        if redis.call('EXISTS',KEYS[2])==0 then redis.call('SET',KEYS[2],'') end
        for _,i in ipairs(additions) do redis.call('SETBIT',KEYS[2],i,1) end
        redis.call('PEXPIRE',KEYS[2],ttl)
        redis.call('SET',KEYS[3],'1','PX',ttl)
        redis.call('ZADD',KEYS[4],now+ttl,ARGV[5])
        return snapshot(p,KEYS[2],count)
        """,String.class);
    private static final DefaultRedisScript<List> SOURCES=new DefaultRedisScript<>(SNAPSHOT+"""
        local peers=redis.call('HGETALL',KEYS[1])
        local count=tonumber(redis.call('HGET',KEYS[3],ARGV[1]) or '0')
        local result={}
        for i=1,#peers,2 do
          local member=cjson.encode({ARGV[1],peers[i]})
          local expiry=tonumber(redis.call('ZSCORE',KEYS[2],member) or '0')
          if expiry>timeMillis(ARGV[2]) and redis.call('EXISTS','lease:'..ARGV[1]..':'..peers[i])==1 then
            table.insert(result,snapshot(cjson.decode(peers[i+1]),'chunks:'..ARGV[1]..':'..peers[i],count))
          end
        end
        return result
        """,List.class);
    private static final DefaultRedisScript<List> CLEANUP=new DefaultRedisScript<>(SNAPSHOT+"""
        local members=redis.call('ZRANGEBYSCORE',KEYS[1],'-inf',timeMillis(ARGV[1]),'LIMIT',0,1000)
        local result={}
        for _,member in ipairs(members) do
          local pair=cjson.decode(member)
          local hash='peers:'..pair[1]
          local raw=redis.call('HGET',hash,pair[2])
          if raw then table.insert(result,raw) end
          redis.call('HDEL',hash,pair[2])
          redis.call('DEL','chunks:'..pair[1]..':'..pair[2],'lease:'..pair[1]..':'..pair[2])
          redis.call('ZREM',KEYS[1],member)
        end
        return result
        """,List.class);
    @org.springframework.beans.factory.annotation.Autowired
    public RedisSwarmRepository(StringRedisTemplate redis,Clock clock){this(redis,clock,true);}
    // Deterministic tests may substitute time; production uses the Redis server clock.
    RedisSwarmRepository(StringRedisTemplate redis,Clock clock,boolean serverTime){this.redis=redis;this.clock=clock;this.serverTime=serverTime;}
    private String now(){return serverTime?"SERVER":Long.toString(clock.millis());}
    public void registerMetadata(String id,int count){if(count<0)throw new IllegalArgumentException("Invalid chunk count");redis.opsForHash().put(METADATA,id,Integer.toString(count));}
    public OptionalInt chunkCount(String id){Object value=redis.opsForHash().get(METADATA,id);return value==null?OptionalInt.empty():OptionalInt.of(Integer.parseInt(value.toString()));}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private Peer decode(String value){
        try{JsonNode p=json.readTree(value);Set<Integer> chunks=new HashSet<>();String bits=p.path("chunkBits").asText("");
            for(int i=0;i<bits.length();i+=2){int b=Integer.parseInt(bits.substring(i,i+2),16);for(int bit=0;bit<8;bit++)if((b&(128>>bit))!=0)chunks.add(i*4+bit);}
            return new Peer(p.path("fileId").asText(),p.path("peerId").asText(),p.path("userId").asText(),p.path("username").asText(),p.path("ip").asText(),p.path("port").asInt(),chunks,Instant.ofEpochMilli(p.path("lastSeen").asLong()));
        }catch(Exception e){throw new IllegalStateException("Invalid Redis peer data",e);}
    }
    private Optional<Peer> mutate(String action,Peer peer){
        Map<String,Object> value=new HashMap<>();value.put("fileId",peer.fileId());value.put("peerId",peer.peerId());value.put("userId",peer.userId());value.put("username",peer.username());value.put("ip",peer.ip());value.put("port",peer.port());value.put("chunks",peer.chunks());
        String result=redis.execute(MUTATE,List.of("peers:"+peer.fileId(),"chunks:"+peer.fileId()+":"+peer.peerId(),"lease:"+peer.fileId()+":"+peer.peerId(),INDEX,METADATA),action,encode(value),now(),Long.toString(PEER_TTL.toMillis()),encode(List.of(peer.fileId(),peer.peerId())));
        if("FORBIDDEN".equals(result))throw new SecurityException("Peer belongs to another user");
        if("UNKNOWN_FILE".equals(result)||"INVALID_CHUNKS".equals(result))throw new IllegalArgumentException(result);
        return result==null||result.isEmpty()?Optional.empty():Optional.of(decode(result));
    }
    public Peer register(Peer peer){return mutate("register",peer).orElseThrow();}
    private Peer command(String file,String id,String user,String ip,int port,Set<Integer> chunks){return new Peer(file,id,user,"",ip,port,chunks,clock.instant());}
    public Optional<Peer> heartbeat(String file,String id,String user,String ip,int port){return mutate("heartbeat",command(file,id,user,ip,port,Set.of()));}
    public Optional<Peer> updateChunks(String file,String id,String user,Set<Integer> chunks){return mutate("chunks",command(file,id,user,"",0,chunks));}
    public Optional<Peer> remove(String file,String id,String user){return mutate("remove",command(file,id,user,"",0,Set.of()));}
    public List<Peer> findSources(String file){return decodeList(redis.execute(SOURCES,List.of("peers:"+file,INDEX,METADATA),file,now())).stream().sorted(Comparator.comparing(Peer::peerId)).toList();}
    private List<Peer> decodeList(List<?> values){if(values==null)return List.of();return values.stream().map(value->decode(value.toString())).toList();}
    public Counts countSeedersLeechers(String file){int count=chunkCount(file).orElse(0);var peers=findSources(file);int seeders=(int)peers.stream().filter(peer->peer.chunks().size()==count).count();return new Counts(seeders,peers.size()-seeders);}
    public List<Peer> removeExpired(){return decodeList(redis.execute(CLEANUP,List.of(INDEX),now()));}
}
