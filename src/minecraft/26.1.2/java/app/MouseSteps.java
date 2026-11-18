package app;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * Turns the view the way a real mouse does: in whole mouse counts, never fractions of one.
 *
 * A mouse reports whole steps, and Minecraft turns each step by a fixed angle from your
 * sensitivity (0.15 degrees at 100%). So every real turn is a whole number of those angles,
 * and anticheats check for it: a turn of 0.0731 degrees can't come from a mouse. Smooth aim
 * that turns by any fraction it likes gets caught that way. Here the wanted turn is saved
 * up and sent only as whole counts; what is left over waits for the next one.
 *
 * It also sends at most every MOUSE_MS, like a mouse that reports 125 times a second (the
 * rate measured in the Mouse Lab recording), not on every frame.
 */
final class MouseSteps {
    private static final long MOUSE_MS = 8;
    private double restYaw;
    private double restPitch;
    private long lastMs;
    // Debug mode's record of the move these turns belong to (null while off).
    Debug.Move rec;

    /** The angle one mouse count turns, from your sensitivity setting. */
    static double degreesPerCount() {
        double sensitivity = Minecraft.getInstance().options.sensitivity().get();
        double f = sensitivity * 0.6 + 0.2;
        return Math.max(1.0E-4, f * f * f * 8.0 * 0.15);
    }

    /**
     * Adds this many degrees to the turn. Sent as whole counts once a mouse report is due,
     * or now if flush (the end of a move).
     */
    void turn(LocalPlayer player, double yawDegrees, double pitchDegrees, boolean flush) {
        restYaw += yawDegrees;
        restPitch += pitchDegrees;
        long now = System.nanoTime() / 1_000_000;
        if (!flush && now - lastMs < MOUSE_MS) {
            return;
        }
        double step = degreesPerCount();
        long countsX = Math.round(restYaw / step);
        long countsY = Math.round(restPitch / step);
        if (countsX == 0 && countsY == 0) {
            return;
        }
        lastMs = now;
        restYaw -= countsX * step;
        restPitch -= countsY * step;
        // Entity.turn multiplies by 0.15, the same as the game does with your mouse.
        player.turn(countsX * step / 0.15, countsY * step / 0.15);
        Debug.turn(rec, countsX * step, countsY * step);
    }

    /** A new move: what was left over from the last one (under half a count) is dropped. */
    void reset() {
        restYaw = 0;
        restPitch = 0;
    }
}
