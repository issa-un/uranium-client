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
 * Trigger Bot: see hitbox, hit. Something living under your crosshair and in reach (not a friend or
 * an armor stand) gets hit, as soon as a hit will count: not still immune from its last hit,
 * and either your attack cooldown full or you falling into a mace slam (the slam's bonus is
 * never cut by the cooldown). A slam that needs Mace Swap waits until the fall is long
 * enough for Mace Swap to bring the mace out (MaceSwap.WORTH_FALL); hitting sooner would
 * go out with what you hold and leave the target immune to the real slam. The hit goes out like your own click, so Mace Swap, Auto Weapon
 * and Auto Stun's Any attack trigger still act on it. Chance is how many of those moments
 * it takes; a moment it skips waits SKIP_MS before the next try. Random delay holds each
 * plain hit a new random time first, so no two hits come the same ms after they could.
 */
final class TriggerBot {
    // A plain hit waits for this much cooldown; a slam goes at once.
    private static final float FULL = 0.95F;
    // A slam on a target we slammed this recently would land in its immunity.
    private static final long OWN_SLAM_IMMUNITY_MS = 500;
    private static final long SKIP_MS = 250;
    static final Num CHANCE = Num.percent(100);
    // Random delay: each plain hit waits a fresh time in this range after it could go.
    static final Num DELAY = Num.ms(80, 160, 400);
    private static volatile boolean enabled;
    private static volatile boolean randomDelay;
    // Auto: Trigger Bot only acts while you look like you're fighting (see fighting()).
    private static volatile boolean auto;
    // Auto's behavior (right-click Auto in the menu): items that turn it off while held, and
    // which signs of a fight count.
    static final int WIND = 1, PEARL = 2, FOOD = 4, BLOCKS = 8, BOW = 16, POTION = 32;
    static final int S_JUMP = 1, S_SWITCH = 2, S_SWING = 4, S_HURT = 8, S_CHASE = 16;
    private static volatile int offHolding = WIND | PEARL | FOOD;
    private static volatile int signsOn = S_JUMP | S_SWITCH | S_SWING | S_HURT | S_CHASE;
    private static volatile boolean autoOpen;
    // How long a sign of a fight keeps Auto on after it, so it doesn't flicker.
    private static final long SIGN_MS = 2500;
    // A player this close and this near your crosshair counts as "looking at someone".
    private static final double LOOK_RANGE = 8;
    private static final double LOOK_ANGLE = 25;
    private static long lookedMs;
    private static long jumpedMs;
    private static long switchedMs;
    private static long swungMs;
    private static long hurtMs;
    private static long chasedMs;
    private static int lastSlot = -1;
    private static boolean wasFighting;
    // A plain hit waiting out its random delay (-1: none), on that target.
    private static double pendingAtMs = -1;
    private static double pendingDelayMs;
    private static int pendingTargetId = -1;
    // Render thread only.
    private static long nextTryMs;
    private static int lastSlamTargetId = -1;
    private static long lastSlamMs;

    private TriggerBot() {}

    /** Called from the input loop: one hit if this is a moment where it counts. */
    static void update(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (enabled && auto && player != null && !fighting(minecraft, player)) {
            pendingAtMs = -1;
            return;
        }
        if (!enabled || player == null || minecraft.gameMode == null || Mc.screen(minecraft) != null
                || !Settings.combatAllowed(minecraft) || player.isUsingItem()
                || AutoStun.isRunning() || PearlCatch.isRunning()
                || CrossbowCart.isBusy() || BowCart.isBusy() || AntiCart.isBusy() || CartRefill.isBusy()) {
            return;
        }
        if (!(minecraft.hitResult instanceof EntityHitResult result)
                || !(result.getEntity() instanceof LivingEntity target)
                || !target.isAlive() || target instanceof ArmorStand
                || target instanceof Player other && UraniumHud.isFriend(other)) {
            pendingAtMs = -1;
            return;
        }
        long now = System.nanoTime() / 1_000_000;
        int ready = now < nextTryMs ? -1 : ready(minecraft, player, target, now);
        if (pendingAtMs >= 0) {
            if (ready != PLAIN || target.getId() != pendingTargetId) {
                pendingAtMs = -1; // the hit stopped counting, or someone else is under the crosshair
            } else if (System.nanoTime() / 1.0E6 >= pendingAtMs) {
                pendingAtMs = -1;
                strike(minecraft, player, target, false, now);
                UraniumLog.log(String.format(java.util.Locale.ROOT, "hit: went %.1f ms after it could", pendingDelayMs));
            }
            return;
        }
        if (ready < 0) {
            return;
        }
        if (!CHANCE.roll()) {
            nextTryMs = now + SKIP_MS;
            return;
        }
        if (randomDelay && ready == PLAIN && !DELAY.isZero()) {
            // A new wait every hit, to a tenth of a ms (100 ms, then 142.1, ...). Slams don't
            // wait: you would land before the hit.
            pendingDelayMs = Math.round((PlayStyle.delay(DELAY.pick()) + java.util.concurrent.ThreadLocalRandom.current().nextDouble()) * 10) / 10.0;
            pendingAtMs = System.nanoTime() / 1.0E6 + pendingDelayMs;
            pendingTargetId = target.getId();
            return;
        }
        strike(minecraft, player, target, ready == SLAM, now);
    }

    static final int PLAIN = 0;
    static final int SLAM = 1;

    /**
     * Whether a hit on the target would count right now: -1 not yet, PLAIN for a normal hit
     * with a full cooldown, SLAM for a mace slam. Shared with Kill Aura.
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
        return AutoStun.immuneWhenHitArrives(minecraft, target) || player.getAttackStrengthScale(0.5F) < FULL
                ? -1 : PLAIN;
    }

    /** One hit, like your own click (so Mace Swap, Auto Weapon and Auto Stun act on it). */
    static void strike(Minecraft minecraft, LocalPlayer player, LivingEntity target, boolean slam, long now) {
        Debug.click(slam ? "slam hit" : "hit");
        minecraft.gameMode.attack(player, target);
        Mc.swingAttack(player, InteractionHand.MAIN_HAND);
        if (slam) {
            lastSlamTargetId = target.getId();
            lastSlamMs = now;
        }
    }

    /** Called every frame by the HUD: lets a waiting hit go on time instead of on the next loop. */
    static void frame(Minecraft minecraft) {
        if (pendingAtMs >= 0 && System.nanoTime() / 1.0E6 >= pendingAtMs) {
            update(minecraft);
        }
    }

    /**
     * Auto: reads what you're doing. Holding a wind charge: off (you're about to throw it).
     * Otherwise on while you look at a player (near your crosshair, close) and show at least
     * one sign of a fight in the last few seconds: jumping, switching hotbar slots (scroll
     * or keys), swinging yourself, getting hit by a player, or sprinting at them.
     */
    private static boolean fighting(Minecraft minecraft, LocalPlayer player) {
        long now = System.nanoTime() / 1_000_000;
        int slot = player.getInventory().getSelectedSlot();
        if (lastSlot >= 0 && slot != lastSlot) {
            switchedMs = now;
        }
        lastSlot = slot;
        if (minecraft.options.keyJump.isDown() && !player.onGround() || player.getDeltaMovement().y > 0.2) {
            jumpedMs = now;
        }
        if (WTap.physicallyDown(minecraft, minecraft.options.keyAttack)) {
            swungMs = now;
        }
        if (player.hurtTime > 0 && player.getLastHurtByMob() instanceof Player) {
            hurtMs = now;
        }
        Player near = nearestLooked(minecraft, player);
        if (near != null) {
            lookedMs = now;
            net.minecraft.world.phys.Vec3 to = near.position().subtract(player.position());
            net.minecraft.world.phys.Vec3 move = player.getDeltaMovement();
            if (player.isSprinting() && move.x * to.x + move.z * to.z > 0) {
                chasedMs = now;
            }
        }
        String holding = heldOff(player.getMainHandItem());
        boolean windCharge = holding != null;
        java.util.List<String> signs = new java.util.ArrayList<>();
        if (sign(S_JUMP) && now - jumpedMs < SIGN_MS) signs.add("jumping");
        if (sign(S_SWITCH) && now - switchedMs < SIGN_MS) signs.add("switching slots");
        if (sign(S_SWING) && now - swungMs < SIGN_MS) signs.add("swinging");
        if (sign(S_HURT) && now - hurtMs < SIGN_MS) signs.add("got hit");
        if (sign(S_CHASE) && now - chasedMs < SIGN_MS) signs.add("sprinting at them");
        boolean fighting = !windCharge && now - lookedMs < SIGN_MS && !signs.isEmpty();
        if (fighting != wasFighting) {
            wasFighting = fighting;
            UraniumLog.log("trigger bot auto: " + (fighting ? "on (" + String.join(", ", signs) + ")"
                    : "off (" + (windCharge ? holding + " in hand" : now - lookedMs >= SIGN_MS
                    ? "nobody in front of you" : "no signs of a fight") + ")"));
            SpearDash.setStatus(fighting ? "Trigger Bot on" : "Trigger Bot off");
        }
        return fighting;
    }

    /** The closest other player near your crosshair and within LOOK_RANGE (friends don't count). */
    private static Player nearestLooked(Minecraft minecraft, LocalPlayer player) {
        net.minecraft.world.phys.Vec3 eye = player.getEyePosition();
        net.minecraft.world.phys.Vec3 look = player.getViewVector(1.0F);
        Player best = null;
        double bestDistance = LOOK_RANGE;
        for (Player other : minecraft.level.players()) {
            if (other == player || !other.isAlive() || other.isSpectator() || UraniumHud.isFriend(other)) {
                continue;
            }
            net.minecraft.world.phys.Vec3 to = other.getBoundingBox().getCenter().subtract(eye);
            double distance = to.length();
            if (distance >= bestDistance) {
                continue;
            }
            double angle = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, look.dot(to.normalize())))));
            if (angle <= LOOK_ANGLE) {
                best = other;
                bestDistance = distance;
            }
        }
        return best;
    }

    /** What in your hand turns Auto off, by the Off holding settings; null if nothing. */
    private static String heldOff(net.minecraft.world.item.ItemStack stack) {
        if (offHolding(WIND) && stack.is(Items.WIND_CHARGE)) return "wind charge";
        if (offHolding(PEARL) && stack.is(Items.ENDER_PEARL)) return "ender pearl";
        if (offHolding(FOOD) && stack.has(net.minecraft.core.component.DataComponents.FOOD)) return "food";
        if (offHolding(BLOCKS) && stack.getItem() instanceof net.minecraft.world.item.BlockItem) return "a block";
        if (offHolding(BOW) && (stack.is(Items.BOW) || stack.is(Items.CROSSBOW))) return "a bow";
        if (offHolding(POTION) && (stack.is(Items.POTION) || stack.is(Items.SPLASH_POTION)
                || stack.is(Items.LINGERING_POTION))) return "a potion";
        return null;
    }

    static boolean offHolding(int item) {
        return (offHolding & item) != 0;
    }

    static void flipOffHolding(int item) {
        offHolding ^= item;
    }

    static int offHoldingMask() {
        return offHolding;
    }

    static void setOffHoldingMask(int mask) {
        offHolding = mask;
    }

    static boolean sign(int s) {
        return (signsOn & s) != 0;
    }

    static void flipSign(int s) {
        signsOn ^= s;
    }

    static int signsMask() {
        return signsOn;
    }

    static void setSignsMask(int mask) {
        signsOn = mask;
    }

    static boolean isAutoOpen() {
        return autoOpen;
    }

    static void toggleAutoOpen() {
        autoOpen = !autoOpen;
    }

    static boolean isAuto() {
        return auto;
    }

    static void setAuto(boolean on) {
        auto = on;
    }

    static void toggleAuto() {
        auto = !auto;
    }

    static boolean isRandomDelay() {
        return randomDelay;
    }

    static void setRandomDelay(boolean on) {
        randomDelay = on;
    }

    static void toggleRandomDelay() {
        randomDelay = !randomDelay;
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
        lastSlot = -1;
        wasFighting = false;
        lastSlamTargetId = -1;
        pendingAtMs = -1;
    }
}
