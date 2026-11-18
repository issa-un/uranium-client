package app;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;

/** Writes your whole inventory to the log once after loading, so it can be looked at. */
final class InventoryDump {
    private static boolean done;

    private InventoryDump() {}

    static void update(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (done || player == null) {
            return;
        }
        done = true;
        int size = player.getInventory().getContainerSize();
        SpearLog.log("inventory: " + size + " slots, selected hotbar " + (player.getInventory().getSelectedSlot() + 1));
        for (int slot = 0; slot < size; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            SpearLog.log("inventory: " + where(slot) + ": " + describe(stack));
        }
        SpearLog.log("inventory: offhand: " + (player.getOffhandItem().isEmpty() ? "empty" : describe(player.getOffhandItem())));
    }

    private static String where(int slot) {
        if (slot < 9) {
            return "hotbar " + (slot + 1);
        }
        if (slot < 36) {
            return "inventory row " + ((slot - 9) / 9 + 1) + " col " + ((slot - 9) % 9 + 1);
        }
        return "slot " + slot;
    }

    private static String describe(ItemStack stack) {
        StringBuilder line = new StringBuilder(stack.getHoverName().getString());
        if (stack.getCount() > 1) {
            line.append(" x").append(stack.getCount());
        }
        StringBuilder enchants = new StringBuilder();
        for (var entry : stack.getEnchantments().entrySet()) {
            String key = entry.getKey().unwrapKey().map(Object::toString).orElse("?");
            int cut = key.lastIndexOf(':');
            String name = cut >= 0 ? key.substring(cut + 1).replace("]", "") : key;
            enchants.append(enchants.length() == 0 ? "" : ", ").append(name).append(' ').append(entry.getIntValue());
        }
        if (enchants.length() > 0) {
            line.append(" [").append(enchants).append(']');
        }
        if (stack.isDamageableItem()) {
            line.append(" (").append(stack.getMaxDamage() - stack.getDamageValue()).append('/').append(stack.getMaxDamage()).append(')');
        }
        if (stack.getItem() instanceof CrossbowItem) {
            line.append(CrossbowItem.isCharged(stack) ? " loaded" : " empty");
        }
        return line.toString();
    }

    static void reset() {
        done = false;
    }
}
