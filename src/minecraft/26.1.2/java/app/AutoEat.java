package app;

import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Auto Eat: when your hunger drops to Hunger or below, switches to the most filling food in
 * your hotbar, holds use until it is eaten, then switches back. Golden apples are left for
 * Auto Gapple, and food that poisons, hurts or teleports you is never eaten. Picking a slot
 * yourself, opening a screen or 3.5 s passing stops it and gives use back to how you hold it.
 */
final class AutoEat {
    private static final int[] HUNGER = {12, 14, 16, 18};
    private static final long EAT_TIMEOUT_MS = 3500;
    private static final long COOLDOWN_MS = 500;
    private static final Set<Item> NEVER = Set.of(Items.GOLDEN_APPLE, Items.ENCHANTED_GOLDEN_APPLE,
            Items.ROTTEN_FLESH, Items.SPIDER_EYE, Items.POISONOUS_POTATO, Items.PUFFERFISH, Items.CHORUS_FRUIT,
            Items.SUSPICIOUS_STEW);
    private static volatile boolean enabled;
    private static volatile int hungerIndex = 1;
    // The meal in progress; render thread only.
    private static int eatingSlot = -1;
    private static int previousSlot = -1;
    private static int countBefore;
    private static long startedMs;
    private static long readyAtMs;

    private AutoEat() {}

    /** Called from the input loop. */
    static void update(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.gameMode == null) {
            eatingSlot = -1;
            return;
        }
        long now = System.nanoTime() / 1_000_000;
        if (eatingSlot >= 0) {
            continueEating(minecraft, player, now);
            return;
        }
        // Never in the middle of another module's swap: it would cancel it.
        if (ShieldBreak.isRunning() || PearlCatch.isRunning() || MinecraftSpearDash.isDashing()) {
            return;
        }
        if (!enabled || Mc.screen(minecraft) != null || now < readyAtMs || player.isUsingItem() || AutoGapple.isEating()
                || player.getFoodData().getFoodLevel() > HUNGER[hungerIndex] || player.getAbilities().instabuild) {
            return;
        }
        int best = -1;
        int bestNutrition = 0;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            FoodProperties food = stack.get(DataComponents.FOOD);
            if (food != null && !NEVER.contains(stack.getItem()) && food.nutrition() > bestNutrition) {
                best = slot;
                bestNutrition = food.nutrition();
            }
        }
        if (best < 0) {
            return;
        }
        previousSlot = player.getInventory().getSelectedSlot();
        eatingSlot = best;
        countBefore = player.getInventory().getItem(best).getCount();
        startedMs = now;
        player.getInventory().setSelectedSlot(best);
        MinecraftSpearDash.sendSelectedSlot(minecraft, false);
        MinecraftInputController.stopGuard();
        minecraft.options.keyUse.setDown(true);
        MinecraftSpearDash.setStatus("Eating");
    }

    private static void continueEating(Minecraft minecraft, LocalPlayer player, long now) {
        boolean eaten = player.getInventory().getItem(eatingSlot).getCount() < countBefore;
        boolean interrupted = player.getInventory().getSelectedSlot() != eatingSlot || Mc.screen(minecraft) != null;
        if (!eaten && !interrupted && now - startedMs < EAT_TIMEOUT_MS) {
            minecraft.options.keyUse.setDown(true);
            return;
        }
        minecraft.options.keyUse.setDown(WTap.physicallyDown(minecraft, minecraft.options.keyUse));
        if (!interrupted) {
            player.getInventory().setSelectedSlot(previousSlot);
            MinecraftSpearDash.sendSelectedSlot(minecraft, false);
        }
        MinecraftSpearDash.setStatus(eaten ? "Ate" : "Eating stopped");
        eatingSlot = -1;
        readyAtMs = now + COOLDOWN_MS;
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

    static String hungerLabel() {
        return HUNGER[hungerIndex] / 2 + " bars";
    }

    static int hungerIndex() {
        return hungerIndex;
    }

    static void setHungerIndex(int index) {
        hungerIndex = Math.floorMod(index, HUNGER.length);
    }

    static void cycleHunger() {
        hungerIndex = (hungerIndex + 1) % HUNGER.length;
    }

    /** Turns the module off when this injection is unloaded, giving use back mid-meal. */
    static void reset(Minecraft minecraft) {
        if (eatingSlot >= 0) {
            minecraft.options.keyUse.setDown(WTap.physicallyDown(minecraft, minecraft.options.keyUse));
            eatingSlot = -1;
        }
        enabled = false;
    }
}
