package app;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** What both Cart modules share: finding rails and carts in the hotbar and using them on a block. */
final class Cart {
    private Cart() {}

    // Macro mode: the Cart modules press your own binds (the hotbar key, right-click) and
    // the game does the switch and the click itself on its next tick, exactly as if you
    // pressed them, instead of the module sending them.
    static volatile boolean macro;
    // A hotbar key pressed by macro mode that the game hasn't acted on yet.
    private static int macroSlot = -1;
    private static int macroSlotTick;
    // The tick of the last macro press; the next step waits until the game has had it.
    private static int pressTick = Integer.MIN_VALUE;

    static boolean isMacro() {
        return macro;
    }

    static void setMacro(boolean on) {
        macro = on;
    }

    static void toggleMacro() {
        macro = !macro;
    }

    /** The slot in your hand, counting a macro hotbar press the game is about to act on. */
    static int heldSlot(LocalPlayer player) {
        int selected = player.getInventory().getSelectedSlot();
        if (macroSlot >= 0) {
            if (selected == macroSlot || player.tickCount - macroSlotTick > 3) {
                macroSlot = -1;
            } else {
                return macroSlot;
            }
        }
        return selected;
    }

    /** A macro press is still waiting for the game's next tick. */
    static boolean macroBusy(LocalPlayer player) {
        return macro && (player.tickCount == pressTick || useWaiting);
    }

    /** Presses your hotbar key for this slot, like your finger would. */
    static void pressSlot(Minecraft minecraft, LocalPlayer player, int slot) {
        if (heldSlot(player) == slot) {
            return;
        }
        net.minecraft.client.KeyMapping key = minecraft.options.keyHotbarSlots[slot];
        if (key.isUnbound()) {
            // no key for that slot: the plain switch
            player.getInventory().setSelectedSlot(slot);
            SpearDash.sendSelectedSlot(minecraft, false);
            SpearLog.log("cart macro: hotbar " + (slot + 1) + " has no key bound, switched without one");
            return;
        }
        net.minecraft.client.KeyMapping.click(com.mojang.blaze3d.platform.InputConstants.getKey(key.saveString()));
        macroSlot = slot;
        macroSlotTick = player.tickCount;
        pressTick = player.tickCount;
    }

    // A macro right-click waiting for the tick after you let go of an item in use.
    private static boolean useWaiting;

    /**
     * Presses your right-click bind once; the game uses whatever you look at. While you're
     * still using an item (a bow held drawn, say) the game throws a click away, so the
     * button is let go first (the game lets go of the item, like your finger would) and
     * the click goes on the next tick.
     */
    static void pressUse(Minecraft minecraft, LocalPlayer player) {
        if (player.isUsingItem()) {
            minecraft.options.keyUse.setDown(false);
            useWaiting = true;
            pressTick = player.tickCount;
            SpearLog.log("cart macro: still using " + player.getUseItem().getHoverName().getString()
                    + ", let go first, the click goes next tick");
            return;
        }
        net.minecraft.client.KeyMapping key = minecraft.options.keyUse;
        net.minecraft.client.KeyMapping.click(com.mojang.blaze3d.platform.InputConstants.getKey(key.saveString()));
        pressTick = player.tickCount;
    }

    /** Called from the input loop: a click that waited for you to let go goes now. */
    static void macroTick(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (!useWaiting || player == null || player.tickCount == pressTick) {
            return;
        }
        if (player.isUsingItem()) {
            minecraft.options.keyUse.setDown(false);
            return;
        }
        useWaiting = false;
        net.minecraft.client.KeyMapping key = minecraft.options.keyUse;
        net.minecraft.client.KeyMapping.click(com.mojang.blaze3d.platform.InputConstants.getKey(key.saveString()));
        pressTick = player.tickCount;
    }

    static boolean isRail(ItemStack stack) {
        return stack.getItem() instanceof BlockItem item && item.getBlock() instanceof BaseRailBlock;
    }

    /** The rail you hold if you hold one, else the first hotbar slot with any kind of rail, or -1. */
    static int railSlot(LocalPlayer player) {
        if (isRail(player.getInventory().getItem(player.getInventory().getSelectedSlot()))) {
            return player.getInventory().getSelectedSlot();
        }
        for (int slot = 0; slot < 9; slot++) {
            if (isRail(player.getInventory().getItem(slot))) {
                return slot;
            }
        }
        return -1;
    }

    /** Whether a rail can go in this block: free, with something solid under it. */
    static boolean railFits(Minecraft minecraft, BlockPos spot) {
        return minecraft.level.getBlockState(spot).canBeReplaced()
                && Blocks.RAIL.defaultBlockState().canSurvive(minecraft.level, spot)
                // Frost Walker's ice melts in seconds and drops the rail (and the cart) with it
                && !minecraft.level.getBlockState(spot.below()).is(Blocks.FROSTED_ICE);
    }

    static boolean hasRail(Minecraft minecraft, BlockPos spot) {
        return minecraft.level.getBlockState(spot).getBlock() instanceof BaseRailBlock;
    }

    /** A click on the top of the rail in this block, where a cart goes. */
    static BlockHitResult onRail(BlockPos spot) {
        return new BlockHitResult(new Vec3(spot.getX() + 0.5, spot.getY() + 0.1, spot.getZ() + 0.5),
                Direction.UP, spot, false);
    }

    // The last click was held back because your crosshair wasn't on the block.
    static volatile boolean offTarget;

    /**
     * What your crosshair really hits right now: the game's own ray from your eye along your
     * view, out to your reach. Null unless it lands on this block (and on this face, unless
     * face is null). A click always goes out as this, so the server sees a real placement
     * where you look, never a made-up spot.
     */
    static BlockHitResult realClick(Minecraft minecraft, LocalPlayer player, BlockPos pos, Direction face) {
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getViewVector(1.0F).scale(player.blockInteractionRange()));
        BlockHitResult hit = minecraft.level.clip(new net.minecraft.world.level.ClipContext(eye, end,
                net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.NONE,
                player));
        if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK || !hit.getBlockPos().equals(pos)
                || face != null && hit.getDirection() != face) {
            return null;
        }
        if (macro && entityInTheWay(minecraft, player, eye, hit.getLocation())) {
            // the game's own click would land on that entity (the cart, a player), not the block
            return null;
        }
        return hit;
    }

    /** Something you can click (a cart, a player) is between your eye and that point. */
    static boolean entityInTheWay(Minecraft minecraft, LocalPlayer player, Vec3 eye, Vec3 point) {
        net.minecraft.world.phys.AABB around = new net.minecraft.world.phys.AABB(eye, point).inflate(1.0);
        for (net.minecraft.world.entity.Entity entity : minecraft.level.getEntities(player, around,
                e -> !e.isSpectator() && e.isPickable())) {
            net.minecraft.world.phys.AABB box = entity.getBoundingBox().inflate(entity.getPickRadius());
            if (box.contains(eye) || box.clip(eye, point).isPresent()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Selects the hotbar slot (it stays selected, so the swap really shows) and uses that
     * item on the block; true if the game took it. Only when your crosshair is on that
     * block face: otherwise nothing is sent (offTarget says so).
     */
    static boolean use(Minecraft minecraft, LocalPlayer player, int slot, BlockHitResult click) {
        return use(minecraft, player, slot, click, false);
    }

    private static boolean use(Minecraft minecraft, LocalPlayer player, int slot, BlockHitResult click,
            boolean anyFace) {
        BlockHitResult real = realClick(minecraft, player, click.getBlockPos(), anyFace ? null : click.getDirection());
        offTarget = real == null;
        if (real == null) {
            SpearLog.log("cart: crosshair not on " + click.getBlockPos().toShortString() + ", no click sent");
            Debug.info("click held back, crosshair not on the block");
            return false;
        }
        Debug.click(macro ? "macro right-click on " + player.getInventory().getItem(slot).getHoverName().getString()
                : "used " + player.getInventory().getItem(slot).getHoverName().getString() + " on a block");
        click = real;
        if (macro) {
            pressSlot(minecraft, player, slot);
            pressUse(minecraft, player);
            MinecraftInputController.stopGuard();
            return true; // the game does the click on its next tick
        }
        player.getInventory().setSelectedSlot(slot);
        // A normal send: only when the slot really changed. Forcing it every time sends the
        // same slot twice, which anticheats flag.
        SpearDash.sendSelectedSlot(minecraft, false);
        MinecraftInputController.stopGuard();
        InteractionResult result = minecraft.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, click);
        if (Mc.swingsOnClient(result)) {
            Mc.swing(player, InteractionHand.MAIN_HAND);
        }
        return result.consumesAction();
    }

    /** True if there is a TNT minecart to place: in the hotbar or in your offhand. */
    static boolean hasCart(LocalPlayer player) {
        return BucketUse.findSlot(player, Items.TNT_MINECART) >= 0 || player.getOffhandItem().is(Items.TNT_MINECART);
    }

    /**
     * Puts a TNT minecart on the rail: from the hotbar (the slot shows) or, with none there,
     * straight from your offhand with no swap. Returns the hotbar slot now selected, or -1
     * if nothing could be placed; *placed[0] says whether the game took it.
     */
    // Rails your own carts went on lately, so Block leaves those carts alone.
    private static final java.util.Map<BlockPos, Long> OWN = new java.util.HashMap<>();

    /** A cart near this spot is one of yours (put down in the last 10 seconds). */
    static boolean isOwn(BlockPos pos) {
        long now = System.nanoTime() / 1_000_000;
        OWN.values().removeIf(at -> now - at > 10_000);
        for (BlockPos own : OWN.keySet()) {
            if (own.distManhattan(pos) <= 2) {
                return true;
            }
        }
        return false;
    }

    static int placeCart(Minecraft minecraft, LocalPlayer player, BlockHitResult click, boolean[] placed) {
        OWN.put(click.getBlockPos().immutable(), System.nanoTime() / 1_000_000);
        int slot = BucketUse.findSlot(player, Items.TNT_MINECART);
        if (slot >= 0) {
            // any face of the rail takes a cart
            placed[0] = use(minecraft, player, slot, click, true);
            return slot;
        }
        if (!player.getOffhandItem().is(Items.TNT_MINECART)) {
            placed[0] = false;
            return -1;
        }
        BlockHitResult real = realClick(minecraft, player, click.getBlockPos(), null);
        offTarget = real == null;
        if (real == null) {
            SpearLog.log("cart: crosshair not on the rail at " + click.getBlockPos().toShortString() + ", no click sent");
            placed[0] = false;
            return player.getInventory().getSelectedSlot();
        }
        click = real;
        Debug.click("offhand TNT cart on the rail");
        if (macro) {
            // Your right-click bind: the game tries your main hand, then the offhand cart.
            pressUse(minecraft, player);
            placed[0] = true;
            SpearLog.log("cart: macro right-click for the offhand cart on " + click.getBlockPos().toShortString());
            return heldSlot(player);
        }
        InteractionResult result = minecraft.gameMode.useItemOn(player, InteractionHand.OFF_HAND, click);
        if (Mc.swingsOnClient(result)) {
            Mc.swing(player, InteractionHand.OFF_HAND);
        }
        placed[0] = result.consumesAction();
        SpearLog.log("cart: TNT cart from your offhand on " + click.getBlockPos().toShortString() + ", the game said "
                + result.getClass().getSimpleName() + (placed[0] ? "" : " (not placed)"));
        return player.getInventory().getSelectedSlot();
    }

    /**
     * True once the server has the TNT cart on this rail: the game never spawns a cart on
     * your side, so seeing one means the server took it.
     */
    static boolean cartAt(Minecraft minecraft, BlockPos spot) {
        return !minecraft.level.getEntitiesOfClass(net.minecraft.world.entity.vehicle.minecart.MinecartTNT.class,
                new net.minecraft.world.phys.AABB(spot).inflate(0.3)).isEmpty();
    }

    /** How long to wait for the server's cart before calling it blocked: your ping plus a margin. */
    static long cartWaitMs(Minecraft minecraft) {
        return AutoStun.pingMs(minecraft) + 200L;
    }

    /**
     * For a cart the server refused: the server may have a different slot than the game
     * thinks, and sending the cart slot again could then be a repeat. So it hops to a slot
     * that is neither the cart's, nor the one the game last sent, nor the one showing: a
     * real change whatever the server has. The cart slot after it is a real change too.
     * Returns the slot hopped to.
     */
    static int hop(Minecraft minecraft, LocalPlayer player, int cartSlot) {
        int sent = SpearDash.sentSlot(minecraft);
        int showing = player.getInventory().getSelectedSlot();
        for (int slot = 0; slot < 9; slot++) {
            if (slot != cartSlot && slot != sent && slot != showing) {
                select(minecraft, player, slot);
                return slot;
            }
        }
        return showing;
    }

    /** How long the line from a to b runs inside the box. */
    static double overlap(net.minecraft.world.phys.Vec3 a, net.minecraft.world.phys.Vec3 b,
            net.minecraft.world.phys.AABB box) {
        double near = 0;
        double far = 1;
        double[] from = {a.x, a.y, a.z};
        double[] move = {b.x - a.x, b.y - a.y, b.z - a.z};
        double[] low = {box.minX, box.minY, box.minZ};
        double[] high = {box.maxX, box.maxY, box.maxZ};
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(move[axis]) < 1.0E-9) {
                if (from[axis] < low[axis] || from[axis] > high[axis]) {
                    return 0;
                }
                continue;
            }
            double t1 = (low[axis] - from[axis]) / move[axis];
            double t2 = (high[axis] - from[axis]) / move[axis];
            near = Math.max(near, Math.min(t1, t2));
            far = Math.min(far, Math.max(t1, t2));
        }
        return far > near ? (far - near) * a.distanceTo(b) : 0;
    }

    /** Selects a hotbar slot so it shows, with no use. */
    static void select(Minecraft minecraft, LocalPlayer player, int slot) {
        if (macro) {
            pressSlot(minecraft, player, slot);
            MinecraftInputController.stopGuard();
            return;
        }
        player.getInventory().setSelectedSlot(slot);
        SpearDash.sendSelectedSlot(minecraft, false);
        MinecraftInputController.stopGuard();
    }
}
