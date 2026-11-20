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

/**
 * Aim Assist: you look at a player, it locks onto their torso, and it stays locked until you
 * yank your mouse away. Just on or off.
 *
 * Inside:
 * - Picks the player nearest your crosshair that you can see within 6 blocks, in any
 *   direction: someone behind you counts too. Friends are never picked.
 * - Locked, it snaps your view onto the middle of their torso every frame (90% there in under 50 ms,
 *   in whole mouse counts like a real mouse), wherever they go and whatever you hold.
 * - Small mouse moves don't break it: it pulls you back. A hard flick away does. Your mouse
 *   movement against it adds up and fades over about a sixth of a second; past 20 degrees
 *   (and once you're 10 degrees off them) it lets go, and doesn't lock onto anyone for a
 *   second so you can look where you want.
 * - It also lets go when they die, get more than 8 blocks away, or stay behind a wall for a
 *   second.
 * - It holds off while you eat or drink and while a menu is open.
 * - While locked, your spear's hotbar slot glows gold.
 * - Drift Lock (a setting, on by default): the further the locked player is thrown off the
 *   path they were on (knockback, a wind charge, a pearl), the more of your mouse it ignores
 *   and the faster it turns after them. Their path is guessed from last tick's movement the
 *   way the game moves them (drag and gravity); a gap past 0.15 blocks a tick starts it, 0.6
 *   is full: all of your mouse ignored and three times the speed. It fades over about a
 *   third of a second once they're back on a normal path.
 */
final class AimAssist {
    private static final double PICK_RANGE = 6;
    private static final double KEEP_RANGE = 8;
    private static final double PICK_CONE = 181; // all the way around: behind you too
    /** Where on their hitbox the torso's middle is, from the feet up. */
    private static final double TORSO = 0.6;
    /** How quickly it closes the gap: 1 - e^(-50 t), 90% of the way in under 50 ms. */
    private static final double GLIDE = 50;
    private static final double YANK_DEGREES = 20;
    private static final double YANK_OFF = 10;
    private static final double YANK_FADE_S = 0.15;
    private static final long REST_AFTER_YANK_MS = 1000;
    private static final long HIDDEN_MS = 1000;
    private static final MouseSteps MOUSE = new MouseSteps();
    private static volatile boolean enabled;
    // Render thread only.
    private static Player target;
    private static long lastFrameNs;
    private static boolean haveView;
    private static float viewYaw;
    private static float viewPitch;
    private static double yank;
    private static long restUntilMs;
    private static long seenMs;
    private static volatile boolean driftLock = true;
    private static final double DRIFT_FROM = 0.15;
    private static final double DRIFT_FULL = 0.6;
    private static final double DRIFT_FADE_S = 0.3;
    // The locked player's movement last tick, and how drifted they are.
    private static int driftTick = -1;
    private static Vec3 move;
    private static double drift;

    private AimAssist() {}

    /** Called every frame by the HUD, and from the input loop for when the HUD is hidden. */
    static void update(Minecraft minecraft) {
        long nowNs = System.nanoTime();
        if (lastFrameNs != 0 && nowNs - lastFrameNs < 2_000_000L) {
            return; // already turned this frame (the HUD and the input loop both call in)
        }
        double seconds = lastFrameNs == 0 ? 0 : Math.min(0.1, (nowNs - lastFrameNs) / 1.0E9);
        lastFrameNs = nowNs;
        long now = nowNs / 1_000_000;
        LocalPlayer player = minecraft.player;
        if (!enabled || player == null || minecraft.level == null || Mc.screen(minecraft) != null
                || !SpearSettings.combatAllowed(minecraft) || eating(player)) {
            haveView = false;
            return;
        }
        float partial = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        Vec3 eye = player.getEyePosition(partial);

        // How far you turned the view yourself since the last frame (it was left at viewYaw).
        double handYaw = haveView ? Mth.wrapDegrees(player.getYRot() - viewYaw) : 0;
        double handPitch = haveView ? player.getXRot() - viewPitch : 0;

        if (target != null && !keep(minecraft, player, eye, partial, now)) {
            letGo("");
        }
        if (target == null) {
            if (now < restUntilMs) {
                remember(player);
                return;
            }
            target = pick(minecraft, player, eye, partial);
            if (target == null) {
                remember(player);
                return;
            }
            yank = 0;
            seenMs = now;
            driftTick = -1;
            drift = 0;
            move = null;
            MOUSE.reset();
            SpearLog.log("aim assist: locked onto " + target.getName().getString());
        }

        // Drift Lock: thrown off their path, your mouse counts less and it turns faster.
        double strength = driftLock ? drift(seconds) : 0;
        if (strength > 0 && haveView) {
            float undoYaw = (float) (handYaw * strength);
            float undoPitch = (float) (handPitch * strength);
            player.setYRot(player.getYRot() - undoYaw);
            player.setXRot(Mth.clamp(player.getXRot() - undoPitch, -90F, 90F));
            player.yRotO -= undoYaw;
            player.xRotO -= undoPitch;
        }
        yank = yank * Math.exp(-seconds / YANK_FADE_S) + Math.hypot(handYaw, handPitch) * (1 - strength);

        float[] want = angles(eye, torso(target, partial));
        double yawOff = Mth.wrapDegrees(want[0] - player.getYRot());
        double pitchOff = want[1] - player.getXRot();
        if (yank > YANK_DEGREES && Math.hypot(yawOff, pitchOff) > YANK_OFF) {
            letGo("you looked away");
            restUntilMs = now + REST_AFTER_YANK_MS;
            remember(player);
            return;
        }
        double share = 1 - Math.exp(-seconds * GLIDE * (1 + 2 * strength));
        float pitch = Mth.clamp(player.getXRot() + (float) (pitchOff * share), -90F, 90F);
        // Sent every frame (still whole mouse counts): waiting for the next 8 ms mouse report
        // made it turn only every other frame, in doubled jumps, which looked choppy.
        MOUSE.turn(player, yawOff * share, pitch - player.getXRot(), true);
        remember(player);
    }

    /**
     * How thrown off their path the locked player is, 0 to 1. Each new tick, where they should
     * have gone (last tick's move, slowed by air drag and pulled by gravity) is compared with
     * where they went; the gap fades over DRIFT_FADE_S.
     */
    private static double drift(double seconds) {
        drift *= Math.exp(-seconds / DRIFT_FADE_S);
        if (target.tickCount != driftTick) {
            driftTick = target.tickCount;
            Vec3 now = new Vec3(target.getX() - target.xo, target.getY() - target.yo, target.getZ() - target.zo);
            if (move != null) {
                Vec3 expected = new Vec3(move.x * 0.91, (move.y - 0.08) * 0.98, move.z * 0.91);
                if (target.onGround()) {
                    expected = new Vec3(move.x * 0.6, 0, move.z * 0.6);
                }
                double gap = now.subtract(expected).length();
                double strength = Mth.clamp((gap - DRIFT_FROM) / (DRIFT_FULL - DRIFT_FROM), 0, 1);
                if (strength > drift) {
                    if (drift < 0.05 && strength > 0.3) {
                        SpearLog.log(String.format(java.util.Locale.ROOT,
                                "aim assist: drift lock, %s thrown %.2f blocks a tick off their path",
                                target.getName().getString(), gap));
                    }
                    drift = strength;
                }
            }
            move = now;
        }
        return drift;
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

    private static void letGo(String why) {
        if (target != null) {
            SpearLog.log("aim assist: let go of " + target.getName().getString()
                    + (why.isEmpty() ? "" : " (" + why + ")"));
        }
        target = null;
    }

    /** The view as it is after this frame's turn: next frame, any change from it is your hand. */
    private static void remember(LocalPlayer player) {
        viewYaw = player.getYRot();
        viewPitch = player.getXRot();
        haveView = true;
    }

    /** Whether the locked player is still worth holding: alive, near, not hidden for long. */
    private static boolean keep(Minecraft minecraft, LocalPlayer player, Vec3 eye, float partial, long now) {
        if (!target.isAlive() || target.isRemoved() || target.isSpectator() || target.level() != player.level()) {
            return false;
        }
        if (reach(eye, drawnBox(target, partial)) > KEEP_RANGE) {
            return false;
        }
        if (canSee(minecraft, player, eye, torso(target, partial))) {
            seenMs = now;
        }
        return now - seenMs < HIDDEN_MS;
    }

    /** The player nearest your crosshair that you can see, in the cone and in range. */
    private static Player pick(Minecraft minecraft, LocalPlayer player, Vec3 eye, float partial) {
        Player best = null;
        double bestOff = PICK_CONE;
        // Nearest your crosshair first, so whoever's in front wins; behind you counts too.
        for (Player other : minecraft.level.players()) {
            if (other == player || !other.isAlive() || other.isSpectator() || other.isInvisible()
                    || VelocityHud.isFriend(other)) {
                continue;
            }
            if (reach(eye, drawnBox(other, partial)) > PICK_RANGE) {
                continue;
            }
            Vec3 spot = torso(other, partial);
            float[] want = angles(eye, spot);
            double off = Math.hypot(Mth.wrapDegrees(want[0] - player.getYRot()), want[1] - player.getXRot());
            if (off < bestOff && canSee(minecraft, player, eye, spot)) {
                bestOff = off;
                best = other;
            }
        }
        return best;
    }

    /** Eating or drinking: turning you then would only get in the way. */
    private static boolean eating(LocalPlayer player) {
        if (!player.isUsingItem()) {
            return false;
        }
        ItemStack using = player.getUseItem();
        return using.has(DataComponents.FOOD) || using.has(DataComponents.CONSUMABLE);
    }

    /** The middle of their torso, where they're drawn this frame. */
    private static Vec3 torso(Player other, float partial) {
        AABB box = drawnBox(other, partial);
        return new Vec3((box.minX + box.maxX) / 2, box.minY + box.getYsize() * TORSO, (box.minZ + box.maxZ) / 2);
    }

    private static AABB drawnBox(Player other, float partial) {
        return other.getBoundingBox().move(other.getPosition(partial).subtract(other.position()));
    }

    /** Distance from your eyes to the nearest point of their hitbox. */
    private static double reach(Vec3 eye, AABB box) {
        return eye.distanceTo(new Vec3(Mth.clamp(eye.x, box.minX, box.maxX), Mth.clamp(eye.y, box.minY, box.maxY),
                Mth.clamp(eye.z, box.minZ, box.maxZ)));
    }

    private static boolean canSee(Minecraft minecraft, LocalPlayer player, Vec3 eye, Vec3 point) {
        return minecraft.level.clip(new ClipContext(eye, point, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                player)).getType() == HitResult.Type.MISS;
    }

    /** The view {yaw, pitch} from the eye to the point. */
    private static float[] angles(Vec3 eye, Vec3 point) {
        Vec3 to = point.subtract(eye);
        return new float[] {
            (float) Math.toDegrees(Math.atan2(-to.x, to.z)),
            (float) Math.toDegrees(-Math.atan2(to.y, Math.hypot(to.x, to.z)))};
    }

    /** Locked onto someone right now. */
    static boolean isActive(Minecraft minecraft) {
        return enabled && target != null && Mc.screen(minecraft) == null;
    }

    /** While locked, a gold glow over your spear's hotbar slot. Drawn by the HUD. */
    static void drawSpearGlow(net.minecraft.client.gui.GuiGraphicsExtractor graphics, Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player == null || Mc.hudHidden(minecraft) || !isActive(minecraft)) {
            return;
        }
        int left = graphics.guiWidth() / 2 - 90;
        int top = graphics.guiHeight() - 19;
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

    static boolean isEnabled() {
        return enabled;
    }

    static void setEnabled(boolean on) {
        enabled = on;
        if (!on) {
            target = null;
        }
    }

    static void toggleEnabled() {
        setEnabled(!enabled);
    }

    /** Turns the module off when this injection is unloaded. */
    static void reset() {
        enabled = false;
        target = null;
        haveView = false;
    }
}
