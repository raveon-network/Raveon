package ru.raveon.verification;

import ru.raveon.api.configuration.RedisConfig;
import ru.raveon.api.redis.RedisManager;
import ru.raveon.service.PlayerOnlineService;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class RedisPresenceVerification {
    private static final long FUTURE_TIMEOUT_SECONDS = 15;
    public static void main(String[] args) throws Exception {
        if (args.length != 0) throw new IllegalArgumentException("This test manages its own localhost Redis instance");
        try (RedisFixture fixture = RedisFixture.start()) {
            verify(fixture.port);
        }
    }

    private static void verify(int port) throws Exception {
        String uuid = UUID.randomUUID().toString();
        String playerKey = "player:" + uuid + ":online";
        String heartbeat = script("HEARTBEAT");
        String quit = script("QUIT");
        try (RedisSocket redis = new RedisSocket(port)) {
            require(evalHeartbeat(redis, heartbeat, uuid, "a", "A") == 1, "Initial ownership failed");
            require(evalHeartbeat(redis, heartbeat, uuid, "a", "A") == 1, "Idempotent heartbeat failed");
            require(evalHeartbeat(redis, heartbeat, uuid, "b", "B") == 1, "New server did not claim ownership");
            require((long) redis.command("PTTL", "online:anarchy:B:players") > 0,
                    "Per-server presence index has no expiration");
            redis.command("EVAL", script("CLEANUP"), "3", "online:players", "online:anarchy:B:players",
                    "online:servers", Long.toString(System.currentTimeMillis() - 60_000), "0");
            require((long) redis.command("SISMEMBER", "online:servers", "A") == 0,
                    "Cleanup retained an expired server index");
            require((long) redis.command("SISMEMBER", "online:servers", "B") == 1,
                    "Cleanup removed an active server index");
            require(evalHeartbeat(redis, heartbeat, uuid, "a", "A") == 0, "Late old heartbeat stole ownership");
            require(evalQuit(redis, quit, uuid, "a") == 0, "Old server removed a newer session");
            require("b".equals(redis.command("HGET", playerKey, "session")), "New session hash changed");
            require(redis.command("ZSCORE", "online:players", uuid) != null, "New session disappeared from online set");
            require(evalHeartbeat(redis, heartbeat, uuid, "c", "C") == 1, "Claim fixture failed");
            require(evalHeartbeat(redis, heartbeat, uuid, "d", "D") == 1, "Later claim fixture failed");
            require(evalHeartbeat(redis, heartbeat, uuid, "c", "C") == 0,
                    "Retry of an acknowledged-lost claim stole newer ownership");
            require(evalQuit(redis, quit, uuid, "d") == 1, "Current owner could not quit");
            require(redis.command("HGET", playerKey, "session") == null, "Quit left an online hash");
        }
        verifyService(port);
        System.out.println("Real localhost Redis atomic ownership, idempotent claim, asynchronous presence and fallback checks passed");
    }

    private static void verifyService(int port) throws Exception {
        RedisConfig config = new RedisConfig("127.0.0.1", port, "", 1000, true);
        RedisManager a = new RedisManager(config);
        RedisManager b = new RedisManager(config);
        PlayerOnlineService serviceA = new PlayerOnlineService(a);
        PlayerOnlineService serviceB = new PlayerOnlineService(b);
        UUID uuid = UUID.randomUUID();
        String key = "player:" + uuid + ":online";
        try {
            serviceA.heartbeat(uuid, "Player", "A");
            String ownerA = a.execute(redis -> redis.hget(key, "session")).get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            require(ownerA != null, "Async heartbeat A was not persisted");
            serviceB.heartbeat(uuid, "Player", "B");
            String ownerB = b.execute(redis -> redis.hget(key, "session")).get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            require(ownerB != null && !ownerA.equals(ownerB), "Async heartbeat B did not replace session");
            b.execute(redis -> redis.del(key + ":claim:" + ownerA)).get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            serviceA.heartbeat(uuid, "Player", "A");
            String afterExpiredClaim = a.execute(redis -> redis.hget(key, "session")).get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            require(ownerB.equals(afterExpiredClaim), "Old service reclaimed ownership after its claim marker expired");
            serviceA.quit(uuid);
            String retained = a.execute(redis -> redis.hget(key, "session")).get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            require(ownerB.equals(retained), "Old service changed B presence after late heartbeat and quit");
            require(serviceB.getOnlinePlayerUuidsAsync().get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS).contains(uuid.toString()),
                    "Async remote snapshot omitted current player");
            b.onDisable();
            require(serviceB.getOnlinePlayerUuids().contains(uuid.toString()),
                    "Local snapshot was unavailable after Redis closure");
        } finally {
            a.onDisable();
            b.onDisable();
        }
    }

    private static String script(String name) throws Exception {
        var field = PlayerOnlineService.class.getDeclaredField(name);
        field.setAccessible(true);
        return (String) field.get(null);
    }

    private static long evalHeartbeat(RedisSocket redis, String script, String uuid, String session, String server)
            throws IOException {
        return (long) redis.command("EVAL", script, "4", "player:" + uuid + ":online", "online:players",
                "online:anarchy:" + server + ":players", "online:servers", session, "140", uuid,
                "Player", server, Long.toString(System.currentTimeMillis()), "70", "1");
    }

    private static long evalQuit(RedisSocket redis, String script, String uuid, String session) throws IOException {
        return (long) redis.command("EVAL", script, "3", "player:" + uuid + ":online",
                "online:players", "online:servers", session, uuid);
    }

    private static final class RedisFixture implements AutoCloseable {
        private final Path directory;
        private final Process process;
        private final int port;

        private RedisFixture(Path directory, Process process, int port) {
            this.directory = directory;
            this.process = process;
            this.port = port;
        }

        private static RedisFixture start() throws Exception {
            String binary = System.getProperty("raveon.redis.binary");
            if (binary == null || binary.isBlank()) binary = System.getenv("RAVEON_REDIS_BINARY");
            if (binary == null || binary.isBlank()) binary = "redis-server";
            int port;
            try (ServerSocket reservation = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
                port = reservation.getLocalPort();
            }
            Path directory = Files.createTempDirectory("raveon-redis-test-");
            Path log = directory.resolve("redis.log");
            Process process;
            try {
                process = new ProcessBuilder(binary, "--bind", "127.0.0.1", "--protected-mode", "yes",
                        "--port", Integer.toString(port), "--save", "", "--appendonly", "no",
                        "--daemonize", "no").directory(directory.toFile()).redirectErrorStream(true)
                        .redirectOutput(log.toFile()).start();
            } catch (IOException error) {
                deleteDirectory(directory);
                throw new IOException("A redis-server binary is required; set RAVEON_REDIS_BINARY "
                        + "or -Draveon.redis.binary to its path", error);
            }
            RedisFixture fixture = new RedisFixture(directory, process, port);
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (System.nanoTime() < deadline) {
                    String output = Files.readString(log);
                    if (!process.isAlive()) throw new IOException("Local Redis failed to start: " + output);
                    if (output.contains("Ready to accept connections")) {
                        try (RedisSocket redis = new RedisSocket(port)) {
                            require("PONG".equals(redis.command("PING")), "Local Redis readiness check failed");
                        }
                        return fixture;
                    }
                    Thread.sleep(25);
                }
                throw new IOException("Local Redis did not become ready: " + Files.readString(log));
            } catch (Exception | Error error) {
                try {
                    fixture.close();
                } catch (Exception cleanupError) {
                    error.addSuppressed(cleanupError);
                }
                throw error;
            }
        }

        @Override
        public void close() throws Exception {
            try {
                process.destroy();
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    if (!process.waitFor(2, TimeUnit.SECONDS))
                        throw new IOException("Local Redis process did not stop");
                }
            } finally {
                if (process.isAlive()) process.destroyForcibly();
                deleteDirectory(directory);
            }
        }

        private static void deleteDirectory(Path directory) throws IOException {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                    Files.deleteIfExists(path);
            }
        }
    }

    private static final class RedisSocket implements AutoCloseable {
        private final Socket socket;
        private final BufferedInputStream input;
        private final BufferedOutputStream output;

        private RedisSocket(int port) throws IOException {
            socket = new Socket("127.0.0.1", port);
            socket.setSoTimeout(2000);
            input = new BufferedInputStream(socket.getInputStream());
            output = new BufferedOutputStream(socket.getOutputStream());
        }

        private Object command(String... arguments) throws IOException {
            output.write(("*" + arguments.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
            for (String argument : arguments) {
                byte[] bytes = argument.getBytes(StandardCharsets.UTF_8);
                output.write(("$" + bytes.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
                output.write(bytes);
                output.write(new byte[] {'\r', '\n'});
            }
            output.flush();
            return read();
        }

        private Object read() throws IOException {
            int prefix = input.read();
            String line = line();
            return switch (prefix) {
                case '+' -> line;
                case '-' -> throw new IOException(line);
                case ':' -> Long.parseLong(line);
                case '$' -> {
                    int length = Integer.parseInt(line);
                    if (length < 0) yield null;
                    byte[] bytes = input.readNBytes(length);
                    if (bytes.length != length || input.read() != '\r' || input.read() != '\n')
                        throw new IOException("Truncated Redis response");
                    yield new String(bytes, StandardCharsets.UTF_8);
                }
                case '*' -> {
                    int count = Integer.parseInt(line);
                    List<Object> values = new ArrayList<>();
                    for (int i = 0; i < count; i++) values.add(read());
                    yield values;
                }
                default -> throw new IOException("Invalid Redis response");
            };
        }

        private String line() throws IOException {
            StringBuilder result = new StringBuilder();
            int next;
            while ((next = input.read()) != -1) {
                if (next == '\r') {
                    if (input.read() != '\n') throw new IOException("Invalid Redis line ending");
                    return result.toString();
                }
                result.append((char) next);
            }
            throw new IOException("Truncated Redis response");
        }

        @Override
        public void close() throws IOException { socket.close(); }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
