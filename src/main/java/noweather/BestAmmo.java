package noweather;

import arc.Core;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import mindustry.Vars;
import mindustry.content.StatusEffects;
import mindustry.entities.bullet.BulletType;
import mindustry.type.Item;
import mindustry.world.Block;
import mindustry.world.blocks.defense.turrets.ItemTurret;
import mindustry.world.meta.Stat;
import mindustry.world.meta.StatCat;

/** Adds a computed "DPS by ammo" line to every item turret's info dialog, best ammo highlighted. */
public class BestAmmo {
    private static final Stat stat = new Stat("nv-ammodps", StatCat.function);

    static void apply() {
        for (Block block : Vars.content.blocks()) {
            if (!(block instanceof ItemTurret turret) || turret.ammoTypes.size == 0) continue;

            Seq<Entry> entries = new Seq<>();
            for (ObjectMap.Entry<Item, BulletType> e : turret.ammoTypes) {
                entries.add(new Entry(e.key, e.value, dps(turret, e.value)));
            }
            entries.sort(e -> -e.dps);
            Entry best = entries.first();

            turret.stats.add(stat, table -> {
                table.row();
                table.table(t -> {
                    for (Entry e : entries) {
                        t.image(e.item.uiIcon).size(24f).padRight(6f).padBottom(2f).left();
                        t.add(describe(e, e == best)).left().padBottom(2f).row();
                    }
                }).left();
            });
        }
    }

    /** Single-target sustained DPS, ignoring burst spacing and damage-over-time — good enough for ranking. */
    private static float dps(ItemTurret turret, BulletType b) {
        float shotsPerSecond = 60f / turret.reload * b.reloadMultiplier * turret.shoot.shots;
        float perBullet = b.damage + Math.max(b.splashDamage, 0f);
        if (b.fragBullet != null) {
            perBullet += b.fragBullets * (b.fragBullet.damage + Math.max(b.fragBullet.splashDamage, 0f));
        }
        return shotsPerSecond * perBullet;
    }

    private static String describe(Entry e, boolean best) {
        StringBuilder sb = new StringBuilder(best ? "[accent]" : "[lightgray]");
        sb.append(e.item.localizedName)
          .append(" — ~").append(Math.round(e.dps)).append(' ')
          .append(Core.bundle.get("nv.dps", "DPS"));
        if (best) sb.append(" ★");

        Seq<String> tags = new Seq<>();
        if (e.bullet.pierceCap > 1) tags.add(Core.bundle.get("nv.pierce", "pierce") + " ×" + e.bullet.pierceCap);
        if (e.bullet.splashDamage > 0) tags.add(Core.bundle.get("nv.splash", "splash"));
        if (e.bullet.makeFire) {
            tags.add(Core.bundle.get("nv.fire", "ignites"));
        } else if (e.bullet.status != StatusEffects.none) {
            tags.add(e.bullet.status.localizedName);
        }
        if (!tags.isEmpty()) sb.append(" (").append(tags.toString(", ")).append(')');
        return sb.toString();
    }

    private static class Entry {
        final Item item;
        final BulletType bullet;
        final float dps;

        Entry(Item item, BulletType bullet, float dps) {
            this.item = item;
            this.bullet = bullet;
            this.dps = dps;
        }
    }
}
