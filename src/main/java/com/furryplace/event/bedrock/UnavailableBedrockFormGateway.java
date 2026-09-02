package com.furryplace.event.bedrock;

import org.bukkit.entity.Player;

import java.util.function.Consumer;

/** No-op gateway used when Floodgate is not installed or could not load. */
public final class UnavailableBedrockFormGateway implements BedrockFormGateway {
    @Override public boolean available() { return false; }
    @Override public boolean isBedrock(Player player) { return false; }
    @Override public boolean sendTest(Player player, Mode mode, Consumer<String> result) { return false; }
}
