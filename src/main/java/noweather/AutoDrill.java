package noweather;

import arc.Core;
import arc.input.KeyCode;
import arc.math.geom.Geometry;
import arc.math.geom.Point2;
import arc.math.geom.Vec2;
import arc.struct.IntIntMap;
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
    private static final int MAX_PATCH = 2000, MAX_PLANS = 256, MAX_BRIDGES = 400;
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
        Seq<int[]> chosen = greedy(p, drill);
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
     * Dense drill packing with a sparse bridge network threading through it: drills that would
     * have no free adjacent tile are dropped, the holes get bridge endpoints that serve up to
     * four neighboring drills each, endpoints are chained with spans flying over the drills,
     * and a single output line of the chosen conveyor leaves the field in the picked direction.
     */
    static void placeWithConveyors(Drill drill, Block conveyor, Tile start, int rot) {
        Item item = start.drop();
        if (item == null || drill.tier < item.hardness || Vars.player.unit() == null) return;
        ItemBridge bridge = findBridge();
        if (bridge == null) return;

        Patch p = flood(start, item);
        int s = drill.size, off = drill.sizeOffset;
        boolean horizontal = rot == 0 || rot == 2;
        boolean forward = rot == 0 || rot == 1;
        int step = forward ? 1 : -1;

        Seq<int[]> drills = greedy(p, drill);
        if (drills.isEmpty()) {
            toast(Core.bundle.get("nv.autodrill.none", "No valid spots for drills"));
            return;
        }

        IntSet occupied = new IntSet();
        for (int[] c : drills) markFootprint(occupied, c, s, off, true);

        // a drill with no usable adjacent cell can never unload — drop it, the hole becomes
        // a future bridge spot serving its neighbors
        while (true) {
            int dropIdx = -1, dropCount = Integer.MAX_VALUE;
            for (int i = 0; i < drills.size; i++) {
                if (hasUsablePerimeter(drills.get(i), s, off, occupied, bridge)) continue;
                if (drills.get(i)[2] < dropCount) {
                    dropCount = drills.get(i)[2];
                    dropIdx = i;
                }
            }
            if (dropIdx == -1) break;
            markFootprint(occupied, drills.get(dropIdx), s, off, false);
            drills.remove(dropIdx);
        }
        if (drills.isEmpty()) {
            toast(Core.bundle.get("nv.autodrill.none", "No valid spots for drills"));
            return;
        }

        // greedy set cover: pick cells serving the most still-uncovered drills
        Seq<int[]> nodes = new Seq<>(); // {x, y}
        IntSet nodeSet = new IntSet();
        boolean[] covered = new boolean[drills.size];
        int uncovered = drills.size;
        while (uncovered > 0 && nodes.size < MAX_BRIDGES) {
            IntIntMap score = new IntIntMap();
            for (int i = 0; i < drills.size; i++) {
                if (covered[i]) continue;
                IntSeq cells = usablePerimeter(drills.get(i), s, off, occupied, bridge);
                for (int j = 0; j < cells.size; j++) {
                    score.increment(cells.get(j), 0, 1);
                }
            }
            int bestPos = 0, bestScore = 0;
            for (IntIntMap.Entry e : score) {
                if (e.value > bestScore) {
                    bestScore = e.value;
                    bestPos = e.key;
                }
            }
            if (bestScore == 0) break;
            int nx = Point2.x(bestPos), ny = Point2.y(bestPos);
            nodes.add(new int[]{nx, ny});
            nodeSet.add(bestPos);
            occupied.add(bestPos);
            for (int i = 0; i < drills.size; i++) {
                if (!covered[i] && touchesCell(drills.get(i), s, off, nx, ny)) {
                    covered[i] = true;
                    uncovered--;
                }
            }
        }
        if (nodes.isEmpty()) {
            toast(Core.bundle.get("nv.autodrill.none", "No valid spots for drills"));
            return;
        }

        // connect bridge nodes into one network (spans may cross over drills)
        connect(nodes, nodeSet, occupied, bridge, p);

        // extend a chain out of the field in the chosen direction
        int exitBound = forward
            ? (horizontal ? p.maxX : p.maxY) + 2
            : (horizontal ? p.minX : p.minY) - 2;
        int outIdx = 0;
        for (int i = 1; i < nodes.size; i++) {
            int a = horizontal ? nodes.get(i)[0] : nodes.get(i)[1];
            int bestA = horizontal ? nodes.get(outIdx)[0] : nodes.get(outIdx)[1];
            if (forward ? a > bestA : a < bestA) outIdx = i;
        }
        int[] cur = nodes.get(outIdx);
        while ((forward ? (horizontal ? cur[0] : cur[1]) < exitBound
                        : (horizontal ? cur[0] : cur[1]) > exitBound) && nodes.size < MAX_BRIDGES) {
            int curA = horizontal ? cur[0] : cur[1];
            int row = horizontal ? cur[1] : cur[0];
            int[] placedNode = null;
            for (int d = bridge.range; d >= 1; d--) {
                int aa = curA + d * step;
                if (forward ? aa > exitBound : aa < exitBound) continue;
                int cx = horizontal ? aa : row;
                int cy = horizontal ? row : aa;
                int pos = Point2.pack(cx, cy);
                if (occupied.contains(pos) || !Build.validPlace(bridge, Vars.player.team(), cx, cy, rot)) continue;
                placedNode = new int[]{cx, cy};
                nodes.add(placedNode);
                nodeSet.add(pos);
                occupied.add(pos);
                break;
            }
            if (placedNode == null) break;
            cur = placedNode;
        }
        int[] output = cur;

        // orient the network: every node points one hop closer to the output
        BuildPlan[] plans = new BuildPlan[nodes.size];
        for (int i = 0; i < nodes.size; i++) {
            plans[i] = new BuildPlan(nodes.get(i)[0], nodes.get(i)[1], rot, bridge);
        }
        orient(nodes, plans, indexOf(nodes, output), bridge.range, horizontal, forward);

        for (BuildPlan plan : plans) Vars.player.unit().addBuild(plan);

        // output line of the chosen conveyor (stack conveyors load at their tail, which sits
        // right after the dumping output bridge, so this works for plastanium too)
        int conveyors = 0;
        boolean stack = conveyor instanceof StackConveyor;
        int len = stack ? 6 : 5;
        int outRow = horizontal ? output[1] : output[0];
        int outA = horizontal ? output[0] : output[1];
        for (int i = 1; i <= len; i++) {
            int a = outA + i * step;
            int cx = horizontal ? a : outRow;
            int cy = horizontal ? outRow : a;
            if (Build.validPlace(conveyor, Vars.player.team(), cx, cy, rot)) {
                Vars.player.unit().addBuild(new BuildPlan(cx, cy, rot, conveyor));
                conveyors++;
            }
        }

        int coveredOre = 0;
        for (int[] c : drills) coveredOre += c[2];
        float perSecond = coveredOre * 60f / drill.getDrillTime(item);
        toast(Core.bundle.format("nv.autodrill.done3",
            drills.size, nodes.size, conveyors, Strings.fixed(perSecond, 1)));
    }

    /** BFS from the output across bridge links; every visited node's config points to its parent. */
    private static void orient(Seq<int[]> nodes, BuildPlan[] plans, int outputIdx, int range,
                               boolean horizontal, boolean forward) {
        int n = nodes.size;
        boolean[] visited = new boolean[n];
        IntSeq frontier = new IntSeq();
        frontier.add(outputIdx);
        visited[outputIdx] = true;
        while (!frontier.isEmpty()) {
            int curIdx = frontier.removeIndex(0);
            int[] cur = nodes.get(curIdx);
            for (int i = 0; i < n; i++) {
                if (visited[i] || !linked(cur, nodes.get(i), range)) continue;
                visited[i] = true;
                plans[i].config = new Point2(cur[0] - nodes.get(i)[0], cur[1] - nodes.get(i)[1]);
                frontier.add(i);
            }
        }
        // disconnected leftovers: orient each toward its own most-forward node, which dumps locally
        for (int c = 0; c < n; c++) {
            if (visited[c]) continue;
            int best = c;
            for (int i = 0; i < n; i++) {
                if (visited[i]) continue;
                int a = horizontal ? nodes.get(i)[0] : nodes.get(i)[1];
                int bestA = horizontal ? nodes.get(best)[0] : nodes.get(best)[1];
                if (forward ? a > bestA : a < bestA) best = i;
            }
            visited[best] = true;
            IntSeq f2 = new IntSeq();
            f2.add(best);
            while (!f2.isEmpty()) {
                int curIdx = f2.removeIndex(0);
                int[] cur = nodes.get(curIdx);
                for (int i = 0; i < n; i++) {
                    if (visited[i] || !linked(cur, nodes.get(i), range)) continue;
                    visited[i] = true;
                    plans[i].config = new Point2(cur[0] - nodes.get(i)[0], cur[1] - nodes.get(i)[1]);
                    f2.add(i);
                }
            }
        }
    }

    private static boolean linked(int[] a, int[] b, int range) {
        if (a[0] == b[0] && a[1] != b[1]) return Math.abs(a[1] - b[1]) <= range;
        if (a[1] == b[1] && a[0] != b[0]) return Math.abs(a[0] - b[0]) <= range;
        return false;
    }

    private static int indexOf(Seq<int[]> nodes, int[] node) {
        for (int i = 0; i < nodes.size; i++) {
            if (nodes.get(i) == node) return i;
        }
        return 0;
    }

    /** Merge disconnected bridge components by laying relay nodes found with a jump-BFS. */
    private static void connect(Seq<int[]> nodes, IntSet nodeSet, IntSet occupied, ItemBridge bridge, Patch p) {
        int range = bridge.range;
        int lo = Point2.pack(p.minX - 5, p.minY - 5), hi = Point2.pack(p.maxX + 5, p.maxY + 5);
        int minX = Point2.x(lo), minY = Point2.y(lo), maxX = Point2.x(hi), maxY = Point2.y(hi);

        for (int guard = 0; guard < 32; guard++) {
            // label components over current nodes
            int n = nodes.size;
            int[] comp = new int[n];
            for (int i = 0; i < n; i++) comp[i] = -1;
            int comps = 0;
            for (int i = 0; i < n; i++) {
                if (comp[i] != -1) continue;
                IntSeq f = new IntSeq();
                f.add(i);
                comp[i] = comps;
                while (!f.isEmpty()) {
                    int cur = f.removeIndex(0);
                    for (int j = 0; j < n; j++) {
                        if (comp[j] == -1 && linked(nodes.get(cur), nodes.get(j), range)) {
                            comp[j] = comps;
                            f.add(j);
                        }
                    }
                }
                comps++;
            }
            if (comps <= 1 || nodes.size >= MAX_BRIDGES) return;

            // jump-BFS from component 0 cells to any node of another component
            IntMap<Integer> from = new IntMap<>();
            IntSeq frontier = new IntSeq();
            IntIntMap compByPos = new IntIntMap();
            for (int i = 0; i < n; i++) {
                compByPos.put(Point2.pack(nodes.get(i)[0], nodes.get(i)[1]), comp[i]);
                if (comp[i] == 0) {
                    int pos = Point2.pack(nodes.get(i)[0], nodes.get(i)[1]);
                    frontier.add(pos);
                    from.put(pos, pos);
                }
            }
            int found = -1;
            while (!frontier.isEmpty() && found == -1) {
                int pos = frontier.removeIndex(0);
                int px = Point2.x(pos), py = Point2.y(pos);
                for (Point2 d : Geometry.d4) {
                    for (int dist = 1; dist <= range && found == -1; dist++) {
                        int cx = px + d.x * dist, cy = py + d.y * dist;
                        if (cx < minX || cx > maxX || cy < minY || cy > maxY) break;
                        int npos = Point2.pack(cx, cy);
                        if (from.containsKey(npos)) continue;
                        boolean isNode = nodeSet.contains(npos);
                        if (!isNode && (occupied.contains(npos)
                            || !Build.validPlace(bridge, Vars.player.team(), cx, cy, 0))) continue;
                        from.put(npos, pos);
                        if (isNode && compByPos.get(npos, 0) != 0) {
                            found = npos;
                        } else if (!isNode) {
                            frontier.add(npos);
                        }
                    }
                }
            }
            if (found == -1) return; // can't connect the rest, leave as is

            // materialize relay nodes along the path
            int walk = from.get(found);
            while (from.get(walk) != walk) {
                if (!nodeSet.contains(walk)) {
                    nodes.add(new int[]{Point2.x(walk), Point2.y(walk)});
                    nodeSet.add(walk);
                    occupied.add(walk);
                }
                walk = from.get(walk);
            }
        }
    }

    private static void markFootprint(IntSet occupied, int[] c, int s, int off, boolean add) {
        for (int dx = 0; dx < s; dx++) {
            for (int dy = 0; dy < s; dy++) {
                int pos = Point2.pack(c[0] + off + dx, c[1] + off + dy);
                if (add) occupied.add(pos);
                else occupied.remove(pos);
            }
        }
    }

    private static boolean touchesCell(int[] c, int s, int off, int x, int y) {
        int fx = c[0] + off, fy = c[1] + off;
        boolean inX = x >= fx && x < fx + s, inY = y >= fy && y < fy + s;
        return (inX && (y == fy - 1 || y == fy + s)) || (inY && (x == fx - 1 || x == fx + s));
    }

    private static boolean hasUsablePerimeter(int[] c, int s, int off, IntSet occupied, ItemBridge bridge) {
        return usablePerimeter(c, s, off, occupied, bridge).size > 0;
    }

    private static IntSeq usablePerimeter(int[] c, int s, int off, IntSet occupied, ItemBridge bridge) {
        IntSeq result = new IntSeq();
        int fx = c[0] + off, fy = c[1] + off;
        for (int i = 0; i < s; i++) {
            addUsable(result, fx + i, fy - 1, occupied, bridge);
            addUsable(result, fx + i, fy + s, occupied, bridge);
            addUsable(result, fx - 1, fy + i, occupied, bridge);
            addUsable(result, fx + s, fy + i, occupied, bridge);
        }
        return result;
    }

    private static void addUsable(IntSeq out, int x, int y, IntSet occupied, ItemBridge bridge) {
        int pos = Point2.pack(x, y);
        if (occupied.contains(pos)) return;
        if (!Build.validPlace(bridge, Vars.player.team(), x, y, 0)) return;
        out.add(pos);
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

    /** Greedy max-coverage dense drill packing. */
    private static Seq<int[]> greedy(Patch p, Drill drill) {
        int s = drill.size, off = drill.sizeOffset;
        Seq<int[]> candidates = new Seq<>();
        for (int x = p.minX - s + 1; x <= p.maxX + s - 1; x++) {
            for (int y = p.minY - s + 1; y <= p.maxY + s - 1; y++) {
                int count = 0;
                for (int dx = 0; dx < s; dx++) {
                    for (int dy = 0; dy < s; dy++) {
                        Tile t = Vars.world.tile(x + off + dx, y + off + dy);
                        if (t != null && p.tiles.contains(t.pos())) count++;
                    }
                }
                if (count > 0) candidates.add(new int[]{x, y, count});
            }
        }
        candidates.sort(c -> -c[2]);

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
