package kz.valqauym.upgrader;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/** One player's upgrader screen. Slot layout must match pack/gen_pack.py. */
public final class UpgraderGui implements InventoryHolder {
    static final int WAGER = 10, TARGET = 16, MINUS = 15, PLUS = 17, ARC = 13, PTR = 22, STATUS = 31;
    static final int[] SPIN = {36, 37, 38};
    static final int[] PRESETS = {39, 40, 41, 42, 43, 44};
    static final double[] PRESET_MULT = {2, 4, 8, 0, 0, 0};
    static final double[] PRESET_CHANCE = {0, 0, 0, 0.30, 0.50, 0.70};
    static final int[] CH_HOSTS = {3, 4, 5, 12, 14, 21};
    static final int[] VL_HOSTS = {18, 19, 20, 27, 28, 29};
    static final int[] VR_HOSTS = {24, 25, 26, 33, 34, 35};
    static final int FRAMES = 120;

    private final UpgraderPlugin plugin;
    final Player player;
    private final Inventory inv;

    ItemStack wager;            // real item held by the GUI (never a display copy)
    Material target;
    int amount = 1;
    int pointer = 0;
    boolean spinning;
    boolean closed;
    boolean switching;          // true while we swap to the catalog screen (ignore that close)
    private String arcState = "g";
    private String status = null;
    private BukkitTask task;

    UpgraderGui(UpgraderPlugin plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
        Component title = Component.text("").font(Key.key("upgrader", "gui")).color(NamedTextColor.WHITE);
        this.inv = Bukkit.createInventory(this, 45, title);
        render();
    }

    @Override public Inventory getInventory() { return inv; }

    void open() { closed = false; player.openInventory(inv); }

    // ------------------------------------------------------------------ values
    double wagerValue() { return wager == null ? 0 : plugin.values().wagerValue(wager, plugin); }

    double targetValue() { return target == null ? 0 : plugin.values().value(target) * amount; }

    /** Success chance in [min,max], or -1 if not ready. */
    double chance() {
        double w = wagerValue(), t = targetValue();
        if (w <= 0 || t <= 0) return -1;
        return plugin.chance(w, t, target);
    }

    // ------------------------------------------------------------------ render
    void render() {
        inv.clear();
        // wager slot
        if (wager != null) {
            ItemStack show = wager.clone();
            appendLore(show, List.of(
                    line("Ценность: ", fmt(wagerValue()), NamedTextColor.GOLD),
                    Component.text("Нажми, чтобы забрать", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
            inv.setItem(WAGER, show);
        } else {
            inv.setItem(WAGER, button("Ставка", NamedTextColor.GOLD,
                    "Кликни по предмету в своём инвентаре,", "чтобы поставить его сюда."));
        }
        // target slot
        if (target != null) {
            ItemStack show = new ItemStack(target, amount);
            appendLore(show, List.of(
                    line("Ценность: ", fmt(targetValue()), NamedTextColor.AQUA),
                    Component.text("Нажми, чтобы выбрать другой", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
            inv.setItem(TARGET, show);
        } else {
            inv.setItem(TARGET, button("Цель", NamedTextColor.AQUA, "Нажми, чтобы выбрать предмет,", "который хочешь получить."));
        }
        inv.setItem(MINUS, button("Количество −1", NamedTextColor.WHITE, "Shift: −10"));
        inv.setItem(PLUS, button("Количество +1", NamedTextColor.WHITE, "Shift: +10"));

        double ch = chance();
        for (int s : SPIN) inv.setItem(s, button("Прокрутить", NamedTextColor.GOLD,
                ch < 0 ? "Поставь предмет и выбери цель" : "Шанс: " + pct(ch)));
        String[] names = {"x2", "x4", "x8", "~30%", "~50%", "~70%"};
        for (int i = 0; i < PRESETS.length; i++) {
            String desc = PRESET_MULT[i] > 0 ? "Цель в " + (int) PRESET_MULT[i] + " раза дороже ставки"
                    : "Цель с шансом около " + (int) Math.round(PRESET_CHANCE[i] * 100) + "%";
            inv.setItem(PRESETS[i], button(names[i], NamedTextColor.YELLOW, desc));
        }

        // wheel
        int disp = ch < 0 ? 0 : displayPct(ch);
        inv.setItem(ARC, overlay("arc_" + arcState + "_" + disp));
        inv.setItem(PTR, overlay("ptr_" + pointer));
        if (ch >= 0) putText("ch", CH_HOSTS, chanceText(ch));
        if (wager != null) putText("vl", VL_HOSTS, valueText(wagerValue()));
        if (target != null) putText("vr", VR_HOSTS, valueText(targetValue()));
        if (status != null) inv.setItem(STATUS, overlay(status));
    }

    static int displayPct(double chance) {
        return (int) Math.max(1, Math.min(100, Math.ceil(chance * 100 - 1e-9)));
    }

    static String chanceText(double ch) {
        return String.format(Locale.ROOT, "%.2f%%", ch * 100);
    }

    static String valueText(double v) {
        long n = Math.min(999999, Math.round(v));
        return Long.toString(n);
    }

    private void putText(String prefix, int[] hosts, String s) {
        int len = Math.min(s.length(), hosts.length);
        int d = hosts.length - len;
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            String g = c == '.' ? "dot" : c == '%' ? "pct" : String.valueOf(c);
            inv.setItem(hosts[i], overlay(prefix + "_" + i + "_" + d + "_" + g));
        }
    }

    // ------------------------------------------------------------------ actions
    void clickPlayerItem(int slot, ItemStack clicked) {
        if (spinning || clicked == null || clicked.getType().isAir()) return;
        if (plugin.values().wagerValue(clicked, plugin) <= 0) {
            player.sendActionBar(Component.text("Этот предмет нельзя поставить", NamedTextColor.RED));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1.2f);
            return;
        }
        ItemStack old = wager;
        wager = clicked.clone();
        player.getInventory().setItem(slot, old);
        player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_GENERIC, 0.7f, 1.4f);
        resetResult();
        render();
    }

    void takeWager() {
        if (spinning || wager == null) return;
        give(wager);
        wager = null;
        resetResult();
        render();
    }

    void changeAmount(int delta) {
        if (spinning || target == null) return;
        int max = Math.max(1, Math.min(plugin.maxTargetCount(), target.getMaxStackSize()));
        amount = Math.max(1, Math.min(max, amount + delta));
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.6f);
        resetResult();
        render();
    }

    void setTarget(Material m, int amt) {
        target = m;
        int max = Math.max(1, Math.min(plugin.maxTargetCount(), m.getMaxStackSize()));
        amount = Math.max(1, Math.min(max, amt));
        resetResult();
        render();
    }

    void preset(int idx) {
        if (spinning) return;
        double w = wagerValue();
        if (w <= 0) {
            player.sendActionBar(Component.text("Сначала поставь предмет", NamedTextColor.RED));
            return;
        }
        double wanted = PRESET_MULT[idx] > 0 ? w * PRESET_MULT[idx] : (1 - plugin.houseEdge()) * w / PRESET_CHANCE[idx];
        Pick p = pick(wanted);
        if (p == null) {
            player.sendActionBar(Component.text("Не нашёл подходящий предмет", NamedTextColor.RED));
            return;
        }
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
        setTarget(p.m, p.amount);
    }

    record Pick(Material m, int amount, double err) { }

    /** Picks an item (and amount) whose total value is close to wanted; random among the closest few. */
    Pick pick(double wanted) {
        List<Pick> best = new ArrayList<>();
        for (Material m : plugin.values().targets()) {
            if (wager != null && m == wager.getType()) continue;
            double v = plugin.values().value(m);
            int max = Math.max(1, Math.min(plugin.maxTargetCount(), m.getMaxStackSize()));
            int a = (int) Math.max(1, Math.min(max, Math.round(wanted / v)));
            double err = Math.abs(Math.log(v * a / wanted)) + (a > 1 ? 0.01 * Math.log(a) : 0);
            best.add(new Pick(m, a, err));
        }
        best.sort((x, y) -> Double.compare(x.err, y.err));
        if (best.isEmpty() || best.get(0).err > 0.7) return null;
        double limit = Math.max(best.get(0).err * 1.5, 0.08);
        List<Pick> pool = best.stream().filter(p -> p.err <= limit).limit(8).toList();
        return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
    }

    void spin() {
        if (spinning) return;
        double ch = chance();
        if (ch < 0) {
            player.sendActionBar(Component.text("Поставь предмет и выбери цель", NamedTextColor.RED));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1.2f);
            return;
        }
        final ItemStack staked = wager;
        final Material tgt = target;
        final int amt = amount;
        final boolean win = ThreadLocalRandom.current().nextDouble() < ch;
        final double halfReal = ch * 180.0;
        final double halfDisp = displayPct(ch) * 1.8;
        double angle;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        if (win) {
            double lim = Math.max(0, halfReal - 1.5);
            angle = lim <= 0 ? 0 : r.nextDouble(-lim, lim);
        } else {
            double from = halfDisp + 3, to = 360 - halfDisp - 3;
            angle = from >= to ? 180 : r.nextDouble(from, to);
        }
        int finalFrame = Math.floorMod((int) Math.round(angle / 3.0), FRAMES);
        int start = pointer;
        int total = 3 * FRAMES + Math.floorMod(finalFrame - start, FRAMES);
        int ticks = plugin.spinTicks();

        spinning = true;
        rememberPending(staked, tgt, amt, win, ch);
        arcState = "g";
        status = "st_spin";
        plugin.log(player.getName() + " spins " + describe(staked) + " -> " + amt + "x" + tgt.getKey().getKey()
                + " chance=" + chanceText(ch) + " result=" + (win ? "WIN" : "LOSE"));
        render();
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 0.8f);

        task = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            int t = 0;
            int lastTick = 0;

            @Override public void run() {
                t++;
                double x = Math.min(1.0, t / (double) ticks);
                int pos = (int) Math.floor(total * (1 - Math.pow(1 - x, 3)));
                pointer = Math.floorMod(start + pos, FRAMES);
                inv.setItem(PTR, overlay("ptr_" + pointer));
                int notch = pos / 10;
                if (notch != lastTick && !closed) {
                    lastTick = notch;
                    float pitch = (float) (0.7 + 1.1 * x);
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.5f, pitch);
                }
                if (t >= ticks) {
                    task.cancel();
                    finish(staked, tgt, amt, win, ch);
                }
            }
        }, 1L, 1L);
    }

    /** Ends a running spin immediately (quit/shutdown). */
    void forceFinish() {
        if (!spinning || task == null) return;
        task.cancel();
        finishPending(); // outcome was already rolled in spin()
    }

    private ItemStack pendingStake;
    private Material pendingTarget;
    private int pendingAmount;
    private boolean pendingWin;
    private double pendingChance;

    private void finishPending() {
        finish(pendingStake, pendingTarget, pendingAmount, pendingWin, pendingChance);
    }

    private void finish(ItemStack staked, Material tgt, int amt, boolean win, double ch) {
        spinning = false;
        task = null;
        wager = null;
        if (win) {
            ItemStack prize = new ItemStack(tgt, amt);
            if (!closed && player.isOnline()) wager = prize; // can be upgraded again right away
            else give(prize);
            arcState = "w";
            status = "st_win";
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
            player.playSound(player.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 0.7f, 1.0f);
            player.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, player.getLocation().add(0, 1, 0), 40, 0.4, 0.6, 0.4, 0.3);
            if (ch < plugin.broadcastBelow()) {
                Bukkit.broadcast(Component.text("✦ ", NamedTextColor.GOLD)
                        .append(Component.text(player.getName(), NamedTextColor.YELLOW))
                        .append(Component.text(" апгрейднул предмет в ", NamedTextColor.GRAY))
                        .append(Component.translatable(tgt.translationKey(), NamedTextColor.AQUA))
                        .append(Component.text(amt > 1 ? " x" + amt : "", NamedTextColor.AQUA))
                        .append(Component.text(" с шансом ", NamedTextColor.GRAY))
                        .append(Component.text(chanceText(ch), NamedTextColor.GOLD))
                        .append(Component.text("!", NamedTextColor.GRAY)));
            }
        } else {
            arcState = "l";
            status = "st_lose";
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 0.8f, 0.8f);
            player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.3f, 1.6f);
        }
        if (!closed) render();
        else plugin.forget(this);
        // keep the coloured result for a moment, then back to idle colours
        if (!closed) Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!spinning && !closed) { resetResult(); render(); }
        }, 50L);
    }

    void resetResult() {
        if (spinning) return;
        arcState = "g";
        status = null;
    }

    void onClose() {
        closed = true;
        if (!spinning) {
            if (wager != null) give(wager);
            wager = null;
            plugin.forget(this);
        }
    }

    void give(ItemStack it) {
        if (it == null || it.getType().isAir()) return;
        if (player.isDead()) { // inventory is about to be dropped/cleared: drop it on the ground instead
            player.getWorld().dropItemNaturally(player.getLocation(), it);
            return;
        }
        player.getInventory().addItem(it).values().forEach(l -> player.getWorld().dropItemNaturally(player.getLocation(), l));
    }

    // remember outcome so quit/shutdown can resolve instantly
    void rememberPending(ItemStack s, Material t, int a, boolean w, double c) {
        pendingStake = s; pendingTarget = t; pendingAmount = a; pendingWin = w; pendingChance = c;
    }

    // ------------------------------------------------------------------ item helpers
    static ItemStack overlay(String model) {
        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta m = it.getItemMeta();
        m.setItemModel(new NamespacedKey("upgrader", model));
        m.setHideTooltip(true);
        it.setItemMeta(m);
        return it;
    }

    static ItemStack button(String name, TextColor color, String... lore) {
        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta m = it.getItemMeta();
        m.setItemModel(new NamespacedKey("upgrader", "blank"));
        m.displayName(Component.text(name, color).decoration(TextDecoration.ITALIC, false).decoration(TextDecoration.BOLD, true));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(Component.text(s, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        m.lore(l);
        it.setItemMeta(m);
        return it;
    }

    static Component line(String label, String value, TextColor color) {
        return Component.text(label, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                .append(Component.text(value, color));
    }

    static void appendLore(ItemStack it, List<Component> extra) {
        ItemMeta m = it.getItemMeta();
        if (m == null) return;
        List<Component> l = m.lore() == null ? new ArrayList<>() : new ArrayList<>(m.lore());
        l.add(Component.empty());
        l.addAll(extra);
        m.lore(l);
        it.setItemMeta(m);
    }

    static String fmt(double v) {
        return v >= 100 ? Long.toString(Math.round(v)) : String.format(Locale.ROOT, "%.1f", v);
    }

    static String pct(double c) { return chanceText(c); }

    static String describe(ItemStack s) {
        return s == null ? "null" : s.getAmount() + "x" + s.getType().getKey().getKey();
    }
}
