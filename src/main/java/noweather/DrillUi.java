package noweather;

import arc.Core;
import arc.math.geom.Vec2;
import arc.scene.style.TextureRegionDrawable;
import arc.scene.ui.ImageButton;
import arc.scene.ui.layout.Cell;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.Align;
import mindustry.Vars;
import mindustry.gen.Icon;
import mindustry.type.Item;
import mindustry.ui.Styles;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.distribution.ArmoredConveyor;
import mindustry.world.blocks.distribution.Conveyor;
import mindustry.world.blocks.distribution.StackConveyor;
import mindustry.world.blocks.production.BurstDrill;
import mindustry.world.blocks.production.Drill;

/**
 * Bottom-left miner panel: toggle button -> drill icons -> conveyor icons (centered above
 * the selected drill) -> click an ore patch in the world -> pick the output direction.
 */
public class DrillUi {
    static boolean open;
    static boolean water;
    static Drill selDrill;
    static Block selConv;
    static Tile pending;

    private static final float BTN = 44f, ICON = 30f;
    private static Seq<Drill> drills = new Seq<>();
    private static Seq<Block> convs = new Seq<>();
    private static Cell<Table> convCell;
    private static Table panel;

    static void reset() {
        pending = null;
    }

    static void build() {
        drills.clear();
        convs.clear();
        for (Block b : Vars.content.blocks()) {
            if (b.isHidden()) continue;
            if (b instanceof Drill d && !(b instanceof BurstDrill)) drills.add(d);
            else if ((b instanceof Conveyor && !(b instanceof ArmoredConveyor)) || b instanceof StackConveyor) convs.add(b);
        }
        if (drills.isEmpty() || convs.isEmpty()) return;

        Vars.ui.hudGroup.fill(parent -> {
            parent.name = "nv-drillui";
            parent.bottom().left();
            parent.marginLeft(8f).marginBottom(8f);
            parent.visible(() -> Vars.state.isGame() && !Vars.mobile
                && Core.settings.getBool("nv-drillui", true));

            panel = new Table();
            panel.bottom().left();

            // conveyor row
            Table convRow = new Table(Styles.black5);
            for (Block conv : convs) {
                ImageButton ib = new ImageButton(new TextureRegionDrawable(conv.uiIcon), Styles.clearTogglei);
                ib.resizeImage(ICON);
                ib.clicked(() -> {
                    selConv = conv;
                    pending = null;
                });
                ib.update(() -> ib.setChecked(selConv == conv));
                convRow.add(ib).size(BTN);
            }
            Block extractor = Vars.content.block("water-extractor");
            if (extractor != null) {
                ImageButton wb = new ImageButton(new TextureRegionDrawable(extractor.uiIcon), Styles.clearTogglei);
                wb.resizeImage(ICON);
                wb.clicked(() -> water = !water);
                wb.update(() -> wb.setChecked(water));
                convRow.add(wb).size(BTN).padLeft(10f);
            }
            convRow.visible(() -> open && selDrill != null);

            // drill row
            Table drillRow = new Table(Styles.black5);
            for (Drill d : drills) {
                ImageButton ib = new ImageButton(new TextureRegionDrawable(d.uiIcon), Styles.clearTogglei);
                ib.resizeImage(ICON);
                ib.clicked(() -> {
                    selDrill = d;
                    pending = null;
                    centerRows();
                });
                ib.update(() -> ib.setChecked(selDrill == d));
                drillRow.add(ib).size(BTN);
            }
            drillRow.visible(() -> open);

            ImageButton toggle = new ImageButton(new TextureRegionDrawable(drills.first().uiIcon), Styles.squareTogglei);
            toggle.resizeImage(ICON);
            toggle.clicked(() -> {
                open = !open;
                pending = null;
            });
            toggle.update(() -> toggle.setChecked(open));

            convCell = panel.add(convRow).left();
            panel.row();
            panel.add(drillRow).left();
            panel.row();
            panel.add(toggle).size(BTN + 4f).left().padTop(2f);

            parent.add(panel);
        });

        // floating direction picker, anchored under the clicked ore tile
        Table dirTable = new Table(Styles.black6);
        dirTable.margin(4f);
        TextureRegionDrawable[] arrows = {Icon.right, Icon.up, Icon.left, Icon.down};
        for (int r = 0; r < 4; r++) {
            int rot = r;
            ImageButton ib = new ImageButton(arrows[r], Styles.cleari);
            ib.resizeImage(ICON);
            ib.clicked(() -> {
                if (pending != null && selDrill != null && selConv != null) {
                    AutoDrill.placeWithConveyors(selDrill, selConv, pending, rot, water);
                    pending = null;
                    open = false;
                }
            });
            dirTable.add(ib).size(BTN);
        }
        dirTable.visible(() -> open && pending != null && Vars.state.isGame());
        dirTable.update(() -> {
            if (pending == null) return;
            // the armed click must not start manual mining
            if (Vars.player.unit() != null && Vars.player.unit().mining()) {
                Vars.player.unit().mineTile(null);
            }
            Vec2 v = Core.input.mouseScreen(pending.worldx(), pending.worldy() - 6f);
            dirTable.pack();
            dirTable.setPosition(v.x, v.y, Align.top);
        });
        dirTable.pack();
        Vars.ui.hudGroup.addChild(dirTable);
    }

    /** Center the conveyor row above the selected drill button. */
    private static void centerRows() {
        if (convCell == null || selDrill == null) return;
        float drillCenter = drills.indexOf(selDrill) * BTN + BTN / 2f;
        convCell.padLeft(Math.max(0f, drillCenter - (convs.size + 1) * BTN / 2f));
        if (panel != null) panel.invalidateHierarchy();
    }

    /** World click -> remember the ore tile and wait for a direction choice. */
    static void update() {
        if (!open || selDrill == null || selConv == null) return;
        if (Vars.mobile || !Vars.state.isGame()) return;
        if (Core.scene.hasMouse() || !Core.input.justTouched()) return;

        Vec2 mouse = Core.input.mouseWorld();
        Tile tile = Vars.world.tileWorld(mouse.x, mouse.y);
        Item drop = tile == null ? null : tile.drop();
        if (drop == null) return;
        if (selDrill.tier < drop.hardness) {
            Vars.ui.showInfoToast(Core.bundle.format("nv.autodrill.tier", drop.localizedName), 3f);
            return;
        }
        pending = tile;
        if (Vars.player.unit() != null) Vars.player.unit().mineTile(null);
        Core.app.post(() -> {
            if (Vars.player.unit() != null) Vars.player.unit().mineTile(null);
        });
        Vars.ui.showInfoToast(Core.bundle.get("nv.autodrill.pick", "Choose output direction"), 2f);
    }
}
