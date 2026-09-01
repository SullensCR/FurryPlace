package com.furryplace.event.protection;

import com.furryplace.event.domain.EventStage;
import com.furryplace.event.domain.PlotRecord;
import com.furryplace.event.domain.RuntimeState;
import com.furryplace.event.world.PlotService;
import org.bukkit.Location;
import org.bukkit.entity.Player;

public final class AccessPolicy {
    private final RuntimeState state;
    private final PlotService plots;

    public AccessPolicy(RuntimeState state, PlotService plots) {
        this.state = state;
        this.plots = plots;
    }

    public boolean admin(Player player) {
        return player.hasPermission("furryplace.admin");
    }

    public boolean mayModify(Player player, Location location) {
        if (admin(player)) return true;
        if (state.stage() != EventStage.ACTIVE) return false;
        PlotRecord plot = plots.plotAt(location).orElse(null);
        return plot != null && plot.complete() && plot.ownerId().equals(player.getUniqueId())
            && plots.isInterior(plot, location);
    }

    public boolean sameActiveInterior(Location from, Location to) {
        if (state.stage() != EventStage.ACTIVE) return false;
        PlotRecord first = plots.plotAt(from).orElse(null);
        PlotRecord second = plots.plotAt(to).orElse(null);
        return first != null && second != null && first.index() == second.index() && first.complete()
            && plots.isInterior(first, from) && plots.isInterior(first, to);
    }

    public boolean inPlace(Location location) {
        return plots.plotWorld().map(world -> location.getWorld() == world).orElse(false);
    }
}
