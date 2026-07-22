package com.luminex_studios.luminstatus.notify;

import com.luminex_studios.luminstatus.config.StatusConfig;
import com.luminex_studios.luminstatus.model.Component;
import com.luminex_studios.luminstatus.model.ComponentState;
import com.luminex_studios.luminstatus.model.Incident;
import com.luminex_studios.luminstatus.util.Json;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sends state changes to a Discord webhook, with flapping protection.
 *
 * <p>A change is not announced the moment it happens. It is held until the
 * component has stayed in the new state for the configured settle time, and
 * dropped entirely if it flips back before then. Without that, one bad thirty
 * seconds turns the alert channel into noise and people stop reading it, which
 * costs more than missing the alert would have.
 */
public final class DiscordNotifier {

    private record Pending(ComponentState from, ComponentState to, Instant dueAt) {
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final Logger logger;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private volatile StatusConfig config;

    public DiscordNotifier(StatusConfig config, Logger logger) {
        this.config = config;
        this.logger = logger;
    }

    public void applyConfig(StatusConfig config) {
        this.config = config;
    }

    /** Queues a component change. Nothing is sent until {@link #flush()} runs. */
    public void queue(Component component, ComponentState previous) {
        StatusConfig current = config;
        if (!current.discord().enabled() || current.discord().webhookUrl().isBlank()) {
            return;
        }
        ComponentState now = component.state();
        Pending existing = pending.get(component.id());
        if (existing != null && existing.from() == now) {
            // Flapped straight back to the last announced state: cancel entirely.
            pending.remove(component.id());
            return;
        }
        pending.put(component.id(), new Pending(previous, now,
                Instant.now().plusSeconds(current.discord().minDurationSeconds())));
    }

    /** Sends any queued change that has settled. Called on the monitor tick. */
    public void flush(Map<String, Component> components) {
        StatusConfig current = config;
        if (!current.discord().enabled() || current.discord().webhookUrl().isBlank()) {
            pending.clear();
            return;
        }
        Instant now = Instant.now();
        pending.entrySet().removeIf(entry -> {
            Pending value = entry.getValue();
            if (now.isBefore(value.dueAt())) {
                return false;
            }
            Component component = components.get(entry.getKey());
            if (component == null || component.state() != value.to()) {
                return true;
            }
            send(buildComponentPayload(current, component, value));
            return true;
        });
    }

    /** Incidents are operator-authored, so they are sent immediately. */
    public void announceIncident(Incident incident, String headline) {
        StatusConfig current = config;
        if (!current.discord().enabled() || current.discord().webhookUrl().isBlank()) {
            return;
        }
        Json.Obj embed = Json.obj()
                .put("title", headline + ": " + incident.title())
                .put("color", incident.resolved()
                        ? ComponentState.OPERATIONAL.color()
                        : incident.impact().color())
                .put("timestamp", Instant.now().toString())
                .put("footer", Json.obj().put("text", "Incident " + incident.id()));

        if (!incident.updates().isEmpty()) {
            Incident.Update latest = incident.updates().get(incident.updates().size() - 1);
            embed.put("description", "**" + latest.stage().label() + "** \u2014 " + latest.message());
        }

        Json.Obj payload = Json.obj()
                .put("username", current.page().title())
                .put("embeds", Json.arr().add(embed));
        if (!current.discord().mention().isBlank() && !incident.resolved()) {
            payload.put("content", current.discord().mention());
        }
        send(payload);
    }

    private Json.Obj buildComponentPayload(StatusConfig current, Component component, Pending change) {
        String verb = change.to().isUp() ? "recovered" : "went down";
        Json.Obj embed = Json.obj()
                .put("title", component.displayName() + " " + verb)
                .put("description", change.from().label() + " \u2192 " + change.to().label())
                .put("color", change.to().color())
                .put("timestamp", Instant.now().toString());

        Json.Arr fields = Json.arr();
        if (component.latencyMillis() >= 0) {
            fields.add(Json.obj().put("name", "Latency")
                    .put("value", component.latencyMillis() + " ms").put("inline", true));
        }
        if (component.onlinePlayers() >= 0) {
            fields.add(Json.obj().put("name", "Players")
                    .put("value", String.valueOf(component.onlinePlayers())).put("inline", true));
        }
        if (fields.size() > 0) {
            embed.put("fields", fields);
        }

        Json.Obj payload = Json.obj()
                .put("username", current.page().title())
                .put("embeds", Json.arr().add(embed));
        if (!current.discord().mention().isBlank() && !change.to().isUp()) {
            payload.put("content", current.discord().mention());
        }
        return payload;
    }

    private void send(Json.Obj payload) {
        String url = config.discord().webhookUrl();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "LuminStatus")
                    .POST(HttpRequest.BodyPublishers.ofString(Json.write(payload), StandardCharsets.UTF_8))
                    .build();
            http.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .thenAccept(response -> {
                        if (response.statusCode() >= 300) {
                            logger.warn("Discord webhook rejected the message with HTTP {}", response.statusCode());
                        }
                    })
                    .exceptionally(error -> {
                        logger.warn("Could not reach the Discord webhook: {}", error.getMessage());
                        return null;
                    });
        } catch (IllegalArgumentException ex) {
            logger.warn("The configured Discord webhook URL is not a valid URL");
        }
    }
}
