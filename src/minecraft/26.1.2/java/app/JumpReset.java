package app;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * Jump Reset: when something hits you while you are on the ground, jumps on that game tick. Jumping as the
 * knockback arrives, while sprinting toward the attacker, adds the sprint-jump push toward
 * them and cuts part of the knockback. A hit shows as your hurt timer jumping back up; jump
 * is held for one game tick, then put back to how you are holding it.
 */
final class JumpReset {
    private static volatile boolean enabled;
    private static volatile boolean onlyWhileSprinting;
    // Render thread only.
    private static int lastHurtTime;
    private static int jumpUntilTick = -1;

    private JumpReset() {}

    /** Called from the input loop. */
    static void update(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player == null) {
            jumpUntilTick = -1;
            return;
        }
        if (jumpUntilTick >= 0) {
            if (player.tickCount >= jumpUntilTick || Mc.screen(minecraft) != null) {
                minecraft.options.keyJump.setDown(WTap.physicallyDown(minecraft, minecraft.options.keyJump));
                jumpUntilTick = -1;
            } else {
                minecraft.options.keyJump.setDown(true);
            }
            return;
        }
        // A hit from something (a player or mob, or their arrow), not a fall or fire.
        boolean newHit = player.hurtTime > lastHurtTime && player.getLastDamageSource() != null
                && player.getLastDamageSource().getEntity() != null;
        lastHurtTime = player.hurtTime;
        if (!enabled || !newHit || !player.onGround() || player.isInWater() || Mc.screen(minecraft) != null
                || (onlyWhileSprinting && !player.isSprinting()) || !SpearSettings.combatAllowed(minecraft)) {
            return;
        }
        minecraft.options.keyJump.setDown(true);
        jumpUntilTick = player.tickCount + 1;
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

    static boolean isOnlyWhileSprinting() {
        return onlyWhileSprinting;
    }

    static void setOnlyWhileSprinting(boolean on) {
        onlyWhileSprinting = on;
    }

    static void toggleOnlyWhileSprinting() {
        onlyWhileSprinting = !onlyWhileSprinting;
    }

    /** Turns the module off when this injection is unloaded, giving jump back. */
    static void reset(Minecraft minecraft) {
        if (jumpUntilTick >= 0) {
            minecraft.options.keyJump.setDown(WTap.physicallyDown(minecraft, minecraft.options.keyJump));
        }
        enabled = false;
        jumpUntilTick = -1;
    }
}
