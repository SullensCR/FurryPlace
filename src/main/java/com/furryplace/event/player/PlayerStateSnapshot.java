package com.furryplace.event.player;

import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Everything swapped for an owner, deliberately excluding experience. */
public record PlayerStateSnapshot(
    ItemStack[] inventory,
    ItemStack[] armor,
    ItemStack offhand,
    ItemStack cursor,
    int selectedSlot,
    double health,
    int food,
    float saturation,
    float exhaustion,
    GameMode gameMode,
    boolean allowFlight,
    boolean flying,
    List<PotionEffect> effects
) {
    public static PlayerStateSnapshot capture(Player player) {
        return new PlayerStateSnapshot(
            cloneItems(player.getInventory().getStorageContents()),
            cloneItems(player.getInventory().getArmorContents()),
            cloneItem(player.getInventory().getItemInOffHand()),
            cloneItem(player.getItemOnCursor()),
            player.getInventory().getHeldItemSlot(),
            player.getHealth(), player.getFoodLevel(), player.getSaturation(), player.getExhaustion(),
            player.getGameMode(), player.getAllowFlight(), player.isFlying(), List.copyOf(player.getActivePotionEffects())
        );
    }

    public static PlayerStateSnapshot emptyEvent(Player player) {
        return new PlayerStateSnapshot(new ItemStack[36], new ItemStack[4], null, null, 0,
            Math.min(20.0, player.getMaxHealth()), 20, 20.0f, 0.0f,
            GameMode.CREATIVE, true, true, List.of());
    }

    public void apply(Player player) {
        player.closeInventory();
        player.setItemOnCursor(null);
        player.getInventory().clear();
        player.getInventory().setStorageContents(cloneItems(inventory));
        player.getInventory().setArmorContents(cloneItems(armor));
        player.getInventory().setItemInOffHand(cloneItem(offhand));
        player.getInventory().setHeldItemSlot(Math.max(0, Math.min(8, selectedSlot)));
        for (PotionEffect effect : player.getActivePotionEffects()) player.removePotionEffect(effect.getType());
        effects.forEach(player::addPotionEffect);
        player.setGameMode(gameMode);
        player.setAllowFlight(allowFlight || gameMode == GameMode.CREATIVE || gameMode == GameMode.SPECTATOR);
        player.setFlying(flying && player.getAllowFlight());
        player.setFoodLevel(food);
        player.setSaturation(saturation);
        player.setExhaustion(exhaustion);
        player.setHealth(Math.max(0.1, Math.min(health, player.getMaxHealth())));
        player.setItemOnCursor(cloneItem(cursor));
        player.updateInventory();
    }

    public void write(ConfigurationSection section) {
        section.set("inventory", Arrays.asList(inventory));
        section.set("armor", Arrays.asList(armor));
        section.set("offhand", offhand);
        section.set("cursor", cursor);
        section.set("selected-slot", selectedSlot);
        section.set("health", health);
        section.set("food", food);
        section.set("saturation", saturation);
        section.set("exhaustion", exhaustion);
        section.set("game-mode", gameMode.name());
        section.set("allow-flight", allowFlight);
        section.set("flying", flying);
        List<Map<String, Object>> serializedEffects = new ArrayList<>();
        for (PotionEffect effect : effects) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("type", effect.getType().getKey().asString());
            value.put("duration", effect.getDuration());
            value.put("amplifier", effect.getAmplifier());
            value.put("ambient", effect.isAmbient());
            value.put("particles", effect.hasParticles());
            value.put("icon", effect.hasIcon());
            serializedEffects.add(value);
        }
        section.set("effects", serializedEffects);
    }

    public static PlayerStateSnapshot read(ConfigurationSection section) {
        ItemStack[] inventory = readItems(section.getList("inventory"), 36);
        ItemStack[] armor = readItems(section.getList("armor"), 4);
        List<PotionEffect> effects = new ArrayList<>();
        for (Map<?, ?> value : section.getMapList("effects")) {
            NamespacedKey key = NamespacedKey.fromString(String.valueOf(value.get("type")));
            PotionEffectType type = key == null ? null : PotionEffectType.getByKey(key);
            if (type == null) continue;
            effects.add(new PotionEffect(type, number(value.get("duration"), 1), number(value.get("amplifier"), 0),
                bool(value.get("ambient")), bool(value.get("particles")), bool(value.get("icon"))));
        }
        GameMode mode;
        try {
            mode = GameMode.valueOf(section.getString("game-mode", "SURVIVAL"));
        } catch (IllegalArgumentException exception) {
            mode = GameMode.SURVIVAL;
        }
        return new PlayerStateSnapshot(inventory, armor, section.getItemStack("offhand"),
            section.getItemStack("cursor"), section.getInt("selected-slot"), section.getDouble("health", 20.0),
            section.getInt("food", 20), (float) section.getDouble("saturation", 5.0),
            (float) section.getDouble("exhaustion", 0.0), mode, section.getBoolean("allow-flight"),
            section.getBoolean("flying"), effects);
    }

    private static ItemStack[] readItems(List<?> values, int size) {
        ItemStack[] result = new ItemStack[size];
        if (values == null) return result;
        for (int index = 0; index < Math.min(size, values.size()); index++) {
            if (values.get(index) instanceof ItemStack item) result[index] = item.clone();
        }
        return result;
    }

    private static ItemStack[] cloneItems(ItemStack[] values) {
        ItemStack[] result = new ItemStack[values.length];
        for (int index = 0; index < values.length; index++) result[index] = cloneItem(values[index]);
        return result;
    }

    private static ItemStack cloneItem(ItemStack value) {
        return value == null ? null : value.clone();
    }

    private static int number(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static boolean bool(Object value) {
        return value instanceof Boolean bool && bool;
    }
}
