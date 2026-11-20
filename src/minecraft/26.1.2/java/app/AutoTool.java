package app;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Auto Tool: while you mine a block, switches to the hotbar tool that breaks it fastest
 * (one that gets its drops first), and with Swap back on, goes back to what you held 0.3 s
 * after you stop. It only acts once the game is actually mining, so clicks on players and
 * quick taps are left alone. A tool with 5 uses or fewer left is not picked.
 */
final class AutoTool {
    private static final long SWAP_BACK_MS = 300;
    private static final int MIN_USES_LEFT = 5;
    private static volatile boolean enabled;
    private static volatile boolean swapBack = true;
    // Render thread only.
    private static int originalSlot = -1;
    private static int toolSlot = -1;
    private static long lastMiningMs;

    private AutoTool() {}

    /** Called from the input loop. */
    static void update(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.gameMode == null || minecraft.level == null) {
            originalSlot = -1;
            return;
        }
        long now = System.nanoTime() / 1_000_000;
        boolean mining = enabled && Mc.screen(minecraft) == null && minecraft.gameMode.isDestroying()
                && minecraft.hitResult instanceof BlockHitResult;
        if (mining) {
            lastMiningMs = now;
            BlockState state = minecraft.level.getBlockState(((BlockHitResult) minecraft.hitResult).getBlockPos());
            int best = bestTool(player, state);
            if (best >= 0 && best != player.getInventory().getSelectedSlot()) {
                if (originalSlot < 0) {
                    originalSlot = player.getInventory().getSelectedSlot();
                }
                toolSlot = best;
                player.getInventory().setSelectedSlot(best);
                SpearDash.sendSelectedSlot(minecraft, false);
                InputLoop.stopGuard();
            }
            return;
        }
        if (originalSlot >= 0 && now - lastMiningMs > SWAP_BACK_MS) {
            // Only if you are still on the tool; a slot you picked yourself is kept.
            if (swapBack && player.getInventory().getSelectedSlot() == toolSlot) {
                player.getInventory().setSelectedSlot(originalSlot);
                SpearDash.sendSelectedSlot(minecraft, false);
            }
            originalSlot = -1;
        }
    }

    /** The hotbar slot that breaks the block fastest, if faster than what you hold; else -1. */
    private static int bestTool(LocalPlayer player, BlockState state) {
        int selected = player.getInventory().getSelectedSlot();
        int best = -1;
        double bestScore = score(player.getInventory().getItem(selected), state);
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.isDamageableItem() && stack.getMaxDamage() - stack.getDamageValue() <= MIN_USES_LEFT) {
                continue;
            }
            double score = score(stack, state);
            if (score > bestScore) {
                best = slot;
                bestScore = score;
            }
        }
        return best;
    }

    /** Mining speed, with getting the block's drops counting before speed. */
    private static double score(ItemStack stack, BlockState state) {
        boolean drops = !state.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(state);
        return stack.getDestroySpeed(state) + (drops ? 1000 : 0);
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

    static boolean isSwapBack() {
        return swapBack;
    }

    static void setSwapBack(boolean on) {
        swapBack = on;
    }

    static void toggleSwapBack() {
        swapBack = !swapBack;
    }

    static void reset() {
        enabled = false;
        originalSlot = -1;
    }
}
