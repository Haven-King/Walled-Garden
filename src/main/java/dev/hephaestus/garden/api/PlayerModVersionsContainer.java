package dev.hephaestus.garden.api;

import dev.hephaestus.garden.impl.PlayerVersionMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

public interface PlayerModVersionsContainer extends Iterable<Map.Entry<String, String>> {
    static PlayerModVersionsContainer of(MinecraftServer server, String playerName) {
        return ((PlayerVersionMap) server).getModVersions(playerName);
    }

    static PlayerModVersionsContainer of(ServerPlayerEntity playerEntity) {
        return of(playerEntity.server, playerEntity.getGameProfile().getName());
    }

    @Nullable String getVersion(String modId);
    Map<String, String> asMap();
}
