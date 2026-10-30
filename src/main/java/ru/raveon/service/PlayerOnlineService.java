package ru.raveon.service;

import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;

import ru.raveon.Raveon;
import ru.raveon.api.redis.RedisManager;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class PlayerOnlineService {
    private static final String ONLINE_PLAYERS_KEY = "online:players";
    private static final String ONLINE_SERVERS_KEY = "online:servers";
    private static final String HEARTBEAT = """
            local current = redis.call('HGET', KEYS[1], 'session')
            local claimKey = KEYS[1] .. ':claim:' .. ARGV[1]
            local first = redis.call('SET', claimKey, '1', 'NX', 'EX', ARGV[2])
            redis.call('EXPIRE', claimKey, ARGV[2])
            if current and current ~= ARGV[1] and (not first or ARGV[8] ~= '1') then return 0 end
            local oldServer = redis.call('HGET', KEYS[1], 'anarchy')
            if oldServer and oldServer ~= ARGV[5] then
                redis.call('ZREM', 'online:anarchy:' .. oldServer .. ':players', ARGV[3])
            end
            redis.call('ZADD', KEYS[2], ARGV[6], ARGV[3])
            redis.call('ZADD', KEYS[3], ARGV[6], ARGV[3])
            redis.call('EXPIRE', KEYS[3], ARGV[7])
            redis.call('HSET', KEYS[1], 'uuid', ARGV[3], 'nickname', ARGV[4],
                'anarchy', ARGV[5], 'lastSeen', ARGV[6], 'session', ARGV[1])
            redis.call('EXPIRE', KEYS[1], ARGV[7])
            redis.call('SADD', KEYS[4], ARGV[5])
            return 1
            """;
    private static final String QUIT = """
            if redis.call('HGET', KEYS[1], 'session') ~= ARGV[1] then return 0 end
            local server = redis.call('HGET', KEYS[1], 'anarchy')
            redis.call('ZREM', KEYS[2], ARGV[2])
            if server then
                local key = 'online:anarchy:' .. server .. ':players'
                redis.call('ZREM', key, ARGV[2])
                if redis.call('ZCARD', key) == 0 then
                    redis.call('DEL', key)
                    redis.call('SREM', KEYS[3], server)
                end
            end
            redis.call('DEL', KEYS[1])
            return 1
            """;

    private static final String CLEANUP = """
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[1])
            redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', ARGV[1])
            local servers = redis.call('SSCAN', KEYS[3], ARGV[2], 'COUNT', 100)
            for _, server in ipairs(servers[2]) do
                if redis.call('EXISTS', 'online:anarchy:' .. server .. ':players') == 0 then
                    redis.call('SREM', KEYS[3], server)
                end
            end
            return servers[1]
            """;

    private final RedisManager redisManager;
    private final long onlineTimeoutMillis;
    private final long playerHashTtlSeconds;
    private final Map<String, OnlineSession> localOnline = new ConcurrentHashMap<>();
    private volatile Set<String> remoteOnline = Set.of();
    private volatile long remoteRefreshedAt;
    private String serverCleanupCursor = "0";

    public PlayerOnlineService(RedisManager redisManager) {
        this(redisManager, 60_000L);
    }

    public PlayerOnlineService(RedisManager redisManager, long onlineTimeoutMillis) {
        if (onlineTimeoutMillis < 1000) throw new IllegalArgumentException("Online timeout is too small");
        this.redisManager = redisManager;
        this.onlineTimeoutMillis = onlineTimeoutMillis;
        playerHashTtlSeconds = TimeUnit.MILLISECONDS.toSeconds(onlineTimeoutMillis) + 10;
    }

    public void heartbeat(UUID uuid, String nickname) {
        heartbeat(uuid.toString(), nickname, Raveon.serverId());
    }

    public void heartbeat(UUID uuid, String nickname, String serverId) {
        heartbeat(uuid.toString(), nickname, serverId);
    }

    public void heartbeat(String uuid, String nickname, String serverId) {
        long now = System.currentTimeMillis();
        OnlineSession session = localOnline.compute(uuid, (key, existing) -> {
            if (existing == null || !existing.serverId.equals(serverId))
                return new OnlineSession(nickname == null ? "" : nickname, serverId, now);
            existing.lastSeen = now;
            return existing;
        });
        if (!enabled()) return;
        redisManager.execute(redis -> {
            if (localOnline.get(uuid) != session) return 0L;
            boolean initialClaim = !session.claimAttempted;
            session.claimAttempted = true;
            Long updated = redis.eval(HEARTBEAT, ScriptOutputType.INTEGER,
                    new String[] {playerKey(uuid), ONLINE_PLAYERS_KEY, serverPlayersKey(serverId), ONLINE_SERVERS_KEY},
                    session.id, Long.toString(playerHashTtlSeconds * 2), uuid, session.nickname, serverId,
                    Long.toString(session.lastSeen), Long.toString(playerHashTtlSeconds),
                    initialClaim ? "1" : "0");
            return updated;
        });
    }

    public void quit(UUID uuid) {
        if (uuid != null) quit(uuid.toString());
    }

    public void quit(String uuid) {
        if (uuid == null) return;
        OnlineSession session = localOnline.remove(uuid);
        if (session == null || !enabled()) return;
        redisManager.execute(redis -> redis.eval(QUIT, ScriptOutputType.INTEGER,
                new String[] {playerKey(uuid), ONLINE_PLAYERS_KEY, ONLINE_SERVERS_KEY}, session.id, uuid));
    }

    public Set<String> getOnlinePlayerUuids() {
        cleanupLocal();
        Set<String> result = new HashSet<>(localOnline.keySet());
        if (enabled() && System.currentTimeMillis() - remoteRefreshedAt <= onlineTimeoutMillis)
            result.addAll(remoteOnline);
        return Set.copyOf(result);
    }

    public CompletableFuture<Set<String>> getOnlinePlayerUuidsAsync() {
        cleanupLocal();
        if (!enabled()) return CompletableFuture.completedFuture(Set.copyOf(localOnline.keySet()));
        return redisManager.execute(redis -> {
            long cutoff = System.currentTimeMillis() - onlineTimeoutMillis;
            redis.zremrangebyscore(ONLINE_PLAYERS_KEY, "-inf", Long.toString(cutoff));
            Set<String> online = Set.copyOf(redis.zrange(ONLINE_PLAYERS_KEY, 0, -1));
            remoteOnline = online;
            remoteRefreshedAt = System.currentTimeMillis();
            return getOnlinePlayerUuids();
        }).exceptionally(error -> Set.copyOf(localOnline.keySet()));
    }

    public void cleanup() {
        cleanupLocal();
        if (!enabled()) return;
        String server = Raveon.serverId();
        redisManager.execute(redis -> {
            long cutoff = System.currentTimeMillis() - onlineTimeoutMillis;
            serverCleanupCursor = redis.eval(CLEANUP, ScriptOutputType.VALUE,
                    new String[] {ONLINE_PLAYERS_KEY, serverPlayersKey(server), ONLINE_SERVERS_KEY},
                    Long.toString(cutoff), serverCleanupCursor);
            return null;
        });
    }

    private void cleanupLocal() {
        long cutoff = System.currentTimeMillis() - onlineTimeoutMillis;
        localOnline.entrySet().removeIf(entry -> entry.getValue().lastSeen < cutoff);
    }

    private boolean enabled() {
        return redisManager != null && redisManager.isEnabled();
    }

    private String playerKey(String uuid) { return "player:" + uuid + ":online"; }
    private String serverPlayersKey(String server) { return "online:anarchy:" + server + ":players"; }

    private static final class OnlineSession {
        private final String id = UUID.randomUUID().toString();
        private final String nickname;
        private final String serverId;
        private volatile long lastSeen;
        private boolean claimAttempted;

        private OnlineSession(String nickname, String serverId, long lastSeen) {
            this.nickname = nickname;
            this.serverId = serverId;
            this.lastSeen = lastSeen;
        }
    }
}
