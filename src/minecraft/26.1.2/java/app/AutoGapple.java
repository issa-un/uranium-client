package app;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.Items;

/**
 * Auto Gapple: when your health drops to Health or below and you have no Regeneration
 * running (so a gapple is not wasted on top of another), switches to a golden apple in your
 * hotbar (an enchanted one only if there is no plain one), holds use until it is eaten, then
 * switches back. Picking a slot yourself, opening a screen or 2.5 s passing stops it and
 * gives use back to how you hold it.
 */
final class AutoGapple {
    private static final int[] HEALTH = {8, 12, 16, 20};
    private static final long EAT_TIMEOUT_MS = 2500;
    private static final long COOLDOWN_MS = 500;
    private static volatile boolean enabled;
    private static volatile int healthIndex = 1;
    // The meal in progress; render thread only.
    private static int eatingSlot = -1;
    private static int previousSlot = -1;
    private static int countBefore;
    private static long startedMs;
    private static long readyAtMs;

    private AutoGapple() {}

    /** Called from the input loop. */
    static void update(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.gameMode == null) {
            eatingSlot = -1;
            return;
        }
        long now = System.nanoTime() / 1_000_000;
        if (eatingSlot >= 0) {
            continueEating(minecraft, player, now);
            return;
        }
        // Never in the middle of another module's swap: it would cancel it.
        if (ShieldBreak.isRunning() || PearlCatch.isRunning() || MinecraftSpearDash.isDashing()) {
            return;
        }
        if (!enabled || Mc.screen(minecraft) != null || now < readyAtMs || player.isUsingItem()
                || player.getHealth() > HEALTH[healthIndex] || player.hasEffect(MobEffects.REGENERATION)
                || !SpearSettings.combatAllowed(minecraft)) {
            return;
        }
        int slot = BucketUse.findSlot(player, Items.GOLDEN_APPLE);
        if (slot < 0) {
            slot = BucketUse.findSlot(player, Items.ENCHANTED_GOLDEN_APPLE);
        }
        if (slot < 0) {
            return;
        }
        previousSlot = player.getInventory().getSelectedSlot();
        eatingSlot = slot;
        countBefore = player.getInventory().getItem(slot).getCount();
        startedMs = now;
        player.getInventory().setSelectedSlot(slot);
        MinecraftSpearDash.sendSelectedSlot(minecraft, false);
        MinecraftInputController.stopGuard();
        minecraft.options.keyUse.setDown(true);
        MinecraftSpearDash.setStatus("Eating gapple");
    }

    private static void continueEating(Minecraft minecraft, LocalPlayer player, long now) {
        boolean eaten = player.getInventory().getItem(eatingSlot).getCount() < countBefore;
        boolean interrupted = player.getInventory().getSelectedSlot() != eatingSlot || Mc.screen(minecraft) != null;
        if (!eaten && !interrupted && now - startedMs < EAT_TIMEOUT_MS) {
            minecraft.options.keyUse.setDown(true);
            return;
        }
        minecraft.options.keyUse.setDown(WTap.physicallyDown(minecraft, minecraft.options.keyUse));
        if (!interrupted) {
            player.getInventory().setSelectedSlot(previousSlot);
            MinecraftSpearDash.sendSelectedSlot(minecraft, false);
        }
        SpearLog.log("auto gapple: " + (eaten ? "eaten" : interrupted ? "stopped" : "timed out")
                + " at " + player.getHealth() + " health");
        MinecraftSpearDash.setStatus(eaten ? "Gapple eaten" : "Gapple stopped");
        eatingSlot = -1;
        readyAtMs = now + COOLDOWN_MS;
    }

    /** True while it is holding use for a gapple, so Auto Eat waits. */
    static boolean isEating() {
        return eatingSlot >= 0;
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

    static String healthLabel() {
        return HEALTH[healthIndex] / 2 + " hearts";
    }

    static int healthIndex() {
        return healthIndex;
    }

    static void setHealthIndex(int index) {
        healthIndex = Math.floorMod(index, HEALTH.length);
    }

    static void cycleHealth() {
        healthIndex = (healthIndex + 1) % HEALTH.length;
    }

    /** Turns the module off when this injection is unloaded, giving use back mid-meal. */
    static void reset(Minecraft minecraft) {
        if (eatingSlot >= 0) {
            minecraft.options.keyUse.setDown(WTap.physicallyDown(minecraft, minecraft.options.keyUse));
            eatingSlot = -1;
        }
        enabled = false;
    }
}
