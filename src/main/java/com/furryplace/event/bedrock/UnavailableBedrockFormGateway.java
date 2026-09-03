package com.furryplace.event.bedrock;

import org.bukkit.entity.Player;

import java.util.List;
import java.util.function.Consumer;

/** No-op gateway used when Floodgate is not installed or could not load. */
public final class UnavailableBedrockFormGateway implements BedrockFormGateway {
    @Override public boolean available() { return false; }
    @Override public boolean isBedrock(Player player) { return false; }
    @Override public boolean sendSimple(Player player, String title, String content, List<Button> buttons, Runnable closed) { return false; }
    @Override public boolean sendModal(Player player, String title, String content, String firstButton, Runnable firstAction,
                                       String secondButton, Runnable secondAction, Runnable closed) { return false; }
    @Override public boolean sendInput(Player player, String title, String content, String label, String placeholder,
                                       String value, Consumer<String> submitted, Runnable closed) { return false; }
    @Override public boolean sendTest(Player player, Mode mode, Consumer<String> result) { return false; }
}
