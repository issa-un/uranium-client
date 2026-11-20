package app;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Placing and scooping water from the hotbar for Web Escape and MLG Water, and lava for
 * Auto Web's drain.
 *
 * Where a bucket puts water is decided by the server's item-use ray from your eyes along
 * your look direction (Item.getPlayerPOVHitResult): the block in front of the face it hits,
 * or the hit block itself if it holds liquids. A ray that starts inside a block (a cobweb you
 * are in) hits that block, on the face opposite the way you look. Your view really turns
 * there first (see View): the camera moves, and the modules turn it back when they are done.
 */
final class BucketUse {
    private static final Direction[] LAVA_AIM_ORDER = {
        Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.UP};
    private static final double[][] FACE_OFFSETS = {{0, 0}, {0.3, 0.3}, {-0.3, 0.3}, {0.3, -0.3}, {-0.3, -0.3}};

    private BucketUse() {}

    /** Where a water bucket used with this look direction puts its water, or null if out of reach. */
    static BlockPos placement(Minecraft minecraft, LocalPlayer player, float yaw, float pitch) {
        BlockHitResult hit = ray(minecraft, player, yaw, pitch, ClipContext.Fluid.NONE);
        if (hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        BlockState hitState = minecraft.level.getBlockState(hit.getBlockPos());
        return hitState.getBlock() instanceof LiquidBlockContainer
                ? hit.getBlockPos()
                : hit.getBlockPos().relative(hit.getDirection());
    }

    /** Whether water can go there: air, or something water replaces (cobweb, fire, grass). */
    static boolean roomForWater(Minecraft minecraft, BlockPos pos) {
        BlockState state = minecraft.level.getBlockState(pos);
        return state.isAir() || state.canBeReplaced(Fluids.WATER);
    }

    /** Water boils away in the Nether. */
    static boolean waterWorks(Minecraft minecraft) {
        return minecraft.level.dimension() != Level.NETHER;
    }

    /** Places water from the given hotbar slot, aimed with this look direction. */
    static void placeWater(Minecraft minecraft, int slot, float yaw, float pitch) {
        useFromHotbar(minecraft, slot, yaw, pitch);
    }

    /**
     * Where a lava bucket used with this look direction puts its lava, or null if out of
     * reach. Unlike water it always goes in front of the face the ray hits.
     */
    static BlockPos lavaPlacement(Minecraft minecraft, LocalPlayer player, float yaw, float pitch) {
        BlockHitResult hit = ray(minecraft, player, yaw, pitch, ClipContext.Fluid.NONE);
        return hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos().relative(hit.getDirection()) : null;
    }

    /**
     * A look direction {yaw, pitch} that puts a lava bucket's lava in that block, or null if
     * there is none in reach. The ray passes through water, so it has to hit a face of a
     * block next to the target from the target's side: the ground under it first, then
     * the sides, then above.
     */
    static float[] aimLavaAt(Minecraft minecraft, LocalPlayer player, BlockPos target) {
        for (Direction side : LAVA_AIM_ORDER) {
            float[] aim = aimAtFace(minecraft, player, target.relative(side), side.getOpposite());
            if (aim != null) {
                return aim;
            }
        }
        return null;
    }

    /**
     * A look direction {yaw, pitch} whose crosshair lands on that face of the block, within
     * reach and not hidden behind another block, or null: the middle of the face first, then
     * nearer its corners.
     */
    static float[] aimAtFace(Minecraft minecraft, LocalPlayer player, BlockPos block, Direction face) {
        Vec3 eye = player.getEyePosition();
        Vec3 center = Vec3.atCenterOf(block).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        for (double[] offset : FACE_OFFSETS) {
            Vec3 point = face.getAxis() == Direction.Axis.Y ? center.add(offset[0], 0, offset[1])
                    : face.getAxis() == Direction.Axis.X ? center.add(0, offset[0], offset[1])
                    : center.add(offset[0], offset[1], 0);
            float[] aim = lookAt(eye, point);
            BlockHitResult hit = ray(minecraft, player, aim[0], aim[1], ClipContext.Fluid.NONE);
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(block)
                    && hit.getDirection() == face) {
                return aim;
            }
        }
        return null;
    }

    /** The look direction {yaw, pitch} from the eye to the point. */
    static float[] lookAt(Vec3 eye, Vec3 point) {
        Vec3 toPoint = point.subtract(eye);
        return new float[] {
            (float) Math.toDegrees(Math.atan2(-toPoint.x, toPoint.z)),
            (float) Math.toDegrees(-Math.atan2(toPoint.y, Math.hypot(toPoint.x, toPoint.z)))};
    }

    /** Places lava from the given hotbar slot, aimed with this look direction. */
    static void placeLava(Minecraft minecraft, int slot, float yaw, float pitch) {
        useFromHotbar(minecraft, slot, yaw, pitch);
    }

    /** Aims an empty bucket at the water source there and scoops it; true if the ray found it. */
    static boolean scoop(Minecraft minecraft, LocalPlayer player, BlockPos water) {
        int bucket = findSlot(player, Items.BUCKET);
        if (bucket < 0) {
            return false;
        }
        float[] aim = lookAt(player.getEyePosition(), Vec3.atCenterOf(water));
        BlockHitResult hit = ray(minecraft, player, aim[0], aim[1], ClipContext.Fluid.SOURCE_ONLY);
        if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(water)) {
            return false;
        }
        useFromHotbar(minecraft, bucket, aim[0], aim[1]);
        return true;
    }

    /** Scoops each water source in turn; returns how many were scooped. */
    static int scoopAll(Minecraft minecraft, LocalPlayer player, List<BlockPos> waters) {
        int scooped = 0;
        for (BlockPos water : waters) {
            if (scoop(minecraft, player, water)) {
                scooped++;
            }
        }
        return scooped;
    }

    /** The server's item-use ray for this look direction. */
    static BlockHitResult ray(Minecraft minecraft, LocalPlayer player, float yaw, float pitch,
            ClipContext.Fluid fluid) {
        Vec3 eye = player.getEyePosition();
        double yawRadians = Math.toRadians(yaw);
        double pitchRadians = Math.toRadians(pitch);
        Vec3 look = new Vec3(-Math.sin(yawRadians) * Math.cos(pitchRadians), -Math.sin(pitchRadians),
                Math.cos(yawRadians) * Math.cos(pitchRadians));
        Vec3 end = eye.add(look.scale(player.blockInteractionRange()));
        return minecraft.level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, fluid, player));
    }

    /** Hotbar slots holding the item, in order. */
    static List<Integer> slotsWith(LocalPlayer player, Item item) {
        List<Integer> slots = new ArrayList<>();
        for (int slot = 0; slot < 9; slot++) {
            if (player.getInventory().getItem(slot).is(item)) {
                slots.add(slot);
            }
        }
        return slots;
    }

    static int findSlot(LocalPlayer player, Item item) {
        List<Integer> slots = slotsWith(player, item);
        return slots.isEmpty() ? -1 : slots.get(0);
    }

    /**
     * Snaps your view to the given direction (see View), uses the hotbar item and swaps back.
     * The use packet carries that view, and the game sends it in its movement update too.
     */
    private static void useFromHotbar(Minecraft minecraft, int slot, float yaw, float pitch) {
        LocalPlayer player = minecraft.player;
        int previousSlot = player.getInventory().getSelectedSlot();
        View.turnTo(player, yaw, pitch);
        try {
            player.getInventory().setSelectedSlot(slot);
            minecraft.gameMode.useItem(player, InteractionHand.MAIN_HAND);
        } finally {
            player.getInventory().setSelectedSlot(previousSlot);
            SpearDash.sendSelectedSlot(minecraft, false);
        }
        InputLoop.stopGuard();
    }
}
