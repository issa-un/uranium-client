package app;

import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Auto Reload, for the Cart modules: when a crossbow in your hotbar is empty and nothing is
 * going on (no cart going down, no refill, you aren't clicking or using anything), it
 * switches to the crossbow, holds right-click until it is loaded, lets go and switches back
 * to what you had. The swaps show in your hotbar. Pick a slot yourself, open a screen, or
 * start using something and it stops at once and leaves you where you are.
 */
final class CrossbowReload {
    private enum Step { DRAW_START, DRAW, BACK }

    // How long things have to be quiet before a reload starts.
    private static final long IDLE_MS = 400;
    // Ticks past the full charge before letting go, so the server has it charged too.
    private static final int SPARE_TICKS = 1;
    // Render thread only.
    private static Step step;
    private static int crossbowSlot;
    private static int backSlot;
    private static long nextMs;
    private static long quietSinceMs = -1;

    private CrossbowReload() {}

    private static long now() {
        return System.nanoTime() / 1_000_000;
    }

    private static boolean wanted() {
        return CrossbowCart.isEnabled() && CrossbowCart.isAutoReload()
                || BowCart.isEnabled() && BowCart.isAutoReload();
    }

    static boolean isBusy() {
        return step != null;
    }

    /** Called from the input loop. */
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
        long now = now();
        if (!wanted() || Mc.screen(minecraft) != null || player.isUsingItem() || player.isSpectator()
                || CrossbowCart.isBusy() || BowCart.isBusy() || CartRefill.isBusy() || AntiCart.isBusy()
                || WTap.physicallyDown(minecraft, minecraft.options.keyUse)
                || WTap.physicallyDown(minecraft, minecraft.options.keyAttack)
                || !SpearSettings.combatAllowed(minecraft)) {
            quietSinceMs = -1;
            return;
        }
        int slot = emptyCrossbow(player);
        if (slot < 0) {
            quietSinceMs = -1;
            return;
        }
        if (quietSinceMs < 0) {
            quietSinceMs = now;
        }
        if (now - quietSinceMs < IDLE_MS) {
            return;
        }
        if (player.getProjectile(player.getInventory().getItem(slot)).isEmpty()) {
            return; // no arrows to load
        }
        quietSinceMs = -1;
        crossbowSlot = slot;
        backSlot = player.getInventory().getSelectedSlot();
        if (backSlot != slot) {
            Cart.select(minecraft, player, slot);
        }
        step = Step.DRAW_START;
        nextMs = now + random(60, 120);
        SpearLog.log("auto reload: loading the crossbow in hotbar " + (slot + 1));
    }

    private static void run(Minecraft minecraft, LocalPlayer player) {
        if (Cart.macroBusy(player)) {
            return; // the macro hotbar press lands on the game's next tick
        }
        if (Mc.screen(minecraft) != null || Cart.heldSlot(player) != crossbowSlot
                || !player.getInventory().getItem(crossbowSlot).is(Items.CROSSBOW)) {
            stop(minecraft, "stopped, you took over");
            return;
        }
        long now = now();
        if (now < nextMs) {
            return;
        }
        ItemStack crossbow = player.getInventory().getItem(crossbowSlot);
        switch (step) {
            case DRAW_START -> {
                minecraft.options.keyUse.setDown(true);
                minecraft.gameMode.useItem(player, InteractionHand.MAIN_HAND);
                step = Step.DRAW;
            }
            case DRAW -> {
                if (!player.isUsingItem()) {
                    stop(minecraft, CrossbowItem.isCharged(crossbow) ? "loaded" : "stopped, the crossbow let go");
                    return;
                }
                minecraft.options.keyUse.setDown(true);
                if (player.getTicksUsingItem() >= CrossbowItem.getChargeDuration(crossbow, player) + SPARE_TICKS) {
                    letGo(minecraft);
                    minecraft.gameMode.releaseUsingItem(player);
                    step = Step.BACK;
                    nextMs = now + random(70, 150);
                }
            }
            case BACK -> {
                step = null;
                if (backSlot != crossbowSlot) {
                    Cart.select(minecraft, player, backSlot);
                }
                SpearDash.setStatus(CrossbowItem.isCharged(crossbow) ? "Crossbow loaded" : "Reload failed");
                SpearLog.log("auto reload: " + (CrossbowItem.isCharged(crossbow) ? "loaded" : "didn't load"));
            }
        }
    }

    private static void stop(Minecraft minecraft, String why) {
        if (step == Step.DRAW) {
            letGo(minecraft);
        }
        step = null;
        SpearLog.log("auto reload: " + why);
    }

    private static void letGo(Minecraft minecraft) {
        minecraft.options.keyUse.setDown(WTap.physicallyDown(minecraft, minecraft.options.keyUse));
    }

    /** The first hotbar crossbow that isn't loaded; -1 if none. */
    private static int emptyCrossbow(LocalPlayer player) {
        if (CrossbowCart.isHardSwitch() && anyLoaded(player)) {
            return -1; // Hard switch: another crossbow is loaded, the shot uses that one
        }
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(Items.CROSSBOW) && !CrossbowItem.isCharged(stack)) {
                return slot;
            }
        }
        return -1;
    }

    /** A loaded crossbow in your hotbar or offhand. */
    private static boolean anyLoaded(LocalPlayer player) {
        if (player.getOffhandItem().is(Items.CROSSBOW) && CrossbowItem.isCharged(player.getOffhandItem())) {
            return true;
        }
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(Items.CROSSBOW) && CrossbowItem.isCharged(stack)) {
                return true;
            }
        }
        return false;
    }

    private static long random(int low, int high) {
        return ThreadLocalRandom.current().nextInt(low, high + 1);
    }

    static void reset(Minecraft minecraft) {
        if (step == Step.DRAW && minecraft != null) {
            letGo(minecraft);
        }
        step = null;
        quietSinceMs = -1;
    }
}
