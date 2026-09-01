package com.furryplace.event.command;

import com.furryplace.event.domain.EnvironmentSettings;
import com.furryplace.event.domain.EventStage;
import com.furryplace.event.domain.PlotRecord;
import com.furryplace.event.domain.Role;
import com.furryplace.event.domain.RuntimeState;
import com.furryplace.event.domain.StoredLocation;
import com.furryplace.event.domain.VoteLedger;
import com.furryplace.event.item.WandService;
import com.furryplace.event.menu.MenuService;
import com.furryplace.event.packet.PacketBridge;
import com.furryplace.event.persistence.StateRepository;
import com.furryplace.event.player.PlayerLifecycleListener;
import com.furryplace.event.portal.PortalService;
import com.furryplace.event.service.EventCoordinator;
import com.furryplace.event.service.MessageService;
import com.furryplace.event.world.PlotService;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class FurryplaceCommand implements CommandExecutor, TabCompleter, MenuService.Actions {
    private enum Confirmation { START, RESET, TEMPLATE_GENERATE, TEMPLATE_REFRESH, WINNER }
    private record Pending(Confirmation action, UUID target) {}

    private final RuntimeState state;
    private final StateRepository repository;
    private final EventCoordinator coordinator;
    private final PlotService plots;
    private final PlayerLifecycleListener players;
    private final PortalService portal;
    private final WandService wands;
    private final MenuService menus;
    private final PacketBridge packets;
    private final MessageService messages;
    private final Map<UUID, Pending> confirmations = new HashMap<>();

    public FurryplaceCommand(RuntimeState state, StateRepository repository, EventCoordinator coordinator,
                             PlotService plots, PlayerLifecycleListener players, PortalService portal,
                             WandService wands, MenuService menus, PacketBridge packets, MessageService messages) {
        this.state = state;
        this.repository = repository;
        this.coordinator = coordinator;
        this.plots = plots;
        this.players = players;
        this.portal = portal;
        this.wands = wands;
        this.menus = menus;
        this.packets = packets;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player player) openMain(player); else messages.send(sender, "errors.player-only");
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "join" -> {
                if (args.length != 1) messages.send(sender, "errors.invalid-stage");
                else player(sender, plots::join);
            }
            case "menu" -> player(sender, this::openMain);
            case "browse" -> player(sender, player -> menus.open(player, "browser"));
            case "view" -> view(sender, args);
            case "tool" -> tool(sender, args);
            case "winner" -> winnerMenu(sender);
            case "set-spawn" -> setSpawn(sender);
            case "portal-wand" -> adminPlayer(sender, portal::giveWand);
            case "template" -> template(sender, args);
            case "reset" -> confirmAdmin(sender, Confirmation.RESET, null);
            default -> messages.send(sender, "errors.invalid-stage");
        }
        return true;
    }

    @Override
    public void perform(Player player, String action, String payload, MenuService.Click click) {
        switch (action) {
            case "BACK_MAIN" -> openMain(player);
            case "JOIN_OWN" -> plots.join(player);
            case "OPEN_BROWSE" -> menus.open(player, "browser");
            case "OPEN_TOOLS" -> menus.open(player, "tools");
            case "OPEN_JUDGE" -> menus.open(player, "judge-browser");
            case "VIEW_WINNER" -> {
                if (state.winner() != null) plots.view(player, state.winner());
            }
            case "ADMIN_PRIMARY" -> adminPrimary(player);
            case "RESET_CONFIRM" -> openConfirmation(player, Confirmation.RESET, null);
            case "EVENT_START_CONFIRM" -> openConfirmation(player, Confirmation.START, null);
            case "EVENT_DURATION" -> duration(player, click);
            case "CONFIRM" -> executeConfirmation(player);
            case "GIVE_WEATHER" -> ownCurrentPlot(player).ifPresent(plot -> wands.restore(player, WandService.Type.WEATHER));
            case "GIVE_TIME" -> ownCurrentPlot(player).ifPresent(plot -> wands.restore(player, WandService.Type.TIME));
            case "GIVE_BIOME" -> ownCurrentPlot(player).ifPresent(plot -> wands.restore(player, WandService.Type.BIOME));
            case "WEATHER_CLEAR" -> weather(player, EnvironmentSettings.WeatherChoice.CLEAR);
            case "WEATHER_RAIN" -> weather(player, EnvironmentSettings.WeatherChoice.RAIN);
            case "WEATHER_THUNDER" -> weather(player, EnvironmentSettings.WeatherChoice.THUNDER);
            case "TIME_DAWN" -> time(player, EnvironmentSettings.TimeChoice.DAWN);
            case "TIME_DAY" -> time(player, EnvironmentSettings.TimeChoice.DAY);
            case "TIME_NOON" -> time(player, EnvironmentSettings.TimeChoice.NOON);
            case "TIME_SUNSET" -> time(player, EnvironmentSettings.TimeChoice.SUNSET);
            case "TIME_NIGHT" -> time(player, EnvironmentSettings.TimeChoice.NIGHT);
            case "TIME_MIDNIGHT" -> time(player, EnvironmentSettings.TimeChoice.MIDNIGHT);
            case "DYNAMIC_BIOME" -> biome(player, payload);
            case "DYNAMIC_PLAYER" -> participant(player, payload, click, false);
            case "DYNAMIC_JUDGE" -> participant(player, payload, click, true);
            case "DYNAMIC_REVIEW_START" -> startReview(player, payload);
            case "DYNAMIC_WINNER" -> selectWinner(player, payload);
            case "REVIEW_PREVIOUS" -> outcome(player, coordinator.moveReview(player.getUniqueId(), false));
            case "REVIEW_NEXT" -> outcome(player, coordinator.moveReview(player.getUniqueId(), true));
            case "REVIEW_TAKEOVER" -> {
                EventCoordinator.Outcome result = coordinator.takeReviewControl(player.getUniqueId());
                outcome(player, result);
                if (result.success()) messages.send(player, "review.takeover");
            }
            case "REVIEW_END" -> outcome(player, coordinator.endReview(player.getUniqueId()));
            default -> { }
        }
    }

    private void openMain(Player player) {
        if (state.stage() == EventStage.INACTIVE && Role.resolve(player) != Role.ADMIN) {
            menus.open(player, "inactive-info");
            return;
        }
        if (state.stage() == EventStage.COMPLETE && Role.resolve(player) != Role.ADMIN) {
            menus.open(player, "complete");
            return;
        }
        switch (Role.resolve(player)) {
            case ADMIN -> menus.open(player, "main-admin");
            case JUDGE -> menus.open(player, "main-judge");
            case PLAYER -> menus.open(player, "main-player");
        }
    }

    private void adminPrimary(Player player) {
        if (!player.hasPermission("furryplace.admin")) return;
        switch (state.stage()) {
            case INACTIVE, ACTIVE -> menus.open(player, "start-event");
            case REVIEW_PENDING -> menus.open(player, "review-start-browser");
            case REVIEWING -> menus.open(player, "review");
            case JUDGING -> menus.open(player, "winner-browser");
            case COMPLETE -> plots.view(player, state.winner());
        }
    }

    private void duration(Player player, MenuService.Click click) {
        if (!player.hasPermission("furryplace.admin")) return;
        if (click == MenuService.Click.SHIFT_LEFT) {
            player.closeInventory();
            packets.openSign(player, value -> {
                if (value.isBlank()) {
                    menus.open(player, "start-event");
                    return;
                }
                try {
                    coordinator.configureDuration(Integer.parseInt(value.trim()), state.stage() == EventStage.ACTIVE);
                } catch (NumberFormatException exception) {
                    messages.send(player, "errors.invalid-stage");
                }
                menus.open(player, "start-event");
            });
            return;
        }
        int delta = (click == MenuService.Click.RIGHT || click == MenuService.Click.SHIFT_RIGHT) ? -5 : 5;
        coordinator.configureDuration(state.configuredMinutes() + delta, state.stage() == EventStage.ACTIVE);
        menus.open(player, "start-event");
    }

    private void participant(Player player, String payload, MenuService.Click click, boolean judge) {
        UUID owner;
        try { owner = UUID.fromString(payload); }
        catch (IllegalArgumentException exception) { return; }
        if (click == MenuService.Click.LEFT || click == MenuService.Click.SHIFT_LEFT) {
            if (owner.equals(player.getUniqueId()) && state.stage() == EventStage.ACTIVE) plots.join(player);
            else plots.view(player, owner);
            return;
        }
        if (judge) {
            if (Role.resolve(player) != Role.JUDGE || (state.stage() != EventStage.REVIEWING && state.stage() != EventStage.JUDGING)) {
                messages.send(player, "errors.invalid-stage"); return;
            }
            VoteLedger.Result result = state.judgeVotes().toggle(player.getUniqueId(), owner, false);
            repository.save(state);
            voteMessage(player, owner, result);
            menus.open(player, "judge-browser");
            return;
        }
        if (Role.resolve(player) != Role.PLAYER || !player.hasPermission("furryplace.player") || state.stage() != EventStage.ACTIVE
            || state.plot(player.getUniqueId()).filter(PlotRecord::complete).isEmpty()) {
            messages.send(player, "errors.invalid-stage"); return;
        }
        VoteLedger.Result result = state.communityVotes().toggle(player.getUniqueId(), owner, true);
        if (result != VoteLedger.Result.REJECTED_SELF) repository.save(state);
        voteMessage(player, owner, result);
        menus.open(player, "browser");
    }

    private void voteMessage(Player player, UUID owner, VoteLedger.Result result) {
        if (result == VoteLedger.Result.REJECTED_SELF) messages.send(player, "vote.self");
        else if (result == VoteLedger.Result.REMOVED) messages.send(player, "vote.removed");
        else messages.send(player, "vote.selected", Map.of("player", state.plot(owner).map(PlotRecord::ownerName).orElse("?")));
    }

    private void weather(Player player, EnvironmentSettings.WeatherChoice choice) {
        ownCurrentPlot(player).ifPresent(plot -> {
            plot.environment().weather(choice);
            repository.save(state);
            packets.refreshEnvironment(plot);
            player.closeInventory();
        });
    }

    private void time(Player player, EnvironmentSettings.TimeChoice choice) {
        ownCurrentPlot(player).ifPresent(plot -> {
            plot.environment().time(choice);
            repository.save(state);
            packets.refreshEnvironment(plot);
            player.closeInventory();
        });
    }

    private void biome(Player player, String value) {
        NamespacedKey key = NamespacedKey.fromString(value);
        if (key == null) return;
        ownCurrentPlot(player).ifPresent(plot -> {
            plot.environment().biome(key);
            repository.save(state);
            packets.refreshEnvironment(plot);
            player.closeInventory();
        });
    }

    private java.util.Optional<PlotRecord> ownCurrentPlot(Player player) {
        PlotRecord plot = plots.plotAt(player.getLocation()).orElse(null);
        if (state.stage() != EventStage.ACTIVE || plot == null || !plot.ownerId().equals(player.getUniqueId())
            || !plots.isInterior(plot, player.getLocation())) {
            messages.send(player, "plot.outside");
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(plot);
    }

    private void startReview(Player player, String payload) {
        if (!player.hasPermission("furryplace.admin")) return;
        try { outcome(player, coordinator.beginReview(UUID.fromString(payload), player.getUniqueId())); }
        catch (IllegalArgumentException ignored) { }
        player.closeInventory();
    }

    private void selectWinner(Player player, String payload) {
        if (!player.hasPermission("furryplace.admin") || state.stage() != EventStage.JUDGING) return;
        try { openConfirmation(player, Confirmation.WINNER, UUID.fromString(payload)); }
        catch (IllegalArgumentException ignored) { }
    }

    private void executeConfirmation(Player player) {
        Pending pending = confirmations.remove(player.getUniqueId());
        player.closeInventory();
        if (pending == null || !player.hasPermission("furryplace.admin")) return;
        switch (pending.action()) {
            case START -> {
                Bukkit.getOnlinePlayers().forEach(players::sendLobby);
                if (!plots.freezeTemplate(success -> {
                    if (success) outcome(player, coordinator.startEvent());
                    else messages.send(player, "errors.invalid-stage");
                })) messages.send(player, "errors.invalid-stage");
            }
            case RESET -> coordinator.requestReset();
            case TEMPLATE_GENERATE -> {
                Bukkit.getOnlinePlayers().forEach(players::sendLobby);
                plots.generateTemplate(success -> messages.send(player, success ? "template.generated" : "errors.invalid-stage"));
            }
            case TEMPLATE_REFRESH -> plots.refreshTemplate(success -> messages.send(player, success ? "template.refreshed" : "errors.invalid-stage"));
            case WINNER -> outcome(player, coordinator.confirmWinner(player.getUniqueId(), pending.target()));
        }
    }

    private void openConfirmation(Player player, Confirmation action, UUID target) {
        confirmations.put(player.getUniqueId(), new Pending(action, target));
        menus.open(player, "confirm");
    }

    private void view(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) { messages.send(sender, "errors.player-only"); return; }
        if (args.length < 2) { messages.send(sender, "errors.player-not-found", Map.of("player", "?")); return; }
        PlotRecord plot = state.plotByName(args[1]).orElse(null);
        if (plot == null) { messages.send(sender, "errors.player-not-found", Map.of("player", args[1])); return; }
        if (plot.ownerId().equals(player.getUniqueId()) && state.stage() == EventStage.ACTIVE) plots.join(player);
        else plots.view(player, plot.ownerId());
    }

    private void tool(CommandSender sender, String[] args) {
        if (args.length < 2) { messages.send(sender, "errors.invalid-stage"); return; }
        WandService.Type type;
        try { type = WandService.Type.valueOf(args[1].toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException exception) { messages.send(sender, "errors.invalid-stage"); return; }
        Player target;
        if (args.length >= 3) {
            if (!sender.hasPermission("furryplace.admin")) { messages.send(sender, "errors.no-permission"); return; }
            target = Bukkit.getPlayerExact(args[2]);
            if (target == null) { messages.send(sender, "errors.player-not-found", Map.of("player", args[2])); return; }
        } else if (sender instanceof Player player) target = player;
        else { messages.send(sender, "errors.console-target-required"); return; }
        if (ownCurrentPlot(target).isEmpty()) return;
        wands.restore(target, type);
    }

    private void winnerMenu(CommandSender sender) {
        player(sender, player -> {
            if (Role.resolve(player) == Role.ADMIN && state.stage() == EventStage.JUDGING) menus.open(player, "winner-browser");
            else if (Role.resolve(player) == Role.JUDGE && (state.stage() == EventStage.REVIEWING || state.stage() == EventStage.JUDGING)) menus.open(player, "judge-browser");
            else if (state.stage() == EventStage.COMPLETE && state.winner() != null) plots.view(player, state.winner());
            else messages.send(player, "errors.invalid-stage");
        });
    }

    private void setSpawn(CommandSender sender) {
        adminPlayer(sender, player -> {
            if (!players.isLobby(player.getWorld())) { messages.send(player, "errors.admin-not-lobby"); return; }
            state.lobbySpawn(StoredLocation.from(player.getLocation()));
            repository.save(state);
            messages.send(player, "spawn.set");
        });
    }

    private void template(CommandSender sender, String[] args) {
        if (!sender.hasPermission("furryplace.admin")) { messages.send(sender, "errors.no-permission"); return; }
        if (!(sender instanceof Player player)) { messages.send(sender, "errors.player-only"); return; }
        if (args.length < 2) { messages.send(sender, "errors.invalid-stage"); return; }
        if (args[1].equalsIgnoreCase("generate") && state.stage() == EventStage.INACTIVE) openConfirmation(player, Confirmation.TEMPLATE_GENERATE, null);
        else if (args[1].equalsIgnoreCase("refresh") && state.stage() == EventStage.ACTIVE) openConfirmation(player, Confirmation.TEMPLATE_REFRESH, null);
        else messages.send(sender, "errors.invalid-stage");
    }

    private void confirmAdmin(CommandSender sender, Confirmation confirmation, UUID target) {
        if (!sender.hasPermission("furryplace.admin")) { messages.send(sender, "errors.no-permission"); return; }
        if (!(sender instanceof Player player)) { messages.send(sender, "errors.player-only"); return; }
        openConfirmation(player, confirmation, target);
    }

    private void adminPlayer(CommandSender sender, java.util.function.Consumer<Player> action) {
        if (!sender.hasPermission("furryplace.admin")) { messages.send(sender, "errors.no-permission"); return; }
        player(sender, action);
    }

    private void player(CommandSender sender, java.util.function.Consumer<Player> action) {
        if (!(sender instanceof Player player)) { messages.send(sender, "errors.player-only"); return; }
        action.accept(player);
    }

    private void outcome(Player player, EventCoordinator.Outcome outcome) {
        if (!outcome.success() && outcome.messageKey() != null) messages.send(player, outcome.messageKey());
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> values = new ArrayList<>();
        if (args.length == 1) {
            values.addAll(List.of("join", "menu", "browse", "view", "tool", "winner"));
            if (sender.hasPermission("furryplace.admin")) values.addAll(List.of("set-spawn", "portal-wand", "template", "reset"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("tool")) values.addAll(List.of("weather", "time", "biome"));
        else if (args.length == 2 && args[0].equalsIgnoreCase("template")) values.addAll(List.of("generate", "refresh"));
        else if (args.length == 2 && args[0].equalsIgnoreCase("view")) values.addAll(state.completedPlotsInAllocationOrder().stream().map(PlotRecord::ownerName).toList());
        else if (args.length == 3 && args[0].equalsIgnoreCase("tool") && sender.hasPermission("furryplace.admin")) values.addAll(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted().toList();
    }
}
