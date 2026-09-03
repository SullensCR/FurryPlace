package com.furryplace.event;

import com.furryplace.event.command.FurryplaceCommand;
import com.furryplace.event.bedrock.BedrockFormGateway;
import com.furryplace.event.bedrock.UnavailableBedrockFormGateway;
import com.furryplace.event.config.ConfigurationMigrationService;
import com.furryplace.event.domain.RuntimeState;
import com.furryplace.event.item.WandService;
import com.furryplace.event.item.MenuItemService;
import com.furryplace.event.menu.MenuService;
import com.furryplace.event.packet.PacketBridge;
import com.furryplace.event.persistence.OrderedDataWriter;
import com.furryplace.event.persistence.StateRepository;
import com.furryplace.event.player.PlayerLifecycleListener;
import com.furryplace.event.player.PlayerStateService;
import com.furryplace.event.portal.PortalService;
import com.furryplace.event.protection.AccessPolicy;
import com.furryplace.event.protection.ProtectionListener;
import com.furryplace.event.review.ReviewControlService;
import com.furryplace.event.service.EventCoordinator;
import com.furryplace.event.service.EventRuntimeHooks;
import com.furryplace.event.service.MessageService;
import com.furryplace.event.world.BlockOperationQueue;
import com.furryplace.event.world.PlotService;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class FurryplaceEventPlugin extends JavaPlugin {
    private OrderedDataWriter writer;
    private StateRepository repository;
    private RuntimeState state;
    private EventCoordinator coordinator;
    private BlockOperationQueue operations;
    private PlayerStateService playerStates;
    private PacketBridge packets;
    private ReviewControlService reviewControls;

    @Override
    public void onEnable() {
        try {
            new ConfigurationMigrationService(this).migrate();
            reloadConfig();
        } catch (java.io.IOException exception) {
            getLogger().severe("No se pudieron preparar las configuraciones versionadas: " + exception.getMessage());
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        String plotsWorld = getConfig().getString("worlds.plots", "place");
        String templateWorld = getConfig().getString("worlds.template", "place-template");
        if (Bukkit.getWorld(plotsWorld) == null || Bukkit.getWorld(templateWorld) == null) {
            getLogger().severe("Los mundos '" + plotsWorld + "' y '" + templateWorld + "' deben existir antes de iniciar FurryplaceEvent.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        if (Bukkit.getWorld(getConfig().getString("worlds.lobby", "lobby")) == null) {
            getLogger().severe("El mundo lobby no está disponible; los teletransportes fallarán de forma segura.");
        }

        writer = new OrderedDataWriter(this);
        repository = new StateRepository(this, writer);
        state = repository.load(getConfig().getInt("timer.default-minutes", 20));
        MessageService messages = new MessageService(this);
        packets = new PacketBridge(this);
        packets.register();
        operations = new BlockOperationQueue(this, state, repository);
        PlotService plots = new PlotService(this, state, repository, messages, operations);
        if (state.templateInitialized() && !plots.loadSnapshot()) {
            getLogger().severe("La instantánea indicada en los datos no existe o está dañada; se requiere regenerar la plantilla.");
            state.templateInitialized(false);
            state.snapshotVersion(0L);
            repository.save(state);
        }
        playerStates = new PlayerStateService(this);
        reviewControls = new ReviewControlService(this, state, plots, messages);
        BedrockFormGateway bedrockForms = bedrockForms();
        MenuService menus = new MenuService(this, state, packets, messages, bedrockForms);
        WandService wands = new WandService(this, state, plots, menus, messages);
        coordinator = new EventCoordinator(this, state, repository, messages);
        PlayerLifecycleListener lifecycle = new PlayerLifecycleListener(this, state, repository, plots, playerStates,
            packets, coordinator, messages, reviewControls);
        plots.entryHandler(playerStates);
        PortalService portal = new PortalService(this, state, repository, messages);
        portal.router(lifecycle::routeForStage);
        ProtectionListener protection = new ProtectionListener(this, new AccessPolicy(state, plots, reviewControls), plots, messages, wands);
        FurryplaceCommand command = new FurryplaceCommand(state, repository, coordinator, plots, lifecycle, portal,
            wands, menus, packets, messages, bedrockForms);
        MenuItemService menuItem = new MenuItemService(this, messages, command::openMain);
        menus.actions(command);
        coordinator.hooks(new EventRuntimeHooks(this, state, plots, lifecycle, playerStates, messages, reviewControls));

        Bukkit.getPluginManager().registerEvents(menus, this);
        Bukkit.getPluginManager().registerEvents(wands, this);
        Bukkit.getPluginManager().registerEvents(menuItem, this);
        Bukkit.getPluginManager().registerEvents(reviewControls, this);
        Bukkit.getPluginManager().registerEvents(portal, this);
        Bukkit.getPluginManager().registerEvents(protection, this);
        Bukkit.getPluginManager().registerEvents(lifecycle, this);
        PluginCommand furryplace = getCommand("furryplace");
        if (furryplace == null) throw new IllegalStateException("Falta el comando furryplace en plugin.yml");
        furryplace.setExecutor(command);
        furryplace.setTabCompleter(command);

        operations.start();
        plots.recoverInterruptedOperations();
        coordinator.startTicker();
        getLogger().info("FurryplaceEvent " + getDescription().getVersion() + " está listo.");
    }

    private BedrockFormGateway bedrockForms() {
        if (!Bukkit.getPluginManager().isPluginEnabled("floodgate")) {
            return new UnavailableBedrockFormGateway();
        }
        try {
            return (BedrockFormGateway) Class.forName("com.furryplace.event.bedrock.FloodgateFormGateway")
                .getConstructor(JavaPlugin.class).newInstance(this);
        } catch (ReflectiveOperationException exception) {
            getLogger().warning("No se pudo activar el puente de formularios Bedrock: " + exception.getMessage());
            return new UnavailableBedrockFormGateway();
        }
    }

    @Override
    public void onDisable() {
        if (coordinator != null) coordinator.stopTicker();
        if (operations != null) operations.stop();
        if (reviewControls != null) reviewControls.cleanupAll();
        if (playerStates != null) playerStates.shutdown(Bukkit.getOnlinePlayers());
        if (packets != null) packets.unregister();
        if (repository != null && state != null) {
            repository.save(state);
        }
        if (writer != null) writer.close();
    }
}
