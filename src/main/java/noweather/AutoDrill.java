package noweather;

import arc.Core;
import arc.input.KeyCode;
import arc.math.geom.Geometry;
import arc.math.geom.Point2;
import arc.math.geom.Vec2;
import arc.struct.IntSet;
import arc.struct.Seq;
import arc.util.Strings;
import mindustry.Vars;
import mindustry.entities.units.BuildPlan;
import mindustry.type.Item;
import mindustry.world.Build;
import mindustry.world.Tile;
import mindustry.world.blocks.production.BurstDrill;
import mindustry.world.blocks.production.Drill;

/**
 * Select a drill in the build menu, hover an ore tile and press H — the whole ore patch
 * gets covered with build plans placed greedily for maximum ore coverage.
 */
public class AutoDrill {
    private static final int MAX_PATCH = 2000, MAX_PLANS = 256;

    static void update() {
        if (Vars.mobile || !Vars.state.isGame() || !Core.settings.getBool("nv-autodrill", true)) return;
        if (Core.scene.hasKeyboard() || !Core.input.keyTap(KeyCode.h)) return;
        if (!(Vars.control.input.block instanceof Drill drill) || drill instanceof BurstDrill) return;
        if (Vars.player.unit() == null) return;

        Vec2 mouse = Core.input.mouseWorld();
        Tile start = Vars.world.tileWorld(mouse.x, mouse.y);
        Item item = start == null ? null : start.drop();
        if (item == null) {
            toast(Core.bundle.get("nv.autodrill.noore", "No ore under cursor"));
            return;
        }
        if (drill.tier < item.hardness) {
            toast(Core.bundle.format("nv.autodrill.tier", item.localizedName));
            return;
        }

        // flood-fill the connected ore patch
        IntSet patch = new IntSet();
        Seq<Tile> frontier = new Seq<>();
        frontier.add(start);
        patch.add(start.pos());
        int minX = start.x, maxX = start.x, minY = start.y, maxY = start.y;
        int patchSize = 0;
        while (!frontier.isEmpty() && patchSize < MAX_PATCH) {
            Tile t = frontier.pop();
            patchSize++;
            minX = Math.min(minX, t.x);
            maxX = Math.max(maxX, t.x);
            minY = Math.min(minY, t.y);
            maxY = Math.max(maxY, t.y);
            for (Point2 d : Geometry.d4) {
                Tile n = Vars.world.tile(t.x + d.x, t.y + d.y);
                if (n != null && n.drop() == item && patch.add(n.pos())) frontier.add(n);
            }
        }

        int s = drill.size;
        int off = drill.sizeOffset;

        // candidate anchors scored by how many patch tiles the footprint covers
        Seq<int[]> candidates = new Seq<>();
        for (int x = minX - s + 1; x <= maxX + s - 1; x++) {
            for (int y = minY - s + 1; y <= maxY + s - 1; y++) {
                int count = 0;
                for (int dx = 0; dx < s; dx++) {
                    for (int dy = 0; dy < s; dy++) {
                        Tile t = Vars.world.tile(x + off + dx, y + off + dy);
                        if (t != null && patch.contains(t.pos())) count++;
                    }
                }
                if (count > 0) candidates.add(new int[]{x, y, count});
            }
        }
        candidates.sort(c -> -c[2]);

        IntSet occupied = new IntSet();
        int placed = 0, covered = 0;
        for (int[] c : candidates) {
            if (placed >= MAX_PLANS) break;
            boolean free = true;
            for (int dx = 0; dx < s && free; dx++) {
                for (int dy = 0; dy < s && free; dy++) {
                    if (occupied.contains(Point2.pack(c[0] + off + dx, c[1] + off + dy))) free = false;
                }
            }
            if (!free || !Build.validPlace(drill, Vars.player.team(), c[0], c[1], 0)) continue;
            for (int dx = 0; dx < s; dx++) {
                for (int dy = 0; dy < s; dy++) {
                    occupied.add(Point2.pack(c[0] + off + dx, c[1] + off + dy));
                }
            }
            Vars.player.unit().addBuild(new BuildPlan(c[0], c[1], 0, drill));
            placed++;
            covered += c[2];
        }

        if (placed == 0) {
            toast(Core.bundle.get("nv.autodrill.none", "No valid spots"));
        } else {
            float perSecond = covered * 60f / drill.getDrillTime(item);
            toast(Core.bundle.format("nv.autodrill.done", placed, Strings.fixed(perSecond, 1)));
        }
    }

    private static void toast(String text) {
        Vars.ui.showInfoToast(text, 3f);
    }
}
