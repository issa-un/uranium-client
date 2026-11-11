package app;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.EntityHitResult;

/**
 * Slam Saver: a slam clicked while the target is still in their hurt immunity (the half
 * second after any hit) only counts for the part that beats their last hit, so most of it
 * is wasted. Slam Saver holds that click and sends it the moment their immunity ends, if
 * you are still falling into the slam and they are still under your crosshair. It gives up
 * after WAIT_MS, or once you land.
 */
final class SlamSaver {
    private static final long WAIT_MS = 600;
    private static volatile boolean enabled;
    // Render thread only.
    private static int heldTargetId = -1;
    private static long heldSinceMs;

    private SlamSaver() {}

    /** Your attack click: true if it was held back for later. */
    static boolean onAttackClick(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (!enabled || player == null || Mc.screen(minecraft) != null || !SpearSettings.combatAllowed(minecraft)
                || !slamReady(player)) {
            return false;
        }
        if (!(minecraft.hitResult instanceof EntityHitResult hit) || !(hit.getEntity() instanceof LivingEntity target)
                || !ShieldBreak.immuneWhenHitArrives(minecraft, target)) {
            return false;
        }
        if (heldTargetId < 0) {
            SpearLog.log("slam saver: held your slam on " + target.getName().getString() + ", they are still immune");
        }
        heldTargetId = target.getId();
        heldSinceMs = nowMs();
        return true;
    }

    /** Called from the input loop: sends the held slam once it will count. */
    static void update(Minecraft minecraft) {
        if (heldTargetId < 0) {
            return;
        }
        LocalPlayer player = minecraft.player;
        long now = nowMs();
        Entity entity = player == null || minecraft.level == null ? null : minecraft.level.getEntity(heldTargetId);
        if (!enabled || !(entity instanceof LivingEntity target) || !target.isAlive() || now - heldSinceMs > WAIT_MS
                || !slamReady(player)) {
            if (entity instanceof LivingEntity gone) {
                SpearLog.log("slam saver: dropped the slam on " + gone.getName().getString()
                        + (player != null && player.onGround() ? ", you landed" : ", waited too long"));
            }
            heldTargetId = -1;
            return;
        }
        if (ShieldBreak.immuneWhenHitArrives(minecraft, target)) {
            return;
        }
        if (!(minecraft.hitResult instanceof EntityHitResult hit) || hit.getEntity() != target) {
            return; // off your crosshair: wait, they may come back under it
        }
        heldTargetId = -1;
        Hit.strike(minecraft, player, target, true, now);
        SpearLog.log("slam saver: sent the slam on " + target.getName().getString() + " after " + (now - heldSinceMs)
                + " ms, their immunity was over");
    }

    private static boolean slamReady(LocalPlayer player) {
        return MaceSwap.isSlam(player)
                && (player.getMainHandItem().is(Items.MACE) || MaceSwap.isEnabled() && MaceSwap.bestMace(player) >= 0);
    }

    private static long nowMs() {
        return System.nanoTime() / 1_000_000;
    }

    static boolean isEnabled() {
        return enabled;
    }

    static void setEnabled(boolean on) {
        enabled = on;
    }

    static void toggleEnabled() {
        enabled = !enabled;
    }

    static void reset() {
        enabled = false;
        heldTargetId = -1;
    }
}
