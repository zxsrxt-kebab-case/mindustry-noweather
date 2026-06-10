package noweather;

import arc.Core;
import arc.input.KeyCode;
import arc.math.geom.Geometry;
import arc.math.geom.Point2;
import arc.math.geom.Vec2;
import arc.struct.IntMap;
import arc.struct.IntSet;
import arc.struct.Seq;
import arc.util.Strings;
import mindustry.Vars;
import mindustry.entities.units.BuildPlan;
import mindustry.type.Item;
import mindustry.world.Block;
import mindustry.world.Build;
import mindustry.world.Tile;
import mindustry.world.blocks.distribution.ArmoredConveyor;
import mindustry.world.blocks.distribution.Conveyor;
import mindustry.world.blocks.distribution.ItemBridge;
import mindustry.world.blocks.distribution.StackConveyor;
import mindustry.world.blocks.production.BurstDrill;
import mindustry.world.blocks.production.Drill;

/**
 * Select a drill in the build menu, hover an ore tile and press H — the whole ore patch
 * gets covered with build plans placed greedily for maximum ore coverage.
 */
public class AutoDrill {
    private static final int MAX_PATCH = 2000, MAX_PLANS = 256, MAX_CONVEYORS = 512;
    /** Quarry half-size for floor-drop resources like sand: covers a (2r+1)² area around the cursor. */
    private static final int FLOOR_RADIUS = 7;

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

        // Ores are overlays with bounded patches; floor drops (sand, dark sand) cover huge
        // areas, so those get clamped to a small quarry around the cursor instead.
        boolean floorDrop = start.overlay().itemDrop == null;
        int radius = floorDrop ? FLOOR_RADIUS : Integer.MAX_VALUE;

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
                if (n != null && n.drop() == item
                    && Math.abs(n.x - start.x) <= radius && Math.abs(n.y - start.y) <= radius
                    && patch.add(n.pos())) {
                    frontier.add(n);
                }
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

    /**
     * Place drills in bands of {@code size} rows/columns separated by one-tile conveyor
     * lanes all flowing in {@code rot} (0=right, 1=up, 2=left, 3=down). Every drill
     * touches a lane, so ore is carried out of the field automatically.
     */
    static void placeWithConveyors(Drill drill, Block conveyor, Tile start, int rot) {
        Item item = start.drop();
        if (item == null || drill.tier < item.hardness || Vars.player.unit() == null) return;

        boolean floorDrop = start.overlay().itemDrop == null;
        int radius = floorDrop ? FLOOR_RADIUS : Integer.MAX_VALUE;

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
                if (n != null && n.drop() == item
                    && Math.abs(n.x - start.x) <= radius && Math.abs(n.y - start.y) <= radius
                    && patch.add(n.pos())) {
                    frontier.add(n);
                }
            }
        }

        boolean horizontal = rot == 0 || rot == 2;
        int s = drill.size;
        int off = drill.sizeOffset;
        int period = s + 1;
        int base = horizontal ? minY : minX;

        // candidates restricted to drill bands: footprint start must align to the band grid
        Seq<int[]> candidates = new Seq<>();
        for (int x = minX - s + 1; x <= maxX + s - 1; x++) {
            for (int y = minY - s + 1; y <= maxY + s - 1; y++) {
                int foot = (horizontal ? y : x) + off;
                if (((foot - base) % period + period) % period != 0) continue;
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
        IntMap<int[]> bandSpans = new IntMap<>(); // band index -> {min, max} along the lane axis
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

            int foot = (horizontal ? c[1] : c[0]) + off;
            int along = (horizontal ? c[0] : c[1]) + off;
            int band = (foot - base) / period;
            int[] span = bandSpans.get(band);
            if (span == null) bandSpans.put(band, new int[]{along, along + s - 1});
            else {
                span[0] = Math.min(span[0], along);
                span[1] = Math.max(span[1], along + s - 1);
            }
        }

        if (placed == 0) {
            toast(Core.bundle.get("nv.autodrill.none", "No valid spots for drills"));
            return;
        }

        // Stack conveyors (plastanium) only load at the tail of a line, so drills can't feed
        // them directly: lanes are built from a basic conveyor instead, merged by a collector
        // into the loading tail of a stack-conveyor trunk.
        boolean stack = conveyor instanceof StackConveyor;
        Block laneBlock = stack ? basicLaneBlock() : conveyor;
        ItemBridge bridge = findBridge();
        boolean forward = rot == 0 || rot == 1;

        // one conveyor lane above each drill band; the band above feeds the same lane
        Seq<int[]> lanes = new Seq<>(); // {lanePos, lo, hi}
        for (IntMap.Entry<int[]> e : bandSpans) {
            int lanePos = base + e.key * period + s;
            int lo = e.value[0], hi = e.value[1];
            int[] above = bandSpans.get(e.key + 1);
            if (above != null) {
                lo = Math.min(lo, above[0]);
                hi = Math.max(hi, above[1]);
            }
            lanes.add(new int[]{lanePos, lo, hi});
        }
        int farMax = Integer.MIN_VALUE, farMin = Integer.MAX_VALUE;
        int laneMin = Integer.MAX_VALUE, laneMax = Integer.MIN_VALUE;
        for (int[] l : lanes) {
            farMax = Math.max(farMax, l[2]);
            farMin = Math.min(farMin, l[1]);
            laneMin = Math.min(laneMin, l[0]);
            laneMax = Math.max(laneMax, l[0]);
        }
        // all lanes finish on the same exit row/column, sticking out of the field
        int exitA = forward ? farMax + 2 : farMin - 2;

        int conveyors = 0;
        for (int[] l : lanes) {
            if (forward) l[2] = exitA; else l[1] = exitA;
            conveyors += placeLane(laneBlock, bridge, rot, horizontal, l[0], l[1], l[2], occupied);
            if (conveyors >= MAX_CONVEYORS) break;
        }

        if (stack && !lanes.isEmpty()) {
            // perpendicular collector merging every lane toward the min-side corner...
            int collA = forward ? exitA + 1 : exitA - 1;
            int collRot = horizontal ? 3 : 2; // down for horizontal lanes, left for vertical
            for (int p = laneMax; p >= laneMin; p--) {
                int cx = horizontal ? collA : p;
                int cy = horizontal ? p : collA;
                int r = p == laneMin ? rot : collRot;
                if (Build.validPlace(laneBlock, Vars.player.team(), cx, cy, r)) {
                    Vars.player.unit().addBuild(new BuildPlan(cx, cy, r, laneBlock));
                    conveyors++;
                }
            }
            // ...which feeds the loading tail of the stack-conveyor trunk
            int step = forward ? 1 : -1;
            for (int i = 1; i <= 6; i++) {
                int a = collA + step * i;
                int cx = horizontal ? a : laneMin;
                int cy = horizontal ? laneMin : a;
                if (Build.validPlace(conveyor, Vars.player.team(), cx, cy, rot)) {
                    Vars.player.unit().addBuild(new BuildPlan(cx, cy, rot, conveyor));
                    conveyors++;
                }
            }
        }

        float perSecond = covered * 60f / drill.getDrillTime(item);
        toast(Core.bundle.format("nv.autodrill.done2", placed, conveyors, Strings.fixed(perSecond, 1)));
    }

    /** Lay one lane in flow order, bridging over obstacles when a bridge can span the gap. */
    private static int placeLane(Block laneBlock, ItemBridge bridge, int rot, boolean horizontal,
                                 int lanePos, int lo, int hi, IntSet occupied) {
        int step = (rot == 0 || rot == 1) ? 1 : -1;
        int begin = step > 0 ? lo : hi;
        int end = step > 0 ? hi : lo;
        Seq<BuildPlan> plans = new Seq<>();
        BuildPlan last = null;
        int lastA = 0;
        boolean gap = false;
        for (int a = begin; ; a += step) {
            int cx = horizontal ? a : lanePos;
            int cy = horizontal ? lanePos : a;
            if (!occupied.contains(Point2.pack(cx, cy))
                && Build.validPlace(laneBlock, Vars.player.team(), cx, cy, rot)) {
                BuildPlan plan;
                if (gap && last != null && bridge != null && Math.abs(a - lastA) <= bridge.range) {
                    // convert the plan before the gap into the entry bridge linked to the exit
                    last.block = bridge;
                    last.config = new Point2(cx - last.x, cy - last.y);
                    plan = new BuildPlan(cx, cy, rot, bridge);
                } else {
                    plan = new BuildPlan(cx, cy, rot, laneBlock);
                }
                gap = false;
                plans.add(plan);
                last = plan;
                lastA = a;
            } else {
                gap = last != null;
            }
            if (a == end) break;
        }
        for (BuildPlan p : plans) Vars.player.unit().addBuild(p);
        return plans.size;
    }

    private static Block basicLaneBlock() {
        Block named = Vars.content.block("titanium-conveyor");
        if (named instanceof Conveyor && !(named instanceof ArmoredConveyor)) return named;
        for (Block b : Vars.content.blocks()) {
            if (b instanceof Conveyor && !(b instanceof ArmoredConveyor)) return b;
        }
        return named;
    }

    private static ItemBridge findBridge() {
        if (Vars.content.block("bridge-conveyor") instanceof ItemBridge ib) return ib;
        for (Block b : Vars.content.blocks()) {
            if (b instanceof ItemBridge ib) return ib;
        }
        return null;
    }

    private static void toast(String text) {
        Vars.ui.showInfoToast(text, 3f);
    }
}
