package com.furryplace.event.portal;

import com.furryplace.event.domain.EventStage;
import com.furryplace.event.domain.RuntimeState;
import com.furryplace.event.domain.WorldBlockKey;
import com.furryplace.event.persistence.StateRepository;
import com.furryplace.event.service.MessageService;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Queue;
import java.util.Set;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class PortalService implements Listener {
    public interface Router { void route(Player player, EventStage stage); }

    private static final BlockFace[] FACES = {
        BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };

    private final JavaPlugin plugin;
    private final RuntimeState state;
    private final StateRepository repository;
    private final MessageService messages;
    private final NamespacedKey wandKey;
    private final int limit;
    private final Map<UUID, Long> handledUntil = new HashMap<>();
    private Router router;

    public PortalService(JavaPlugin plugin, RuntimeState state, StateRepository repository, MessageService messages) {
        this.plugin = plugin;
        this.state = state;
        this.repository = repository;
        this.messages = messages;
        wandKey = new NamespacedKey(plugin, "portal_wand");
        limit = plugin.getConfig().getInt("portal.flood-fill-limit", 1024);
        Bukkit.getScheduler().runTaskTimer(plugin, () -> Bukkit.getOnlinePlayers().forEach(player -> {
            if (isOnControlledPortal(player.getLocation())) handleEntry(player);
        }), 1L, 1L);
    }

    public void router(Router value) { router = value; }

    public void giveWand(Player player) {
        ItemStack item = new ItemStack(Material.FEATHER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messages.itemComponent("items.portal-wand-name"));
        meta.getPersistentDataContainer().set(wandKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        player.getInventory().addItem(item).values().forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
    }

    @EventHandler
    public void onSelect(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK
            || !isWand(event.getItem())) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        Block clicked = event.getClickedBlock();
        String lobbyName = plugin.getConfig().getString("worlds.lobby", "lobby");
        if (clicked == null || clicked.getType() != Material.NETHER_PORTAL || !player.getWorld().getName().equals(lobbyName)) {
            messages.send(player, "portal.invalid");
            return;
        }
        Set<WorldBlockKey> selected = floodFill(clicked);
        if (selected.isEmpty()) {
            messages.send(player, "portal.invalid");
            return;
        }
        state.portalBlocks().clear();
        state.portalBlocks().addAll(selected);
        repository.save(state);
        messages.send(player, "portal.selected");
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST)
    public void onPortal(PlayerPortalEvent event) {
        if (!isOnControlledPortal(event.getFrom())) return;
        event.setCancelled(true);
        handleEntry(event.getPlayer());
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (to == null || !isOnControlledPortal(to)) return;
        event.setCancelled(true);
        handleEntry(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        handledUntil.remove(event.getPlayer().getUniqueId());
    }

    private void handleEntry(Player player) {
        long now = Bukkit.getCurrentTick();
        long until = handledUntil.getOrDefault(player.getUniqueId(), 0L);
        if (until > now) return;
        handledUntil.put(player.getUniqueId(), now + 10L);
        if (!validSelection()) {
            messages.warn(player, "portal.broken");
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.hasPermission("furryplace.admin")) messages.warn(online, "portal.broken");
            }
            return;
        }
        if (state.stage() == EventStage.INACTIVE) {
            Vector look = player.getLocation().getDirection().setY(0);
            if (look.lengthSquared() < 0.01) look = new Vector(0, 0, 1);
            look.normalize().multiply(-plugin.getConfig().getDouble("portal.pushback-horizontal", 1.15));
            look.setY(plugin.getConfig().getDouble("portal.pushback-upward", 0.18));
            player.setVelocity(look);
            messages.warn(player, "event.inactive");
            return;
        }
        if (router != null) router.route(player, state.stage());
    }

    private boolean isOnControlledPortal(Location location) {
        if (location == null || location.getWorld() == null || state.portalBlocks().isEmpty()) return false;
        String world = location.getWorld().getName();
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();
        return state.portalBlocks().contains(new WorldBlockKey(world, x, y, z))
            || state.portalBlocks().contains(new WorldBlockKey(world, x, y + 1, z))
            || state.portalBlocks().contains(new WorldBlockKey(world, x, y - 1, z));
    }

    private Set<WorldBlockKey> floodFill(Block start) {
        Set<WorldBlockKey> result = new LinkedHashSet<>();
        Set<Block> visited = new LinkedHashSet<>();
        Queue<Block> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty() && result.size() < limit) {
            Block block = queue.remove();
            if (!visited.add(block) || block.getType() != Material.NETHER_PORTAL) continue;
            result.add(new WorldBlockKey(block.getWorld().getName(), block.getX(), block.getY(), block.getZ()));
            for (BlockFace face : FACES) queue.add(block.getRelative(face));
        }
        if (!queue.isEmpty()) return Set.of();
        return result;
    }

    private boolean validSelection() {
        if (state.portalBlocks().isEmpty()) return false;
        for (WorldBlockKey key : state.portalBlocks()) {
            World world = Bukkit.getWorld(key.world());
            if (world == null || world.getBlockAt(key.x(), key.y(), key.z()).getType() != Material.NETHER_PORTAL) return false;
        }
        return true;
    }

    private boolean isWand(ItemStack item) {
        return item != null && item.hasItemMeta()
            && item.getItemMeta().getPersistentDataContainer().has(wandKey, PersistentDataType.BYTE);
    }
}
