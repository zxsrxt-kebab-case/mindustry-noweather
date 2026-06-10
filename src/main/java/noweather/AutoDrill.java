package noweather;

import arc.Core;
import arc.input.KeyCode;
import arc.math.geom.Geometry;
import arc.math.geom.Point2;
import arc.math.geom.Vec2;
import arc.struct.IntMap;
import arc.struct.IntSeq;
import arc.struct.IntSet;
import arc.struct.Seq;
import arc.util.Strings;
import mindustry.Vars;
import mindustry.entities.units.BuildPlan;
import mindustry.type.Item;
import mindustry.world.Block;
import mindustry.world.Build;
import mindustry.world.Tile;
import mindustry.world.blocks.distribution.ItemBridge;
import mindustry.world.blocks.distribution.StackConveyor;
import mindustry.world.blocks.production.BurstDrill;
import mindustry.world.blocks.production.Drill;

public class AutoDrill {
    private static final int MAX_PATCH = 2000, MAX_PLANS = 256, MAX_BRIDGES = 600;
    /** Quarry half-size for floor-drop resources like sand: covers a (2r+1)² area around the cursor. */
    private static final int FLOOR_RADIUS = 7;

    /** H hotkey: dense fill of the hovered patch, no transport. */
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

        Patch p = flood(start, item);
        Seq<int[]> chosen = denseDrills(p, drill);
        if (chosen.isEmpty()) {
            toast(Core.bundle.get("nv.autodrill.none", "No valid spots for drills"));
            return;
        }
        int covered = 0;
        for (int[] c : chosen) {
            Vars.player.unit().addBuild(new BuildPlan(c[0], c[1], 0, drill));
            covered += c[2];
        }
        float perSecond = covered * 60f / drill.getDrillTime(item);
        toast(Core.bundle.format("nv.autodrill.done", chosen.size, Strings.fixed(perSecond, 1)));
    }

    /**
     * Drills in bands separated by one-tile channels of chained item bridges. Bridge endpoints
     * accept ore from the drills next to them and relay it along the channel; a perpendicular
     * bridge collector merges all channels into a single output line of the chosen conveyor
     * (or a stack-conveyor trunk loaded at its tail, since those only accept at the tail).
     */
    static void placeWithConveyors(Drill drill, Block conveyor, Tile start, int rot) {
        Item item = start.drop();
        if (item == null || drill.tier < item.hardness || Vars.player.unit() == null) return;
        ItemBridge bridge = findBridge();
        if (bridge == null) {
            toast(Core.bundle.get("nv.autodrill.none", "No valid spots for drills"));
            return;
        }

        Patch p = flood(start, item);
        boolean horizontal = rot == 0 || rot == 2;
        boolean forward = rot == 0 || rot == 1;
        int step = forward ? 1 : -1;
        int s = drill.size, off = drill.sizeOffset, period = s + 1;
        int baseRoot = horizontal ? p.minY : p.minX;

        // try every band-grid offset, keep the one covering the most ore
        Seq<int[]> best = null;
        int bestCov = -1, bestOff = 0;
        for (int o = 0; o < period; o++) {
            Seq<int[]> sel = bandedDrills(p, drill, horizontal, baseRoot + o, period);
            int cov = 0;
            for (int[] c : sel) cov += c[2];
            if (cov > bestCov) {
                bestCov = cov;
                best = sel;
                bestOff = o;
            }
        }
        if (best == null || best.isEmpty()) {
            toast(Core.bundle.get("nv.autodrill.none", "No valid spots for drills"));
            return;
        }
        int base = baseRoot + bestOff;

        // queue drill plans, mark their tiles, record per-band extents along the flow axis
        IntSet occupied = new IntSet();
        IntMap<int[]> bandSpans = new IntMap<>();
        int covered = 0;
        for (int[] c : best) {
            for (int dx = 0; dx < s; dx++) {
                for (int dy = 0; dy < s; dy++) {
                    occupied.add(Point2.pack(c[0] + off + dx, c[1] + off + dy));
                }
            }
            Vars.player.unit().addBuild(new BuildPlan(c[0], c[1], 0, drill));
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

        // channel rows (between bands), each serving the bands below and above it
        Seq<int[]> lanes = new Seq<>(); // {row, lo, hi}
        for (IntMap.Entry<int[]> e : bandSpans) {
            int row = base + e.key * period + s;
            int lo = e.value[0], hi = e.value[1];
            int[] above = bandSpans.get(e.key + 1);
            if (above != null) {
                lo = Math.min(lo, above[0]);
                hi = Math.max(hi, above[1]);
            }
            lanes.add(new int[]{row, lo, hi});
        }
        int farMax = Integer.MIN_VALUE, farMin = Integer.MAX_VALUE;
        for (int[] l : lanes) {
            farMax = Math.max(farMax, l[2]);
            farMin = Math.min(farMin, l[1]);
        }
        int exitA = forward ? farMax + 1 : farMin - 1;
        int collA = exitA + step;
        int spacing = Math.max(1, Math.min(s, bridge.range));

        Seq<BuildPlan> bridgePlans = new Seq<>();
        IntMap<BuildPlan> channelEnds = new IntMap<>();
        for (int[] l : lanes) {
            BuildPlan end = buildChain(bridgePlans, bridge, rot, horizontal, l[0],
                forward ? l[1] : l[2], exitA, spacing, occupied);
            if (end != null) channelEnds.put(l[0], end);
        }

        // collector column: one node per channel row (plus relays), chained toward the min-row corner
        int collRot = horizontal ? 3 : 2;
        IntSeq rowSeq = new IntSeq();
        for (int[] l : lanes) if (channelEnds.containsKey(l[0])) rowSeq.add(l[0]);
        rowSeq.sort();
        IntMap<BuildPlan> nodes = new IntMap<>();
        BuildPlan prevNode = null;
        int prevRow = 0;
        for (int i = rowSeq.size - 1; i >= 0; i--) {
            int row = rowSeq.get(i);
            // relays when the gap down to the next node exceeds bridge range
            while (prevNode != null && prevRow - row > bridge.range) {
                int placedRow = Integer.MIN_VALUE;
                for (int rr = prevRow - bridge.range; rr < prevRow; rr++) {
                    BuildPlan relay = tryBridge(bridgePlans, bridge, collRot, horizontal, collA, rr, occupied);
                    if (relay != null) {
                        link(prevNode, relay);
                        prevNode = relay;
                        placedRow = rr;
                        break;
                    }
                }
                if (placedRow == Integer.MIN_VALUE) break;
                prevRow = placedRow;
            }
            BuildPlan node = tryBridge(bridgePlans, bridge, collRot, horizontal, collA, row, occupied);
            if (node == null) continue;
            if (prevNode != null && prevRow - row <= bridge.range) link(prevNode, node);
            nodes.put(row, node);
            prevNode = node;
            prevRow = row;
        }

        // hook each channel's last endpoint into its collector node
        for (IntMap.Entry<BuildPlan> e : channelEnds) {
            BuildPlan node = nodes.get(e.key);
            if (node == null) continue;
            int dist = Math.abs((horizontal ? node.x - e.value.x : node.y - e.value.y));
            if (dist <= bridge.range && e.value.config == null) link(e.value, node);
        }

        // single output from the corner: one more bridge, then the chosen conveyor line
        int conveyors = 0;
        if (prevNode != null) {
            BuildPlan out = tryBridge(bridgePlans, bridge, rot, horizontal, collA + 2 * step, prevRow, occupied);
            if (out != null) link(prevNode, out);
            boolean stack = conveyor instanceof StackConveyor;
            int len = stack ? 6 : 5;
            for (int i = 3; i < 3 + len; i++) {
                int a = collA + i * step;
                int cx = horizontal ? a : prevRow;
                int cy = horizontal ? prevRow : a;
                if (Build.validPlace(conveyor, Vars.player.team(), cx, cy, rot)) {
                    Vars.player.unit().addBuild(new BuildPlan(cx, cy, rot, conveyor));
                    conveyors++;
                }
            }
        }

        for (BuildPlan plan : bridgePlans) Vars.player.unit().addBuild(plan);

        float perSecond = covered * 60f / drill.getDrillTime(item);
        toast(Core.bundle.format("nv.autodrill.done3",
            best.size, bridgePlans.size, conveyors, Strings.fixed(perSecond, 1)));
    }

    /** Chain of bridge endpoints along the flow axis; every endpoint links to the next one. */
    private static BuildPlan buildChain(Seq<BuildPlan> out, ItemBridge bridge, int rot, boolean horizontal,
                                        int row, int startA, int endA, int spacing, IntSet occupied) {
        int step = startA <= endA ? 1 : -1;
        BuildPlan prev = null;
        int prevA = 0;
        int a = startA;
        while ((step > 0 ? a <= endA : a >= endA) && out.size < MAX_BRIDGES) {
            BuildPlan plan = null;
            int chosen = 0;
            for (int o = 0; o < spacing; o++) {
                int aa = a + o * step;
                if (step > 0 ? aa > endA : aa < endA) break;
                plan = tryBridge(out, bridge, rot, horizontal, aa, row, occupied);
                if (plan != null) {
                    chosen = aa;
                    break;
                }
            }
            if (plan != null) {
                if (prev != null && Math.abs(chosen - prevA) <= bridge.range) link(prev, plan);
                prev = plan;
                prevA = chosen;
                a = chosen + spacing * step;
            } else {
                a += spacing * step;
            }
        }
        // land a final endpoint as close to the exit as possible so the collector can pick it up
        if (prev != null && prevA != endA) {
            for (int aa = endA; (step > 0 ? aa > prevA : aa < prevA); aa -= step) {
                if (Math.abs(aa - prevA) > bridge.range) continue;
                BuildPlan plan = tryBridge(out, bridge, rot, horizontal, aa, row, occupied);
                if (plan != null) {
                    link(prev, plan);
                    prev = plan;
                    break;
                }
            }
        }
        return prev;
    }

    /** {@code a} is the coordinate along the flow axis, {@code row} the perpendicular one. */
    private static BuildPlan tryBridge(Seq<BuildPlan> out, ItemBridge bridge, int rot, boolean horizontal,
                                       int a, int row, IntSet occupied) {
        int cx = horizontal ? a : row;
        int cy = horizontal ? row : a;
        if (out.size >= MAX_BRIDGES) return null;
        if (occupied.contains(Point2.pack(cx, cy))) return null;
        if (!Build.validPlace(bridge, Vars.player.team(), cx, cy, rot)) return null;
        BuildPlan plan = new BuildPlan(cx, cy, rot, bridge);
        occupied.add(Point2.pack(cx, cy));
        out.add(plan);
        return plan;
    }

    private static void link(BuildPlan from, BuildPlan to) {
        from.config = new Point2(to.x - from.x, to.y - from.y);
    }

    private static class Patch {
        final IntSet tiles = new IntSet();
        int minX, maxX, minY, maxY;
    }

    private static Patch flood(Tile start, Item item) {
        boolean floorDrop = start.overlay().itemDrop == null;
        int radius = floorDrop ? FLOOR_RADIUS : Integer.MAX_VALUE;

        Patch p = new Patch();
        p.minX = p.maxX = start.x;
        p.minY = p.maxY = start.y;
        Seq<Tile> frontier = new Seq<>();
        frontier.add(start);
        p.tiles.add(start.pos());
        int count = 0;
        while (!frontier.isEmpty() && count < MAX_PATCH) {
            Tile t = frontier.pop();
            count++;
            p.minX = Math.min(p.minX, t.x);
            p.maxX = Math.max(p.maxX, t.x);
            p.minY = Math.min(p.minY, t.y);
            p.maxY = Math.max(p.maxY, t.y);
            for (Point2 d : Geometry.d4) {
                Tile n = Vars.world.tile(t.x + d.x, t.y + d.y);
                if (n != null && n.drop() == item
                    && Math.abs(n.x - start.x) <= radius && Math.abs(n.y - start.y) <= radius
                    && p.tiles.add(n.pos())) {
                    frontier.add(n);
                }
            }
        }
        return p;
    }

    /** Greedy max-coverage placement without band constraints (H hotkey mode). */
    private static Seq<int[]> denseDrills(Patch p, Drill drill) {
        return greedy(p, drill, candidates(p, drill, null, 0, 0));
    }

    /** Greedy placement restricted to band rows aligned to the given base/period. */
    private static Seq<int[]> bandedDrills(Patch p, Drill drill, boolean horizontal, int base, int period) {
        return greedy(p, drill, candidates(p, drill, horizontal, base, period));
    }

    private static Seq<int[]> candidates(Patch p, Drill drill, Boolean horizontal, int base, int period) {
        int s = drill.size, off = drill.sizeOffset;
        Seq<int[]> result = new Seq<>();
        for (int x = p.minX - s + 1; x <= p.maxX + s - 1; x++) {
            for (int y = p.minY - s + 1; y <= p.maxY + s - 1; y++) {
                if (horizontal != null) {
                    int foot = (horizontal ? y : x) + off;
                    if (((foot - base) % period + period) % period != 0) continue;
                }
                int count = 0;
                for (int dx = 0; dx < s; dx++) {
                    for (int dy = 0; dy < s; dy++) {
                        Tile t = Vars.world.tile(x + off + dx, y + off + dy);
                        if (t != null && p.tiles.contains(t.pos())) count++;
                    }
                }
                if (count > 0) result.add(new int[]{x, y, count});
            }
        }
        result.sort(c -> -c[2]);
        return result;
    }

    private static Seq<int[]> greedy(Patch p, Drill drill, Seq<int[]> candidates) {
        int s = drill.size, off = drill.sizeOffset;
        IntSet occupied = new IntSet();
        Seq<int[]> chosen = new Seq<>();
        for (int[] c : candidates) {
            if (chosen.size >= MAX_PLANS) break;
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
            chosen.add(c);
        }
        return chosen;
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
