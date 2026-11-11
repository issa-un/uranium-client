package app;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.EntityHitResult;

/**
 * Hit: see hitbox, hit. Something living under your crosshair and in reach (not a friend or
 * an armor stand) gets hit, as soon as a hit will count: not still immune from its last hit,
 * and either your attack cooldown full or you falling into a mace slam (the slam's bonus is
 * never cut by the cooldown). A slam that needs Mace Swap waits until the fall is long
 * enough for Mace Swap to bring the mace out (MaceSwap.WORTH_FALL); hitting sooner would
 * go out with what you hold and leave the target immune to the real slam. The hit goes out like your own click, so Mace Swap, Auto Weapon
 * and Shield Break's Any attack trigger still act on it. Chance is how many of those moments
 * it takes; a moment it skips waits SKIP_MS before the next try.
 */
final class Hit {
    // A plain hit waits for this much cooldown; a slam goes at once.
    private static final float FULL = 0.95F;
    // A slam on a target we slammed this recently would land in its immunity.
    private static final long OWN_SLAM_IMMUNITY_MS = 500;
    private static final long SKIP_MS = 250;
    static final Num CHANCE = Num.percent(100);
    private static volatile boolean enabled;
    // Render thread only.
    private static long nextTryMs;
    private static int lastSlamTargetId = -1;
    private static long lastSlamMs;

    private Hit() {}

    /** Called from the input loop: one hit if this is a moment where it counts. */
    static void update(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (!enabled || player == null || minecraft.gameMode == null || Mc.screen(minecraft) != null
                || !SpearSettings.combatAllowed(minecraft) || player.isUsingItem()
                || ShieldBreak.isRunning() || PearlCatch.isRunning()) {
            return;
        }
        if (!(minecraft.hitResult instanceof EntityHitResult result)
                || !(result.getEntity() instanceof LivingEntity target)
                || !target.isAlive() || target instanceof ArmorStand
                || target instanceof Player other && VelocityHud.isFriend(other)) {
            return;
        }
        long now = System.nanoTime() / 1_000_000;
        if (now < nextTryMs) {
            return;
        }
        int ready = ready(minecraft, player, target, now);
        if (ready < 0) {
            return;
        }
        if (!CHANCE.roll()) {
            nextTryMs = now + SKIP_MS;
            return;
        }
        strike(minecraft, player, target, ready == SLAM, now);
    }

    static final int PLAIN = 0;
    static final int SLAM = 1;

    /**
     * Whether a hit on the target would count right now: -1 not yet, PLAIN for a normal hit
     * with a full cooldown, SLAM for a mace slam. Shared with World Slash.
     */
    static int ready(Minecraft minecraft, LocalPlayer player, LivingEntity target, long now) {
        boolean holdsMace = player.getMainHandItem().is(Items.MACE);
        boolean swapComing = !holdsMace && MaceSwap.isEnabled() && MaceSwap.bestMace(player) >= 0;
        if (swapComing && MaceSwap.isSlam(player) && player.fallDistance < MaceSwap.WORTH_FALL) {
            return -1; // falling into a slam that Mace Swap will bring the mace out for: not yet
        }
        boolean slam = MaceSwap.isSlam(player) && (holdsMace || swapComing);
        if (slam) {
            return target.getId() == lastSlamTargetId && now - lastSlamMs < OWN_SLAM_IMMUNITY_MS ? -1 : SLAM;
        }
        return ShieldBreak.immuneWhenHitArrives(minecraft, target) || player.getAttackStrengthScale(0.5F) < FULL
                ? -1 : PLAIN;
    }

    /** One hit, like your own click (so Mace Swap, Auto Weapon and Shield Break act on it). */
    static void strike(Minecraft minecraft, LocalPlayer player, LivingEntity target, boolean slam, long now) {
        minecraft.gameMode.attack(player, target);
        Mc.swingAttack(player, InteractionHand.MAIN_HAND);
        if (slam) {
            lastSlamTargetId = target.getId();
            lastSlamMs = now;
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

    /** Turns the module off when this injection is unloaded. */
    static void reset() {
        enabled = false;
        lastSlamTargetId = -1;
    }
}
