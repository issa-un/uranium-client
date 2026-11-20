package app;

import java.lang.reflect.Field;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.vehicle.minecart.MinecartTNT;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

/**
 * Replenish, for the Cart modules: once the cart you put down has blown (it is gone from its
 * rail), if your rails, TNT minecarts or (for Crossbow Cart) flint and steel ran out of your
 * hotbar and you still have some in your inventory, it opens your
 * inventory, glides the mouse over to a stack the way a hand would (a different path every
 * time), presses the number key of the hotbar slot it ran out of (the slot it was in), and
 * closes the inventory again. A number key swaps: the stack goes into exactly that slot,
 * and whatever junk you picked up into that slot goes back into the inventory, instead of a
 * shift-click filling the first free slot. Never while a cart is going down, never in
 * creative. If that slot holds something you need (a weapon, a crossbow, a bow, a totem, a
 * pearl...), the first empty slot is used instead, and with none it waits.
 */
final class CartRefill {
    private enum Kind {
        RAIL(Cart::isRail),
        CART(stack -> stack.is(Items.TNT_MINECART)),
        FLINT(stack -> stack.is(Items.FLINT_AND_STEEL));

        final Predicate<ItemStack> matches;

        Kind(Predicate<ItemStack> matches) {
            this.matches = matches;
        }
    }

    private enum Step { OPEN, MOVE, SWAP, CLOSE }

    private static final long RETRY_MS = 1500;
    // How long a cart is watched for its blast; one that never blows gets no refill.
    private static final long WATCH_MS = 15000;
    // Render thread only.
    private static final int[] LAST_SLOT = {-1, -1, -1};
    private static Step step;
    private static long nextMs;
    private static long moveStartMs;
    private static long moveMs;
    private static double bend;
    private static double wobble;
    private static double wobbleTurns;
    private static double wobblePhase;
    private static double lean = 1;
    private static double fromX;
    private static double fromY;
    private static double toX;
    private static double toY;
    private static int fromSlot;
    private static int toHotbar;
    private static long lastDoneMs;
    private static Field leftPos;
    private static BlockPos watchSpot;
    private static boolean sawCart;
    private static long watchUntilMs;
    private static boolean blown;
    private static Field topPos;

    private CartRefill() {}

    private static long now() {
        return System.nanoTime() / 1_000_000;
    }

    private static boolean watches(Kind kind) {
        boolean high = CrossbowCart.isEnabled() && CrossbowCart.isReplenish();
        boolean low = BowCart.isEnabled() && BowCart.isReplenish();
        return kind == Kind.FLINT ? high : high || low;
    }

    /** Called from the input loop and every frame. */
    static void update(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.gameMode == null) {
            step = null;
            return;
        }
        if (step != null) {
            run(minecraft, player);
            return;
        }
        if (!watches(Kind.RAIL)) {
            return;
        }
        remember(player);
        watchBlast(minecraft);
        if (!blown || Mc.screen(minecraft) != null || player.isCreative() || player.isUsingItem()
                || !player.onGround() && !player.isInWater() // not mid-air: once you land
                || player.getDeltaMovement().horizontalDistance() > 0.3 // not at a full sprint
                || CrossbowCart.isBusy() || BowCart.isBusy() || CrossbowReload.isBusy() || AntiCart.isBusy() || now() - lastDoneMs < RETRY_MS
                || !SpearSettings.combatAllowed(minecraft)) {
            return;
        }
        blown = false;
        if (!findJob(player)) {
            SpearLog.log("cart replenish: nothing to refill (hotbar has it, none left in the inventory, or no empty slot)");
        } else {
            Mc.setScreen(minecraft, new InventoryScreen(player));
            step = Step.OPEN;
            nextMs = now() + random(90, 160);
            SpearLog.log("cart replenish: inventory slot " + fromSlot + " to hotbar " + (toHotbar + 1));
        }
    }

    static boolean isBusy() {
        return step != null;
    }

    /** A cart click found nothing to place: refill now, without waiting for a blast. */
    /**
     * Stops a refill now and closes your inventory: you pressed something that would act on
     * the stack under the mouse (a hotbar key swaps it, Q drops it, a click picks it up).
     */
    static void abort(Minecraft minecraft, String why) {
        if (step == null) {
            return;
        }
        step = null;
        lastDoneMs = now();
        if (Mc.screen(minecraft) instanceof InventoryScreen screen) {
            screen.onClose();
        }
        SpearLog.log("cart replenish: stopped, " + why + " (it would have moved the stack under the mouse)");
    }

    static void requestNow() {
        if (watches(Kind.RAIL)) {
            blown = true;
        }
    }

    /** A cart of ours just went down here: refill once it has blown. */
    static void watch(BlockPos spot) {
        watchSpot = spot.immutable();
        sawCart = false;
        watchUntilMs = now() + WATCH_MS;
    }

    /** Sets blown once the watched cart is seen and then gone. */
    private static void watchBlast(Minecraft minecraft) {
        if (watchSpot == null || minecraft.level == null) {
            return;
        }
        boolean there = !minecraft.level.getEntitiesOfClass(MinecartTNT.class, new AABB(watchSpot).inflate(1.5)).isEmpty();
        if (there) {
            sawCart = true;
        } else if (sawCart) {
            blown = true;
            watchSpot = null;
            SpearLog.log("cart replenish: the cart blew");
            return;
        }
        if (now() > watchUntilMs) {
            watchSpot = null;
        }
    }

    /** Where each kind last sat in the hotbar, so a refill goes back to the same slot. */
    private static void remember(LocalPlayer player) {
        for (Kind kind : Kind.values()) {
            for (int slot = 0; slot < 9; slot++) {
                if (kind.matches.test(player.getInventory().getItem(slot))) {
                    LAST_SLOT[kind.ordinal()] = slot;
                    break;
                }
            }
        }
    }

    /** The next thing to refill, into fromSlot (menu index) and toHotbar; false if none. */
    private static boolean findJob(LocalPlayer player) {
        for (Kind kind : Kind.values()) {
            if (!watches(kind) || inHotbar(player, kind)) {
                continue;
            }
            int stack = inInventory(player.inventoryMenu, kind);
            if (stack < 0) {
                continue;
            }
            // Its own slot (where it was), with the number key; that slot holding something
            // you need, the first empty slot; neither, it waits.
            int home = LAST_SLOT[kind.ordinal()];
            int target = home >= 0 && !keeps(player.getInventory().getItem(home)) ? home : emptyHotbar(player);
            if (target < 0) {
                continue;
            }
            fromSlot = stack;
            toHotbar = target;
            return true;
        }
        return false;
    }

    /** In the hotbar or the offhand: either way there is no need to refill it. */
    private static boolean inHotbar(LocalPlayer player, Kind kind) {
        if (kind.matches.test(player.getOffhandItem())) {
            return true;
        }
        for (int slot = 0; slot < 9; slot++) {
            if (kind.matches.test(player.getInventory().getItem(slot))) {
                return true;
            }
        }
        return false;
    }

    /** The biggest stack of that kind in the main inventory, as a menu index; -1 if none. */
    private static int inInventory(InventoryMenu menu, Kind kind) {
        int best = -1;
        int bestCount = 0;
        for (int index = InventoryMenu.INV_SLOT_START; index < InventoryMenu.INV_SLOT_END; index++) {
            ItemStack stack = menu.getSlot(index).getItem();
            if (kind.matches.test(stack) && stack.getCount() > bestCount) {
                best = index;
                bestCount = stack.getCount();
            }
        }
        return best;
    }

    /** Something worth keeping in the hotbar, so a refill never swaps it out. */
    private static boolean keeps(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (Cart.isRail(stack) || stack.is(Items.TNT_MINECART) || stack.is(Items.FLINT_AND_STEEL)) {
            return true; // a cart item already there
        }
        return stack.isDamageableItem() || stack.is(Items.TOTEM_OF_UNDYING) || stack.is(Items.ENDER_PEARL)
                || stack.is(Items.GOLDEN_APPLE) || stack.is(Items.ENCHANTED_GOLDEN_APPLE)
                || stack.is(Items.WIND_CHARGE) || stack.is(Items.END_STONE) || stack.is(Items.OBSIDIAN)
                || stack.is(Items.LAVA_BUCKET) || stack.is(Items.WATER_BUCKET) || stack.is(Items.BUCKET) || stack.is(Items.COBWEB)
                || stack.is(Items.ARROW) || stack.is(Items.FIREWORK_ROCKET);
    }

    private static int emptyHotbar(LocalPlayer player) {
        for (int slot = 0; slot < 9; slot++) {
            if (player.getInventory().getItem(slot).isEmpty()) {
                return slot;
            }
        }
        return -1;
    }

    private static void run(Minecraft minecraft, LocalPlayer player) {
        if (!(Mc.screen(minecraft) instanceof InventoryScreen screen)) {
            step = null; // you closed it (or something else opened)
            lastDoneMs = now();
            return;
        }
        long now = now();
        if (step == Step.MOVE) {
            glide(minecraft, now);
        }
        if (now < nextMs) {
            return;
        }
        switch (step) {
            case OPEN -> startGlide(minecraft, screen, now);
            case MOVE -> {
                step = Step.SWAP;
                nextMs = now + random(40, 90); // a beat over the item before the key
            }
            case SWAP -> {
                InventoryMenu menu = screen.getMenu();
                if (menu.getCarried().isEmpty() && !menu.getSlot(fromSlot).getItem().isEmpty()) {
                    // The number key of that hotbar slot, over the stack: it swaps into exactly
                    // that slot (junk there goes back into the inventory).
                    ItemStack was = player.getInventory().getItem(toHotbar);
                    minecraft.gameMode.handleContainerInput(menu.containerId, fromSlot, toHotbar,
                            ContainerInput.SWAP, player);
                    SpearDash.setStatus("Replenished");
                    if (!was.isEmpty()) {
                        SpearLog.log("cart replenish: " + was.getHoverName().getString() + " went from hotbar "
                                + (toHotbar + 1) + " back into the inventory");
                    }
                }
                if (findJob(player)) {
                    startGlide(minecraft, screen, now); // more ran out: the next one in the same visit
                } else {
                    step = Step.CLOSE;
                    nextMs = now + random(80, 150);
                }
            }
            case CLOSE -> {
                screen.onClose();
                step = null;
                lastDoneMs = now;
            }
        }
    }

    /** Starts the mouse moving from where it is to the middle of the slot. */
    private static void startGlide(Minecraft minecraft, AbstractContainerScreen<?> screen, long now) {
        Slot slot = screen.getMenu().getSlot(fromSlot);
        double scaleX = minecraft.getWindow().getScreenWidth() / (double) minecraft.getWindow().getGuiScaledWidth();
        double scaleY = minecraft.getWindow().getScreenHeight() / (double) minecraft.getWindow().getGuiScaledHeight();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        toX = (left(screen) + slot.x + 8 + random.nextDouble(-3, 3)) * scaleX;
        toY = (top(screen) + slot.y + 8 + random.nextDouble(-3, 3)) * scaleY;
        double[] x = new double[1];
        double[] y = new double[1];
        GLFW.glfwGetCursorPos(minecraft.getWindow().handle(), x, y);
        fromX = x[0];
        fromY = y[0];
        // Longer for a longer way, a little different each time.
        double distance = Math.hypot(toX - fromX, toY - fromY);
        moveMs = Math.round((110 + 0.25 * distance) * random.nextDouble(0.8, 1.25));
        // Jitter, so no two trips take the same path: the line bows a different amount and
        // way each time, a small wobble rides on it, and the speed curve leans early or late.
        bend = distance * random.nextDouble(0.04, 0.16) * (random.nextBoolean() ? 1 : -1);
        wobble = Math.min(6, distance * random.nextDouble(0.005, 0.02));
        wobbleTurns = random.nextDouble(1.0, 2.5);
        wobblePhase = random.nextDouble(Math.PI * 2);
        lean = random.nextDouble(0.8, 1.25);
        moveStartMs = now;
        step = Step.MOVE;
        nextMs = now + moveMs;
        Debug.end(cursorRec);
        cursorRec = Debug.beginCursor("Replenish", distance, moveMs);
    }

    // Debug mode's record of the cursor trip (null while off).
    private static Debug.Move cursorRec;

    /** Minimum-jerk glide: slow start, fast middle, slow end. */
    private static void glide(Minecraft minecraft, long now) {
        double t = Math.min(1.0, (now - moveStartMs) / (double) Math.max(1, moveMs));
        double u = Math.pow(t, lean);
        double s = u * u * u * (10 + u * (-15 + 6 * u));
        double dx = toX - fromX;
        double dy = toY - fromY;
        double length = Math.max(1, Math.hypot(dx, dy));
        // Sideways from the straight line: the bow, plus the wobble, both gone at the ends.
        double side = bend * Math.sin(Math.PI * s)
                + wobble * Math.sin(Math.PI * 2 * wobbleTurns * t + wobblePhase) * Math.sin(Math.PI * t);
        GLFW.glfwSetCursorPos(minecraft.getWindow().handle(), fromX + dx * s - dy / length * side,
                fromY + dy * s + dx / length * side);
        Debug.point(cursorRec, fromX + dx * s - dy / length * side, fromY + dy * s + dx / length * side);
        if (t >= 1) {
            Debug.end(cursorRec);
            cursorRec = null;
        }
    }

    private static int left(AbstractContainerScreen<?> screen) {
        try {
            if (leftPos == null) {
                leftPos = AbstractContainerScreen.class.getDeclaredField("leftPos");
                leftPos.setAccessible(true);
            }
            return leftPos.getInt(screen);
        } catch (ReflectiveOperationException error) {
            return (screen.width - 176) / 2;
        }
    }

    private static int top(AbstractContainerScreen<?> screen) {
        try {
            if (topPos == null) {
                topPos = AbstractContainerScreen.class.getDeclaredField("topPos");
                topPos.setAccessible(true);
            }
            return topPos.getInt(screen);
        } catch (ReflectiveOperationException error) {
            return (screen.height - 166) / 2;
        }
    }

    private static long random(int low, int high) {
        return ThreadLocalRandom.current().nextInt(low, high + 1);
    }

    static void reset() {
        step = null;
        watchSpot = null;
        blown = false;
    }
}
