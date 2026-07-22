package com.luminex_studios.luminstatus.web;

import com.luminex_studios.luminstatus.config.StatusConfig;
import com.luminex_studios.luminstatus.model.Incident;
import com.luminex_studios.luminstatus.util.Text;

import java.time.Instant;
import java.util.List;

/**
 * Atom 1.0 feed of incidents.
 *
 * <p>Cheap to produce and it lets operators wire the status page into anything
 * that reads feeds without asking for an API integration.
 */
public final class FeedRenderer {

    public String render(StatusConfig config, List<Incident> incidents) {
        String base = config.page().publicUrl().isBlank()
                ? "urn:lumin:status"
                : config.page().publicUrl();

        StringBuilder out = new StringBuilder(4096);
        out.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        out.append("<feed xmlns=\"http://www.w3.org/2005/Atom\">\n");
        out.append("  <title>").append(Text.escapeHtml(config.page().title())).append("</title>\n");
        out.append("  <subtitle>").append(Text.escapeHtml(config.page().tagline())).append("</subtitle>\n");
        out.append("  <id>").append(Text.escapeHtml(base)).append("</id>\n");
        out.append("  <updated>").append(Text.rfc3339(latestChange(incidents))).append("</updated>\n");
        if (!config.page().publicUrl().isBlank()) {
            out.append("  <link rel=\"alternate\" href=\"")
                    .append(Text.escapeHtml(config.page().publicUrl())).append("\"/>\n");
        }

        for (Incident incident : incidents) {
            Instant updated = incident.updates().isEmpty()
                    ? incident.createdAt()
                    : incident.updates().get(incident.updates().size() - 1).at();
            out.append("  <entry>\n");
            out.append("    <title>")
                    .append(Text.escapeHtml("[" + incident.stage().label() + "] " + incident.title()))
                    .append("</title>\n");
            out.append("    <id>").append(Text.escapeHtml(base + "/incident/" + incident.id())).append("</id>\n");
            out.append("    <published>").append(Text.rfc3339(incident.createdAt())).append("</published>\n");
            out.append("    <updated>").append(Text.rfc3339(updated)).append("</updated>\n");
            out.append("    <content type=\"html\">");
            StringBuilder body = new StringBuilder();
            for (int i = incident.updates().size() - 1; i >= 0; i--) {
                Incident.Update update = incident.updates().get(i);
                body.append("<p><strong>").append(update.stage().label()).append("</strong> \u2014 ")
                        .append(Text.rfc3339(update.at())).append("<br>")
                        .append(Text.escapeHtml(update.message()))
                        .append("</p>");
            }
            out.append(Text.escapeHtml(body.toString()));
            out.append("</content>\n");
            out.append("  </entry>\n");
        }

        out.append("</feed>\n");
        return out.toString();
    }

    private Instant latestChange(List<Incident> incidents) {
        Instant latest = Instant.EPOCH;
        for (Incident incident : incidents) {
            for (Incident.Update update : incident.updates()) {
                if (update.at().isAfter(latest)) {
                    latest = update.at();
                }
            }
        }
        return latest == Instant.EPOCH ? Instant.now() : latest;
    }
}
