package com.furryplace.event.bedrock;

import org.bukkit.entity.Player;

import java.util.function.Consumer;

/** Optional bridge for Bedrock-native Floodgate forms. */
public interface BedrockFormGateway {
    enum Mode { MODAL, SIMPLE, CUSTOM }

    boolean available();

    boolean isBedrock(Player player);

    boolean sendTest(Player player, Mode mode, Consumer<String> result);
}
