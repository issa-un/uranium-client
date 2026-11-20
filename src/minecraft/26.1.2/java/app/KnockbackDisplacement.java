package app;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.minecart.MinecartTNT;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Knockback Steer: your sprint hit sends them where you want, not just straight back.
 *
 * How the game does it: a sprint hit (or a Knockback weapon) pushes twice. The small base
 * push goes straight away from you; the bigger extra push goes the way your camera faces
 * (Player.causeExtraKnockback uses your yaw). So turning your view sideways at the hit,
 * while your crosshair is still on them, bends where they fly.
 *
 * When you click a sprint hit on a player, it looks for something to push them into along
 * every direction your crosshair can still reach on their hitbox: lava, fire, cobwebs,
 * cactus, magma, a TNT cart, or a drop of 3+ blocks. It flies their knockback the way the
 * game does for each and picks the best. If one is worth it, your click is held, your view
 * turns there (whole mouse steps, like your hand), and the hit goes out on the next tick,
 * once the server has your new view (it takes your view from the movement update, which is
 * sent once a tick, while a hit goes out at once).
 *
 * Toward you (mode): knockback can't pull them in, both pushes go away from you. But when
 * they strafe while you corner them, the sideways push can cancel their run, so they land
 * in your path and close (where you'll be once they land) instead of off to the side. Both
 * does hazards first and this when there is none. Nothing to push them into, no sprint,
 * cooldown not full, or they left your crosshair: your click goes out as it is.
 */
final class KnockbackDisplacement {
    static final Num MAX_TURN = Num.value(25, 60, 1, "°");
    private static final String[] MODES = {"Both", "Hazards", "Toward you"};
    // Toward you: steer only if they land at least this much closer to where you'll be.
    private static final double WORTH_BLOCKS = 0.3;
    private static volatile int mode;
    // Holding your click longer than this isn't worth it: the hit goes as it is.
    private static final long GIVE_UP_MS = 160;
    private static volatile boolean enabled;
    // Render thread only.
    private static final HumanAim AIM = new HumanAim();
    private static int targetId = -1;
    private static long heldAtMs;
    private static int turnedTick = -1;
    private static String goal = "";
    // Where the last flown knockback landed them.
    private static Vec3 landing = Vec3.ZERO;

    private KnockbackDisplacement() {}

    private static long now() {
        return System.nanoTime() / 1_000_000;
    }

    /** Your attack click: true if it was held to steer the hit. */
    static boolean onAttackClick(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (!enabled || player == null || minecraft.level == null || Mc.screen(minecraft) != null
                || targetId >= 0 || !Settings.combatAllowed(minecraft)) {
            return false;
        }
        if (!(minecraft.hitResult instanceof EntityHitResult hit) || !(hit.getEntity() instanceof Player target)
                || !target.isAlive() || UraniumHud.isFriend(target)) {
            return false;
        }
        boolean knockbackWeapon = MaceSwap.enchantmentLevel(player.getMainHandItem(), Enchantments.KNOCKBACK) > 0;
        // The extra push only comes with a sprint hit at (almost) full cooldown, or a Knockback weapon.
        if (!(player.isSprinting() && player.getAttackStrengthScale(0.5F) > 0.9F) && !knockbackWeapon) {
            return false;
        }
        float[] range = yawRange(player, target);
        if (range == null) {
            return false;
        }
        int steps = 12;
        float best = player.getYRot();
        String bestGoal = "";
        if (mode != 2) {
            double bestScore = score(minecraft, player, target, player.getYRot());
            bestGoal = goal;
            for (int i = 0; i <= steps; i++) {
                float yaw = range[0] + (range[1] - range[0]) * i / steps;
                double value = score(minecraft, player, target, yaw);
                if (value > bestScore + 0.5) {
                    bestScore = value;
                    best = yaw;
                    bestGoal = goal;
                }
            }
            if (bestScore <= 0) {
                best = player.getYRot();
                bestGoal = "";
            }
        }
        if (bestGoal.isEmpty() && mode != 1) {
            // Toward you: knockback can't pull them in (both pushes go away from you), but
            // the sideways one can cancel their strafe, so they land in your path, close,
            // instead of running off to the side.
            Vec3 me = futureYou(player);
            score(minecraft, player, target, player.getYRot());
            double now = horizontal(landing, me);
            double closest = now;
            for (int i = 0; i <= steps; i++) {
                float yaw = range[0] + (range[1] - range[0]) * i / steps;
                score(minecraft, player, target, yaw);
                double d = horizontal(landing, me);
                if (d < closest) {
                    closest = d;
                    best = yaw;
                }
            }
            if (now - closest >= WORTH_BLOCKS) {
                bestGoal = String.format(java.util.Locale.ROOT, "your path (%.1f blocks closer)", now - closest);
            } else {
                best = player.getYRot();
            }
        }
        float turn = Mth.wrapDegrees(best - player.getYRot());
        if (bestGoal.isEmpty() || Math.abs(turn) < 1.5F) {
            return false; // nothing better: the hit goes as it is
        }
        goal = bestGoal;
        targetId = target.getId();
        heldAtMs = now();
        turnedTick = -1;
        AIM.stop();
        AIM.glide(turn, 0, PlayStyle.turnMs(40 + java.util.concurrent.ThreadLocalRandom.current().nextLong(0, 20)));
        UraniumLog.log(String.format(java.util.Locale.ROOT, "knockback displacement: turning %.1f° to push %s %s %s",
                turn, target.getName().getString(), goal.startsWith("your path") ? "into" : "into", goal));
        return true;
    }

    /** Called every frame by the HUD: moves the turn along. */
    static void frame(Minecraft minecraft) {
        if (targetId >= 0 && minecraft.player != null && AIM.active()) {
            AIM.step(minecraft.player);
        }
    }

    /** Called from the input loop: the hit, once the server has the new view. */
    static void update(Minecraft minecraft) {
        if (targetId < 0) {
            return;
        }
        LocalPlayer player = minecraft.player;
        Entity entity = player == null || minecraft.level == null ? null : minecraft.level.getEntity(targetId);
        if (!(entity instanceof LivingEntity target) || !target.isAlive() || minecraft.gameMode == null) {
            drop("they are gone");
            return;
        }
        if (AIM.active()) {
            if (now() - heldAtMs > GIVE_UP_MS) {
                AIM.stop();
                hitNow(minecraft, player, target, "the turn took too long, hit as it was");
            }
            return;
        }
        if (turnedTick < 0) {
            turnedTick = player.tickCount; // the view is there; the next tick sends it
            return;
        }
        if (player.tickCount <= turnedTick && now() - heldAtMs < GIVE_UP_MS) {
            return;
        }
        hitNow(minecraft, player, target, "into " + goal);
    }

    private static void hitNow(Minecraft minecraft, LocalPlayer player, LivingEntity target, String how) {
        int id = targetId;
        targetId = -1;
        if (!(minecraft.hitResult instanceof EntityHitResult hit) || hit.getEntity().getId() != id) {
            UraniumLog.log("knockback displacement: dropped, they left your crosshair during the turn");
            return;
        }
        TriggerBot.strike(minecraft, player, target, false, now());
        UraniumLog.log("knockback displacement: hit " + (now() - heldAtMs) + " ms after your click, " + how);
    }

    private static void drop(String why) {
        targetId = -1;
        AIM.stop();
        UraniumLog.log("knockback displacement: dropped, " + why);
    }

    /**
     * The yaws (at your pitch) where your crosshair still lands on their hitbox, a little in
     * from each edge and no more than Max turn from where you look; null if it isn't on them.
     */
    private static float[] yawRange(LocalPlayer player, Player target) {
        Vec3 eye = player.getEyePosition();
        AABB box = target.getBoundingBox();
        double reach = player.entityInteractionRange() + 1;
        float now = player.getYRot();
        if (!hits(eye, box, now, player.getXRot(), reach)) {
            return null;
        }
        float max = MAX_TURN.low();
        float low = now;
        float high = now;
        for (float step = 0.5F; step <= max; step += 0.5F) {
            if (!hits(eye, box, now - step, player.getXRot(), reach)) {
                break;
            }
            low = now - step;
        }
        for (float step = 0.5F; step <= max; step += 0.5F) {
            if (!hits(eye, box, now + step, player.getXRot(), reach)) {
                break;
            }
            high = now + step;
        }
        // stay a degree in from the edges, so a little lag can't put the crosshair off them
        low = Math.min(now, low + 1);
        high = Math.max(now, high - 1);
        return new float[] {low, high};
    }

    private static boolean hits(Vec3 eye, AABB box, float yaw, float pitch, double reach) {
        Vec3 look = Vec3.directionFromRotation(pitch, yaw);
        return box.clip(eye, eye.add(look.scale(reach))).isPresent() || box.contains(eye);
    }

    /**
     * How good a hit with this yaw is: flies their knockback the way the game does (the base
     * push away from you, halved by the extra push in the yaw's direction, then air drag and
     * gravity) and scores what they fly into. Sets goal to what it is.
     */
    private static double score(Minecraft minecraft, LocalPlayer player, Player target, float yaw) {
        goal = "";
        double resist = target.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.KNOCKBACK_RESISTANCE);
        double keep = Math.max(0, 1 - resist);
        Vec3 away = new Vec3(target.getX() - player.getX(), 0, target.getZ() - player.getZ());
        away = away.lengthSqr() < 1.0E-4 ? Vec3.ZERO : away.normalize();
        double extraStrength = (player.isSprinting() ? 1 : 0)
                + MaceSwap.enchantmentLevel(player.getMainHandItem(), Enchantments.KNOCKBACK);
        Vec3 facing = new Vec3(-Mth.sin(yaw * Mth.DEG_TO_RAD), 0, Mth.cos(yaw * Mth.DEG_TO_RAD));
        Vec3 v = target.getDeltaMovement();
        v = new Vec3(v.x / 2 + away.x * 0.4 * keep, target.onGround() ? Math.min(0.4, v.y / 2 + 0.4 * keep) : v.y,
                v.z / 2 + away.z * 0.4 * keep);
        v = new Vec3(v.x / 2 + facing.x * 0.5 * extraStrength * keep, v.y, v.z / 2 + facing.z * 0.5 * extraStrength * keep);
        Vec3 pos = target.position();
        double best = 0;
        String found = "";
        for (int tick = 0; tick < 14; tick++) {
            Vec3 next = pos.add(v);
            BlockPos feet = BlockPos.containing(next.x, next.y + 0.1, next.z);
            if (blocked(minecraft, feet)) {
                break; // a wall stops them
            }
            double here = hazard(minecraft, feet, next);
            if (here > best) {
                best = here;
                found = goal;
            }
            pos = next;
            v = new Vec3(v.x * 0.91, (v.y - 0.08) * 0.98, v.z * 0.91);
            if (tick > 2 && !minecraft.level.getBlockState(BlockPos.containing(pos.x, pos.y - 0.1, pos.z))
                    .getCollisionShape(minecraft.level, BlockPos.containing(pos.x, pos.y - 0.1, pos.z)).isEmpty()) {
                break; // landed
            }
        }
        goal = found;
        landing = pos;
        return best;
    }

    /**
     * Where you'll be once they land (about 8 ticks): your hit slows you to 60% and stops
     * your sprint, then you keep running the way you were.
     */
    private static Vec3 futureYou(LocalPlayer player) {
        Vec3 v = player.getDeltaMovement().multiply(0.6, 0, 0.6);
        Vec3 pos = player.position();
        for (int tick = 0; tick < 8; tick++) {
            pos = pos.add(v);
            v = v.scale(0.91);
        }
        return pos;
    }

    private static double horizontal(Vec3 a, Vec3 b) {
        return Math.hypot(a.x - b.x, a.z - b.z);
    }

    private static boolean blocked(Minecraft minecraft, BlockPos feet) {
        return !minecraft.level.getBlockState(feet).getCollisionShape(minecraft.level, feet).isEmpty()
                && !minecraft.level.getBlockState(feet.above()).getCollisionShape(minecraft.level, feet.above()).isEmpty();
    }

    /** What is at this spot worth pushing them into, 0 if nothing. Sets goal. */
    private static double hazard(Minecraft minecraft, BlockPos feet, Vec3 at) {
        BlockState state = minecraft.level.getBlockState(feet);
        BlockState under = minecraft.level.getBlockState(feet.below());
        if (!minecraft.level.getEntitiesOfClass(MinecartTNT.class, new AABB(at, at).inflate(0.9)).isEmpty()) {
            goal = "your TNT cart";
            return 15;
        }
        if (state.is(Blocks.LAVA) || under.is(Blocks.LAVA)) {
            goal = "lava";
            return 12;
        }
        if (state.is(Blocks.COBWEB)) {
            goal = "a cobweb";
            return 9;
        }
        if (state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE) || under.is(Blocks.MAGMA_BLOCK)) {
            goal = "fire";
            return 7;
        }
        if (state.is(Blocks.CACTUS) || state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.POWDER_SNOW)) {
            goal = state.is(Blocks.CACTUS) ? "a cactus" : state.is(Blocks.POWDER_SNOW) ? "powder snow" : "a berry bush";
            return 5;
        }
        int depth = 0;
        for (int down = 1; down <= 24; down++) {
            BlockPos below = feet.below(down);
            if (below.getY() < minecraft.level.getMinY()) {
                depth = 99; // the void
                break;
            }
            if (!minecraft.level.getBlockState(below).getCollisionShape(minecraft.level, below).isEmpty()) {
                break;
            }
            depth = down;
        }
        if (depth >= 3) {
            goal = depth >= 99 ? "the void" : "a " + depth + "-block drop";
            return Math.min(14, 3 + depth * 0.5);
        }
        return 0;
    }

    static String modeLabel() {
        return MODES[mode];
    }

    static int mode() {
        return mode;
    }

    static void setMode(int value) {
        mode = Math.floorMod(value, MODES.length);
    }

    static void cycleMode() {
        mode = (mode + 1) % MODES.length;
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

    static void reset() {
        enabled = false;
        targetId = -1;
        AIM.stop();
    }
}
