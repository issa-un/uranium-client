package app;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Bow Cart: when you fire your bow, it works out where the arrow will come down,
 * then puts a rail and a TNT minecart there before the arrow lands, so a flame arrow sets
 * it off. You can only place within your normal reach, so it works for arrows that come
 * down near you: shoot up high and it has the second or two it needs.
 *
 * It moves like a hand: your camera turns to the spot in one human flick (HumanAim, with
 * a small correction if it lands short), and the rail goes down only once your real
 * crosshair is on that block, with the game's own click. The cart follows after Delay,
 * the same way, then your hotbar goes back to the bow. The camera is never snapped and
 * never turned back: after the cart it is yours again.
 *
 * One cart goes down per shot (insta carting). Crossbow too also does it for crossbow
 * shots (crossbow carting), not only the bow.
 */
final class BowCart {
    private enum Step { AIM_RAIL, WAIT_CART, AIM_CART, BOW }

    private static final int MAX_FLIGHT_TICKS = 200;
    // The flick, the rail, the cart delay and a tick to spare before the arrow is there.
    private static final int MIN_TICKS_LEFT = 6;
    // A shot that flies this many ticks or more gets the normal pace; a flat one less.
    private static final double FULL_PACE_TICKS = 20;
    static final Num CHANCE = Num.percent(100);
    // Lowest draw: a bow shoots from 3 ticks drawn (under that the shot is too weak and
    // nothing comes out); one more so the server counts it too.
    private static final int LOWEST_DRAW_TICKS = 4;
    private static volatile boolean lowestDraw;
    // After a lowest-draw shot: right-click doesn't count until you let go of it.
    private static boolean waitForRelease;
    static final Num DELAY = Num.ms(50, 150, 500);
    // A pause before a correcting flick, like a hand that sees it landed short.
    private static final long CORRECT_PAUSE_MS = 60;
    private static volatile boolean enabled;
    private static volatile boolean replenish;
    private static volatile boolean autoReload;
    private static volatile boolean crossbowToo;
    // Render thread only.
    private static final Set<Integer> SEEN = new HashSet<>();
    private static final HumanAim AIM = new HumanAim();
    private static Step step;
    private static BlockPos ground;
    private static BlockPos spot;
    private static Vec3 aimPoint;
    private static int bowSlot = -1;
    private static int nextTick;
    private static long nextMs;
    private static long cartPlacedMs;
    private static int cartRetries;
    private static int retryAtTick;
    private static int cartsDown;
    private static int landTick;
    private static long flickDoneMs;
    // The arrow this cart is for, to follow its path and see afterwards if it hit.
    private static int arrowId = -1;
    // Flat (low angle) shots land soon: the flicks and the wait are cut to this share.
    private static double pace = 1;
    // The arrow's flight passes fire or lava (so a crossbow arrow catches fire on the way).
    private static boolean pathBurns;
    // How fast the arrow is going when it comes down (the cart blows harder the faster).
    private static double landingSpeed;
    // A few ticks after the arrow should land: did the cart blow? (0: nothing to check)
    private static int checkTick;
    private static BlockPos checkSpot;

    private BowCart() {}

    /** Called from the input loop each tick: watches for your new arrows, places when the crosshair is there. */
    static void update(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (!enabled || player == null || minecraft.gameMode == null || minecraft.level == null) {
            stop();
            return;
        }
        lowestDraw(minecraft, player);
        if (step != null) {
            if (Cart.macroBusy(player)) {
                return; // the game hasn't acted on the last macro press yet
            }
            run(minecraft, player);
            return;
        }
        if (checkTick > 0 && player.tickCount >= checkTick) {
            checkTick = 0;
            reportOutcome(minecraft);
        }
        if (Mc.screen(minecraft) != null || !isShooter(player.getMainHandItem()) && !isShooter(player.getOffhandItem())) {
            return;
        }
        for (AbstractArrow arrow : minecraft.level.getEntitiesOfClass(AbstractArrow.class,
                player.getBoundingBox().inflate(4.0))) {
            if (arrow.tickCount > 3 || !SEEN.add(arrow.getId()) || !isMine(player, arrow)) {
                continue;
            }
            if (SEEN.size() > 64) {
                SEEN.clear();
                SEEN.add(arrow.getId());
            }
            onShot(minecraft, player, arrow);
            return;
        }
    }

    /**
     * Lowest draw: lets go of your bow the moment it is drawn enough to shoot at all, so the
     * arrow is slow and comes down close. One shot a press: holding right-click doesn't
     * draw again until you let go.
     */
    private static void lowestDraw(Minecraft minecraft, LocalPlayer player) {
        if (waitForRelease) {
            if (WTap.physicallyDown(minecraft, minecraft.options.keyUse)) {
                minecraft.options.keyUse.setDown(false);
                return;
            }
            waitForRelease = false;
        }
        if (!lowestDraw || !player.isUsingItem() || !player.getUseItem().is(Items.BOW)
                || player.getTicksUsingItem() < LOWEST_DRAW_TICKS || Mc.screen(minecraft) != null) {
            return;
        }
        minecraft.gameMode.releaseUsingItem(player);
        minecraft.options.keyUse.setDown(false);
        waitForRelease = true;
        UraniumLog.log("bow cart: bow let go at the lowest draw (" + LOWEST_DRAW_TICKS + " ticks)");
    }

    /** Called every frame by the HUD: one smooth step of the camera toward the spot. */
    static void frame(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (step != Step.AIM_RAIL && step != Step.AIM_CART || player == null || minecraft.level == null) {
            return;
        }
        if (AIM.active()) {
            if (AIM.step(player)) {
                flickDoneMs = System.nanoTime() / 1_000_000;
            }
            return;
        }
        if (onTarget(minecraft) || System.nanoTime() / 1_000_000 - flickDoneMs < CORRECT_PAUSE_MS) {
            return;
        }
        flickTo(player, aimPoint);
    }

    private static void run(Minecraft minecraft, LocalPlayer player) {
        if (Mc.screen(minecraft) != null) {
            stop();
            SpearDash.setStatus("Cart stopped");
            return;
        }
        switch (step) {
            case AIM_RAIL -> {
                follow(minecraft, player);
                if (step != Step.AIM_RAIL) {
                    return; // a rail was already at the new spot: straight to the cart
                }
                if (player.tickCount > landTick - 4) {
                    giveUp(minecraft, player, "Too slow for the arrow");
                } else if (onTarget(minecraft)) {
                    placeRail(minecraft, player);
                }
            }
            case WAIT_CART -> {
                if (System.nanoTime() / 1_000_000 >= nextMs) {
                    step = Step.AIM_CART;
                    aimPoint = Vec3.atBottomCenterOf(spot).add(0, 0.06, 0);
                }
            }
            case AIM_CART -> {
                boolean railComing = Cart.isMacro() && !Cart.hasRail(minecraft, spot)
                        && System.nanoTime() / 1_000_000 - railSentMs < 200;
                if (railComing && player.tickCount < landTick - 1) {
                    return; // Macro: the game hasn't put the rail down yet
                }
                if (player.tickCount >= landTick - 1 || !Cart.hasRail(minecraft, spot)) {
                    if (cartsDown > 0) {
                        finish(minecraft, player); // the stack is as tall as the arrow allows
                    } else {
                        giveUp(minecraft, player, Cart.hasRail(minecraft, spot) ? "Cart too late" : "The rail didn't go down");
                    }
                } else if (cartsDown > 0 || onTarget(minecraft)) {
                    placeCart(minecraft, player);
                }
            }
            case BOW -> {
                if (player.tickCount >= nextTick && !waitingForCart(minecraft, player)) {
                    backToBow(minecraft, player);
                    step = null;
                }
            }
        }
    }

    /**
     * Is the cart really there? The server sometimes refuses it; then it is placed once
     * more (slot forced) while the arrow is still up. True while it waits for the server.
     */
    private static boolean waitingForCart(Minecraft minecraft, LocalPlayer player) {
        if (cartsDown == 0 || Cart.cartAt(minecraft, spot) || player.tickCount >= landTick - 1) {
            return false;
        }
        if (System.nanoTime() / 1_000_000 - cartPlacedMs < Cart.cartWaitMs(minecraft)) {
            return true;
        }
        if (retryAtTick == 0 && (cartRetries >= 1 || !Cart.hasCart(player) || !Cart.hasRail(minecraft, spot))) {
            UraniumLog.log("bow cart: the server blocked the cart" + (cartRetries >= 1 ? " twice" : ""));
            SpearDash.setStatus("Server blocked the cart");
            return false;
        }
        if (retryAtTick > 0) {
            if (player.tickCount >= retryAtTick) {
                retryAtTick = 0;
                boolean[] took = new boolean[1];
                Cart.placeCart(minecraft, player, Cart.onRail(spot), took);
                cartPlacedMs = System.nanoTime() / 1_000_000;
            }
            return true;
        }
        cartRetries++;
        UraniumLog.log("bow cart: the server blocked the cart, placing it again");
        // A tick on another slot first, so the cart slot after it is a real change.
        Cart.hop(minecraft, player, BucketUse.findSlot(player, Items.TNT_MINECART));
        retryAtTick = player.tickCount + 1;
        return true;
    }

    private static boolean isMine(LocalPlayer player, AbstractArrow arrow) {
        if (arrow.getOwner() != null) {
            return arrow.getOwner() == player;
        }
        return arrow.distanceTo(player) < 2.5;
    }

    private static void onShot(Minecraft minecraft, LocalPlayer player, AbstractArrow arrow) {
        if (!Settings.combatAllowed(minecraft)) {
            return;
        }
        if (CrossbowCart.shotRecently()) {
            return; // Crossbow Cart's own shot at its own cart
        }
        int[] ticks = new int[1];
        List<Vec3> path = new ArrayList<>();
        BlockHitResult landing = landing(minecraft, arrow, ticks, path);
        if (landing == null) {
            return;
        }
        // Only a burning arrow sets a TNT cart off; a cart under any other arrow is wasted.
        // The shooter is in either hand (an offhand crossbow counts).
        ItemStack shooter = isShooter(player.getMainHandItem()) ? player.getMainHandItem() : player.getOffhandItem();
        boolean flame = shooter.is(Items.BOW) && MaceSwap.enchantmentLevel(shooter, Enchantments.FLAME) > 0;
        if (!arrow.isOnFire() && !flame && !pathBurns) {
            SpearDash.setStatus(shooter.is(Items.BOW) ? "Bow has no Flame" : "Arrow isn't burning");
            UraniumLog.log("bow cart: no cart, the arrow isn't burning ("
                    + (shooter.is(Items.BOW) ? "the bow has no Flame"
                    : "a crossbow arrow only burns if it flies through fire or lava, and this one doesn't") + ")");
            return;
        }
        for (Vec3 point : path) {
            if (minecraft.level.isRainingAt(BlockPos.containing(point))) {
                // a burning arrow goes out in the rain before it comes down
                SpearDash.setStatus("Rain puts the arrow out");
                UraniumLog.log("bow cart: no cart, it's raining where the arrow comes down (it goes out)");
                return;
            }
        }
        if (!CHANCE.roll()) {
            UraniumLog.log("bow cart: skipped this shot (Chance)");
            return;
        }
        if (landing.getDirection() != Direction.UP) {
            SpearDash.setStatus("Arrow lands on a wall");
            return;
        }
        net.minecraft.world.entity.player.Player first = hitsPlayerFirst(minecraft, player, path);
        if (first != null) {
            SpearDash.setStatus("Arrow hits " + first.getName().getString() + " first");
            UraniumLog.log("bow cart: no cart, the arrow hits " + first.getName().getString() + " before it lands");
            return;
        }
        if (ticks[0] < MIN_TICKS_LEFT) {
            SpearDash.setStatus("Arrow lands too soon");
            return;
        }
        if (!player.isWithinBlockInteractionRange(landing.getBlockPos(), 1.0)) {
            SpearDash.setStatus("Arrow lands out of reach");
            UraniumLog.log("bow cart: arrow lands at " + landing.getBlockPos().above().toShortString() + ", out of reach");
            return;
        }
        BlockPos target = bestSpot(minecraft, player, path, landing.getBlockPos());
        if (target == null) {
            SpearDash.setStatus("No room for a rail there");
            UraniumLog.log("bow cart: arrow lands at " + landing.getBlockPos().above().toShortString()
                    + ", no spot under its path takes a rail you can reach and see");
            return;
        }
        String risk = SelfBlast.danger(minecraft, player, SelfBlast.cartCenter(target), landingSpeed);
        if (risk != null) {
            SpearDash.setStatus("Too close for your health");
            UraniumLog.log("bow cart: no cart at " + target.toShortString() + ", " + risk);
            return;
        }
        BlockPos hitGround = target.below();
        boolean railThere = Cart.hasRail(minecraft, target);
        int rail = Cart.railSlot(player);
        if (rail < 0 && !railThere || !Cart.hasCart(player)) {
            UraniumLog.log("bow cart: arrow lands at " + target.toShortString() + ", "
                    + (Cart.hasCart(player) ? "no rail in hotbar" : "no TNT minecart"));
            CartRefill.requestNow();
            SpearDash.setStatus(rail < 0 ? "No rail in hotbar" : "No TNT minecart");
            return;
        }
        // With a rail already there the crosshair lands on the rail, not the ground under it.
        float[] aim = railThere ? new float[0] : BucketUse.aimAtFace(minecraft, player, hitGround, Direction.UP);
        if (aim == null) {
            SpearDash.setStatus("Can't see where it lands");
            UraniumLog.log("bow cart: arrow lands at " + target.toShortString() + ", can't see the spot");
            return;
        }
        ground = hitGround;
        spot = target;
        // A spot on the top face a little off the middle, different every time.
        ThreadLocalRandom random = ThreadLocalRandom.current();
        aimPoint = new Vec3(hitGround.getX() + 0.5 + random.nextDouble(-0.2, 0.2), hitGround.getY() + 1.0,
                hitGround.getZ() + 0.5 + random.nextDouble(-0.2, 0.2));
        bowSlot = Cart.heldSlot(player);
        cartsDown = 0;
        cartRetries = 0;
        retryAtTick = 0;
        landTick = player.tickCount + ticks[0];
        arrowId = arrow.getId();
        // A flat shot is in the air for less time: everything goes faster to fit.
        pace = Math.max(0.35, Math.min(1, ticks[0] / FULL_PACE_TICKS));
        checkTick = 0;
        step = Step.AIM_RAIL;
        if (railThere) {
            step = Step.AIM_CART; // a rail is already there: straight to the cart
            aimPoint = Vec3.atBottomCenterOf(spot).add(0, 0.06, 0);
        }
        AIM.stop();
        flickDoneMs = 0;
        UraniumLog.log("bow cart: arrow lands at " + landing.getBlockPos().above().toShortString() + " in " + ticks[0]
                + " ticks, cart at " + target.toShortString() + (railThere ? ", a rail is already there" : ""));
    }

    /** Starts a human flick from where you look now to the point. */
    private static void flickTo(LocalPlayer player, Vec3 point) {
        Vec3 eye = player.getEyePosition();
        float[] want = BucketUse.lookAt(eye, point);
        float yaw = Mth.wrapDegrees(want[0] - player.getYRot());
        float pitch = want[1] - player.getXRot();
        float width = (float) Math.toDegrees(2 * Math.atan(0.35 / Math.max(0.5, eye.distanceTo(point))));
        double distance = Math.hypot(yaw, pitch);
        long ms = Math.round(PlayStyle.turnMs(Math.round(90 + 1.5 * distance)) * pace);
        switch (look) {
            case 1 -> AIM.glide(yaw, pitch, ms); // Smooth: one even turn, ends on the spot
            case 2 -> AIM.stutter(yaw, pitch, ms); // Stutter: quick jumps, ends on the spot
            default -> {
                AIM.flick(yaw, pitch, width, true); // Flick: a hand's flick, then a correction
                AIM.hurry(pace);
            }
        }
    }

    private static final String[] LOOKS = {"Flick", "Smooth", "Stutter"};
    // Look: how the view turns onto the rail spot (see flickTo).
    private static volatile int look;

    static String lookLabel() {
        return LOOKS[look];
    }

    static int look() {
        return look;
    }

    static void setLook(int value) {
        look = Math.floorMod(value, LOOKS.length);
    }

    static void cycleLook() {
        look = (look + 1) % LOOKS.length;
    }

    /** Whether your real crosshair is on the block the next click needs. */
    private static boolean onTarget(Minecraft minecraft) {
        // The game's ray from your view right now (what the server will check), not the
        // crosshair the game worked out at the start of the frame.
        return step == Step.AIM_RAIL ? Cart.realClick(minecraft, minecraft.player, ground, Direction.UP) != null
                : Cart.realClick(minecraft, minecraft.player, spot, null) != null;
    }

    private static void placeRail(Minecraft minecraft, LocalPlayer player) {
        int rail = Cart.railSlot(player);
        if (rail < 0 || !Cart.railFits(minecraft, spot)) {
            giveUp(minecraft, player, rail < 0 ? "No rail in hotbar" : "No room for a rail there");
            return;
        }
        boolean placed = Cart.use(minecraft, player, rail, new BlockHitResult(Vec3.atCenterOf(ground), Direction.UP, ground, false));
        UraniumLog.log("bow cart: rail " + (placed ? "down" : "refused") + ", "
                + (landTick - player.tickCount) + " ticks before the arrow");
        if (!placed) {
            giveUp(minecraft, player, "Rail refused");
            return;
        }
        AIM.stop();
        step = Step.WAIT_CART;
        railSentMs = System.nanoTime() / 1_000_000;
        nextMs = railSentMs + PlayStyle.delay(Math.round(DELAY.pick() * pace));
    }

    // When the rail click went out; in Macro mode the game puts it down a tick or two later.
    private static long railSentMs;

    private static void placeCart(Minecraft minecraft, LocalPlayer player) {
        if (!Cart.hasCart(player)) {
            giveUp(minecraft, player, "No TNT minecart");
            return;
        }
        // After the first cart your crosshair is on that cart, not the rail; the click is
        // still on the rail block you are looking at.
        String risk = SelfBlast.danger(minecraft, player, SelfBlast.cartCenter(spot), landingSpeed);
        if (risk != null) {
            giveUp(minecraft, player, "Too close for your health");
            UraniumLog.log("bow cart: no cart, you're too close now, " + risk);
            return;
        }
        BlockHitResult click = Cart.onRail(spot); // sent as what your view really hits on the rail
        boolean[] took = new boolean[1];
        Cart.placeCart(minecraft, player, click, took);
        boolean placed = took[0];
        if (placed) {
            cartsDown++;
            cartPlacedMs = System.nanoTime() / 1_000_000;
            CartRefill.watch(spot);
        }
        UraniumLog.log("bow cart: cart " + (cartsDown + (placed ? 0 : 1)) + " " + (placed ? "on the rail" : "refused")
                + ", " + (landTick - player.tickCount) + " ticks before the arrow");
        AIM.stop();
        finish(minecraft, player);
    }

    private static void finish(Minecraft minecraft, LocalPlayer player) {
        SpearDash.setStatus(cartsDown == 0 ? "Cart refused"
                : cartsDown == 1 ? "Cart down" : cartsDown + " carts stacked");
        step = Step.BOW;
        nextTick = player.tickCount + 1;
        if (cartsDown > 0) {
            checkTick = landTick + 5;
            checkSpot = spot;
        }
    }

    /**
     * A few ticks after the arrow should have landed: did the cart blow? If not, the log
     * says where the arrow ended up and whether it was still burning, so a miss can be
     * told apart from a dud.
     */
    private static void reportOutcome(Minecraft minecraft) {
        if (minecraft.level == null || checkSpot == null) {
            return;
        }
        if (!Cart.cartAt(minecraft, checkSpot)) {
            UraniumLog.log("bow cart: the cart blew");
            return;
        }
        Entity arrow = minecraft.level.getEntity(arrowId);
        String where;
        if (arrow == null) {
            where = "the arrow is gone (it hit something else, or it is out of sight)";
        } else {
            Vec3 middle = Vec3.atBottomCenterOf(checkSpot).add(0, 0.35, 0);
            where = String.format(java.util.Locale.ROOT, "the arrow stopped at %.1f, %.1f, %.1f, %.1f blocks from the cart's middle, %s",
                    arrow.getX(), arrow.getY(), arrow.getZ(), arrow.position().distanceTo(middle),
                    arrow.isOnFire() ? "burning" : "not burning");
        }
        UraniumLog.log("bow cart: the cart didn't blow, " + where);
        SpearDash.setStatus("Arrow missed the cart");
    }

    /**
     * Until the rail goes down: works the arrow's landing out again from where it really
     * is now, and moves the spot if it changed (the server can nudge the arrow).
     */
    private static void follow(Minecraft minecraft, LocalPlayer player) {
        if (!(minecraft.level.getEntity(arrowId) instanceof AbstractArrow arrow)
                || arrow.getDeltaMovement().lengthSqr() < 1.0E-4) {
            return;
        }
        int[] ticks = new int[1];
        List<Vec3> path = new ArrayList<>();
        BlockHitResult landing = landing(minecraft, arrow, ticks, path);
        if (landing == null || landing.getDirection() != Direction.UP) {
            return;
        }
        landTick = player.tickCount + ticks[0];
        BlockPos cell = bestSpot(minecraft, player, path, landing.getBlockPos());
        if (cell == null || cell.equals(spot)) {
            return;
        }
        UraniumLog.log("bow cart: the arrow's path changed, the cart spot moves from "
                + spot.toShortString() + " to " + cell.toShortString());
        spot = cell;
        ground = cell.below();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        aimPoint = new Vec3(ground.getX() + 0.5 + random.nextDouble(-0.2, 0.2), ground.getY() + 1.0,
                ground.getZ() + 0.5 + random.nextDouble(-0.2, 0.2));
        AIM.stop();
        flickDoneMs = 0;
        if (Cart.hasRail(minecraft, cell)) {
            step = Step.AIM_CART;
            aimPoint = Vec3.atBottomCenterOf(spot).add(0, 0.06, 0);
        }
    }

    /**
     * Where the cart goes: of the block the arrow lands in and the 8 around it, the one whose
     * cart the arrow's path runs through the longest (an arrow coming down at an angle near
     * a block edge can pass mostly over the next block, and a cart it only grazes may be
     * missed). It must take a rail (or have one), and its ground must be in reach and in
     * sight. Null if none.
     */
    private static BlockPos bestSpot(Minecraft minecraft, LocalPlayer player, List<Vec3> path, BlockPos landingGround) {
        BlockPos landing = landingGround.above();
        BlockPos best = null;
        double bestLength = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos cell = landing.offset(dx, 0, dz);
                BlockPos under = cell.below();
                boolean rail = Cart.hasRail(minecraft, cell);
                if (!rail && (!Cart.railFits(minecraft, cell)
                        || AutoWeb.reactsToUse(minecraft.level.getBlockState(under).getBlock())
                        || BucketUse.aimAtFace(minecraft, player, under, Direction.UP) == null)) {
                    continue;
                }
                if (!player.isWithinBlockInteractionRange(under, 0.0)) {
                    continue;
                }
                AABB cart = new AABB(cell.getX() + 0.01, cell.getY(), cell.getZ() + 0.01,
                        cell.getX() + 0.99, cell.getY() + 0.7, cell.getZ() + 0.99);
                double length = 0;
                for (int i = 1; i < path.size(); i++) {
                    length += Cart.overlap(path.get(i - 1), path.get(i), cart);
                }
                if (dx == 0 && dz == 0) {
                    length += 0.05; // a tie goes to where it lands
                }
                if (length > bestLength) {
                    bestLength = length;
                    best = cell;
                }
            }
        }
        return best;
    }

    private static void giveUp(Minecraft minecraft, LocalPlayer player, String why) {
        SpearDash.setStatus(why);
        UraniumLog.log("bow cart: stopped, " + why.toLowerCase());
        if (Cart.heldSlot(player) != bowSlot) {
            backToBow(minecraft, player);
        }
        stop();
    }

    private static void backToBow(Minecraft minecraft, LocalPlayer player) {
        if (bowSlot >= 0 && isShooter(player.getInventory().getItem(bowSlot))) {
            Cart.select(minecraft, player, bowSlot);
        }
    }

    private static void stop() {
        step = null;
        AIM.stop();
    }

    /**
     * Flies the arrow forward the way the game does (move, then air drag, then gravity) and
     * returns the block it hits, with the ticks it takes in ticks[0]; null if it doesn't land.
     */
    private static BlockHitResult landing(Minecraft minecraft, AbstractArrow arrow, int[] ticks, List<Vec3> path) {
        Vec3 pos = arrow.position();
        Vec3 velocity = arrow.getDeltaMovement();
        path.add(pos);
        pathBurns = false;
        for (int tick = 1; tick <= MAX_FLIGHT_TICKS; tick++) {
            Vec3 next = pos.add(velocity);
            BlockHitResult hit = minecraft.level.clip(new ClipContext(pos, next, ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE, arrow));
            // an arrow flies through fluids; fire or lava on the way sets it burning
            Vec3 stop = hit.getType() == HitResult.Type.BLOCK ? hit.getLocation() : next;
            for (int part = 1; part <= 4 && !pathBurns; part++) {
                BlockPos at = BlockPos.containing(pos.add(stop.subtract(pos).scale(part / 4.0)));
                net.minecraft.world.level.block.state.BlockState state = minecraft.level.getBlockState(at);
                pathBurns = state.is(net.minecraft.world.level.block.Blocks.FIRE)
                        || state.is(net.minecraft.world.level.block.Blocks.SOUL_FIRE)
                        || state.getFluidState().is(net.minecraft.tags.FluidTags.LAVA);
            }
            if (hit.getType() == HitResult.Type.BLOCK) {
                ticks[0] = tick;
                landingSpeed = velocity.length();
                path.add(hit.getLocation());
                // only the last stretch matters for which block's cart it passes
                if (path.size() > 6) {
                    path.subList(0, path.size() - 6).clear();
                }
                return hit;
            }
            path.add(next);
            velocity = velocity.scale(0.99).add(0, -0.05, 0);
            pos = next;
            if (pos.y < minecraft.level.getMinY()) {
                return null;
            }
        }
        return null;
    }

    /** Another player the arrow's last stretch passes through before it lands (they'd take it). */
    private static net.minecraft.world.entity.player.Player hitsPlayerFirst(Minecraft minecraft, LocalPlayer player,
            List<Vec3> path) {
        for (int i = 1; i < path.size(); i++) {
            Vec3 a = path.get(i - 1);
            Vec3 b = path.get(i);
            for (net.minecraft.world.entity.player.Player other : minecraft.level.players()) {
                if (other == player || other.isSpectator() || !other.isAlive()) {
                    continue;
                }
                if (other.getBoundingBox().inflate(0.3).clip(a, b).isPresent()) {
                    return other;
                }
            }
        }
        return null;
    }

    private static boolean isShooter(ItemStack stack) {
        return stack.is(Items.BOW) || crossbowToo && stack.is(Items.CROSSBOW);
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

    static boolean isLowestDraw() {
        return lowestDraw;
    }

    static void setLowestDraw(boolean on) {
        lowestDraw = on;
    }

    static void toggleLowestDraw() {
        lowestDraw = !lowestDraw;
    }

    static boolean isCrossbowToo() {
        return crossbowToo;
    }

    static void setCrossbowToo(boolean on) {
        crossbowToo = on;
    }

    static void toggleCrossbowToo() {
        crossbowToo = !crossbowToo;
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

    static void reset() {
        enabled = false;
        stop();
        SEEN.clear();
        checkTick = 0;
    }
}
