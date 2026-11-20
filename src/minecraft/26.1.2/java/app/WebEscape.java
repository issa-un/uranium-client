package app;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/**
 * Web Escape: when you are stuck in a cobweb, or on fire, places water from your hotbar and
 * scoops it straight back up once the web is gone or the fire is out.
 *
 * Water put straight into a cobweb destroys it at once; flowing water also destroys webs,
 * but it spreads only every 5 ticks (0.25 s). A bucket used from inside a web always puts
 * its water next to that web, never into it (see BucketUse), so:
 * - Web at your feet only: water goes in the block your head is in and flows down, 0.25 s.
 * - Web at your head: water goes above your head and flows down through the head web, 0.25 s,
 *   then through a web at your feet, another 0.25 s.
 * - Webs at head and feet with two water buckets: one goes straight into the feet web (aimed
 *   up from inside the head web, which puts it in the block below), clearing it at once,
 *   and the other above your head, so both are gone after 0.25 s.
 * If webs still touch you once the water should have reached them (a web trap), it scoops
 * and goes again, up to three times. On fire, water goes at your feet and comes back up as
 * soon as the fire is out. Nothing happens in the Nether, where water boils away. Your view
 * really turns to place and to scoop, then back to where you were looking.
 */
final class WebEscape {
    private static final int SPREAD_MS = 250;
    // On top of the spreads: your ping both ways for the server to place and the update to arrive.
    private static final long MARGIN_MS = 150;
    private static final long FIRE_TIMEOUT_MS = 1000;
    private static final int MAX_TRIES = 3;
    // After an escape, so a web you are still brushing does not start another at once.
    private static final long COOLDOWN_MS = 400;
    // Nothing to do (no bucket, Nether, no room): wait this long before checking again.
    private static final long RETRY_MS = 2000;
    // How long the view stays on the scooped water before turning back, so you see it.
    private static final long VIEW_HOLD_MS = 100;
    private static volatile boolean enabled;
    private static volatile boolean pickUp = true;
    private static volatile boolean onFire = true;
    // The escape in progress; render thread only.
    private static final List<BlockPos> WATER = new ArrayList<>();
    private static boolean forFire;
    private static long placedMs;
    private static long deadlineMs;
    private static int tries;
    private static long readyAtMs;

    private WebEscape() {}

    /** Called from the input loop. */
    static void update(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.gameMode == null || minecraft.level == null) {
            WATER.clear();
            return;
        }
        long now = System.nanoTime() / 1_000_000;
        if (!WATER.isEmpty()) {
            finishIfDone(minecraft, player, now);
            return;
        }
        if (!enabled || Mc.screen(minecraft) != null || now < readyAtMs || !SpearSettings.combatAllowed(minecraft)) {
            return;
        }
        List<BlockPos> webs = websAround(minecraft, player);
        if (!webs.isEmpty()) {
            escapeWeb(minecraft, player, webs, now);
        } else if (onFire && player.isOnFire() && !player.isInWater() && !player.isInLava()) {
            putOutFire(minecraft, player, now);
        } else {
            tries = 0;
        }
    }

    private static void finishIfDone(Minecraft minecraft, LocalPlayer player, long now) {
        boolean done = forFire ? !player.isOnFire() : websAround(minecraft, player).isEmpty();
        if (!done && now < deadlineMs) {
            return;
        }
        int scooped = pickUp ? BucketUse.scoopAll(minecraft, player, WATER) : 0;
        String what = forFire ? (done ? "fire out" : "still burning") : (done ? "web gone" : "webs still touching");
        SpearLog.log((forFire ? "fire" : "web escape") + ": " + what + " " + (now - placedMs) + " ms after the water"
                + (pickUp ? ", scooped " + scooped + " of " + WATER.size() : "") + ", try " + tries);
        SpearDash.setStatus(forFire ? (done ? "Fire out" : "Still burning")
                : (done ? "Web cleared" : "Webs left, again"));
        WATER.clear();
        View.release(VIEW_HOLD_MS);
        // A trap with more webs: go again straight away, a few times.
        readyAtMs = !done && !forFire && tries < MAX_TRIES ? now : now + COOLDOWN_MS;
        if (done) {
            tries = 0;
        }
    }

    private static void escapeWeb(Minecraft minecraft, LocalPlayer player, List<BlockPos> webs, long now) {
        if (!BucketUse.waterWorks(minecraft)) {
            giveUp(now, "Water boils away in the Nether");
            return;
        }
        List<Integer> buckets = BucketUse.slotsWith(player, Items.WATER_BUCKET);
        if (buckets.isEmpty()) {
            giveUp(now, "No water bucket in hotbar");
            return;
        }
        float yaw = player.getYRot();
        BlockPos head = BlockPos.containing(player.getEyePosition());
        boolean headWeb = webs.contains(head);
        boolean feetWeb = webs.contains(head.below());
        int spreads = headWeb && feetWeb ? 2 : 1;
        tries++;
        if (headWeb && feetWeb && buckets.size() >= 2) {
            // Aimed up from inside the head web, the water lands in the block below: the feet web.
            BlockPos intoFeet = BucketUse.placement(minecraft, player, yaw, -90);
            if (head.below().equals(intoFeet) && BucketUse.roomForWater(minecraft, intoFeet)) {
                BucketUse.placeWater(minecraft, buckets.get(0), yaw, -90);
                WATER.add(intoFeet);
                buckets = buckets.subList(1, buckets.size());
                spreads = 1;
            }
        }
        BlockPos target = BucketUse.placement(minecraft, player, yaw, 90);
        if (target == null || !BucketUse.roomForWater(minecraft, target)) {
            if (WATER.isEmpty()) {
                giveUp(now, "No room for water");
                return;
            }
        } else {
            BucketUse.placeWater(minecraft, buckets.get(0), yaw, 90);
            WATER.add(target);
        }
        forFire = false;
        placedMs = now;
        deadlineMs = now + (long) spreads * SPREAD_MS + 2L * AutoStun.pingMs(minecraft) + MARGIN_MS;
        SpearDash.setStatus("Clearing web...");
    }

    private static void putOutFire(Minecraft minecraft, LocalPlayer player, long now) {
        if (!BucketUse.waterWorks(minecraft)) {
            giveUp(now, "Water boils away in the Nether");
            return;
        }
        int bucket = BucketUse.findSlot(player, Items.WATER_BUCKET);
        if (bucket < 0) {
            giveUp(now, "No water bucket in hotbar");
            return;
        }
        float yaw = player.getYRot();
        BlockPos target = BucketUse.placement(minecraft, player, yaw, 90);
        if (target == null || !BucketUse.roomForWater(minecraft, target)) {
            giveUp(now, "No room for water");
            return;
        }
        BucketUse.placeWater(minecraft, bucket, yaw, 90);
        WATER.add(target);
        forFire = true;
        placedMs = now;
        deadlineMs = now + FIRE_TIMEOUT_MS;
        SpearDash.setStatus("Putting fire out...");
    }

    private static void giveUp(long now, String reason) {
        SpearDash.setStatus(reason);
        readyAtMs = now + RETRY_MS;
        tries = 0;
    }

    /** The cobwebs your body is touching. */
    private static List<BlockPos> websAround(Minecraft minecraft, LocalPlayer player) {
        List<BlockPos> webs = new ArrayList<>();
        AABB body = player.getBoundingBox().deflate(1.0E-3);
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(body.minX, body.minY, body.minZ),
                BlockPos.containing(body.maxX, body.maxY, body.maxZ))) {
            if (minecraft.level.getBlockState(pos).is(Blocks.COBWEB)) {
                webs.add(pos.immutable());
            }
        }
        return webs;
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

    static boolean isOnFire() {
        return onFire;
    }

    static void setOnFire(boolean on) {
        onFire = on;
    }

    static void toggleOnFire() {
        onFire = !onFire;
    }

    /** Turns the module off when this injection is unloaded. */
    static void reset() {
        enabled = false;
        WATER.clear();
        tries = 0;
    }
}
