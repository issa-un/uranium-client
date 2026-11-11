package app;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Pearl Catch: on its bind (or your right-click with a pearl, with On right-click), throws
 * the pearl along your view, works out its whole flight, and throws a wind charge after it
 * on the straight line that meets it mid-air. A pearl that hits a wind charge lands there
 * (wind charges are redirectable projectiles, which other projectiles collide with), so you
 * teleport to that point in the air.
 *
 * The flight is the game's own: a pearl leaves from 0.1 below your eyes at 1.5 blocks a
 * tick plus your movement, then each tick loses 0.03 to gravity, keeps 99% of its speed and
 * moves (ThrowableProjectile.tick). A wind charge leaves from your eyes at 1.5 plus your
 * movement and flies straight without slowing (AbstractWindCharge: no acceleration, inertia
 * 1). The game adds a small random spread to both, so a catch can still miss by a little.
 * The pearl looks for the charge along its move each tick, against where the charge was
 * before its own move; the meet point is put on that move.
 *
 * Both fly at 1.5, so the charge cannot go at once: it makes up the pearl's head start by
 * taking the short, straight way. It waits on the charge's slot for the tick that puts it on
 * the chosen point, then turns only the throw toward it (your camera never moves) and
 * switches back to your slot.
 */
final class PearlCatch {
    private static final double SPEED = 1.5;
    private static final double GRAVITY = 0.03;
    private static final double DRAG = 0.99;
    private static final double PEARL_BELOW_EYES = 0.1;
    // How far ahead the flight is worked out, and the longest wait before the charge.
    private static final int MAX_TICKS = 80;
    private static final int MAX_WAIT = 40;
    // The catch is the soonest meet at least MIN_RISE above your eyes, the way players throw
    // it by hand: pearl, wind charge right behind it, and you are up there within a few ticks.
    // Among meets that soon, one that still works with the charge a server tick early or late
    // wins, since the server's tick can fall either side of the gap between the two throws.
    private static final double MIN_RISE = 1;
    // How close the pearl's move must pass the charge to hit it: half the charge's width,
    // plus the margin a projectile adds once it is a few ticks old (ProjectileUtil).
    private static final double CHARGE_HALF = 0.15625;
    private static final double MAX_MARGIN = 0.3;
    // Moves the meet a tick later or earlier, for a server whose ticks line up differently.
    private static final int[] TIMINGS = {0, 1, -1, 2, -2};
    // How long past the meet and your ping to wait for the teleport, and how close counts.
    private static final int RESULT_TICKS = 10;
    private static final double CAUGHT_WITHIN = 2.5;
    private static volatile boolean enabled;
    private static volatile boolean onRightClick = true;
    private static volatile int timing;
    private static volatile int keyCode = -1;
    private static volatile int mouseButton = -1;
    private static volatile boolean awaitingBinding;
    // The catch in progress (render thread only): the pearl's flight from its throw, the
    // pearl's age when the charge goes out, and the slots.
    private static boolean running;
    private static List<Vec3> flight;
    private static int throwTick;
    private static int fireAge;
    private static double throwEyeY;
    private static int windSlot;
    private static int previousSlot;
    // After the charge is out: where you should arrive, and by when.
    private static Vec3 watchPoint;
    private static int watchUntilTick;
    private static int tries;
    private static int caught;

    /**
     * A meet: the point, the pearl's age there, the charge's ticks of flight, its throw
     * direction, and how many of the charge going out a tick early, on time or late still hit.
     */
    private record Plan(Vec3 point, int meetAge, int flightTicks, Vec3 direction, int robust) {}

    private PearlCatch() {}

    /** Runs from its bind or your right-click; true if the pearl went out (or a catch is on). */
    static boolean activate(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (!enabled || player == null || minecraft.gameMode == null || Mc.screen(minecraft) != null) {
            return false;
        }
        if (!SpearSettings.combatAllowed(minecraft)) {
            MinecraftSpearDash.setStatus("Off on this server");
            return false;
        }
        if (running) {
            return true; // a second press would throw the wind charge you are holding
        }
        int selected = player.getInventory().getSelectedSlot();
        int pearl = player.getMainHandItem().is(Items.ENDER_PEARL) ? selected
                : BucketUse.findSlot(player, Items.ENDER_PEARL);
        int wind = BucketUse.findSlot(player, Items.WIND_CHARGE);
        if (pearl < 0) {
            MinecraftSpearDash.setStatus("No ender pearl in hotbar");
            return false;
        }
        if (wind < 0) {
            MinecraftSpearDash.setStatus("No wind charge in hotbar");
            return false;
        }
        if (coolingDown(player, pearl) || coolingDown(player, wind)) {
            MinecraftSpearDash.setStatus("Pearl or wind charge cooling down");
            return false;
        }
        Vec3 eye = player.getEyePosition();
        Vec3 drift = drift(player);
        List<Vec3> path = pearlFlight(minecraft, eye.subtract(0, PEARL_BELOW_EYES, 0),
                direction(player.getYRot(), player.getXRot()).scale(SPEED).add(drift));
        // Planned as if you stay where you are; the throw is worked out again when it goes.
        Plan best = null;
        int bestWait = -1;
        for (int wait = 1; wait <= MAX_WAIT; wait++) {
            Plan plan = solve(minecraft, path, wait, eye, drift, eye.y + MIN_RISE);
            if (plan != null && better(plan, best)) {
                best = plan;
                bestWait = wait;
            }
        }
        if (best == null) {
            MinecraftSpearDash.setStatus("No catch point on this throw");
            return false;
        }
        previousSlot = selected;
        select(minecraft, pearl);
        InteractionResult result = minecraft.gameMode.useItem(player, InteractionHand.MAIN_HAND);
        if (Mc.swingsOnClient(result)) {
            Mc.swing(player, InteractionHand.MAIN_HAND);
        }
        if (!result.consumesAction()) {
            select(minecraft, previousSlot);
            MinecraftSpearDash.setStatus("Pearl not thrown");
            return true;
        }
        select(minecraft, wind);
        windSlot = wind;
        flight = path;
        throwTick = player.tickCount;
        fireAge = bestWait;
        throwEyeY = eye.y;
        running = true;
        MinecraftSpearDash.setStatus("Catch in " + bestWait + " ticks, "
                + String.format(Locale.ROOT, "%.1f", best.point().y - eye.y) + " up");
        return true;
    }

    /**
     * Your right-click, with On right-click on: a pearl in your hand is thrown as a catch.
     * True if the click is used; with no catch point the pearl goes out as a normal throw.
     */
    static boolean onUseClick(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (!enabled || !onRightClick || player == null || Mc.screen(minecraft) != null) {
            return false;
        }
        if (running) {
            return true; // you are holding the wind charge, which must wait for its tick
        }
        return player.getMainHandItem().is(Items.ENDER_PEARL) && activate(minecraft);
    }

    /** Called from the input loop: throws the wind charge on its tick, then watches the result. */
    static void update(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (watchPoint != null) {
            watchResult(player);
        }
        if (!running) {
            return;
        }
        if (player == null || minecraft.gameMode == null) {
            running = false;
            return;
        }
        if (Mc.screen(minecraft) != null) {
            running = false;
            swapBack(minecraft);
            MinecraftSpearDash.setStatus("Pearl catch cancelled");
            return;
        }
        if (player.getInventory().getSelectedSlot() != windSlot) {
            // You picked another slot meanwhile; that choice is kept.
            running = false;
            MinecraftSpearDash.setStatus("Pearl catch stopped");
            SpearLog.log("pearl catch: stopped, hotbar moved to " + (player.getInventory().getSelectedSlot() + 1));
            return;
        }
        int age = player.tickCount - throwTick;
        if (age < fireAge) {
            return;
        }
        running = false;
        // From where you are now, in case you moved while it waited.
        Vec3 eye = player.getEyePosition();
        Plan plan = solve(minecraft, flight, age, eye, drift(player), throwEyeY + MIN_RISE);
        if (plan == null) {
            swapBack(minecraft);
            MinecraftSpearDash.setStatus("Lost the catch");
            SpearLog.log("pearl catch: no meet point left at pearl age " + age);
            return;
        }
        throwToward(minecraft, player, plan.direction());
        swapBack(minecraft);
        tries++;
        watchPoint = plan.point();
        watchUntilTick = player.tickCount + plan.meetAge() - age + ShieldBreak.pingMs(minecraft) / 50 + RESULT_TICKS;
        SpearLog.log("pearl catch: charge at pearl age " + age + " (planned " + fireAge + "), meets in "
                + plan.flightTicks() + " ticks at age " + plan.meetAge() + ", "
                + String.format(Locale.ROOT, "%.1f", plan.point().y - throwEyeY) + " blocks up");
    }

    /** Whether you arrived at the meet point (the pearl hit the charge) before time ran out. */
    private static void watchResult(LocalPlayer player) {
        if (player == null) {
            watchPoint = null;
            return;
        }
        if (player.position().distanceTo(watchPoint) < CAUGHT_WITHIN) {
            caught++;
            MinecraftSpearDash.setStatus("Caught (" + caught + "/" + tries + ")");
            SpearLog.log("pearl catch result: teleported to the meet point");
            watchPoint = null;
        } else if (player.tickCount > watchUntilTick) {
            MinecraftSpearDash.setStatus("Missed the catch (" + caught + "/" + tries + ")");
            SpearLog.log("pearl catch result: no teleport, ended "
                    + String.format(Locale.ROOT, "%.1f", player.position().distanceTo(watchPoint)) + " blocks from the meet point");
            watchPoint = null;
        }
    }

    /**
     * The best meet for a wind charge thrown now, with the pearl at this age: after j ticks
     * of flight the charge is at from + (direction * SPEED + drift) * j, and the pearl looks
     * for it on its move from age + j to age + j + 1 (shifted by Timing). Null if no point it
     * can reach in time is at least minY high with nothing in the way.
     */
    private static Plan solve(Minecraft minecraft, List<Vec3> path, int age, Vec3 from, Vec3 drift, double minY) {
        Plan best = null;
        int shift = TIMINGS[timing];
        for (int flightTicks = 1; ; flightTicks++) {
            int meetAge = age + flightTicks + 1 + shift;
            if (meetAge >= path.size()) {
                return best;
            }
            if (meetAge <= age) {
                continue;
            }
            Vec3 carried = from.add(drift.scale(flightTicks));
            Vec3 point = meetOnMove(path.get(meetAge - 1), path.get(meetAge), carried, SPEED * flightTicks);
            if (point == null || point.y < minY || blocked(minecraft, from, point)) {
                continue;
            }
            Vec3 direction = point.subtract(carried).normalize();
            Vec3 velocity = direction.scale(SPEED).add(drift);
            int robust = 0;
            for (int early = -1; early <= 1; early++) {
                if (hits(path, age + early, from, velocity, minY)) {
                    robust++;
                }
            }
            Plan plan = new Plan(point, meetAge, flightTicks, direction, robust);
            if (better(plan, best)) {
                best = plan;
            }
        }
    }

    /**
     * Whether a charge that goes out with the pearl at this age, from there at this
     * velocity, is hit by the pearl at least minY high: each tick the pearl moves from its
     * age n to n + 1 and looks for the charge where it was before its own move. The first
     * touch is where the pearl lands, so a touch too low (a charge out in the same tick as
     * the pearl meets it at your face) is a failed catch.
     */
    private static boolean hits(List<Vec3> path, int spawnAge, Vec3 from, Vec3 velocity, double minY) {
        if (spawnAge < 0) {
            return false;
        }
        for (int age = spawnAge; age + 1 < path.size(); age++) {
            Vec3 charge = from.add(velocity.scale(age - spawnAge));
            double reach = CHARGE_HALF + Math.max(0, Math.min(MAX_MARGIN, (age - 2) / 20.0));
            if (crossesBox(path.get(age), path.get(age + 1), charge, reach)) {
                return charge.y >= minY;
            }
        }
        return false;
    }

    /** Whether the move from a to b passes through the box of this half-size around center. */
    private static boolean crossesBox(Vec3 a, Vec3 b, Vec3 center, double half) {
        double enter = 0;
        double leave = 1;
        double[] from = {a.x, a.y, a.z};
        double[] to = {b.x, b.y, b.z};
        double[] mid = {center.x, center.y, center.z};
        for (int axis = 0; axis < 3; axis++) {
            double move = to[axis] - from[axis];
            double low = mid[axis] - half;
            double high = mid[axis] + half;
            if (Math.abs(move) < 1e-9) {
                if (from[axis] < low || from[axis] > high) {
                    return false;
                }
                continue;
            }
            double t1 = (low - from[axis]) / move;
            double t2 = (high - from[axis]) / move;
            enter = Math.max(enter, Math.min(t1, t2));
            leave = Math.min(leave, Math.max(t1, t2));
            if (enter > leave) {
                return false;
            }
        }
        return true;
    }

    /** The point on the move from a to b that is exactly reach from center, or null. */
    private static Vec3 meetOnMove(Vec3 a, Vec3 b, Vec3 center, double reach) {
        // |a + (b - a) s - center| = reach, a quadratic in s.
        Vec3 move = b.subtract(a);
        Vec3 offset = a.subtract(center);
        double qa = move.dot(move);
        double qb = 2 * offset.dot(move);
        double qc = offset.dot(offset) - reach * reach;
        double discriminant = qb * qb - 4 * qa * qc;
        if (qa == 0 || discriminant < 0) {
            return null;
        }
        double root = Math.sqrt(discriminant);
        for (double s : new double[] {(-qb - root) / (2 * qa), (-qb + root) / (2 * qa)}) {
            if (s >= 0 && s <= 1) {
                return a.add(move.scale(s));
            }
        }
        return null;
    }

    private static boolean better(Plan plan, Plan best) {
        if (best == null) {
            return true;
        }
        if (plan.robust() != best.robust()) {
            return plan.robust() > best.robust();
        }
        return plan.meetAge() < best.meetAge();
    }

    static boolean isRunning() {
        return running;
    }

    /** The pearl's position each tick from its throw, up to the first block it would hit. */
    private static List<Vec3> pearlFlight(Minecraft minecraft, Vec3 start, Vec3 velocity) {
        List<Vec3> path = new ArrayList<>();
        path.add(start);
        Vec3 position = start;
        Vec3 motion = velocity;
        for (int age = 1; age <= MAX_TICKS; age++) {
            motion = new Vec3(motion.x, motion.y - GRAVITY, motion.z).scale(DRAG);
            Vec3 next = position.add(motion);
            if (blocked(minecraft, position, next)) {
                break;
            }
            path.add(next);
            position = next;
        }
        return path;
    }

    private static boolean blocked(Minecraft minecraft, Vec3 from, Vec3 to) {
        return minecraft.level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                minecraft.player)).getType() != HitResult.Type.MISS;
    }

    /** Your movement a throw adds to the item's speed: all of it in the air, none upward on the ground. */
    private static Vec3 drift(LocalPlayer player) {
        Vec3 motion = player.getDeltaMovement();
        return player.onGround() ? new Vec3(motion.x, 0, motion.z) : motion;
    }

    /** The unit direction a throw with this view goes (Projectile.shootFromRotation). */
    private static Vec3 direction(float yaw, float pitch) {
        double y = Math.toRadians(yaw);
        double p = Math.toRadians(pitch);
        return new Vec3(-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p));
    }

    /**
     * Throws the held wind charge in this direction: the server is told you look that way
     * just for the throw, and your real view right after, so your camera never moves.
     */
    private static void throwToward(Minecraft minecraft, LocalPlayer player, Vec3 direction) {
        float[] aim = BucketUse.lookAt(Vec3.ZERO, direction);
        float yaw = player.getYRot();
        float pitch = player.getXRot();
        float yawO = player.yRotO;
        float pitchO = player.xRotO;
        ClientPacketListener connection = minecraft.getConnection();
        if (connection != null) {
            connection.send(new ServerboundMovePlayerPacket.Rot(aim[0], aim[1], player.onGround(),
                    player.horizontalCollision));
        }
        player.setYRot(aim[0]);
        player.setXRot(aim[1]);
        try {
            InteractionResult result = minecraft.gameMode.useItem(player, InteractionHand.MAIN_HAND);
            if (Mc.swingsOnClient(result)) {
                Mc.swing(player, InteractionHand.MAIN_HAND);
            }
        } finally {
            player.setYRot(yaw);
            player.setXRot(pitch);
            player.yRotO = yawO;
            player.xRotO = pitchO;
            if (connection != null) {
                connection.send(new ServerboundMovePlayerPacket.Rot(yaw, pitch, player.onGround(),
                        player.horizontalCollision));
            }
        }
    }

    private static boolean coolingDown(LocalPlayer player, int slot) {
        return player.getCooldowns().isOnCooldown(player.getInventory().getItem(slot));
    }

    private static void select(Minecraft minecraft, int slot) {
        if (minecraft.player.getInventory().getSelectedSlot() != slot) {
            minecraft.player.getInventory().setSelectedSlot(slot);
            MinecraftSpearDash.sendSelectedSlot(minecraft, false);
        }
    }

    private static void swapBack(Minecraft minecraft) {
        if (minecraft.player != null && minecraft.player.getInventory().getSelectedSlot() == windSlot) {
            select(minecraft, previousSlot);
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

    static boolean isOnRightClick() {
        return onRightClick;
    }

    static void setOnRightClick(boolean on) {
        onRightClick = on;
    }

    static void toggleOnRightClick() {
        onRightClick = !onRightClick;
    }

    static int timing() {
        return timing;
    }

    static String timingLabel() {
        int shift = TIMINGS[timing];
        return shift == 0 ? "On time" : (shift > 0 ? "+" : "") + shift + (Math.abs(shift) == 1 ? " tick" : " ticks");
    }

    static void setTiming(int value) {
        timing = Math.floorMod(value, TIMINGS.length);
    }

    static void cycleTiming() {
        timing = (timing + 1) % TIMINGS.length;
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

    /** Turns the module off when this injection is unloaded, switching back mid-catch. */
    static void reset(Minecraft minecraft) {
        if (running) {
            running = false;
            swapBack(minecraft);
        }
        watchPoint = null;
        enabled = false;
        awaitingBinding = false;
    }
}
