package com.furryplace.event.review;

import com.furryplace.event.domain.EventStage;
import com.furryplace.event.domain.PlotRecord;
import com.furryplace.event.domain.RuntimeState;
import com.furryplace.event.service.MessageService;
import com.furryplace.event.world.PlotService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/** Owns the temporary review hotbar controls and per-reviewer permissions. */
public final class ReviewControlService implements Listener {
    public static final int SPEED_SLOT = 5;
    public static final int MODIFY_SLOT = 6;

    public enum Speed {
        SLOW(0.05f, "review.speed-slow"),
        NORMAL(0.10f, "review.speed-normal"),
        FAST(0.20f, "review.speed-fast");

        private final float value;
        private final String labelPath;

        Speed(float value, String labelPath) {
            this.value = value;
            this.labelPath = labelPath;
        }

        public float value() {
            return value;
        }

        public String labelPath() {
            return labelPath;
        }

        public Speed next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    private final JavaPlugin plugin;
    private final RuntimeState state;
    private final PlotService plots;
    private final MessageService messages;
    private final NamespacedKey speedKey;
    private final NamespacedKey modifyKey;
    private final Map<Speed, Float> speedValues = new EnumMap<>(Speed.class);
    private final Map<UUID, Speed> speeds = new HashMap<>();
    private final Map<UUID, Boolean> editEnabled = new HashMap<>();

    public ReviewControlService(JavaPlugin plugin, RuntimeState state, PlotService plots, MessageService messages) {
        this.plugin = plugin;
        this.state = state;
        this.plots = plots;
        this.messages = messages;
        speedKey = new NamespacedKey(plugin, "review_speed_control");
        modifyKey = new NamespacedKey(plugin, "review_modify_control");
        speedValues.put(Speed.SLOW, (float) plugin.getConfig().getDouble("review.speed-slow", Speed.SLOW.value()));
        speedValues.put(Speed.NORMAL, (float) plugin.getConfig().getDouble("review.speed-normal", Speed.NORMAL.value()));
        speedValues.put(Speed.FAST, (float) plugin.getConfig().getDouble("review.speed-fast", Speed.FAST.value()));
        Bukkit.getScheduler().runTaskTimer(plugin, () -> Bukkit.getOnlinePlayers().forEach(this::ensure), 1L, 10L);
    }

    public boolean isReviewing() {
        return state.stage() == EventStage.REVIEWING;
    }

    public boolean canToggle(Player player) {
        return player.hasPermission("furryplace.admin") || player.hasPermission("furryplace.judge");
    }

    public boolean isCurrentReviewPlot(Location location) {
        if (!isReviewing() || state.review().current() == null || location == null) return false;
        PlotRecord plot = plots.plotAt(location).orElse(null);
        return plot != null && plot.complete() && plot.ownerId().equals(state.review().current())
            && plots.isInterior(plot, location);
    }

    public boolean mayModify(Player player, Location location) {
        if (!isCurrentReviewPlot(location) || !canToggle(player)) return false;
        return editEnabled.getOrDefault(player.getUniqueId(), false);
    }

    /** Used by physics events which have no actor UUID. */
    public boolean currentPlotHasEditor(Location location) {
        if (!isCurrentReviewPlot(location)) return false;
        return editEnabled.entrySet().stream().anyMatch(entry -> entry.getValue()
            && Bukkit.getPlayer(entry.getKey()) != null && canToggle(Bukkit.getPlayer(entry.getKey())));
    }

    public void enterPlot(Player player, PlotRecord plot) {
        if (!isReviewing() || state.review().current() == null || !plot.ownerId().equals(state.review().current())) {
            return;
        }
        speeds.putIfAbsent(player.getUniqueId(), Speed.NORMAL);
        editEnabled.put(player.getUniqueId(), false);
        applySpeed(player);
        ensure(player);
    }

    /** Removes controls while retaining the selected speed for the next reviewed plot. */
    public void leavePlot(Player player) {
        editEnabled.remove(player.getUniqueId());
        removeControls(player);
    }

    /** Removes all controls and forgets the review-only state. */
    public void leaveReview(Player player) {
        editEnabled.remove(player.getUniqueId());
        speeds.remove(player.getUniqueId());
        removeControls(player);
    }

    public void cleanupAll() {
        Bukkit.getOnlinePlayers().forEach(this::leaveReview);
        editEnabled.clear();
        speeds.clear();
    }

    public Speed speed(Player player) {
        return speeds.getOrDefault(player.getUniqueId(), Speed.NORMAL);
    }

    public boolean editing(Player player) {
        return editEnabled.getOrDefault(player.getUniqueId(), false);
    }

    public boolean isSpeedItem(ItemStack item) {
        return tagged(item, speedKey);
    }

    public boolean isModifyItem(ItemStack item) {
        return tagged(item, modifyKey);
    }

    public void reviewActionBar(Player player, PlotRecord plot, int current, int total) {
        Map<String, Object> values = Map.of("player", plot.ownerName(), "current", current, "total", total);
        TagResolver resolver = TagResolver.builder()
            .resolver(Placeholder.component("speed-slow", speedLabel(player, Speed.SLOW)))
            .resolver(Placeholder.component("speed-normal", speedLabel(player, Speed.NORMAL)))
            .resolver(Placeholder.component("speed-fast", speedLabel(player, Speed.FAST)))
            .build();
        messages.actionBar(player, "review.actionbar", values, resolver);
    }

    private Component speedLabel(Player player, Speed candidate) {
        String color = speed(player) == candidate ? "aqua" : "gray";
        Component label = messages.parse("<" + color + ">" + messages.raw(candidate.labelPath(), candidate.name())
            + "</" + color + ">", Map.of());
        return label.decoration(TextDecoration.ITALIC, false);
    }

    private void cycleSpeed(Player player) {
        if (!isReviewing()) return;
        Speed next = speed(player).next();
        speeds.put(player.getUniqueId(), next);
        applySpeed(player);
    }

    private void toggleEditing(Player player) {
        if (!isReviewing() || !canToggle(player) || !isCurrentReviewPlot(player.getLocation())) return;
        boolean enabled = !editing(player);
        editEnabled.put(player.getUniqueId(), enabled);
        player.getInventory().setItem(MODIFY_SLOT, createModifyItem(enabled));
        player.updateInventory();
    }

    private void applySpeed(Player player) {
        if (!player.getAllowFlight()) player.setAllowFlight(true);
        player.setFlying(true);
        player.setFlySpeed(speedValues.getOrDefault(speed(player), speed(player).value()));
    }

    public void ensure(Player player) {
        if (!isReviewing() || !isCurrentReviewPlot(player.getLocation())) {
            if (hasTaggedControl(player)) removeControls(player);
            return;
        }
        speeds.putIfAbsent(player.getUniqueId(), Speed.NORMAL);
        applySpeed(player);
        PlayerInventory inventory = player.getInventory();
        inventory.setItem(SPEED_SLOT, createSpeedItem());
        if (canToggle(player)) inventory.setItem(MODIFY_SLOT, createModifyItem(editing(player)));
        else inventory.setItem(MODIFY_SLOT, null);
    }

    private void removeControls(Player player) {
        PlayerInventory inventory = player.getInventory();
        inventory.setItem(SPEED_SLOT, null);
        inventory.setItem(MODIFY_SLOT, null);
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (isSpeedItem(item) || isModifyItem(item)) inventory.setItem(slot, null);
        }
        if (isSpeedItem(player.getItemOnCursor()) || isModifyItem(player.getItemOnCursor())) player.setItemOnCursor(null);
        player.updateInventory();
    }

    private boolean hasTaggedControl(Player player) {
        if (isSpeedItem(player.getItemOnCursor()) || isModifyItem(player.getItemOnCursor())) return true;
        for (ItemStack item : player.getInventory().getContents()) {
            if (isSpeedItem(item) || isModifyItem(item)) return true;
        }
        return false;
    }

    private ItemStack createSpeedItem() {
        ItemStack item = new ItemStack(Material.FEATHER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messages.itemComponent("review.speed-name"));
        meta.lore(messages.itemComponentList("review.speed-lore", Map.of()));
        meta.getPersistentDataContainer().set(speedKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createModifyItem(boolean enabled) {
        ItemStack item = new ItemStack(enabled ? Material.DIAMOND_PICKAXE : Material.STONE_PICKAXE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messages.itemComponent(enabled ? "review.modify-enabled-name" : "review.modify-disabled-name"));
        meta.lore(messages.itemComponentList(enabled ? "review.modify-enabled-lore" : "review.modify-disabled-lore", Map.of()));
        meta.getPersistentDataContainer().set(modifyKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean tagged(ItemStack item, NamespacedKey key) {
        return item != null && item.hasItemMeta()
            && item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        if (isSpeedItem(item)) {
            event.setCancelled(true);
            cycleSpeed(event.getPlayer());
        } else if (isModifyItem(item)) {
            event.setCancelled(true);
            toggleEditing(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (isSpeedItem(event.getCurrentItem()) || isModifyItem(event.getCurrentItem())
            || isSpeedItem(event.getCursor()) || isModifyItem(event.getCursor())
            || event.getHotbarButton() == SPEED_SLOT || event.getHotbarButton() == MODIFY_SLOT
            || (event.getSlotType() == org.bukkit.event.inventory.InventoryType.SlotType.QUICKBAR
                && (event.getSlot() == SPEED_SLOT || event.getSlot() == MODIFY_SLOT))) {
            event.setCancelled(true);
            ensure(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int offset = event.getView().getTopInventory().getSize();
        if (isSpeedItem(event.getOldCursor()) || isModifyItem(event.getOldCursor())
            || event.getRawSlots().contains(offset + SPEED_SLOT) || event.getRawSlots().contains(offset + MODIFY_SLOT)) {
            event.setCancelled(true);
            ensure(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (isSpeedItem(event.getItemDrop().getItemStack()) || isModifyItem(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
            ensure(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMoveItem(InventoryMoveItemEvent event) {
        if (isSpeedItem(event.getItem()) || isModifyItem(event.getItem())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryPickup(InventoryPickupItemEvent event) {
        if (isSpeedItem(event.getItem().getItemStack()) || isModifyItem(event.getItem().getItemStack())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityPickup(EntityPickupItemEvent event) {
        if (isSpeedItem(event.getItem().getItemStack()) || isModifyItem(event.getItem().getItemStack())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (isSpeedItem(event.getMainHandItem()) || isModifyItem(event.getMainHandItem())
            || isSpeedItem(event.getOffHandItem()) || isModifyItem(event.getOffHandItem())) {
            event.setCancelled(true);
            ensure(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (isSpeedItem(event.getItem()) || isModifyItem(event.getItem())) {
            event.setCancelled(true);
            ensure(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        event.getDrops().removeIf(item -> isSpeedItem(item) || isModifyItem(item));
        Bukkit.getScheduler().runTask(plugin, () -> ensure(event.getEntity()));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) { scheduleEnsure(event.getPlayer()); }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) { scheduleEnsure(event.getPlayer()); }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) { scheduleEnsure(event.getPlayer()); }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) { scheduleEnsure(event.getPlayer()); }

    private void scheduleEnsure(Player player) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) ensure(player);
        });
    }

}
