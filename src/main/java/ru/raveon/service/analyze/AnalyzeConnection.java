package ru.raveon.service.analyze;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.bukkit.plugin.Plugin;

import ru.raveon.Raveon;
import ru.raveon.config.anticheat.ChecksConfigManager;
import ru.raveon.model.ModelId;
import ru.raveon.model.PluginModelProfile;
import ru.raveon.model.CloudPunishments;
import ru.raveon.config.RuntimeConfiguration;
import ru.raveon.config.anticheat.PunishmentConfigManager;
import ru.raveon.config.MainConfigManager;
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

    public record Result(Status status, String server, PluginModelProfile profile, String apiKey, String detail) {
        static Result failed(Status status, String detail) {
            return new Result(status, "", PluginModelProfile.EMPTY, "", detail);
        }

        public String modelNames() {
            return profile.models().values().stream().map(PluginModelProfile.Model::name).collect(Collectors.joining(", "));
        }
    }

    private final Plugin plugin;
    private final Supplier<ChecksConfigManager> settings;
    private final java.util.concurrent.atomic.AtomicBoolean polling = new java.util.concurrent.atomic.AtomicBoolean();
    private final Set<CompletableFuture<?>> pending = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile CloudPunishments.Policy observedLocal;
    private volatile boolean stopped;
    private SchedulerUtils.TaskHandle watchTask;
    private volatile long lastFailure;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    public AnalyzeConnection(Plugin plugin, Supplier<ChecksConfigManager> settings) {
        this.plugin = plugin;
        this.settings = settings;
    }

    /** Exchanges the code for an API key, stores the key and enables analysis without a restart. */
    public CompletableFuture<Result> connect(String code) {
        return exchange(code).thenCompose(result -> result.status() == Status.CONNECTED
                ? apply(result) : CompletableFuture.completedFuture(result));
    }

    /** Fetches the same complete profile on startup and every explicit reload. */
    public void refresh() {
        MainConfigManager messages = Raveon.INSTANCE.getMainConfigManager();
        Raveon.INSTANCE.reloadConfigurationAsync().whenComplete((ignored, error) -> {
            if (error != null) plugin.getLogger().warning(messages.plainMessage("logs.plugin-check-failed", "error", error.getMessage()));
        });
    }

    public CompletableFuture<Result> fetch(ChecksConfigManager candidate) {
        if (candidate.getApiKey().isEmpty())
            return CompletableFuture.completedFuture(new Result(Status.CONNECTED, "", PluginModelProfile.EMPTY, "", ""));
        URI url = URI.create(candidate.getAnalyzeServer()).resolve("/v1/auth/check");
        return send(HttpRequest.newBuilder(url).header("Authorization", "Bearer " + candidate.getApiKey()).GET());
    }

    public record Prepared(Result result, CloudPunishments.Policy local) {}

    public void startWatching() {
        watchTask = SchedulerUtils.runTimerAsync(plugin, this::requestRefresh, 100, 100);
    }

    public void stop() {
        stopped = true;
        if (watchTask != null) watchTask.cancel();
        pending.forEach(future -> future.cancel(true));
        pending.clear();
    }

    public void requestRefresh() {
        if (stopped || !plugin.isEnabled() || settings.get().getApiKey().isEmpty() || !polling.compareAndSet(false, true)) return;
        RuntimeConfiguration previous = Raveon.INSTANCE.getConfiguration();
        ChecksConfigManager candidate = previous.checks();
        var request = HttpRequest.newBuilder(URI.create(candidate.getAnalyzeServer()).resolve("/v1/auth/check"))
                .header("Authorization", "Bearer " + candidate.getApiKey()).GET();
        if (!candidate.getRemoteProfile().crc().isEmpty())
            request.header("If-None-Match", "\"" + candidate.getRemoteProfile().crc() + "\"");
        send(request, candidate.getRemoteProfile())
                .thenCompose(result -> prepare(previous, candidate, result))
                .thenCompose(prepared -> Raveon.INSTANCE.applyPreparedProfile(previous, candidate, prepared, false))
                .whenComplete((ignored, error) -> {
                    polling.set(false);
                    long now = System.nanoTime();
                    if (!stopped && error != null && now - lastFailure > java.util.concurrent.TimeUnit.SECONDS.toNanos(60)) {
                        lastFailure = now;
                        plugin.getLogger().warning("Cloud profile update failed; keeping current settings: " + error.getMessage());
                    }
                });
    }

    public CompletableFuture<Prepared> prepare(RuntimeConfiguration previous, ChecksConfigManager candidate, Result result) {
        if (result.status() != Status.CONNECTED)
            return CompletableFuture.failedFuture(new IllegalStateException(result.detail()));
        CompletableFuture<Prepared> prepared = new CompletableFuture<>();
        try {
            SchedulerUtils.run(plugin, () -> {
                try {
                    if (stopped || Raveon.INSTANCE.getConfiguration() != previous)
                        throw new IllegalStateException("Settings changed while fetching profile");
                    var local = new PunishmentConfigManager(plugin).getLocalPolicy();
                    var cloud = result.profile().punishments();
                    RuleSyncPlan plan = RuleSyncPlan.decide(cloud, previous.checks().getRemoteProfile().punishments().revision(), local, observedLocal);
                    if (plan.conflict()) plugin.getLogger().warning("Punishment rules changed locally and in the panel; newer panel revision wins");
                    if (!plan.upload()) { prepared.complete(new Prepared(result, local)); return; }
                    JsonObject body = new JsonObject();
                    body.addProperty("revision", cloud.revision());
                    body.add("policy", local.json());
                    var request = HttpRequest.newBuilder(URI.create(candidate.getAnalyzeServer()).resolve("/v1/plugin/punishments"))
                            .header("Authorization", "Bearer " + candidate.getApiKey()).header("Content-Type", "application/json")
                            .timeout(TIMEOUT).POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
                    track(httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())).whenComplete((response, error) -> {
                        if (error != null) { prepared.completeExceptionally(error); return; }
                        if (response.statusCode() == 409) plugin.getLogger().warning("Punishment revision conflict; current panel rules retained");
                        Result updated = describe(request, parse(response.statusCode() == 409 ? 200 : response.statusCode(), response.body()));
                        if (updated.status() == Status.CONNECTED) prepared.complete(new Prepared(updated, local));
                        else prepared.completeExceptionally(new IllegalStateException(updated.detail()));
                    });
                } catch (RuntimeException error) { prepared.completeExceptionally(error); }
            });
        } catch (RuntimeException error) { prepared.completeExceptionally(error); }
        return prepared;
    }

    public boolean localChanged(Prepared prepared) { return !prepared.local().equals(observedLocal); }

    public void commitRules(Prepared prepared, RuntimeConfiguration previous) {
        var latest = new PunishmentConfigManager(plugin).getLocalPolicy();
        if (!latest.equals(prepared.local())) throw new IllegalStateException("Local punishment file changed during sync; retry");
        var cloud = prepared.result().profile().punishments();
        if (cloud.mode().equals("sync") && cloud.policy() != null && !latest.equals(cloud.policy()))
            PunishmentConfigManager.writeCloudPolicy(plugin, cloud.policy());
    }

    public void acknowledge(Prepared prepared) {
        var cloud = prepared.result().profile().punishments();
        observedLocal = cloud.mode().equals("sync") && cloud.policy() != null ? cloud.policy() : prepared.local();
    }

    private <T> CompletableFuture<T> track(CompletableFuture<T> future) {
        pending.add(future);
        future.whenComplete((value, error) -> pending.remove(future));
        if (stopped) future.cancel(true);
        return future;
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

    private CompletableFuture<Result> send(HttpRequest.Builder request) { return send(request, null); }

    private CompletableFuture<Result> send(HttpRequest.Builder request, PluginModelProfile unchanged) {
        HttpRequest outgoing = request.timeout(TIMEOUT).build();
        return track(httpClient.sendAsync(outgoing, HttpResponse.BodyHandlers.ofString()))
                .thenApply(response -> response.statusCode() == 304 && unchanged != null
                        ? new Result(Status.CONNECTED, "", unchanged, "", "") : describe(outgoing, parse(response.statusCode(), response.body())))
                .exceptionally(error -> describe(outgoing, Result.failed(Status.UNAVAILABLE, String.valueOf(error.getMessage()))));
    }

    static Result describe(HttpRequest request, Result result) {
        return result.status() == Status.CONNECTED ? result
                : Result.failed(result.status(), request.method() + " " + request.uri() + ": " + result.detail());
    }

    private CompletableFuture<Result> apply(Result result) {
        CompletableFuture<Result> applied = new CompletableFuture<>();
        SchedulerUtils.run(plugin, () -> {
            try {
                ChecksConfigManager.saveConnection(plugin, result.apiKey());
                Raveon.INSTANCE.reloadConfigurationAsync().whenComplete((ignored, error) -> {
                    if (error == null) applied.complete(result);
                    else applied.completeExceptionally(error);
                });
            } catch (RuntimeException error) {
                applied.completeExceptionally(error);
            }
        });
        return applied;
    }

    static Result parse(int status, String body) {
        if (status < 200 || status >= 300) {
            String detail = "HTTP " + status;
            try {
                String reason = text(JsonParser.parseString(body).getAsJsonObject(), "detail");
                if (!reason.isBlank()) detail += ": " + reason;
            } catch (RuntimeException ignored) {
                // Reverse proxies often return plain text or HTML for errors.
            }
            return Result.failed(status == 401 || status == 403 ? Status.REJECTED : Status.UNAVAILABLE, detail);
        }
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            PluginModelProfile profile = PluginModelProfile.parse(json.getAsJsonObject("profile"));
            if (!models(json).equals(profile.models().keySet()))
                throw new IllegalArgumentException("Allowed models/profile mismatch");
            return new Result(Status.CONNECTED, text(json, "server"), profile, text(json, "api_key"), "");
        } catch (RuntimeException error) {
            return Result.failed(Status.UNAVAILABLE, "Invalid model profile (HTTP " + status + ")");
        }
    }

    private static Set<ModelId> models(JsonObject json) {
        Set<ModelId> models = new java.util.HashSet<>();
        if (!json.has("models")) return models;
        for (JsonElement element : json.getAsJsonArray("models")) {
            if (!models.add(ModelId.parse(element.getAsString()))) throw new IllegalArgumentException("Duplicate allowed model");
        }
        return models;
    }

    private static String text(JsonObject json, String field) {
        JsonElement value = json.get(field);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }
}
