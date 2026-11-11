package app;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/**
 * Aim Helper: while its key works (hold Left Alt by default), turns your view toward the
 * player nearest your crosshair, with any item in your hand. Your own mouse always still
 * works on top of it.
 * Settings follow Wurst's Aim Assist:
 * - Range: every moment it picks the player closest to your crosshair within this many
 *   blocks of your eyes (measured to their hitbox, so reach).
 * - FOV: only players inside this cone around your crosshair (360 = all around you).
 * - Aim at: Auto aims at the spot on their hitbox nearest your crosshair, so once you are on
 *   them it stops moving and never fights you; or Head, Center, Feet.
 * - Speed: how fast it turns, in degrees per second, at a steady rate.
 * - Ignore mouse: holds back that share of your own mouse while it aims.
 * - Line of sight: no aiming through walls. Ignore invisible / Ignore creative players.
 * - Their hitbox is moved ahead by their speed for one tick plus your ping, so it aims where
 *   they will be when your hit arrives.
 * - Runs every frame, so it glides.
 * - It holds off while you eat, drink or block with a shield, unless Aim while blocking.
 * - Air only unless Ground too is on.
 * - Key: Hold (only while held), Toggle (press on, press off) or Always (no key needed).
 * Friends are never aimed at.
 */
final class AimHelper {
    private static final double MIN_RANGE = 1;
    private static final int MIN_FOV = 30;
    private static final double MAX_LEAD_TICKS = 6;
    /** How far into their hitbox the aim spot sits, so you end up on it, not on its edge. */
    private static final double INSET = 0.1;
    private static final long LOG_GAP_MS = 1000;
    private static final String[] KEY_MODES = {"Hold", "Toggle", "Always"};
    private static final String[] AIM_AT = {"Auto", "Head", "Center", "Feet"};
    /** How far away a player may be, to their hitbox (your reach). */
    static final Num RANGE = Num.tenths(4.5, 6, " blocks");
    /** How fast it turns your view. */
    static final Num SPEED = Num.value(600, 3600, 10, "\u00B0/s");
    /** How wide around your crosshair it looks for players. */
    static final Num FOV = Num.value(120, 360, 10, "\u00B0");
    /** How much of your own mouse movement it holds back while aiming. */
    static final Num IGNORE_MOUSE = Num.percent(0);
    private static volatile int aimAt;
    private static volatile boolean lineOfSight = true;
    private static volatile boolean whileBlocking;
    private static volatile boolean ignoreInvisible = true;
    private static volatile boolean ignoreCreative;
    private static volatile boolean enabled;
    private static volatile boolean ground;
    private static volatile int keyCode = GLFW.GLFW_KEY_LEFT_ALT;
    private static volatile int mouseButton = -1;
    private static volatile boolean awaitingBinding;
    private static volatile int keyMode;
    private static volatile boolean toggledOn;
    // Render thread only.
    private static long lastStepMs;
    private static String lastTarget = "";
    private static boolean haveLast;
    private static float lastYaw;
    private static float lastPitch;
    private static String lastNoteKind = "";
    private static long lastNoteMs;

    private AimHelper() {}

    /**
     * Called every frame by the HUD, and from the input loop for when the HUD is hidden: one
     * step, as big as the time since the last one.
     */
    static void update(Minecraft minecraft) {
        long now = System.nanoTime() / 1_000_000;
        long elapsed = lastStepMs == 0 ? 0 : now - lastStepMs;
        lastStepMs = now;
        LocalPlayer player = minecraft.player;
        if (!enabled || player == null || minecraft.level == null || Mc.screen(minecraft) != null
                || !SpearSettings.combatAllowed(minecraft) || !keyActive(minecraft)) {
            lastTarget = "";
            haveLast = false;
            return;
        }
        if (player.onGround() && !ground) {
            haveLast = false;
            note("waiting: on the ground (turn on Ground too to help here)", now);
            return;
        }
        if (!whileBlocking && busyHands(player)) {
            haveLast = false;
            note("waiting: you are eating, drinking or blocking", now);
            return;
        }
        float partial = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        Vec3 eye = player.getEyePosition(partial);
        Vec3 look = player.getViewVector(partial);
        double lead = Math.min(MAX_LEAD_TICKS, 1 + ShieldBreak.pingMs(minecraft) / 50.0);
        double range = Math.max(MIN_RANGE, RANGE.tenths());
        double fovHalf = Math.max(MIN_FOV, FOV.low()) / 2.0;
        Player best = null;
        Vec3 bestSpot = null;
        double bestOff = fovHalf >= 180 ? Double.MAX_VALUE : fovHalf;
        for (Player other : minecraft.level.players()) {
            if (other == player || !other.isAlive() || other.isSpectator() || VelocityHud.isFriend(other)
                    || ignoreInvisible && other.isInvisible() || ignoreCreative && other.isCreative()) {
                continue;
            }
            AABB box = ahead(other, partial, lead);
            if (reach(eye, box) > range) {
                continue;
            }
            Vec3 spot = spot(eye, look, box, other);
            double off = angleOff(player, eye, spot);
            if (off < bestOff && (!lineOfSight || canSee(minecraft, player, eye, spot))) {
                bestOff = off;
                best = other;
                bestSpot = spot;
            }
        }
        if (best == null) {
            lastTarget = "";
            haveLast = false;
            note("waiting: no player you can see within " + range + " blocks and " + (int) fovHalf
                    + " degrees of your crosshair", now);
            return;
        }
        holdBackMouse(player);
        String name = best.getName().getString();
        if (!name.equals(lastTarget)) {
            lastTarget = name;
            note("aiming at " + name, now);
        }
        float[] want = angles(eye, bestSpot);
        double yawOff = Mth.wrapDegrees(want[0] - player.getYRot());
        double pitchOff = want[1] - player.getXRot();
        double off = Math.hypot(yawOff, pitchOff);
        if (off < 0.05) {
            remember(player);
            return; // already on them
        }
        double seconds = Math.min(0.1, elapsed / 1000.0);
        double step = SPEED.low() * seconds;
        // One straight turn: yaw and pitch arrive together, never past the spot.
        double share = Math.min(1, step / off);
        double stepYaw = yawOff * share;
        float pitch = Mth.clamp(player.getXRot() + (float) (pitchOff * share), -90F, 90F);
        float turnedPitch = pitch - player.getXRot();
        // Moved like a mouse turn (Entity.turn): the previous rotation goes along, so the
        // step shows at once instead of being blended in over the next frames.
        player.setYRot(player.getYRot() + (float) stepYaw);
        player.setXRot(pitch);
        player.yRotO += (float) stepYaw;
        player.xRotO += turnedPitch;
        remember(player);
        note(String.format(java.util.Locale.ROOT, "helping onto %s: %.0f degrees off, turned %.1f",
                name, off, Math.hypot(stepYaw, turnedPitch)), now);
    }

    /**
     * Ignore mouse: undoes that share of how far your mouse turned you since the last step,
     * so the aim is harder to pull off the target.
     */
    private static void holdBackMouse(LocalPlayer player) {
        double share = IGNORE_MOUSE.low() / 100.0;
        if (!haveLast || share <= 0) {
            return;
        }
        float undoYaw = (float) (Mth.wrapDegrees(player.getYRot() - lastYaw) * share);
        float pitch = Mth.clamp(player.getXRot() - (float) ((player.getXRot() - lastPitch) * share), -90F, 90F);
        float undoPitch = player.getXRot() - pitch;
        player.setYRot(player.getYRot() - undoYaw);
        player.setXRot(pitch);
        player.yRotO -= undoYaw;
        player.xRotO -= undoPitch;
    }

    private static void remember(LocalPlayer player) {
        lastYaw = player.getYRot();
        lastPitch = player.getXRot();
        haveLast = true;
    }

    /** Eating, drinking or holding a shield up: aiming then would only get in the way. */
    private static boolean busyHands(LocalPlayer player) {
        if (player.isBlocking()) {
            return true;
        }
        if (!player.isUsingItem()) {
            return false;
        }
        ItemStack using = player.getUseItem();
        return using.has(DataComponents.FOOD) || using.has(DataComponents.CONSUMABLE);
    }

    /** Their hitbox, drawn smoothly between ticks and moved ahead by their speed for the lead. */
    private static AABB ahead(Player target, float partial, double lead) {
        Vec3 drawn = target.getPosition(partial).subtract(target.position());
        Vec3 speed = new Vec3(target.getX() - target.xo, target.getY() - target.yo, target.getZ() - target.zo);
        return target.getBoundingBox().move(drawn.add(speed.scale(lead)));
    }

    /** Distance from your eyes to the nearest point of the box: your reach to it. */
    private static double reach(Vec3 eye, AABB box) {
        return eye.distanceTo(clampInto(eye, box, 0));
    }

    /**
     * Where on them to aim, by Aim at. Auto: the spot on the box nearest your crosshair (the
     * point along your view as far away as the box's middle, pulled inside the box, a little
     * in from its edges). Head: their eyes. Center: their middle. Feet: just above the bottom.
     */
    private static Vec3 spot(Vec3 eye, Vec3 look, AABB box, Player target) {
        Vec3 middle = box.getCenter();
        return switch (aimAt) {
            case 1 -> new Vec3(middle.x, Math.min(box.maxY - INSET, box.minY + target.getEyeHeight()), middle.z);
            case 2 -> middle;
            case 3 -> new Vec3(middle.x, box.minY + INSET, middle.z);
            default -> clampInto(eye.add(look.scale(eye.distanceTo(middle))), box, INSET);
        };
    }

    private static Vec3 clampInto(Vec3 point, AABB box, double inset) {
        double ix = Math.min(inset, box.getXsize() / 2);
        double iy = Math.min(inset, box.getYsize() / 2);
        double iz = Math.min(inset, box.getZsize() / 2);
        return new Vec3(
                Mth.clamp(point.x, box.minX + ix, box.maxX - ix),
                Mth.clamp(point.y, box.minY + iy, box.maxY - iy),
                Mth.clamp(point.z, box.minZ + iz, box.maxZ - iz));
    }

    private static double angleOff(LocalPlayer player, Vec3 eye, Vec3 point) {
        float[] want = angles(eye, point);
        return Math.hypot(Mth.wrapDegrees(want[0] - player.getYRot()), want[1] - player.getXRot());
    }

    /** No block between your eyes and the spot. */
    private static boolean canSee(Minecraft minecraft, LocalPlayer player, Vec3 eye, Vec3 point) {
        return minecraft.level.clip(new ClipContext(eye, point, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                player)).getType() == HitResult.Type.MISS;
    }

    /** Whether the key lets the helper work right now. */
    private static boolean keyActive(Minecraft minecraft) {
        return switch (keyMode) {
            case 1 -> toggledOn;
            case 2 -> true;
            default -> mouseButton >= 0
                    ? GLFW.glfwGetMouseButton(minecraft.getWindow().handle(), mouseButton) == GLFW.GLFW_PRESS
                    : keyCode >= 0 && Mc.isKeyDown(minecraft, keyCode);
        };
    }

    /** The key was pressed: in Toggle mode, flips the helper on or off. Never swallows the key. */
    static void onKey(Minecraft minecraft) {
        if (!enabled || keyMode != 1 || Mc.screen(minecraft) != null) {
            return;
        }
        toggledOn = !toggledOn;
        MinecraftSpearDash.setStatus(toggledOn ? "Aim Helper on" : "Aim Helper off");
    }

    /** Logs what the helper is doing, when that changes or once a second while it keeps on. */
    private static void note(String what, long now) {
        String kind = what.contains(":") ? what.substring(0, what.indexOf(':')) : what;
        if (!kind.equals(lastNoteKind) || now - lastNoteMs >= LOG_GAP_MS) {
            SpearLog.log("aim helper: " + what);
            lastNoteKind = kind;
            lastNoteMs = now;
        }
    }

    /** The view {yaw, pitch} from the eye to the point. */
    private static float[] angles(Vec3 eye, Vec3 point) {
        Vec3 to = point.subtract(eye);
        return new float[] {
            (float) Math.toDegrees(Math.atan2(-to.x, to.z)),
            (float) Math.toDegrees(-Math.atan2(to.y, Math.hypot(to.x, to.z)))};
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

    static boolean isGround() {
        return ground;
    }

    static void setGround(boolean on) {
        ground = on;
    }

    static void toggleGround() {
        ground = !ground;
    }

    static String keyModeLabel() {
        return KEY_MODES[keyMode];
    }

    static int keyMode() {
        return keyMode;
    }

    static void setKeyMode(int value) {
        keyMode = Math.floorMod(value, KEY_MODES.length);
        toggledOn = false;
    }

    static void cycleKeyMode() {
        setKeyMode(keyMode + 1);
    }

    static int keyCode() {
        return keyCode;
    }

    static int mouseButton() {
        return mouseButton;
    }

    static void setBinding(int key, int button) {
        keyCode = key;
        mouseButton = button;
    }

    static void bindKeyboard(int key) {
        keyCode = key;
        mouseButton = -1;
        awaitingBinding = false;
    }

    static void bindMouse(int button) {
        mouseButton = button;
        awaitingBinding = false;
    }

    static boolean isAwaitingBinding() {
        return awaitingBinding;
    }

    static void awaitBinding() {
        awaitingBinding = true;
    }

    static void cancelBinding() {
        awaitingBinding = false;
    }

    /** Turns the module off when this injection is unloaded. */
    static void reset() {
        enabled = false;
        toggledOn = false;
        awaitingBinding = false;
        lastTarget = "";
        haveLast = false;
    }

    static String aimAtLabel() {
        return AIM_AT[aimAt];
    }

    static int aimAt() {
        return aimAt;
    }

    static void setAimAt(int value) {
        aimAt = Math.floorMod(value, AIM_AT.length);
    }

    static void cycleAimAt() {
        setAimAt(aimAt + 1);
    }

    static boolean isLineOfSight() {
        return lineOfSight;
    }

    static void setLineOfSight(boolean on) {
        lineOfSight = on;
    }

    static void toggleLineOfSight() {
        lineOfSight = !lineOfSight;
    }

    static boolean isWhileBlocking() {
        return whileBlocking;
    }

    static void setWhileBlocking(boolean on) {
        whileBlocking = on;
    }

    static void toggleWhileBlocking() {
        whileBlocking = !whileBlocking;
    }

    static boolean isIgnoreInvisible() {
        return ignoreInvisible;
    }

    static void setIgnoreInvisible(boolean on) {
        ignoreInvisible = on;
    }

    static void toggleIgnoreInvisible() {
        ignoreInvisible = !ignoreInvisible;
    }

    static boolean isIgnoreCreative() {
        return ignoreCreative;
    }

    static void setIgnoreCreative(boolean on) {
        ignoreCreative = on;
    }

    static void toggleIgnoreCreative() {
        ignoreCreative = !ignoreCreative;
    }
}
