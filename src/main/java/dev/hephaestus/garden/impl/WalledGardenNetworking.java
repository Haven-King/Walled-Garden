package dev.hephaestus.garden.impl;

import dev.hephaestus.garden.mixin.GameProfileAccessor;
import io.netty.buffer.Unpooled;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerLoginConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerLoginNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientLoginNetworkHandler;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginNetworkHandler;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public class WalledGardenNetworking implements ModInitializer, ClientModInitializer {
	private static final Identifier MOD_VALIDATION_CHANNEL = WalledGarden.id("channel", "mod_validation");
	private static final Text REQUEST_NOT_UNDERSTOOD = Text.literal("Please install the Walled Garden mod to play on this server.");
	private static final Text ALSO_REQUIRED = Text.literal("The following mods are also required:");

	@Override
	public void onInitialize() {
		ServerLoginNetworking.registerGlobalReceiver(MOD_VALIDATION_CHANNEL, WalledGardenNetworking::handleResponse);
		ServerLoginConnectionEvents.QUERY_START.register(WalledGardenNetworking::request);
	}

	@Override
	@Environment(EnvType.CLIENT)
	public void onInitializeClient() {
		ClientLoginNetworking.registerGlobalReceiver(MOD_VALIDATION_CHANNEL, WalledGardenNetworking::response);
	}

	private static void request(ServerLoginNetworkHandler handler, MinecraftServer server, PacketSender sender, ServerLoginNetworking.LoginSynchronizer loginSynchronizer) {
		sender.sendPacket(MOD_VALIDATION_CHANNEL, PacketByteBufs.empty());
	}

	@Environment(EnvType.CLIENT)
	private static CompletableFuture<PacketByteBuf> response(MinecraftClient client, ClientLoginNetworkHandler handler, PacketByteBuf empty, Consumer<GenericFutureListener<? extends Future<? super Void>>> genericFutureListenerConsumer) {
		PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());

		Collection<ModContainer> mods = FabricLoader.getInstance().getAllMods();

		buf.writeVarInt(mods.size());

		for (ModContainer container : mods) {
			ModMetadata metadata = container.getMetadata();
			buf.writeString(metadata.getId());
			buf.writeString(metadata.getVersion().toString());
		}

		return CompletableFuture.completedFuture(buf);
	}

	private static void handleResponse(MinecraftServer server, ServerLoginNetworkHandler handler, boolean understood, PacketByteBuf buf, ServerLoginNetworking.LoginSynchronizer loginSynchronizer, PacketSender sender) {
		if (!understood) {
			MutableText text = REQUEST_NOT_UNDERSTOOD.copy();

			if (!Config.getRequiredMods().isEmpty()) {
				text.append(Text.literal("\n").append(ALSO_REQUIRED));
			}

			text.append(DependencyUtil.getTextWithLinks(Collections.emptyMap()));

			handler.disconnect(text);
		} else {
			String playerName = ((GameProfileAccessor) handler).getProfile().getName();

			Map<String, String> mods = new HashMap<>();
			Map<String, String> notAllowedMods = new LinkedHashMap<>();

			int modCount = buf.readVarInt();

			// Read all mods from the packet buffer
			for (int i = 0; i < modCount; ++i) {
				String modId = buf.readString(32767);
				String modVersion = buf.readString(32767);

				if (WalledGarden.isBlacklisted(modId, modVersion) || (!WalledGarden.isWhitelisted(modId, modVersion) && !WalledGarden.isRequired(modId, modVersion))) {
					notAllowedMods.put(modId, modVersion);
				}

				mods.put(modId, modVersion);
			}

			Map<String, String> requiredMods = Config.getMissing(mods);

			Optional<MutableText> blacklistResult = WalledGarden.checkBlacklist(playerName, notAllowedMods);
			Optional<MutableText> requiredModsResult = WalledGarden.checkRequiredMods(playerName, requiredMods);

			// Disconnect if either criteria is not met
			if (blacklistResult.isPresent() || requiredModsResult.isPresent()) {
				MutableText disconnectReason = Text.literal("");

				if (blacklistResult.isPresent()) {
					disconnectReason.append(blacklistResult.get());

					if (requiredModsResult.isPresent()) {
						disconnectReason.append(Text.literal("\n\n"));
					}
				}

				requiredModsResult.ifPresent(disconnectReason::append);

				handler.disconnect(disconnectReason);
				return;
			}

			// And finally update the players version map if they're not disconnected.
			PlayerModVersionsContainerImpl versions = (PlayerModVersionsContainerImpl) ((PlayerVersionMap) server).getModVersions(playerName);

			for (Map.Entry<String, String> mod : mods.entrySet()) {
				versions.put(mod.getKey(), mod.getValue());
			}
		}
	}

}
