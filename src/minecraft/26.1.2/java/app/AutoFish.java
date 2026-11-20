package app;

import java.lang.reflect.Field;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.Items;

/**
 * Auto Fish: while you hold a fishing rod with the line out, reels in the moment a fish
 * bites (the bobber's bite flag, which the server sends), then casts again 0.6 s later.
 * Casting the first line is yours.
 */
final class AutoFish {
    private static final long RECAST_MS = 600;
    private static volatile boolean enabled;
    // Render thread only.
    private static long recastAtMs = -1;
    private static Field biting;

    private AutoFish() {}

    /** Called from the input loop. */
    static void update(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (!enabled || player == null || minecraft.gameMode == null || Mc.screen(minecraft) != null
                || !player.getMainHandItem().is(Items.FISHING_ROD)) {
            recastAtMs = -1;
            return;
        }
        long now = System.nanoTime() / 1_000_000;
        FishingHook hook = player.fishing;
        if (hook != null && recastAtMs < 0 && biting(hook)) {
            minecraft.gameMode.useItem(player, InteractionHand.MAIN_HAND);
            Mc.swing(player, InteractionHand.MAIN_HAND);
            recastAtMs = now + RECAST_MS;
            UraniumLog.log("auto fish: reeled in");
        } else if (hook == null && recastAtMs >= 0 && now >= recastAtMs) {
            minecraft.gameMode.useItem(player, InteractionHand.MAIN_HAND);
            Mc.swing(player, InteractionHand.MAIN_HAND);
            recastAtMs = -1;
        }
    }

    private static boolean biting(FishingHook hook) {
        try {
            if (biting == null) {
                biting = FishingHook.class.getDeclaredField("biting");
                biting.setAccessible(true);
            }
            return biting.getBoolean(hook);
        } catch (ReflectiveOperationException exception) {
            return false;
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

    static void reset() {
        enabled = false;
        recastAtMs = -1;
    }
}
