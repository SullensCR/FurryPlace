package com.furryplace.event.service;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class MessageService {
    private final JavaPlugin plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();
    private YamlConfiguration messages;
    private long warningCooldownMillis;

    public MessageService(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        messages = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "messages.yml"));
        warningCooldownMillis = plugin.getConfig().getLong("presentation.warning-cooldown-seconds", 5L) * 1000L;
    }

    public Component component(String path) {
        return component(path, Map.of());
    }

    public Component component(String path, Map<String, ?> replacements) {
        String raw = messages.getString(path, "<red>Mensaje faltante: " + path + "</red>");
        return parse(raw, replacements);
    }

    public Component parse(String raw, Map<String, ?> replacements) {
        TagResolver.Builder builder = TagResolver.builder();
        replacements.forEach((key, value) -> builder.resolver(Placeholder.unparsed(key, String.valueOf(value))));
        return miniMessage.deserialize(raw, builder.build());
    }

    public List<Component> componentList(String path, Map<String, ?> replacements) {
        return messages.getStringList(path).stream().map(line -> parse(line, replacements)).toList();
    }

    public Component itemComponent(String path) {
        return itemComponent(path, Map.of());
    }

    public Component itemComponent(String path, Map<String, ?> replacements) {
        return component(path, replacements).decoration(TextDecoration.ITALIC, false);
    }

    public List<Component> itemComponentList(String path, Map<String, ?> replacements) {
        return componentList(path, replacements).stream()
            .map(component -> component.decoration(TextDecoration.ITALIC, false)).toList();
    }

    public void send(CommandSender sender, String path) {
        sender.sendMessage(prefix().append(component(path)));
    }

    public void send(CommandSender sender, String path, Map<String, ?> replacements) {
        sender.sendMessage(prefix().append(component(path, replacements)));
    }

    public boolean warn(Player player, String path) {
        long now = System.currentTimeMillis();
        Map<String, Long> playerCooldowns = cooldowns.computeIfAbsent(player.getUniqueId(), ignored -> new HashMap<>());
        if (playerCooldowns.getOrDefault(path, 0L) > now) {
            return false;
        }
        playerCooldowns.put(path, now + warningCooldownMillis);
        send(player, path);
        return true;
    }

    public void broadcast(String path) {
        Component message = prefix().append(component(path));
        Bukkit.getServer().sendMessage(message);
    }

    public void broadcast(String path, Map<String, ?> replacements) {
        Component message = prefix().append(component(path, replacements));
        Bukkit.getServer().sendMessage(message);
    }

    public void actionBar(Player player, String path, Map<String, ?> replacements) {
        player.sendActionBar(component(path, replacements));
    }

    public void clearActionBar(Player player) {
        player.sendActionBar(Component.empty());
    }

    public void title(Player player, String titlePath, String subtitlePath, Map<String, ?> replacements) {
        player.showTitle(Title.title(component(titlePath, replacements), component(subtitlePath, replacements), Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(4), Duration.ofMillis(700))));
    }

    public String raw(String path, String fallback) {
        return messages.getString(path, fallback);
    }

    private Component prefix() {
        return miniMessage.deserialize(messages.getString("prefix", ""));
    }
}
