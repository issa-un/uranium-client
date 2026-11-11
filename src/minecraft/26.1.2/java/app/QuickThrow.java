package app;

import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * Quick Throw: on its bind, throws an ender pearl (or, with Item on Wind charge, a wind
 * charge) from your hotbar along your own view and switches straight back, the way Spear
 * Dash does with the spear. Nothing is aimed for you. An item still cooling down is not
 * thrown, so a press never wastes the swap. Swap delay, Throw delay and Swap back put a
 * random pause in their range before each step.
 */
final class QuickThrow {
    private static final String[] ITEM_NAMES = {"Ender pearl", "Wind charge"};
    private static final Item[] ITEMS = {Items.ENDER_PEARL, Items.WIND_CHARGE};
    private static volatile boolean enabled;
    private static volatile int item;
    private static volatile int keyCode = -1;
    private static volatile int mouseButton = -1;
    private static volatile boolean awaitingBinding;
    static final Num SWAP_DELAY = Num.ms(0, 0, 300);
    static final Num THROW_DELAY = Num.ms(0, 0, 300);
    static final Num SWAP_BACK = Num.ms(0, 0, 500);
    private static final SwapAction ACTION = new SwapAction();

    private QuickThrow() {}

    /** Runs from its bind; true if something was thrown, so the press is used up. */
    static boolean activate(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (!enabled || player == null || minecraft.gameMode == null || Mc.screen(minecraft) != null) {
            return false;
        }
        if (!SpearSettings.combatAllowed(minecraft)) {
            MinecraftSpearDash.setStatus("Off on this server");
            return false;
        }
        if (ACTION.busy()) {
            return true; // the last throw is still on its way
        }
        int slot = BucketUse.findSlot(player, ITEMS[item]);
        if (slot < 0) {
            MinecraftSpearDash.setStatus("No " + ITEM_NAMES[item].toLowerCase(Locale.ROOT) + " in hotbar");
            return false;
        }
        if (player.getCooldowns().isOnCooldown(player.getInventory().getItem(slot))) {
            MinecraftSpearDash.setStatus(ITEM_NAMES[item] + " cooling down");
            return false;
        }
        String name = ITEM_NAMES[item];
        ACTION.start(minecraft, slot, true, SWAP_DELAY, THROW_DELAY, SWAP_BACK, game -> {
            LocalPlayer thrower = game.player;
            if (thrower.getCooldowns().isOnCooldown(thrower.getMainHandItem())) {
                return false;
            }
            InteractionResult result = game.gameMode.useItem(thrower, InteractionHand.MAIN_HAND);
            if (Mc.swingsOnClient(result)) {
                Mc.swing(thrower, InteractionHand.MAIN_HAND);
            }
            return result.consumesAction();
        }, (game, thrown, from, used) -> {
            MinecraftInputController.stopGuard();
            if (thrown) {
                MinecraftSpearDash.setStatus(name + " thrown");
                SpearLog.log("quick throw: " + name.toLowerCase(Locale.ROOT) + " from hotbar " + (used + 1));
            }
        });
        return true;
    }

    /** Called from the input loop: the steps of a delayed throw. */
    static void update(Minecraft minecraft) {
        ACTION.update(minecraft);
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

    static String itemLabel() {
        return ITEM_NAMES[item];
    }

    static int item() {
        return item;
    }

    static void setItem(int newItem) {
        item = Math.floorMod(newItem, ITEMS.length);
    }

    static void cycleItem() {
        item = (item + 1) % ITEMS.length;
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
