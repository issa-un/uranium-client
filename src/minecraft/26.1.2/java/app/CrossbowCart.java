package app;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.vehicle.minecart.MinecartTNT;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Crossbow Cart: right-click a block with a rail and it puts the rail down, puts a TNT
 * minecart on it after Delay, lights the block your shot will pass through (side or corner)
 * with your flint and steel, and pulls out your crossbow. With Shoot on, after another Delay
 * it makes a small aim correction onto the cart, through the fire, and fires the loaded
 * crossbow; off, the shot is yours. Every step
 * is its own tick, so each swap shows in your hotbar. No flint and steel, or nowhere for
 * fire there: that step is skipped. Without a TNT minecart in your hotbar the click is
 * left to the game.
 */
final class CrossbowCart {
    private enum Step { RAIL, CART, GUARD, GUARD_AIM, JUMP, PILLAR, PILLAR_LAND, FLINT, AIM_FIRE, LAVA, CROSSBOW, SHOOT, BOW_DRAW, RETRY }

    static final Num DELAY = Num.ms(50, 150, 500);
    static final Num CHANCE = Num.percent(100);
    // Self guard's block goes down under this much health (hearts), or whenever the blast
    // would surely pop you (an elytra instead of a chestplate, say).
    static final Num GUARD_UNDER = Num.tenths(6.0, 10.0, " hearts");
    // The loaded crossbow is in your offhand: it fires from there, no swap.
    private static boolean offhandShot;
    private static long lastShotMs = -10_000;
    private static final long GIVE_UP_MS = 2500;
    // The shot line: 5 pixels (of 16 a block) up the cart's hitbox. On or above it fires.
    private static final double SHOT_LINE = 5 / 16.0;
    // Where a flick from under the line ends: a little over it, so spread can't drop it under.
    private static final double ABOVE_LINE = 0.1;
    private static final long TICK_MS = 50;
    // How long the bow is drawn: the least the game shoots (3 ticks) plus one for the server.
    private static final int DRAW_TICKS = 4;
    // A fix when your mouse moved the aim back under the line: short, but still one glide.
    private static final long FIX_MS = 90;
    private static volatile boolean enabled;
    private static volatile boolean replenish;
    private static volatile boolean autoReload;
    private static volatile boolean shoot;
    // Hard switch: with two crossbows, the shot takes the loaded one and Auto reload waits
    // until both are empty.
    private static volatile boolean hardSwitch;
    // Overload: a second TNT cart on the same rail, for a blast that goes through a block.
    private static volatile boolean overload;
    // Look: false Stutter (quick jumps), true Smooth (one even turn).
    private static volatile boolean smoothLook;
    // Carts put on this rail so far.
    private static int cartsPut;
    // Render thread only.
    private static Step step;
    private static BlockHitResult click;
    private static BlockPos spot;
    private static BlockPos fireSpot;
    private static int expectedSlot;
    private static long nextMs;
    private static long startMs;
    private static Vec3 aimPoint;
    private static final HumanAim AIM = new HumanAim();
    private static boolean glided;
    // No room for fire: a crossbow arrow can't catch fire on the way, so a (Flame) bow is used.
    private static boolean bowMode;
    private static boolean fixed;
    private static long correctAt;
    private static long shotAtMs;
    private static long cartPlacedMs;
    private static int cartRetries;
    // The fire or lava the arrow has to fly through (null: none was put down).
    private static AABB burn;
    private static long burnSetMs;
    // Lava for a spot fire can't go (you shoot from under or above it, or have no flint).
    private static BlockPos lavaSpot;
    // Nothing to light with (no flint and steel, no lava): quicker swaps with a hand shake.
    private static boolean simple;
    // A lava source is about this high; the arrow has to pass through that part.
    private static final double LAVA_HEIGHT = 0.85;
    // At or under this speed (blocks a tick: about a sprint) the delays are as set.
    private static final double NORMAL_SPEED = 0.35;

    private CrossbowCart() {}

    private static long now() {
        return System.nanoTime() / 1_000_000;
    }

    // A hotbar key you pressed that the game has not switched to yet.
    private static final long QUICK_SWITCH_MS = 600;
    private static int pressedSlot = -1;
    // The rail of your last click: a fast click right next to it counts as that rail.
    private static final long RECLICK_MS = 1200;
    private static BlockPos lastSpot;
    private static long lastSpotMs;
    private static long pressedAtMs;

    /** A hotbar key went down; the game switches to it on its next tick. */
    static void onHotbarPress(int slot) {
        pressedSlot = slot;
        pressedAtMs = now();
        System.arraycopy(PRESS_SLOTS, 1, PRESS_SLOTS, 0, PRESS_SLOTS.length - 1);
        System.arraycopy(PRESS_TIMES, 1, PRESS_TIMES, 0, PRESS_TIMES.length - 1);
        PRESS_SLOTS[PRESS_SLOTS.length - 1] = slot;
        PRESS_TIMES[PRESS_TIMES.length - 1] = pressedAtMs;
    }

    // Your last few hotbar keys, for the log when a click isn't taken.
    private static final int[] PRESS_SLOTS = {-1, -1, -1, -1, -1};
    private static final long[] PRESS_TIMES = new long[5];

    /** Like "keys 5 (300 ms ago), 2 (120 ms ago), 5 (40 ms ago), holding 2". */
    private static String keys(LocalPlayer player) {
        StringBuilder line = new StringBuilder("keys");
        long now = now();
        boolean any = false;
        for (int i = 0; i < PRESS_SLOTS.length; i++) {
            if (PRESS_SLOTS[i] >= 0 && now - PRESS_TIMES[i] < 1500) {
                line.append(any ? ", " : " ").append(PRESS_SLOTS[i] + 1).append(" (").append(now - PRESS_TIMES[i]).append(" ms ago)");
                any = true;
            }
        }
        if (!any) {
            line.append(" none lately");
        }
        ItemStack held = player.getInventory().getItem(Cart.heldSlot(player));
        return line.append(", holding ").append(Cart.heldSlot(player) + 1).append(" (")
                .append(held.isEmpty() ? "empty" : held.getHoverName().getString()).append(")").toString();
    }

    /**
     * The rail you are switching to: the slot you hold, or the hotbar key you just pressed if
     * the game hasn't switched to it yet (a quick switch and right-click land together).
     */
    private static int heldRail(LocalPlayer player) {
        int held = Cart.heldSlot(player);
        if (pressedSlot >= 0 && pressedSlot != held && now() - pressedAtMs < QUICK_SWITCH_MS) {
            // Your newest hotbar key wins: a rail, or something else you switched to.
            return Cart.isRail(player.getInventory().getItem(pressedSlot)) ? pressedSlot : -1;
        }
        return Cart.isRail(player.getInventory().getItem(held)) ? held : -1;
    }

    /** A right-click; true if the module took it over. */
    static boolean onUseClick(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (!enabled || player == null || minecraft.gameMode == null || minecraft.level == null
                || Mc.screen(minecraft) != null) {
            return false;
        }
        int railInHand = heldRail(player);
        if (step != null) {
            // A cart is going down. An extra click (a double click, a second rail by
            // accident, a click on the cart) is swallowed so it can't put down a second
            // rail, use the cart or flint in your hand, or restart it. Only a rail click on
            // a different spot starts a new one.
            boolean elsewhere = railInHand >= 0 && minecraft.hitResult instanceof BlockHitResult other
                    && other.getType() == HitResult.Type.BLOCK && !nearSpot(other.getBlockPos());
            if (!elsewhere) {
                UraniumLog.log("crossbow cart: extra click while the cart goes down (at "
                        + step.name().toLowerCase() + "), ignored");
                return true;
            }
            UraniumLog.log("crossbow cart: new rail click somewhere else, the last one (at "
                    + step.name().toLowerCase() + ") is dropped");
            step = null;
            AIM.stop();
        }
        if (railInHand < 0 && goPing(minecraft, player)) {
            return true;
        }
        if (railInHand < 0) {
            // Only worth a line when a rail was in play (held, or a key pressed lately).
            boolean railLately = false;
            for (int i = 0; i < PRESS_SLOTS.length; i++) {
                railLately |= PRESS_SLOTS[i] >= 0 && now() - PRESS_TIMES[i] < 1500
                        && Cart.isRail(player.getInventory().getItem(PRESS_SLOTS[i]));
            }
            if (railLately) {
                UraniumLog.log("crossbow cart: click left to the game, no rail counted as in hand; " + keys(player));
            }
            return false;
        }
        BlockHitResult hit;
        if (minecraft.hitResult instanceof BlockHitResult block && block.getType() == HitResult.Type.BLOCK) {
            hit = block;
        } else if (minecraft.hitResult instanceof net.minecraft.world.phys.EntityHitResult entityHit
                && entityHit.getEntity() instanceof net.minecraft.world.entity.vehicle.minecart.AbstractMinecart minecart
                && Cart.hasRail(minecraft, minecart.blockPosition())) {
            // Your crosshair is on a cart sitting on a rail: the next cart goes on that rail.
            hit = Cart.onRail(minecart.blockPosition());
            UraniumLog.log("crossbow cart: clicked a cart on its rail, the next cart goes on that rail");
        } else {
            UraniumLog.log("crossbow cart: click left to the game, the crosshair isn't on a block; " + keys(player));
            return false;
        }
        if (!Settings.combatAllowed(minecraft)) {
            SpearDash.setStatus("Off on this server");
            UraniumLog.log("crossbow cart: click left to the game, combat modules are off on this server");
            return false;
        }
        if (!CHANCE.roll()) {
            UraniumLog.log("crossbow cart: click left to the game (Chance)");
            return false;
        }
        if (!Cart.hasCart(player)) {
            SpearDash.setStatus("No TNT minecart");
            UraniumLog.log("crossbow cart: click left to the game, no TNT minecart");
            CartRefill.requestNow();
            return false;
        }
        BlockPos clicked = hit.getBlockPos();
        BlockPos target;
        if (Cart.hasRail(minecraft, clicked)) {
            // A rail already there (a second click, or one left after a blast): the cart goes
            // on it. Any face counts: looking across a rail at an angle, the crosshair often
            // lands on its side edge, and that must not put a second rail next to it.
            target = clicked;
        } else if (lastSpot != null && now() - lastSpotMs < RECLICK_MS && Cart.hasRail(minecraft, lastSpot)
                && Math.abs(clicked.getX() - lastSpot.getX()) <= 1 && Math.abs(clicked.getZ() - lastSpot.getZ()) <= 1
                && Math.abs(clicked.getY() - lastSpot.getY()) <= 1) {
            // A fast click right next to the rail you just put down (the crosshair slid past
            // it onto the ground): counted as that rail, not a second rail behind it.
            target = lastSpot;
            UraniumLog.log("crossbow cart: fast click next to your last rail, counted as that rail");
        } else {
            target = minecraft.level.getBlockState(clicked).canBeReplaced() ? clicked : clicked.relative(hit.getDirection());
            if (Cart.hasRail(minecraft, target)) {
                // clicked the ground next to a rail that is already in that spot: use it
            } else if (!Cart.railFits(minecraft, target)) {
                refuse("No room for a rail there", target);
                return false;
            } else if (AutoWeb.reactsToUse(minecraft.level.getBlockState(clicked).getBlock())) {
                refuse("That block opens instead", target);
                return false;
            }
        }
        // Hotbar keys the game hasn't acted on yet (a fast rail > sword > rail). The game
        // works through them in slot order, not the order you pressed them, so it could end
        // on the sword in the middle of the cart and stop it. The rail was pressed last, so
        // they are all used up here.
        int dropped = 0;
        for (net.minecraft.client.KeyMapping key : minecraft.options.keyHotbarSlots) {
            while (key.consumeClick()) {
                dropped++;
            }
        }
        if (railInHand != Cart.heldSlot(player)) {
            // Finish the switch you started, so the rail really is in your hand.
            Cart.select(minecraft, player, railInHand);
            UraniumLog.log("crossbow cart: quick switch to hotbar " + (railInHand + 1) + " counted"
                    + (dropped > 1 ? " (" + dropped + " fast key presses)" : ""));
        } else if (dropped > 0) {
            UraniumLog.log("crossbow cart: " + dropped + " hotbar key presses still queued were used up, the rail stays");
        }
        String risk = SelfBlast.danger(minecraft, player, SelfBlast.cartCenter(target), SelfBlast.CROSSBOW_SPEED);
        if (risk != null) {
            // your own blast would kill you (or pop your totem): the rail is yours, no cart
            SpearDash.setStatus("Too close for your health");
            UraniumLog.log("crossbow cart: no cart at " + target.toShortString() + ", " + risk);
            return false;
        }
        if (minecraft.level.isRainingAt(target.above())) {
            UraniumLog.log("crossbow cart: it's raining on the cart, rain puts a burning arrow out (cover helps)");
        }
        UraniumLog.log("crossbow cart: click taken at " + target.toShortString() + "; " + keys(player));
        pressedSlot = -1;
        click = target.equals(clicked) || !Cart.hasRail(minecraft, target) ? hit : Cart.onRail(target);
        spot = target;
        lastSpot = target;
        lastSpotMs = now();
        cartRetries = 0;
        cartsPut = 0;
        guardCell = null;
        fireRetried = false;
        aimAfterClickMs = 0;
        reshot = false;
        reshootAtMs = 0;
        relocated = false;
        fireMacroPending = false;
        offhandShot = false;
        bowMode = false;
        fireSpot = null;
        lavaSpot = null;
        pouredLava = null;
        burn = null;
        aimPoint = null;
        simple = BucketUse.findSlot(player, Items.FLINT_AND_STEEL) < 0
                && BucketUse.findSlot(player, Items.LAVA_BUCKET) < 0;
        startMs = now();
        aimTries = 0;
        if (Cart.isMacro()) {
            // Your own click goes to the game: it puts the rail down itself (or just clicks
            // the top of a rail that is already there), and the cart follows once the rail
            // shows. A click that would put a second rail next to it (the side of a rail, or
            // the ground beside your last rail) is kept from the game instead.
            boolean fresh = !Cart.hasRail(minecraft, target);
            boolean onTop = target.equals(clicked) && hit.getDirection() == Direction.UP;
            boolean toGame = fresh || onTop;
            railsBefore.clear();
            for (BlockPos near : BlockPos.betweenClosed(target.offset(-2, -1, -2), target.offset(2, 1, 2))) {
                if (Cart.hasRail(minecraft, near)) {
                    railsBefore.add(near.immutable());
                }
            }
            expectedSlot = railInHand;
            next(Step.CART, stepDelay(player));
            UraniumLog.log("crossbow cart: macro, " + (fresh ? "your click puts the rail down"
                    : onTop ? "your click on the rail goes to the game" : "click kept from the game (it would double the rail)")
                    + ", the cart follows");
            return !toGame;
        }
        placeRail(minecraft, player);
        return true;
    }

    /**
     * Go-ping (always on, no switch): picks the sequence up wherever it stands instead of
     * only from the rail. Right-click a rail with a TNT cart in hand and it carries on from
     * the cart (cart, fire, shot). Right-click near a TNT cart that's already on a rail with
     * flint and steel or lava in hand (say you put it down, ran out of reach and came back)
     * and it carries on from the fire (fire on the shot's path, then the shot). Whatever you
     * are missing is skipped the same way as from the start: no flint, lava or nothing; no
     * loaded crossbow, the bow.
     */
    private static boolean goPing(Minecraft minecraft, LocalPlayer player) {
        ItemStack held = player.getMainHandItem();
        boolean cartItem = held.is(Items.TNT_MINECART);
        boolean lighter = held.is(Items.FLINT_AND_STEEL) || held.is(Items.LAVA_BUCKET);
        if (!cartItem && !lighter || !Settings.combatAllowed(minecraft)) {
            return false;
        }
        BlockPos rail = pingRail(minecraft);
        if (rail == null || !player.isWithinBlockInteractionRange(rail, 0.0)) {
            return false;
        }
        boolean cartThere = Cart.cartAt(minecraft, rail);
        String risk = SelfBlast.danger(minecraft, player, SelfBlast.cartCenter(rail), SelfBlast.CROSSBOW_SPEED);
        if (risk != null) {
            SpearDash.setStatus("Too close for your health");
            UraniumLog.log("crossbow cart: go-ping skipped at " + rail.toShortString() + ", " + risk);
            return false;
        }
        if (lighter && !cartThere) {
            return false; // nothing on the rail to blow: your click is your own
        }
        if (!CHANCE.roll()) {
            return false;
        }
        spot = rail;
        lastSpot = rail;
        lastSpotMs = now();
        click = Cart.onRail(rail);
        cartRetries = 0;
        cartsPut = cartThere ? 1 : 0;
        fireRetried = false;
        aimAfterClickMs = 0;
        reshot = false;
        reshootAtMs = 0;
        relocated = false;
        fireMacroPending = false;
        offhandShot = false;
        bowMode = false;
        fireSpot = null;
        lavaSpot = null;
        pouredLava = null;
        burn = null;
        aimPoint = null;
        fixed = false;
        simple = BucketUse.findSlot(player, Items.FLINT_AND_STEEL) < 0
                && BucketUse.findSlot(player, Items.LAVA_BUCKET) < 0;
        startMs = now();
        aimTries = 0;
        pressedSlot = -1;
        expectedSlot = Cart.heldSlot(player);
        boolean fromCart = cartItem && !cartThere;
        UraniumLog.log("crossbow cart: go-ping, picked up at the " + (fromCart ? "cart" : "fire") + " on the rail at "
                + rail.toShortString());
        SpearDash.setStatus(fromCart ? "Picked up at the cart" : "Picked up at the fire");
        next(fromCart ? Step.CART : Step.FLINT, 0);
        return true;
    }

    /**
     * The rail your click is about: the rail under your crosshair (or on the block it hits),
     * the rail under a cart you're looking at, or a rail with a TNT cart on it right next to
     * where you look. Null if none.
     */
    private static BlockPos pingRail(Minecraft minecraft) {
        if (minecraft.hitResult instanceof net.minecraft.world.phys.EntityHitResult entityHit
                && entityHit.getEntity() instanceof net.minecraft.world.entity.vehicle.minecart.AbstractMinecart minecart) {
            BlockPos under = minecart.blockPosition();
            return Cart.hasRail(minecraft, under) ? under : null;
        }
        if (!(minecraft.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        BlockPos pos = hit.getBlockPos();
        if (Cart.hasRail(minecraft, pos)) {
            return pos;
        }
        if (Cart.hasRail(minecraft, pos.above())) {
            return pos.above();
        }
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos near : BlockPos.betweenClosed(pos.offset(-1, 0, -1), pos.offset(1, 2, 1))) {
            if (Cart.hasRail(minecraft, near) && Cart.cartAt(minecraft, near)) {
                double distance = near.distSqr(pos);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = near.immutable();
                }
            }
        }
        return best;
    }

    /**
     * The slot holds the rail or the TNT cart and the sequence is past using it: a press
     * on it now is a leftover, not you taking over.
     */
    private static boolean usedAlready(LocalPlayer player, int slot) {
        if (slot < 0 || slot > 8 || step == null || step == Step.RAIL || step == Step.CART) {
            return false;
        }
        ItemStack stack = player.getInventory().getItem(slot);
        return Cart.isRail(stack) || stack.is(Items.TNT_MINECART);
    }

    /** The block is the cart's spot, its ground, or right next to them. */
    private static boolean nearSpot(BlockPos pos) {
        return spot != null && Math.abs(pos.getX() - spot.getX()) <= 1
                && Math.abs(pos.getY() - spot.getY()) <= 1 && Math.abs(pos.getZ() - spot.getZ()) <= 1;
    }

    /** Called from the input loop: the next step on its tick. */
    static void update(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (measureUntilMs > 0 && player != null) {
            measure(player);
        }
        if (step == null) {
            if (reshootAtMs > 0 && player != null && minecraft.gameMode != null && minecraft.level != null) {
                checkReshoot(minecraft, player);
                if (step != null) {
                    return;
                }
            }
            if (pickupSpot != null && player != null && minecraft.gameMode != null && minecraft.level != null) {
                pickUpLava(minecraft, player);
            }
            return;
        }
        pickupSpot = null; // a new cart started: the lava stays
        if (jumpHeldMs > 0 && now() - jumpHeldMs > 60) {
            minecraft.options.keyJump.setDown(WTap.physicallyDown(minecraft, minecraft.options.keyJump));
            jumpHeldMs = 0;
        }
        if (step != Step.BOW_DRAW) {
            // Holding right-click makes the game use your hand again every 4 ticks: a second
            // rail, a cart or fire from the item the module just switched to. Held down
            // doesn't count while a cart goes down; press it again to use it after.
            minecraft.options.keyUse.setDown(false);
        }
        if (player == null || minecraft.gameMode == null || minecraft.level == null) {
            step = null;
            return;
        }
        if (Mc.screen(minecraft) == null && Cart.heldSlot(player) != expectedSlot && now() - startMs <= GIVE_UP_MS
                && usedAlready(player, Cart.heldSlot(player))) {
            // You pressed the rail or cart key again (habit, or a fast double press) after the
            // module already used it: that's not taking over, so it goes on with its own slot.
            int pressed = Cart.heldSlot(player);
            Cart.select(minecraft, player, expectedSlot);
            UraniumLog.log("crossbow cart: hotbar " + (pressed + 1) + " pressed at " + step.name().toLowerCase()
                    + " (already used), kept going on hotbar " + (expectedSlot + 1) + "; " + keys(player));
            return;
        }
        if (Mc.screen(minecraft) != null || Cart.heldSlot(player) != expectedSlot
                || now() - startMs > GIVE_UP_MS) {
            UraniumLog.log("crossbow cart: stopped at " + step.name().toLowerCase() + ", "
                    + (Mc.screen(minecraft) != null ? "a screen opened"
                    : Cart.heldSlot(player) != expectedSlot
                    ? "hotbar " + (Cart.heldSlot(player) + 1) + " was picked (wanted "
                            + (expectedSlot + 1) + "; " + keys(player) + ")"
                    : "it took over " + GIVE_UP_MS + " ms"));
            if (step == Step.BOW_DRAW) {
                minecraft.options.keyUse.setDown(WTap.physicallyDown(minecraft, minecraft.options.keyUse));
            }
            step = null; // you took over (or picked a slot yourself)
            AIM.stop();
            SpearDash.setStatus("Cart stopped");
            return;
        }
        if (now() < nextMs || Cart.macroBusy(player)) {
            return;
        }
        if (aimAfterClickMs > 0) {
            // Macro: the game has done the click now, so the turn onto the shot can start
            long ms = aimAfterClickMs;
            aimAfterClickMs = 0;
            aimShot(minecraft, player, ms);
            correctAt = shotAtMs;
        }
        if (step == Step.SHOOT && aimPoint == null) {
            plan(minecraft, player);
        }
        switch (step) {
            case RAIL -> placeRail(minecraft, player);
            case CART -> placeCart(minecraft, player);
            case GUARD -> startGuard(minecraft, player);
            case GUARD_AIM -> placeGuard(minecraft, player);
            case JUMP -> jumpForShot(minecraft, player);
            case PILLAR -> placePillar(minecraft, player);
            case PILLAR_LAND -> {
                if (player.onGround() || now() - pillarMs > 1200) {
                    next(Step.JUMP, TICK_MS); // on top of it: the jump for the shot
                }
            }
            case FLINT -> light(minecraft, player);
            case AIM_FIRE -> lightNow(minecraft, player);
            case LAVA -> pourLava(minecraft, player);
            case CROSSBOW -> pullCrossbow(minecraft, player);
            case SHOOT -> frame(minecraft);
            case BOW_DRAW -> drawBow(minecraft, player);
            case RETRY -> {
                if (aimTries > 0 && AIM.active() && Cart.realClick(minecraft, player, spot, null) == null) {
                    return; // still looking back at the rail
                }
                boolean[] took = new boolean[1];
                Cart.placeCart(minecraft, player, Cart.onRail(spot), took);
                if (Cart.offTarget && lookAgain(player, railTop(spot), "rail")) {
                    return;
                }
                cartPlacedMs = now();
                expectedSlot = Cart.heldSlot(player);
                next(Step.CROSSBOW, TICK_MS); // back to the crossbow (or bow), then the shot
            }
        }
    }

    /** Waits at least until the next tick, so every step shows. */
    private static void next(Step then, long delayMs) {
        step = then;
        aimTries = 0;
        nextMs = now() + Math.max(1, delayMs);
    }

    // Times this step had to look back at its block because the crosshair had slid off it.
    private static int aimTries;

    /**
     * The crosshair slid off the block (you moved): a quick stutter back onto it, and the
     * same step tries again once the look lands. False after two tries; the step then gives
     * up on that click rather than send one where you aren't looking.
     */
    private static boolean lookAgain(LocalPlayer player, Vec3 point, String what) {
        if (aimTries >= (player.onGround() ? 2 : 3)) {
            UraniumLog.log("crossbow cart: crosshair still off the " + what + " after two looks, skipped");
            return false;
        }
        aimTries++;
        float[] want = BucketUse.lookAt(predictEye(player, 80), point);
        AIM.stop();
        turnTo(Mth.wrapDegrees(want[0] - player.getYRot()), want[1] - player.getXRot(),
                PlayStyle.turnMs(60 + java.util.concurrent.ThreadLocalRandom.current().nextLong(0, 30)));
        UraniumLog.log("crossbow cart: crosshair was off the " + what + ", looking back at it first");
        nextMs = now() + 1;
        return true;
    }

    /** A spot on top of the rail in this block: the one closest to where you already look. */
    private static Vec3 railTop(BlockPos pos) {
        return nearestOnFloor(minecraftPlayer(), pos, pos.getY() + 0.06);
    }

    private static LocalPlayer minecraftPlayer() {
        return net.minecraft.client.Minecraft.getInstance().player;
    }

    /**
     * The point on this block's floor (at height y) closest to where your crosshair is now,
     * kept a little inside the edges: the shortest turn that still lands on it.
     */
    private static Vec3 nearestOnFloor(LocalPlayer player, BlockPos cell, double y) {
        java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
        double x = cell.getX() + 0.5;
        double z = cell.getZ() + 0.5;
        if (player != null) {
            Vec3 eye = player.getEyePosition();
            Vec3 look = player.getViewVector(1.0F);
            if (Math.abs(look.y) > 1.0E-4) {
                double t = (y - eye.y) / look.y;
                if (t > 0) {
                    x = eye.x + look.x * t;
                    z = eye.z + look.z * t;
                }
            }
        }
        double margin = 0.2;
        x = Mth.clamp(x + random.nextDouble(-0.05, 0.05), cell.getX() + margin, cell.getX() + 1 - margin);
        z = Mth.clamp(z + random.nextDouble(-0.05, 0.05), cell.getZ() + margin, cell.getZ() + 1 - margin);
        return new Vec3(x, y, z);
    }

    /**
     * A point on this block's floor you can see without the cart (or anyone) in front of it,
     * the one closest to where you look now. The fire block is right next to the cart, so the
     * spot nearest your crosshair is often its edge behind the cart's side: aimed there, the
     * game's own click lands on the cart and no fire goes down. Falls back to the closest
     * point if every one is covered.
     */
    private static Vec3 clearFloorPoint(Minecraft minecraft, LocalPlayer player, BlockPos cell, double y) {
        Vec3 nearest = nearestOnFloor(player, cell, y);
        if (player.getDeltaMovement().horizontalDistance() > 0.08 || !player.onGround()) {
            // on the move the spot slides under the crosshair: the middle has the most room
            nearest = new Vec3(cell.getX() + 0.5, y, cell.getZ() + 0.5);
        }
        Vec3 eye = player.getEyePosition();
        Vec3 best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i <= 4; i++) {
            for (int j = 0; j <= 4; j++) {
                Vec3 point = new Vec3(cell.getX() + 0.15 + 0.7 * i / 4, y, cell.getZ() + 0.15 + 0.7 * j / 4);
                if (Cart.entityInTheWay(minecraft, player, eye, point)) {
                    continue;
                }
                double distance = point.distanceToSqr(nearest);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = point;
                }
            }
        }
        return best != null ? best : nearest;
    }

    /** A turn by the Look setting: Smooth, one even turn; Stutter, quick jumps. */
    private static void turnTo(float yaw, float pitch, long ms) {
        if (smoothLook) {
            AIM.glide(yaw, pitch, ms);
        } else {
            AIM.stutter(yaw, pitch, ms);
        }
    }

    /** A bow in the hotbar, one with Flame first; -1 if none. */
    private static int bowSlot(LocalPlayer player) {
        int any = -1;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(Items.BOW)) {
                if (hasFlame(stack)) {
                    return slot;
                }
                if (any < 0) {
                    any = slot;
                }
            }
        }
        return any;
    }

    private static boolean hasFlame(ItemStack stack) {
        return MaceSwap.enchantmentLevel(stack, net.minecraft.world.item.enchantment.Enchantments.FLAME) > 0;
    }

    /**
     * No room for fire: pulls out the bow instead of the crossbow. With Shoot on it starts
     * drawing it and glides your aim onto the cart, lifted for the arrow's drop.
     */
    private static void pullBow(Minecraft minecraft, LocalPlayer player) {
        if (shoot && waitingForCart(minecraft, player)) {
            if (step == Step.CROSSBOW) {
                nextMs = Math.max(nextMs, now() + 20); // look again in a moment
            }
            return;
        }
        int bow = bowSlot(player);
        if (bow < 0) {
            step = null;
            SpearDash.setStatus("Cart down");
            return;
        }
        AIM.stop(); // the crossbow's glide is not the bow's
        Cart.select(minecraft, player, bow);
        expectedSlot = bow;
        if (!shoot) {
            step = null;
            SpearDash.setStatus("Cart down, bow out");
            return;
        }
        if (player.getProjectile(player.getMainHandItem()).isEmpty()) {
            step = null;
            SpearDash.setStatus("No arrows for the bow");
            UraniumLog.log("crossbow cart: no bow shot, no arrows");
            return;
        }
        minecraft.options.keyUse.setDown(true);
        if (Cart.isMacro()) {
            Cart.pressUse(minecraft, player); // the game starts the draw; held down keeps it
        } else {
            minecraft.gameMode.useItem(player, InteractionHand.MAIN_HAND);
        }
        float[] aim = bowAim(minecraft, player);
        if (aim != null) {
            AIM.glide(Mth.wrapDegrees(aim[0] - player.getYRot()), aim[1] - player.getXRot(), DRAW_TICKS * TICK_MS - 40);
        }
        next(Step.BOW_DRAW, 0);
    }

    /** Holds the draw; lets go once it is drawn long enough and the aim has arrived. */
    private static void drawBow(Minecraft minecraft, LocalPlayer player) {
        if (!player.isUsingItem()) {
            minecraft.options.keyUse.setDown(WTap.physicallyDown(minecraft, minecraft.options.keyUse));
            step = null;
            UraniumLog.log("crossbow cart: the bow stopped drawing");
            return;
        }
        if (player.getTicksUsingItem() < DRAW_TICKS || AIM.active()) {
            minecraft.options.keyUse.setDown(true);
            return;
        }
        int drawn = player.getTicksUsingItem();
        String risk = SelfBlast.danger(minecraft, player, SelfBlast.cartCenter(spot),
                net.minecraft.world.item.BowItem.getPowerForTime(drawn) * 3.0);
        if (risk != null) {
            // hold the arrow: letting go now would blow you up with the cart
            step = null;
            AIM.stop();
            minecraft.options.keyUse.setDown(WTap.physicallyDown(minecraft, minecraft.options.keyUse));
            SpearDash.setStatus("Too close to shoot");
            UraniumLog.log("crossbow cart: bow held, you're too close now, " + risk);
            return;
        }
        minecraft.options.keyUse.setDown(WTap.physicallyDown(minecraft, minecraft.options.keyUse));
        minecraft.gameMode.releaseUsingItem(player);
        step = null;
        lastShotMs = now();
        reshootAtMs = now() + AutoStun.pingMs(minecraft) + 700; // a bow arrow is slower
        SpearDash.setStatus("Cart shot (bow)");
        PlayStyle.bump(0.3);
        UraniumLog.log("crossbow cart: shot the bow " + (now() - startMs) + " ms after the click, drawn " + drawn + " ticks");
    }

    /**
     * The look {yaw, pitch} that lands a bow arrow drawn DRAW_TICKS on the middle of the cart:
     * the arrow is flown the way the game does it (move, air drag, gravity) and the pitch is
     * searched until it comes down on the cart's middle; null if none can reach it.
     */
    private static float[] bowAim(Minecraft minecraft, LocalPlayer player) {
        Vec3 origin = shotOrigin(player);
        Vec3 target = cartBox(minecraft).getCenter();
        float[] direct = BucketUse.lookAt(origin, target);
        double distance = Math.hypot(target.x - origin.x, target.z - origin.z);
        double speed = net.minecraft.world.item.BowItem.getPowerForTime(DRAW_TICKS) * 3.0;
        double up = -60;
        double down = 89;
        for (int i = 0; i < 30; i++) {
            double pitch = (up + down) / 2;
            double height = heightAt(origin.y, Math.toRadians(pitch), speed, distance);
            if (Double.isNaN(height) || height < target.y) {
                down = pitch; // lands short or low: aim higher
            } else {
                up = pitch;
            }
        }
        return new float[] {direct[0], (float) ((up + down) / 2)};
    }

    /** The arrow's height once it has gone this far sideways; NaN if it lands first. */
    private static double heightAt(double startY, double pitch, double speed, double distance) {
        double side = Math.cos(pitch) * speed;
        double vertical = -Math.sin(pitch) * speed;
        double x = 0;
        double y = startY;
        for (int tick = 0; tick < 100; tick++) {
            double nextX = x + side;
            double nextY = y + vertical;
            if (nextX >= distance) {
                return y + (nextY - y) * (distance - x) / Math.max(1.0E-6, side);
            }
            x = nextX;
            y = nextY;
            side *= 0.99;
            vertical = vertical * 0.99 - 0.05;
            if (y < startY - 20) {
                return Double.NaN;
            }
        }
        return Double.NaN;
    }

    /**
     * Before the shot: is the cart really there? The server sometimes refuses it (it still
     * had another item in your hand, or it just said no). Then it is placed once more, with
     * the slot forced, and the shot follows; a second refusal stops it. True while the shot
     * has to wait (or it stopped).
     */
    private static boolean waitingForCart(Minecraft minecraft, LocalPlayer player) {
        if (Cart.cartAt(minecraft, spot) || !minecraft.level.getEntitiesOfClass(MinecartTNT.class,
                new AABB(spot).inflate(1.0)).isEmpty()) {
            return false; // there (or pushed a little along the rail: still the one to shoot)
        }
        if (now() - cartPlacedMs < Cart.cartWaitMs(minecraft)) {
            return true; // not back from the server yet
        }
        if (cartRetries >= 1 || !Cart.hasCart(player) || !Cart.hasRail(minecraft, spot)) {
            UraniumLog.log("crossbow cart: the server blocked the cart" + (cartRetries >= 1 ? " twice" : "")
                    + (Cart.hasRail(minecraft, spot) ? "" : ", and the rail is gone") + ", stopped");
            SpearDash.setStatus("Server blocked the cart");
            step = null;
            AIM.stop();
            return true;
        }
        cartRetries++;
        UraniumLog.log("crossbow cart: the server blocked the cart, placing it again ("
                + (now() - cartPlacedMs) + " ms and no cart)");
        // One tick on another slot first, so the cart slot after it is a real change for
        // the server (no repeated slot packet), then the cart again.
        expectedSlot = Cart.hop(minecraft, player, BucketUse.findSlot(player, Items.TNT_MINECART));
        next(Step.RETRY, TICK_MS);
        return true;
    }

    private static void refuse(String why, BlockPos where) {
        SpearDash.setStatus(why);
        UraniumLog.log("crossbow cart: click left to the game at " + where.toShortString() + ", " + why.toLowerCase());
    }

    private static void placeRail(Minecraft minecraft, LocalPlayer player) {
        int rail = Cart.railSlot(player);
        if (Cart.hasRail(minecraft, spot)) {
            UraniumLog.log("crossbow cart: a rail is already at " + spot.toShortString() + ", cart goes on it");
            expectedSlot = Cart.heldSlot(player);
            next(Step.CART, stepDelay(player));
            return;
        }
        if (rail < 0 || !Cart.railFits(minecraft, spot)) {
            step = null;
            SpearDash.setStatus(rail < 0 ? "No rail in hotbar" : "No room for the rail");
            UraniumLog.log("crossbow cart: stopped, " + (rail < 0 ? "no rail in hotbar" : "no room for the rail"));
            return;
        }
        if (!player.isWithinBlockInteractionRange(click.getBlockPos(), 0.0)) {
            if (relocate(minecraft, player)) {
                return;
            }
            step = null;
            SpearDash.setStatus("Out of reach");
            UraniumLog.log("crossbow cart: stopped, you moved out of reach of the rail spot");
            return;
        }
        if (step == Step.RAIL && aimTries > 0 && AIM.active()
                && Cart.realClick(minecraft, player, click.getBlockPos(), click.getDirection()) == null) {
            return; // still looking back at the spot
        }
        Cart.use(minecraft, player, rail, click);
        if (Cart.offTarget) {
            Vec3 face = click.getLocation();
            if (lookAgain(player, face, "rail spot")) {
                step = Step.RAIL; // tried again from the loop once the look lands
                expectedSlot = Cart.heldSlot(player);
                return;
            }
            step = null;
            SpearDash.setStatus("Crosshair off the spot");
            return;
        }
        expectedSlot = rail;
        if (simple) {
            AIM.shake(0.8F, 140);
        }
        next(Step.CART, stepDelay(player));
    }

    private static void placeCart(Minecraft minecraft, LocalPlayer player) {
        if (Cart.isMacro() && !Cart.hasRail(minecraft, spot)) {
            // You moved: the game's click (a tick after yours) put the rail where the
            // crosshair was by then, a block or two off. That new rail is the spot.
            for (BlockPos near : BlockPos.betweenClosed(spot.offset(-2, -1, -2), spot.offset(2, 1, 2))) {
                if (Cart.hasRail(minecraft, near) && !railsBefore.contains(near)) {
                    UraniumLog.log("crossbow cart: macro, the rail went to " + near.toShortString() + " (you moved), cart goes there");
                    spot = near.immutable();
                    lastSpot = spot;
                    break;
                }
            }
        }
        if (Cart.isMacro() && Cart.hasCart(player) && !Cart.hasRail(minecraft, spot) && now() - startMs < 250) {
            return; // macro: the game puts your rail down on its next tick
        }
        if (!Cart.hasCart(player) || !Cart.hasRail(minecraft, spot)) {
            step = null;
            boolean none = !Cart.hasCart(player);
            SpearDash.setStatus(none ? "No TNT minecart" : "The rail didn't go down");
            UraniumLog.log("crossbow cart: no cart, " + (none ? "none left" : "no rail at " + spot.toShortString()));
            return;
        }
        if (!player.isWithinBlockInteractionRange(spot, 0.0)) {
            if (relocate(minecraft, player)) {
                return;
            }
            step = null;
            SpearDash.setStatus("Out of reach");
            UraniumLog.log("crossbow cart: stopped, you moved out of reach of the rail before the cart");
            return;
        }
        if (aimTries > 0 && AIM.active() && Cart.realClick(minecraft, player, spot, null) == null) {
            return; // still looking back at the rail
        }
        boolean[] took = new boolean[1];
        int cart = Cart.placeCart(minecraft, player, Cart.onRail(spot), took);
        if (Cart.offTarget && lookAgain(player, railTop(spot), "rail")) {
            return;
        }
        boolean placed = took[0];
        UraniumLog.log("crossbow cart: cart " + (placed ? "on the rail" : "refused") + " at " + spot.toShortString()
                + " after " + (now() - startMs) + " ms");
        expectedSlot = cart;
        cartPlacedMs = now();
        if (placed) {
            CartRefill.watch(spot);
            cartsPut++;
        }
        if (placed && overload && cartsPut < 2 && Cart.hasCart(player)) {
            if (Cart.isMacro()) {
                // the game's own click lands on the first cart, not the rail
                UraniumLog.log("crossbow cart: overload skipped, Macro mode can't click the rail under a cart");
            } else {
                UraniumLog.log("crossbow cart: overload, the second cart next tick");
                next(Step.CART, TICK_MS);
                return;
            }
        }
        fixed = false;
        if (simple) {
            AIM.stop();
            AIM.shake(0.8F, 140); // the bow (if any) glides on its own
        }
        guardCell = placed ? planGuard(minecraft, player) : null;
        pillared = false;
        boolean blocked = guardCell == null && placed && shoot && player.onGround()
                && !clearsFrom(minecraft, player, player.getEyePosition());
        // no clear shot from where you stand (a wall, the cart up high): the jump step
        // checks whether a jump, or a block under you and a jump, gets one
        next(guardCell != null ? Step.GUARD : blocked ? Step.JUMP : Step.FLINT, 0);
    }

    /**
     * Fire where your shot will pass, if there is flint and steel and room for fire. A TNT
     * cart only blows from an arrow that is already burning, and an arrow catches fire by
     * flying through a fire block, so the fire goes in the block next to the rail (any of
     * the 8 around it, corners too) that the shot line goes through the most before the
     * cart. The shot line runs from your eye to a point on the cart, picked so it crosses a
     * block that can take fire; the aim correction before the shot puts your view on it.
     */
    private static void light(Minecraft minecraft, LocalPlayer player) {
        next(Step.CROSSBOW, 0);
        int crossbow = BucketUse.findSlot(player, Items.CROSSBOW);
        if (loadedCrossbow(player) < 0 && !offhandLoaded(player)) {
            // No loaded crossbow: the fire is only there to light a crossbow arrow, so it is
            // skipped and the bow is used (a Flame one first).
            bowMode = bowSlot(player) >= 0;
            UraniumLog.log("crossbow cart: " + (crossbow < 0 ? "no crossbow" : "crossbow not loaded")
                    + ", no fire" + (bowMode ? ", using the bow" : ""));
            return;
        }
        shotAtMs = now() + 2 * TICK_MS + 100; // about when the shot goes, for where you'll be
        plan(minecraft, player);
        int flint = BucketUse.findSlot(player, Items.FLINT_AND_STEEL);
        if ((flint < 0 || fireSpot == null) && startLava(minecraft, player)) {
            return;
        }
        if (flint < 0 || fireSpot == null) {
            int bow = bowSlot(player);
            bowMode = bow >= 0;
            fireSpot = null;
            if (!bowMode && shoot) {
                if (!fireworkLoaded(player)) {
                    // An arrow that never flew through fire or lava doesn't set a TNT cart
                    // off: the shot would only waste the crossbow's load.
                    UraniumLog.log("crossbow cart: nothing to light the arrow (no fire, lava or bow), no shot");
                    SpearDash.setStatus("Cart down, nothing to light it");
                    step = null;
                    AIM.stop();
                    return;
                }
                aimShot(minecraft, player, TICK_MS + stepDelay(player)); // a firework blast sets it off
            }
            UraniumLog.log("crossbow cart: no fire" + (flint < 0 ? " (no flint and steel)" : " (no block the shot passes can burn)")
                    + (bowMode ? ", using the bow" + (hasFlame(player.getInventory().getItem(bow)) ? " (Flame)" : " (no Flame, it won't light)")
                    : ", and no bow"));
            return;
        }
        // Flint out, then the view jumps (a stutter, not a slide) onto the block the fire
        // goes in; the fire goes down once it is there.
        Cart.select(minecraft, player, flint);
        expectedSlot = flint;
        java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
        Vec3 look = clearFloorPoint(minecraft, player, fireSpot, fireSpot.getY());
        float[] want = BucketUse.lookAt(predictEye(player, 110), look);
        AIM.stop();
        turnTo(Mth.wrapDegrees(want[0] - player.getYRot()), want[1] - player.getXRot(),
                PlayStyle.turnMs(90 + random.nextLong(0, 40)));
        next(Step.AIM_FIRE, 0);
    }

    /** The view is on the fire block: light it, then jump onto the shot. */
    private static void lightNow(Minecraft minecraft, LocalPlayer player) {
        if (AIM.active()) {
            // walking or falling, the spot slides off again once the turn stops: click the
            // moment the crosshair is on it, even mid-turn
            if (Cart.realClick(minecraft, player, fireSpot.below(), Direction.UP) == null) {
                return; // still moving onto it
            }
            AIM.stop();
        }
        int flint = BucketUse.findSlot(player, Items.FLINT_AND_STEEL);
        if (new AABB(fireSpot).intersects(player.getBoundingBox().inflate(0.1))) {
            // you moved into the fire's block (sprinting at the cart): not lit under your feet
            UraniumLog.log("crossbow cart: you're standing in the fire's block, no fire");
            fireSpot = null;
            burn = null;
            expectedSlot = Cart.heldSlot(player);
            next(Step.CROSSBOW, 0);
            return;
        }
        BlockHitResult top = new BlockHitResult(Vec3.atBottomCenterOf(fireSpot), Direction.UP, fireSpot.below(), false);
        boolean lit = flint >= 0 && Cart.use(minecraft, player, flint, top);
        if (!lit && Cart.offTarget && lookAgain(player, clearFloorPoint(minecraft, player, fireSpot, fireSpot.getY()), "fire block")) {
            return;
        }
        UraniumLog.log("crossbow cart: fire at " + fireSpot.toShortString() + " ("
                + (fireSpot.getX() != spot.getX() && fireSpot.getZ() != spot.getZ() ? "corner" : "side")
                + ")" + (lit ? "" : " refused"));
        if (lit) {
            burn = new AABB(fireSpot);
            burnSetMs = now();
            fireMacroPending = Cart.isMacro() && !fireThere(minecraft, fireSpot);
        }
        expectedSlot = Cart.heldSlot(player);
        if (shoot) {
            if (Cart.isMacro() && lit) {
                // the game clicks on its next tick: turning away now would move the crosshair
                // off the fire block before it clicks, and the click would land on the cart
                aimAfterClickMs = TICK_MS + stepDelay(player);
            } else {
                aimShot(minecraft, player, TICK_MS + stepDelay(player));
            }
        }
        next(Step.CROSSBOW, 0);
    }

    /**
     * Jumps the view (a stutter) onto the shot point and sets the shot for this many ms
     * from now.
     */
    private static void aimShot(Minecraft minecraft, LocalPlayer player, long ms) {
        shotAtMs = now() + ms;
        glided = false;
        AIM.stop();
        Vec3 target = shotPoint(minecraft, player);
        float[] want = BucketUse.lookAt(shotOrigin(player), target);
        float yaw = Mth.wrapDegrees(want[0] - player.getYRot());
        float pitch = want[1] - player.getXRot();
        if (Math.hypot(yaw, pitch) < 0.3) {
            return; // already there
        }
        turnTo(yaw, pitch, Math.max(60, ms - 20));
        glided = true;
    }

    /**
     * Where the shot aims: a point on the cart where the arrow goes in at or above the shot
     * line AND flies through the fire (or lava) on the way, as far through it as possible
     * (a little preference for staying near where you already aim). Without fire or lava,
     * just above the line.
     */
    private static Vec3 shotPoint(Minecraft minecraft, LocalPlayer player) {
        AABB box = cartBox(minecraft);
        double line = box.minY + SHOT_LINE;
        Vec3 origin = shotOrigin(player);
        Vec3 now = box.clip(origin, origin.add(player.getViewVector(1.0F).scale(8.0))).orElse(null);
        if (burn == null) {
            return aboveLine(box, line, now);
        }
        Vec3 best = null;
        double bestScore = -Double.MAX_VALUE;
        double low = line + 0.08;
        double high = box.maxY - 0.05;
        for (int i = 0; i <= 4; i++) {
            for (int j = 0; j <= 4; j++) {
                for (int k = 0; k <= 3; k++) {
                    Vec3 p = new Vec3(box.minX + 0.15 + (box.getXsize() - 0.3) * i / 4,
                            low + (high - low) * k / 3, box.minZ + 0.15 + (box.getZsize() - 0.3) * j / 4);
                    Vec3 far = origin.add(p.subtract(origin).normalize().scale(8.0));
                    Vec3 entry = box.clip(origin, far).orElse(null);
                    if (entry == null || entry.y < line + 0.03) {
                        continue;
                    }
                    if (minecraft.level.clip(new net.minecraft.world.level.ClipContext(origin, entry,
                            net.minecraft.world.level.ClipContext.Block.COLLIDER,
                            net.minecraft.world.level.ClipContext.Fluid.NONE, player)).getType() == HitResult.Type.BLOCK) {
                        continue; // a block in the way would take the arrow
                    }
                    double through = Cart.overlap(origin, entry, burn);
                    if (through < 0.15) {
                        continue;
                    }
                    double score = through - (now == null ? 0 : entry.distanceTo(now) * 0.3);
                    if (score > bestScore) {
                        bestScore = score;
                        best = p;
                    }
                }
            }
        }
        if (best == null) {
            UraniumLog.log("crossbow cart: no shot both above the line and through the fire, going above the line");
            return aboveLine(box, line, now);
        }
        return best;
    }

    /**
     * Lava instead of fire, for spots fire can't go: you shoot from under the cart or down
     * at it (the shot passes no ground next to it), or you have no flint and steel. An arrow
     * flying through lava catches fire the same way. The lava goes in the block around the
     * cart (above, below and corners too) the shot passes the longest; the bucket goes
     * wherever you look, so the aim turns onto a face next to that block first. True if it
     * started.
     */
    private static boolean startLava(Minecraft minecraft, LocalPlayer player) {
        int lava = BucketUse.findSlot(player, Items.LAVA_BUCKET);
        if (lava < 0) {
            return false;
        }
        lavaSpot = planLava(minecraft, player);
        if (lavaSpot == null) {
            UraniumLog.log("crossbow cart: no block the shot passes can take lava either");
            return false;
        }
        float[] aim = BucketUse.aimLavaAt(minecraft, player, lavaSpot);
        if (aim == null) {
            lavaSpot = null;
            return false;
        }
        Cart.select(minecraft, player, lava);
        expectedSlot = lava;
        AIM.stop();
        long turn = PlayStyle.turnMs(90 + java.util.concurrent.ThreadLocalRandom.current().nextLong(0, 40));
        turnTo(Mth.wrapDegrees(aim[0] - player.getYRot()), aim[1] - player.getXRot(), turn);
        next(Step.LAVA, 0);
        return true;
    }

    /** Pours the lava once the aim is on its face, then glides back onto the cart. */
    private static void pourLava(Minecraft minecraft, LocalPlayer player) {
        if (AIM.active()) {
            return; // still turning
        }
        BlockPos lands = BucketUse.lavaPlacement(minecraft, player, player.getYRot(), player.getXRot());
        boolean poured = false;
        if (lavaSpot.equals(lands) && player.getMainHandItem().is(Items.LAVA_BUCKET)) {
            Debug.click("lava poured");
            if (Cart.isMacro()) {
                Cart.pressUse(minecraft, player);
            } else {
                minecraft.gameMode.useItem(player, InteractionHand.MAIN_HAND);
            }
            poured = true;
            pouredLava = lavaSpot;
            UraniumLog.log("crossbow cart: lava at " + lavaSpot.toShortString()
                    + (lavaSpot.getY() != spot.getY() ? (lavaSpot.getY() > spot.getY() ? " (above)" : " (below)") : " (beside)"));
        } else {
            bowMode = bowSlot(player) >= 0;
            UraniumLog.log("crossbow cart: the lava would land at " + (lands == null ? "nothing" : lands.toShortString())
                    + ", not poured" + (bowMode ? ", using the bow" : ""));
        }
        if (poured) {
            burnSetMs = now();
            burn = new AABB(lavaSpot.getX(), lavaSpot.getY(), lavaSpot.getZ(),
                    lavaSpot.getX() + 1, lavaSpot.getY() + LAVA_HEIGHT, lavaSpot.getZ() + 1);
        }
        if (poured && shoot) {
            aimPoint = cartMiddle(minecraft);
            fixed = false;
            long ms = TICK_MS + 60 + java.util.concurrent.ThreadLocalRandom.current().nextLong(0, 40);
            if (Cart.isMacro()) {
                aimAfterClickMs = ms; // same as the fire: the game pours on its next tick
            } else {
                aimShot(minecraft, player, ms);
            }
        }
        next(Step.CROSSBOW, TICK_MS);
    }

    // The lava this cart poured (null if none), and the pickup after the blast.
    private static BlockPos pouredLava;
    private static BlockPos pickupSpot;
    private static long pickupShotMs;
    // 0 waiting for the blast, 1 turning onto the lava, 2 scooped (switching back)
    private static int pickupStage;
    private static int pickupTries;
    private static int pickupBackSlot;
    private static int pickupHeldSlot;
    private static long pickupNextMs;
    // How long after the shot to wait for the blast before getting the lava anyway.
    private static final long PICKUP_WAIT_MS = 2500;

    // How long the lava waits for you to look back at it before it's left.
    private static final long PICKUP_KEEP_MS = 10_000;

    /**
     * After the blast, look back at the lava and it's picked up: the moment your crosshair
     * lands on it, the empty bucket comes out, scoops it, and your hand goes back to what it
     * had. The view never moves for it. It waits up to PICKUP_KEEP_MS; a new cart, the lava
     * going away, or no empty bucket and it's left.
     */
    private static void pickUpLava(Minecraft minecraft, LocalPlayer player) {
        long now = now();
        if (now < pickupNextMs || Cart.macroBusy(player) || Mc.screen(minecraft) != null) {
            return;
        }
        BlockPos lava = pickupSpot;
        boolean source = minecraft.level.getFluidState(lava).is(net.minecraft.tags.FluidTags.LAVA)
                && minecraft.level.getFluidState(lava).isSource();
        BlockHitResult hit = BucketUse.ray(minecraft, player, player.getYRot(), player.getXRot(),
                net.minecraft.world.level.ClipContext.Fluid.SOURCE_ONLY);
        boolean looking = hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(lava);
        switch (pickupStage) {
            case 0 -> {
                if (!source || now - pickupShotMs > PICKUP_KEEP_MS) {
                    pickupSpot = null;
                    UraniumLog.log("crossbow cart: lava left, " + (!source ? "it's gone" : "you didn't look back at it"));
                    return;
                }
                if (Cart.cartAt(minecraft, spot == null ? lava : spot) && now - pickupShotMs < PICKUP_WAIT_MS) {
                    return; // the cart hasn't blown yet
                }
                if (!looking || player.isUsingItem()) {
                    return;
                }
                int bucket = BucketUse.findSlot(player, Items.BUCKET);
                if (bucket < 0) {
                    return; // no empty bucket in the hotbar (yet)
                }
                pickupBackSlot = Cart.heldSlot(player);
                Cart.select(minecraft, player, bucket);
                pickupStage = 1;
                pickupNextMs = now + 1;
            }
            case 1 -> {
                if (!looking || !source || !player.getMainHandItem().is(Items.BUCKET)) {
                    // looked away before the scoop: hand back, wait for the next look
                    if (Cart.heldSlot(player) != pickupBackSlot) {
                        Cart.select(minecraft, player, pickupBackSlot);
                    }
                    pickupStage = 0;
                    pickupNextMs = now + TICK_MS;
                    return;
                }
                Debug.click("lava scooped back up");
                if (Cart.isMacro()) {
                    Cart.pressUse(minecraft, player);
                } else {
                    minecraft.gameMode.useItem(player, InteractionHand.MAIN_HAND);
                }
                UraniumLog.log("crossbow cart: you looked back at the lava, picked it up at " + lava.toShortString());
                SpearDash.setStatus("Lava back");
                pickupStage = 2;
                pickupNextMs = now + TICK_MS + java.util.concurrent.ThreadLocalRandom.current().nextLong(20, 60);
            }
            default -> {
                pickupSpot = null;
                if (pickupBackSlot >= 0 && pickupBackSlot != Cart.heldSlot(player)) {
                    Cart.select(minecraft, player, pickupBackSlot);
                }
            }
        }
    }

    /** The block around the cart the shot line runs through the longest that lava can go in. */
    private static BlockPos planLava(Minecraft minecraft, LocalPlayer player) {
        Vec3 origin = shotOrigin(player);
        Vec3 target = cartMiddle(minecraft);
        double best = 0.05;
        BlockPos found = null;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    BlockPos cell = spot.offset(dx, dy, dz);
                    AABB lava = new AABB(cell.getX(), cell.getY(), cell.getZ(),
                            cell.getX() + 1, cell.getY() + LAVA_HEIGHT, cell.getZ() + 1);
                    double length = overlap(origin, target, lava);
                    if (length > best && canPour(minecraft, player, cell)) {
                        best = length;
                        found = cell;
                    }
                }
            }
        }
        return found;
    }

    /** Lava can go here: free air, not on you, and a face next to it your crosshair can reach. */
    private static boolean canPour(Minecraft minecraft, LocalPlayer player, BlockPos cell) {
        if (!minecraft.level.getBlockState(cell).isAir()
                || new AABB(cell).intersects(player.getBoundingBox().inflate(0.3))) {
            return false;
        }
        float[] aim = BucketUse.aimLavaAt(minecraft, player, cell);
        return aim != null && cell.equals(BucketUse.lavaPlacement(minecraft, player, aim[0], aim[1]));
    }

    /**
     * One step's wait: your Delay, shorter the faster you move (an elytra dive past the rail
     * spot leaves no time), and half with a little jitter when there is nothing to light with.
     */
    private static long stepDelay(LocalPlayer player) {
        double speed = player.getDeltaMovement().length();
        double factor = speed <= NORMAL_SPEED ? 1 : Math.max(0.2, NORMAL_SPEED / speed);
        double ms = DELAY.pick() * factor;
        if (simple) {
            ms = ms * 0.5 + java.util.concurrent.ThreadLocalRandom.current().nextGaussian() * 10;
        }
        // Never two clicks in one tick: a hand can't, and anticheats count it.
        return Math.max(TICK_MS, PlayStyle.delay(Math.round(ms)));
    }

    /**
     * Picks the aim point, the middle of the cart's hitbox, and the fire block: the
     * neighbour (side or corner) the arrow's line to that point passes through the longest.
     * fireSpot stays null if no block on that line can take fire.
     */
    private static void plan(Minecraft minecraft, LocalPlayer player) {
        Vec3 origin = shotOrigin(player);
        aimPoint = cartMiddle(minecraft);
        double best = 0.05; // a line that only grazes a block isn't enough to light the arrow
        fireSpot = null;
        // Any block the shot flies through on its way, not only the ones next to the rail:
        // the arrow catches fire wherever it passes through it.
        AABB way = new AABB(origin, aimPoint).inflate(0.5);
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(way.minX, way.minY, way.minZ),
                BlockPos.containing(way.maxX, way.maxY, way.maxZ))) {
            BlockPos cell = pos.immutable();
            if (cell.equals(spot)) {
                continue;
            }
            double length = overlap(origin, aimPoint, new AABB(cell));
            // a little extra for cells close to the cart, so the arrow can't burn out first
            double score = length - 0.01 * Math.sqrt(cell.distSqr(spot));
            if (length > 0.05 && score > best && canBurn(minecraft, player, cell)) {
                best = score;
                fireSpot = cell;
            }
        }
    }

    /** The middle of the TNT cart's hitbox on the rail; where it will sit if it isn't seen yet. */
    private static Vec3 cartMiddle(Minecraft minecraft) {
        for (MinecartTNT cart : minecraft.level.getEntitiesOfClass(MinecartTNT.class, new AABB(spot).inflate(1.0))) {
            return cart.getBoundingBox().getCenter();
        }
        return Vec3.atBottomCenterOf(spot).add(0, 0.0625 + 0.35, 0);
    }

    /** Fire can go here: free air, a solid top under it, in reach, and not where you stand. */
    private static boolean canBurn(Minecraft minecraft, LocalPlayer player, BlockPos cell) {
        BlockPos ground = cell.below();
        return minecraft.level.getBlockState(cell).isAir()
                && minecraft.level.getBlockState(ground).isFaceSturdy(minecraft.level, ground, Direction.UP)
                && !AutoWeb.reactsToUse(minecraft.level.getBlockState(ground).getBlock())
                && player.isWithinBlockInteractionRange(ground, 0.0)
                && !new AABB(cell).intersects(player.getBoundingBox());
    }

    /** How long the line from a to b runs inside the box. */
    private static double overlap(Vec3 a, Vec3 b, AABB box) {
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

    /**
     * Where the crossbow shot will start: just under your eye, where your eye will be when
     * the shot goes (shotAtMs). Jumping, falling or flying, you move a lot in the few ticks
     * the turn takes: a jump drops you about half a block in the 3 ticks after its top, a
     * slam fall 3+ blocks, an elytra 4-8 blocks. Aiming from where you are now would put the
     * shot under the cart's line by the time it fires; this flies you forward the way the
     * game does (gravity 0.08 a tick, air drag 0.98; an elytra just keeps its speed) and
     * stops at the ground under you.
     */
    private static Vec3 shotOrigin(LocalPlayer player) {
        return predictEye(player, shotAtMs - now()).subtract(0, 0.1, 0);
    }

    /**
     * Where your eye will be this many ms from now (the same flight as the shot's). A turn
     * aimed from where you are when it starts lands off its spot if you're jumping or
     * falling: in the ~100 ms a turn takes you can rise or drop half a block.
     */
    static Vec3 predictEye(LocalPlayer player, long aheadMs) {
        Vec3 eye = player.getEyePosition();
        long ahead = Math.min(600, Math.max(0, aheadMs));
        int ticks = (int) (ahead / TICK_MS);
        if (ticks <= 0) {
            return eye;
        }
        if (player.hasEffect(net.minecraft.world.effect.MobEffects.SLOW_FALLING)
                || player.hasEffect(net.minecraft.world.effect.MobEffects.LEVITATION)
                || player.isInWater() || player.isInLava() || player.onClimbable() || player.isAutoSpinAttack()
                || player.isPassenger()) {
            return eye; // other physics (slow fall, levitation, water, a ladder, riptide, riding): not guessed
        }
        Vec3 v = player.getDeltaMovement();
        boolean flying = player.isFallFlying();
        boolean air = flying || !player.onGround() || v.y > 0.01;
        double vx = v.x;
        double vz = v.z;
        double x = 0;
        double y = 0;
        double z = 0;
        double vy = v.y;
        for (int i = 0; i < ticks; i++) {
            x += vx;
            z += vz;
            if (air) {
                y += vy;
                if (!flying) {
                    vy = (vy - 0.08) * 0.98;
                    vx *= 0.91; // air drag (a knockback dies out like this)
                    vz *= 0.91;
                }
            }
        }
        if (y < 0) {
            // not through the ground under you
            Vec3 feet = player.position();
            BlockHitResult floor = player.level().clip(new net.minecraft.world.level.ClipContext(feet,
                    feet.add(x, y - 0.01, z), net.minecraft.world.level.ClipContext.Block.COLLIDER,
                    net.minecraft.world.level.ClipContext.Fluid.NONE, player));
            if (floor.getType() == HitResult.Type.BLOCK) {
                y = Math.max(y, floor.getLocation().y - feet.y);
            }
        }
        return eye.add(x, y, z);
    }

    /** The shot starts here right now (for checking where you aim at this moment). */
    private static Vec3 shotOriginNow(LocalPlayer player) {
        return player.getEyePosition().subtract(0, 0.1, 0);
    }

    private static void pullCrossbow(Minecraft minecraft, LocalPlayer player) {
        int loaded = loadedCrossbow(player);
        boolean offhand = loaded < 0 && offhandLoaded(player);
        if (!bowMode && shoot && loaded < 0 && !offhand && bowSlot(player) >= 0) {
            bowMode = true;
            UraniumLog.log("crossbow cart: " + (BucketUse.findSlot(player, Items.CROSSBOW) < 0
                    && !player.getOffhandItem().is(Items.CROSSBOW) ? "no crossbow" : "crossbow not loaded") + ", using the bow");
        }
        if (bowMode) {
            pullBow(minecraft, player);
            return;
        }
        offhandShot = offhand;
        if (offhand) {
            // The loaded crossbow is in your offhand: no swap, it fires from there.
            expectedSlot = Cart.heldSlot(player);
        } else {
            int crossbow = loaded >= 0 ? loaded : BucketUse.findSlot(player, Items.CROSSBOW);
            if (crossbow < 0) {
                step = null;
                AIM.stop();
                SpearDash.setStatus("Cart down");
                return;
            }
            Cart.select(minecraft, player, crossbow);
            expectedSlot = crossbow;
        }
        if (shoot) {
            correctAt = shotAtMs; // the glide that started with the cart ends about now
            next(Step.SHOOT, 0);
        } else {
            step = null;
            SpearDash.setStatus("Cart down");
        }
    }

    /**
     * Right after the cart goes down (with Shoot on): picks when the shot goes, and if your
     * aim would hit under the shot line (it starts on the ground, where you clicked the rail),
     * starts one slow glide up to just above the line that lasts until then. The flint and
     * crossbow swaps happen during it, so the view never stops and starts.
     */
    private static void startGlide(Minecraft minecraft, LocalPlayer player) {
        shotAtMs = now() + 2 * TICK_MS + stepDelay(player);
        glideToLine(minecraft, player);
    }

    /** Glides the aim to just above the shot line, arriving at shotAtMs; nothing if it is there. */
    private static void glideToLine(Minecraft minecraft, LocalPlayer player) {
        glided = false;
        AIM.stop();
        AABB box = cartBox(minecraft);
        double line = box.minY + SHOT_LINE;
        Vec3 origin = shotOrigin(player);
        Vec3 hit = box.clip(origin, origin.add(player.getViewVector(1.0F).scale(8.0))).orElse(null);
        if (hit != null && hit.y >= line) {
            return; // already above the line: nothing to move
        }
        Vec3 target = aboveLine(box, line, hit);
        float[] want = BucketUse.lookAt(origin, target);
        AIM.glide(Mth.wrapDegrees(want[0] - player.getYRot()), want[1] - player.getXRot(), shotAtMs - now());
        glided = true;
    }

    /** Just above the line, across from where you aim now (or the middle if off the cart). */
    private static Vec3 aboveLine(AABB box, double line, Vec3 hit) {
        Vec3 center = box.getCenter();
        double x = hit == null ? center.x : Mth.clamp(hit.x, box.minX + 0.15, box.maxX - 0.15);
        double z = hit == null ? center.z : Mth.clamp(hit.z, box.minZ + 0.15, box.maxZ - 0.15);
        return new Vec3(x, Math.min(line + ABOVE_LINE, box.maxY - 0.05), z);
    }

    /**
     * Called every frame by the HUD (and from the input loop): moves the glide along, then
     * the shot. An imaginary line runs across the cart 5 pixels up its hitbox; the shot goes
     * when the glide is done. Only if no glide ran and the aim is somehow under the line
     * (you moved the mouse down) is there one quick fix before it.
     */
    static void frame(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (step == null || player == null || minecraft.gameMode == null || minecraft.level == null) {
            return;
        }
        if (AIM.active()) {
            AIM.step(player);
            return;
        }
        if (step != Step.SHOOT || now() < correctAt) {
            return;
        }
        if (waitingForCart(minecraft, player)) {
            return;
        }
        AABB box = cartBox(minecraft);
        double line = box.minY + SHOT_LINE;
        Vec3 origin = shotOriginNow(player);
        Vec3 hit = box.clip(origin, origin.add(player.getViewVector(1.0F).scale(8.0))).orElse(null);
        // On or above the line: fire. Under it (you moved or fell during the glide, so the
        // aim it worked out went low): one quick fix onto the shot point from where you are
        // now, then fire whatever happens.
        if (fireMacroPending && fireSpot != null) {
            // Macro: the game did the fire click itself. Is the fire really there?
            if (fireThere(minecraft, fireSpot)) {
                fireMacroPending = false;
            } else if (now() - burnSetMs < 2 * TICK_MS + 40) {
                return; // the game clicks on its next tick; give it that
            } else {
                fireMacroPending = false;
                UraniumLog.log("crossbow cart: the fire click made no fire at " + fireSpot.toShortString()
                        + "; the game's crosshair was on " + describe(minecraft) + "; mine was on "
                        + describeRay(minecraft, player) + (fireRetried ? ", shooting without it" : ", trying once more"));
                int flint = BucketUse.findSlot(player, Items.FLINT_AND_STEEL);
                if (!fireRetried && flint >= 0) {
                    fireRetried = true;
                    burn = null;
                    Cart.select(minecraft, player, flint);
                    expectedSlot = flint;
                    next(Step.AIM_FIRE, 0);
                    lookAgain(player, clearFloorPoint(minecraft, player, fireSpot, fireSpot.getY()), "fire block");
                    return;
                }
                burn = null;
            }
        }
        if (burn != null && burnGone(minecraft)) {
            step = null;
            AIM.stop();
            SpearDash.setStatus("Fire went out");
            UraniumLog.log("crossbow cart: the fire (or lava) is gone before the shot, the arrow wouldn't light, no shot");
            return;
        }
        if (hit != null && hit.y >= line || fixed) {
            String risk = SelfBlast.danger(minecraft, player, SelfBlast.cartCenter(spot), SelfBlast.CROSSBOW_SPEED);
            if (risk != null) {
                // you came closer since the click: the shot would blow you up with it
                step = null;
                AIM.stop();
                SpearDash.setStatus("Too close to shoot");
                UraniumLog.log("crossbow cart: no shot, you're too close now, " + risk);
                return;
            }
            fire(minecraft, player, (hit == null ? "off the cart" : hit.y >= line
                    ? String.format(java.util.Locale.ROOT, "%.0f px above the line", (hit.y - line) * 16)
                    : String.format(java.util.Locale.ROOT, "%.0f px under the line", (line - hit.y) * 16))
                    + (glided ? ", after the glide" : fixed ? ", after a fix" : ", no move"));
            return;
        }
        fixed = true;
        UraniumLog.log(String.format(java.util.Locale.ROOT, "crossbow cart: aim ended %s, one quick fix first",
                hit == null ? "off the cart" : String.format(java.util.Locale.ROOT, "%.0f px under the line", (line - hit.y) * 16)));
        shotAtMs = now() + FIX_MS; // aim from where you'll be when the fix ends
        float[] want = BucketUse.lookAt(shotOrigin(player), shotPoint(minecraft, player));
        AIM.glide(Mth.wrapDegrees(want[0] - player.getYRot()), want[1] - player.getXRot(), FIX_MS);
    }

    // After a shot: how much your own blast really did, next to what the guard expected.
    private static long measureUntilMs;
    private static double measureStartHealth;
    private static double measureLowest;
    private static double measureExpect;
    private static double measureDistance;

    private static void measure(LocalPlayer player) {
        double health = player.getHealth() + player.getAbsorptionAmount();
        measureLowest = Math.min(measureLowest, health);
        if (player.getHealth() <= 0 || now() < measureUntilMs) {
            return;
        }
        measureUntilMs = 0;
        double took = measureStartHealth - measureLowest;
        UraniumLog.log(String.format(java.util.Locale.ROOT,
                "self blast: %.1f blocks from the cart, the guard expected about %.1f (%s roll), you took %.1f%s",
                measureDistance, measureExpect, SelfBlast.modeLabel().toLowerCase(), took,
                took <= 0.05 ? " (nothing, or a totem/regen covered it)" : ""));
    }

    // Self guard: the block between you and the cart, and when the jump key went down.
    private static BlockPos guardCell;
    private static int guardSlot;
    private static Vec3 guardPoint;
    private static long jumpHeldMs;

    /**
     * Self guard (Block): where a block goes to keep your own blast off you, or null if not
     * worth it or no room. The block goes right next to the cart on your side: the blast
     * starts at the bottom of the cart, so one block from it every line to your body
     * (feet to head, even mid-jump) runs under 1 block high, and one block covers them all.
     * You jump so the shot clears it: standing 4 blocks away your shot runs 0.78 high where
     * the block is, jumping (eye 1.25 higher) 1.09, over it.
     */
    private static BlockPos planGuard(Minecraft minecraft, LocalPlayer player) {
        if (SelfBlast.mode() == 0 || !shoot || !player.onGround() || player.isPassenger()) {
            return null;
        }
        double expect = SelfBlast.damage(minecraft, player, SelfBlast.cartCenter(spot), SelfBlast.CROSSBOW_SPEED);
        double health = player.getHealth() + player.getAbsorptionAmount();
        boolean low = health < GUARD_UNDER.tenths() * 2;
        // counts the armor you really wear: an elytra in the chestplate's place takes away
        // its 8 armor and its Protection: at 4 blocks the blast does about twice as much (11.7 to 24.7)
        boolean surePop = expect >= health - 0.5;
        if (expect < 1 || !low && !surePop) {
            return null; // healthy enough for this blast: no guard, a faster shot
        }
        int slot = AntiCart.blockSlot(player);
        if (slot < 0) {
            UraniumLog.log("crossbow cart: no block for the guard, shooting without it");
            return null;
        }
        double dx = player.getX() - (spot.getX() + 0.5);
        double dz = player.getZ() - (spot.getZ() + 0.5);
        double length = Math.hypot(dx, dz);
        if (length < 2.2) {
            return null; // too close: the guard's block would be where you stand
        }
        // toward you, one of the 8 cells around the cart
        int stepX = (int) Math.round(dx / length * 1.3);
        int stepZ = (int) Math.round(dz / length * 1.3);
        stepX = Math.max(-1, Math.min(1, stepX));
        stepZ = Math.max(-1, Math.min(1, stepZ));
        if (stepX == 0 && stepZ == 0) {
            return null;
        }
        BlockPos cell = spot.offset(stepX, 0, stepZ);
        BlockPos ground = cell.below();
        if (!minecraft.level.getBlockState(cell).canBeReplaced()
                || new AABB(cell).intersects(player.getBoundingBox())
                || !minecraft.level.getBlockState(ground).isFaceSturdy(minecraft.level, ground, Direction.UP)
                || AutoWeb.reactsToUse(minecraft.level.getBlockState(ground).getBlock())
                || !player.isWithinBlockInteractionRange(ground, 0.0)
                || BucketUse.aimAtFace(minecraft, player, ground, Direction.UP) == null) {
            UraniumLog.log("crossbow cart: no room for the guard's block, shooting without it");
            return null;
        }
        guardSlot = slot;
        UraniumLog.log(String.format(java.util.Locale.ROOT,
                "crossbow cart: guard (%s), a block at %s keeps about %.1f of your own blast off you",
                surePop ? "the blast would pop you" : String.format(java.util.Locale.ROOT, "%.1f hearts", health / 2),
                cell.toShortString(), expect));
        return cell;
    }

    /** The block out, the view onto the ground where it goes. */
    private static void startGuard(Minecraft minecraft, LocalPlayer player) {
        if (AntiCart.blockSlot(player) < 0 || !minecraft.level.getBlockState(guardCell).canBeReplaced()) {
            next(Step.FLINT, 0);
            return;
        }
        guardSlot = AntiCart.blockSlot(player);
        Cart.select(minecraft, player, guardSlot);
        expectedSlot = guardSlot;
        guardPoint = clearFloorPoint(minecraft, player, guardCell, guardCell.getY());
        float[] want = BucketUse.lookAt(predictEye(player, 100), guardPoint);
        AIM.stop();
        turnTo(Mth.wrapDegrees(want[0] - player.getYRot()), want[1] - player.getXRot(),
                PlayStyle.turnMs(80 + java.util.concurrent.ThreadLocalRandom.current().nextLong(0, 40)));
        next(Step.GUARD_AIM, 0);
    }

    /** Places the guard's block once the view is on its spot, then the jump. */
    private static void placeGuard(Minecraft minecraft, LocalPlayer player) {
        if (AIM.active()) {
            if (Cart.realClick(minecraft, player, guardCell.below(), Direction.UP) == null) {
                return;
            }
            AIM.stop(); // on it already: place now
        }
        BlockHitResult top = new BlockHitResult(guardPoint, Direction.UP, guardCell.below(), false);
        boolean placed = Cart.use(minecraft, player, guardSlot, top);
        if (!placed && Cart.offTarget && lookAgain(player, clearFloorPoint(minecraft, player, guardCell, guardCell.getY()),
                "guard's spot")) {
            return;
        }
        UraniumLog.log("crossbow cart: guard block at " + guardCell.toShortString() + (placed ? "" : " refused"));
        expectedSlot = Cart.heldSlot(player);
        next(placed ? Step.JUMP : Step.FLINT, TICK_MS);
    }

    /** Jumps (your jump key), so the shot goes over the guard's block; the fire and shot follow in the air. */
    private static void jumpForShot(Minecraft minecraft, LocalPlayer player) {
        if (player.onGround() && !pillared) {
            Vec3 eye = player.getEyePosition();
            if (!clearsFrom(minecraft, player, eye.add(0, JUMP_RISE, 0))
                    && clearsFrom(minecraft, player, eye.add(0, 1 + JUMP_RISE, 0)) && pillarFits(minecraft, player)) {
                startPillar(minecraft, player);
                return;
            }
        }
        if (player.onGround()) {
            minecraft.options.keyJump.setDown(true);
            jumpHeldMs = now();
            UraniumLog.log("crossbow cart: jump, the shot goes over the guard");
        }
        // a jump peaks about 6 ticks in: the fire and the shot land around there
        next(Step.FLINT, 2 * TICK_MS);
    }

    // How high the shot goes from in a jump (a jump tops out at 1.25; the shot is near the top).
    private static final double JUMP_RISE = 1.0;
    // Pillar: a block under you mid-jump, for a shot angle a jump alone can't clear.
    private static boolean pillared;
    private static BlockPos pillarCell;
    private static double pillarStartY;
    private static long pillarMs;

    /** Some point of the cart above the shot line can be hit from this eye without a block in the way. */
    private static boolean clearsFrom(Minecraft minecraft, LocalPlayer player, Vec3 eye) {
        AABB box = cartBox(minecraft);
        double line = box.minY + SHOT_LINE + 0.05;
        for (int i = 0; i <= 2; i++) {
            for (int j = 0; j <= 2; j++) {
                for (int k = 0; k <= 2; k++) {
                    Vec3 point = new Vec3(box.minX + 0.15 + (box.getXsize() - 0.3) * i / 2,
                            line + (box.maxY - 0.05 - line) * k / 2, box.minZ + 0.15 + (box.getZsize() - 0.3) * j / 2);
                    if (minecraft.level.clip(new net.minecraft.world.level.ClipContext(eye, point,
                            net.minecraft.world.level.ClipContext.Block.COLLIDER,
                            net.minecraft.world.level.ClipContext.Fluid.NONE, player)).getType() != HitResult.Type.BLOCK) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** A block can go under your feet: one in the hotbar, solid ground under you, room overhead. */
    private static boolean pillarFits(Minecraft minecraft, LocalPlayer player) {
        BlockPos feet = player.blockPosition();
        BlockPos ground = feet.below();
        return AntiCart.blockSlot(player) >= 0
                && minecraft.level.getBlockState(feet).canBeReplaced()
                && minecraft.level.getBlockState(ground).isFaceSturdy(minecraft.level, ground, Direction.UP)
                && !AutoWeb.reactsToUse(minecraft.level.getBlockState(ground).getBlock())
                && minecraft.level.getBlockState(feet.above(2)).isAir()
                && minecraft.level.getBlockState(feet.above(3)).isAir();
    }

    /** Jump, block out, look down under you; the block goes in once you're above it. */
    private static void startPillar(Minecraft minecraft, LocalPlayer player) {
        pillared = true;
        pillarCell = player.blockPosition();
        pillarStartY = player.getY();
        pillarMs = now();
        minecraft.options.keyJump.setDown(true);
        jumpHeldMs = now();
        int slot = AntiCart.blockSlot(player);
        Cart.select(minecraft, player, slot);
        expectedSlot = slot;
        guardSlot = slot;
        Vec3 point = new Vec3(pillarCell.getX() + 0.5, pillarCell.getY(), pillarCell.getZ() + 0.5);
        float[] want = BucketUse.lookAt(predictEye(player, 120), point);
        AIM.stop();
        turnTo(Mth.wrapDegrees(want[0] - player.getYRot()), want[1] - player.getXRot(),
                PlayStyle.turnMs(90 + java.util.concurrent.ThreadLocalRandom.current().nextLong(0, 30)));
        UraniumLog.log("crossbow cart: the shot is too steep from here, a block under you first");
        next(Step.PILLAR, TICK_MS);
    }

    /** Once your feet are above the block's spot, the block goes in under you. */
    private static void placePillar(Minecraft minecraft, LocalPlayer player) {
        if (now() - pillarMs > 900 || player.onGround() && now() - pillarMs > 200) {
            UraniumLog.log("crossbow cart: the block under you didn't fit in time, shooting from here");
            next(Step.FLINT, 0);
            return;
        }
        if (player.getY() < pillarStartY + 1.02 || AIM.active()) {
            return; // still rising (or still turning)
        }
        BlockPos ground = pillarCell.below();
        BlockHitResult top = new BlockHitResult(new Vec3(pillarCell.getX() + 0.5, pillarCell.getY(), pillarCell.getZ() + 0.5),
                Direction.UP, ground, false);
        boolean placed = Cart.use(minecraft, player, guardSlot, top);
        if (!placed && Cart.offTarget && lookAgain(player, new Vec3(pillarCell.getX() + 0.5, pillarCell.getY(),
                pillarCell.getZ() + 0.5), "block under you")) {
            return;
        }
        UraniumLog.log("crossbow cart: block under you at " + pillarCell.toShortString() + (placed ? "" : " refused"));
        expectedSlot = Cart.heldSlot(player);
        next(placed ? Step.PILLAR_LAND : Step.FLINT, TICK_MS);
    }

    // Macro: rails already near the spot when you clicked (a new one is yours).
    private static final java.util.Set<BlockPos> railsBefore = new java.util.HashSet<>();

    // Hidden retry: when to check that the cart blew, and whether it already shot again.
    private static long reshootAtMs;
    private static boolean reshot;
    // Too far: the rail spot was moved next to you once already this cart.
    private static boolean relocated;

    /**
     * After the shot: the cart should be gone. Still sitting on the rail (the arrow missed,
     * went out, or the server didn't count it) and there's something loaded (another
     * crossbow, the offhand one, or the bow): it shoots once more, lighting the fire again
     * first if that's gone too.
     */
    private static void checkReshoot(Minecraft minecraft, LocalPlayer player) {
        if (now() < reshootAtMs) {
            return;
        }
        reshootAtMs = 0;
        if (reshot || spot == null || !Cart.cartAt(minecraft, spot) || Mc.screen(minecraft) != null) {
            return; // it blew (or this was the retry already)
        }
        boolean crossbowReady = loadedCrossbow(player) >= 0 || offhandLoaded(player);
        int bow = bowSlot(player);
        boolean bowReady = bow >= 0 && !player.getProjectile(player.getInventory().getItem(bow)).isEmpty();
        if (!crossbowReady && !bowReady) {
            UraniumLog.log("crossbow cart: the cart didn't blow, nothing loaded to shoot it again");
            SpearDash.setStatus("Cart didn't blow");
            return;
        }
        reshot = true;
        if (pickupSpot != null) {
            pouredLava = pickupSpot; // the lava pickup comes back after the retry
        }
        startMs = now();
        expectedSlot = Cart.heldSlot(player);
        fixed = false;
        aimPoint = null;
        bowMode = !crossbowReady;
        UraniumLog.log("crossbow cart: the cart didn't blow, shooting again" + (bowMode ? " with the bow" : ""));
        SpearDash.setStatus("Shooting again");
        if (!bowMode && (burn == null || burnGone(minecraft))) {
            next(Step.FLINT, 0); // the fire first, then the crossbow
        } else {
            if (!bowMode) {
                aimShot(minecraft, player, TICK_MS + stepDelay(player));
            }
            next(Step.CROSSBOW, 0);
        }
    }

    /**
     * Too far: you've moved out of reach of the cart spot. Instead of stopping, the rail and
     * cart go down about 3 blocks in front of you, the same way the old spot was, and the
     * shot is taken from there. Once per cart; false if no spot fits.
     */
    private static boolean relocate(Minecraft minecraft, LocalPlayer player) {
        if (relocated || Cart.railSlot(player) < 0 || !Cart.hasCart(player)) {
            return false;
        }
        double dx = spot.getX() + 0.5 - player.getX();
        double dz = spot.getZ() + 0.5 - player.getZ();
        double length = Math.hypot(dx, dz);
        if (length < 1.0E-3) {
            return false;
        }
        dx /= length;
        dz /= length;
        for (double distance : new double[] {3.0, 3.5, 2.5, 4.0}) {
            BlockPos base = BlockPos.containing(player.getX() + dx * distance, player.getY(), player.getZ() + dz * distance);
            for (int dy : new int[] {0, -1, 1}) {
                BlockPos cell = base.above(dy);
                BlockPos ground = cell.below();
                if (!Cart.railFits(minecraft, cell) || Cart.hasRail(minecraft, cell)
                        || new AABB(cell).intersects(player.getBoundingBox())
                        || !player.isWithinBlockInteractionRange(ground, 0.0)
                        || AutoWeb.reactsToUse(minecraft.level.getBlockState(ground).getBlock())
                        || BucketUse.aimAtFace(minecraft, player, ground, Direction.UP) == null) {
                    continue;
                }
                relocated = true;
                UraniumLog.log("crossbow cart: too far from " + spot.toShortString() + ", the cart goes next to you at "
                        + cell.toShortString() + " instead");
                spot = cell;
                lastSpot = cell;
                lastSpotMs = now();
                Vec3 top = clearFloorPoint(minecraft, player, cell, cell.getY());
                click = new BlockHitResult(top, Direction.UP, ground, false);
                guardCell = null;
                step = Step.RAIL;
                expectedSlot = Cart.heldSlot(player);
                aimTries = 0;
                lookAgain(player, top, "new rail spot");
                return true;
            }
        }
        return false;
    }

    // Macro: a fire click went out and the fire hasn't shown yet; one retry per cart.
    private static boolean fireMacroPending;
    // Macro: the turn onto the shot waits for the game's click (ms the turn gets, 0 = none).
    private static long aimAfterClickMs;
    private static boolean fireRetried;

    private static boolean fireThere(Minecraft minecraft, BlockPos pos) {
        net.minecraft.world.level.block.state.BlockState state = minecraft.level.getBlockState(pos);
        return state.is(net.minecraft.world.level.block.Blocks.FIRE) || state.is(net.minecraft.world.level.block.Blocks.SOUL_FIRE);
    }

    /** What the game's own crosshair is on, for the log. */
    private static String describe(Minecraft minecraft) {
        HitResult hit = minecraft.hitResult;
        if (hit instanceof net.minecraft.world.phys.EntityHitResult entity) {
            return "the entity " + entity.getEntity().getName().getString();
        }
        if (hit instanceof BlockHitResult block && block.getType() == HitResult.Type.BLOCK) {
            return block.getBlockPos().toShortString() + " (" + minecraft.level.getBlockState(block.getBlockPos()).getBlock()
                    .getName().getString() + ", " + block.getDirection().getName() + " face)";
        }
        return "nothing";
    }

    /** What the ray from your view right now hits (blocks only). */
    private static String describeRay(Minecraft minecraft, LocalPlayer player) {
        BlockHitResult hit = BucketUse.ray(minecraft, player, player.getYRot(), player.getXRot(),
                net.minecraft.world.level.ClipContext.Fluid.NONE);
        return hit.getType() == HitResult.Type.BLOCK
                ? hit.getBlockPos().toShortString() + " " + hit.getDirection().getName() + " face" : "nothing";
    }

    /** The fire or lava the arrow had to fly through isn't there any more. */
    private static boolean burnGone(Minecraft minecraft) {
        BlockPos at = BlockPos.containing(burn.getCenter());
        net.minecraft.world.level.block.state.BlockState state = minecraft.level.getBlockState(at);
        boolean fire = state.is(net.minecraft.world.level.block.Blocks.FIRE)
                || state.is(net.minecraft.world.level.block.Blocks.SOUL_FIRE);
        boolean lava = state.getFluidState().is(net.minecraft.tags.FluidTags.LAVA);
        // just placed: your screen may not show it yet
        return !fire && !lava && now() - burnSetMs > AutoStun.pingMs(minecraft) + 150;
    }

    /** The TNT cart's hitbox on the rail; where it will be if it isn't seen yet. */
    private static AABB cartBox(Minecraft minecraft) {
        for (MinecartTNT cart : minecraft.level.getEntitiesOfClass(MinecartTNT.class, new AABB(spot).inflate(1.0))) {
            return cart.getBoundingBox();
        }
        Vec3 bottom = Vec3.atBottomCenterOf(spot).add(0, 0.0625, 0);
        return new AABB(bottom.x - 0.49, bottom.y, bottom.z - 0.49, bottom.x + 0.49, bottom.y + 0.7, bottom.z + 0.49);
    }

    /** Fires the loaded crossbow where you look, which is now the cart, through the fire. */
    private static void fire(Minecraft minecraft, LocalPlayer player, String where) {
        if (Cart.isMacro() && offhandShot && usesFirst(player.getMainHandItem())) {
            int quiet = quietSlot(player);
            if (quiet >= 0) {
                // your right-click would use the main hand's item before the offhand crossbow
                Cart.select(minecraft, player, quiet);
                expectedSlot = quiet;
                correctAt = now() + TICK_MS;
                UraniumLog.log("crossbow cart: macro, hotbar " + (quiet + 1) + " first so the right-click fires the offhand crossbow");
                return;
            }
        }
        step = null;
        if (pouredLava != null) {
            // after the blast, the bucket goes back for the lava
            pickupSpot = pouredLava;
            pickupShotMs = now();
            pickupStage = 0;
            pickupTries = 0;
            pickupNextMs = 0;
            pouredLava = null;
        }
        InteractionHand hand = offhandShot ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        ItemStack crossbow = player.getItemInHand(hand);
        if (!crossbow.is(Items.CROSSBOW) || !CrossbowItem.isCharged(crossbow)) {
            SpearDash.setStatus("Crossbow not loaded");
            UraniumLog.log("crossbow cart: no shot, crossbow not loaded");
            return;
        }
        Debug.click("crossbow shot");
        if (Cart.isMacro()) {
            Cart.pressUse(minecraft, player); // the game fires it (main hand first, then offhand)
        } else {
            minecraft.gameMode.useItem(player, hand);
        }
        lastShotMs = now();
        measureUntilMs = now() + 1500;
        reshootAtMs = now() + AutoStun.pingMs(minecraft) + 450;
        measureStartHealth = player.getHealth() + player.getAbsorptionAmount();
        measureLowest = measureStartHealth;
        measureExpect = SelfBlast.damage(minecraft, player, SelfBlast.cartCenter(spot), SelfBlast.CROSSBOW_SPEED);
        measureDistance = Math.sqrt(player.distanceToSqr(SelfBlast.cartCenter(spot)));
        if (pickupSpot != null) {
            pickupHeldSlot = Cart.heldSlot(player); // switching off this while it waits cancels the pickup
        }
        if (offhandShot) {
            UraniumLog.log("crossbow cart: the crossbow fired from your offhand");
        }
        SpearDash.setStatus("Cart shot");
        PlayStyle.bump(0.3);
        UraniumLog.log(String.format(java.util.Locale.ROOT,
                "crossbow cart: shot the crossbow %d ms after the click, %s",
                now() - startMs, where));
    }

    /** A right-click with this in the main hand does something before the offhand gets a turn. */
    private static boolean usesFirst(ItemStack stack) {
        return stack.is(Items.FLINT_AND_STEEL) || stack.is(Items.LAVA_BUCKET) || stack.is(Items.BUCKET)
                || stack.is(Items.WATER_BUCKET) || stack.is(Items.TNT_MINECART) || Cart.isRail(stack)
                || stack.getItem() instanceof net.minecraft.world.item.BlockItem || stack.is(Items.CROSSBOW)
                || stack.is(Items.BOW) || stack.has(net.minecraft.core.component.DataComponents.FOOD);
    }

    /** A hotbar slot a right-click does nothing with (empty, or a sword/axe/pick); -1 if none. */
    private static int quietSlot(LocalPlayer player) {
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.isEmpty() || !usesFirst(stack) && !stack.is(Items.ENDER_PEARL) && !stack.is(Items.WIND_CHARGE)
                    && !stack.is(Items.TOTEM_OF_UNDYING) && !stack.is(Items.SHIELD) && !stack.is(Items.TRIDENT)) {
                return slot;
            }
        }
        return -1;
    }

    /** The first hotbar crossbow that is loaded; -1 if none. */
    private static int loadedCrossbow(LocalPlayer player) {
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(Items.CROSSBOW) && CrossbowItem.isCharged(stack)) {
                return slot;
            }
        }
        return -1;
    }

    /** A loaded crossbow (hotbar or offhand) holding a firework: its blast sets a TNT cart off unlit. */
    private static boolean fireworkLoaded(LocalPlayer player) {
        java.util.List<ItemStack> stacks = new java.util.ArrayList<>();
        stacks.add(player.getOffhandItem());
        for (int slot = 0; slot < 9; slot++) {
            stacks.add(player.getInventory().getItem(slot));
        }
        for (ItemStack stack : stacks) {
            net.minecraft.world.item.component.ChargedProjectiles charged =
                    stack.get(net.minecraft.core.component.DataComponents.CHARGED_PROJECTILES);
            if (stack.is(Items.CROSSBOW) && charged != null && charged.contains(Items.FIREWORK_ROCKET)) {
                return true;
            }
        }
        return false;
    }

    private static boolean offhandLoaded(LocalPlayer player) {
        ItemStack stack = player.getOffhandItem();
        return stack.is(Items.CROSSBOW) && CrossbowItem.isCharged(stack);
    }

    /** True for a short while after Crossbow Cart fired (Bow Cart leaves that arrow alone). */
    static boolean shotRecently() {
        return now() - lastShotMs < 600;
    }

    private static final int UNLOADED_COVER = 0xA0E01818;

    /**
     * Called by the HUD every frame while Crossbow Cart is on: a big red cover over every
     * crossbow in your hotbar (and offhand) that isn't loaded, so you see it before you
     * need the shot.
     */
    static void drawUnloaded(net.minecraft.client.gui.GuiGraphicsExtractor graphics, Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (!enabled || player == null || player.isSpectator()
                || ArmorHud.isEnabled() && ArmorHud.isAlerts()) {
            // with Armor HUD' alerts on, the !!! over the crossbow says it instead
            return;
        }
        int left = graphics.guiWidth() / 2 - 91;
        int top = graphics.guiHeight() - 22;
        for (int slot = 0; slot < 9; slot++) {
            if (unloaded(player.getInventory().getItem(slot))) {
                int x = left + 1 + slot * 20;
                graphics.fill(x, top + 1, x + 20, top + 21, UNLOADED_COVER);
            }
        }
        if (unloaded(player.getOffhandItem())) {
            boolean rightHanded = player.getMainArm() == net.minecraft.world.entity.HumanoidArm.RIGHT;
            int x = rightHanded ? left - 29 : left + 182 + 7;
            graphics.fill(x + 1, top, x + 22, top + 22, UNLOADED_COVER);
        }
    }

    private static boolean unloaded(ItemStack stack) {
        return stack.is(Items.CROSSBOW) && !CrossbowItem.isCharged(stack);
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

    static boolean isShoot() {
        return shoot;
    }

    static void setShoot(boolean on) {
        shoot = on;
    }

    static void toggleShoot() {
        shoot = !shoot;
    }

    static boolean isOverload() {
        return overload;
    }

    static void setOverload(boolean on) {
        overload = on;
    }

    static void toggleOverload() {
        overload = !overload;
    }

    static String lookLabel() {
        return smoothLook ? "Smooth" : "Stutter";
    }

    static boolean isSmoothLook() {
        return smoothLook;
    }

    static void setSmoothLook(boolean on) {
        smoothLook = on;
    }

    static void cycleLook() {
        smoothLook = !smoothLook;
    }

    static boolean isHardSwitch() {
        return hardSwitch;
    }

    static void setHardSwitch(boolean on) {
        hardSwitch = on;
    }

    static void toggleHardSwitch() {
        hardSwitch = !hardSwitch;
    }

    static boolean isAutoReload() {
        return autoReload;
    }

    static void setAutoReload(boolean on) {
        autoReload = on;
    }

    static void toggleAutoReload() {
        autoReload = !autoReload;
    }

    static boolean isReplenish() {
        return replenish;
    }

    static void setReplenish(boolean on) {
        replenish = on;
    }

    static void toggleReplenish() {
        replenish = !replenish;
    }

    /** True while a cart is going down (Replenish waits for it). */
    static boolean isBusy() {
        return step != null;
    }

    /** Getting the lava back after a blast; Replenish waits so it can't swap the bucket out. */
    static boolean isPickingUp() {
        return pickupSpot != null;
    }

    static void reset() {
        enabled = false;
        step = null;
        AIM.stop();
    }
}
