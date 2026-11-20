package app;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Auto Web: on your bind, webs the player under your crosshair. The cobweb goes in the
 * block they are standing in, or, while they are in the air (jumping at you for a crit),
 * in the block they will land in, up to 4 blocks below them; if their feet are already in
 * a web it goes at their head. Where they will be is led by how they are moving: players
 * are drawn behind where the server has them (your ping plus the game's smoothing), so
 * a web put where you see them lands behind anyone running. It swaps to the cobweb,
 * places it and swaps back in one go. Your view really snaps onto the block it clicks and
 * back to the target 0.15 s later (move the mouse and it stays yours).
 *
 * Drain: if they have put water down around them (to clear the web, or to land in), the
 * same press pours lava from your hotbar into that water source instead. Lava replaces
 * the source and turns to obsidian at once if flowing water still touches it, so their
 * water is gone either way. The view snaps there the same way.
 *
 * Your press is always the trigger, and only the player you are looking at is a target;
 * friends are never webbed. A web is never put in your own block (Web Escape would clear
 * it) and never on a block that does something when used (chests, doors, buttons...).
 */
final class AutoWeb {
    // How much further than your reach a player can be: the web goes at their feet, which
    // must be in reach, not at the part of them you are looking at.
    private static final double TARGET_EXTRA = 1.0;
    private static final int MAX_DROP = 4;
    private static final int MAX_LEAD_TICKS = 20;
    // Other players are drawn about this far behind the server, on top of your ping.
    private static final int VIEW_LAG_MS = 100;
    private static final int MAX_DRAIN_TRIES = 8;
    // How long the view stays where it placed before turning back to the target, so you see it.
    private static final long VIEW_HOLD_MS = 150;
    private static volatile boolean enabled;
    private static volatile boolean drain = true;
    private static volatile int keyCode = -1;
    private static volatile int mouseButton = -1;
    private static volatile boolean awaitingBinding;
    // Render thread only.
    private static final Map<Class<?>, Boolean> USABLE_BLOCKS = new HashMap<>();
    private static String whyNot = "";

    private AutoWeb() {}

    /** Runs from its bind; true if it placed something (or tried), so the press is used up. */
    static boolean activate(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (!enabled || player == null || minecraft.gameMode == null || minecraft.level == null
                || Mc.screen(minecraft) != null) {
            return false;
        }
        if (!SpearSettings.combatAllowed(minecraft)) {
            SpearDash.setStatus("Off on this server");
            return false;
        }
        Player target = lookedAtPlayer(minecraft, player);
        if (target == null) {
            SpearDash.setStatus("No player in sight");
            return false;
        }
        if (VelocityHud.isFriend(target)) {
            SpearDash.setStatus("Friend, not webbed");
            return false;
        }
        if (drain && drainWater(minecraft, player, target)) {
            return true;
        }
        int web = BucketUse.findSlot(player, Items.COBWEB);
        if (web < 0) {
            SpearDash.setStatus("No cobweb in hotbar");
            return false;
        }
        Vec3 lead = leadPosition(minecraft, target);
        BlockPos spot = usableSpot(minecraft, player, lead);
        boolean led = spot != null;
        if (spot == null) {
            spot = usableSpot(minecraft, player, target.position());
        }
        if (spot == null) {
            SpearDash.setStatus(whyNot);
            return false;
        }
        boolean placed = placeWeb(minecraft, player, web, spot);
        SpearDash.setStatus(placed ? "Webbed " + target.getName().getString() : "Web refused");
        SpearLog.log("auto web: " + (placed ? "web" : "web refused") + " at " + spot.toShortString()
                + " for " + target.getName().getString() + (target.onGround() ? " (on ground" : " (in the air")
                + (led ? String.format(Locale.ROOT, ", led %.1f blocks)", lead.subtract(target.position()).horizontalDistance())
                        : ", where seen)"));
        return true;
    }

    /** The player under your crosshair, up to a little past your reach, not through walls. */
    private static Player lookedAtPlayer(Minecraft minecraft, LocalPlayer player) {
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getViewVector(1.0F).scale(player.blockInteractionRange() + TARGET_EXTRA));
        BlockHitResult wall = minecraft.level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, player));
        if (wall.getType() != HitResult.Type.MISS) {
            end = wall.getLocation();
        }
        AABB search = player.getBoundingBox().expandTowards(end.subtract(eye)).inflate(1.0);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(player, eye, end, search,
                entity -> entity instanceof Player other && other != player && !other.isSpectator() && other.isAlive(),
                eye.distanceToSqr(end));
        return hit != null && hit.getEntity() instanceof Player target ? target : null;
    }

    /**
     * Where the target will be once the web is there: by then the server has them your
     * ping plus the view lag ahead of where you see them, and someone in the air comes
     * down where their jump carries them.
     */
    private static Vec3 leadPosition(Minecraft minecraft, Player target) {
        int ticks = (AutoStun.pingMs(minecraft) + VIEW_LAG_MS + 49) / 50;
        if (!target.onGround()) {
            ticks = Math.max(ticks, airTicks(minecraft, target));
        }
        ticks = Math.min(ticks, MAX_LEAD_TICKS);
        return new Vec3(target.getX() + (target.getX() - target.xo) * ticks, target.getY(),
                target.getZ() + (target.getZ() - target.zo) * ticks);
    }

    /** Game ticks until the target lands, with the game's gravity and air drag. */
    private static int airTicks(Minecraft minecraft, Player target) {
        BlockPos spot = webSpot(minecraft, target.position());
        if (spot == null) {
            return 0;
        }
        double y = target.getY();
        double fallSpeed = target.getY() - target.yo;
        for (int tick = 1; tick <= MAX_LEAD_TICKS; tick++) {
            fallSpeed = (fallSpeed - 0.08) * 0.98;
            y += fallSpeed;
            if (y <= spot.getY()) {
                return tick;
            }
        }
        return MAX_LEAD_TICKS;
    }

    /** The web spot for someone at these feet, if you can place it from here; else null and whyNot. */
    private static BlockPos usableSpot(Minecraft minecraft, LocalPlayer player, Vec3 feet) {
        BlockPos spot = webSpot(minecraft, feet);
        if (spot == null) {
            whyNot = "Nowhere to put a web";
            return null;
        }
        if (new AABB(spot).intersects(player.getBoundingBox())) {
            whyNot = "Too close to web";
            return null;
        }
        BlockPos ground = spot.below();
        if (!player.isWithinBlockInteractionRange(ground, 0.0)) {
            whyNot = "Too far to web";
            return null;
        }
        if (reactsToUse(minecraft.level.getBlockState(ground).getBlock())) {
            whyNot = "Can't web on that block";
            return null;
        }
        if (BucketUse.aimAtFace(minecraft, player, ground, Direction.UP) == null) {
            whyNot = "Can't see where to web";
            return null;
        }
        return spot;
    }

    /**
     * The block a web should go in for someone with their feet here: the block they are in,
     * or the one above if that is taken (a web, a slab), dropped down to the ground below
     * them; null if they are higher than MAX_DROP above anything.
     */
    private static BlockPos webSpot(Minecraft minecraft, Vec3 feet) {
        BlockPos spot = BlockPos.containing(feet);
        if (!replaceable(minecraft, spot)) {
            spot = spot.above();
        }
        for (int drop = 0; drop < MAX_DROP && replaceable(minecraft, spot.below()); drop++) {
            spot = spot.below();
        }
        return replaceable(minecraft, spot) && !replaceable(minecraft, spot.below()) ? spot : null;
    }

    /** Air, grass, water, fire and the like: placing a block puts it in their place. */
    private static boolean replaceable(Minecraft minecraft, BlockPos pos) {
        return minecraft.level.getBlockState(pos).canBeReplaced();
    }

    /**
     * Whether using an item on the block can do something other than place (open a chest,
     * press a button, flip a trapdoor): any block with its own use code.
     */
    static boolean reactsToUse(Block block) {
        return USABLE_BLOCKS.computeIfAbsent(block.getClass(), AutoWeb::hasUseCode);
    }

    private static boolean hasUseCode(Class<?> type) {
        for (Class<?> current = type; current != null && current != BlockBehaviour.class;
                current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals("useWithoutItem") || method.getName().equals("useItemOn")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Snaps your view onto the top of the ground block, clicks it with the cobweb where the
     * crosshair lands, then swaps back; false if that face cannot be seen from here.
     */
    private static boolean placeWeb(Minecraft minecraft, LocalPlayer player, int slot, BlockPos spot) {
        float[] aim = BucketUse.aimAtFace(minecraft, player, spot.below(), Direction.UP);
        if (aim == null) {
            return false;
        }
        View.turnTo(player, aim[0], aim[1]);
        BlockHitResult click = BucketUse.ray(minecraft, player, player.getYRot(), player.getXRot(),
                ClipContext.Fluid.NONE);
        View.release(VIEW_HOLD_MS);
        if (click.getType() != HitResult.Type.BLOCK || !click.getBlockPos().equals(spot.below())) {
            return false;
        }
        Inventory inventory = player.getInventory();
        int previousSlot = inventory.getSelectedSlot();
        InteractionResult result;
        try {
            inventory.setSelectedSlot(slot);
            result = minecraft.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, click);
            if (Mc.swingsOnClient(result)) {
                Mc.swing(player, InteractionHand.MAIN_HAND);
            }
        } finally {
            inventory.setSelectedSlot(previousSlot);
            SpearDash.sendSelectedSlot(minecraft, false);
        }
        MinecraftInputController.stopGuard();
        return result.consumesAction();
    }

    /** Pours lava into a water source around the target; false if there is none to reach. */
    private static boolean drainWater(Minecraft minecraft, LocalPlayer player, Player target) {
        int lava = BucketUse.findSlot(player, Items.LAVA_BUCKET);
        if (lava < 0) {
            return false;
        }
        for (BlockPos water : waterAround(minecraft, player, target)) {
            float[] aim = BucketUse.aimLavaAt(minecraft, player, water);
            if (aim != null) {
                BucketUse.placeLava(minecraft, lava, aim[0], aim[1]);
                View.release(VIEW_HOLD_MS);
                SpearDash.setStatus("Drained " + target.getName().getString());
                SpearLog.log("auto web: lava into water at " + water.toShortString() + " next to "
                        + target.getName().getString());
                return true;
            }
        }
        return false;
    }

    /** Water sources within a block of the target, nearest to their feet first; never in your own block. */
    private static List<BlockPos> waterAround(Minecraft minecraft, LocalPlayer player, Player target) {
        AABB area = target.getBoundingBox().inflate(1.0);
        List<BlockPos> sources = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(area.minX, area.minY, area.minZ),
                BlockPos.containing(area.maxX, area.maxY, area.maxZ))) {
            BlockState state = minecraft.level.getBlockState(pos);
            if (state.is(Blocks.WATER) && state.getFluidState().isSource()
                    && !new AABB(pos).intersects(player.getBoundingBox())) {
                sources.add(pos.immutable());
            }
        }
        Vec3 feet = target.position();
        sources.sort(Comparator.comparingDouble(pos -> Vec3.atCenterOf(pos).distanceToSqr(feet)));
        return sources.size() > MAX_DRAIN_TRIES ? sources.subList(0, MAX_DRAIN_TRIES) : sources;
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

    static boolean isDrain() {
        return drain;
    }

    static void setDrain(boolean on) {
        drain = on;
    }

    static void toggleDrain() {
        drain = !drain;
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
        awaitingBinding = false;
    }
}
