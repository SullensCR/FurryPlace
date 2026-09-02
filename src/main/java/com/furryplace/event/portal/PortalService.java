package com.furryplace.event.portal;

import com.furryplace.event.domain.EventStage;
import com.furryplace.event.domain.RuntimeState;
import com.furryplace.event.domain.WorldBlockKey;
import com.furryplace.event.persistence.StateRepository;
import com.furryplace.event.service.MessageService;
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
import org.bukkit.event.block.BlockBreakEvent;
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

    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!isSelectedPortalBlock(event.getBlock())) return;
        if (!isWand(event.getPlayer().getInventory().getItemInMainHand())) {
            event.setCancelled(true);
            return;
        }
        state.portalBlocks().clear();
        repository.save(state);
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
        // Cancelling a move rewinds the player to the previous position. During
        // the inactive stage that rewind cancels out the velocity we apply for
        // portal pushback, making it look like a teleport instead of knockback.
        // Let that movement complete so the server can apply the impulse. For
        // active stages, retain cancellation while routing the portal entry.
        if (state.stage() != EventStage.INACTIVE) event.setCancelled(true);
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
        World world = location.getWorld();
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();
        return isSelectedPortalBlock(world, x, y, z)
            || isSelectedPortalBlock(world, x, y + 1, z)
            || isSelectedPortalBlock(world, x, y - 1, z);
    }

    private boolean isSelectedPortalBlock(World world, int x, int y, int z) {
        return state.portalBlocks().contains(new WorldBlockKey(world.getName(), x, y, z))
            && world.getBlockAt(x, y, z).getType() == Material.NETHER_PORTAL;
    }

    private boolean isSelectedPortalBlock(Block block) {
        return block.getType() == Material.NETHER_PORTAL
            && state.portalBlocks().contains(new WorldBlockKey(block.getWorld().getName(), block.getX(), block.getY(), block.getZ()));
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

    private boolean isWand(ItemStack item) {
        return item != null && item.hasItemMeta()
            && item.getItemMeta().getPersistentDataContainer().has(wandKey, PersistentDataType.BYTE);
    }
}
