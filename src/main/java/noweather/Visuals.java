package noweather;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Font;
import arc.math.geom.Rect;
import arc.scene.ui.layout.Scl;
import arc.struct.IntFloatMap;
import arc.struct.IntSet;
import arc.struct.Seq;
import arc.util.Align;
import arc.util.Time;
import arc.util.Tmp;
import mindustry.Vars;
import mindustry.gen.Groups;
import mindustry.graphics.Drawf;
import mindustry.graphics.Layer;
import mindustry.graphics.Pal;
import mindustry.ui.Fonts;
import mindustry.world.Tile;
import mindustry.world.blocks.defense.turrets.BaseTurret;

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

        Draw.z(Layer.overlayUI);

        if (rangesEnemy || rangesOwn || bars) {
            Groups.build.each(b -> {
                if ((rangesEnemy || rangesOwn) && b instanceof BaseTurret.BaseTurretBuild t) {
                    boolean ally = b.team == Vars.player.team();
                    float r = t.range();
                    if ((ally ? rangesOwn : rangesEnemy)
                        && cam.overlaps(Tmp.r1.set(b.x - r, b.y - r, r * 2f, r * 2f))) {
                        Drawf.dashCircle(b.x, b.y, r, ally ? Pal.heal : Pal.health);
                    }
                }
                if (bars && b.health < b.maxHealth - 0.01f && cam.contains(b.x, b.y)) {
                    drawBar(b.x, b.y + b.block.size * 4f, b.block.size * 8f * 0.8f, b.health / b.maxHealth);
                }
            });
        }

        if (bars) {
            Groups.unit.each(u -> {
                if (u.health < u.maxHealth - 0.01f && cam.contains(u.x, u.y)) {
                    drawBar(u.x, u.y + u.hitSize / 2f + 1f, Math.max(u.hitSize, 8f), u.health / u.maxHealth);
                }
            });
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
