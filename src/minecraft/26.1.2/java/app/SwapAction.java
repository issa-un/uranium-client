package app;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * Switch to a hotbar item, use it, switch back, each step after its own delay: the shared
 * timing behind Spear Dash, Mace Swap, Auto Weapon, Auto Totem, Quick Throw and Elytra Swap.
 * With every delay at 0 the whole thing runs at once, in one step, as before. Each module
 * keeps one and calls update from the input loop; render thread only.
 */
final class SwapAction {
    /** What happens with the item selected; true if it happened. */
    interface Act {
        boolean run(Minecraft minecraft);
    }

    /** Called once the slot is back (or the item stays, with no swap back). */
    interface Done {
        void finished(Minecraft minecraft, boolean acted, int fromSlot, int itemSlot);
    }

    private static final int IDLE = 0;
    private static final int SWAP = 1;
    private static final int ACT = 2;
    private static final int BACK = 3;
    // Also read from the network thread (busy), so the phase is published at once.
    private volatile int phase = IDLE;
    private long dueMs;
    private int fromSlot;
    private int itemSlot;
    private boolean swapBack;
    private boolean acted;
    private Num actDelay;
    private Num backDelay;
    private Act act;
    private Done done;

    boolean busy() {
        return phase != IDLE;
    }

    /**
     * Starts the sequence and runs every step that is due now. backDelay is ignored without
     * swapBack (the item stays selected).
     */
    void start(Minecraft minecraft, int slot, boolean back, Num swapDelay, Num actDelay, Num backDelay,
            Act act, Done done) {
        fromSlot = minecraft.player.getInventory().getSelectedSlot();
        itemSlot = slot;
        swapBack = back;
        acted = false;
        this.actDelay = actDelay;
        this.backDelay = backDelay;
        this.act = act;
        this.done = done;
        phase = SWAP;
        dueMs = nowMs() + swapDelay.pick();
        update(minecraft);
    }

    /** Runs the steps that are due. A screen opening puts the slot back at once. */
    void update(Minecraft minecraft) {
        while (phase != IDLE) {
            LocalPlayer player = minecraft.player;
            if (player == null || minecraft.gameMode == null) {
                phase = IDLE;
                return;
            }
            if (Mc.screen(minecraft) != null) {
                cancel(minecraft);
                SpearDash.setStatus("Cancelled");
                return;
            }
            int selected = player.getInventory().getSelectedSlot();
            if (selected != (phase == SWAP ? fromSlot : itemSlot)) {
                // You picked a slot yourself meanwhile; that choice is kept.
                phase = IDLE;
                SpearDash.setStatus("Stopped: you picked a slot");
                finish(minecraft);
                return;
            }
            long now = nowMs();
            if (now < dueMs) {
                return;
            }
            switch (phase) {
                case SWAP -> {
                    player.getInventory().setSelectedSlot(itemSlot);
                    SpearDash.sendSelectedSlot(minecraft, false);
                    phase = ACT;
                    dueMs = now + actDelay.pick();
                }
                case ACT -> {
                    try {
                        acted = act.run(minecraft);
                    } catch (RuntimeException exception) {
                        acted = false;
                    }
                    if (!swapBack) {
                        phase = IDLE;
                        finish(minecraft);
                        return;
                    }
                    phase = BACK;
                    // Nothing happened: no reason to keep the item out.
                    dueMs = acted ? now + backDelay.pick() : now;
                }
                default -> {
                    phase = IDLE;
                    selectFrom(minecraft);
                    finish(minecraft);
                    return;
                }
            }
        }
    }

    /** Puts the slot back now (screen opened, Unload); the module is not told. */
    void cancel(Minecraft minecraft) {
        boolean swapped = phase == ACT || phase == BACK;
        phase = IDLE;
        if (swapped && swapBack && minecraft.player != null && minecraft.gameMode != null) {
            selectFrom(minecraft);
        }
    }

    private void selectFrom(Minecraft minecraft) {
        if (minecraft.player.getInventory().getSelectedSlot() == itemSlot) {
            minecraft.player.getInventory().setSelectedSlot(fromSlot);
            SpearDash.sendSelectedSlot(minecraft, false);
        }
    }

    private void finish(Minecraft minecraft) {
        if (done != null) {
            done.finished(minecraft, acted, fromSlot, itemSlot);
        }
    }

    private static long nowMs() {
        return System.nanoTime() / 1_000_000;
    }
}
