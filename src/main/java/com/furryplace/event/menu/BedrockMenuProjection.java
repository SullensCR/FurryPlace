package com.furryplace.event.menu;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.List;

/** Small, Bukkit-free helpers shared by the Bedrock form renderer and its tests. */
final class BedrockMenuProjection {
    private static final PlainTextComponentSerializer PLAIN_TEXT = PlainTextComponentSerializer.plainText();

    private BedrockMenuProjection() { }

    static String plain(Component component) {
        return PLAIN_TEXT.serialize(component).trim();
    }

    static String buttonText(String name, List<String> lore) {
        List<String> lines = lore.stream().filter(line -> !line.isBlank()).toList();
        return lines.isEmpty() ? name : name + "\n" + String.join("\n", lines);
    }

    static <T> List<T> pageEntries(List<T> entries, int page, int pageSize) {
        int safeSize = Math.max(1, pageSize);
        int from = Math.max(0, page) * safeSize;
        if (from >= entries.size()) return List.of();
        return entries.subList(from, Math.min(entries.size(), from + safeSize));
    }
}
