package app;

import java.util.ArrayDeque;
import java.util.concurrent.ThreadLocalRandom;
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
 * Aim Assist: while its key works (hold Left Alt by default), turns your view toward the
 * player nearest your crosshair, with any item in your hand. Your own mouse always still
 * works on top of it, and every turn goes out in whole mouse counts like a real mouse.
 * Aim mode picks how it moves:
 * - Simple: speeds up, then slows down as it gets close, so it settles instead of stopping
 *   dead. Each target gets its own top speed from the Speed range.
 * - Wind: see a player, lock onto their torso, hold until you yank your mouse away (see
 *   WindLock). Works in any direction, no FOV, no sliders.
 * - Human: reacts like a person: aims where they were a moment ago (Reaction), flicks a
 *   little past on big turns and comes back (Overshoot), then follows.
 * The rest:
 * - Range: players within this many blocks of your eyes (to their hitbox, so reach).
 * - FOV: only players inside this cone around your crosshair (360 = all around you).
 * - It sticks to one player until another is clearly nearer your crosshair.
 * - Aim at: Center by default; Auto is the spot on their hitbox nearest your crosshair.
 * - Prediction: how far ahead of a moving player it aims (0% = where they are, smoothest).
 *   Their speed is smoothed over a few ticks first, so it doesn't jump.
 * - Vertical aim: off turns you only left and right.
 * - Ignore mouse: holds back that share of your own mouse while it aims.
 * - Line of sight: no aiming through walls. Ignore invisible / Ignore creative players.
 * - It holds off while you eat, drink or block with a shield, unless Aim while blocking.
 * - Air only unless Ground too is on.
 * - Key: Hold (only while held), Toggle (press on, press off) or Always (no key needed).
 * - Drift Lock (on by default, every mode): the further they're thrown off the path they
 *   were on (knockback, wind charge, pearl), the more of your mouse it ignores and the faster
 *   it turns.
 * Friends are never aimed at.
 */
final class AimAssist {
    private static final double MIN_RANGE = 1;
    private static final int MIN_FOV = 30;
    private static final double MAX_LEAD_TICKS = 4;
    /** How far into their hitbox the aim spot sits, so you end up on it, not on its edge. */
    private static final double INSET = 0.1;
    private static final long LOG_GAP_MS = 1000;
    private static final String[] KEY_MODES = {"Hold", "Toggle", "Always"};
    private static final String[] AIM_AT = {"Auto", "Head", "Center", "Feet"};
    private static final String[] MODES = {"Simple", "Wind", "Human"};
    /** Another player has to be this much nearer your crosshair to take over. */
    private static final double SWITCH_SHARE = 0.6;
    private static final long HISTORY_MS = 400;
    /** A locked player out of sight this briefly is still the same move. */
    private static final long LOCK_GRACE_MS = 400;
    /** A locked player may drift this much past Range before it lets go. */
    private static final double LOCK_EXTRA_RANGE = 1.0;
    /** How far a Human aim may drift from them before it flicks again, in degrees. */
    private static final double FLICK_FROM = 9;
    /** How far away a player may be, to their hitbox (your reach). */
    static final Num RANGE = Num.tenths(4.5, 6, " blocks");
    /** Simple: its top turn speed, a new one from this range for each target. */
    static final Num SPEED = Num.range(600, 800, 3600, 10, "°/s");
    private static final MouseSteps MOUSE = new MouseSteps();
    /** How wide around your crosshair it looks for players. */
    static final Num FOV = Num.value(120, 360, 10, "°");
    /** How much of your own mouse movement it holds back while aiming. */
    static final Num IGNORE_MOUSE = Num.percent(0);
    /** How far ahead of a moving player it aims: 0% is where they are now. */
    static final Num PREDICTION = Num.percent(0);
    /** Human: how fast its flicks and follow are, 1.0x being a normal hand. */
    static final Num HUMAN_SPEED = Num.tenths(1.5, 2.0, "x");
    /** Human: how long it takes to react to them moving. */
    static final Num REACTION = Num.value(80, 300, 5, " ms");
    /** Human: how far past it goes on a big flick, as a share of the flick. */
    static final Num OVERSHOOT = Num.value(10, 30, 1, "%");
    private static volatile int mode;
    private static volatile boolean vertical = true;
    private static volatile boolean driftLock = true;
    private static volatile int aimAt = 2;
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
    private static Player target;
    private static boolean haveLast;
    private static float lastYaw;
    private static float lastPitch;
    private static String lastNoteKind = "";
    private static long lastNoteMs;
    // The current move.
    private static double topSpeed;
    private static double speedNow;
    private static double velYaw;
    private static double velPitch;
    private static double windYaw;
    private static double windPitch;
    private static double windClock;
    private static Vec3 smoothSpeed = Vec3.ZERO;
    private static final ArrayDeque<long[]> HISTORY_TIMES = new ArrayDeque<>();
    private static final ArrayDeque<Vec3> HISTORY_SPOTS = new ArrayDeque<>();
    private static boolean flicking;
    // When the locked player was last lost (0: still locked). Back within LOCK_GRACE_MS, it
    // carries on where it was instead of starting over.
    private static long lostMs;
    private static double flickMs;
    private static double flickLength;
    private static double pastYaw;
    private static double pastPitch;

    private AimAssist() {}

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
                || !Settings.combatAllowed(minecraft) || !keyActive(minecraft)) {
            drop();
            WindLock.stop();
            return;
        }
        if (mode == 1) {
            // Wind: the lock-until-yanked mode, its own thing.
            drop();
            if (busyHands(player) && !player.isBlocking()) {
                WindLock.stop(); // eating or drinking
            } else {
                WindLock.update(minecraft);
            }
            return;
        }
        WindLock.stop();
        if (player.onGround() && !ground) {
            drop();
            note("waiting: on the ground (turn on Ground too to help here)", now);
            return;
        }
        if (!whileBlocking && busyHands(player)) {
            drop();
            note("waiting: you are eating, drinking or blocking", now);
            return;
        }
        float partial = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        Vec3 eye = player.getEyePosition(partial);
        Vec3 look = player.getViewVector(partial);
        double range = Math.max(MIN_RANGE, RANGE.tenths());
        double fovHalf = Math.max(MIN_FOV, FOV.low()) / 2.0;
        Player best = null;
        double bestOff = fovHalf >= 180 ? Double.MAX_VALUE : fovHalf;
        double currentOff = Double.MAX_VALUE;
        for (Player other : minecraft.level.players()) {
            if (other == player || !other.isAlive() || other.isSpectator() || UraniumHud.isFriend(other)
                    || ignoreInvisible && other.isInvisible() || ignoreCreative && other.isCreative()) {
                continue;
            }
            // Once locked on, FOV no longer matters and it holds on a bit past Range, so
            // diving over them (they slide far off your crosshair) doesn't lose them.
            boolean locked = other == target && (lostMs == 0 || now - lostMs < LOCK_GRACE_MS);
            AABB box = drawnBox(other, partial);
            if (reach(eye, box) > range + (locked ? LOCK_EXTRA_RANGE : 0)) {
                continue;
            }
            Vec3 spot = spot(eye, look, box, other);
            double off = angleOff(player, eye, spot);
            if (!locked && off >= (fovHalf >= 180 ? Double.MAX_VALUE : fovHalf)
                    || lineOfSight && !canSee(minecraft, player, eye, spot)) {
                continue;
            }
            if (locked) {
                currentOff = off;
                continue; // weighed after the loop
            }
            if (off < bestOff) {
                bestOff = off;
                best = other;
            }
        }
        // Sticks to who it is on until someone is clearly nearer your crosshair.
        if (target != null && currentOff != Double.MAX_VALUE
                && (best == null || bestOff > currentOff * SWITCH_SHARE)) {
            best = target;
        }
        if (best == null) {
            drop(now);
            note("waiting: no player you can see within " + range + " blocks and " + (int) fovHalf
                    + " degrees of your crosshair", now);
            return;
        }
        if (best != target || lostMs != 0 && now - lostMs >= LOCK_GRACE_MS) {
            startMove(best);
        }
        lostMs = 0;
        double seconds = Math.min(0.1, elapsed / 1000.0);
        double drift = driftLock ? WindLock.drift(best, seconds) : 0;
        holdBackMouse(player, drift);
        String name = best.getName().getString();
        if (!name.equals(lastTarget)) {
            lastTarget = name;
            note("aiming at " + name + " (" + MODES[mode] + ")", now);
        }
        Vec3 spot = leadSpot(minecraft, eye, look, best, partial, seconds);
        if (mode == 2) {
            spot = reacted(now, spot);
        }
        float[] want = angles(eye, spot);
        double yawOff = Mth.wrapDegrees(want[0] - player.getYRot());
        double pitchOff = vertical ? want[1] - player.getXRot() : 0;
        double[] turn = mode == 2 ? human(yawOff, pitchOff, seconds) : simple(yawOff, pitchOff, seconds);
        if (drift > 0) {
            // Drift Lock: up to three times as far this frame, never past them.
            double off = Math.hypot(yawOff, pitchOff);
            double size = Math.hypot(turn[0], turn[1]);
            double grow = size < 1e-6 ? 1 : Math.min(1 + 2 * drift, off / size);
            turn[0] *= Math.max(1, grow);
            turn[1] *= Math.max(1, grow);
        }
        float pitch = Mth.clamp(player.getXRot() + (float) turn[1], -90F, 90F);
        float turnedPitch = pitch - player.getXRot();
        // Moved like a mouse turn (Entity.turn), in whole mouse counts like a real mouse.
        MOUSE.turn(player, turn[0], turnedPitch, false);
        remember(player);
        note(String.format(java.util.Locale.ROOT, "helping onto %s: %.0f degrees off, turned %.1f",
                name, Math.hypot(yawOff, pitchOff), Math.hypot(turn[0], turnedPitch)), now);
    }

    /** Nobody to aim at: the next one starts fresh. */
    /** Nobody to aim at right now: the lock is kept a moment in case they're back. */
    private static void drop(long now) {
        if (target != null && lostMs == 0) {
            lostMs = now;
        }
        haveLast = false;
    }

    private static void drop() {
        lastTarget = "";
        target = null;
        lostMs = 0;
        haveLast = false;
    }

    /** A new player: a fresh move, with its own top speed. */
    private static void startMove(Player next) {
        target = next;
        topSpeed = SPEED.pick();
        speedNow = 0;
        velYaw = 0;
        velPitch = 0;
        windYaw = 0;
        windPitch = 0;
        windClock = 0;
        smoothSpeed = Vec3.ZERO;
        HISTORY_TIMES.clear();
        HISTORY_SPOTS.clear();
        flicking = false;
        MOUSE.reset();
    }

    /**
     * Simple: speeds up over about a tenth of a second, and as it gets close wants less and
     * less speed (9 times the degrees left, per second), so it eases onto them.
     */
    private static double[] simple(double yawOff, double pitchOff, double seconds) {
        double off = Math.hypot(yawOff, pitchOff);
        if (off < 0.05 || seconds <= 0) {
            speedNow = 0;
            return new double[] {0, 0};
        }
        double top = PlayStyle.speed(topSpeed);
        double wanted = Math.min(top, off * 9);
        speedNow = Math.min(wanted, speedNow + top / 0.09 * seconds);
        double share = Math.min(1, speedNow * seconds / off);
        return new double[] {yawOff * share, pitchOff * share};
    }

    /**
     * Human: a big gap starts a flick that goes a little past them (Overshoot) and eases in
     * and out like a hand; after it, it follows them smoothly. It aims where they were
     * Reaction ms ago, so it trails them a little like a person does.
     */
    private static double[] human(double yawOff, double pitchOff, double seconds) {
        double off = Math.hypot(yawOff, pitchOff);
        double speed = Math.max(0.1, HUMAN_SPEED.tenths());
        if (!flicking && off > FLICK_FROM) {
            flicking = true;
            flickMs = 0;
            flickLength = (110 + 45 * Math.log(1 + off / 3) / Math.log(2)) / PlayStyle.speed(speed);
            double past = OVERSHOOT.low() / 100.0 * (0.6 + ThreadLocalRandom.current().nextDouble() * 0.8);
            pastYaw = yawOff * past;
            pastPitch = pitchOff * past;
        }
        if (flicking) {
            double before = minimumJerk(flickMs / flickLength);
            flickMs += seconds * 1000;
            double after = minimumJerk(flickMs / flickLength);
            if (flickMs >= flickLength) {
                flicking = false;
            }
            double share = before >= 1 ? 1 : (after - before) / (1 - before);
            return new double[] {(yawOff + pastYaw) * share, (pitchOff + pastPitch) * share};
        }
        if (off < 0.05) {
            return new double[] {0, 0};
        }
        double share = 1 - Math.exp(-seconds * 12 * PlayStyle.speed(speed));
        return new double[] {yawOff * share, pitchOff * share};
    }

    /** How far along a hand's flick is at this share of its time: slow, fast, slow. */
    private static double minimumJerk(double t) {
        double x = Mth.clamp(t, 0, 1);
        return x * x * x * (10 - 15 * x + 6 * x * x);
    }

    /** Human: where they were Reaction ms ago (the newest spot at least that old). */
    private static Vec3 reacted(long now, Vec3 spot) {
        HISTORY_TIMES.addLast(new long[] {now});
        HISTORY_SPOTS.addLast(spot);
        while (HISTORY_TIMES.size() > 1 && now - HISTORY_TIMES.peekFirst()[0] > HISTORY_MS) {
            HISTORY_TIMES.removeFirst();
            HISTORY_SPOTS.removeFirst();
        }
        long wanted = now - REACTION.low();
        Vec3 found = HISTORY_SPOTS.peekFirst();
        var times = HISTORY_TIMES.iterator();
        var spots = HISTORY_SPOTS.iterator();
        while (times.hasNext()) {
            long time = times.next()[0];
            Vec3 at = spots.next();
            if (time > wanted) {
                break;
            }
            found = at;
        }
        return found;
    }

    /**
     * The aim spot on them, moved ahead by Prediction. Their speed is smoothed over about a
     * fifth of a second first, so the spot glides instead of jumping every tick.
     */
    private static Vec3 leadSpot(Minecraft minecraft, Vec3 eye, Vec3 look, Player other, float partial,
            double seconds) {
        Vec3 tickSpeed = new Vec3(other.getX() - other.xo, other.getY() - other.yo, other.getZ() - other.zo);
        smoothSpeed = smoothSpeed.add(tickSpeed.subtract(smoothSpeed).scale(Math.min(1, seconds / 0.2)));
        double lead = PREDICTION.low() / 100.0 * Math.min(MAX_LEAD_TICKS, 1 + AutoStun.pingMs(minecraft) / 50.0);
        AABB box = drawnBox(other, partial).move(smoothSpeed.scale(lead));
        return spot(eye, look, box, other);
    }

    /**
     * Ignore mouse: undoes that share of how far your mouse turned you since the last step,
     * so the aim is harder to pull off the target.
     */
    private static void holdBackMouse(LocalPlayer player, double drift) {
        double share = Math.max(IGNORE_MOUSE.low() / 100.0, drift);
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

    /** Their hitbox where it is drawn this frame (smooth between ticks). */
    private static AABB drawnBox(Player target, float partial) {
        return target.getBoundingBox().move(target.getPosition(partial).subtract(target.position()));
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

    /** Whether it would aim right now (on, and its key held, toggled on, or Always). */
    static boolean isActive(Minecraft minecraft) {
        return enabled && Mc.screen(minecraft) == null && keyActive(minecraft);
    }

    /**
     * While Aim Assist is on (key held, toggled on, or Always), a gold glow over the spear's
     * hotbar slot, so you can see it's working at a glance. Drawn by the HUD.
     */
    static void drawSpearGlow(net.minecraft.client.gui.GuiGraphicsExtractor graphics, Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player == null || Mc.hudHidden(minecraft) || !isActive(minecraft)) {
            return;
        }
        int left = graphics.guiWidth() / 2 - 90;
        int top = graphics.guiHeight() - 19;
        // a slow breathe, so it reads as "on" without being loud
        int alpha = 0x50 + (int) (0x30 * (0.5 + 0.5 * Math.sin(System.nanoTime() / 2.5E8)));
        for (int slot = 0; slot < 9; slot++) {
            if (!SpearDash.isSpear(player.getInventory().getItem(slot))) {
                continue;
            }
            int x = left + slot * 20 + 2;
            graphics.fill(x - 1, top - 1, x + 17, top + 17, (alpha << 24) | 0xFFC21A);
            graphics.fill(x - 1, top - 1, x + 17, top, 0xFFFFD54F);
            graphics.fill(x - 1, top + 16, x + 17, top + 17, 0xFFFFD54F);
            graphics.fill(x - 1, top, x, top + 16, 0xFFFFD54F);
            graphics.fill(x + 16, top, x + 17, top + 16, 0xFFFFD54F);
        }
    }

    static String modeLabel() {
        return MODES[mode];
    }

    static int mode() {
        return mode;
    }

    static void setMode(int value) {
        mode = Math.floorMod(value, MODES.length);
        target = null;
    }

    static void cycleMode() {
        setMode(mode + 1);
    }

    static boolean isDriftLock() {
        return driftLock;
    }

    static void setDriftLock(boolean on) {
        driftLock = on;
    }

    static void toggleDriftLock() {
        driftLock = !driftLock;
    }

    static boolean isVertical() {
        return vertical;
    }

    static void setVertical(boolean on) {
        vertical = on;
    }

    static void toggleVertical() {
        vertical = !vertical;
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
        SpearDash.setStatus(toggledOn ? "Aim Assist on" : "Aim Assist off");
    }

    /** Logs what the helper is doing, when that changes or once a second while it keeps on. */
    private static void note(String what, long now) {
        String kind = what.contains(":") ? what.substring(0, what.indexOf(':')) : what;
        if (!kind.equals(lastNoteKind) || now - lastNoteMs >= LOG_GAP_MS) {
            UraniumLog.log("aim assist: " + what);
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
        drop();
        WindLock.reset();
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
