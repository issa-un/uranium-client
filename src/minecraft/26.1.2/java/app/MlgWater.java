package app;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;

/**
 * MLG Water: when a fall would hurt you at least as much as the Min damage setting, places
 * water on the ground below you before you land, so you land in water and take no fall
 * damage, then scoops it back up.
 *
 * The water goes down as soon as the ground is within the bucket's reach (4.5 blocks from
 * your eyes, about 3 from your feet), aimed straight down, so it is there before you are.
 * Landing is predicted with the same calculation as the Landing readout. It waits a game
 * tick after you land before scooping, so the server has handled your landing in the water
 * first. Water is not placed over lava or in the Nether, or when the landing is already safe.
 * Your view really turns down to place and to scoop, then back to where you were looking.
 */
final class MlgWater {
    // Min damage choices in half-hearts; 0 means only fatal falls.
    private static final int[] MIN_DAMAGE = {4, 8, 12, 0};
    private static final String[] MIN_DAMAGE_LABELS = {"2 hearts", "4 hearts", "7 hearts", "Fatal only"};
    private static final long LANDING_TIMEOUT_MS = 3000;
    // How long the view stays on the scooped water before turning back, so you see it.
    private static final long VIEW_HOLD_MS = 100;
    private static volatile boolean enabled;
    private static volatile boolean pickUp = true;
    private static volatile int minDamageIndex = 1;
    // The clutch in progress; render thread only.
    private static BlockPos waterAt;
    private static long placedMs;
    private static int landedAtTick = -1;

    private MlgWater() {}

    /** Called from the input loop. */
    static void update(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.gameMode == null || minecraft.level == null) {
            waterAt = null;
            return;
        }
        long now = System.nanoTime() / 1_000_000;
        if (waterAt != null) {
            scoopAfterLanding(minecraft, player, now);
            return;
        }
        if (!enabled || Mc.screen(minecraft) != null || !SpearSettings.combatAllowed(minecraft)
                || !BucketUse.waterWorks(minecraft)) {
            return;
        }
        VelocityHud.Landing landing = VelocityHud.predictLanding(minecraft, player);
        if (landing.kind() != VelocityHud.LandingKind.DAMAGE || !worthIt(player, landing)) {
            return;
        }
        int bucket = BucketUse.findSlot(player, Items.WATER_BUCKET);
        if (bucket < 0) {
            return;
        }
        // Straight down onto the ground you will land on; nothing until it is within reach.
        // Over grass, flowers or snow the bucket hits that cover and puts the water one block
        // higher, which still works: you fall through it on the way down.
        float yaw = player.getYRot();
        BlockPos target = BucketUse.placement(minecraft, player, yaw, 90);
        BlockPos landingSpot = landing.ground().above();
        boolean onLanding = target != null && target.getX() == landingSpot.getX() && target.getZ() == landingSpot.getZ()
                && target.getY() >= landingSpot.getY() && target.getY() <= landingSpot.getY() + 1;
        if (!onLanding || !BucketUse.roomForWater(minecraft, target)) {
            return;
        }
        BucketUse.placeWater(minecraft, bucket, yaw, 90);
        waterAt = target;
        placedMs = now;
        landedAtTick = -1;
        MinecraftSpearDash.setStatus("MLG water");
        SpearLog.log("mlg: water at " + String.format(java.util.Locale.ROOT, "%.1f", landing.drop())
                + " blocks up, saving about " + landing.damage() / 2.0 + " hearts");
    }

    private static boolean worthIt(LocalPlayer player, VelocityHud.Landing landing) {
        int minDamage = MIN_DAMAGE[minDamageIndex];
        return minDamage == 0 ? landing.fatal(player) : landing.damage() >= minDamage;
    }

    private static void scoopAfterLanding(Minecraft minecraft, LocalPlayer player, long now) {
        boolean landed = player.onGround() || player.isInWater();
        if (landed && landedAtTick < 0) {
            landedAtTick = player.tickCount;
        }
        boolean settled = landedAtTick >= 0 && player.tickCount > landedAtTick;
        if (!settled && now - placedMs < LANDING_TIMEOUT_MS) {
            return;
        }
        boolean scooped = pickUp && BucketUse.scoop(minecraft, player, waterAt);
        SpearLog.log("mlg: " + (settled ? "landed" : "no landing seen") + (pickUp
                ? (scooped ? ", water scooped" : ", could not scoop the water") : ""));
        MinecraftSpearDash.setStatus(settled ? "MLG landed" : "MLG: no landing");
        waterAt = null;
        View.release(VIEW_HOLD_MS);
    }

    /** True while its water is down and waiting for you to land in it. */
    static boolean isActive() {
        return waterAt != null;
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

    static boolean isPickUp() {
        return pickUp;
    }

    static void setPickUp(boolean on) {
        pickUp = on;
    }

    static void togglePickUp() {
        pickUp = !pickUp;
    }

    static String minDamageLabel() {
        return MIN_DAMAGE_LABELS[minDamageIndex];
    }

    static int minDamageIndex() {
        return minDamageIndex;
    }

    static void setMinDamageIndex(int index) {
        minDamageIndex = Math.floorMod(index, MIN_DAMAGE.length);
    }

    static void cycleMinDamage() {
        minDamageIndex = (minDamageIndex + 1) % MIN_DAMAGE.length;
    }

    /** Turns the module off when this injection is unloaded. */
    static void reset() {
        enabled = false;
        waterAt = null;
    }
}
