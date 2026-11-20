package app;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.vehicle.minecart.MinecartTNT;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Cart, Block: a TNT cart that isn't yours comes within Range, and it puts blocks between
 * you and it before it goes off.
 *
 * How much of a blast hits you depends on how much of your body the blast can see: the game
 * draws lines from the explosion to points all over you and counts how many get through
 * without a block in the way. So it tries every free block around the line from the cart to
 * you and picks the one that cuts the most of those lines, then the next best for the lines
 * left (up to Blocks). End Stone first (it takes blasts well), then obsidian, then any full
 * block in your hotbar.
 *
 * Each block goes down like you'd do it: the block comes out (your hotbar shows it), your
 * view flicks onto the face it goes against, and the game's own click places it where the
 * crosshair really is. Then your hotbar goes back. Your own carts (Crossbow Cart, Bow Cart)
 * are left alone.
 */
final class AntiCart {
    private enum Step { AIM, PLACE, LOOK_BACK, BACK }

    static final Num RANGE = Num.tenths(4.0, 8.0, " blocks");
    static final Num BLOCKS = Num.value(2, 4, 1, "");
    private static final long GIVE_UP_MS = 900;
    private static volatile boolean enabled;
    // Render thread only.
    private static final HumanAim AIM = new HumanAim();
    private static final Set<Integer> HANDLED = new HashSet<>();
    private static Step step;
    private static final List<Plan> PLANS = new ArrayList<>();
    private static int backSlot = -1;
    private static int blockSlot = -1;
    private static long startMs;
    private static long nextMs;

    // smooth: a calm look (the jump block under you), else a stutter
    private record Plan(BlockPos cell, BlockPos against, Direction face, boolean smooth) {}

    static final Num ALERT_RANGE = Num.tenths(10.0, 24.0, " blocks");
    private static volatile boolean jumpBlock = true;
    private static volatile boolean alert = true;
    private static final int TAN = 0xFFD2B48C;
    // One block under you a jump.
    private static boolean jumpDone;
    // Where you looked before a jump block: the view glides back there after.
    private static float[] lookBack;

    private AntiCart() {}

    private static long now() {
        return System.nanoTime() / 1_000_000;
    }

    /** Called every frame by the HUD: moves the view along. */
    static void frame(Minecraft minecraft) {
        if ((step == Step.AIM || step == Step.LOOK_BACK) && minecraft.player != null && AIM.active()) {
            AIM.step(minecraft.player);
        }
    }

    /** Called from the input loop. */
    static void update(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (!enabled || player == null || minecraft.level == null || minecraft.gameMode == null) {
            stop(minecraft, null);
            return;
        }
        if (step != null) {
            if (!Cart.macroBusy(player)) {
                run(minecraft, player);
            }
            return;
        }
        if (Mc.screen(minecraft) != null || player.isSpectator() || !SpearSettings.combatAllowed(minecraft)
                || CrossbowCart.isBusy() || BowCart.isBusy() || CartRefill.isBusy() || player.isUsingItem()) {
            return;
        }
        if (player.onGround()) {
            jumpDone = false;
        }
        double range = RANGE.tenths();
        if (jumpBlock && !jumpDone && !player.onGround() && player.getDeltaMovement().y > -0.2
                && nearestCart(minecraft, player, range) != null && jumpUnder(minecraft, player)) {
            return;
        }
        MinecartTNT threat = null;
        double closest = range;
        for (MinecartTNT cart : minecraft.level.getEntitiesOfClass(MinecartTNT.class,
                player.getBoundingBox().inflate(range + 1))) {
            double d = cart.position().distanceTo(player.position());
            if (d <= closest && !HANDLED.contains(cart.getId()) && !Cart.isOwn(cart.blockPosition())) {
                closest = d;
                threat = cart;
            }
        }
        if (threat == null) {
            return;
        }
        HANDLED.add(threat.getId());
        if (HANDLED.size() > 64) {
            HANDLED.clear();
        }
        blockSlot = blockSlot(player);
        if (blockSlot < 0) {
            SpearLog.log("anti cart: a TNT cart is " + String.format(java.util.Locale.ROOT, "%.1f", closest)
                    + " blocks away, but there is no block in your hotbar");
            SpearDash.setStatus("No block to put down");
            return;
        }
        plan(minecraft, player, threat);
        if (PLANS.isEmpty()) {
            SpearLog.log("anti cart: a TNT cart is close, but no free spot between you and it takes a block");
            return;
        }
        backSlot = Cart.heldSlot(player);
        Cart.select(minecraft, player, blockSlot);
        startMs = now();
        SpearLog.log(String.format(java.util.Locale.ROOT, "anti cart: TNT cart %.1f blocks away, %d block%s going down",
                closest, PLANS.size(), PLANS.size() == 1 ? "" : "s"));
        aimNext(minecraft, player);
    }

    private static void run(Minecraft minecraft, LocalPlayer player) {
        if (Mc.screen(minecraft) != null || now() - startMs > GIVE_UP_MS) {
            stop(minecraft, Mc.screen(minecraft) != null ? "a screen opened" : "it took too long");
            return;
        }
        if (step != Step.BACK && Cart.heldSlot(player) != blockSlot) {
            stop(minecraft, "you picked another slot");
            return;
        }
        if (now() < nextMs) {
            return;
        }
        if (aimNextLater) {
            aimNextLater = false;
            aimNext(minecraft, player);
            return;
        }
        switch (step) {
            case AIM -> {
                if (!AIM.active()) {
                    step = Step.PLACE;
                }
            }
            case PLACE -> place(minecraft, player);
            case LOOK_BACK -> {
                if (!AIM.active()) {
                    step = Step.BACK;
                }
            }
            case BACK -> {
                if (backSlot >= 0 && backSlot != Cart.heldSlot(player)) {
                    Cart.select(minecraft, player, backSlot);
                }
                step = null;
            }
        }
    }

    // Macro: the turn to the next block waits for the game's click on this one.
    private static boolean aimNextLater;

    private static void aimNext(Minecraft minecraft, LocalPlayer player) {
        if (PLANS.isEmpty()) {
            if (lookBack != null) {
                // after the block under you: the view glides back up to where you looked
                AIM.stop();
                AIM.glide(Mth.wrapDegrees(lookBack[0] - player.getYRot()), lookBack[1] - player.getXRot(),
                        PlayStyle.turnMs(110 + java.util.concurrent.ThreadLocalRandom.current().nextLong(0, 40)));
                lookBack = null;
                step = Step.LOOK_BACK;
                return;
            }
            step = Step.BACK;
            nextMs = now() + 50 + java.util.concurrent.ThreadLocalRandom.current().nextLong(0, 40);
            return;
        }
        Plan next = PLANS.get(0);
        float[] aim = BucketUse.aimAtFace(minecraft, player, next.against(), next.face());
        if (aim == null) {
            PLANS.remove(0);
            aimNext(minecraft, player);
            return;
        }
        AIM.stop();
        float yaw = Mth.wrapDegrees(aim[0] - player.getYRot());
        float pitch = aim[1] - player.getXRot();
        if (next.smooth()) {
            AIM.glide(yaw, pitch, PlayStyle.turnMs(90 + java.util.concurrent.ThreadLocalRandom.current().nextLong(0, 40)));
        } else {
            AIM.glide(yaw, pitch, PlayStyle.turnMs(80 + java.util.concurrent.ThreadLocalRandom.current().nextLong(0, 40)));
        }
        step = Step.AIM;
    }

    /** The closest TNT cart that isn't yours within this far; null if none. */
    private static MinecartTNT nearestCart(Minecraft minecraft, LocalPlayer player, double range) {
        MinecartTNT best = null;
        double closest = range;
        for (MinecartTNT cart : minecraft.level.getEntitiesOfClass(MinecartTNT.class,
                player.getBoundingBox().inflate(range + 1))) {
            double d = cart.position().distanceTo(player.position());
            if (d <= closest && !Cart.isOwn(cart.blockPosition())) {
                closest = d;
                best = cart;
            }
        }
        return best;
    }

    /**
     * You jumped with a cart close: a block goes in the space under your feet (on the ground
     * you jumped from), with a calm look down and back up. True if it started.
     */
    private static boolean jumpUnder(Minecraft minecraft, LocalPlayer player) {
        BlockPos cell = BlockPos.containing(player.getX(), player.getY() - 1, player.getZ());
        if (player.getY() < cell.getY() + 1 || !minecraft.level.getBlockState(cell).canBeReplaced()) {
            return false; // not high enough yet
        }
        BlockPos ground = cell.below();
        if (!minecraft.level.getBlockState(ground).isFaceSturdy(minecraft.level, ground, Direction.UP)
                || !player.isWithinBlockInteractionRange(ground, 0.0)
                || BucketUse.aimAtFace(minecraft, player, ground, Direction.UP) == null) {
            return false;
        }
        blockSlot = blockSlot(player);
        if (blockSlot < 0) {
            return false;
        }
        jumpDone = true;
        PLANS.clear();
        PLANS.add(new Plan(cell, ground, Direction.UP, true));
        lookBack = new float[] {player.getYRot(), player.getXRot()};
        backSlot = Cart.heldSlot(player);
        Cart.select(minecraft, player, blockSlot);
        startMs = now();
        SpearLog.log("anti cart: you jumped with a TNT cart close, a block under you at " + cell.toShortString());
        aimNext(minecraft, player);
        return true;
    }

    /**
     * Called by the HUD: while a TNT cart that isn't yours is within Alert range, three red
     * !!! hop at the right middle of your screen with a tan arrow under them that points to
     * the cart (up is straight ahead).
     */
    static void drawAlert(net.minecraft.client.gui.GuiGraphicsExtractor graphics, Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (!enabled || !alert || player == null || minecraft.level == null || player.isSpectator()) {
            return;
        }
        MinecartTNT cart = nearestCart(minecraft, player, ALERT_RANGE.tenths());
        if (cart == null) {
            return;
        }
        int x = graphics.guiWidth() - Math.max(24, graphics.guiWidth() / 10);
        int y = graphics.guiHeight() / 2 - 12;
        ArmorHud.bang(graphics, minecraft, x, y, 0);
        double toCart = Math.toDegrees(Math.atan2(-(cart.getX() - player.getX()), cart.getZ() - player.getZ()));
        float turn = (float) Math.toRadians(Mth.wrapDegrees(toCart - player.getYRot()));
        var pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(x, y + 20);
        pose.rotate(turn);
        // a small pixel arrow pointing up: head rows, then the shaft
        for (int row = 0; row < 4; row++) {
            graphics.fill(-row, -6 + row, row + 1, -5 + row, TAN);
        }
        graphics.fill(-1, -2, 2, 6, TAN);
        pose.popMatrix();
        String far = String.format(java.util.Locale.ROOT, "%.0f", cart.position().distanceTo(player.position()));
        graphics.text(minecraft.font, far, x - minecraft.font.width(far) / 2, y + 30, TAN);
    }

    static boolean isJumpBlock() {
        return jumpBlock;
    }

    static void setJumpBlock(boolean on) {
        jumpBlock = on;
    }

    static void toggleJumpBlock() {
        jumpBlock = !jumpBlock;
    }

    static boolean isAlert() {
        return alert;
    }

    static void setAlert(boolean on) {
        alert = on;
    }

    static void toggleAlert() {
        alert = !alert;
    }

    /** Places the block with the game's own click, where the crosshair really is. */
    private static void place(Minecraft minecraft, LocalPlayer player) {
        Plan plan = PLANS.remove(0);
        if (!(minecraft.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK
                || !hit.getBlockPos().equals(plan.against()) || hit.getDirection() != plan.face()) {
            SpearLog.log("anti cart: the crosshair didn't land on the spot for " + plan.cell().toShortString() + ", skipped");
        } else if (!minecraft.level.getBlockState(plan.cell()).canBeReplaced()) {
            SpearLog.log("anti cart: " + plan.cell().toShortString() + " is already filled");
        } else {
            Debug.click("block placed in front of the cart");
            if (Cart.isMacro()) {
                Cart.pressUse(minecraft, player);
                SpearLog.log("anti cart: macro right-click for the block at " + plan.cell().toShortString());
            } else {
                InteractionResult result = minecraft.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
                if (Mc.swingsOnClient(result)) {
                    Mc.swing(player, InteractionHand.MAIN_HAND);
                }
                SpearLog.log("anti cart: block at " + plan.cell().toShortString() + (result.consumesAction() ? "" : " refused"));
            }
            SpearDash.setStatus("Blocked the cart");
        }
        nextMs = now() + 50; // the next one on its own tick
        if (Cart.isMacro()) {
            // the game clicks on its next tick: turning to the next spot now would move the
            // crosshair off this one before it clicks
            aimNextLater = true;
        } else {
            aimNext(minecraft, player);
        }
    }

    private static void stop(Minecraft minecraft, String why) {
        if (step == null) {
            return;
        }
        AIM.stop();
        PLANS.clear();
        lookBack = null;
        step = null;
        if (why != null) {
            SpearLog.log("anti cart: stopped, " + why);
        }
    }

    /**
     * Picks the blocks: lines from the cart's middle to 27 points over your body, and for
     * each free block around them that can be placed (a solid face next to it you can see
     * and reach, not in you or the cart), how many lines it cuts. The best one, then the
     * best for the lines still open, up to Blocks.
     */
    // A blast line only counts as blocked when it runs this far through a block. Two blocks
    // side by side with a cart coming at them on the diagonal touch only at a corner, and
    // lines through that crack barely clip either block and leak damage; so a block on the
    // diagonal, right in the line, beats two beside it.
    private static final double SOLID = 0.3;

    private static void plan(Minecraft minecraft, LocalPlayer player, MinecartTNT cart) {
        PLANS.clear();
        Vec3 blast = cart.getBoundingBox().getCenter();
        AABB body = player.getBoundingBox();
        List<Vec3> points = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                for (int k = 0; k < 3; k++) {
                    points.add(new Vec3(Mth.lerp(0.05 + 0.45 * i, body.minX, body.maxX),
                            Mth.lerp(0.05 + 0.45 * j, body.minY, body.maxY), Mth.lerp(0.05 + 0.45 * k, body.minZ, body.maxZ)));
                }
            }
        }
        AABB around = body.minmax(cart.getBoundingBox()).inflate(1);
        List<BlockPos> cells = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(around.minX, around.minY, around.minZ),
                BlockPos.containing(around.maxX, around.maxY, around.maxZ))) {
            cells.add(pos.immutable());
        }
        List<Vec3> open = new ArrayList<>(points);
        for (int n = 0; n < BLOCKS.low() && !open.isEmpty(); n++) {
            Plan best = null;
            int bestCut = 0;
            for (BlockPos cell : cells) {
                if (PLANS.stream().anyMatch(p -> p.cell().equals(cell))) {
                    continue;
                }
                AABB box = new AABB(cell);
                int cut = 0;
                for (Vec3 point : open) {
                    if (Cart.overlap(blast, point, box) > SOLID) {
                        cut++;
                    }
                }
                if (cut <= bestCut) {
                    continue;
                }
                Plan plan = placeable(minecraft, player, cart, cell);
                if (plan != null) {
                    best = plan;
                    bestCut = cut;
                }
            }
            if (best == null) {
                break;
            }
            PLANS.add(best);
            AABB box = new AABB(best.cell());
            open.removeIf(point -> Cart.overlap(blast, point, box) > SOLID);
        }
    }

    /** How to place a block in this cell, or null if it can't be. */
    private static Plan placeable(Minecraft minecraft, LocalPlayer player, MinecartTNT cart, BlockPos cell) {
        if (!minecraft.level.getBlockState(cell).canBeReplaced()
                || new AABB(cell).intersects(player.getBoundingBox())
                || new AABB(cell).intersects(cart.getBoundingBox())
                || !minecraft.level.getEntitiesOfClass(net.minecraft.world.entity.Entity.class, new AABB(cell),
                        e -> e.blocksBuilding).isEmpty()) {
            return null;
        }
        for (Direction side : Direction.values()) {
            BlockPos against = cell.relative(side);
            Direction face = side.getOpposite();
            if (!minecraft.level.getBlockState(against).isFaceSturdy(minecraft.level, against, face)
                    || AutoWeb.reactsToUse(minecraft.level.getBlockState(against).getBlock())
                    || !player.isWithinBlockInteractionRange(against, 0.0)) {
                continue;
            }
            if (BucketUse.aimAtFace(minecraft, player, against, face) != null) {
                return new Plan(cell, against, face, false);
            }
        }
        return null;
    }

    /** End Stone first, then obsidian, then any full block in the hotbar; -1 if none. */
    static int blockSlot(LocalPlayer player) {
        int end = BucketUse.findSlot(player, Items.END_STONE);
        if (end >= 0) {
            return end;
        }
        int obsidian = BucketUse.findSlot(player, Items.OBSIDIAN);
        if (obsidian >= 0) {
            return obsidian;
        }
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.getItem() instanceof BlockItem block
                    && block.getBlock().defaultBlockState().isCollisionShapeFullBlock(player.level(), BlockPos.ZERO)
                    && !Cart.isRail(stack) && !stack.is(Items.TNT)) {
                return slot;
            }
        }
        return -1;
    }

    static boolean isBusy() {
        return step != null;
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
        step = null;
        AIM.stop();
        PLANS.clear();
        HANDLED.clear();
    }
}
