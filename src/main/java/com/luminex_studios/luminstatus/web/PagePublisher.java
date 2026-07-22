package com.luminex_studios.luminstatus.web;

import com.luminex_studios.luminstatus.config.StatusConfig;
import com.luminex_studios.luminstatus.history.HistoryStore;
import com.luminex_studios.luminstatus.incident.IncidentManager;
import com.luminex_studios.luminstatus.model.Incident;
import com.luminex_studios.luminstatus.util.Json;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns the rendered artefacts and keeps them in one place.
 *
 * <p>Rendering happens once per refresh on a background thread; the HTTP handler
 * and the static exporter both read the cached result. That keeps request
 * handling allocation-free and means a burst of traffic during an outage cannot
 * turn into a burst of rendering work.
 */
public final class PagePublisher {

    /** An immutable set of rendered artefacts. */
    public record Rendered(String html, String json, String feed, long generatedAt) {
    }

    private final StatusSnapshot snapshot;
    private final IncidentManager incidents;
    private final PageRenderer pageRenderer;
    private final FeedRenderer feedRenderer = new FeedRenderer();
    private final Logger logger;
    private final AtomicReference<Rendered> current = new AtomicReference<>(
            new Rendered("<!doctype html><title>Starting</title>", "{}", "", 0L));

    private volatile StatusConfig config;

    public PagePublisher(StatusSnapshot snapshot,
                         IncidentManager incidents,
                         StatusConfig config,
                         Logger logger) throws IOException {
        this.snapshot = snapshot;
        this.incidents = incidents;
        this.config = config;
        this.logger = logger;
        this.pageRenderer = new PageRenderer();
    }

    public void applyConfig(StatusConfig config) {
        this.config = config;
    }

    public Rendered current() {
        return current.get();
    }

    /** Re-renders every artefact from the live state. */
    public Rendered refresh() {
        StatusConfig active = config;
        Json.Obj payload = snapshot.build(active);
        String json = Json.write(payload);
        String html = pageRenderer.render(active, payload);

        List<Incident> feedIncidents = new ArrayList<>(incidents.active());
        feedIncidents.addAll(incidents.recentlyResolved(25));
        String feed = feedRenderer.render(active, feedIncidents);

        Rendered rendered = new Rendered(html, json, feed, System.currentTimeMillis());
        current.set(rendered);
        return rendered;
    }

    /**
     * Writes the artefacts to a directory. Used for the static export mode, where
     * the files are then served by a web server, GitHub Pages or object storage.
     * That mode is the recommended default: a status page hosted on the machine
     * that just went down is not a status page.
     */
    public void exportTo(Path directory) {
        Rendered rendered = current();
        HistoryStore.writeAtomically(directory.resolve("index.html"), rendered.html(), logger);
        HistoryStore.writeAtomically(directory.resolve("status.json"), rendered.json(), logger);
        HistoryStore.writeAtomically(directory.resolve("feed.atom"), rendered.feed(), logger);
    }
}
