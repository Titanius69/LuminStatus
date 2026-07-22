package com.luminex_studios.luminstatus.web;

import com.luminex_studios.luminstatus.config.StatusConfig;
import com.luminex_studios.luminstatus.util.Json;
import com.luminex_studios.luminstatus.util.Text;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Fills the bundled single-file template with the current snapshot.
 *
 * <p>The JSON is embedded directly into the document as well as being served
 * separately, so a page saved to disk or mirrored to object storage still shows
 * real data without a fetch succeeding.
 */
public final class PageRenderer {

    private final String template;

    public PageRenderer() throws IOException {
        try (InputStream in = PageRenderer.class.getResourceAsStream("/web/index.html")) {
            if (in == null) {
                throw new IOException("Bundled web/index.html is missing from the jar");
            }
            this.template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public String render(StatusConfig config, Json.Obj snapshot) {
        String json = Json.write(snapshot);
        // The payload sits inside a <script type="application/json"> block, so the
        // only sequence that can break out of it is a literal closing script tag.
        json = json.replace("</", "<\\/");
        return template
                .replace("__PAGE_TITLE__", Text.escapeHtml(config.page().title()))
                .replace("__PAGE_TAGLINE__", Text.escapeHtml(config.page().tagline()))
                .replace("__STATUS_JSON__", json);
    }
}
