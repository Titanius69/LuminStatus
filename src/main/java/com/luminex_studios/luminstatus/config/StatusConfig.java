package com.luminex_studios.luminstatus.config;

import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Typed view over {@code config.conf}.
 *
 * <p>Values are read once at load time into immutable records. Reloading builds
 * a brand new instance rather than mutating this one, so readers never observe a
 * half-updated configuration.
 */
public final class StatusConfig {

    /** Per-component presentation overrides. */
    public record ComponentOverride(String displayName, String group, boolean hidden) {
    }

    public record Page(String title, String tagline, String publicUrl, String supportUrl, String footer) {
    }

    public record Monitoring(int intervalSeconds,
                             int timeoutSeconds,
                             int failureThreshold,
                             int recoveryThreshold,
                             double degradedMultiplier,
                             int baselineSamples,
                             boolean passiveChecks) {
    }

    public record Http(boolean enabled, String bindAddress, int port, int workerThreads) {
    }

    public record StaticExport(boolean enabled, String directory, int intervalSeconds) {
    }

    public record Discord(boolean enabled, String webhookUrl, int minDurationSeconds, String mention) {
    }

    public record History(int retentionDays) {
    }

    public record UpdateChecker(boolean enabled, int resourceId, int intervalHours) {
    }

    private final Page page;
    private final Monitoring monitoring;
    private final Http http;
    private final StaticExport staticExport;
    private final Discord discord;
    private final History history;
    private final UpdateChecker updateChecker;
    private final Map<String, ComponentOverride> overrides;

    private StatusConfig(Page page,
                         Monitoring monitoring,
                         Http http,
                         StaticExport staticExport,
                         Discord discord,
                         History history,
                         UpdateChecker updateChecker,
                         Map<String, ComponentOverride> overrides) {
        this.page = page;
        this.monitoring = monitoring;
        this.http = http;
        this.staticExport = staticExport;
        this.discord = discord;
        this.history = history;
        this.updateChecker = updateChecker;
        this.overrides = Map.copyOf(overrides);
    }

    public Page page() {
        return page;
    }

    public Monitoring monitoring() {
        return monitoring;
    }

    public Http http() {
        return http;
    }

    public StaticExport staticExport() {
        return staticExport;
    }

    public Discord discord() {
        return discord;
    }

    public History history() {
        return history;
    }

    public UpdateChecker updateChecker() {
        return updateChecker;
    }

    /** Override for a server name, or {@code null} when the defaults apply. */
    public ComponentOverride override(String serverName) {
        return overrides.get(serverName.toLowerCase(Locale.ROOT));
    }

    /**
     * Loads the configuration, writing the bundled defaults first if the file is
     * missing.
     *
     * @throws ConfigurateException if the file exists but cannot be parsed; the
     *                              caller is expected to abort startup rather
     *                              than silently run on defaults
     */
    public static StatusConfig load(Path dataDirectory) throws ConfigurateException, IOException {
        Files.createDirectories(dataDirectory);
        Path file = dataDirectory.resolve("config.conf");
        if (Files.notExists(file)) {
            try (InputStream in = StatusConfig.class.getResourceAsStream("/config.conf")) {
                if (in == null) {
                    throw new IOException("Bundled config.conf is missing from the jar");
                }
                Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        HoconConfigurationLoader loader = HoconConfigurationLoader.builder()
                .path(file)
                .build();
        CommentedConfigurationNode root = loader.load();

        Page page = new Page(
                root.node("page", "title").getString("Network Status"),
                root.node("page", "tagline").getString("Live availability of every server on the network."),
                root.node("page", "public-url").getString(""),
                root.node("page", "support-url").getString(""),
                root.node("page", "footer").getString("Powered by LuminStatus")
        );

        Monitoring monitoring = new Monitoring(
                clamp(root.node("monitoring", "interval-seconds").getInt(30), 5, 3600),
                clamp(root.node("monitoring", "timeout-seconds").getInt(5), 1, 60),
                clamp(root.node("monitoring", "failure-threshold").getInt(3), 1, 20),
                clamp(root.node("monitoring", "recovery-threshold").getInt(2), 1, 20),
                Math.max(1.2D, root.node("monitoring", "degraded-multiplier").getDouble(3.0D)),
                clamp(root.node("monitoring", "baseline-samples").getInt(60), 5, 1000),
                root.node("monitoring", "passive-checks").getBoolean(true)
        );

        Http http = new Http(
                root.node("http", "enabled").getBoolean(false),
                root.node("http", "bind-address").getString("0.0.0.0"),
                clamp(root.node("http", "port").getInt(8080), 1, 65535),
                clamp(root.node("http", "worker-threads").getInt(2), 1, 16)
        );

        StaticExport staticExport = new StaticExport(
                root.node("static-export", "enabled").getBoolean(true),
                root.node("static-export", "directory").getString("public"),
                clamp(root.node("static-export", "interval-seconds").getInt(60), 10, 3600)
        );

        Discord discord = new Discord(
                root.node("discord", "enabled").getBoolean(false),
                root.node("discord", "webhook-url").getString(""),
                clamp(root.node("discord", "min-duration-seconds").getInt(120), 0, 3600),
                root.node("discord", "mention").getString("")
        );

        History history = new History(clamp(root.node("history", "retention-days").getInt(90), 7, 365));

        UpdateChecker updateChecker = new UpdateChecker(
                root.node("update-checker", "enabled").getBoolean(true),
                root.node("update-checker", "resource-id").getInt(0),
                clamp(root.node("update-checker", "interval-hours").getInt(6), 1, 168)
        );

        Map<String, ComponentOverride> overrides = new LinkedHashMap<>();
        for (Map.Entry<Object, CommentedConfigurationNode> entry : root.node("components").childrenMap().entrySet()) {
            String key = String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT);
            CommentedConfigurationNode node = entry.getValue();
            overrides.put(key, new ComponentOverride(
                    node.node("display-name").getString(""),
                    node.node("group").getString(""),
                    node.node("hidden").getBoolean(false)
            ));
        }

        return new StatusConfig(page, monitoring, http, staticExport, discord, history, updateChecker, overrides);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
