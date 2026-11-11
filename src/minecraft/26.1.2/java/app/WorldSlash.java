package app;

import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * World Slash: a quiet kill aura. It turns your real view onto the closest player in front of
 * you and hits them when a hit counts. Made to look like a person playing, not a bot:
 * - Your camera really turns (nothing is faked in packets), so what the server sees is what
 *   you see. It turns at a steady Rotation speed that wobbles a bit and slows down as it gets
 *   close, and aims at a spot on them that drifts around instead of one fixed point.
 * - It only hits once your crosshair is actually on them, after a human Reaction delay, so
 *   every hit passes the server's own look and reach checks. Never through walls.
 * - Hits only when they count (full cooldown, not immune, or a mace slam), the same rules as
 *   Hit. Chance skips some of them.
 * - Only players inside FOV in front of you, within Range (vanilla reach is 3). It sticks to
 *   one target and does not flick between people.
 * - Pauses while you eat, block, use an item or have a menu open. Friends are never hit.
 */
final class WorldSlash {
    private static final double MIN_RANGE = 1;
    private static final int MIN_FOV = 30;
    /** How long it keeps the same target before it may switch to someone closer. */
    private static final long STICK_MS = 600;
    /** Below this many degrees off, the turn slows down like a hand settling. */
    private static final double SETTLE_DEGREES = 12;
    private static final double SLOWEST_SHARE = 0.3;
    private static final double WOBBLE = 0.25;
    private static final double INSET = 0.2;
    private static final double DRIFT = 0.15;
    private static final long SKIP_MS = 250;
    private static final long LOG_GAP_MS = 1000;
    static final Num RANGE = Num.tenths(3.0, 4.5, " blocks");
    static final Num SPEED = Num.value(300, 1200, 10, "°/s");
    static final Num FOV = Num.value(90, 360, 10, "°");
    static final Num REACTION = Num.ms(70, 150, 500);
    static final Num CHANCE = Num.percent(90);
    private static volatile boolean enabled;
    private static volatile boolean mobs;
    // Render thread only.
    private static LivingEntity target;
    private static long targetSinceMs;
    private static long lastStepMs;
    private static long onTargetSinceMs = -1;
    private static long reactionMs;
    private static long nextTryMs;
    private static double driftX;
    private static double driftY;
    private static double driftZ;
    private static long nextDriftMs;
    private static double wobble = 1;
    private static String lastNoteKind = "";
    private static long lastNoteMs;

    private WorldSlash() {}

    /** Called every frame by the HUD and from the input loop: one turn step and maybe a hit. */
    static void update(Minecraft minecraft) {
        long now = System.nanoTime() / 1_000_000;
        long elapsed = lastStepMs == 0 ? 0 : now - lastStepMs;
        lastStepMs = now;
        LocalPlayer player = minecraft.player;
        if (!enabled || player == null || minecraft.level == null || minecraft.gameMode == null
                || Mc.screen(minecraft) != null || !SpearSettings.combatAllowed(minecraft)) {
            drop();
            return;
        }
        if (player.isUsingItem() || player.isBlocking() || ShieldBreak.isRunning() || PearlCatch.isRunning()
                || MinecraftSpearDash.isDashing()) {
            onTargetSinceMs = -1;
            return;
        }
        float partial = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        Vec3 eye = player.getEyePosition(partial);
        Vec3 look = player.getViewVector(partial);
        double range = Math.max(MIN_RANGE, RANGE.tenths());
        double fovHalf = Math.max(MIN_FOV, FOV.low()) / 2.0;
        if (target != null && (!valid(player, target) || reach(eye, box(target, partial)) > range
                || angleOff(player, eye, box(target, partial).getCenter()) > fovHalf * 1.5)) {
            drop();
        }
        if (target == null || now - targetSinceMs > STICK_MS) {
            LivingEntity pick = pick(minecraft, player, eye, look, range, fovHalf, partial);
            if (pick != null && pick != target) {
                target = pick;
                targetSinceMs = now;
                onTargetSinceMs = -1;
                note("slashing " + target.getName().getString(), now);
            }
        }
        if (target == null) {
            return;
        }
        turn(player, eye, look, box(target, partial), elapsed, now);
        tryHit(minecraft, player, now);
    }

    /** One smooth step of the camera toward the drifting spot on them. */
    private static void turn(LocalPlayer player, Vec3 eye, Vec3 look, AABB box, long elapsed, long now) {
        if (now >= nextDriftMs) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            driftX = random.nextDouble(-DRIFT, DRIFT);
            driftY = random.nextDouble(-DRIFT * 2, DRIFT * 2);
            driftZ = random.nextDouble(-DRIFT, DRIFT);
            wobble = 1 + random.nextDouble(-WOBBLE, WOBBLE);
            nextDriftMs = now + random.nextLong(250, 700);
        }
        Vec3 middle = box.getCenter();
        Vec3 along = eye.add(look.scale(eye.distanceTo(middle)));
        AABB inner = box.deflate(Math.min(INSET, box.getXsize() / 2 - 0.01), Math.min(INSET, box.getYsize() / 2 - 0.01),
                Math.min(INSET, box.getZsize() / 2 - 0.01));
        if (inner.contains(along)) {
            return; // your crosshair is already on them: leave your aim alone
        }
        Vec3 spot = clampInto(along.add(driftX, driftY, driftZ), inner);
        float[] want = angles(eye, spot);
        double yawOff = Mth.wrapDegrees(want[0] - player.getYRot());
        double pitchOff = want[1] - player.getXRot();
        double off = Math.hypot(yawOff, pitchOff);
        if (off < 0.05) {
            return;
        }
        double seconds = Math.min(0.1, elapsed / 1000.0);
        double settle = Mth.clamp(off / SETTLE_DEGREES, SLOWEST_SHARE, 1);
        double step = SPEED.low() * wobble * settle * seconds;
        double share = Math.min(1, step / off);
        double stepYaw = yawOff * share;
        float pitch = Mth.clamp(player.getXRot() + (float) (pitchOff * share), -90F, 90F);
        float turnedPitch = pitch - player.getXRot();
        player.setYRot(player.getYRot() + (float) stepYaw);
        player.setXRot(pitch);
        player.yRotO += (float) stepYaw;
        player.xRotO += turnedPitch;
    }

    /** Hits once the game's own crosshair is on them, after the reaction delay, when it counts. */
    private static void tryHit(Minecraft minecraft, LocalPlayer player, long now) {
        boolean onThem = minecraft.hitResult instanceof EntityHitResult hit && hit.getEntity() == target;
        if (!onThem) {
            onTargetSinceMs = -1;
            return;
        }
        if (onTargetSinceMs < 0) {
            onTargetSinceMs = now;
            reactionMs = REACTION.pick();
        }
        if (now - onTargetSinceMs < reactionMs || now < nextTryMs) {
            return;
        }
        int ready = Hit.ready(minecraft, player, target, now);
        if (ready < 0) {
            return;
        }
        if (!CHANCE.roll()) {
            nextTryMs = now + SKIP_MS;
            return;
        }
        Hit.strike(minecraft, player, target, ready == Hit.SLAM, now);
        // A fresh reaction for the next hit, like a person re-timing their click.
        onTargetSinceMs = now;
        reactionMs = REACTION.pick();
    }

    /** The one closest to your crosshair, in range, in front of you and in sight. */
    private static LivingEntity pick(Minecraft minecraft, LocalPlayer player, Vec3 eye, Vec3 look, double range,
            double fovHalf, float partial) {
        LivingEntity best = null;
        double bestOff = fovHalf >= 180 ? Double.MAX_VALUE : fovHalf;
        AABB around = player.getBoundingBox().inflate(range + 2);
        for (Entity entity : minecraft.level.getEntities(player, around)) {
            if (!(entity instanceof LivingEntity living) || !valid(player, living)) {
                continue;
            }
            AABB box = box(living, partial);
            if (reach(eye, box) > range) {
                continue;
            }
            Vec3 middle = box.getCenter();
            double off = angleOff(player, eye, middle);
            if (off < bestOff && canSee(minecraft, player, eye, middle)) {
                bestOff = off;
                best = living;
            }
        }
        return best;
    }

    private static boolean valid(LocalPlayer player, LivingEntity entity) {
        if (entity == player || !entity.isAlive() || entity.isRemoved() || entity instanceof ArmorStand
                || entity.isInvisible()) {
            return false;
        }
        if (entity instanceof Player other) {
            return !other.isSpectator() && !other.isCreative() && !VelocityHud.isFriend(other);
        }
        return mobs;
    }

    private static AABB box(LivingEntity entity, float partial) {
        return entity.getBoundingBox().move(entity.getPosition(partial).subtract(entity.position()));
    }

    private static double reach(Vec3 eye, AABB box) {
        return eye.distanceTo(clampInto(eye, box));
    }

    private static Vec3 clampInto(Vec3 point, AABB box) {
        return new Vec3(Mth.clamp(point.x, box.minX, box.maxX), Mth.clamp(point.y, box.minY, box.maxY),
                Mth.clamp(point.z, box.minZ, box.maxZ));
    }

    private static double angleOff(LocalPlayer player, Vec3 eye, Vec3 point) {
        float[] want = angles(eye, point);
        return Math.hypot(Mth.wrapDegrees(want[0] - player.getYRot()), want[1] - player.getXRot());
    }

    private static boolean canSee(Minecraft minecraft, LocalPlayer player, Vec3 eye, Vec3 point) {
        return minecraft.level.clip(new ClipContext(eye, point, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                player)).getType() == HitResult.Type.MISS;
    }

    private static float[] angles(Vec3 eye, Vec3 point) {
        Vec3 to = point.subtract(eye);
        return new float[] {
            (float) Math.toDegrees(Math.atan2(-to.x, to.z)),
            (float) Math.toDegrees(-Math.atan2(to.y, Math.hypot(to.x, to.z)))};
    }

    private static void drop() {
        target = null;
        onTargetSinceMs = -1;
    }

    private static void note(String what, long now) {
        String kind = what.contains(":") ? what.substring(0, what.indexOf(':')) : what;
        if (!kind.equals(lastNoteKind) || now - lastNoteMs >= LOG_GAP_MS) {
            SpearLog.log("world slash: " + what);
            lastNoteKind = kind;
            lastNoteMs = now;
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

    static boolean isMobs() {
        return mobs;
    }

    static void setMobs(boolean on) {
        mobs = on;
    }

    static void toggleMobs() {
        mobs = !mobs;
    }

    /** Turns the module off when this injection is unloaded. */
    static void reset() {
        enabled = false;
        drop();
    }
}
