package app;

import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * Play Style: how the aim and timing of Cart, Aim Assist, Kill Aura and Trigger Bot feel.
 *
 * Calm (off) is the quiet way: slow even glides and steady timing.
 * Cracked (on) plays like someone sweating a fight: turns are quick and hard and go a hair
 * past the target before snapping back, waits are shorter and uneven (mostly quick, now and
 * then a little late, like a real hand under pressure), and it heats up as the fight goes:
 * every hit you land, every hit you take and every cart you shoot makes the next move come
 * faster. Out of the fight the heat cools off over about 6 seconds.
 */
final class PlayStyle {
    private static final double COOL_MS = 6000;
    private static volatile boolean cracked;
    // Render thread only.
    private static double heat;
    private static long lastMs;
    private static int lastHitStamp = Integer.MIN_VALUE;
    private static int lastHurtTime;

    private PlayStyle() {}

    /** Called from the input loop: warms up on hits, cools off over time. */
    static void update(Minecraft minecraft) {
        long now = System.nanoTime() / 1_000_000;
        long elapsed = lastMs == 0 ? 0 : Math.min(500, now - lastMs);
        lastMs = now;
        heat = Math.max(0, heat - elapsed / COOL_MS);
        LocalPlayer player = minecraft.player;
        if (!cracked || player == null) {
            lastHitStamp = Integer.MIN_VALUE;
            lastHurtTime = 0;
            return;
        }
        int stamp = player.getLastHurtMobTimestamp();
        if (lastHitStamp != Integer.MIN_VALUE && stamp != lastHitStamp) {
            bump(0.3); // you landed one
        }
        lastHitStamp = stamp;
        if (player.hurtTime > lastHurtTime) {
            bump(0.15); // you took one
        }
        lastHurtTime = player.hurtTime;
    }

    /** Something happened in the fight: hotter, up to 1. */
    static void bump(double amount) {
        if (cracked) {
            heat = Math.min(1, heat + amount);
        }
    }

    /** How hot the fight is, 0 to 1 (0 on Calm). */
    static double heat() {
        return cracked ? heat : 0;
    }

    /**
     * A wait the way this style takes it. Calm: as set. Cracked: about 55% (down to 35% in a
     * hot fight), mostly a bit quicker still, one time in seven a little late.
     */
    static long delay(long ms) {
        if (!cracked || ms <= 0) {
            return ms;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double scale = 0.55 - 0.2 * heat;
        double hand = random.nextInt(7) == 0 ? random.nextDouble(1.2, 1.5) : random.nextDouble(0.7, 1.0);
        return Math.max(1, Math.round(ms * scale * hand));
    }

    /** A turn speed (degrees a second) the way this style takes it: Cracked is 1.6x to 2.2x. */
    static double speed(double degreesPerSecond) {
        return cracked ? degreesPerSecond * (1.6 + 0.6 * heat) : degreesPerSecond;
    }

    /** How long a flick or glide takes in this style: Cracked about 60% (50% hot). */
    static long turnMs(long ms) {
        return cracked ? Math.max(30, Math.round(ms * (0.6 - 0.1 * heat))) : ms;
    }

    static boolean isCracked() {
        return cracked;
    }

    static void setCracked(boolean on) {
        cracked = on;
    }

    static void toggle() {
        cracked = !cracked;
        heat = 0;
    }

    static String label() {
        return cracked ? "Cracked" : "Calm";
    }

    static void reset() {
        cracked = false;
        heat = 0;
    }
}
