package ru.raveon.service.analyze;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import ru.raveon.config.anticheat.ChecksConfigManager;
import ru.raveon.model.ModelId;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

public final class ConnectionVerification {
    public static void main(String[] args) throws Exception {
        verifyParsing();
        verifySavedConnection();
        verifyExchangeAndCheck();
        System.out.println("Connection verification passed");
    }

    private static void verifyParsing() {
        var connected = AnalyzeConnection.parse(200,
                "{\"api_key\":\"rvk_a\",\"server\":\"Survival\",\"models\":[\"pro\",\"ultra\",\"future\"]}");
        require(connected.status() == AnalyzeConnection.Status.CONNECTED, "successful reply");
        require(connected.apiKey().equals("rvk_a") && connected.server().equals("Survival"), "reply fields");
        require(connected.models().equals(Set.of(ModelId.PRO, ModelId.ULTRA)), "unknown models are skipped");
        require(connected.modelNames().equals("Pro, Ultra"), "model names");

        var rejected = AnalyzeConnection.parse(401, "{\"detail\":\"connect_code_invalid\"}");
        require(rejected.status() == AnalyzeConnection.Status.REJECTED, "rejected code");
        require(rejected.detail().equals("connect_code_invalid"), "rejection reason");
        require(AnalyzeConnection.parse(502, "<html>").status() == AnalyzeConnection.Status.UNAVAILABLE,
                "gateway error page");
        require(AnalyzeConnection.parse(200, "not json").status() == AnalyzeConnection.Status.UNAVAILABLE,
                "malformed reply");
    }

    private static void verifySavedConnection() throws Exception {
        Path folder = Files.createTempDirectory("raveon-connection");
        Plugin plugin = plugin(folder);
        write(folder, "http://127.0.0.1:1/v1/inference", false);
        var before = new ChecksConfigManager(plugin);
        require(before.getApiKey().isEmpty() && !before.isAnalysisEnabled(), "not connected by default");

        ChecksConfigManager.saveConnection(plugin, "rvk_saved");
        var after = new ChecksConfigManager(plugin);
        require(after.getApiKey().equals("rvk_saved"), "key was not saved");
        require(after.isAnalysisEnabled(), "connecting must enable analysis");
    }

    private static void verifyExchangeAndCheck() throws Exception {
        AtomicReference<String> connectBody = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/auth/connect", exchange -> {
            connectBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            reply(exchange, 200, "{\"api_key\":\"rvk_new\",\"server\":\"Survival\",\"models\":[\"pro\"]}");
        });
        server.createContext("/v1/auth/check", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            reply(exchange, 401, "{\"detail\":\"api_key_invalid\"}");
        });
        server.start();
        try {
            Path folder = Files.createTempDirectory("raveon-connection");
            Plugin plugin = plugin(folder);
            write(folder, "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/inference", true);
            var settings = new ChecksConfigManager(plugin);
            var dispatcher = new AnalyzeBatchDispatcher(plugin, () -> settings);
            var connection = new AnalyzeConnection(plugin, () -> settings, dispatcher);

            var exchanged = connection.exchange("K7QM-2XPD").join();
            require(exchanged.apiKey().equals("rvk_new"), "exchanged key");
            require(connectBody.get().equals("{\"code\":\"K7QM-2XPD\"}"), "request body: " + connectBody.get());

            var checked = connection.check("rvk_new").join();
            require(checked.status() == AnalyzeConnection.Status.REJECTED, "rejected key");
            require("Bearer rvk_new".equals(authorization.get()), "authorization header");
            dispatcher.stop();
        } finally {
            server.stop(0);
        }

        // Closed port: the node being down must be reported, not thrown.
        Path folder = Files.createTempDirectory("raveon-connection");
        Plugin plugin = plugin(folder);
        write(folder, "http://127.0.0.1:1/v1/inference", true);
        var settings = new ChecksConfigManager(plugin);
        var dispatcher = new AnalyzeBatchDispatcher(plugin, () -> settings);
        var result = new AnalyzeConnection(plugin, () -> settings, dispatcher).exchange("X").join();
        require(result.status() == AnalyzeConnection.Status.UNAVAILABLE, "unreachable node");
        dispatcher.stop();
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static void write(Path folder, String endpoint, boolean enabled) throws IOException {
        var yaml = new YamlConfiguration();
        yaml.set("analyze.enabled", enabled);
        yaml.set("analyze.server", endpoint);
        yaml.set("analyze.worldguard.enabled", false);
        yaml.save(folder.resolve("checks.yml").toFile());
    }

    private static Plugin plugin(Path folder) {
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class},
                (object, method, args) -> switch (method.getName()) {
                    case "getDataFolder" -> folder.toFile();
                    case "getLogger" -> Logger.getLogger("ConnectionVerification");
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
