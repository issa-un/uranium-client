package app;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;

/**
 * Slam Steer: while you fall into a slam with a mace ready, holds your own movement keys
 * (W A S D) toward the player below you, so your air strafe carries you onto them instead
 * of past them. Plain vanilla air control: it only presses keys, it never turns your view
 * and never pushes you faster than your keys can. A key you are holding yourself always
 * wins on its axis (hold S to back off and it stops pushing forward). It lets go once you
 * are right above them, land, or they get away.
 */
final class SlamSteer {
    private static final double MIN_FALL = 1.5;
    private static final double RANGE = 6;
    private static final double ON_TOP = 0.4;
    private static final double AXIS = 0.35;
    private static volatile boolean enabled;
    // Input loop only: whether our keys are down right now.
    private static boolean steering;

    private SlamSteer() {}

    /** Called from the input loop. */
    static void update(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        Player target = enabled && player != null && minecraft.level != null && Mc.screen(minecraft) == null
                && SpearSettings.combatAllowed(minecraft) && falling(player) && maceReady(player)
                ? target(minecraft, player) : null;
        if (target == null) {
            release(minecraft);
            return;
        }
        double dx = target.getX() - player.getX();
        double dz = target.getZ() - player.getZ();
        double distance = Math.hypot(dx, dz);
        if (distance < ON_TOP) {
            release(minecraft);
            return;
        }
        double yaw = Math.toRadians(player.getYRot());
        double forward = (-Math.sin(yaw) * dx + Math.cos(yaw) * dz) / distance;
        double left = (Math.cos(yaw) * dx + Math.sin(yaw) * dz) / distance;
        Options options = minecraft.options;
        steering = true;
        axis(minecraft, options.keyUp, options.keyDown, forward);
        axis(minecraft, options.keyLeft, options.keyRight, left);
    }

    /** Presses the key toward them on one axis, unless you are using that axis yourself. */
    private static void axis(Minecraft minecraft, KeyMapping plus, KeyMapping minus, double share) {
        boolean plusHeld = WTap.physicallyDown(minecraft, plus);
        boolean minusHeld = WTap.physicallyDown(minecraft, minus);
        if (plusHeld || minusHeld) {
            plus.setDown(plusHeld);
            minus.setDown(minusHeld);
            return;
        }
        plus.setDown(share > AXIS);
        minus.setDown(share < -AXIS);
    }

    private static boolean falling(LocalPlayer player) {
        return !player.onGround() && !player.isFallFlying() && !player.isInWater()
                && player.getDeltaMovement().y < -0.1 && player.fallDistance >= MIN_FALL;
    }

    private static boolean maceReady(LocalPlayer player) {
        return player.getMainHandItem().is(Items.MACE) || MaceSwap.isEnabled() && MaceSwap.bestMace(player) >= 0;
    }

    /** The closest player below you, within RANGE blocks across. */
    private static Player target(Minecraft minecraft, LocalPlayer player) {
        Player best = null;
        double bestDistance = RANGE;
        for (Player other : minecraft.level.players()) {
            if (other == player || !other.isAlive() || other.isSpectator() || VelocityHud.isFriend(other)
                    || other.getBoundingBox().maxY > player.getY()) {
                continue;
            }
            double distance = Math.hypot(other.getX() - player.getX(), other.getZ() - player.getZ());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = other;
            }
        }
        return best;
    }

    /** Hands the movement keys back to what you are physically holding. */
    private static void release(Minecraft minecraft) {
        if (!steering) {
            return;
        }
        steering = false;
        Options options = minecraft.options;
        for (KeyMapping key : new KeyMapping[] {options.keyUp, options.keyDown, options.keyLeft, options.keyRight}) {
            key.setDown(WTap.physicallyDown(minecraft, key));
        }
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

    static void reset(Minecraft minecraft) {
        enabled = false;
        release(minecraft);
    }
}
