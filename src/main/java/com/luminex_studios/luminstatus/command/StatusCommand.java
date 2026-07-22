package com.luminex_studios.luminstatus.command;

import com.luminex_studios.luminstatus.LuminStatusPlugin;
import com.luminex_studios.luminstatus.model.Component;
import com.luminex_studios.luminstatus.model.ComponentState;
import com.luminex_studios.luminstatus.model.Incident;
import com.luminex_studios.luminstatus.util.Text;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * The operator-facing command tree.
 *
 * <p>Every incident action is reachable in game on purpose: during an outage the
 * staff who know what is happening are already logged in, and asking them to
 * open a web dashboard first is how status pages end up unmaintained.
 */
public final class StatusCommand {

    public static final String PERMISSION = "luminstatus.admin";

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final LuminStatusPlugin plugin;

    public StatusCommand(LuminStatusPlugin plugin) {
        this.plugin = plugin;
    }

    public BrigadierCommand build() {
        LiteralCommandNode<CommandSource> root = LiteralArgumentBuilder.<CommandSource>literal("luminstatus")
                .requires(source -> source.hasPermission(PERMISSION))
                .executes(this::overview)
                .then(LiteralArgumentBuilder.<CommandSource>literal("reload")
                        .executes(this::reload))
                .then(LiteralArgumentBuilder.<CommandSource>literal("refresh")
                        .executes(this::refresh))
                .then(LiteralArgumentBuilder.<CommandSource>literal("maintenance")
                        .then(RequiredArgumentBuilder.<CommandSource, String>argument("server", StringArgumentType.word())
                                .suggests(this::suggestServers)
                                .then(LiteralArgumentBuilder.<CommandSource>literal("on")
                                        .executes(context -> maintenance(context, true)))
                                .then(LiteralArgumentBuilder.<CommandSource>literal("off")
                                        .executes(context -> maintenance(context, false)))))
                .then(LiteralArgumentBuilder.<CommandSource>literal("incident")
                        .then(LiteralArgumentBuilder.<CommandSource>literal("list")
                                .executes(this::listIncidents))
                        .then(LiteralArgumentBuilder.<CommandSource>literal("create")
                                .then(RequiredArgumentBuilder.<CommandSource, String>argument("impact", StringArgumentType.word())
                                        .suggests(this::suggestImpacts)
                                        .then(RequiredArgumentBuilder.<CommandSource, String>argument("components", StringArgumentType.word())
                                                .suggests(this::suggestServerList)
                                                .then(RequiredArgumentBuilder.<CommandSource, String>argument("title", StringArgumentType.greedyString())
                                                        .executes(this::createIncident)))))
                        .then(LiteralArgumentBuilder.<CommandSource>literal("update")
                                .then(RequiredArgumentBuilder.<CommandSource, String>argument("id", StringArgumentType.word())
                                        .suggests(this::suggestOpenIncidents)
                                        .then(RequiredArgumentBuilder.<CommandSource, String>argument("stage", StringArgumentType.word())
                                                .suggests(this::suggestStages)
                                                .then(RequiredArgumentBuilder.<CommandSource, String>argument("message", StringArgumentType.greedyString())
                                                        .executes(this::updateIncident)))))
                        .then(LiteralArgumentBuilder.<CommandSource>literal("resolve")
                                .then(RequiredArgumentBuilder.<CommandSource, String>argument("id", StringArgumentType.word())
                                        .suggests(this::suggestOpenIncidents)
                                        .then(RequiredArgumentBuilder.<CommandSource, String>argument("message", StringArgumentType.greedyString())
                                                .executes(this::resolveIncident)))))
                .build();

        return new BrigadierCommand(root);
    }

    // ------------------------------------------------------------- subcommands

    private int overview(CommandContext<CommandSource> context) {
        CommandSource source = context.getSource();
        ComponentState overall = plugin.snapshot().overallState();
        send(source, "<dark_gray>\u2500\u2500\u2500 <white><bold>LuminStatus</bold></white> \u2500\u2500\u2500</dark_gray>");
        send(source, " <gray>Network:</gray> " + colored(overall, overall.label()));

        List<Component> components = plugin.snapshot().visibleComponents();
        if (components.isEmpty()) {
            send(source, " <gray>No servers registered on the proxy.</gray>");
        }
        for (Component component : components) {
            String latency = component.latencyMillis() >= 0 ? component.latencyMillis() + "ms" : "\u2014";
            String uptime = String.format(Locale.ROOT, "%.2f%%", plugin.history().uptime(component.id()) * 100);
            send(source, " <dark_gray>\u2022</dark_gray> <white>" + escape(component.displayName()) + "</white> "
                    + colored(component.state(), component.state().label())
                    + " <dark_gray>|</dark_gray> <gray>" + latency + " \u00b7 " + uptime + "</gray>");
        }

        List<Incident> active = plugin.incidents().active();
        if (!active.isEmpty()) {
            send(source, " <gray>Open incidents:</gray>");
            for (Incident incident : active) {
                send(source, "  <yellow>" + incident.id() + "</yellow> <white>"
                        + escape(incident.title()) + "</white> <gray>("
                        + incident.stage().label().toLowerCase(Locale.ROOT) + ", "
                        + Text.humanDuration(Duration.between(incident.createdAt(), Instant.now())) + ")</gray>");
            }
        }

        if (plugin.updateChecker() != null && plugin.updateChecker().updateAvailable()) {
            send(source, " <gold>Version " + plugin.updateChecker().latestVersion()
                    + " is available on SpigotMC.</gold>");
        }
        return 1;
    }

    private int reload(CommandContext<CommandSource> context) {
        if (plugin.reload()) {
            send(context.getSource(), "<green>Configuration reloaded.</green>");
        } else {
            send(context.getSource(), "<red>Reload failed. The previous configuration is still active. "
                    + "The console has the parse error.</red>");
        }
        return 1;
    }

    private int refresh(CommandContext<CommandSource> context) {
        plugin.publishNow();
        send(context.getSource(), "<green>Page rebuilt and exported.</green>");
        return 1;
    }

    private int maintenance(CommandContext<CommandSource> context, boolean enabled) {
        String server = context.getArgument("server", String.class);
        Optional<Component> found = plugin.monitor().component(server);
        if (found.isEmpty()) {
            send(context.getSource(), "<red>No server named " + escape(server) + " is registered on the proxy.</red>");
            return 0;
        }
        found.get().setMaintenance(enabled);
        plugin.publishNow();
        send(context.getSource(), enabled
                ? "<yellow>" + escape(found.get().displayName()) + " now shows as under maintenance.</yellow>"
                : "<green>" + escape(found.get().displayName()) + " is back to live monitoring.</green>");
        return 1;
    }

    private int listIncidents(CommandContext<CommandSource> context) {
        List<Incident> all = plugin.incidents().all();
        if (all.isEmpty()) {
            send(context.getSource(), "<gray>No incidents recorded.</gray>");
            return 1;
        }
        for (Incident incident : all) {
            send(context.getSource(), " <yellow>" + incident.id() + "</yellow> "
                    + (incident.resolved() ? "<green>[resolved]</green> " : "<red>[open]</red> ")
                    + "<white>" + escape(incident.title()) + "</white>");
        }
        return 1;
    }

    private int createIncident(CommandContext<CommandSource> context) {
        ComponentState impact = parseImpact(context.getArgument("impact", String.class));
        if (impact == null) {
            send(context.getSource(), "<red>Impact must be one of: degraded, maintenance, "
                    + "partial_outage, major_outage.</red>");
            return 0;
        }

        String raw = context.getArgument("components", String.class);
        Set<String> components = new LinkedHashSet<>();
        if (raw.equalsIgnoreCase("all")) {
            components.addAll(plugin.monitor().components().keySet());
        } else {
            for (String part : raw.split(",")) {
                String name = part.trim();
                if (name.isEmpty()) {
                    continue;
                }
                if (plugin.monitor().component(name).isEmpty()) {
                    send(context.getSource(), "<red>Unknown server: " + escape(name) + "</red>");
                    return 0;
                }
                components.add(name);
            }
        }
        if (components.isEmpty()) {
            send(context.getSource(), "<red>Name at least one server, or use \"all\".</red>");
            return 0;
        }

        String title = context.getArgument("title", String.class);
        Incident incident = plugin.incidents().create(title, impact, components, null);
        plugin.notifier().announceIncident(incident, "New incident");
        plugin.publishNow();
        send(context.getSource(), "<green>Incident <yellow>" + incident.id()
                + "</yellow> published.</green> <gray>Post updates with /luminstatus incident update "
                + incident.id() + " identified ...</gray>");
        return 1;
    }

    private int updateIncident(CommandContext<CommandSource> context) {
        String id = context.getArgument("id", String.class);
        Incident.Stage stage = Incident.Stage.parse(context.getArgument("stage", String.class));
        if (stage == Incident.Stage.RESOLVED) {
            send(context.getSource(), "<red>Use /luminstatus incident resolve to close an incident.</red>");
            return 0;
        }
        String message = context.getArgument("message", String.class);
        Optional<Incident> updated = plugin.incidents().update(id, stage, message);
        if (updated.isEmpty()) {
            send(context.getSource(), "<red>No incident with id " + escape(id) + ".</red>");
            return 0;
        }
        plugin.notifier().announceIncident(updated.get(), "Incident update");
        plugin.publishNow();
        send(context.getSource(), "<green>Update posted.</green>");
        return 1;
    }

    private int resolveIncident(CommandContext<CommandSource> context) {
        String id = context.getArgument("id", String.class);
        String message = context.getArgument("message", String.class);
        Optional<Incident> resolved = plugin.incidents().update(id, Incident.Stage.RESOLVED, message);
        if (resolved.isEmpty()) {
            send(context.getSource(), "<red>No incident with id " + escape(id) + ".</red>");
            return 0;
        }
        // Closing an incident lifts any maintenance flag it put in place.
        for (String componentId : resolved.get().components()) {
            plugin.monitor().component(componentId)
                    .filter(Component::maintenance)
                    .ifPresent(component -> component.setMaintenance(false));
        }
        plugin.notifier().announceIncident(resolved.get(), "Incident resolved");
        plugin.publishNow();
        send(context.getSource(), "<green>Incident " + escape(id) + " resolved.</green>");
        return 1;
    }

    // -------------------------------------------------------------- suggestions

    private CompletableFuture<Suggestions> suggestServers(CommandContext<CommandSource> context,
                                                          SuggestionsBuilder builder) {
        plugin.monitor().components().keySet().forEach(builder::suggest);
        return builder.buildFuture();
    }

    private CompletableFuture<Suggestions> suggestServerList(CommandContext<CommandSource> context,
                                                             SuggestionsBuilder builder) {
        builder.suggest("all");
        plugin.monitor().components().keySet().forEach(builder::suggest);
        return builder.buildFuture();
    }

    private CompletableFuture<Suggestions> suggestImpacts(CommandContext<CommandSource> context,
                                                          SuggestionsBuilder builder) {
        builder.suggest("degraded");
        builder.suggest("partial_outage");
        builder.suggest("major_outage");
        builder.suggest("maintenance");
        return builder.buildFuture();
    }

    private CompletableFuture<Suggestions> suggestStages(CommandContext<CommandSource> context,
                                                         SuggestionsBuilder builder) {
        builder.suggest("investigating");
        builder.suggest("identified");
        builder.suggest("monitoring");
        return builder.buildFuture();
    }

    private CompletableFuture<Suggestions> suggestOpenIncidents(CommandContext<CommandSource> context,
                                                                SuggestionsBuilder builder) {
        plugin.incidents().active().forEach(incident -> builder.suggest(incident.id()));
        return builder.buildFuture();
    }

    // ----------------------------------------------------------------- helpers

    private static ComponentState parseImpact(String raw) {
        return Arrays.stream(ComponentState.values())
                .filter(state -> state.id().equals(raw.toLowerCase(Locale.ROOT)))
                .filter(state -> state != ComponentState.OPERATIONAL)
                .findFirst()
                .orElse(null);
    }

    private static String colored(ComponentState state, String label) {
        return switch (state) {
            case OPERATIONAL -> "<green>" + label + "</green>";
            case DEGRADED -> "<yellow>" + label + "</yellow>";
            case MAINTENANCE -> "<gray>" + label + "</gray>";
            case PARTIAL_OUTAGE -> "<gold>" + label + "</gold>";
            case MAJOR_OUTAGE -> "<red>" + label + "</red>";
        };
    }

    /** Escapes MiniMessage tags in operator supplied text so titles cannot inject formatting. */
    private static String escape(String input) {
        return input.replace("<", "\\<");
    }

    private static void send(CommandSource source, String miniMessage) {
        source.sendMessage(MINI.deserialize(miniMessage));
    }
}
