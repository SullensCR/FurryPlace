package com.furryplace.event.domain;

import org.bukkit.Location;
import org.bukkit.block.Block;

public record WorldBlockKey(String world, int x, int y, int z) {
    public static WorldBlockKey from(Block block) {
        return new WorldBlockKey(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    public boolean matches(Location location) {
        return location.getWorld() != null && world.equals(location.getWorld().getName())
            && x == location.getBlockX() && y == location.getBlockY() && z == location.getBlockZ();
    }
}

