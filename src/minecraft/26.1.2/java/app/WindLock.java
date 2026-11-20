package app;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Aim Assist's Wind mode: see a player, lock onto their torso, hold until you yank away.
 * Aim Assist decides when it may run (on, key, menus); this does the lock.
 * - Picks the player nearest your crosshair that you can see within Aim Assist's Range, in
 *   any direction (behind you too). Friends never; Ignore invisible / creative apply.
 * - Locked, it closes 90% of the gap to the middle of their torso in under 50 ms, every
 *   frame, in whole mouse counts, wherever they go.
 * - Small mouse moves don't break it; a hard flick does: your mouse movement against it adds
 *   up and fades over about a sixth of a second, and past 20 degrees (once you're 10 degrees
 *   off them) it lets go and rests a second before locking again.
 * - It also lets go when they die, get 2 blocks past Range, or stay behind a wall a second.
 *
 * Drift Lock (Aim Assist's setting, used by every mode through {@link #drift}): the further
 * the player is thrown off the path they were on, the more of your mouse is ignored and the
 * faster it turns.
 */
final class WindLock {
    /** Where on their hitbox the torso's middle is, from the feet up. */
    private static final double TORSO = 0.6;
    private static final double GLIDE = 50;
    private static final double KEEP_EXTRA = 2;
    private static final double YANK_DEGREES = 20;
    private static final double YANK_OFF = 10;
    private static final double YANK_FADE_S = 0.15;
    private static final long REST_AFTER_YANK_MS = 1000;
    private static final long HIDDEN_MS = 1000;
    private static final double DRIFT_FROM = 0.15;
    private static final double DRIFT_FULL = 0.6;
    private static final double DRIFT_FADE_S = 0.3;
    private static final MouseSteps MOUSE = new MouseSteps();
    // Render thread only.
    private static Player target;
    private static long lastFrameNs;
    private static boolean haveView;
    private static float viewYaw;
    private static float viewPitch;
    private static double yank;
    private static long restUntilMs;
    private static long seenMs;
    // Drift: whose movement is being followed, their last tick's move, and how drifted.
    private static Player driftOf;
    private static int driftTick = -1;
    private static Vec3 move;
    private static double drift;

    private WindLock() {}

    /** One frame of Wind mode. */
    static void update(Minecraft minecraft) {
        long nowNs = System.nanoTime();
        double seconds = lastFrameNs == 0 ? 0 : Math.min(0.1, (nowNs - lastFrameNs) / 1.0E9);
        lastFrameNs = nowNs;
        long now = nowNs / 1_000_000;
        LocalPlayer player = minecraft.player;
        float partial = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        Vec3 eye = player.getEyePosition(partial);
        double range = Math.max(1, AimAssist.RANGE.tenths());

        // How far you turned the view yourself since the last frame.
        double handYaw = haveView ? Mth.wrapDegrees(player.getYRot() - viewYaw) : 0;
        double handPitch = haveView ? player.getXRot() - viewPitch : 0;

        if (target != null && !keep(minecraft, player, eye, partial, now, range + KEEP_EXTRA)) {
            letGo("");
        }
        if (target == null) {
            yank = 0;
            if (now < restUntilMs) {
                remember(player);
                return;
            }
            target = pick(minecraft, player, eye, partial, range);
            if (target == null) {
                remember(player);
                return;
            }
            seenMs = now;
            MOUSE.reset();
            SpearLog.log("aim assist (wind): locked onto " + target.getName().getString());
        }

        double strength = AimAssist.isDriftLock() ? drift(target, seconds) : 0;
        if (strength > 0 && haveView) {
            ignoreHand(player, handYaw * strength, handPitch * strength);
        }
        yank = yank * Math.exp(-seconds / YANK_FADE_S) + Math.hypot(handYaw, handPitch) * (1 - strength);

        float[] want = angles(eye, torso(target, partial));
        double yawOff = Mth.wrapDegrees(want[0] - player.getYRot());
        double pitchOff = AimAssist.isVertical() ? want[1] - player.getXRot() : 0;
        if (yank > YANK_DEGREES && Math.hypot(yawOff, pitchOff) > YANK_OFF) {
            letGo("you looked away");
            restUntilMs = now + REST_AFTER_YANK_MS;
            remember(player);
            return;
        }
        double share = 1 - Math.exp(-seconds * GLIDE * (1 + 2 * strength));
        float pitch = Mth.clamp(player.getXRot() + (float) (pitchOff * share), -90F, 90F);
        MOUSE.turn(player, yawOff * share, pitch - player.getXRot(), false);
        remember(player);
    }

    /** Takes back this much of your own turn (Drift Lock), as if the mouse hadn't moved it. */
    static void ignoreHand(LocalPlayer player, double yaw, double pitch) {
        float undoYaw = (float) yaw;
        float newPitch = Mth.clamp(player.getXRot() - (float) pitch, -90F, 90F);
        float undoPitch = player.getXRot() - newPitch;
        player.setYRot(player.getYRot() - undoYaw);
        player.setXRot(newPitch);
        player.yRotO -= undoYaw;
        player.xRotO -= undoPitch;
    }

    /**
     * How thrown off their path this player is, 0 to 1. Each new tick, where they should have
     * gone (last tick's move, slowed by air drag and pulled by gravity, or by ground friction)
     * is compared with where they went; a gap past 0.15 blocks a tick starts it, 0.6 is full,
     * and it fades over about a third of a second.
     */
    static double drift(Player who, double seconds) {
        if (who != driftOf) {
            driftOf = who;
            driftTick = -1;
            move = null;
            drift = 0;
        }
        drift *= Math.exp(-seconds / DRIFT_FADE_S);
        if (who.tickCount != driftTick) {
            driftTick = who.tickCount;
            Vec3 now = new Vec3(who.getX() - who.xo, who.getY() - who.yo, who.getZ() - who.zo);
            if (move != null) {
                Vec3 expected = who.onGround()
                        ? new Vec3(move.x * 0.6, 0, move.z * 0.6)
                        : new Vec3(move.x * 0.91, (move.y - 0.08) * 0.98, move.z * 0.91);
                double gap = now.subtract(expected).length();
                double strength = Mth.clamp((gap - DRIFT_FROM) / (DRIFT_FULL - DRIFT_FROM), 0, 1);
                if (strength > drift) {
                    if (drift < 0.05 && strength > 0.3) {
                        SpearLog.log(String.format(java.util.Locale.ROOT,
                                "aim assist: drift lock, %s thrown %.2f blocks a tick off their path",
                                who.getName().getString(), gap));
                    }
                    drift = strength;
                }
            }
            move = now;
        }
        return drift;
    }

    private static void letGo(String why) {
        if (target != null) {
            SpearLog.log("aim assist (wind): let go of " + target.getName().getString()
                    + (why.isEmpty() ? "" : " (" + why + ")"));
        }
        target = null;
    }

    private static void remember(LocalPlayer player) {
        viewYaw = player.getYRot();
        viewPitch = player.getXRot();
        haveView = true;
    }

    private static boolean keep(Minecraft minecraft, LocalPlayer player, Vec3 eye, float partial, long now,
            double keepRange) {
        if (!target.isAlive() || target.isRemoved() || target.isSpectator() || target.level() != player.level()) {
            return false;
        }
        if (reach(eye, drawnBox(target, partial)) > keepRange) {
            return false;
        }
        if (!AimAssist.isLineOfSight() || canSee(minecraft, player, eye, torso(target, partial))) {
            seenMs = now;
        }
        return now - seenMs < HIDDEN_MS;
    }

    private static Player pick(Minecraft minecraft, LocalPlayer player, Vec3 eye, float partial, double range) {
        Player best = null;
        double bestOff = Double.MAX_VALUE;
        for (Player other : minecraft.level.players()) {
            if (other == player || !other.isAlive() || other.isSpectator() || VelocityHud.isFriend(other)
                    || AimAssist.isIgnoreInvisible() && other.isInvisible()
                    || AimAssist.isIgnoreCreative() && other.isCreative()) {
                continue;
            }
            if (reach(eye, drawnBox(other, partial)) > range) {
                continue;
            }
            Vec3 spot = torso(other, partial);
            float[] want = angles(eye, spot);
            double off = Math.hypot(Mth.wrapDegrees(want[0] - player.getYRot()), want[1] - player.getXRot());
            // Nearest your crosshair first, so whoever's in front wins; behind you counts too.
            if (off < bestOff && (!AimAssist.isLineOfSight() || canSee(minecraft, player, eye, spot))) {
                bestOff = off;
                best = other;
            }
        }
        return best;
    }

    private static Vec3 torso(Player other, float partial) {
        AABB box = drawnBox(other, partial);
        return new Vec3((box.minX + box.maxX) / 2, box.minY + box.getYsize() * TORSO, (box.minZ + box.maxZ) / 2);
    }

    private static AABB drawnBox(Player other, float partial) {
        return other.getBoundingBox().move(other.getPosition(partial).subtract(other.position()));
    }

    private static double reach(Vec3 eye, AABB box) {
        return eye.distanceTo(new Vec3(Mth.clamp(eye.x, box.minX, box.maxX), Mth.clamp(eye.y, box.minY, box.maxY),
                Mth.clamp(eye.z, box.minZ, box.maxZ)));
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

    /** Locked onto someone right now. */
    static boolean locked() {
        return target != null;
    }

    /** Not allowed to run this frame (off, key up, menu): lets go, and starts fresh after. */
    static void stop() {
        letGo("");
        haveView = false;
        lastFrameNs = 0;
    }

    static void reset() {
        target = null;
        haveView = false;
        lastFrameNs = 0;
        driftOf = null;
    }
}
