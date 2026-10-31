package ru.raveon.service.analyze;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import org.bukkit.plugin.Plugin;

import ru.raveon.Raveon;
import ru.raveon.config.anticheat.ChecksConfigManager;
import ru.raveon.model.ModelId;
import ru.raveon.service.LanguageService;
import ru.raveon.utils.SchedulerUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Connects the plugin to its license: a one-time code from the website is exchanged
 * for an API key, and the key tells which models the license may use.
 */
public final class AnalyzeConnection {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    public enum Status { CONNECTED, REJECTED, UNAVAILABLE }

    public record Result(Status status, String server, Set<ModelId> models, String apiKey, String detail) {
        static Result failed(Status status, String detail) {
            return new Result(status, "", Set.of(), "", detail);
        }

        public String modelNames() {
            return models.stream().map(ModelId::displayName).collect(Collectors.joining(", "));
        }
    }

    private final Plugin plugin;
    private final Supplier<ChecksConfigManager> settings;
    private final AnalyzeBatchDispatcher dispatcher;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    public AnalyzeConnection(Plugin plugin, Supplier<ChecksConfigManager> settings,
            AnalyzeBatchDispatcher dispatcher) {
        this.plugin = plugin;
        this.settings = settings;
        this.dispatcher = dispatcher;
    }

    /** Exchanges the code for an API key, stores the key and enables analysis without a restart. */
    public CompletableFuture<Result> connect(String code) {
        return exchange(code).thenCompose(result -> result.status() == Status.CONNECTED
                ? apply(result) : CompletableFuture.completedFuture(result));
    }

    /** Reports the stored key's state at startup and restricts the models it may use. */
    public void refresh() {
        LanguageService language = Raveon.INSTANCE.getLanguageService();
        String apiKey = settings.get().getApiKey();
        if (apiKey.isEmpty()) {
            plugin.getLogger().warning(language.plain("logs.plugin-not-connected"));
            return;
        }
        check(apiKey).thenAccept(result -> {
            switch (result.status()) {
                case CONNECTED -> {
                    dispatcher.connected(result.models());
                    plugin.getLogger().info(language.plain("logs.plugin-connected",
                            "server", result.server(), "models", result.modelNames()));
                }
                case REJECTED -> plugin.getLogger().warning(
                        language.plain("logs.plugin-rejected", "reason", result.detail()));
                case UNAVAILABLE -> plugin.getLogger().warning(
                        language.plain("logs.plugin-check-failed", "error", result.detail()));
            }
        });
    }

    CompletableFuture<Result> exchange(String code) {
        JsonObject body = new JsonObject();
        body.addProperty("code", code);
        return send(HttpRequest.newBuilder(endpoint("/v1/auth/connect"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())));
    }

    CompletableFuture<Result> check(String apiKey) {
        return send(HttpRequest.newBuilder(endpoint("/v1/auth/check"))
                .header("Authorization", "Bearer " + apiKey).GET());
    }

    private URI endpoint(String path) {
        return URI.create(settings.get().getAnalyzeServer()).resolve(path);
    }

    private CompletableFuture<Result> send(HttpRequest.Builder request) {
        return httpClient.sendAsync(request.timeout(TIMEOUT).build(), HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> parse(response.statusCode(), response.body()))
                .exceptionally(error -> Result.failed(Status.UNAVAILABLE, String.valueOf(error.getMessage())));
    }

    private CompletableFuture<Result> apply(Result result) {
        CompletableFuture<Result> applied = new CompletableFuture<>();
        SchedulerUtils.run(plugin, () -> {
            try {
                ChecksConfigManager.saveConnection(plugin, result.apiKey());
                Raveon.INSTANCE.reloadConfiguration();
                dispatcher.connected(result.models());
                applied.complete(result);
            } catch (RuntimeException error) {
                applied.completeExceptionally(error);
            }
        });
        return applied;
    }

    static Result parse(int status, String body) {
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            if (status == 401 || status == 403)
                return Result.failed(Status.REJECTED, text(json, "detail"));
            if (status < 200 || status >= 300)
                return Result.failed(Status.UNAVAILABLE, "HTTP " + status);
            return new Result(Status.CONNECTED, text(json, "server"), models(json), text(json, "api_key"), "");
        } catch (JsonParseException | IllegalStateException error) {
            return Result.failed(Status.UNAVAILABLE, "HTTP " + status);
        }
    }

    private static Set<ModelId> models(JsonObject json) {
        Set<ModelId> models = EnumSet.noneOf(ModelId.class);
        if (!json.has("models")) return models;
        for (JsonElement element : json.getAsJsonArray("models")) {
            // A newer API may list models this plugin build does not know about.
            for (ModelId model : ModelId.values())
                if (model.key().equals(element.getAsString())) models.add(model);
        }
        return models;
    }

    private static String text(JsonObject json, String field) {
        JsonElement value = json.get(field);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }
}
