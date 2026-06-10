package noweather;

import arc.Core;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.Interval;
import arc.util.Strings;
import mindustry.Vars;
import mindustry.game.SpawnGroup;
import mindustry.type.Item;
import mindustry.type.UnitType;
import mindustry.ui.Styles;

import java.util.LinkedHashMap;
import java.util.Map;

/** HUD panels under the minimap: next wave composition and core resource rates. */
public class HudPanels {
    private static int builtWave = -1;
    private static final Interval timer = new Interval();
    private static final Seq<int[]> samples = new Seq<>();

    static void reset() {
        builtWave = -1;
        samples.clear();
    }

    static void build() {
        Vars.ui.hudGroup.fill(parent -> {
            parent.name = "nv-panels";
            parent.top().right();
            parent.marginTop(200f).marginRight(4f);

            Table wave = new Table(Styles.black3);
            wave.margin(6f);
            wave.visible(() -> on("nv-wavepreview") && Vars.state.isGame() && !Vars.state.rules.spawns.isEmpty());
            wave.update(() -> {
                if (builtWave != Vars.state.wave) {
                    builtWave = Vars.state.wave;
                    rebuildWave(wave);
                }
            });
            parent.add(wave).right().row();

            Table rates = new Table(Styles.black3);
            rates.margin(6f);
            rates.visible(() -> on("nv-corerates") && Vars.state.isGame() && Vars.player.team().core() != null);
            rates.update(() -> updateRates(rates));
            parent.add(rates).right().padTop(6f);
        });
    }

    private static void rebuildWave(Table t) {
        t.clearChildren();
        int waveIndex = Math.max(Vars.state.wave - 1, 0);
        t.add("[accent]" + Core.bundle.format("nv.wave", Vars.state.wave)).left().row();

        int ground = Math.max(Vars.spawner.countGroundSpawns(), 1);
        int flyers = Math.max(Vars.spawner.countFlyerSpawns(), 1);

        LinkedHashMap<UnitType, int[]> totals = new LinkedHashMap<>();
        for (SpawnGroup group : Vars.state.rules.spawns) {
            int amount = group.getSpawned(waveIndex);
            if (amount <= 0) continue;
            int mult = group.spawn != -1 ? 1 : group.type.flying ? flyers : ground;
            int[] entry = totals.computeIfAbsent(group.type, k -> new int[2]);
            entry[0] += amount * mult;
            entry[1] = Math.max(entry[1], Math.round(group.getShield(waveIndex)));
        }

        if (totals.isEmpty()) {
            t.add("[lightgray]—").left();
            return;
        }
        int rows = 0;
        for (Map.Entry<UnitType, int[]> e : totals.entrySet()) {
            if (++rows > 10) {
                t.add("[lightgray]+" + (totals.size() - 10) + "...").left();
                break;
            }
            Table row = new Table();
            row.image(e.getKey().uiIcon).size(20f).padRight(4f);
            String text = "×" + e.getValue()[0];
            if (e.getValue()[1] > 0) {
                text += " [royal]" + Core.bundle.format("nv.shield", e.getValue()[1]);
            }
            row.add(text).left();
            t.add(row).left().row();
        }
    }

    private static void updateRates(Table t) {
        if (!timer.get(60f)) return;
        var core = Vars.player.team().core();
        if (core == null) {
            samples.clear();
            return;
        }

        Seq<Item> items = Vars.content.items();
        int[] counts = new int[items.size];
        for (int i = 0; i < items.size; i++) counts[i] = core.items.get(items.get(i));

        samples.add(counts);
        if (samples.size > 6) samples.remove(0);
        if (samples.size < 2) return;

        int[] oldest = samples.first();
        float seconds = samples.size - 1;

        t.clearChildren();
        t.add("[accent]" + Core.bundle.get("nv.rates", "Core Δ/s")).left().row();
        boolean any = false;
        for (int i = 0; i < items.size; i++) {
            float rate = (counts[i] - oldest[i]) / seconds;
            if (Math.abs(rate) < 0.05f) continue;
            any = true;
            Table row = new Table();
            row.image(items.get(i).uiIcon).size(18f).padRight(4f);
            row.add((rate > 0 ? "[lime]+" : "[scarlet]") + Strings.fixed(rate, 1)).left();
            t.add(row).left().row();
        }
        if (!any) t.add("[lightgray]±0").left();
    }

    private static boolean on(String key) {
        return Core.settings.getBool(key, true);
    }
}
