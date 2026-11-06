package kz.valqauym.upgrader;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;

public final class UpgraderPlugin extends JavaPlugin implements Listener {
    private final ItemValues values = new ItemValues();
    private final Map<UUID, UpgraderGui> sessions = new HashMap<>();
    private final Map<UUID, CatalogGui> searching = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, String> ruNames = new HashMap<>();
    private NamespacedKey itemKey, recipeKey;
    private UUID packId;
    private byte[] packHash;

    @Override public void onEnable() {
        saveDefaultConfig();
        itemKey = new NamespacedKey(this, "upgrader");
        recipeKey = new NamespacedKey(this, "upgrader");
        loadAll();
        getServer().getPluginManager().registerEvents(this, this);
        registerRecipe();
    }

    /** Adds the crafting recipe (again). /minecraft:reload wipes plugin recipes, so this also runs after every reload. */
    void registerRecipe() {
        if (Bukkit.getRecipe(recipeKey) == null) {
            ShapedRecipe r = new ShapedRecipe(recipeKey, upgraderItem());
            r.shape("GDG", "DAD", "GDG");
            r.setIngredient('G', Material.GOLD_INGOT);
            r.setIngredient('D', Material.DIAMOND);
            r.setIngredient('A', Material.ANVIL);
            Bukkit.addRecipe(r, true);
        }
        for (Player p : Bukkit.getOnlinePlayers()) p.discoverRecipe(recipeKey);
    }

    @EventHandler public void onDataReload(io.papermc.paper.event.server.ServerResourcesReloadedEvent e) {
        Bukkit.getScheduler().runTask(this, this::registerRecipe);
    }

    @Override public void onDisable() {
        for (UpgraderGui g : new ArrayList<>(sessions.values())) {
            g.forceFinish();
            g.player.closeInventory();
            g.onClose();
        }
        sessions.clear();
        Bukkit.removeRecipe(recipeKey);
    }

    private void loadAll() {
        reloadConfig();
        values.load(getConfig());
        getLogger().info("Priced " + values.targets().size() + " target items");
        ruNames.clear();
        File lang = new File(getDataFolder(), "ru_ru.json");
        if (lang.exists()) {
            try (Reader rd = new InputStreamReader(new FileInputStream(lang), StandardCharsets.UTF_8)) {
                JsonObject o = JsonParser.parseReader(rd).getAsJsonObject();
                for (String k : o.keySet()) if (k.startsWith("item.minecraft.") || k.startsWith("block.minecraft."))
                    ruNames.put(k, o.get(k).getAsString().toLowerCase(Locale.ROOT));
            } catch (Exception e) {
                getLogger().warning("ru_ru.json: " + e.getMessage());
            }
        }
        String sha = getConfig().getString("resource-pack.sha1", "");
        packHash = sha.length() == 40 ? HexFormat.of().parseHex(sha) : null;
        packId = UUID.nameUUIDFromBytes(("valqauym-pack-" + sha).getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ config accessors
    ItemValues values() { return values; }
    double houseEdge() { return getConfig().getDouble("house-edge", 0.1); }
    int spinTicks() { return getConfig().getInt("spin-ticks", 90); }
    int maxTargetCount() { return getConfig().getInt("max-target-count", 64); }
    double broadcastBelow() { return getConfig().getDouble("broadcast-below", 0.10); }

    double chance(double wager, double target) {
        double c = (1 - houseEdge()) * wager / target;
        return Math.max(getConfig().getDouble("min-chance", 0.001), Math.min(getConfig().getDouble("max-chance", 0.9), c));
    }

    boolean matches(Material m, String q) {
        if (m.getKey().getKey().replace('_', ' ').contains(q) || m.getKey().getKey().contains(q)) return true;
        String ru = ruNames.get(m.translationKey());
        return ru != null && ru.contains(q);
    }

    void log(String line) {
        getLogger().info(line);
        try (Writer w = new OutputStreamWriter(new FileOutputStream(new File(getDataFolder(), "rolls.log"), true), StandardCharsets.UTF_8)) {
            w.write(LocalDateTime.now() + " " + line + "\n");
        } catch (IOException ignored) { }
    }

    void forget(UpgraderGui g) { sessions.remove(g.player.getUniqueId(), g); }

    // ------------------------------------------------------------------ item
    ItemStack upgraderItem() {
        ItemStack it = new ItemStack(Material.KNOWLEDGE_BOOK);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text("Апгрейдер", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false).decoration(TextDecoration.BOLD, true));
        m.lore(List.of(
                Component.text("ПКМ — открыть колесо апгрейда", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Рискни предметом ради лучшего", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
        m.setItemModel(new NamespacedKey("upgrader", "icon"));
        m.setMaxStackSize(1);
        m.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    boolean isUpgraderItem(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return false;
        return it.getItemMeta().getPersistentDataContainer().has(itemKey, PersistentDataType.BYTE);
    }

    void openFor(Player p) {
        UpgraderGui g = sessions.get(p.getUniqueId());
        if (g != null && g.spinning) { g.open(); return; }
        g = new UpgraderGui(this, p);
        sessions.put(p.getUniqueId(), g);
        g.open();
        p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_ENDER_CHEST_OPEN, 0.5f, 1.4f);
    }

    // ------------------------------------------------------------------ events
    @EventHandler public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        p.discoverRecipe(recipeKey);
        String url = getConfig().getString("resource-pack.url", "");
        if (!url.isEmpty() && packHash != null) {
            Component prompt = Component.text("Ресурспак VALQAUYM нужен для апгрейдера и меню сервера", NamedTextColor.GOLD);
            p.setResourcePack(packId, url, packHash, prompt, getConfig().getBoolean("resource-pack.required", true));
        }
    }

    @EventHandler(priority = EventPriority.HIGH) public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (!isUpgraderItem(e.getItem())) return;
        // The item is a knowledge book: vanilla would consume it on use. Block that from either hand.
        e.setCancelled(true);
        e.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        if (e.getHand() == EquipmentSlot.OFF_HAND && isUpgraderItem(e.getPlayer().getInventory().getItemInMainHand())) return;
        openFor(e.getPlayer());
    }

    @EventHandler public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (top.getHolder() instanceof UpgraderGui g) {
            e.setCancelled(true);
            if (e.getClickedInventory() == null) return;
            if (e.getClickedInventory() == top) {
                int s = e.getRawSlot();
                if (s == UpgraderGui.WAGER) g.takeWager();
                else if (s == UpgraderGui.TARGET) {
                    if (g.spinning) return;
                    CatalogGui c = new CatalogGui(this, g);
                    g.switching = true;
                    e.getWhoClicked().openInventory(c.getInventory());
                } else if (s == UpgraderGui.MINUS) g.changeAmount(e.isShiftClick() ? -10 : -1);
                else if (s == UpgraderGui.PLUS) g.changeAmount(e.isShiftClick() ? 10 : 1);
                else if (Arrays.stream(UpgraderGui.SPIN).anyMatch(x -> x == s)) g.spin();
                else for (int i = 0; i < UpgraderGui.PRESETS.length; i++) if (UpgraderGui.PRESETS[i] == s) g.preset(i);
            } else if (e.getClickedInventory() == e.getWhoClicked().getInventory()) {
                g.clickPlayerItem(e.getSlot(), e.getCurrentItem());
            }
        } else if (top.getHolder() instanceof CatalogGui c) {
            e.setCancelled(true);
            if (e.getClickedInventory() == top) c.click(e.getRawSlot(), e.isRightClick());
        }
    }

    @EventHandler public void onDrag(InventoryDragEvent e) {
        Object h = e.getView().getTopInventory().getHolder();
        if (h instanceof UpgraderGui || h instanceof CatalogGui) e.setCancelled(true);
    }

    @EventHandler public void onClose(InventoryCloseEvent e) {
        Object h = e.getInventory().getHolder();
        if (h instanceof UpgraderGui g) {
            if (g.switching) { g.switching = false; return; }
            g.onClose();
        } else if (h instanceof CatalogGui c) {
            if (c.reopeningMain) { c.reopeningMain = false; return; }
            if (searching.get(e.getPlayer().getUniqueId()) == c) return;
            c.gui.onClose();
        }
    }

    @EventHandler public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        searching.remove(id);
        UpgraderGui g = sessions.get(id);
        if (g != null) {
            g.closed = true;
            g.forceFinish();
            g.onClose();
            sessions.remove(id);
        }
    }

    void beginSearch(CatalogGui c) {
        Player p = c.gui.player;
        searching.put(p.getUniqueId(), c);
        p.closeInventory();
        p.sendMessage(Component.text("Напиши название предмета в чат (или «отмена»):", NamedTextColor.GOLD));
    }

    @EventHandler(priority = EventPriority.LOWEST) public void onChat(AsyncChatEvent e) {
        CatalogGui c = searching.remove(e.getPlayer().getUniqueId());
        if (c == null) return;
        e.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        Bukkit.getScheduler().runTask(this, () -> {
            if (!e.getPlayer().isOnline()) return;
            if (!text.equalsIgnoreCase("отмена")) c.query = text.isEmpty() ? null : text.toLowerCase(Locale.ROOT);
            c.rebuild();
            c.gui.switching = false;
            e.getPlayer().openInventory(c.getInventory());
        });
    }

    // ------------------------------------------------------------------ command
    @Override public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(Component.text("Апгрейдер крафтится: 4 золота, 4 алмаза и наковальня в центре.", NamedTextColor.GOLD));
            return true;
        }
        if (!sender.hasPermission("upgrader.admin")) {
            sender.sendMessage(Component.text("Нет прав.", NamedTextColor.RED));
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "give" -> {
                Player t = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : (sender instanceof Player p ? p : null);
                if (t == null) { sender.sendMessage("Игрок не найден"); return true; }
                t.getInventory().addItem(upgraderItem());
                sender.sendMessage(Component.text("Выдан апгрейдер: " + t.getName(), NamedTextColor.GREEN));
            }
            case "open" -> { if (sender instanceof Player p) openFor(p); }
            case "reload" -> { loadAll(); registerRecipe(); sender.sendMessage(Component.text("Перезагружено. Предметов: " + values.targets().size(), NamedTextColor.GREEN)); }
            case "value" -> {
                if (sender instanceof Player p) {
                    ItemStack h = p.getInventory().getItemInMainHand();
                    sender.sendMessage("Ценность: " + UpgraderGui.fmt(values.wagerValue(h, this)) + " (за 1 шт. материала: " + UpgraderGui.fmt(values.value(h.getType())) + ")");
                }
            }
            case "dump" -> {
                File f = new File(getDataFolder(), "values.csv");
                try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
                    for (Material m : values.targets()) w.write(m.getKey().getKey() + "," + values.value(m) + "\n");
                    sender.sendMessage("Записано " + values.targets().size() + " цен в " + f.getPath());
                } catch (IOException ex) { sender.sendMessage("Ошибка: " + ex.getMessage()); }
            }
            default -> sender.sendMessage("/upgrader give [игрок] | open | reload | value | dump");
        }
        return true;
    }

    @Override public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (a.length == 1 && s.hasPermission("upgrader.admin")) return List.of("give", "open", "reload", "value");
        return null;
    }
}
