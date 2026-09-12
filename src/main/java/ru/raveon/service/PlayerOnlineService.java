package ru.raveon.service;

import io.lettuce.core.RedisException;
import io.lettuce.core.api.sync.RedisCommands;
import lombok.Data;
import ru.raveon.Raveon;
import ru.raveon.api.redis.RedisManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class PlayerOnlineService {

    private static final String ONLINE_PLAYERS_KEY = "online:players";
    private static final String ONLINE_SERVERS_KEY = "online:servers";

    private final RedisManager redisManager;
    private final long onlineTimeoutMillis;
    private final long playerHashTtlSeconds;

    private final Map<String, PlayerOnlineInfo> localOnline = new ConcurrentHashMap<>();

    public PlayerOnlineService(RedisManager redisManager) {
        this(redisManager, 60_000L);
    }

    public PlayerOnlineService(RedisManager redisManager, long onlineTimeoutMillis) {
        this.redisManager = redisManager;
        this.onlineTimeoutMillis = onlineTimeoutMillis;

        long timeoutSeconds = TimeUnit.MILLISECONDS.toSeconds(onlineTimeoutMillis);
        this.playerHashTtlSeconds = Math.max(1L, timeoutSeconds + 10L);
    }

    public void heartbeat(UUID uuid, String nickname) {
        heartbeat(uuid.toString(), nickname, Raveon.serverId());
    }

    public void heartbeat(UUID uuid, String nickname, String anarchyId) {
        heartbeat(uuid.toString(), nickname, anarchyId);
    }

    public void heartbeat(String uuid, String nickname, String anarchyId) {
        long now = System.currentTimeMillis();

        if (!isRedisAvailable()) {
            localHeartbeat(uuid, nickname, anarchyId, now);
            return;
        }

        try {
            RedisCommands<String, String> redis = commands();

            String playerKey = playerKey(uuid);

            String oldAnarchyId = redis.hget(playerKey, "anarchy");
            if (oldAnarchyId != null && !oldAnarchyId.equals(anarchyId)) {
                redis.zrem(anarchyPlayersKey(oldAnarchyId), uuid);
                cleanupServer(oldAnarchyId);
            }

            redis.zadd(ONLINE_PLAYERS_KEY, now, uuid);
            redis.zadd(anarchyPlayersKey(anarchyId), now, uuid);

            Map<String, String> data = new HashMap<>();
            data.put("uuid", uuid);
            data.put("nickname", nickname == null ? "" : nickname);
            data.put("anarchy", anarchyId);
            data.put("lastSeen", String.valueOf(now));

            redis.hset(playerKey, data);
            redis.expire(playerKey, playerHashTtlSeconds);

            redis.sadd(ONLINE_SERVERS_KEY, anarchyId);
        } catch (RedisException exception) {
            localHeartbeat(uuid, nickname, anarchyId, now);
        }
    }

    public void quit(UUID uuid) {
        quit(uuid.toString());
    }

    public void quit(String uuid) {
        if (!isRedisAvailable()) {
            localOnline.remove(uuid);
            return;
        }

        try {
            RedisCommands<String, String> redis = commands();

            String playerKey = playerKey(uuid);
            String anarchyId = redis.hget(playerKey, "anarchy");

            redis.zrem(ONLINE_PLAYERS_KEY, uuid);

            if (anarchyId != null) {
                redis.zrem(anarchyPlayersKey(anarchyId), uuid);
                cleanupServer(anarchyId);
            }

            redis.del(playerKey);
        } catch (RedisException exception) {
            localOnline.remove(uuid);
        }
    }


    public void quit(UUID uuid, String anarchyId) {
        quit(uuid.toString(), anarchyId);
    }

    public void quit(String uuid, String anarchyId) {
        if (anarchyId == null || anarchyId.trim().isEmpty()) {
            quit(uuid);
            return;
        }

        if (!isRedisAvailable()) {
            localOnline.remove(uuid);
            return;
        }

        try {
            RedisCommands<String, String> redis = commands();

            redis.zrem(ONLINE_PLAYERS_KEY, uuid);
            redis.zrem(anarchyPlayersKey(anarchyId), uuid);
            redis.del(playerKey(uuid));

            cleanupServer(anarchyId);
        } catch (RedisException exception) {
            localOnline.remove(uuid);
        }
    }

    public boolean isOnline(UUID uuid) {
        return isOnline(uuid.toString());
    }

    public boolean isOnline(String uuid) {
        long cutoff = System.currentTimeMillis() - onlineTimeoutMillis;

        if (!isRedisAvailable()) {
            cleanupLocal();
            PlayerOnlineInfo info = localOnline.get(uuid);
            return info != null && info.getLastSeen() >= cutoff;
        }

        try {
            RedisCommands<String, String> redis = commands();

            Double lastSeen = redis.zscore(ONLINE_PLAYERS_KEY, uuid);
            if (lastSeen == null) {
                return false;
            }

            if (lastSeen.longValue() < cutoff) {
                quit(uuid);
                return false;
            }

            return true;
        } catch (RedisException exception) {
            cleanupLocal();
            return localOnline.containsKey(uuid);
        }
    }

    public Optional<PlayerOnlineInfo> getPlayer(UUID uuid) {
        return getPlayer(uuid.toString());
    }

    public Optional<PlayerOnlineInfo> getPlayer(String uuid) {
        long cutoff = System.currentTimeMillis() - onlineTimeoutMillis;

        if (!isRedisAvailable()) {
            cleanupLocal();
            PlayerOnlineInfo info = localOnline.get(uuid);
            return Optional.ofNullable(info);
        }

        try {
            RedisCommands<String, String> redis = commands();

            Map<String, String> data = redis.hgetall(playerKey(uuid));
            if (data == null || data.isEmpty()) {
                return Optional.empty();
            }

            PlayerOnlineInfo info = fromRedisHash(data);

            if (info.getLastSeen() < cutoff) {
                quit(uuid);
                return Optional.empty();
            }

            return Optional.of(info);
        } catch (RedisException exception) {
            cleanupLocal();
            return Optional.ofNullable(localOnline.get(uuid));
        }
    }

    public long getTotalOnline() {
        cleanup();

        if (!isRedisAvailable()) {
            return localOnline.size();
        }

        try {
            Long count = commands().zcard(ONLINE_PLAYERS_KEY);
            return count == null ? 0L : count;
        } catch (RedisException exception) {
            cleanupLocal();
            return localOnline.size();
        }
    }

    public long getOnlineOnServer(String serverId) {
        cleanupServer(serverId);

        if (!isRedisAvailable()) {
            cleanupLocal();
            return localOnline.values()
                    .stream()
                    .filter(info -> serverId.equals(info.getServerId()))
                    .count();
        }

        try {
            Long count = commands().zcard(anarchyPlayersKey(serverId));
            return count == null ? 0L : count;
        } catch (RedisException exception) {
            cleanupLocal();
            return localOnline.values()
                    .stream()
                    .filter(info -> serverId.equals(info.getServerId()))
                    .count();
        }
    }

    public Set<String> getOnlinePlayers() {
        cleanup();

        if (!isRedisAvailable()) {
            cleanupLocal();
            return localOnline.values()
                    .stream()
                    .map(PlayerOnlineInfo::getNickname)
                    .filter(nickname -> nickname != null && !nickname.isEmpty())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        try {
            RedisCommands<String, String> redis = commands();

            List<String> uuids = redis.zrange(ONLINE_PLAYERS_KEY, 0, -1);
            Set<String> nicknames = new LinkedHashSet<>();

            for (String uuid : uuids) {
                String nickname = redis.hget(playerKey(uuid), "nickname");

                if (nickname != null && !nickname.isEmpty()) {
                    nicknames.add(nickname);
                }
            }

            return nicknames;
        } catch (RedisException exception) {
            cleanupLocal();
            return localOnline.values()
                    .stream()
                    .map(PlayerOnlineInfo::getNickname)
                    .filter(nickname -> nickname != null && !nickname.isEmpty())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
    }

    public Set<String> getOnlinePlayersOnAnarchy(String anarchyId) {
        cleanupServer(anarchyId);

        if (!isRedisAvailable()) {
            cleanupLocal();
            return localOnline.values()
                    .stream()
                    .filter(info -> anarchyId.equals(info.getServerId()))
                    .map(PlayerOnlineInfo::getUuid)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        try {
            List<String> players = commands().zrange(anarchyPlayersKey(anarchyId), 0, -1);
            return new LinkedHashSet<>(players);
        } catch (RedisException exception) {
            cleanupLocal();
            return localOnline.values()
                    .stream()
                    .filter(info -> anarchyId.equals(info.getServerId()))
                    .map(PlayerOnlineInfo::getUuid)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
    }

    public Set<String> getOnlineServers() {
        cleanup();

        if (!isRedisAvailable()) {
            cleanupLocal();
            return localOnline.values()
                    .stream()
                    .map(PlayerOnlineInfo::getServerId)
                    .collect(Collectors.toSet());
        }

        try {
            Set<String> servers = commands().smembers(ONLINE_SERVERS_KEY);
            return servers == null ? Collections.emptySet() : servers;
        } catch (RedisException exception) {
            cleanupLocal();
            return localOnline.values()
                    .stream()
                    .map(PlayerOnlineInfo::getServerId)
                    .collect(Collectors.toSet());
        }
    }

    public void cleanup() {
        long cutoff = System.currentTimeMillis() - onlineTimeoutMillis;

        if (!isRedisAvailable()) {
            cleanupLocal();
            return;
        }

        try {
            RedisCommands<String, String> redis = commands();

            redis.zremrangebyscore(ONLINE_PLAYERS_KEY, "-inf", String.valueOf(cutoff));

            Set<String> servers = redis.smembers(ONLINE_SERVERS_KEY);
            if (servers == null || servers.isEmpty()) {
                return;
            }

            for (String anarchyId : new ArrayList<>(servers)) {
                cleanupServer(anarchyId);
            }
        } catch (RedisException exception) {
            cleanupLocal();
        }
    }

    public void cleanupServer(String anarchyId) {
        if (anarchyId == null || anarchyId.trim().isEmpty()) {
            return;
        }

        long cutoff = System.currentTimeMillis() - onlineTimeoutMillis;

        if (!isRedisAvailable()) {
            cleanupLocal();
            return;
        }

        try {
            RedisCommands<String, String> redis = commands();
            String key = anarchyPlayersKey(anarchyId);

            redis.zremrangebyscore(key, "-inf", String.valueOf(cutoff));

            Long count = redis.zcard(key);
            if (count == null || count <= 0L) {
                redis.del(key);
                redis.srem(ONLINE_SERVERS_KEY, anarchyId);
            }
        } catch (RedisException ignored) {
            cleanupLocal();
        }
    }

    private void localHeartbeat(String uuid, String nickname, String anarchyId, long now) {
        localOnline.put(uuid, new PlayerOnlineInfo(
                uuid,
                nickname == null ? "" : nickname,
                anarchyId,
                now
        ));
    }

    private void cleanupLocal() {
        long cutoff = System.currentTimeMillis() - onlineTimeoutMillis;
        localOnline.entrySet().removeIf(entry -> entry.getValue().getLastSeen() < cutoff);
    }

    private boolean isRedisAvailable() {
        return redisManager != null
                && redisManager.isEnabled()
                && redisManager.getConnection() != null;
    }

    private RedisCommands<String, String> commands() {
        return redisManager.getConnection().sync();
    }

    private String playerKey(String uuid) {
        return "player:" + uuid + ":online";
    }

    private String anarchyPlayersKey(String anarchyId) {
        return "online:anarchy:" + anarchyId + ":players";
    }

    private PlayerOnlineInfo fromRedisHash(Map<String, String> data) {
        String uuid = data.getOrDefault("uuid", "");
        String nickname = data.getOrDefault("nickname", "");
        String anarchyId = data.getOrDefault("anarchy", "");

        long lastSeen;
        try {
            lastSeen = Long.parseLong(data.getOrDefault("lastSeen", "0"));
        } catch (NumberFormatException exception) {
            lastSeen = 0L;
        }

        return new PlayerOnlineInfo(uuid, nickname, anarchyId, lastSeen);
    }

    @Data
    public static final class PlayerOnlineInfo {
        private final String uuid;
        private final String nickname;
        private final String serverId;
        private final long lastSeen;
    }
}