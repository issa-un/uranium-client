package app;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Armor Slots: your four armor pieces in the game's own hotbar slots on the left of your
 * hotbar (past the offhand slot), each with its durability bar, so you see them without
 * opening your inventory.
 *
 * Alerts: three red "!!!" hop over any slot that needs you:
 * - armor under 20% durability, or a piece missing while you wear the rest,
 * - a hotbar item under 10% durability,
 * - a crossbow that isn't loaded,
 * - running low: 2 or fewer TNT carts, pearls or golden apples, or 8 or fewer rails (counting
 *   your whole inventory),
 * - an empty offhand while you carry a totem.
 */
final class ArmorSlots {
    private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
        EquipmentSlot.FEET};
    private static final int RED = 0xFFFF3030;
    private static final net.minecraft.resources.Identifier HOTBAR =
            net.minecraft.resources.Identifier.withDefaultNamespace("hud/hotbar");
    private static final int SLOT_FILL = 0x90000000;
    private static final int SLOT_EDGE = 0x40FFFFFF;
    private static volatile boolean enabled;
    private static volatile boolean alerts = true;

    private ArmorSlots() {}

    /** Called by the HUD every frame. */
    static void draw(GuiGraphicsExtractor graphics, Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player == null || player.isSpectator() || !enabled) {
            return;
        }
        int left = graphics.guiWidth() / 2 - 91;
        int top = graphics.guiHeight() - 22;
        boolean rightHanded = player.getMainArm() == HumanoidArm.RIGHT;
        if (enabled) {
            // On the left of the hotbar, past the offhand slot when that is on the left too.
            int width = 4 * 20 + 3;
            int x = rightHanded ? left - 29 - 6 - width : left - 6 - width;
            boolean wearsAny = false;
            for (EquipmentSlot slot : ARMOR) {
                wearsAny |= !player.getItemBySlot(slot).isEmpty();
            }
            // the game's own hotbar frame: its first four slots, then its right edge
            graphics.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, HOTBAR, 182, 22, 0, 0,
                    x, top, 81, 22);
            graphics.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, HOTBAR, 182, 22, 180, 0,
                    x + 81, top, 2, 22);
            for (int i = 0; i < ARMOR.length; i++) {
                int sx = x + 3 + i * 20;
                ItemStack piece = player.getItemBySlot(ARMOR[i]);
                if (!piece.isEmpty()) {
                    graphics.item(piece, sx, top + 3);
                    graphics.itemDecorations(minecraft.font, piece, sx, top + 3);
                }
                if (alerts && (piece.isEmpty() ? wearsAny : worn(piece, 0.2))) {
                    bang(graphics, minecraft, sx + 8, top - 9, i);
                }
            }
        }
        if (!alerts) {
            return;
        }
        for (int slot = 0; slot < 9; slot++) {
            if (needsYou(player, player.getInventory().getItem(slot))) {
                bang(graphics, minecraft, left + 11 + slot * 20, top - 9, slot + 4);
            }
        }
        if (player.getOffhandItem().isEmpty() && carries(player, Items.TOTEM_OF_UNDYING)) {
            int x = rightHanded ? left - 29 + 11 : left + 182 + 7 + 11;
            bang(graphics, minecraft, x, top - 9, 13);
        }
    }

    private static boolean needsYou(LocalPlayer player, ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (worn(stack, 0.1)) {
            return true;
        }
        if (stack.is(Items.CROSSBOW) && !CrossbowItem.isCharged(stack)) {
            return true;
        }
        if (stack.is(Items.TNT_MINECART)) {
            return count(player, s -> s.is(Items.TNT_MINECART)) <= 2;
        }
        if (stack.is(Items.ENDER_PEARL)) {
            return count(player, s -> s.is(Items.ENDER_PEARL)) <= 2;
        }
        if (stack.is(Items.GOLDEN_APPLE) || stack.is(Items.ENCHANTED_GOLDEN_APPLE)) {
            return count(player, s -> s.is(Items.GOLDEN_APPLE) || s.is(Items.ENCHANTED_GOLDEN_APPLE)) <= 2;
        }
        if (Cart.isRail(stack)) {
            return count(player, Cart::isRail) <= 8;
        }
        return false;
    }

    /** Under this share of its durability left. */
    private static boolean worn(ItemStack stack, double share) {
        return stack.isDamageableItem() && stack.getMaxDamage() > 0
                && (stack.getMaxDamage() - stack.getDamageValue()) / (double) stack.getMaxDamage() < share;
    }

    private static int count(LocalPlayer player, java.util.function.Predicate<ItemStack> kind) {
        int total = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (kind.test(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static boolean carries(LocalPlayer player, net.minecraft.world.item.Item item) {
        return count(player, s -> s.is(item)) > 0;
    }

    private static void outline(GuiGraphicsExtractor graphics, int x, int y, int w, int h) {
        graphics.fill(x, y, x + w, y + 1, SLOT_EDGE);
        graphics.fill(x, y + h - 1, x + w, y + h, SLOT_EDGE);
        graphics.fill(x, y, x + 1, y + h, SLOT_EDGE);
        graphics.fill(x + w - 1, y, x + w, y + h, SLOT_EDGE);
    }

    /** Three red "!" in the game's pixel font, each hopping a beat after the one before. */
    static void bang(GuiGraphicsExtractor graphics, Minecraft minecraft, int centerX, int y, int seed) {
        double t = System.nanoTime() / 1.0E9;
        int width = minecraft.font.width("!");
        int gap = width + 1;
        int start = centerX - (gap * 3 - 1) / 2;
        for (int i = 0; i < 3; i++) {
            // a hop: up fast, down fast, a short rest on the ground
            double phase = (t * 2.2 + i * 0.18 + seed * 0.07) % 1.0;
            int hop = phase < 0.5 ? (int) Math.round(Math.sin(phase * 2 * Math.PI) * 3) : 0;
            graphics.text(minecraft.font, "!", start + i * gap, y - hop, RED);
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

    static boolean isAlerts() {
        return alerts;
    }

    static void setAlerts(boolean on) {
        alerts = on;
    }

    static void toggleAlerts() {
        alerts = !alerts;
    }

    static void reset() {
        enabled = false;
    }
}
