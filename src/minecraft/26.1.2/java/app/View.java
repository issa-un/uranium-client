package app;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

/**
 * Turns your real view (the camera) for Web Escape, MLG Water, Clutch and Auto Web: they
 * snap it to where they place, the game sends that view like any mouse movement, and once
 * they are done it turns back to where you were looking with a hand-like flick (see
 * HumanAim), not a snap. If you move the mouse while it is turned or turning back, the view
 * is yours from then on.
 */
final class View {
    // Render thread only.
    private static boolean saved;
    private static float savedYaw;
    private static float savedPitch;
    private static float setYaw;
    private static float setPitch;
    private static long releaseAtMs = -1;
    private static final HumanAim BACK = new HumanAim();
    private static boolean returning;

    private View() {}

    /** Snaps your view to this direction, remembering where you were looking first. */
    static void turnTo(LocalPlayer player, float yaw, float pitch) {
        if (!saved || movedByYou(player)) {
            savedYaw = player.getYRot();
            savedPitch = player.getXRot();
            saved = true;
        }
        BACK.stop();
        returning = false;
        snap(player, yaw, pitch);
        releaseAtMs = -1;
    }

    /** Turns back to where you were looking after this long, unless you move the mouse first. */
    static void release(long delayMs) {
        if (saved) {
            releaseAtMs = System.nanoTime() / 1_000_000 + delayMs;
        }
    }

    /** Called from the input loop. */
    static void update(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (!saved) {
            return;
        }
        if (player == null || movedByYou(player)) {
            saved = false; // you took the view over
            releaseAtMs = -1;
            BACK.stop();
            returning = false;
            return;
        }
        if (!returning && releaseAtMs >= 0 && System.nanoTime() / 1_000_000 >= releaseAtMs) {
            BACK.flickExact(Mth.wrapDegrees(savedYaw - player.getYRot()), savedPitch - player.getXRot());
            returning = true;
            releaseAtMs = -1;
        }
        if (returning) {
            boolean done = BACK.step(player);
            setYaw = player.getYRot();
            setPitch = player.getXRot();
            if (done) {
                saved = false;
                returning = false;
            }
        }
    }

    /**
     * Sets the view in one step: the turn is taken the short way round from where you face,
     * and the previous-frame angles are set too, so it does not sweep across the screen.
     */
    private static void snap(LocalPlayer player, float yaw, float pitch) {
        float newYaw = player.getYRot() + Mth.wrapDegrees(yaw - player.getYRot());
        float newPitch = Mth.clamp(pitch, -90.0F, 90.0F);
        player.setYRot(newYaw);
        player.setXRot(newPitch);
        player.yRotO = newYaw;
        player.xRotO = newPitch;
        setYaw = newYaw;
        setPitch = newPitch;
    }

    private static boolean movedByYou(LocalPlayer player) {
        return Math.abs(player.getYRot() - setYaw) > 0.01F || Math.abs(player.getXRot() - setPitch) > 0.01F;
    }

    /** Forgets the remembered view when this injection is unloaded. */
    static void reset() {
        BACK.stop();
        returning = false;
        saved = false;
        releaseAtMs = -1;
    }
}
