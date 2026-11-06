package kz.valqauym.upgrader;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Target picker: every item, best odds first, with ~70/50/30/10% quick filters and search. */
public final class CatalogGui implements InventoryHolder {
    static final int PREV = 45, F70 = 46, F50 = 47, F30 = 48, F10 = 49, SEARCH = 50, BACK = 51, RESET = 52, NEXT = 53;
    private static final double[] FILTERS = {0.70, 0.50, 0.30, 0.10};

    record Entry(Material m, int amount, double chance) { }

    private final UpgraderPlugin plugin;
    final UpgraderGui gui;
    private final Inventory inv;
    private int page;
    double filter = -1;
    String query;
    private List<Entry> entries = List.of();
    boolean reopeningMain;

    CatalogGui(UpgraderPlugin plugin, UpgraderGui gui) {
        this.plugin = plugin;
        this.gui = gui;
        this.inv = Bukkit.createInventory(this, 54, Component.text("Выбор цели", NamedTextColor.DARK_GRAY));
        rebuild();
    }

    @Override public Inventory getInventory() { return inv; }

    void rebuild() {
        double w = gui.wagerValue();
        List<Entry> list = new ArrayList<>();
        String q = query == null ? null : query.toLowerCase(Locale.ROOT).trim();
        for (Material m : plugin.values().targets()) {
            if (q != null && !q.isEmpty() && !plugin.matches(m, q)) continue;
            double v = plugin.values().value(m);
            int max = Math.max(1, Math.min(plugin.maxTargetCount(), m.getMaxStackSize()));
            int a = 1;
            if (filter > 0 && w > 0) {
                a = (int) Math.max(1, Math.min(max, Math.round((1 - plugin.houseEdge()) * w / filter / v)));
                double c = plugin.chance(w, v * a);
                if (Math.abs(c - filter) > Math.max(0.05, filter * 0.2)) continue;
            }
            double c = w > 0 ? plugin.chance(w, v * a) : -1;
            list.add(new Entry(m, a, c));
        }
        if (filter > 0) list.sort((x, y) -> Double.compare(Math.abs(x.chance - filter), Math.abs(y.chance - filter)));
        // otherwise targets() is already sorted by value ascending = best odds first
        entries = list;
        page = Math.max(0, Math.min(page, pages() - 1));
        render();
    }

    int pages() { return Math.max(1, (entries.size() + 44) / 45); }

    void render() {
        inv.clear();
        int from = page * 45;
        for (int i = 0; i < 45 && from + i < entries.size(); i++) {
            Entry e = entries.get(from + i);
            ItemStack it = new ItemStack(e.m, e.amount);
            List<Component> lore = new ArrayList<>();
            lore.add(UpgraderGui.line("Ценность: ", UpgraderGui.fmt(plugin.values().value(e.m) * e.amount), NamedTextColor.AQUA));
            if (e.chance >= 0) lore.add(UpgraderGui.line("Шанс: ", UpgraderGui.pct(e.chance), NamedTextColor.GOLD));
            lore.add(Component.text("ЛКМ — выбрать", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
            ItemMeta m = it.getItemMeta();
            if (m != null) { m.lore(lore); it.setItemMeta(m); }
            inv.setItem(i, it);
        }
        inv.setItem(PREV, nav(Material.ARROW, "← Назад", "Страница " + (page + 1) + " / " + pages()));
        inv.setItem(NEXT, nav(Material.ARROW, "Вперёд →", "Страница " + (page + 1) + " / " + pages()));
        Material[] dyes = {Material.LIME_DYE, Material.YELLOW_DYE, Material.ORANGE_DYE, Material.RED_DYE};
        int[] slots = {F70, F50, F30, F10};
        for (int i = 0; i < 4; i++) {
            boolean on = Math.abs(filter - FILTERS[i]) < 1e-9;
            ItemStack b = nav(dyes[i], (on ? "▶ " : "") + "~" + (int) (FILTERS[i] * 100) + "%",
                    "Показать предметы с шансом", "около " + (int) (FILTERS[i] * 100) + "% (кол-во подберётся)");
            if (on) { ItemMeta m = b.getItemMeta(); m.setEnchantmentGlintOverride(true); b.setItemMeta(m); }
            inv.setItem(slots[i], b);
        }
        inv.setItem(SEARCH, nav(Material.OAK_SIGN, "Поиск",
                query == null ? "Нажми и напиши название в чат" : "Сейчас: " + query));
        inv.setItem(RESET, nav(Material.BARRIER, "Сбросить фильтры", "Показать все предметы"));
        inv.setItem(BACK, nav(Material.SPYGLASS, "Назад к апгрейдеру", ""));
    }

    void click(int slot, boolean right) {
        if (slot < 45) {
            int idx = page * 45 + slot;
            if (idx >= entries.size()) return;
            Entry e = entries.get(idx);
            gui.setTarget(e.m, e.amount);
            gui.player.playSound(gui.player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.3f);
            backToMain();
            return;
        }
        switch (slot) {
            case PREV -> { if (page > 0) { page--; render(); } }
            case NEXT -> { if (page < pages() - 1) { page++; render(); } }
            case F70, F50, F30, F10 -> {
                double f = FILTERS[slot - F70];
                if (gui.wagerValue() <= 0) {
                    gui.player.sendActionBar(Component.text("Сначала поставь предмет", NamedTextColor.RED));
                    return;
                }
                filter = Math.abs(filter - f) < 1e-9 ? -1 : f;
                page = 0;
                rebuild();
            }
            case RESET -> { filter = -1; query = null; page = 0; rebuild(); }
            case SEARCH -> plugin.beginSearch(this);
            case BACK -> backToMain();
            default -> { }
        }
    }

    void backToMain() {
        reopeningMain = true;
        gui.open();
    }

    private static ItemStack nav(Material mat, String name, String... lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text(name, NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        List<Component> l = new ArrayList<>();
        for (String s : lore) if (!s.isEmpty()) l.add(Component.text(s, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        m.lore(l);
        it.setItemMeta(m);
        return it;
    }
}
