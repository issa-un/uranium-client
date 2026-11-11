package app;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.EntityHitResult;

/**
 * Slam Follow-up: the "slam then sword" combo. After you slam someone, their hurt immunity
 * blocks any hit for half a second. The moment it ends, while they are still under your
 * crosshair and in reach, Slam Follow-up pulls out your sword, hits, and goes back to what
 * you held, so a slam is followed by a full sword hit at the earliest tick it can count.
 * The sword stays in your hand for SHOW_MS so the swap is a real one you can see. It skips
 * when you are falling into another slam (that one hits harder) or no sword is in your
 * hotbar.
 */
final class SlamFollowUp {
    /** Your sword's cooldown is back to full this long after the slam. */
    private static final long READY_MS = 575;
    private static final long GIVE_UP_MS = 1500;
    private static final long SHOW_MS = 150;
    private static volatile boolean enabled;
    // Set on the attack's thread, read on the input loop.
    private static volatile int slamTargetId = -1;
    private static volatile long slamMs;
    // Input loop only.
    private static int backToSlot = -1;
    private static long backAtMs;

    private SlamFollowUp() {}

    /** A slam just went out (from MaceLog, on the thread that sends it). */
    static void onSlam(LivingEntity target) {
        if (enabled) {
            slamTargetId = target.getId();
            slamMs = nowMs();
        }
    }

    /** Called from the input loop. */
    static void update(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        long now = nowMs();
        if (backToSlot >= 0 && now >= backAtMs) {
            if (player != null) {
                select(minecraft, backToSlot);
            }
            backToSlot = -1;
        }
        int id = slamTargetId;
        if (id < 0 || player == null || minecraft.level == null || minecraft.gameMode == null) {
            return;
        }
        if (!enabled || now - slamMs > GIVE_UP_MS || Mc.screen(minecraft) != null
                || !SpearSettings.combatAllowed(minecraft)) {
            slamTargetId = -1;
            return;
        }
        if (now - slamMs < READY_MS || MaceSwap.isSlam(player) || ShieldBreak.isRunning()) {
            return;
        }
        Entity entity = minecraft.level.getEntity(id);
        if (!(entity instanceof LivingEntity target) || !target.isAlive()) {
            slamTargetId = -1;
            return;
        }
        if (ShieldBreak.immuneWhenHitArrives(minecraft, target) || target.isBlocking()
                || !(minecraft.hitResult instanceof EntityHitResult hit) || hit.getEntity() != target) {
            return;
        }
        int sword = swordSlot(player);
        if (sword < 0) {
            slamTargetId = -1;
            return;
        }
        slamTargetId = -1;
        int previous = player.getInventory().getSelectedSlot();
        select(minecraft, sword);
        Hit.strike(minecraft, player, target, false, now);
        if (previous != sword) {
            backToSlot = previous;
            backAtMs = now + SHOW_MS;
        }
        SpearLog.log("slam follow-up: sword hit on " + target.getName().getString() + " " + (now - slamMs)
                + " ms after the slam");
    }

    private static int swordSlot(LocalPlayer player) {
        for (int slot = 0; slot < 9; slot++) {
            if (player.getInventory().getItem(slot).is(ItemTags.SWORDS)) {
                return slot;
            }
        }
        return -1;
    }

    private static void select(Minecraft minecraft, int slot) {
        if (minecraft.player.getInventory().getSelectedSlot() != slot) {
            minecraft.player.getInventory().setSelectedSlot(slot);
            MinecraftSpearDash.sendSelectedSlot(minecraft, false);
        }
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
        slamTargetId = -1;
        backToSlot = -1;
    }
}
