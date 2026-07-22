package com.luminex_studios.luminstatus.health;

import com.luminex_studios.luminstatus.config.StatusConfig;
import com.luminex_studios.luminstatus.history.HistoryStore;
import com.luminex_studios.luminstatus.model.Component;
import com.luminex_studios.luminstatus.model.ComponentState;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.slf4j.Logger;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * Active and passive health measurement for every registered backend.
 *
 * <p>Active checks use {@link RegisteredServer#ping()}, which the proxy already
 * speaks and which returns latency, player count and version in one round trip.
 * Passive checks reuse connection failures the proxy observes while routing real
 * players, which costs nothing and reacts faster than the poll interval.
 */
public final class HealthMonitor {

    private final ProxyServer proxy;
    private final Logger logger;
    private final HistoryStore history;
    private final Map<String, Component> components = new ConcurrentHashMap<>();
    private final Map<String, LatencyBaseline> baselines = new ConcurrentHashMap<>();

    private volatile StatusConfig config;
    private volatile BiConsumer<Component, ComponentState> stateChangeListener = (component, previous) -> {
    };

    public HealthMonitor(ProxyServer proxy, Logger logger, HistoryStore history, StatusConfig config) {
        this.proxy = proxy;
        this.logger = logger;
        this.history = history;
        this.config = config;
    }

    /** Called with the component and its previous state whenever the state flips. */
    public void onStateChange(BiConsumer<Component, ComponentState> listener) {
        this.stateChangeListener = listener;
    }

    public void applyConfig(StatusConfig config) {
        this.config = config;
        syncComponents();
    }

    public Map<String, Component> components() {
        return components;
    }

    public Optional<Component> component(String id) {
        return Optional.ofNullable(components.get(id));
    }

    /**
     * Rebuilds the component list from the proxy's registered servers. Called on
     * startup and on reload, so servers added to velocity.toml appear without
     * touching this plugin's config.
     */
    public void syncComponents() {
        StatusConfig current = config;
        for (RegisteredServer server : proxy.getAllServers()) {
            String name = server.getServerInfo().getName();
            StatusConfig.ComponentOverride override = current.override(name);
            String displayName = override != null && !override.displayName().isBlank()
                    ? override.displayName()
                    : name;
            String group = override != null ? override.group() : "";
            boolean hidden = override != null && override.hidden();
            components.compute(name, (key, existing) -> {
                if (existing != null
                        && existing.displayName().equals(displayName)
                        && existing.hidden() == hidden) {
                    return existing;
                }
                return new Component(name, displayName, group, hidden);
            });
            baselines.computeIfAbsent(name, key -> new LatencyBaseline(current.monitoring().baselineSamples()));
        }
        components.keySet().removeIf(name -> proxy.getServer(name).isEmpty());
    }

    /** Runs one round of active checks. Every ping is asynchronous. */
    public void runChecks() {
        StatusConfig current = config;
        for (RegisteredServer server : proxy.getAllServers()) {
            String name = server.getServerInfo().getName();
            Component component = components.get(name);
            if (component == null) {
                continue;
            }
            long startedAt = System.nanoTime();
            server.ping()
                    .orTimeout(current.monitoring().timeoutSeconds(), TimeUnit.SECONDS)
                    .whenComplete((ping, error) -> {
                        ComponentState previous = component.state();
                        if (error != null || ping == null) {
                            String reason = error == null ? "empty response" : rootMessage(error);
                            component.recordFailure(reason, current.monitoring().failureThreshold());
                        } else {
                            long latency = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
                            LatencyBaseline baseline = baselines
                                    .computeIfAbsent(name, key -> new LatencyBaseline(current.monitoring().baselineSamples()));
                            boolean degraded = baseline.isDegraded(latency, current.monitoring().degradedMultiplier());
                            baseline.add(latency);
                            int online = ping.getPlayers().map(players -> players.getOnline()).orElse(-1);
                            component.recordSuccess(latency, online, degraded);
                        }
                        history.record(name, component.state(), component.latencyMillis());
                        ComponentState now = component.state();
                        if (now != previous) {
                            logger.info("Component {} moved from {} to {}", name, previous.id(), now.id());
                            stateChangeListener.accept(component, previous);
                        }
                    });
        }
    }

    /**
     * Records a connection failure the proxy saw while routing a real player.
     * Free evidence, so it is applied immediately, but it still honours the
     * flapping threshold before flipping the component.
     */
    public void recordPassiveFailure(String serverName, String reason) {
        if (!config.monitoring().passiveChecks()) {
            return;
        }
        Component component = components.get(serverName);
        if (component == null) {
            return;
        }
        ComponentState previous = component.state();
        component.recordFailure(reason, config.monitoring().failureThreshold());
        ComponentState now = component.state();
        if (now != previous) {
            logger.info("Component {} moved from {} to {} (observed while routing a player)",
                    serverName, previous.id(), now.id());
            stateChangeListener.accept(component, previous);
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable cursor = error;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        String type = cursor.getClass().getSimpleName();
        return message == null || message.isBlank() ? type : type + ": " + message;
    }
}
