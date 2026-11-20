package app;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Items;

/**
 * Auto Totem: keeps a Totem of Undying in your offhand. When the offhand has none (it popped,
 * or with Mode on Always, it holds something else), a totem from your hotbar goes there the
 * way the swap-offhand key moves it: that slot is selected, swapped with the offhand and
 * the old slot selected again, all at once. With your inventory open, a totem anywhere in it
 * is moved the same way (as if you pressed the swap key over it). Your offhand item, if any,
 * ends up where the totem was. Reaction waits a random time in its range before acting on
 * an empty offhand; Step delay spaces out the hotbar swap's three steps.
 */
final class AutoTotem {
    private static final String[] MODES = {"Empty offhand", "Always"};
    // After a swap, wait this long for the server's answer before trying again.
    private static final long RETRY_MS = 400;
    // The swap key's button number for the offhand in an inventory screen.
    private static final int OFFHAND_BUTTON = 40;
    private static volatile boolean enabled;
    private static volatile int mode;
    // Offhand needs a totem -> first step; between the hotbar swap's steps.
    static final Num REACTION = Num.ms(0, 0, 1000);
    static final Num STEP_DELAY = Num.ms(0, 0, 300);
    private static final Num NOW = Num.ms(0, 0, 0);
    private static final SwapAction ACTION = new SwapAction();
    // Render thread only.
    private static long lastSwapMs;
    private static long reactAtMs = -1;

    private AutoTotem() {}

    /** Called from the input loop. */
    static void update(Minecraft minecraft) {
        ACTION.update(minecraft);
        LocalPlayer player = minecraft.player;
        if (!enabled || player == null || minecraft.gameMode == null || minecraft.getConnection() == null
                || player.isSpectator() || !SpearSettings.combatAllowed(minecraft) || ACTION.busy()) {
            return;
        }
        if (player.getOffhandItem().is(Items.TOTEM_OF_UNDYING)
                || (mode == 0 && !player.getOffhandItem().isEmpty())) {
            reactAtMs = -1;
            return;
        }
        long now = System.nanoTime() / 1_000_000;
        if (reactAtMs < 0) {
            reactAtMs = now + REACTION.pick();
        }
        if (now < reactAtMs || now - lastSwapMs < RETRY_MS) {
            return;
        }
        if (Mc.screen(minecraft) instanceof InventoryScreen screen) {
            fromInventory(minecraft, player, screen.getMenu(), now);
        } else if (Mc.screen(minecraft) == null) {
            fromHotbar(minecraft, player, now);
        }
    }

    private static void fromHotbar(Minecraft minecraft, LocalPlayer player, long now) {
        int totem = BucketUse.findSlot(player, Items.TOTEM_OF_UNDYING);
        if (totem < 0) {
            return;
        }
        lastSwapMs = now;
        ACTION.start(minecraft, totem, true, NOW, STEP_DELAY, STEP_DELAY, game -> {
            if (game.getConnection() == null || !game.player.getMainHandItem().is(Items.TOTEM_OF_UNDYING)) {
                return false;
            }
            game.getConnection().send(new ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));
            return true;
        }, (game, acted, from, slot) -> {
            MinecraftInputController.stopGuard();
            lastSwapMs = System.nanoTime() / 1_000_000;
            reactAtMs = -1;
            if (acted) {
                SpearDash.setStatus("Totem to offhand");
                SpearLog.log("auto totem: hotbar " + (slot + 1) + " to offhand");
            }
        });
    }

    private static void fromInventory(Minecraft minecraft, LocalPlayer player, InventoryMenu menu, long now) {
        if (!menu.getCarried().isEmpty()) {
            return;
        }
        for (Slot slot : menu.slots) {
            if (slot.index >= InventoryMenu.INV_SLOT_START && slot.index < InventoryMenu.USE_ROW_SLOT_END
                    && slot.getItem().is(Items.TOTEM_OF_UNDYING)) {
                minecraft.gameMode.handleContainerInput(menu.containerId, slot.index, OFFHAND_BUTTON,
                        ContainerInput.SWAP, player);
                lastSwapMs = now;
                reactAtMs = -1;
                SpearDash.setStatus("Totem to offhand");
                SpearLog.log("auto totem: inventory slot " + slot.index + " to offhand");
                return;
            }
        }
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

    static String modeLabel() {
        return MODES[mode];
    }

    static int mode() {
        return mode;
    }

    static void setMode(int newMode) {
        mode = Math.floorMod(newMode, MODES.length);
    }

    static void cycleMode() {
        mode = (mode + 1) % MODES.length;
    }

    static void reset(Minecraft minecraft) {
        ACTION.cancel(minecraft);
        reactAtMs = -1;
        enabled = false;
    }
}
