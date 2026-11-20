package app;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/**
 * Aim recorder (in Debug): writes how your view turns, every frame, to a file in the
 * Uranium folder's recordings folder, so any aim assist (another client's too) can be
 * measured from the outside: only what shows in the game, never its code.
 *
 * Each frame: your view, how far your own mouse moved (the cursor, which only your hand
 * moves), so the rest of the turn is what the assist did; the keys and buttons you hold;
 * and the player nearest your crosshair: the angles to their head, middle and feet, how far
 * they are and how they move. Every key and button press and release is written too, with
 * its time. lab/aim_analyze.py turns a recording into a report: how fast the assist turns
 * at each distance from them, how late it reacts, whether it goes past, how much it
 * wobbles, where on them it aims, and whether its turns are whole mouse counts.
 *
 * Render thread for frames; presses come from the input callbacks (same thread in GLFW).
 */
final class AimRecorder {
    private static final double LOOK_RANGE = 8;
    private static BufferedWriter out;
    private static Path file;
    private static long startNs;
    private static long lastFlushMs;
    private static double lastCursorX = Double.NaN;
    private static double lastCursorY;
    private static int frames;

    private AimRecorder() {}

    static boolean isRecording() {
        return out != null;
    }

    static String label() {
        return isRecording() ? "Stop recording (" + frames + " frames)" : "Record my aim";
    }

    static void toggle() {
        if (isRecording()) {
            stop();
        } else {
            start();
        }
    }

    private static void start() {
        try {
            Path folder = HandProfile.humansFile().getParent().resolve("recordings");
            Files.createDirectories(folder);
            file = folder.resolve("aim-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
                    + ".csv");
            out = Files.newBufferedWriter(file);
            Minecraft minecraft = Minecraft.getInstance();
            out.write(String.format(Locale.ROOT, "# uranium aim recording%n# degrees_per_count=%.6f%n"
                    + "# sensitivity=%.4f%n# invert_y=%s%n", MouseSteps.degreesPerCount(),
                    minecraft.options.sensitivity().get(), minecraft.options.invertMouseY().get()));
            out.write("kind,t_ms,yaw,pitch,hand_yaw,hand_pitch,lmb,rmb,alt,move_keys,jump,sneak,on_ground,item,"
                    + "target,dist,yaw_mid,pitch_head,pitch_mid,pitch_feet,half_width_deg,tvx,tvy,tvz,"
                    + "svx,svy,svz,key,action\n");
            startNs = System.nanoTime();
            frames = 0;
            lastCursorX = Double.NaN;
            SpearDash.setStatus("Recording your aim: " + file.getFileName());
            SpearLog.log("aim recorder: started " + file);
        } catch (IOException exception) {
            out = null;
            SpearDash.setStatus("Couldn't start the recording: " + exception.getMessage());
        }
    }

    static void stop() {
        if (out == null) {
            return;
        }
        try {
            out.close();
        } catch (IOException ignored) {
            // what was written stays
        }
        out = null;
        SpearDash.setStatus("Saved " + frames + " frames to recordings/" + file.getFileName());
        SpearLog.log("aim recorder: saved " + frames + " frames to " + file);
    }

    private static double ms() {
        return (System.nanoTime() - startNs) / 1.0E6;
    }

    /** A key or mouse button went down or up (button: -1-button for mouse buttons). */
    static void press(int key, int action) {
        if (out == null || action == GLFW.GLFW_REPEAT) {
            return;
        }
        write(String.format(Locale.ROOT, "press,%.2f%s,%d,%s%n", ms(), ",".repeat(25), key,
                action == GLFW.GLFW_PRESS ? "down" : "up"));
    }

    /** One frame: called every frame by the HUD. */
    static void frame(Minecraft minecraft) {
        if (out == null) {
            return;
        }
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null) {
            return;
        }
        long window = minecraft.getWindow().handle();
        double[] cx = new double[1];
        double[] cy = new double[1];
        GLFW.glfwGetCursorPos(window, cx, cy);
        if (Mc.screen(minecraft) != null) {
            // A menu is open: the cursor moves freely, not your view; start over after.
            lastCursorX = Double.NaN;
            return;
        }
        double perCount = MouseSteps.degreesPerCount();
        double handYaw = 0;
        double handPitch = 0;
        if (!Double.isNaN(lastCursorX)) {
            handYaw = (cx[0] - lastCursorX) * perCount;
            handPitch = (cy[0] - lastCursorY) * perCount * (minecraft.options.invertMouseY().get() ? -1 : 1);
        }
        lastCursorX = cx[0];
        lastCursorY = cy[0];
        float yaw = player.getYRot();
        float pitch = player.getXRot();
        float partial = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        Vec3 eye = player.getEyePosition(partial);
        Player target = nearest(minecraft, player, eye, yaw, pitch, partial);
        StringBuilder row = new StringBuilder(220);
        row.append(String.format(Locale.ROOT, "frame,%.2f,%.4f,%.4f,%.4f,%.4f,%d,%d,%d,%s,%d,%d,%d,%s,", ms(), yaw,
                pitch, handYaw, handPitch, button(window, GLFW.GLFW_MOUSE_BUTTON_LEFT),
                button(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT),
                Mc.isKeyDown(minecraft, GLFW.GLFW_KEY_LEFT_ALT) ? 1 : 0, moveKeys(minecraft),
                minecraft.options.keyJump.isDown() ? 1 : 0, minecraft.options.keyShift.isDown() ? 1 : 0,
                player.onGround() ? 1 : 0,
                BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem()).getPath()));
        if (target == null) {
            row.append(",,,,,,,,,,");
        } else {
            AABB box = target.getBoundingBox().move(target.getPosition(partial).subtract(target.position()));
            Vec3 mid = box.getCenter();
            double flat = Math.hypot(mid.x - eye.x, mid.z - eye.z);
            double yawMid = Mth.wrapDegrees(Math.toDegrees(Math.atan2(-(mid.x - eye.x), mid.z - eye.z)));
            double pitchHead = pitchTo(eye, box.minY + target.getEyeHeight(), flat);
            double pitchMid = pitchTo(eye, mid.y, flat);
            double pitchFeet = pitchTo(eye, box.minY, flat);
            double halfWidth = Math.toDegrees(Math.atan2(box.getXsize() / 2, Math.max(0.1, flat)));
            row.append(String.format(Locale.ROOT, "%s,%.3f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,",
                    target.getName().getString(), eye.distanceTo(mid), yawMid, pitchHead, pitchMid, pitchFeet,
                    halfWidth, target.getX() - target.xo, target.getY() - target.yo, target.getZ() - target.zo));
        }
        row.append(String.format(Locale.ROOT, "%.4f,%.4f,%.4f,,%n", player.getX() - player.xo,
                player.getY() - player.yo, player.getZ() - player.zo));
        write(row.toString());
        frames++;
    }

    private static double pitchTo(Vec3 eye, double y, double flat) {
        return Math.toDegrees(-Math.atan2(y - eye.y, flat));
    }

    private static int button(long window, int button) {
        return GLFW.glfwGetMouseButton(window, button) == GLFW.GLFW_PRESS ? 1 : 0;
    }

    private static String moveKeys(Minecraft minecraft) {
        return (minecraft.options.keyUp.isDown() ? "W" : "") + (minecraft.options.keyLeft.isDown() ? "A" : "")
                + (minecraft.options.keyDown.isDown() ? "S" : "") + (minecraft.options.keyRight.isDown() ? "D" : "")
                + (minecraft.options.keySprint.isDown() ? "R" : "");
    }

    /** The player nearest your crosshair, within 8 blocks, any direction. */
    private static Player nearest(Minecraft minecraft, LocalPlayer player, Vec3 eye, float yaw, float pitch,
            float partial) {
        Player best = null;
        double bestOff = Double.MAX_VALUE;
        for (Player other : minecraft.level.players()) {
            if (other == player || !other.isAlive() || other.isSpectator()) {
                continue;
            }
            Vec3 mid = other.getBoundingBox().move(other.getPosition(partial).subtract(other.position())).getCenter();
            if (eye.distanceTo(mid) > LOOK_RANGE) {
                continue;
            }
            Vec3 to = mid.subtract(eye);
            double wantYaw = Math.toDegrees(Math.atan2(-to.x, to.z));
            double wantPitch = Math.toDegrees(-Math.atan2(to.y, Math.hypot(to.x, to.z)));
            double off = Math.hypot(Mth.wrapDegrees(wantYaw - yaw), wantPitch - pitch);
            if (off < bestOff) {
                bestOff = off;
                best = other;
            }
        }
        return best;
    }

    private static void write(String text) {
        try {
            out.write(text);
            long now = System.nanoTime() / 1_000_000;
            if (now - lastFlushMs > 1000) {
                out.flush();
                lastFlushMs = now;
            }
        } catch (IOException exception) {
            stop();
        }
    }

    /** A small REC badge while recording. */
    static void drawBadge(net.minecraft.client.gui.GuiGraphicsExtractor graphics, Minecraft minecraft) {
        if (out == null || Mc.screen(minecraft) != null) {
            return;
        }
        String text = "● REC " + frames;
        int x = minecraft.getWindow().getGuiScaledWidth() - minecraft.font.width(text) - 6;
        graphics.fill(x - 3, 2, x + minecraft.font.width(text) + 3, 14, 0x90000000);
        boolean blink = (System.nanoTime() / 500_000_000L) % 2 == 0;
        graphics.text(minecraft.font, text, x, 4, blink ? 0xFFFF453A : 0xFFFF8A80, false);
    }
}
