package com.furryplace.event.domain;

import org.bukkit.Location;

import java.util.OptionalInt;

public record PlotBounds(int index, int originX, int originZ, int size, int boundaryWidth) {
    public PlotBounds {
        if (index < 1 || size < 1 || boundaryWidth < 0) {
            throw new IllegalArgumentException("Invalid plot geometry");
        }
    }

    public static PlotBounds forIndex(int index, int size, int spacing, int boundaryWidth) {
        return new PlotBounds(index, Math.multiplyExact(index - 1, spacing), 0, size, boundaryWidth);
    }

    public static OptionalInt locateIndex(int x, int z, int size, int spacing, int boundaryWidth, int maximum) {
        if (z < -boundaryWidth || z >= size + boundaryWidth) {
            return OptionalInt.empty();
        }
        int candidate = Math.floorDiv(x + boundaryWidth, spacing) + 1;
        if (candidate < 1 || candidate > maximum) {
            return OptionalInt.empty();
        }
        PlotBounds bounds = forIndex(candidate, size, spacing, boundaryWidth);
        return bounds.containsManaged(x, z) ? OptionalInt.of(candidate) : OptionalInt.empty();
    }

    public int maxX() {
        return originX + size - 1;
    }

    public int maxZ() {
        return originZ + size - 1;
    }

    public boolean containsInterior(int x, int z) {
        return x >= originX && x <= maxX() && z >= originZ && z <= maxZ();
    }

    public boolean containsManaged(int x, int z) {
        return x >= originX - boundaryWidth && x <= maxX() + boundaryWidth
            && z >= originZ - boundaryWidth && z <= maxZ() + boundaryWidth;
    }

    public boolean containsBoundary(int x, int z) {
        return containsManaged(x, z) && !containsInterior(x, z);
    }

    public double centerX() {
        return originX + (size / 2.0) - 0.5;
    }

    public double centerZ() {
        return originZ + (size / 2.0) - 0.5;
    }

    public boolean containsInterior(Location location) {
        return containsInterior(location.getBlockX(), location.getBlockZ());
    }
}

