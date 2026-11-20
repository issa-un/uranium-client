package app;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.equipment.Equippable;

/**
 * Elytra Swap: on its bind, swaps what you wear on your chest between an elytra and the best
 * chestplate in your hotbar, the way right-clicking the hotbar item puts it on (the worn one
 * goes to that hotbar slot), then switches back to what you held. Swap delay, Use delay
 * and Swap back put a random pause in their range before each step.
 */
final class ElytraSwap {
    private static volatile boolean enabled;
    private static volatile int keyCode = -1;
    private static volatile int mouseButton = -1;
    private static volatile boolean awaitingBinding;
    static final Num SWAP_DELAY = Num.ms(0, 0, 300);
    static final Num USE_DELAY = Num.ms(0, 0, 300);
    static final Num SWAP_BACK = Num.ms(0, 0, 500);
    private static final SwapAction ACTION = new SwapAction();

    private ElytraSwap() {}

    /** Runs from its bind; true if it swapped, so the press is used up. */
    static boolean activate(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (!enabled || player == null || minecraft.gameMode == null || Mc.screen(minecraft) != null) {
            return false;
        }
        if (ACTION.busy()) {
            return true; // the last swap is still on its way
        }
        boolean wearingElytra = player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA);
        int slot = -1;
        double bestArmor = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!wearingElytra && stack.is(Items.ELYTRA)) {
                slot = i;
                break;
            }
            double armor = wearingElytra ? chestArmor(stack) : 0;
            if (armor > bestArmor) {
                slot = i;
                bestArmor = armor;
            }
        }
        if (slot < 0) {
            SpearDash.setStatus(wearingElytra ? "No chestplate in hotbar" : "No elytra in hotbar");
            return false;
        }
        ACTION.start(minecraft, slot, true, SWAP_DELAY, USE_DELAY, SWAP_BACK, game -> {
            game.gameMode.useItem(game.player, InteractionHand.MAIN_HAND);
            return true;
        }, (game, worn, from, used) -> {
            MinecraftInputController.stopGuard();
            if (worn) {
                SpearDash.setStatus(wearingElytra ? "Chestplate on" : "Elytra on");
                SpearLog.log("elytra swap: " + (wearingElytra ? "chestplate" : "elytra") + " from hotbar " + (used + 1));
            }
        });
        return true;
    }

    /** Called from the input loop: the steps of a delayed swap. */
    static void update(Minecraft minecraft) {
        ACTION.update(minecraft);
    }

    /** Armor points of a chestplate; 0 for anything else. */
    private static double chestArmor(ItemStack stack) {
        Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
        ItemAttributeModifiers modifiers = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
        if (equippable == null || equippable.slot() != EquipmentSlot.CHEST || modifiers == null) {
            return 0;
        }
        return modifiers.compute(Attributes.ARMOR, 0, EquipmentSlot.CHEST);
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

    static int keyCode() {
        return keyCode;
    }

    static int mouseButton() {
        return mouseButton;
    }

    static void setBinding(int key, int button) {
        keyCode = key;
        mouseButton = button;
    }

    static void bindKeyboard(int key) {
        keyCode = key;
        mouseButton = -1;
        awaitingBinding = false;
    }

    static void bindMouse(int button) {
        mouseButton = button;
        awaitingBinding = false;
    }

    static boolean isAwaitingBinding() {
        return awaitingBinding;
    }

    static void awaitBinding() {
        awaitingBinding = true;
    }

    static void cancelBinding() {
        awaitingBinding = false;
    }

    static void reset(Minecraft minecraft) {
        ACTION.cancel(minecraft);
        enabled = false;
        awaitingBinding = false;
    }
}
