package kz.valqauym.upgrader;

import io.papermc.paper.datacomponent.DataComponentTypes;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.ShulkerBox;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

/**
 * Prices every item by walking its recipes back to raw materials (like the Upgrader mod).
 * Uses an iterative relaxation (cheapest known way to make each item) so cycles and
 * huge tag choices never blow up: O(passes * recipes).
 */
public final class ItemValues {
    private static final double INF = Double.POSITIVE_INFINITY;

    /** A recipe reduced to: result material, result amount, list of ingredient alternatives (each with a count). */
    private record Flat(Material result, int amount, List<Ing> ings, double extra) { }
    private record Ing(List<Material> options, double count) { }

    private final Map<Material, Double> base = new EnumMap<>(Material.class);
    private final Map<Material, Double> val = new EnumMap<>(Material.class);
    private final Set<Material> blacklist = EnumSet.noneOf(Material.class);
    private final List<Material> targets = new ArrayList<>();
    private double enchantLevelValue;

    public void load(ConfigurationSection cfg) {
        base.clear(); val.clear(); blacklist.clear(); targets.clear();
        enchantLevelValue = cfg.getDouble("enchant-level-value", 25);
        ConfigurationSection b = cfg.getConfigurationSection("base-values");
        if (b != null) for (String k : b.getKeys(false)) {
            Material m = Material.matchMaterial(k);
            if (m != null) base.put(m, b.getDouble(k));
        }
        for (String k : cfg.getStringList("target-blacklist")) {
            Material m = Material.matchMaterial(k);
            if (m != null) blacklist.add(m);
        }

        List<Flat> flats = new ArrayList<>();
        Iterator<Recipe> it = Bukkit.recipeIterator();
        while (it.hasNext()) {
            Recipe r;
            try { r = it.next(); } catch (Throwable t) { continue; }
            if (r == null || r.getResult() == null || r.getResult().getType().isAir()) continue;
            Flat f = flatten(r);
            if (f != null && !base.containsKey(f.result)) flats.add(f);
        }

        // raw materials (nothing crafts them, not in base): seed with a rarity fallback
        Set<Material> producible = EnumSet.noneOf(Material.class);
        for (Flat f : flats) producible.add(f.result);
        Map<Material, Double> seed = new EnumMap<>(base);
        for (Material m : Material.values()) {
            if (m.isLegacy() || !m.isItem() || m.isAir() || seed.containsKey(m) || producible.contains(m)) continue;
            seed.put(m, fallback(m));
        }
        // pass 1, then derive values for items obtained by tools/water/weathering, then pass 2 from scratch
        relax(seed, flats);
        Map<Material, Double> derived = new EnumMap<>(Material.class);
        for (Material m : Material.values()) {
            if (base.containsKey(m) || producible.contains(m) || m.isLegacy()) continue;
            Material src = derivedSource(m);
            if (src != null && val.containsKey(src)) derived.put(m, val.get(src));
        }
        seed.putAll(derived);
        relax(seed, flats);

        for (Material m : Material.values()) {
            if (!isTargetable(m)) continue;
            if (value(m) > 0) targets.add(m);
        }
        targets.sort(Comparator.comparingDouble(this::value).thenComparing(Enum::name));
    }

    /** Items you get from another item without a recipe (axe, water, weathering). */
    private static Material derivedSource(Material m) {
        String n = m.name();
        String src = null;
        if (n.startsWith("STRIPPED_")) src = n.substring(9);
        else if (n.endsWith("_CONCRETE")) src = n + "_POWDER";
        else for (String pre : new String[]{"EXPOSED_", "WEATHERED_", "OXIDIZED_"}) {
            if (n.startsWith(pre)) { src = n.substring(pre.length()); break; }
        }
        if (src == null) return null;
        if (src.equals("COPPER")) src = "COPPER_BLOCK";
        return Material.getMaterial(src);
    }

    private void relax(Map<Material, Double> seed, List<Flat> flats) {
        val.clear();
        val.putAll(seed);
        for (int pass = 0; pass < 64; pass++) {
            boolean changed = false;
            for (Flat f : flats) {
                double cost = f.extra;
                for (Ing ing : f.ings) {
                    double best = INF;
                    for (Material m : ing.options) {
                        Double v = val.get(m);
                        if (v != null && v < best) best = v;
                    }
                    if (best == INF) { cost = INF; break; }
                    cost += best * ing.count;
                }
                if (cost == INF) continue;
                double unit = Math.round(cost / f.amount * 100.0) / 100.0;
                if (unit <= 0) unit = 0.01;
                Double cur = val.get(f.result);
                if (cur == null || unit < cur - 1e-9) { val.put(f.result, unit); changed = true; }
            }
            if (!changed) break;
        }
    }

    private Flat flatten(Recipe r) {
        Material res = r.getResult().getType();
        int amt = Math.max(1, r.getResult().getAmount());
        List<Ing> ings = new ArrayList<>();
        if (r instanceof ShapedRecipe s) {
            Map<Character, Integer> counts = new HashMap<>();
            for (String row : s.getShape()) for (char c : row.toCharArray()) if (c != ' ') counts.merge(c, 1, Integer::sum);
            for (Map.Entry<Character, RecipeChoice> e : s.getChoiceMap().entrySet()) {
                if (e.getValue() == null) continue;
                Ing i = ing(e.getValue(), counts.getOrDefault(e.getKey(), 0));
                if (i == null) return null;
                if (i.count > 0) ings.add(i);
            }
            return new Flat(res, amt, ings, 0);
        }
        if (r instanceof ShapelessRecipe s) {
            for (RecipeChoice c : s.getChoiceList()) { Ing i = ing(c, 1); if (i == null) return null; ings.add(i); }
            return new Flat(res, amt, ings, 0);
        }
        if (r instanceof CookingRecipe<?> c) {
            Ing i = ing(c.getInputChoice(), 1); if (i == null) return null;
            ings.add(i);
            return new Flat(res, amt, ings, 0.5);
        }
        if (r instanceof StonecuttingRecipe s) {
            Ing i = ing(s.getInputChoice(), 1); if (i == null) return null;
            ings.add(i);
            return new Flat(res, amt, ings, 0);
        }
        if (r instanceof SmithingTransformRecipe s) { // template is not counted (like the mod)
            Ing a = ing(s.getBase(), 1), b = ing(s.getAddition(), 1);
            if (a == null || b == null) return null;
            ings.add(a); ings.add(b);
            return new Flat(res, amt, ings, 0);
        }
        if (r instanceof TransmuteRecipe t) {
            Ing a = ing(t.getInput(), 1), b = ing(t.getMaterial(), 1);
            if (a == null || b == null) return null;
            ings.add(a); ings.add(b);
            return new Flat(res, amt, ings, 0);
        }
        return null;
    }

    private static Ing ing(RecipeChoice c, double count) {
        if (c == null) return new Ing(List.of(), 0);
        List<Material> opts = new ArrayList<>();
        if (c instanceof RecipeChoice.MaterialChoice mc) {
            for (Material m : mc.getChoices()) if (!m.isAir()) opts.add(m);
        } else if (c instanceof RecipeChoice.ExactChoice ec) {
            for (ItemStack s : ec.getChoices()) if (s.getAmount() == 1) opts.add(s.getType());
        } else return null;
        if (opts.isEmpty()) return null;
        return new Ing(opts, count);
    }

    public List<Material> targets() { return targets; }

    private boolean isTargetable(Material m) {
        if (m.isLegacy() || !m.isItem() || m.isAir() || blacklist.contains(m)) return false;
        String n = m.name();
        if (n.endsWith("_SPAWN_EGG") || n.contains("COMMAND_BLOCK") || n.startsWith("STRUCTURE_")) return false;
        try {
            if (!Bukkit.getWorlds().isEmpty() && !m.isEnabledByFeature(Bukkit.getWorlds().get(0))) return false;
        } catch (Throwable ignored) { }
        return true;
    }

    /** Value of one plain item of this material (no damage/enchant adjustments). */
    public double value(Material m) {
        Double v = val.get(m);
        if (v != null) return v;
        double f = fallback(m);
        val.put(m, f);
        return f;
    }

    private double fallback(Material m) {
        if (!m.isItem() || m.isAir()) return 0;
        try {
            ItemRarity rarity = ItemStack.of(m).getData(DataComponentTypes.RARITY);
            if (rarity != null) return switch (rarity) {
                case COMMON -> 4;
                case UNCOMMON -> 40;
                case RARE -> 250;
                case EPIC -> 1500;
            };
        } catch (Throwable ignored) { }
        return 4;
    }

    /** Total value of a wager stack, or -1 if it can't be wagered. */
    public double wagerValue(ItemStack it, UpgraderPlugin plugin) {
        if (it == null || it.getType().isAir() || plugin.isUpgraderItem(it)) return -1;
        ItemMeta meta = it.getItemMeta();
        if (meta instanceof BlockStateMeta bsm && bsm.hasBlockState() && bsm.getBlockState() instanceof ShulkerBox sb
                && Arrays.stream(sb.getInventory().getContents()).anyMatch(Objects::nonNull)) return -1;
        if (meta instanceof BundleMeta bm && bm.hasItems()) return -1;
        double v = value(it.getType());
        if (!(v > 0)) return -1;
        short max = it.getType().getMaxDurability();
        if (max > 0 && meta instanceof Damageable d && d.hasDamage()) {
            v *= Math.max(0, (max - d.getDamage())) / (double) max;
        }
        int levels = 0;
        for (int l : it.getEnchantments().values()) levels += l;
        if (meta instanceof EnchantmentStorageMeta esm) for (int l : esm.getStoredEnchants().values()) levels += l;
        v += levels * enchantLevelValue;
        return v * it.getAmount();
    }
}
