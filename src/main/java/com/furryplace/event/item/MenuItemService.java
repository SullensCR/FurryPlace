package com.furryplace.event.item;

import com.furryplace.event.service.MessageService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.function.Consumer;

/** Maintains the unremovable slot-8 event menu item in every world. */
public final class MenuItemService implements Listener {
    private static final int MENU_SLOT = 8;

    private final JavaPlugin plugin;
    private final MessageService messages;
    private final Consumer<Player> openMenu;
    private final NamespacedKey itemKey;

    public MenuItemService(JavaPlugin plugin, MessageService messages, Consumer<Player> openMenu) {
        this.plugin = plugin;
        this.messages = messages;
        this.openMenu = openMenu;
        itemKey = new NamespacedKey(plugin, "event_menu_item");
        Bukkit.getScheduler().runTaskTimer(plugin,
            () -> Bukkit.getOnlinePlayers().forEach(this::ensure), 1L, 10L);
    }

    public void ensure(Player player) {
        PlayerInventory inventory = player.getInventory();
        ItemStack atMenuSlot = inventory.getItem(MENU_SLOT);
        int sourceSlot = isMenuItem(atMenuSlot) ? MENU_SLOT : findMenuItem(inventory);
        ItemStack menuItem = sourceSlot >= 0 ? inventory.getItem(sourceSlot) : createMenuItem();
        boolean changed = false;

        if (sourceSlot != MENU_SLOT) {
            if (sourceSlot >= 0) inventory.setItem(sourceSlot, null);
            ItemStack displaced = inventory.getItem(MENU_SLOT);
            if (!isEmpty(displaced)) {
                inventory.setItem(MENU_SLOT, null);
                preserveDisplaced(player, inventory, displaced);
            }
            inventory.setItem(MENU_SLOT, menuItem);
            changed = true;
        } else if (menuItem.getAmount() != 1) {
            menuItem.setAmount(1);
            changed = true;
        }

        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (slot != MENU_SLOT && isMenuItem(inventory.getItem(slot))) {
                inventory.setItem(slot, null);
                changed = true;
            }
        }
        if (changed) player.updateInventory();
    }

    public boolean isMenuItem(ItemStack item) {
        return item != null && item.getType() == Material.NETHER_STAR && item.hasItemMeta()
            && item.getItemMeta().getPersistentDataContainer().has(itemKey, PersistentDataType.BYTE);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (!isMenuItem(event.getItem())) return;
        event.setCancelled(true);
        openMenu.accept(event.getPlayer());
        scheduleEnsure(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        boolean menuInteraction = isMenuItem(event.getCurrentItem()) || isMenuItem(event.getCursor())
            || (event.getSlotType() == org.bukkit.event.inventory.InventoryType.SlotType.QUICKBAR && event.getSlot() == MENU_SLOT)
            || event.getHotbarButton() == MENU_SLOT;
        if (!menuInteraction) {
            scheduleEnsure(player);
            return;
        }
        event.setCancelled(true);
        scheduleEnsure(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int playerHotbarSlot = event.getView().getTopInventory().getSize() + MENU_SLOT;
        if (isMenuItem(event.getOldCursor()) || event.getRawSlots().contains(playerHotbarSlot)) event.setCancelled(true);
        scheduleEnsure(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (!isMenuItem(event.getItemDrop().getItemStack())) return;
        event.setCancelled(true);
        scheduleEnsure(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMoveItem(InventoryMoveItemEvent event) {
        if (isMenuItem(event.getItem())) event.setCancelled(true);
        if (event.getDestination().getHolder() instanceof Player player) scheduleEnsure(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryPickup(InventoryPickupItemEvent event) {
        if (isMenuItem(event.getItem().getItemStack())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityPickup(EntityPickupItemEvent event) {
        if (isMenuItem(event.getItem().getItemStack())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        event.getDrops().removeIf(this::isMenuItem);
        scheduleEnsure(event.getEntity());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (isMenuItem(event.getMainHandItem()) || isMenuItem(event.getOffHandItem())) {
            event.setCancelled(true);
            scheduleEnsure(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (isMenuItem(event.getItem())) {
            event.setCancelled(true);
            scheduleEnsure(event.getPlayer());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) { scheduleEnsure(event.getPlayer()); }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) { scheduleEnsure(event.getPlayer()); }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) { scheduleEnsure(event.getPlayer()); }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) { scheduleEnsure(event.getPlayer()); }

    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) scheduleEnsure(player);
    }

    private int findMenuItem(PlayerInventory inventory) {
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (slot != MENU_SLOT && isMenuItem(inventory.getItem(slot))) return slot;
        }
        return -1;
    }

    private ItemStack createMenuItem() {
        ItemStack item = new ItemStack(Material.NETHER_STAR);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messages.itemComponent("items.menu-name"));
        meta.lore(messages.itemComponentList("items.menu-lore", Map.of()));
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private void preserveDisplaced(Player player, PlayerInventory inventory, ItemStack displaced) {
        for (int slot = 0; slot < 36; slot++) {
            if (slot != MENU_SLOT && isEmpty(inventory.getItem(slot))) {
                inventory.setItem(slot, displaced);
                return;
            }
        }
        player.getWorld().dropItemNaturally(player.getLocation(), displaced);
    }

    private boolean isEmpty(ItemStack item) {
        return item == null || item.getType().isAir();
    }

    private void scheduleEnsure(Player player) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) ensure(player);
        });
    }
}
