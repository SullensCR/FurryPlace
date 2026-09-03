package com.furryplace.event.protection;

import com.furryplace.event.domain.EventStage;
import com.furryplace.event.domain.PlotRecord;
import com.furryplace.event.domain.RuntimeState;
import com.furryplace.event.review.ReviewControlService;
import com.furryplace.event.world.PlotService;
import org.bukkit.Location;
import org.bukkit.entity.Player;

public final class AccessPolicy {
    private final RuntimeState state;
    private final PlotService plots;
    private final ReviewControlService reviewControls;

    public AccessPolicy(RuntimeState state, PlotService plots, ReviewControlService reviewControls) {
        this.state = state;
        this.plots = plots;
        this.reviewControls = reviewControls;
    }

    public AccessPolicy(RuntimeState state, PlotService plots) {
        this(state, plots, null);
    }

    public boolean admin(Player player) {
        return player.hasPermission("furryplace.admin");
    }

    public boolean mayModify(Player player, Location location) {
        if (state.stage() == EventStage.REVIEWING) return reviewControls != null && reviewControls.mayModify(player, location);
        if (admin(player)) return true;
        if (state.stage() != EventStage.ACTIVE) return false;
        PlotRecord plot = plots.plotAt(location).orElse(null);
        return plot != null && plot.complete() && plot.ownerId().equals(player.getUniqueId())
            && plots.isInterior(plot, location);
    }

    public boolean sameEditableInterior(Location from, Location to) {
        if (state.stage() != EventStage.ACTIVE && state.stage() != EventStage.REVIEWING) return false;
        PlotRecord first = plots.plotAt(from).orElse(null);
        PlotRecord second = plots.plotAt(to).orElse(null);
        if (first == null || second == null || first.index() != second.index() || !first.complete()
            || !plots.isInterior(first, from) || !plots.isInterior(first, to)) return false;
        if (state.stage() == EventStage.REVIEWING) {
            return reviewControls != null && reviewControls.currentPlotHasEditor(from) && reviewControls.currentPlotHasEditor(to)
                && state.review().current() != null && state.review().current().equals(first.ownerId());
        }
        return true;
    }

    public boolean allowsIndirect(Location location) {
        if (!inPlace(location)) return true;
        if (state.stage() == EventStage.REVIEWING) return reviewControls != null && reviewControls.currentPlotHasEditor(location);
        return false;
    }

    public boolean reviewing() {
        return state.stage() == EventStage.REVIEWING;
    }

    public boolean inPlace(Location location) {
        return plots.plotWorld().map(world -> location.getWorld() == world).orElse(false);
    }
}
