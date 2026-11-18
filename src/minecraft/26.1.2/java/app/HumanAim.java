package app;

import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.player.LocalPlayer;

/**
 * One mouse flick the way a hand makes it, from what is known about human aiming:
 * - Fitts's law: the flick takes longer the farther and the smaller the target
 *   (about 70 ms + 90 ms per bit of log2(distance / width + 1)), a little different each time.
 * - Minimum jerk: speed rises and falls in a bell curve, slow start, fast middle, slow end,
 *   never a constant-speed slide.
 * - Submovements: the main flick lands short of the target most of the time (about 95% of the
 *   way) and past it now and then; the module makes a smaller correcting flick after a pause.
 * - The path bows a little to one side instead of a straight line.
 * - Hand tremor: a tiny jitter on top that never adds up to drift.
 * Shaped after a real hand (the Mouse Lab recording, the shape only, not the speed): the
 * main push lands about 90% of the way and goes past about a quarter of the time, its speed
 * peaks anywhere from a third to two thirds in (not always the middle), and the path bends
 * about 5% to a random side. It turns the view in whole mouse counts at a mouse's report
 * rate (MouseSteps), with Entity.turn like the mouse does, so your own mouse movement still
 * adds on top. One per module; render thread only.
 */
final class HumanAim {
    private static final double DEGREES_PER_TURN = 0.15;
    private static final float TREMOR_DEGREES = 0.08F;
    private float yaw;
    private float pitch;
    private float curve;
    private float doneYaw;
    private float donePitch;
    private float tremorYaw;
    private float tremorPitch;
    private long startMs;
    private long durationMs;
    private boolean active;
    // Cracked glides: go this far past (a share of the move), then snap back to the spot.
    private float snapPast;
    // A stutter: the move in 2 or 3 quick jumps with tiny stops between, each a little off
    // the straight path, the last one exact.
    private boolean stutter;
    private float[] wayYaw;
    private float[] wayPitch;
    // A shake: a quick wobble that dies out and ends exactly where it started.
    private boolean shaking;
    private float shakeAmp;
    private float shakeAngle;
    private float shakeCycles;
    // Where in a flick its speed peaks, as a time warp exponent (1: the middle).
    private float skew = 1;
    private final MouseSteps mouse = new MouseSteps();
    // What Debug mode records of the current move (null while Debug is off).
    private Debug.Move rec;

    boolean active() {
        return active;
    }

    private void record(String kind, double yawDegrees, double pitchDegrees, double reach) {
        Debug.end(rec);
        rec = Debug.begin(kind, yawDegrees, pitchDegrees, durationMs, reach);
        mouse.rec = rec;
    }

    void stop() {
        Debug.end(rec);
        rec = null;
        mouse.rec = null;
        active = false;
        shaking = false;
        stutter = false;
    }

    /**
     * A jittery move that ends exactly where asked and takes this long: 2 or 3 quick jumps,
     * each a hair off the straight line, with a tiny stop after each, like a hand that
     * snaps there instead of sliding.
     */
    void stutter(float yawDegrees, float pitchDegrees, long durationMs) {
        float distance = (float) Math.hypot(yawDegrees, pitchDegrees);
        if (distance < 0.05F) {
            active = false;
            return;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int jumps = distance < 6 || random.nextBoolean() ? 2 : 3;
        float[] share = jumps == 2
                ? new float[] {0, (float) random.nextDouble(0.6, 0.8), 1}
                : new float[] {0, (float) random.nextDouble(0.45, 0.6), (float) random.nextDouble(0.8, 0.9), 1};
        wayYaw = new float[jumps + 1];
        wayPitch = new float[jumps + 1];
        float off = (float) Math.min(0.9, 0.15 + distance * 0.03);
        for (int i = 1; i <= jumps; i++) {
            float side = i == jumps ? 0 : (float) (random.nextGaussian() * off);
            float along = i == jumps ? 0 : (float) (random.nextGaussian() * off * 0.5);
            // off the line: sideways (perpendicular) and a little along it
            wayYaw[i] = yawDegrees * share[i] - pitchDegrees / distance * side + yawDegrees / distance * along;
            wayPitch[i] = pitchDegrees * share[i] + yawDegrees / distance * side + pitchDegrees / distance * along;
        }
        shaking = false;
        stutter = true;
        mouse.reset();
        doneYaw = 0;
        donePitch = 0;
        this.durationMs = Math.max(45, durationMs);
        startMs = System.nanoTime() / 1_000_000;
        active = true;
        record("stutter", yawDegrees, pitchDegrees, Double.NaN);
    }

    private boolean stepStutter(LocalPlayer player) {
        float t = Math.min(1.0F, (System.nanoTime() / 1_000_000 - startMs) / (float) Math.max(1, durationMs));
        int jumps = wayYaw.length - 1;
        int jump = Math.min(jumps - 1, (int) (t * jumps));
        float u = t * jumps - jump;
        // each jump moves in the first 65% of its time and holds still for the rest
        float m = Math.min(1, u / 0.65F);
        float s = m * m * m * (10 + m * (-15 + 6 * m));
        float wantYaw = t >= 1 ? wayYaw[jumps] : wayYaw[jump] + (wayYaw[jump + 1] - wayYaw[jump]) * s;
        float wantPitch = t >= 1 ? wayPitch[jumps] : wayPitch[jump] + (wayPitch[jump + 1] - wayPitch[jump]) * s;
        float moveYaw = wantYaw - doneYaw;
        float movePitch = wantPitch - donePitch;
        doneYaw = wantYaw;
        donePitch = wantPitch;
        if (t >= 1) {
            active = false;
            stutter = false;
        }
        mouse.turn(player, moveYaw, movePitch, !active);
        return !active;
    }

    /**
     * A hand shake, like the bump a click gives your mouse: a wobble of about this many
     * degrees, a different direction and rhythm each time, that dies out over this long and
     * ends exactly where it started.
     */
    void shake(float amplitudeDegrees, long durationMs) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        shakeAmp = (float) (amplitudeDegrees * random.nextDouble(0.7, 1.3));
        shakeAngle = (float) (random.nextDouble() * Math.PI * 2);
        shakeCycles = (float) random.nextDouble(1.5, 2.6);
        stutter = false;
        doneYaw = 0;
        donePitch = 0;
        this.durationMs = Math.max(40, durationMs);
        startMs = System.nanoTime() / 1_000_000;
        shaking = true;
        mouse.reset();
        active = true;
        record("shake", amplitudeDegrees, 0, Double.NaN);
    }

    private boolean stepShake(LocalPlayer player) {
        float t = Math.min(1.0F, (System.nanoTime() / 1_000_000 - startMs) / (float) Math.max(1, durationMs));
        float fade = 1 - t;
        float main = (float) Math.sin(Math.PI * 2 * shakeCycles * t) * fade * shakeAmp;
        float side = (float) Math.sin(Math.PI * 2 * shakeCycles * 1.7 * t) * fade * shakeAmp * 0.35F;
        float cos = (float) Math.cos(shakeAngle);
        float sin = (float) Math.sin(shakeAngle);
        float wantYaw = t >= 1 ? 0 : main * cos - side * sin;
        float wantPitch = t >= 1 ? 0 : main * sin + side * cos;
        float moveYaw = wantYaw - doneYaw;
        float movePitch = wantPitch - donePitch;
        doneYaw = wantYaw;
        donePitch = wantPitch;
        if (t >= 1) {
            active = false;
            shaking = false;
        }
        mouse.turn(player, moveYaw, movePitch, !active);
        return !active;
    }

    /**
     * Starts a flick of this many degrees towards a target this many degrees wide. With
     * overshoot off it may still land short but never past (for turns that must end exactly).
     */
    void flick(float yawDegrees, float pitchDegrees, float widthDegrees, boolean overshoot) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        float distance = (float) Math.hypot(yawDegrees, pitchDegrees);
        if (distance < 0.05F) {
            active = false;
            return;
        }
        shaking = false;
        stutter = false;
        double bits = Math.log(distance / Math.max(0.5F, widthDegrees) + 1) / Math.log(2);
        durationMs = PlayStyle.turnMs(Math.round((70 + 90 * bits) * random.nextDouble(0.85, 1.15)));
        snapPast = 0;
        // Cracked: a fast hand lands past the target more often than short of it.
        double reach;
        // Your hand's shape from the mouse test (HandProfile), or an average hand's.
        double pastRate = PlayStyle.isCracked() ? Math.max(0.4, HandProfile.overshoot()) : HandProfile.overshoot();
        if (overshoot && random.nextDouble() < pastRate) {
            reach = Math.min(1.10, 1.0 + Math.abs(random.nextGaussian() * 0.04)); // past it
        } else {
            reach = Math.min(overshoot ? 0.99 : 1.0,
                    Math.max(0.75, HandProfile.reach() + random.nextGaussian() * HandProfile.reachSpread()));
        }
        yaw = (float) (yawDegrees * reach);
        pitch = (float) (pitchDegrees * reach);
        curve = (float) (distance * Math.max(-0.12, Math.min(0.12, random.nextGaussian() * HandProfile.bend())));
        // speed peaks where your hand's does (an average hand: around 46% in)
        double peak = Math.max(0.3, Math.min(0.65, HandProfile.peak() + random.nextGaussian() * HandProfile.peakSpread()));
        skew = (float) (Math.log(0.5) / Math.log(peak));
        mouse.reset();
        doneYaw = 0;
        donePitch = 0;
        startMs = System.nanoTime() / 1_000_000;
        active = true;
        record("flick", yawDegrees, pitchDegrees, reach);
    }

    /**
     * One slow, even move that ends exactly where asked and takes this long: the same
     * bell-shaped speed, no overshoot and only a slight bow, for a turn that should be one
     * continuous motion instead of a quick flick.
     */
    void glide(float yawDegrees, float pitchDegrees, long durationMs) {
        float distance = (float) Math.hypot(yawDegrees, pitchDegrees);
        if (distance < 0.05F) {
            active = false;
            return;
        }
        shaking = false;
        stutter = false;
        yaw = yawDegrees;
        pitch = pitchDegrees;
        curve = (float) (distance * ThreadLocalRandom.current().nextDouble(-0.03, 0.03));
        doneYaw = 0;
        donePitch = 0;
        this.durationMs = Math.max(40, PlayStyle.turnMs(durationMs));
        snapPast = PlayStyle.isCracked() ? (float) ThreadLocalRandom.current().nextDouble(0.03, 0.08) : 0;
        skew = 1;
        mouse.reset();
        startMs = System.nanoTime() / 1_000_000;
        active = true;
        record("glide", yawDegrees, pitchDegrees, Double.NaN);
    }

    /** Makes the move just started take this share of its time (0.35: about a third). */
    void hurry(double share) {
        durationMs = Math.max(30, Math.round(durationMs * Math.min(1, share)));
        if (rec != null) {
            rec.plannedMs = durationMs;
        }
    }

    /** A flick that ends exactly where asked (turning back to where you were looking). */
    void flickExact(float yawDegrees, float pitchDegrees) {
        flick(yawDegrees, pitchDegrees, 3.0F, false);
        snapPast = 0;
        yaw = yawDegrees;
        pitch = pitchDegrees;
    }

    /** Moves the view along the flick; true once it is done. */
    boolean step(LocalPlayer player) {
        boolean done = advance(player);
        if (done && rec != null) {
            Debug.end(rec);
            rec = null;
            mouse.rec = null;
        }
        return done;
    }

    private boolean advance(LocalPlayer player) {
        if (!active) {
            return true;
        }
        if (shaking) {
            return stepShake(player);
        }
        if (stutter) {
            return stepStutter(player);
        }
        float t = Math.min(1.0F, (System.nanoTime() / 1_000_000 - startMs) / (float) Math.max(1, durationMs));
        float s;
        if (snapPast > 0) {
            // Cracked: most of the time to a little past the spot, the rest snapping back.
            if (t < 0.8F) {
                float u = t / 0.8F;
                s = (1 + snapPast) * u * u * u * (10 + u * (-15 + 6 * u));
            } else {
                float u = (t - 0.8F) / 0.2F;
                s = 1 + snapPast - snapPast * u * u * u * (10 + u * (-15 + 6 * u));
            }
        } else {
            float w = skew == 1 ? t : (float) Math.pow(t, skew);
            s = w * w * w * (10 + w * (-15 + 6 * w)); // minimum jerk, its peak moved by skew
        }
        float bow = (float) Math.sin(Math.PI * t) * curve;
        float length = (float) Math.hypot(yaw, pitch);
        float wantYaw = yaw * s + (length > 0 ? -pitch / length * bow : 0);
        float wantPitch = pitch * s + (length > 0 ? yaw / length * bow : 0);
        float moveYaw = wantYaw - doneYaw;
        float movePitch = wantPitch - donePitch;
        doneYaw = wantYaw;
        donePitch = wantPitch;
        if (t < 1.0F) {
            // Tremor: each step adds the change in a small wobble, so it cancels out over time.
            ThreadLocalRandom random = ThreadLocalRandom.current();
            float newYaw = tremorYaw + (float) ((random.nextGaussian() * TREMOR_DEGREES - tremorYaw) * 0.5);
            float newPitch = tremorPitch + (float) ((random.nextGaussian() * TREMOR_DEGREES - tremorPitch) * 0.5);
            moveYaw += newYaw - tremorYaw;
            movePitch += newPitch - tremorPitch;
            tremorYaw = newYaw;
            tremorPitch = newPitch;
        } else {
            moveYaw -= tremorYaw;
            movePitch -= tremorPitch;
            tremorYaw = 0;
            tremorPitch = 0;
            active = false;
        }
        mouse.turn(player, moveYaw, movePitch, !active);
        return !active;
    }
}
