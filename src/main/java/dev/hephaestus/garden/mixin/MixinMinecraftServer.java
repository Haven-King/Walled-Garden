package dev.hephaestus.garden.mixin;

import dev.hephaestus.garden.api.PlayerModVersionsContainer;
import dev.hephaestus.garden.impl.PlayerModVersionsContainerImpl;
import dev.hephaestus.garden.impl.PlayerVersionMap;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.HashMap;
import java.util.Map;

@Mixin(MinecraftServer.class)
public class MixinMinecraftServer implements PlayerVersionMap {
    @Unique private final Map<String, PlayerModVersionsContainer> playerVersions = new HashMap<>();


    @Override
    public PlayerModVersionsContainer getModVersions(String playerName) {
        return playerVersions.computeIfAbsent(playerName, id -> new PlayerModVersionsContainerImpl());
    }
}
