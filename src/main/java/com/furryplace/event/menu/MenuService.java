package com.furryplace.event.menu;

import com.furryplace.event.domain.PlotRecord;
import com.furryplace.event.domain.RuntimeState;
import com.furryplace.event.config.ConfigurationMigrationService;
import com.furryplace.event.packet.PacketBridge;
import com.furryplace.event.service.MessageService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Validated YAML inventories with dynamic participant and biome pages. */
public final class MenuService implements Listener {
    public interface Actions {
        void perform(Player player, String action, String payload, Click click);
    }

    public enum Click { LEFT, RIGHT, SHIFT_LEFT, SHIFT_RIGHT }

    private static final Set<String> ACTIONS = Set.of(
        "CLOSE", "BACK_MAIN", "JOIN_OWN", "OPEN_BROWSE", "OPEN_TOOLS", "OPEN_JUDGE",
        "ADMIN_PRIMARY", "RESET_CONFIRM", "EVENT_START_CONFIRM", "EVENT_DURATION", "CONFIRM", "VIEW_WINNER",
        "PAGE_PREVIOUS", "PAGE_NEXT", "SEARCH", "GIVE_WEATHER", "GIVE_TIME", "GIVE_BIOME",
        "WEATHER_CLEAR", "WEATHER_RAIN", "WEATHER_THUNDER", "TIME_DAWN", "TIME_DAY", "TIME_NOON",
        "TIME_SUNSET", "TIME_NIGHT", "TIME_MIDNIGHT", "REVIEW_PREVIOUS", "REVIEW_TAKEOVER",
        "REVIEW_END", "REVIEW_NEXT"
    );

    private static final List<String> FILES = ConfigurationMigrationService.MENU_NAMES;

    private static final class Holder implements InventoryHolder {
        private final String menu;
        private final int page;
        private final String query;
        private final String returnMenu;
        private final Map<Integer, SlotAction> actions = new HashMap<>();
        private Inventory inventory;

        private Holder(String menu, int page, String query, String returnMenu) {
            this.menu = menu;
            this.page = page;
            this.query = query;
            this.returnMenu = returnMenu;
        }

        @Override public Inventory getInventory() { return inventory; }
    }

    private record SlotAction(String action, String payload, String sound) {}
    private record Definition(String name, int size, List<Integer> dynamicSlots, List<ConfiguredItem> items) {}
    private record ConfiguredItem(String id, List<Integer> slots, String action, Material material, String name,
                                  List<String> lore, boolean glow, String sound) {}
    private record DynamicEntry(String payload, String search, ItemStack item) {}

    private final JavaPlugin plugin;
    private final RuntimeState state;
    private final PacketBridge packets;
    private final MessageService messages;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Map<String, Definition> definitions = new HashMap<>();
    private final Map<String, String> biomeNames = new HashMap<>();
    private Actions actions;

    public MenuService(JavaPlugin plugin, RuntimeState state, PacketBridge packets, MessageService messages) {
        this.plugin = plugin;
        this.state = state;
        this.packets = packets;
        this.messages = messages;
        loadBiomeNames();
        reload();
    }

    public void actions(Actions value) {
        actions = value;
    }

    public void reload() {
        definitions.clear();
        for (String name : FILES) definitions.put(name, load(name));
    }

    public void open(Player player, String menu) {
        open(player, menu, 0, "", null);
    }

    public void open(Player player, String menu, int page, String query, String returnMenu) {
        Definition definition = definitions.get(menu);
        if (definition == null) return;
        List<DynamicEntry> dynamic = dynamicEntries(menu, player);
        if (!query.isBlank()) dynamic = dynamic.stream().filter(entry -> entry.search().contains(query.toLowerCase(Locale.ROOT))).toList();
        int pageSize = Math.max(1, definition.dynamicSlots().size());
        int pages = Math.max(1, (dynamic.size() + pageSize - 1) / pageSize);
        int boundedPage = Math.max(0, Math.min(page, pages - 1));
        PlotRecord winner = state.winner() == null ? null : state.plot(state.winner()).orElse(null);
        TagResolver placeholders = TagResolver.resolver(
            Placeholder.unparsed("page", Integer.toString(boundedPage + 1)),
            Placeholder.unparsed("pages", Integer.toString(pages)),
            Placeholder.unparsed("minutes", Integer.toString(state.configuredMinutes())),
            Placeholder.unparsed("winner", winner == null ? "-" : winner.ownerName()),
            Placeholder.unparsed("votes", winner == null ? "0" : Integer.toString(state.communityVotes().countFor(winner.ownerId())))
        );
        Holder holder = new Holder(menu, boundedPage, query, returnMenu);
        Inventory inventory = Bukkit.createInventory(holder, definition.size(), miniMessage.deserialize(definition.name(), placeholders));
        holder.inventory = inventory;
        applyUniversalPattern(inventory);
        for (ConfiguredItem configured : definition.items()) {
            if (menu.equals("start-event") && configured.id().equals("border")) continue;
            ItemStack item = item(configured.material(), configured.name(), configured.lore(), configured.glow(), placeholders);
            for (int slot : configured.slots()) {
                if (slot < 0 || slot >= inventory.getSize()) continue;
                inventory.setItem(slot, item);
                if (configured.action() != null) holder.actions.put(slot, new SlotAction(configured.action(), "", configured.sound()));
            }
        }
        int from = boundedPage * pageSize;
        for (int index = 0; index < definition.dynamicSlots().size() && from + index < dynamic.size(); index++) {
            int slot = definition.dynamicSlots().get(index);
            DynamicEntry entry = dynamic.get(from + index);
            inventory.setItem(slot, entry.item());
            String action = switch (menu) {
                case "biome" -> "DYNAMIC_BIOME";
                case "judge-browser" -> "DYNAMIC_JUDGE";
                case "review-start-browser" -> "DYNAMIC_REVIEW_START";
                case "winner-browser" -> "DYNAMIC_WINNER";
                default -> "DYNAMIC_PLAYER";
            };
            holder.actions.put(slot, new SlotAction(action, entry.payload(), null));
        }
        player.openInventory(inventory);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof Holder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getClickedInventory() != event.getInventory()) return;
        SlotAction selected = holder.actions.get(event.getRawSlot());
        if (selected == null) return;
        if (selected.sound() != null && !selected.sound().isBlank()) {
            player.playSound(player.getLocation(), selected.sound(), 1.0f, 1.0f);
        }
        Click click = event.isShiftClick() ? (event.isLeftClick() ? Click.SHIFT_LEFT : Click.SHIFT_RIGHT)
            : (event.isLeftClick() ? Click.LEFT : Click.RIGHT);
        switch (selected.action()) {
            case "CLOSE" -> player.closeInventory();
            case "PAGE_PREVIOUS" -> open(player, holder.menu, holder.page - 1, holder.query, holder.returnMenu);
            case "PAGE_NEXT" -> open(player, holder.menu, holder.page + 1, holder.query, holder.returnMenu);
            case "SEARCH" -> {
                player.closeInventory();
                packets.openSign(player, query -> open(player, holder.menu, 0, query, holder.returnMenu));
            }
            default -> {
                if (actions != null) actions.perform(player, selected.action(), selected.payload(), click);
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        // Holder state is immutable and intentionally discarded with the inventory.
    }

    private List<DynamicEntry> dynamicEntries(String menu, Player viewer) {
        if (menu.equals("browser") || menu.equals("judge-browser") || menu.equals("review-start-browser")
            || menu.equals("winner-browser")) {
            return state.completedPlotsInAllocationOrder().stream().map(plot -> participantItem(plot, menu, viewer)).toList();
        }
        if (menu.equals("biome")) {
            List<DynamicEntry> result = new ArrayList<>();
            for (org.bukkit.block.Biome biome : Registry.BIOME) {
                NamespacedKey key = biome.getKey();
                String label = biomeNames.getOrDefault(key.asString(), humanize(key.getKey()));
                TagResolver resolver = TagResolver.resolver(Placeholder.unparsed("biome", label),
                    Placeholder.unparsed("key", key.asString()));
                ItemStack item = item(Material.GRASS_BLOCK, messages.raw("menu-items.biome-name", "<green><biome></green>"),
                    List.of(messages.raw("menu-items.biome-key", "<gray><key></gray>")), false, resolver);
                result.add(new DynamicEntry(key.asString(), (label + " " + key.asString()).toLowerCase(Locale.ROOT), item));
            }
            result.sort(Comparator.comparing(DynamicEntry::search));
            return result;
        }
        return List.of();
    }

    private DynamicEntry participantItem(PlotRecord plot, String menu, Player viewer) {
        boolean judgeCounts = menu.equals("judge-browser") || menu.equals("winner-browser");
        int votes = judgeCounts ? state.judgeVotes().countFor(plot.ownerId())
            : state.communityVotes().countFor(plot.ownerId());
        List<String> lore = new ArrayList<>();
        lore.add(messages.raw("menu-items.participant-plot", "<gray>Parcela <plot></gray>"));
        lore.add(messages.raw("menu-items.participant-votes", "<aqua>Votos: <votes></aqua>"));
        if (menu.equals("browser")) {
            lore.add(messages.raw("menu-items.participant-visit", "<gray>Click izquierdo: visitar</gray>"));
            lore.add(messages.raw("menu-items.participant-vote", "<gray>Click derecho: votar o quitar voto</gray>"));
        } else if (menu.equals("judge-browser")) {
            lore.add(messages.raw("menu-items.participant-visit", "<gray>Click izquierdo: visitar</gray>"));
            lore.add(messages.raw("menu-items.participant-vote", "<gray>Click derecho: votar o quitar voto</gray>"));
        }
        if (judgeCounts) {
            List<String> names = state.judgeVotes().votersFor(plot.ownerId()).stream()
                .map(Bukkit::getOfflinePlayer).map(player -> player.getName() == null ? "?" : player.getName()).toList();
            if (!names.isEmpty()) lore.add(messages.raw("menu-items.participant-judges", "<yellow>Jueces: <judges></yellow>"));
        }
        int judgeMaximum = state.completedPlotsInAllocationOrder().stream()
            .mapToInt(candidate -> state.judgeVotes().countFor(candidate.ownerId())).max().orElse(0);
        boolean glow = menu.equals("winner-browser")
            ? judgeMaximum > 0 && state.judgeVotes().countFor(plot.ownerId()) == judgeMaximum
            : plot.ownerId().equals((judgeCounts ? state.judgeVotes() : state.communityVotes()).selectionOf(viewer.getUniqueId()));
        TagResolver participant = TagResolver.resolver(
            Placeholder.unparsed("player", plot.ownerName()), Placeholder.unparsed("plot", Integer.toString(plot.index())),
            Placeholder.unparsed("votes", Integer.toString(votes)), Placeholder.unparsed("judges", String.join(", ",
                state.judgeVotes().votersFor(plot.ownerId()).stream().map(Bukkit::getOfflinePlayer)
                    .map(offline -> offline.getName() == null ? "?" : offline.getName()).toList())));
        ItemStack head = item(Material.PLAYER_HEAD, messages.raw("menu-items.participant-name", "<yellow><player></yellow>"), lore,
            glow, participant);
        if (head.getItemMeta() instanceof SkullMeta skull) {
            skull.setOwningPlayer(Bukkit.getOfflinePlayer(plot.ownerId()));
            head.setItemMeta(skull);
        }
        return new DynamicEntry(plot.ownerId().toString(), (plot.ownerName() + " " + plot.ownerId()).toLowerCase(Locale.ROOT), head);
    }

    private Definition load(String name) {
        File file = new File(plugin.getDataFolder(), "menus/" + name + ".yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        try {
            String title = required(yaml, "name", name);
            miniMessage.deserialize(title);
            int size = yaml.getInt("size");
            if (size < 9 || size > 54 || size % 9 != 0) throw invalid(name, "size", "debe ser un múltiplo de 9 entre 9 y 54");
            List<Integer> dynamic = parseSlots(yaml.getList("dynamic-slots", List.of()), name, "dynamic-slots", size);
            List<ConfiguredItem> items = new ArrayList<>();
            ConfigurationSection section = yaml.getConfigurationSection("items");
            if (section == null) throw invalid(name, "items", "sección ausente");
            for (String id : section.getKeys(false)) items.add(parseItem(section.getConfigurationSection(id), name, "items." + id, size));
            return new Definition(title, size, dynamic, items);
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().severe(exception.getMessage() + "; se usará la definición incluida.");
            YamlConfiguration fallback = YamlConfiguration.loadConfiguration(new InputStreamReader(
                plugin.getResource("menus/" + name + ".yml"), StandardCharsets.UTF_8));
            return parseTrusted(fallback, name);
        }
    }

    private Definition parseTrusted(YamlConfiguration yaml, String name) {
        int size = yaml.getInt("size", 27);
        List<ConfiguredItem> items = new ArrayList<>();
        ConfigurationSection section = yaml.getConfigurationSection("items");
        if (section != null) for (String id : section.getKeys(false)) items.add(parseItem(section.getConfigurationSection(id), name, "items." + id, size));
        return new Definition(yaml.getString("name", name), size,
            parseSlots(yaml.getList("dynamic-slots", List.of()), name, "dynamic-slots", size), items);
    }

    private ConfiguredItem parseItem(ConfigurationSection section, String menu, String path, int size) {
        if (section == null) throw invalid(menu, path, "sección ausente");
        ConfigurationSection item = section.getConfigurationSection("item");
        if (item == null) throw invalid(menu, path + ".item", "sección ausente");
        String materialName = item.getString("material");
        if (materialName == null) throw invalid(menu, path + ".item.material", "valor ausente");
        Material material = Material.matchMaterial(materialName);
        if (material == null || !material.isItem()) throw invalid(menu, path + ".item.material", "material inválido");
        String action = section.getString("action");
        if (action != null && !ACTIONS.contains(action)) throw invalid(menu, path + ".action", "acción desconocida " + action);
        List<?> rawSlots = section.contains("slot") ? List.of(section.getInt("slot")) : section.getList("slots", List.of());
        List<Integer> slots = parseSlots(rawSlots, menu, path + ".slots", size);
        if (slots.isEmpty()) throw invalid(menu, path + ".slots", "no contiene ranuras");
        String itemName = item.getString("name", " ");
        List<String> lore = item.getStringList("lore");
        miniMessage.deserialize(itemName);
        lore.forEach(miniMessage::deserialize);
        String sound = section.getString("sound", item.getString("sound"));
        if (sound != null) {
            NamespacedKey soundKey = NamespacedKey.fromString(sound);
            if (soundKey == null || Registry.SOUNDS.get(soundKey) == null) throw invalid(menu, path + ".sound", "sonido inválido");
        }
        return new ConfiguredItem(section.getName(), slots, action, material, itemName, lore,
            item.getBoolean("glow"), sound);
    }

    private List<Integer> parseSlots(List<?> raw, String menu, String path, int size) {
        List<Integer> slots = new ArrayList<>();
        for (Object value : raw) {
            if (value instanceof Number number) slots.add(number.intValue());
            else {
                String text = String.valueOf(value);
                if (text.matches("\\d+-\\d+")) {
                    String[] split = text.split("-", 2);
                    for (int slot = Integer.parseInt(split[0]); slot <= Integer.parseInt(split[1]); slot++) slots.add(slot);
                } else if (text.matches("\\d+")) slots.add(Integer.parseInt(text));
                else throw invalid(menu, path, "rango inválido " + text);
            }
        }
        if (slots.stream().anyMatch(slot -> slot < 0 || slot >= size)) throw invalid(menu, path, "ranura fuera del inventario");
        return List.copyOf(slots);
    }

    private ItemStack item(Material material, String name, List<String> lore, boolean glow, TagResolver placeholders) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(miniMessage.deserialize(name.replace("\r", ""), placeholders));
        if (!lore.isEmpty()) meta.lore(lore.stream().map(line -> miniMessage.deserialize(line.replace("\r", ""), placeholders)).toList());
        if (glow) {
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
        stack.setItemMeta(meta);
        return stack;
    }

    private void applyUniversalPattern(Inventory inventory) {
        ItemStack pane = item(Material.GRAY_STAINED_GLASS_PANE, messages.raw("menu-items.pattern-name", " "),
            List.of(), false, TagResolver.empty());
        int size = inventory.getSize();
        for (int slot = 0; slot < size; slot++) {
            int column = slot % 9;
            if (slot < 9 || slot >= size - 9 || column == 0 || column == 8) inventory.setItem(slot, pane.clone());
        }
    }

    private String required(ConfigurationSection section, String path, String menu) {
        String value = section.getString(path);
        if (value == null) throw invalid(menu, path, "valor ausente");
        return value;
    }

    private IllegalArgumentException invalid(String menu, String path, String reason) {
        return new IllegalArgumentException("menus/" + menu + ".yml: " + path + " " + reason);
    }

    private String humanize(String key) {
        return Arrays.stream(key.split("_")).map(word -> Character.toUpperCase(word.charAt(0)) + word.substring(1))
            .reduce((left, right) -> left + " " + right).orElse(key);
    }

    private void loadBiomeNames() {
        biomeNames.clear();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "biome-names.yml"));
        ConfigurationSection section = yaml.getConfigurationSection("names");
        if (section == null) return;
        for (String namespace : section.getKeys(false)) {
            ConfigurationSection values = section.getConfigurationSection(namespace);
            if (values == null) continue;
            for (String key : values.getKeys(false)) biomeNames.put(namespace + ":" + key, values.getString(key, humanize(key)));
        }
    }
}
