package app;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.EntityHitResult;

/**
 * Combat stats, a HUD panel (HUD category): what a stun slam depends on, live. Your ping in
 * ms and ticks, your fall and whether the next mace hit is a slam, the mace and axe it would
 * use, your attack charge; the target under your crosshair (else the last one broken) with
 * its health, distance and shield (up, or still going up with its block-delay ticks); and
 * Auto Stun's landed count, last press and its result. Drawn by VelocityHud, movable in
 * the HUD editor.
 */
final class CombatStats {
    static final int WIDTH = 150;
    private static final int LINE = 10;
    private static final int PAD = 4;
    private static final int BACKGROUND = 0x90000000;
    private static final int HEADER = 0xFFFFD60A;
    private static final int LABEL = 0xFF98989F;
    private static final int TEXT = 0xFFF5F5F7;
    private static final int GOOD = 0xFF30D158;
    private static final int BAD = 0xFFFF453A;
    private static final int WARN = 0xFFFFD23F;
    private static final long RECENT_TARGET_MS = 4000;
    private static volatile boolean on;

    private CombatStats() {}

    private record Line(String label, String value, int color) {}

    static void draw(GuiGraphicsExtractor graphics, Minecraft minecraft, LocalPlayer player, int x, int y) {
        List<Line> lines = new ArrayList<>();
        int ping = AutoStun.pingMs(minecraft);
        lines.add(new Line("Ping", ping + " ms (" + (ping + 49) / 50 + " ticks)", ping <= 80 ? GOOD : ping <= 150 ? WARN : BAD));
        boolean slam = MaceSwap.isSlam(player);
        lines.add(new Line("Fall", String.format(Locale.ROOT, "%.1f b", player.fallDistance)
                + (slam ? "  SLAM" : player.onGround() ? "" : "  no slam yet"), slam ? GOOD : TEXT));
        int mace = MaceSwap.bestMace(player);
        lines.add(new Line("Mace", mace < 0 ? "none" : MaceSwap.describe(player.getInventory().getItem(mace))
                + " (slot " + (mace + 1) + ")", mace < 0 ? BAD : TEXT));
        lines.add(new Line("Axe", AutoStun.hasAxe(minecraft) ? "ready" : "none in hotbar",
                AutoStun.hasAxe(minecraft) ? TEXT : BAD));
        float charge = player.getAttackStrengthScale(0);
        lines.add(new Line("Charge", Math.round(charge * 100) + "%", charge >= 0.9F ? GOOD : WARN));

        LivingEntity target = minecraft.hitResult instanceof EntityHitResult hit
                && hit.getEntity() instanceof LivingEntity living ? living : null;
        if (target == null && AutoStun.lastPressAgoMs() >= 0 && AutoStun.lastPressAgoMs() < RECENT_TARGET_MS) {
            target = AutoStun.recentTarget();
        }
        if (target != null && target.isAlive()) {
            lines.add(new Line("Target", target.getName().getString(), HEADER));
            lines.add(new Line("  HP", String.format(Locale.ROOT, "%.1f / %.0f  %.1fm",
                    target.getHealth() + target.getAbsorptionAmount(), target.getMaxHealth(),
                    player.distanceTo(target)), TEXT));
            String shield = AutoStun.shieldLabel(minecraft, target);
            lines.add(new Line("  Shield", shield, shield.equals("UP") ? WARN : shield.startsWith("raising") ? LABEL : TEXT));
            lines.add(new Line("  Hurt", target.hurtTime > 0 ? target.hurtTime + "t immune" : "no", target.hurtTime > 0 ? WARN : TEXT));
        } else {
            lines.add(new Line("Target", "none", LABEL));
        }

        int tried = AutoStun.breaksTried();
        int landed = AutoStun.breaksLanded();
        lines.add(new Line("Breaks", landed + " / " + tried + (tried > 0 ? "  (" + Math.round(100.0 * landed / tried) + "%)" : ""),
                tried == 0 ? TEXT : landed * 10 >= tried * 9 ? GOOD : landed * 10 >= tried * 7 ? WARN : BAD));
        long ago = AutoStun.lastPressAgoMs();
        lines.add(new Line("Last", AutoStun.lastPress() + (ago >= 0 ? ", " + ago / 1000 + "s ago" : ""), TEXT));
        lines.add(new Line("Result", AutoStun.lastResult(), AutoStun.lastResultGood() ? GOOD
                : AutoStun.lastResult().startsWith("waiting") || AutoStun.lastResult().equals("-") ? TEXT : BAD));

        Font font = minecraft.font;
        int height = height(lines.size());
        graphics.fill(x, y, x + WIDTH, y + height, BACKGROUND);
        graphics.text(font, "Combat Stats", x + PAD, y + PAD, HEADER);
        int rowY = y + PAD + LINE + 2;
        int valueX = x + PAD + 44;
        for (Line line : lines) {
            graphics.text(font, line.label(), x + PAD, rowY, LABEL);
            graphics.text(font, font.plainSubstrByWidth(line.value(), x + WIDTH - PAD - valueX), valueX, rowY, line.color());
            rowY += LINE;
        }
    }

    /** The panel's height with this many lines; the HUD editor uses the usual count. */
    static int height(int lines) {
        return PAD * 2 + LINE + 2 + lines * LINE;
    }

    static boolean isOn() {
        return on;
    }

    static void setOn(boolean value) {
        on = value;
    }

    static void toggle() {
        on = !on;
    }
}
