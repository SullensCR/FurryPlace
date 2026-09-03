package com.furryplace.event.bedrock;

import org.bukkit.entity.Player;

import java.util.List;
import java.util.function.Consumer;

/** Optional bridge for Bedrock-native Floodgate forms. */
public interface BedrockFormGateway {
    enum Mode { MODAL, SIMPLE, CUSTOM }

    record Button(String text, Runnable action) {}

    boolean available();

    boolean isBedrock(Player player);

    boolean sendSimple(Player player, String title, String content, List<Button> buttons, Runnable closed);

    boolean sendModal(Player player, String title, String content, String firstButton, Runnable firstAction,
                      String secondButton, Runnable secondAction, Runnable closed);

    boolean sendInput(Player player, String title, String content, String label, String placeholder, String value,
                      Consumer<String> submitted, Runnable closed);

    boolean sendTest(Player player, Mode mode, Consumer<String> result);
}
