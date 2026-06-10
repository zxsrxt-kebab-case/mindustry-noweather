package noweather;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Font;
import arc.math.Mathf;
import arc.math.geom.Rect;
import arc.math.geom.Vec2;
import arc.scene.ui.layout.Scl;
import arc.struct.IntFloatMap;
import arc.struct.IntSet;
import arc.struct.Seq;
import arc.util.Align;
import arc.util.Strings;
import arc.util.Time;
import arc.util.Tmp;
import mindustry.Vars;
import mindustry.gen.Groups;
import mindustry.gen.Icon;
import mindustry.graphics.Drawf;
import mindustry.graphics.Layer;
import mindustry.graphics.Pal;
import mindustry.type.Item;
import mindustry.ui.Fonts;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.defense.ForceProjector;
import mindustry.world.blocks.defense.MendProjector;
import mindustry.world.blocks.defense.OverdriveProjector;
import mindustry.world.blocks.defense.turrets.BaseTurret;
import mindustry.world.blocks.defense.turrets.Turret;
import mindustry.world.blocks.power.PowerGenerator;
import mindustry.world.blocks.production.BurstDrill;
import mindustry.world.blocks.production.Drill;

public class Visuals {
    private static final Rect cam = new Rect();
    private static final IntFloatMap lastHealth = new IntFloatMap();
    private static final Seq<DamageLabel> labels = new Seq<>();
    private static final IntSet myBlocks = new IntSet();
    private static float purgeTimer;

    private static class DamageLabel {
        float x, y, life, value;
        boolean ally;
    }

    static void reset() {
        lastHealth.clear();
        labels.clear();
        myBlocks.clear();
        purgeTimer = 0f;
    }

    static void addMyBlock(int pos) {
        myBlocks.add(pos);
    }

    static void removeMyBlock(int pos) {
        myBlocks.remove(pos);
    }

    static void update() {
        if (!Vars.state.isGame()) return;

        if (on("nv-damage", true)) {
            Core.camera.bounds(cam);
            cam.x -= 32f; cam.y -= 32f; cam.width += 64f; cam.height += 64f;

            Groups.unit.each(u -> trackHealth(u.id, u.health,
                u.x, u.y + u.hitSize / 2f + 2f,
                u.team == Vars.player.team(), cam.contains(u.x, u.y)));
            Groups.build.each(b -> trackHealth(-b.id, b.health,
                b.x, b.y + b.block.size * 4f + 2f,
                b.team == Vars.player.team(), cam.contains(b.x, b.y)));

            // forget stale entries so the map does not grow forever
            purgeTimer += Time.delta;
            if (purgeTimer > 600f) {
                purgeTimer = 0f;
                lastHealth.clear();
            }
        } else if (lastHealth.size > 0) {
            lastHealth.clear();
        }

        if (!labels.isEmpty()) {
            labels.each(l -> {
                l.life -= Time.delta;
                l.y += Time.delta * 0.35f;
            });
            labels.removeAll(l -> l.life <= 0f);
        }
    }

    private static void trackHealth(int id, float health, float x, float y, boolean ally, boolean visible) {
        float prev = lastHealth.get(id, Float.NaN);
        lastHealth.put(id, health);
        if (Float.isNaN(prev) || !visible) return;
        float dmg = prev - health;
        if (dmg >= 1f && labels.size < 128) {
            DamageLabel l = new DamageLabel();
            l.x = x;
            l.y = y;
            l.value = dmg;
            l.life = 50f;
            l.ally = ally;
            labels.add(l);
        }
    }

    static void draw() {
        if (!Vars.state.isGame()) return;
        Core.camera.bounds(cam);

        boolean rangesEnemy = on("nv-ranges-enemy", true);
        boolean rangesOwn = on("nv-ranges-own", false);
        boolean bars = on("nv-healthbars", true);
        boolean projectors = on("nv-projectors", true);

        Draw.z(Layer.overlayUI);

        if (rangesEnemy || rangesOwn || bars || projectors) {
            Groups.build.each(b -> {
                if ((rangesEnemy || rangesOwn) && b instanceof BaseTurret.BaseTurretBuild t) {
                    boolean ally = b.team == Vars.player.team();
                    float r = t.range();
                    if ((ally ? rangesOwn : rangesEnemy)
                        && cam.overlaps(Tmp.r1.set(b.x - r, b.y - r, r * 2f, r * 2f))) {
                        Drawf.dashCircle(b.x, b.y, r, ally ? Pal.heal : Pal.health);
                    }
                }
                if (projectors && b.team == Vars.player.team()) {
                    float pr =
                        b.block instanceof MendProjector m ? m.range :
                        b.block instanceof OverdriveProjector o ? o.range :
                        b.block instanceof ForceProjector f ? f.radius : 0f;
                    if (pr > 0f && cam.overlaps(Tmp.r1.set(b.x - pr, b.y - pr, pr * 2f, pr * 2f))) {
                        Drawf.dashCircle(b.x, b.y, pr,
                            b.block instanceof MendProjector ? Pal.heal :
                            b.block instanceof OverdriveProjector ? Pal.accent : b.team.color);
                    }
                }
                if (bars && b.health < b.maxHealth - 0.01f && cam.contains(b.x, b.y)) {
                    drawBar(b.x, b.y + b.block.size * 4f, b.block.size * 8f * 0.8f, b.health / b.maxHealth);
                }
            });
        }

        if (DrillUi.pending != null) {
            Drawf.square(DrillUi.pending.worldx(), DrillUi.pending.worldy(), 6f, Pal.accent);
        }

        if (on("nv-spawns", true) && Vars.state.rules.waves) {
            float dropRadius = Vars.state.rules.dropZoneRadius;
            for (Tile spawn : Vars.spawner.getSpawns()) {
                float sx = spawn.worldx(), sy = spawn.worldy();
                if (!cam.overlaps(Tmp.r1.set(sx - dropRadius, sy - dropRadius, dropRadius * 2f, dropRadius * 2f))) continue;
                Drawf.dashCircle(sx, sy, dropRadius, Pal.remove);
                Draw.color(Pal.remove, 0.8f);
                Draw.rect(Icon.units.getRegion(), sx, sy, 10f, 10f);
                Draw.color();
            }
        }

        boolean unitRangesEnemy = on("nv-uranges-enemy", true);
        boolean unitRangesOwn = on("nv-uranges-own", false);
        if (bars || unitRangesEnemy || unitRangesOwn) {
            Groups.unit.each(u -> {
                boolean ally = u.team == Vars.player.team();
                if (ally ? unitRangesOwn : unitRangesEnemy) {
                    float r = u.type.maxRange;
                    if (r > 1f && cam.overlaps(Tmp.r1.set(u.x - r, u.y - r, r * 2f, r * 2f))) {
                        Drawf.dashCircle(u.x, u.y, r, ally ? Pal.heal : Pal.health);
                    }
                }
                if (bars && u.health < u.maxHealth - 0.01f && cam.contains(u.x, u.y)) {
                    drawBar(u.x, u.y + u.hitSize / 2f + 1f, Math.max(u.hitSize, 8f), u.health / u.maxHealth);
                }
            });
        }

        if (on("nv-alerts", true)) {
            float iconAlpha = 0.55f + 0.45f * Mathf.absin(Time.time, 4f, 1f);
            Groups.build.each(b -> {
                if (b.team != Vars.player.team() || !cam.contains(b.x, b.y)) return;
                boolean warn =
                    (b instanceof Turret.TurretBuild t && !t.hasAmmo())
                    || (b instanceof PowerGenerator.GeneratorBuild g
                        && g.productionEfficiency <= 0.001f && b.block.hasConsumers);
                if (warn) {
                    Draw.color(Pal.health, iconAlpha);
                    Draw.rect(Icon.warning.getRegion(), b.x, b.y + b.block.size * 4f + 4f, 6f, 6f);
                    Draw.color();
                }
            });
        }

        if (on("nv-orehover", true) && !Vars.mobile) {
            Vec2 mouse = Core.input.mouseWorld();
            Tile hovered = Vars.world.tileWorld(mouse.x, mouse.y);
            Item drop = hovered == null ? null : hovered.drop();
            if (drop != null) {
                StringBuilder sb = new StringBuilder("[accent]").append(drop.localizedName).append("[]");
                for (Block block : Vars.content.blocks()) {
                    if (block instanceof Drill d && !(block instanceof BurstDrill)
                        && d.tier >= drop.hardness && !block.isHidden()) {
                        float perSecond = 60f * d.size * d.size / d.getDrillTime(drop);
                        sb.append('\n').append(block.localizedName).append(": ")
                          .append(Strings.fixed(perSecond, 2)).append('/')
                          .append(Core.bundle.get("nv.sec", "s"));
                    }
                }
                Font font = Fonts.outline;
                boolean ints = font.usesIntegerPositions();
                font.setUseIntegerPositions(false);
                font.getData().setScale(1f / 4f / Scl.scl(1f));
                font.setColor(Color.white);
                font.draw(sb.toString(), mouse.x + 6f, mouse.y - 4f, Align.left);
                font.getData().setScale(1f);
                font.setUseIntegerPositions(ints);
            }
        }

        if (on("nv-myblocks", true) && !myBlocks.isEmpty()) {
            IntSet.IntSetIterator it = myBlocks.iterator();
            while (it.hasNext) {
                Tile tile = Vars.world.tile(it.next());
                if (tile == null || tile.build == null) continue;
                if (cam.contains(tile.build.x, tile.build.y)) {
                    Drawf.square(tile.build.x, tile.build.y, tile.build.block.size * 4f + 1f, 0f, Pal.accent);
                }
            }
        }

        if (on("nv-damage", true) && !labels.isEmpty()) {
            Font font = Fonts.outline;
            boolean ints = font.usesIntegerPositions();
            font.setUseIntegerPositions(false);
            font.getData().setScale(1f / 4f / Scl.scl(1f));
            for (DamageLabel l : labels) {
                float alpha = Math.min(l.life / 20f, 1f);
                font.setColor(Tmp.c1.set(l.ally ? Pal.health : Color.yellow).a(alpha));
                font.draw(String.valueOf((int) (l.value + 0.5f)), l.x, l.y, Align.center);
            }
            font.setColor(Color.white);
            font.getData().setScale(1f);
            font.setUseIntegerPositions(ints);
        }

        Draw.reset();
    }

    private static void drawBar(float x, float y, float width, float fraction) {
        float h = 1.2f, half = width / 2f;
        Draw.color(Color.black, 0.55f);
        Fill.crect(x - half - 0.4f, y - 0.4f, width + 0.8f, h + 0.8f);
        Draw.color(Tmp.c1.set(Color.scarlet).lerp(Color.lime, fraction));
        Fill.crect(x - half, y, width * fraction, h);
        Draw.color();
    }

    private static boolean on(String key, boolean def) {
        return Core.settings.getBool(key, def);
    }
}
