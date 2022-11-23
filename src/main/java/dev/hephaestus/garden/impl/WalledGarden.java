package dev.hephaestus.garden.impl;

import com.google.common.collect.ImmutableSet;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.hephaestus.garden.api.PlayerModVersionsContainer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.registry.RegistryEntryAddedCallback;
import net.fabricmc.loader.api.*;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.api.metadata.version.VersionInterval;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.LiteralText;
import net.minecraft.text.MutableText;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.Identifier;
import net.minecraft.util.registry.Registry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

public class WalledGarden implements ModInitializer {
    private static final ImmutableSet<String> DEFAULT_WHITELIST = ImmutableSet.of("walled-garden", "minecraft", "java", "fabricloader", "fabric-api-base", "fabric", "fabric-api", "fabric-api-lookup-api-v1", "fabric-biome-api-v1", "fabric-blockrenderlayer-v1", "fabric-client-tags-api-v1", "fabric-convention-tags-v1", "fabric-commands-v0", "fabric-command-api-v1", "fabric-command-api-v2", "fabric-config-api-v1", "fabric-containers-v0", "fabric-content-registries-v0", "fabric-crash-report-info-v1", "fabric-data-generation-api-v1", "fabric-dimensions-v1", "fabric-entity-events-v1", "fabric-events-interaction-v0", "fabric-events-lifecycle-v0", "fabric-game-rule-api-v1", "fabric-item-api-v1", "fabric-item-groups-v0", "fabric-keybindings-v0", "fabric-key-binding-api-v1", "fabric-lifecycle-events-v1", "fabric-loot-tables-v1", "fabric-loot-api-v2", "fabric-message-api-v1", "fabric-mining-levels-v0", "fabric-mining-level-api-v1", "fabric-models-v0", "fabric-networking-v0", "fabric-networking-api-v1", "fabric-networking-blockentity-v0", "fabric-object-builder-api-v1", "fabric-object-builders-v0", "fabric-particles-v1", "fabric-registry-sync-v0", "fabric-renderer-api-v1", "fabric-renderer-indigo", "fabric-renderer-registries-v1", "fabric-rendering-v0", "fabric-rendering-v1", "fabric-rendering-data-attachment-v1", "fabric-rendering-fluids-v1", "fabric-resource-conditions-api-v1", "fabric-resource-loader-v0", "fabric-screen-api-v1", "fabric-screen-handler-api-v1", "fabric-structure-api-v1", "fabric-tag-extensions-v0", "fabric-textures-v0", "fabric-tool-attribute-api-v1", "fabric-transfer-api-v1", "fabric-transitive-access-wideners-v1");
    private static final String MOD_ID = "walled-garden";

    public static final Logger LOG = LogManager.getLogger("WalledGarden");

    public static Identifier id(String path1, String... path) {
        return new Identifier(MOD_ID, path1 + (path.length > 0 ? "/" + String.join("/", path) : ""));
    }

    @Override
    public void onInitialize() {
        Config.read();
        check(Registry.BLOCK, Registry.ITEM);

        SuggestionProvider<ServerCommandSource> conditionType = (context, builder) -> {
            for (Condition action : Condition.values()) builder.suggest(action.condition);

            return CompletableFuture.completedFuture(builder.build());
        };

        SuggestionProvider<ServerCommandSource> actions = (context, builder) -> {
            builder.suggest("add");
            builder.suggest("get");
            builder.suggest("list");
            builder.suggest("remove");

            return CompletableFuture.completedFuture(builder.build());
        };

        SuggestionProvider<ServerCommandSource> mods = (context, builder) -> {
            String action = context.getArgument("action", String.class);
            Condition condition = Condition.of(context.getArgument("condition", String.class));

            if (action.equalsIgnoreCase("get") || action.equalsIgnoreCase("remove")) {
                for (ModDependency dependency : condition.list()) {
                    builder.suggest(dependency.getModId());
                }
            } else if (action.equalsIgnoreCase("add") && condition != Condition.BLACKLISTED) {
                for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
                    builder.suggest(mod.getMetadata().getId());
                }
            }

            return CompletableFuture.completedFuture(builder.build());
        };

        CommandRegistrationCallback.EVENT.register(((dispatcher, dedicated) ->
                dispatcher.register(CommandManager.literal("wg")
                        .requires(source -> source.hasPermissionLevel(4))
                        .then(RequiredArgumentBuilder.<ServerCommandSource, String>argument("condition", StringArgumentType.string())
                                .suggests(conditionType)
                                        .then(RequiredArgumentBuilder.<ServerCommandSource, String>argument("action", StringArgumentType.string())
                                                .suggests(actions)
                                                .then(RequiredArgumentBuilder.<ServerCommandSource, String>argument("modId", StringArgumentType.string())
                                                        .suggests(mods)
                                                        .executes(WalledGarden::get)
                                                        .then(RequiredArgumentBuilder.<ServerCommandSource, String>argument("versionPredicate", StringArgumentType.string())
                                                                .executes(WalledGarden::addWithVersion))
                                                        .executes(WalledGarden::withoutVersion))
                                                .executes(WalledGarden::get)
                                        )
                                )
                        .then(LiteralArgumentBuilder.<ServerCommandSource>literal("require_mods_that_add_blocks_and_items")
                                .then(RequiredArgumentBuilder.<ServerCommandSource, Boolean>argument("required", BoolArgumentType.bool())
                                        .executes(WalledGarden::requireModsThatAddBlocksAndItems)
                                )
                        )
                ))
        );
    }

    private static int requireModsThatAddBlocksAndItems(CommandContext<ServerCommandSource> context) {
        Boolean required = context.getArgument("required", Boolean.class);

        Config.setRequireModsThatAddBlocksAndItems(required);

        MinecraftServer server = context.getSource().getMinecraftServer();
        PlayerVersionMap versions = (PlayerVersionMap) server;
        PlayerManager playerManager = server.getPlayerManager();

        for (ServerPlayerEntity player : playerManager.getPlayerList()) {
            PlayerModVersionsContainer playerVersions = versions.getModVersions(player.getGameProfile().getName());

            Map<String, String> missing = Config.getMissing(playerVersions.asMap());

            Optional<MutableText> text = checkRequiredMods(player.getGameProfile().getName(), missing);

            text.ifPresent(message -> player.networkHandler.disconnect(message));
        }

        return 0;
    }

    private static int get(CommandContext<ServerCommandSource> context) {
        String action = context.getArgument("action", String.class);

        if (action.equalsIgnoreCase("list")) {
            Condition condition = Condition.of(context.getArgument("condition", String.class));
            Collection<ModDependency> dependencies = condition.list();

            context.getSource().sendFeedback(new TranslatableText("command.walled-garden.list." + condition.condition,
                    dependencies.size()
            ), false);

            for (ModDependency dependency : dependencies) {
                context.getSource().sendFeedback(new LiteralText("  • " + DependencyUtil.toString(dependency)), false);
            }

            return 1;
        }

        return 100;
    }

    private static int addWithVersion(CommandContext<ServerCommandSource> context) {
        return add(context, '"' + context.getArgument("versionPredicate", String.class) + '"');
    }

    private static int withoutVersion(CommandContext<ServerCommandSource> context) {
        String action = context.getArgument("action", String.class);
        Condition condition = Condition.of(context.getArgument("condition", String.class));

        if (action.equalsIgnoreCase("add")) {
            return add(context, "\"*\"");
        } else if (action.equalsIgnoreCase("remove")) {
            String modId = context.getArgument("modId", String.class);

            ModDependency removed = condition.remove(modId);

            if (removed != null) {
                context.getSource().sendFeedback(new TranslatableText("command.walled-garden.remove." + condition.condition, modId), true);
            }

            return 1;
        } else if (action.equalsIgnoreCase("get")) {
            String modId = context.getArgument("modId", String.class);

            ModDependency dependency = condition.get(modId);

            context.getSource().sendFeedback(dependency == null
                            ? new TranslatableText("command.walled-garden.not-found", modId)
                            : new LiteralText(DependencyUtil.toString(dependency)), false);

            return 1;
        }

        return 100;
    }

    private static int add(CommandContext<ServerCommandSource> context, String versionPredicate) {
        ServerCommandSource source = context.getSource();
        String modId = context.getArgument("modId", String.class);
        Condition condition = Condition.of(context.getArgument("condition", String.class));
        ModDependency dependency = DependencyUtil.dependency(modId, versionPredicate);

        if (dependency == null) return -1;

        return condition.add(source, modId, dependency);
    }

    private static int require(ServerCommandSource source, String modId, ModDependency dependency) {
        Config.require(modId, dependency);

        MinecraftServer server = source.getMinecraftServer();
        PlayerVersionMap versions = (PlayerVersionMap) server;
        PlayerManager playerManager = server.getPlayerManager();

        for (ServerPlayerEntity player : playerManager.getPlayerList()) {
            PlayerModVersionsContainer playerVersions = versions.getModVersions(player.getGameProfile().getName());
            String versionString = playerVersions.getVersion(modId);

            boolean disconnect = false;

            if (versionString == null) {
                disconnect = true;
            } else {
                try {
                    SemanticVersion version = SemanticVersion.parse(versionString);
                    disconnect = !dependency.matches(version);
                } catch (VersionParsingException ignored) {
                    for (VersionPredicate predicate : dependency.getVersionRequirements()) {
                        if (predicate.getInterval() != VersionInterval.INFINITE) {
                            disconnect = true;
                            break;
                        }
                    }
                }
            }

            if (disconnect) {
                player.networkHandler.disconnect(
                        new TranslatableText("message.walled-garden.required",
                                "\n" + DependencyUtil.toString(dependency))
                );
            }
        }

        source.sendFeedback(
                new TranslatableText("command.walled-garden.required", DependencyUtil.toString(dependency)),
                true);

        return 1;
    }

    private static int blacklist(ServerCommandSource source, String modId, ModDependency dependency) {
        Config.blacklist(modId, dependency);

        MinecraftServer server = source.getMinecraftServer();
        PlayerVersionMap versions = (PlayerVersionMap) server;
        PlayerManager playerManager = server.getPlayerManager();

        for (ServerPlayerEntity player : playerManager.getPlayerList()) {
            PlayerModVersionsContainer playerVersions = versions.getModVersions(player.getGameProfile().getName());
            String version = playerVersions.getVersion(modId);

            if (version != null && isBlacklisted(modId, version)) {
                player.networkHandler.disconnect(
                        new TranslatableText("message.walled-garden.blacklist",
                                "\n" + DependencyUtil.toString(dependency))
                );
            }
        }

        source.sendFeedback(
                new TranslatableText("command.walled-garden.blacklist", DependencyUtil.toString(dependency)), true);

        return 1;
    }

    private static int whitelist(ServerCommandSource source, String modId, ModDependency dependency) {
        Config.whitelist(modId, dependency);

        MinecraftServer server = source.getMinecraftServer();
        PlayerVersionMap versions = (PlayerVersionMap) server;
        PlayerManager playerManager = server.getPlayerManager();

        for (ServerPlayerEntity player : playerManager.getPlayerList()) {
            PlayerModVersionsContainer playerVersions = versions.getModVersions(player.getGameProfile().getName());

            StringBuilder builder = new StringBuilder();

            for (Map.Entry<String, String> modVersions : playerVersions) {
                if (!isWhitelisted(modVersions.getKey(), modVersions.getValue())) {
                    builder.append("\n").append(modVersions.getKey());
                }
            }

            String message = builder.toString();

            if (!message.isEmpty()) {
                player.networkHandler.disconnect(
                        new TranslatableText("message.walled-garden.whitelist",
                                message)
                );
            }
        }

        source.sendFeedback(
                new TranslatableText("command.walled-garden.whitelist", DependencyUtil.toString(dependency)), true);

        return 1;
    }

    public static boolean isBlacklisted(String modId, String modVersion) {
        try {
            ModDependency dependency = Config.getBlacklistedVersion(modId);
            return dependency != null && dependency.matches(SemanticVersion.parse(modVersion));
        } catch (VersionParsingException e) {
            return Config.getBlacklistedVersion(modId) != null;
        }
    }

    public static boolean isWhitelisted(String modId, String modVersion) {
        if (Config.getWhitelistedMods().isEmpty()) return true;

        if (DEFAULT_WHITELIST.contains(modId)) return true;

        try {
            ModDependency dependency = Config.getWhitelistedVersion(modId);
            return dependency != null && dependency.matches(SemanticVersion.parse(modVersion));
        } catch (VersionParsingException e) {
            return Config.getWhitelistedVersion(modId) != null;
        }
    }

    public static boolean isRequired(String modId, String modVersion) {
        if (Config.getRequiredMods().isEmpty()) return false;

        try {
            ModDependency dependency = Config.getRequiredVersion(modId);
            return dependency != null && dependency.matches(SemanticVersion.parse(modVersion));
        } catch (VersionParsingException e) {
            return Config.getRequiredVersion(modId) != null;
        }
    }

    private static final Map<String, Condition> CONDITIONS = new HashMap<>();

    enum Condition {
        BLACKLISTED("blacklist", Config::getBlacklistedMods, WalledGarden::blacklist, Config::getBlacklistedVersion, Config::unBlacklist),
        REQUIRED("required", Config::getRequiredMods, WalledGarden::require, Config::getRequiredVersion, Config::unRequire),
        WHITELISTED("whitelist", Config::getWhitelistedMods, WalledGarden::whitelist, Config::getWhitelistedVersion, Config::unWhitelist);

        public final String condition;
        private final Supplier<Collection<ModDependency>> list;
        private final Adder adder;
        private final Function<String, @Nullable ModDependency> getter;
        private final Function<String, @Nullable ModDependency> remover;

        Condition(String condition, Supplier<Collection<ModDependency>> list, Adder adder, Function<String, @Nullable ModDependency> getter, Function<String, @Nullable ModDependency> remover) {
            this.condition = condition;
            this.list = list;
            this.adder = adder;
            this.getter = getter;
            this.remover = remover;
            CONDITIONS.put(condition, this);
        }

        public static WalledGarden.Condition of(String condition) {
            return CONDITIONS.get(condition);
        }

        public Collection<ModDependency> list() {
            return this.list.get();
        }

        public int add(ServerCommandSource source, String modId, ModDependency dependency) {
            return this.adder.add(source, modId, dependency);
        }

        public @Nullable ModDependency get(String modId) {
            return this.getter.apply(modId);
        }

        public @Nullable ModDependency remove(String modId) {
            return this.remover.apply(modId);
        }
    }

    @FunctionalInterface
    private interface Adder {
        int add(ServerCommandSource source, String modId, ModDependency dependency);
    }

    private static void check(Registry<?>... registries) {
        for (Registry<?> registry: registries) {
            for (Identifier id : registry.getIds()) {
                Config.addsBlockOrItem(id.getNamespace());
            }

            RegistryEntryAddedCallback.event(registry).register((rawId, id, object) ->
                    Config.addsBlockOrItem(id.getNamespace()));
        }
    }

    public static Optional<MutableText> checkBlacklist(String playerName, Map<String, String> blackListed) {
        if (blackListed.isEmpty()) return Optional.empty();

        LOG.info("{} tried to join with disallowed mods:", playerName);

        StringBuilder builder = new StringBuilder();

        for (Map.Entry<String, String> entry : blackListed.entrySet()) {
            String modId = entry.getKey();
            String modVersion = entry.getValue();
            LOG.info("\t{}: {}", modId, modVersion);

            builder.append("\n");

            ModDependency dependency = Config.getBlacklistedVersion(modId);
            builder.append(dependency == null ? modId : dependency);
        }

        return Optional.of(new TranslatableText("message.walled-garden.blacklist", builder.toString()));
    }

    public static Optional<MutableText> checkRequiredMods(String playerName, Map<String, String> missingMods){
        if (missingMods.isEmpty()) return Optional.empty();

        LOG.info("{} tried to join without the following mods:", playerName);

        for (Map.Entry<String, String> entry : missingMods.entrySet()) {
            LOG.info("\t{}: {}", entry.getKey(), entry.getValue());
        }

        return Optional.of(new TranslatableText("message.walled-garden.required")
                .append(DependencyUtil.getTextWithLinks(missingMods)));
    }
}
