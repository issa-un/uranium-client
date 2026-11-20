package app;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.EntityHitResult;

/**
 * Hit Select: any hit clicked while the target is still in their hurt immunity (the half
 * second after any hit) only counts for the part that beats their last hit, so most of it
 * is wasted. Hit Select holds that click and sends it the moment their immunity ends, while
 * they are still under your crosshair. Works for every hit: sword, axe, spear, fist, mace.
 * It gives up after WAIT_MS; a held mace slam is also dropped once you land (it wouldn't be
 * a slam any more).
 */
final class HitSelect {
    private static final long WAIT_MS = 600;
    private static volatile boolean enabled;
    // Render thread only.
    private static int heldTargetId = -1;
    private static long heldSinceMs;
    private static boolean heldSlam;

    private HitSelect() {}

    /** Your attack click: true if it was held back for later. */
    static boolean onAttackClick(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (!enabled || player == null || Mc.screen(minecraft) != null || !Settings.combatAllowed(minecraft)) {
            return false;
        }
        if (!(minecraft.hitResult instanceof EntityHitResult hit) || !(hit.getEntity() instanceof LivingEntity target)
                || !AutoStun.immuneWhenHitArrives(minecraft, target)) {
            return false;
        }
        boolean slam = slamReady(player);
        if (heldTargetId < 0) {
            UraniumLog.log("hit select: held your " + (slam ? "slam" : "hit") + " on " + target.getName().getString()
                    + ", they are still immune");
        }
        heldSlam = slam;
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
                || heldSlam && !slamReady(player)) {
            if (entity instanceof LivingEntity gone) {
                UraniumLog.log("hit select: dropped the " + (heldSlam ? "slam" : "hit") + " on " + gone.getName().getString()
                        + (heldSlam && player != null && player.onGround() ? ", you landed" : ", waited too long"));
            }
            heldTargetId = -1;
            return;
        }
        if (AutoStun.immuneWhenHitArrives(minecraft, target)) {
            return;
        }
        if (!(minecraft.hitResult instanceof EntityHitResult hit) || hit.getEntity() != target) {
            return; // off your crosshair: wait, they may come back under it
        }
        heldTargetId = -1;
        TriggerBot.strike(minecraft, player, target, heldSlam, now);
        UraniumLog.log("hit select: sent the " + (heldSlam ? "slam" : "hit") + " on " + target.getName().getString() + " after " + (now - heldSinceMs)
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
