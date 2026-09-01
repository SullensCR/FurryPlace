package com.furryplace.event.domain;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

public record StoredLocation(String world, double x, double y, double z, float yaw, float pitch) {
    public static StoredLocation from(Location location) {
        if (location.getWorld() == null) {
            throw new IllegalArgumentException("Location has no world");
        }
        return new StoredLocation(location.getWorld().getName(), location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
    }

    public Location resolve() {
        World resolved = Bukkit.getWorld(world);
        return resolved == null ? null : new Location(resolved, x, y, z, yaw, pitch);
    }
}

