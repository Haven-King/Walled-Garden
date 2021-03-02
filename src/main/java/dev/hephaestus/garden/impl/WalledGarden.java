package dev.hephaestus.garden.impl;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.hephaestus.garden.api.PlayerModVersionsContainer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.fabricmc.loader.api.*;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.LiteralText;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.Identifier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;

public class WalledGarden implements ModInitializer {
    private static final String MOD_ID = "walled-garden";

    public static final Logger LOG = LogManager.getLogger("WalledGarden");

    public static Identifier id(String path1, String... path) {
        return new Identifier(MOD_ID, path1 + (path.length > 0 ? "/" + String.join("/", path) : ""));
    }

    @Override
    public void onInitialize() {
        Config.read();

        SuggestionProvider<ServerCommandSource> conditionType = (context, builder) -> {
            builder.suggest("require");
            builder.suggest("blacklist");

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
            boolean blacklist = context.getArgument("condition", String.class)
                    .equalsIgnoreCase("blacklist");

            if (action.equalsIgnoreCase("get") || action.equalsIgnoreCase("remove")) {
                for (ModDependency dependency : blacklist ? Config.getBlacklistedMods() : Config.getRequiredMods()) {
                    builder.suggest(dependency.getModId());
                }
            } else if (action.equalsIgnoreCase("add") && !blacklist) {
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
                        )
                )
        );
    }

    private static int get(CommandContext<ServerCommandSource> context) {
        String action = context.getArgument("action", String.class);

        if (action.equalsIgnoreCase("list")) {
            String condition = context.getArgument("condition", String.class);
                boolean blacklist = condition.equals("blacklist");

                Collection<ModDependency> dependencies = blacklist ? Config.getBlacklistedMods() : Config.getRequiredMods();

                context.getSource().sendFeedback(new TranslatableText(blacklist
                        ? "command.walled-garden.list.blacklist"
                        : "command.walled-garden.list.required",
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
        String condition = context.getArgument("condition", String.class);

        if (action.equalsIgnoreCase("add")) {
            return add(context, "\"*\"");
        } else if (action.equalsIgnoreCase("remove")) {
            String modId = context.getArgument("modId", String.class);

            ModDependency removed = condition.equals("blacklist")
                    ? Config.unBlacklist(modId)
                    : Config.unRequire(modId);

            if (removed != null) {
                context.getSource().sendFeedback(new TranslatableText("command.walled-garden.remove." + condition, modId), true);
            }

            return 1;
        } else if (action.equalsIgnoreCase("get")) {
            String modId = context.getArgument("modId", String.class);

            ModDependency dependency = condition.equals("blacklist")
                    ? Config.getBlacklistedVersion(modId)
                    : Config.getRequiredVersion(modId);

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
        String condition = context.getArgument("condition", String.class);
        ModDependency dependency = DependencyUtil.dependency(modId, versionPredicate);

        if (dependency == null) return -1;

        if (condition.equalsIgnoreCase("blacklist")) {
            return blacklist(source, modId, dependency);
        } else if (condition.equalsIgnoreCase("require")) {
            return require(source, modId, dependency);
        }

        return 100;
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
                        if (predicate.getType() != VersionPredicate.Type.ANY) {
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

    public static boolean isBlacklisted(String modId, String modVersion) {
        try {
            ModDependency dependency = Config.getBlacklistedVersion(modId);
            return dependency != null && dependency.matches(SemanticVersion.parse(modVersion));
        } catch (VersionParsingException e) {
            return Config.getBlacklistedVersion(modId) != null;
        }
    }
}
