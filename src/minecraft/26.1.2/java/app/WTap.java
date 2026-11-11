package app;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.EntityHitResult;
import org.lwjgl.glfw.GLFW;

/**
 * W-Tap: after a hit you land, lets go of forward for a moment (W-tap) or taps back
 * (S-tap), then puts the keys back to how you are holding them. A sprint hit stops your
 * sprint and slows you to 60%; letting go of forward and pressing it again restarts the
 * sprint (with sprint held, or as a double-tap), so the next hit is a sprint hit with its
 * extra knockback, and the step back keeps your distance. It starts the game tick after your
 * click, once the hit has gone out, plus Reaction; the tap lasts Length, both random times
 * in their ranges, and Chance decides how many hits get one. With Trigger on Any attack,
 * every attack your client sends counts (see AttackHook), not only your clicks.
 */
final class WTap {
    private static final String[] MODES = {"W-tap", "S-tap"};
    private static volatile boolean enabled;
    private static volatile int mode;
    // Hit -> keys let go; how long they stay let go.
    static final Num REACTION = Num.ms(0, 0, 300);
    static final Num LENGTH = Num.ms(100, 100, 500);
    static final Num CHANCE = Num.percent(100);
    private static volatile boolean onlySprintHits = true;
    // Any attack: every attack your client sends, caught by AttackHook, instead of your click.
    private static volatile boolean anyAttack;
    // Sprinting as of the last loop, for attacks caught on the network thread: by then a
    // sprint hit may already have ended the sprint.
    private static volatile boolean sprintingBeforeHit;
    // Render thread only.
    private static int clickTick = -1;
    private static boolean clickWasSprint;
    private static long tapAtMs = -1;
    private static long tapUntilMs = -1;

    private WTap() {}

    /** Your attack click: remember it if it lands on something living. */
    static void onAttackClick(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (enabled && !anyAttack && player != null && Mc.screen(minecraft) == null && SpearSettings.combatAllowed(minecraft)
                && minecraft.hitResult instanceof EntityHitResult hit && hit.getEntity() instanceof LivingEntity) {
            clickTick = player.tickCount;
            clickWasSprint = player.isSprinting();
        }
    }

    /** True while a tap holds forward up (Auto Walk waits). */
    static boolean isTapping() {
        return tapUntilMs >= 0;
    }

    static boolean watchesAnyAttack() {
        return enabled && anyAttack;
    }

    static boolean wasSprinting() {
        return sprintingBeforeHit;
    }

    /** An attack on something living caught by AttackHook, on the render thread. */
    static void onAnyAttack(Minecraft minecraft, boolean wasSprinting) {
        LocalPlayer player = minecraft.player;
        if (enabled && anyAttack && player != null && Mc.screen(minecraft) == null
                && SpearSettings.combatAllowed(minecraft)) {
            clickTick = player.tickCount;
            clickWasSprint = wasSprinting;
        }
    }

    /** Called from the input loop. */
    static void update(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player == null) {
            clickTick = -1;
            tapAtMs = -1;
            tapUntilMs = -1;
            return;
        }
        sprintingBeforeHit = player.isSprinting();
        long now = System.nanoTime() / 1_000_000;
        if (tapUntilMs >= 0) {
            if (now >= tapUntilMs || Mc.screen(minecraft) != null) {
                restoreKeys(minecraft);
                tapUntilMs = -1;
            } else {
                holdTap(minecraft);
            }
            return;
        }
        // The click turns into a hit during the next game tick.
        if (clickTick >= 0 && player.tickCount > clickTick) {
            boolean tap = (!onlySprintHits || clickWasSprint) && CHANCE.roll();
            clickTick = -1;
            if (tap) {
                tapAtMs = now + REACTION.pick();
            }
        }
        if (tapAtMs >= 0 && Mc.screen(minecraft) != null) {
            tapAtMs = -1;
        } else if (tapAtMs >= 0 && now >= tapAtMs) {
            tapAtMs = -1;
            holdTap(minecraft);
            tapUntilMs = now + LENGTH.pick();
        }
    }

    private static void holdTap(Minecraft minecraft) {
        minecraft.options.keyUp.setDown(false);
        if (mode == 1) {
            minecraft.options.keyDown.setDown(true);
        }
    }

    private static void restoreKeys(Minecraft minecraft) {
        minecraft.options.keyUp.setDown(physicallyDown(minecraft, minecraft.options.keyUp));
        minecraft.options.keyDown.setDown(physicallyDown(minecraft, minecraft.options.keyDown));
    }

    /** Whether you are holding the key or button bound to the mapping right now. */
    static boolean physicallyDown(Minecraft minecraft, KeyMapping mapping) {
        InputConstants.Key key = InputConstants.getKey(mapping.saveString());
        if (key.getValue() < 0) {
            return false; // bound to nothing
        }
        if (key.getType() == InputConstants.Type.MOUSE) {
            return GLFW.glfwGetMouseButton(minecraft.getWindow().handle(), key.getValue()) == GLFW.GLFW_PRESS;
        }
        return key.getType() == Mc.keyboard() && Mc.isKeyDown(minecraft, key.getValue());
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

    static String modeLabel() {
        return MODES[mode];
    }

    static int mode() {
        return mode;
    }

    static void setMode(int newMode) {
        mode = Math.floorMod(newMode, MODES.length);
    }

    static void cycleMode() {
        mode = (mode + 1) % MODES.length;
    }

    static boolean isOnlySprintHits() {
        return onlySprintHits;
    }

    static void setOnlySprintHits(boolean on) {
        onlySprintHits = on;
    }

    static boolean isAnyAttack() {
        return anyAttack;
    }

    static void setAnyAttack(boolean on) {
        anyAttack = on;
    }

    static void toggleAnyAttack() {
        anyAttack = !anyAttack;
    }

    static String triggerLabel() {
        return AttackHook.triggerLabel(anyAttack);
    }

    static void toggleOnlySprintHits() {
        onlySprintHits = !onlySprintHits;
    }

    /** Turns the module off when this injection is unloaded, giving the keys back. */
    static void reset(Minecraft minecraft) {
        if (tapUntilMs >= 0) {
            restoreKeys(minecraft);
        }
        enabled = false;
        clickTick = -1;
        tapAtMs = -1;
        tapUntilMs = -1;
    }
}
