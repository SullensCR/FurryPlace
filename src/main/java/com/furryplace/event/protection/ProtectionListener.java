package com.furryplace.event.protection;

import com.furryplace.event.domain.PlotBounds;
import com.furryplace.event.domain.PlotRecord;
import com.furryplace.event.item.WandService;
import com.furryplace.event.service.MessageService;
import com.furryplace.event.world.PlotService;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.vehicle.VehicleCreateEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Centralized direct/indirect protection, prohibited-item, and entity policy. */
public final class ProtectionListener implements Listener {
    private static final Set<Material> PROHIBITED = EnumSet.of(
        Material.COMMAND_BLOCK, Material.CHAIN_COMMAND_BLOCK, Material.REPEATING_COMMAND_BLOCK,
        Material.STRUCTURE_BLOCK, Material.STRUCTURE_VOID, Material.JIGSAW, Material.BARRIER,
        Material.BEDROCK, Material.END_PORTAL_FRAME, Material.END_PORTAL, Material.SPAWNER,
        Material.TNT_MINECART, Material.END_CRYSTAL, Material.RESPAWN_ANCHOR
    );

    private final JavaPlugin plugin;
    private final AccessPolicy policy;
    private final PlotService plots;
    private final MessageService messages;
    private final WandService wands;
    private final NamespacedKey blockedToken;
    private final NamespacedKey plotEntity;
    private final int entityLimit;
    private final Map<UUID, org.bukkit.Location> lastEntityLocations = new HashMap<>();

    public ProtectionListener(JavaPlugin plugin, AccessPolicy policy, PlotService plots,
                              MessageService messages, WandService wands) {
        this.plugin = plugin;
        this.policy = policy;
        this.plots = plots;
        this.messages = messages;
        this.wands = wands;
        blockedToken = new NamespacedKey(plugin, "blocked_item_token");
        plotEntity = new NamespacedKey(plugin, "plot_entity");
        entityLimit = plugin.getConfig().getInt("plots.entity-limit", 50);
        Bukkit.getScheduler().runTaskTimer(plugin, this::scanOwnersAndEntities, 20L, 20L);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBreak(BlockBreakEvent event) {
        if (policy.inPlace(event.getBlock().getLocation()) && !policy.mayModify(event.getPlayer(), event.getBlock().getLocation())) deny(event.getPlayer(), event);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPlace(BlockPlaceEvent event) {
        if (!policy.inPlace(event.getBlock().getLocation())) return;
        if (!policy.mayModify(event.getPlayer(), event.getBlock().getLocation())
            || (!policy.admin(event.getPlayer()) && (prohibited(event.getItemInHand()) || blocked(event.getItemInHand())))) {
            deny(event.getPlayer(), event);
            return;
        }
        if (wouldCompleteConstruct(event.getBlock())) {
            event.setCancelled(true);
            messages.warn(event.getPlayer(), "plot.forbidden-entity");
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null || !policy.inPlace(block.getLocation())) return;
        if (!policy.mayModify(event.getPlayer(), block.getLocation())
            || (!policy.admin(event.getPlayer()) && (prohibited(event.getItem()) || blocked(event.getItem())))) deny(event.getPlayer(), event);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (policy.inPlace(event.getBlock().getLocation()) && !policy.mayModify(event.getPlayer(), event.getBlock().getLocation())) deny(event.getPlayer(), event);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (policy.inPlace(event.getBlock().getLocation()) && !policy.mayModify(event.getPlayer(), event.getBlock().getLocation())) deny(event.getPlayer(), event);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFluid(BlockFromToEvent event) {
        if ((policy.inPlace(event.getBlock().getLocation()) || policy.inPlace(event.getToBlock().getLocation()))
            && !policy.sameEditableInterior(event.getBlock().getLocation(), event.getToBlock().getLocation())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (!policy.inPlace(event.getBlock().getLocation()) && event.getBlocks().stream().noneMatch(block -> policy.inPlace(block.getLocation()))) return;
        for (Block block : event.getBlocks()) if ((policy.inPlace(block.getLocation()) || policy.inPlace(block.getRelative(event.getDirection()).getLocation()))
            && !policy.sameEditableInterior(block.getLocation(), block.getRelative(event.getDirection()).getLocation())) {
            event.setCancelled(true); return;
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (!policy.inPlace(event.getBlock().getLocation()) && event.getBlocks().stream().noneMatch(block -> policy.inPlace(block.getLocation()))) return;
        for (Block block : event.getBlocks()) if ((policy.inPlace(block.getLocation()) || policy.inPlace(block.getRelative(event.getDirection()).getLocation()))
            && !policy.sameEditableInterior(block.getLocation(), block.getRelative(event.getDirection()).getLocation())) {
            event.setCancelled(true); return;
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent event) {
        org.bukkit.block.BlockFace face = ((org.bukkit.block.Dispenser) event.getBlock().getState()).getBlockData() instanceof org.bukkit.block.data.Directional directional
            ? directional.getFacing() : org.bukkit.block.BlockFace.UP;
        Location target = event.getBlock().getRelative(face).getLocation();
        if ((policy.inPlace(event.getBlock().getLocation()) || policy.inPlace(target))
            && !policy.sameEditableInterior(event.getBlock().getLocation(), target)) event.setCancelled(true);
    }

    @EventHandler public void onBlockExplosion(BlockExplodeEvent event) {
        if (!policy.inPlace(event.getBlock().getLocation()) && event.blockList().stream().noneMatch(block -> policy.inPlace(block.getLocation()))) return;
        if (!policy.allowsIndirect(event.getBlock().getLocation()) || event.blockList().stream()
            .anyMatch(block -> policy.inPlace(block.getLocation()) && !policy.sameEditableInterior(event.getBlock().getLocation(), block.getLocation()))) event.setCancelled(true);
    }
    @EventHandler public void onEntityExplosion(EntityExplodeEvent event) {
        if (!policy.inPlace(event.getLocation()) && event.blockList().stream().noneMatch(block -> policy.inPlace(block.getLocation()))) return;
        if (!policy.allowsIndirect(event.getLocation()) || event.blockList().stream()
            .anyMatch(block -> policy.inPlace(block.getLocation()) && !policy.sameEditableInterior(event.getLocation(), block.getLocation()))) event.setCancelled(true);
    }
    @EventHandler public void onIgnite(BlockIgniteEvent event) { if (policy.inPlace(event.getBlock().getLocation()) && event.getCause() == BlockIgniteEvent.IgniteCause.EXPLOSION && !policy.allowsIndirect(event.getBlock().getLocation())) event.setCancelled(true); }
    @EventHandler public void onBurn(BlockBurnEvent event) { if (policy.inPlace(event.getBlock().getLocation()) && !policy.allowsIndirect(event.getBlock().getLocation())) event.setCancelled(true); }
    @EventHandler public void onSpread(BlockSpreadEvent event) { if (policy.inPlace(event.getBlock().getLocation()) && !policy.allowsIndirect(event.getBlock().getLocation())) event.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player) || !policy.inPlace(player.getLocation())) return;
        if (event.getInventory().getType() == org.bukkit.event.inventory.InventoryType.ENDER_CHEST) event.setCancelled(true);
        else if (event.getInventory().getHolder(false) instanceof InventoryHolder holder && holder instanceof org.bukkit.block.BlockState state
            && !policy.mayModify(player, state.getLocation())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        if (event.getPlayer() != null && policy.inPlace(event.getEntity().getLocation())
            && !policy.mayModify(event.getPlayer(), event.getEntity().getLocation())) deny(event.getPlayer(), event);
    }

    @EventHandler(ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent event) {
        if (event.getRemover() instanceof Player player && policy.inPlace(event.getEntity().getLocation())
            && !policy.mayModify(player, event.getEntity().getLocation())) deny(player, event);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        if (policy.inPlace(event.getRightClicked().getLocation()) && !policy.mayModify(event.getPlayer(), event.getRightClicked().getLocation())) deny(event.getPlayer(), event);
    }

    @EventHandler(ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        if (policy.inPlace(event.getRightClicked().getLocation()) && !policy.mayModify(event.getPlayer(), event.getRightClicked().getLocation())) deny(event.getPlayer(), event);
    }

    @EventHandler public void onVehicleCreate(VehicleCreateEvent event) { validateSpawn(event.getVehicle(), event); }
    @EventHandler public void onVehicleDamage(VehicleDamageEvent event) { if (event.getAttacker() instanceof Player player && policy.inPlace(event.getVehicle().getLocation()) && !policy.mayModify(player, event.getVehicle().getLocation())) deny(player, event); }
    @EventHandler public void onVehicleDestroy(VehicleDestroyEvent event) { if (event.getAttacker() instanceof Player player && policy.inPlace(event.getVehicle().getLocation()) && !policy.mayModify(player, event.getVehicle().getLocation())) deny(player, event); }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (!policy.inPlace(event.getPlayer().getLocation())) return;
        if (!policy.mayModify(event.getPlayer(), event.getPlayer().getLocation()) || blocked(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
            messages.warn(event.getPlayer(), "plot.outside");
            return;
        }
        validateSpawn(event.getItemDrop(), event);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && policy.inPlace(event.getItem().getLocation())
            && !policy.mayModify(player, event.getItem().getLocation())) event.setCancelled(true);
    }

    @EventHandler public void onDamage(EntityDamageEvent event) {
        if (!policy.inPlace(event.getEntity().getLocation())) return;
        if (event.getEntity() instanceof Player || event.getCause() == EntityDamageEvent.DamageCause.FIRE
            || event.getCause() == EntityDamageEvent.DamageCause.FIRE_TICK || event.getCause() == EntityDamageEvent.DamageCause.LAVA
            || event.getCause() == EntityDamageEvent.DamageCause.HOT_FLOOR
            || event.getCause() == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION
            || event.getCause() == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION) event.setCancelled(true);
    }
    @EventHandler public void onCombust(EntityCombustEvent event) { if (policy.inPlace(event.getEntity().getLocation()) && !policy.allowsIndirect(event.getEntity().getLocation())) event.setCancelled(true); }
    @EventHandler public void onChangeBlock(EntityChangeBlockEvent event) { if (policy.inPlace(event.getBlock().getLocation()) && !policy.allowsIndirect(event.getBlock().getLocation())) event.setCancelled(true); }
    @EventHandler public void onProjectile(ProjectileLaunchEvent event) { if (policy.inPlace(event.getEntity().getLocation()) && (!(event.getEntity().getShooter() instanceof Player player) || !policy.mayModify(player, event.getEntity().getLocation()))) event.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onCreature(CreatureSpawnEvent event) {
        if (!policy.inPlace(event.getLocation())) return;
        if (policy.reviewing() && !policy.allowsIndirect(event.getLocation())) {
            event.setCancelled(true);
            return;
        }
        if (event.getEntity() instanceof Monster || !ownerSpawnReason(event.getSpawnReason())
            || event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.BUILD_SNOWMAN
            || event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.BUILD_IRONGOLEM
            || event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.BUILD_COPPERGOLEM
            || event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.BUILD_WITHER) {
            event.setCancelled(true);
            return;
        }
        validateSpawn(event.getEntity(), event);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntitySpawn(EntitySpawnEvent event) {
        if (!(event.getEntity() instanceof Player) && !(event.getEntity() instanceof Monster)) validateSpawn(event.getEntity(), event);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCreative(InventoryCreativeEvent event) {
        if (event.getWhoClicked() instanceof Player player && policy.inPlace(player.getLocation()) && prohibited(event.getCursor())) {
            event.setCancelled(true);
            replaceCursor(player);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !policy.inPlace(player.getLocation())) return;
        if (blocked(event.getCurrentItem()) || blocked(event.getCursor())) event.setCancelled(true);
        if (prohibited(event.getCursor())) {
            event.setCancelled(true);
            replaceCursor(player);
        }
    }

    private void scanOwnersAndEntities() {
        plots.plotWorld().ifPresent(world -> {
            for (Player player : world.getPlayers()) {
                if (!policy.mayModify(player, player.getLocation()) || (policy.admin(player) && !policy.reviewing())) continue;
                ItemStack[] contents = player.getInventory().getStorageContents();
                for (int slot = 0; slot < contents.length; slot++) {
                    if (prohibited(contents[slot])) replaceSlot(player, slot);
                }
            }
            for (Entity entity : world.getEntities()) {
                if (entity instanceof Player) continue;
                if (entity instanceof Monster) {
                    entity.remove();
                    continue;
                }
                String value = entity.getPersistentDataContainer().get(plotEntity, PersistentDataType.STRING);
                if (value == null) continue;
                PlotRecord plot;
                try { plot = plots.plotAt(lastEntityLocations.getOrDefault(entity.getUniqueId(), entity.getLocation())).orElse(null); }
                catch (Exception exception) { plot = null; }
                if (plot == null || !plot.ownerId().toString().equals(value)) {
                    entity.remove();
                    continue;
                }
                if (plots.isInterior(plot, entity.getLocation())) lastEntityLocations.put(entity.getUniqueId(), entity.getLocation().clone());
                else {
                    org.bukkit.Location safe = lastEntityLocations.get(entity.getUniqueId());
                    if (safe == null) entity.remove(); else entity.teleportAsync(safe);
                }
            }
        });
    }

    private void validateSpawn(Entity entity, org.bukkit.event.Cancellable event) {
        if (!policy.inPlace(entity.getLocation())) return;
        if (policy.reviewing() && !policy.allowsIndirect(entity.getLocation())) {
            event.setCancelled(true);
            return;
        }
        PlotRecord plot = plots.plotAt(entity.getLocation()).orElse(null);
        if (plot == null || !plots.isInterior(plot, entity.getLocation()) || countEntities(plot, entity) >= entityLimit
            || entity instanceof EnderCrystal || entity.getType() == EntityType.TNT) {
            event.setCancelled(true);
            return;
        }
        entity.getPersistentDataContainer().set(plotEntity, PersistentDataType.STRING, plot.ownerId().toString());
        lastEntityLocations.put(entity.getUniqueId(), entity.getLocation().clone());
    }

    private int countEntities(PlotRecord plot, Entity spawning) {
        return (int) plots.plotWorld().orElseThrow().getEntities().stream().filter(entity -> !(entity instanceof Player))
            .filter(entity -> !entity.getUniqueId().equals(spawning.getUniqueId()))
            .filter(entity -> plots.isInterior(plot, entity.getLocation())).count();
    }

    private boolean prohibited(ItemStack item) {
        if (item == null || item.getType().isAir() || wands.isAnyWand(item) || blocked(item)) return false;
        Material material = item.getType();
        if (PROHIBITED.contains(material)) return true;
        if (!material.name().endsWith("_SPAWN_EGG")) return false;
        String name = material.name().substring(0, material.name().length() - "_SPAWN_EGG".length());
        try {
            Class<? extends Entity> type = EntityType.valueOf(name).getEntityClass();
            return type != null && Monster.class.isAssignableFrom(type);
        } catch (IllegalArgumentException exception) {
            return true;
        }
    }

    private boolean blocked(ItemStack item) {
        return item != null && item.hasItemMeta()
            && item.getItemMeta().getPersistentDataContainer().has(blockedToken, PersistentDataType.STRING);
    }

    private void replaceSlot(Player player, int slot) {
        String token = UUID.randomUUID().toString();
        player.getInventory().setItem(slot, token(token));
        messages.warn(player, "plot.forbidden-item");
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            ItemStack current = player.getInventory().getItem(slot);
            if (current != null && token.equals(current.getItemMeta().getPersistentDataContainer().get(blockedToken, PersistentDataType.STRING))) {
                player.getInventory().setItem(slot, null);
            }
        }, 100L);
    }

    private void replaceCursor(Player player) {
        String token = UUID.randomUUID().toString();
        player.setItemOnCursor(token(token));
        messages.warn(player, "plot.forbidden-item");
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            ItemStack current = player.getItemOnCursor();
            if (current != null && token.equals(current.getItemMeta().getPersistentDataContainer().get(blockedToken, PersistentDataType.STRING))) player.setItemOnCursor(null);
        }, 100L);
    }

    private ItemStack token(String token) {
        ItemStack item = new ItemStack(Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messages.itemComponent("items.blocked-name"));
        meta.lore(messages.itemComponentList("items.blocked-lore", Map.of()));
        meta.getPersistentDataContainer().set(blockedToken, PersistentDataType.STRING, token);
        item.setItemMeta(meta);
        return item;
    }

    private boolean ownerSpawnReason(CreatureSpawnEvent.SpawnReason reason) {
        return reason == CreatureSpawnEvent.SpawnReason.EGG || reason == CreatureSpawnEvent.SpawnReason.SPAWNER_EGG
            || reason == CreatureSpawnEvent.SpawnReason.DISPENSE_EGG || reason == CreatureSpawnEvent.SpawnReason.BREEDING
            || reason == CreatureSpawnEvent.SpawnReason.BUCKET || reason == CreatureSpawnEvent.SpawnReason.CUSTOM
            || reason == CreatureSpawnEvent.SpawnReason.BUILD_SNOWMAN || reason == CreatureSpawnEvent.SpawnReason.BUILD_IRONGOLEM
            || reason == CreatureSpawnEvent.SpawnReason.BUILD_COPPERGOLEM || reason == CreatureSpawnEvent.SpawnReason.BUILD_WITHER;
    }

    private boolean wouldCompleteConstruct(Block block) {
        Material type = block.getType();
        if (type != Material.CARVED_PUMPKIN && type != Material.JACK_O_LANTERN && type != Material.WITHER_SKELETON_SKULL
            && type != Material.WITHER_SKELETON_WALL_SKULL) return false;
        if (type == Material.CARVED_PUMPKIN || type == Material.JACK_O_LANTERN) {
            Block below = block.getRelative(org.bukkit.block.BlockFace.DOWN);
            boolean snow = below.getType() == Material.SNOW_BLOCK && below.getRelative(org.bukkit.block.BlockFace.DOWN).getType() == Material.SNOW_BLOCK;
            boolean iron = below.getType() == Material.IRON_BLOCK && below.getRelative(org.bukkit.block.BlockFace.DOWN).getType() == Material.IRON_BLOCK
                && ((below.getRelative(org.bukkit.block.BlockFace.EAST).getType() == Material.IRON_BLOCK && below.getRelative(org.bukkit.block.BlockFace.WEST).getType() == Material.IRON_BLOCK)
                || (below.getRelative(org.bukkit.block.BlockFace.NORTH).getType() == Material.IRON_BLOCK && below.getRelative(org.bukkit.block.BlockFace.SOUTH).getType() == Material.IRON_BLOCK));
            return snow || iron;
        }
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
            Block soul = block.getRelative(x, -1, z);
            if ((soul.getType() == Material.SOUL_SAND || soul.getType() == Material.SOUL_SOIL)
                && soul.getRelative(org.bukkit.block.BlockFace.DOWN).getType() == soul.getType()) return true;
        }
        return false;
    }

    private void deny(Player player, org.bukkit.event.Cancellable event) {
        event.setCancelled(true);
        messages.warn(player, "plot.outside");
    }
}
