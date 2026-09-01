package com.furryplace.event.item;

import com.furryplace.event.domain.EventStage;
import com.furryplace.event.domain.PlotRecord;
import com.furryplace.event.domain.RuntimeState;
import com.furryplace.event.menu.MenuService;
import com.furryplace.event.service.MessageService;
import com.furryplace.event.world.PlotService;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;
import java.util.List;

public final class WandService implements Listener {
    public enum Type {
        WEATHER(Material.BREEZE_ROD, "weather", "weather"),
        TIME(Material.FEATHER, "time", "time"),
        BIOME(Material.STICK, "biome", "biome");

        private final Material material;
        private final String messagePrefix;
        private final String menu;

        Type(Material material, String messagePrefix, String menu) {
            this.material = material;
            this.messagePrefix = messagePrefix;
            this.menu = menu;
        }
    }

    private final RuntimeState state;
    private final PlotService plots;
    private final MenuService menus;
    private final MessageService messages;
    private final NamespacedKey key;

    public WandService(JavaPlugin plugin, RuntimeState state, PlotService plots, MenuService menus,
                       MessageService messages) {
        this.state = state;
        this.plots = plots;
        this.menus = menus;
        this.messages = messages;
        key = new NamespacedKey(plugin, "environment_wand");
    }

    public boolean restore(Player player, Type type) {
        ItemStack[] original = player.getInventory().getStorageContents();
        ItemStack[] changed = Arrays.stream(original).map(item -> item == null ? null : item.clone()).toArray(ItemStack[]::new);
        for (int index = 0; index < changed.length; index++) {
            if (isWand(changed[index], type)) changed[index] = null;
        }
        int target = type.ordinal();
        if (changed[target] != null) {
            int empty = -1;
            for (int index = 0; index < changed.length; index++) {
                if (index != target && changed[index] == null) {
                    empty = index;
                    break;
                }
            }
            if (empty == -1) {
                messages.send(player, "errors.inventory-full");
                return false;
            }
            changed[empty] = changed[target];
        }
        changed[target] = create(type);
        player.getInventory().setStorageContents(changed);
        player.updateInventory();
        return true;
    }

    @EventHandler
    public void onUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || (event.getAction() != Action.RIGHT_CLICK_AIR
            && event.getAction() != Action.RIGHT_CLICK_BLOCK)) return;
        Type type = type(event.getItem());
        if (type == null) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        PlotRecord plot = plots.plotAt(player.getLocation()).orElse(null);
        if (state.stage() != EventStage.ACTIVE || plot == null || !plot.ownerId().equals(player.getUniqueId())
            || !plots.isInterior(plot, player.getLocation())) {
            messages.warn(player, "plot.outside");
            return;
        }
        menus.open(player, type.menu);
    }

    public boolean isAnyWand(ItemStack item) {
        return type(item) != null;
    }

    private Type type(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        String value = item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (value == null) return null;
        try { return Type.valueOf(value); }
        catch (IllegalArgumentException exception) { return null; }
    }

    private boolean isWand(ItemStack item, Type type) {
        return type(item) == type;
    }

    private ItemStack create(Type type) {
        ItemStack item = new ItemStack(type.material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messages.itemComponent("items." + type.messagePrefix + "-wand-name"));
        meta.lore(messages.itemComponentList("items." + type.messagePrefix + "-wand-lore", java.util.Map.of()));
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, type.name());
        item.setItemMeta(meta);
        return item;
    }
}
