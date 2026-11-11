package app;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

final class MinecraftSpearDash {
    private static volatile boolean enabled;
    private static volatile int keyCode = 71;
    private static volatile int mouseButton = -1;
    // Press -> spear selected, spear selected -> jab, jab -> back to your slot, and the
    // extra wait once the spear is charged again before a chained dash.
    static final Num SWAP_DELAY = Num.ms(0, 0, 300);
    static final Num JAB_DELAY = Num.ms(0, 0, 300);
    static final Num RETURN = Num.ms(0, 0, 500);
    static final Num CHAIN_GAP = Num.ms(0, 0, 500);
    // How long the spear shows in your hand after the jab when Return is 0.
    private static final Num SHOW = Num.ms(120, 120, 500);
    private static final SwapAction SWAP = new SwapAction();
    private static volatile String status = "Ready";
    // A short warning shown on screen even with the menu closed.
    private static final long ALERT_MS = 2500;
    private static volatile String alert = "";
    private static volatile long alertUntilMs;
    private static volatile boolean awaitingBinding;
    private static final String[] DASH_COUNTS = {"Single", "Double", "Triple"};
    // Packet: only the server sees a level view, for the jab; your camera never moves.
    private static final String[] FLAT_MODES = {"Off", "Smooth", "Fast", "Packet"};
    private static final int FLAT_PACKET = 3;
    // Gliding to level takes a base time plus a little per degree, eased in and out:
    // Smooth turns 45 degrees in about 0.2 s, Fast in about 0.1 s.
    private static final long[] LEVEL_BASE_MS = {0, 80, 40, 0};
    private static final double[] LEVEL_MS_PER_DEGREE = {0, 3.0, 1.5, 0};
    // Close enough to level that Lunge keeps over 99.99% of its push.
    private static final float FLAT_ENOUGH = 0.5f;
    private static final long LEVEL_TIMEOUT_MS = 1500;
    // A chained dash waits this long at most for the spear to charge again.
    private static final long CHAIN_TIMEOUT_MS = 2000;
    private static volatile int dashCount = 1;
    private static volatile int flatMode = 1;
    private static volatile boolean readySound;
    private static volatile boolean onlyFromWindCharge;
    private static boolean wasCharged;
    private static Method startAttack;
    private static Method ensureHasSentCarriedItem;
    private static volatile Field carriedIndex;
    // Dash state for the current press, touched only on the render thread.
    private static int dashesLeft;
    private static boolean levelling;
    private static boolean waitingForSend;
    private static boolean waitingForCharge;
    private static float levelStartPitch;
    private static long levelStartMs;
    private static long levelDurationMs;
    private static int levelledAtTick;
    private static long chargeDeadlineMs;
    private static long chainReadyAtMs = -1;
    private static boolean dashLevelled;
    private static boolean jabResult;

    private MinecraftSpearDash() {}

    /**
     * A press of the dash binding; true if a dash went out or is on its way. With Only from
     * wind charge, a press while you hold anything else is left to the game, so a binding
     * that is also the spear's hotbar key just selects the spear.
     */
    static boolean activate(Minecraft minecraft) {
        if (!enabled || minecraft.player == null || minecraft.gameMode == null) {
            return false;
        }
        // A press while a dash is still on its way (or the spear still shows) is used up, so
        // a binding that is also a hotbar key cannot switch you to that slot mid-dash.
        if (dashesLeft > 0 || SWAP.busy()) {
            return true;
        }
        if (onlyFromWindCharge && !minecraft.player.getMainHandItem().is(Items.WIND_CHARGE)) {
            return false;
        }
        if (!SpearSettings.combatAllowed(minecraft)) {
            status = "Off on this server";
            return false;
        }
        if (minecraft.hitResult == null || minecraft.hitResult.getType() != HitResult.Type.MISS) {
            status = "Aim at open air";
            return false;
        }
        ItemStack spear = lungeSpear(minecraft);
        if (spear.isEmpty()) {
            status = "No Lunge spear";
            return false;
        }
        // Checked now so the view does not glide for a jab that cannot happen.
        if (minecraft.player.cannotAttackWithItem(spear, 0)) {
            status = "Not charged yet";
            return false;
        }
        dashesLeft = dashCount;
        return beginDash(minecraft);
    }

    /**
     * Starts the next dash of this press: levels the view first if Flat lunge needs to.
     * True if the dash went out or the levelling before it started.
     */
    private static boolean beginDash(Minecraft minecraft) {
        float pitch = minecraft.player.getXRot();
        if (flatMode != 0 && flatMode != FLAT_PACKET && Math.abs(pitch) > FLAT_ENOUGH) {
            levelling = true;
            levelStartPitch = pitch;
            levelStartMs = System.nanoTime() / 1_000_000;
            levelDurationMs = LEVEL_BASE_MS[flatMode]
                    + Math.round(Math.abs(pitch) * LEVEL_MS_PER_DEGREE[flatMode]);
            status = "Levelling...";
            return true;
        }
        return performDash(minecraft, false);
    }

    /**
     * Moves the current press along; the HUD calls this every frame and the input loop
     * calls it too. Lunge pushes along your look direction with the vertical part removed,
     * so looking up or down shortens it (60 degrees down halves it). Flat lunge glides the
     * view to level like a quick mouse flick; the level view reaches the server in the
     * game's normal movement update, with no extra packets, and the dash follows once that
     * update has gone out. Double and Triple fire the next dash as soon as the spear is
     * fully charged again (spears need a full charge to jab). Dashes go out only from the
     * input loop (mayDash), never in the middle of rendering.
     */
    static void updateDash(Minecraft minecraft, boolean mayDash) {
        if (mayDash) {
            SWAP.update(minecraft);
        }
        if (dashesLeft == 0) {
            return;
        }
        LocalPlayer player = minecraft.player;
        long now = System.nanoTime() / 1_000_000;
        if (player == null || Mc.screen(minecraft) != null) {
            cancel("Dash cancelled");
            return;
        }
        if (levelling || waitingForSend) {
            if (now - levelStartMs > LEVEL_TIMEOUT_MS) {
                cancel("Dash cancelled");
                return;
            }
            if (levelling) {
                double progress = Math.min(1, (now - levelStartMs) / (double) levelDurationMs);
                double eased = progress * progress * (3 - 2 * progress);
                setPitch(player, (float) (levelStartPitch * (1 - eased)));
                if (progress >= 1) {
                    levelling = false;
                    waitingForSend = true;
                    levelledAtTick = player.tickCount;
                }
                return;
            }
            // The level view goes out in the movement update at the end of the next game
            // tick, so once a tick has passed the jab reaches the server after it.
            if (mayDash && player.tickCount > levelledAtTick) {
                waitingForSend = false;
                performDash(minecraft, true);
            }
            return;
        }
        if (!levelling && !waitingForSend && !waitingForCharge && !SWAP.busy()) {
            cancel("Dash cancelled"); // the swap ended without telling us (left the world)
            return;
        }
        if (waitingForCharge && mayDash) {
            if (now > chargeDeadlineMs) {
                cancel("Chain timed out");
                return;
            }
            ItemStack spear = lungeSpear(minecraft);
            if (spear.isEmpty()) {
                cancel("No Lunge spear");
                return;
            }
            if (!lungeCanPush(player)) {
                cancel("Lunge can't push now");
                return;
            }
            if (!SWAP.busy() && !player.cannotAttackWithItem(spear, 0)) {
                if (chainReadyAtMs < 0) {
                    chainReadyAtMs = now + CHAIN_GAP.pick();
                }
                if (now >= chainReadyAtMs) {
                    waitingForCharge = false;
                    beginDash(minecraft);
                }
            }
        }
    }

    /**
     * Called from the input loop: with Ready sound on, a quiet click the moment the Lunge
     * spear becomes fully charged, so you know a dash can go without looking.
     */
    static void updateReadySound(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        ItemStack spear = player == null ? ItemStack.EMPTY : lungeSpear(minecraft);
        boolean charged = !spear.isEmpty() && !player.cannotAttackWithItem(spear, 0);
        if (charged && !wasCharged && readySound && enabled && SpearSettings.combatAllowed(minecraft)) {
            minecraft.getSoundManager().play(
                    SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.8F, 0.35F));
        }
        wasCharged = charged;
    }

    private static void cancel(String reason) {
        dashesLeft = 0;
        levelling = false;
        waitingForSend = false;
        waitingForCharge = false;
        status = reason;
    }

    private static void setPitch(LocalPlayer player, float pitch) {
        // Shift the previous pitch by the same amount, as mouse turning does, so the
        // camera moves this frame instead of easing in over the next one.
        float delta = pitch - player.getXRot();
        player.setXRot(pitch);
        player.xRotO += delta;
    }

    /**
     * Fires one dash: swap to the spear, jab, swap back, each after its delay (all at once
     * when they are 0). For Double or Triple, dashDone then waits for the charge for the
     * next one. True if the dash went out or is on its way.
     */
    private static boolean performDash(Minecraft minecraft, boolean levelled) {
        int spearSlot = findLungeSpear(minecraft);
        if (spearSlot < 0) {
            cancel("No Lunge spear");
            return false;
        }
        dashLevelled = levelled;
        jabResult = false;
        // A new dash is ours: the last dash's switch-back watch must not take it for the server.
        MinecraftInputController.stopGuard();
        // With Return at 0 the spear still stays in hand for a moment, so the swap shows.
        SWAP.start(minecraft, spearSlot, true, SWAP_DELAY, JAB_DELAY, RETURN.isZero() ? SHOW : RETURN,
                MinecraftSpearDash::jab, MinecraftSpearDash::dashDone);
        // Without delays the dash is already over; dashDone cancelled the press if it failed.
        return SWAP.busy() || jabResult;
    }

    /** Once the slot is back: counts the dash, or ends the press if the jab did not go out. */
    private static void dashDone(Minecraft minecraft, boolean jabbed, int previousSlot, int spearSlot) {
        SpearLog.log("dash: hotbar " + (previousSlot + 1) + " -> spear " + (spearSlot + 1) + " -> back to "
                + (minecraft.player == null ? "?" : String.valueOf(minecraft.player.getInventory().getSelectedSlot() + 1))
                + (jabbed ? ", jab sent" : ", no jab (" + status + ")"));
        jabResult = jabbed;
        if (!jabbed) {
            cancel(status); // jab() said why
            return;
        }
        MinecraftInputController.guardSwitchBack(previousSlot, spearSlot);
        if (dashesLeft <= 0) {
            return; // the press was cancelled while this dash was on its way
        }
        dashesLeft--;
        int done = dashCount - dashesLeft;
        String sent = dashLevelled ? "Flat dash" : "Dash";
        status = dashCount == 1 ? sent + " sent" : sent + " " + done + "/" + dashCount;
        if (dashesLeft > 0) {
            waitingForCharge = true;
            chainReadyAtMs = -1;
            chargeDeadlineMs = System.nanoTime() / 1_000_000 + CHAIN_TIMEOUT_MS + CHAIN_GAP.high();
        }
    }

    /** Jabs with the spear that is now selected; sets the status when it cannot. */
    private static boolean jab(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player.cannotAttackWithItem(player.getMainHandItem(), 0)) {
            status = "Not charged yet";
            return false;
        }
        boolean jabbed = flatMode == FLAT_PACKET && Math.abs(player.getXRot()) > FLAT_ENOUGH
                ? jabLevelByPacket(minecraft) : jabOnce(minecraft);
        if (jabbed) {
            return true;
        }
        if (startAttack != null) {
            status = "Not ready";
        }
        return false;
    }

    /**
     * Flat lunge on Packet: the server is told you look level just for the jab, and your
     * real view right after, so the lunge pushes at full strength while your camera never
     * moves. Your view is level on your side too during the jab, so anything the game
     * works out from it matches what the server sees.
     */
    private static boolean jabLevelByPacket(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        ClientPacketListener connection = minecraft.getConnection();
        if (connection == null) {
            return jabOnce(minecraft);
        }
        float pitch = player.getXRot();
        float pitchO = player.xRotO;
        connection.send(new ServerboundMovePlayerPacket.Rot(player.getYRot(), 0, player.onGround(),
                player.horizontalCollision));
        player.setXRot(0);
        try {
            return jabOnce(minecraft);
        } finally {
            player.setXRot(pitch);
            player.xRotO = pitchO;
            connection.send(new ServerboundMovePlayerPacket.Rot(player.getYRot(), pitch, player.onGround(),
                    player.horizontalCollision));
        }
    }

    /**
     * Lunge's own conditions for pushing (data/minecraft/enchantment/lunge.json): not
     * riding, gliding or in water, and at least 7 food unless in creative.
     */
    static boolean lungeCanPush(LocalPlayer player) {
        return !player.isPassenger() && !player.isFallFlying() && !player.isInWater()
                && (player.isCreative() || player.getFoodData().getFoodLevel() >= 7);
    }

    /**
     * Sends the selected slot to the server now instead of on the next game tick.
     * With force, it is sent even if the game thinks the server already has it: a slot
     * forced by the server updates the hotbar but not the game's record of what it sent.
     */
    static void sendSelectedSlot(Minecraft minecraft, boolean force) {
        if (minecraft.gameMode == null) {
            return;
        }
        try {
            if (force) {
                carriedIndexField().setInt(minecraft.gameMode, -1);
            }
            if (ensureHasSentCarriedItem == null) {
                ensureHasSentCarriedItem = MultiPlayerGameMode.class
                        .getDeclaredMethod("ensureHasSentCarriedItem");
                ensureHasSentCarriedItem.setAccessible(true);
            }
            ensureHasSentCarriedItem.invoke(minecraft.gameMode);
        } catch (ReflectiveOperationException ignored) {
            // The game sends the slot on its next tick anyway.
        }
    }

    /** The hotbar slot the server has for you: the one the game last sent. */
    static int sentSlot(Minecraft minecraft) {
        int selected = minecraft.player.getInventory().getSelectedSlot();
        try {
            int sent = carriedIndexField().getInt(minecraft.gameMode);
            return sent >= 0 ? sent : selected;
        } catch (ReflectiveOperationException exception) {
            return selected;
        }
    }

    /** Records a slot sent outside the game, so the game does not send it again. */
    static void markSlotSent(Minecraft minecraft, int slot) {
        try {
            carriedIndexField().setInt(minecraft.gameMode, slot);
        } catch (ReflectiveOperationException ignored) {
            // The game sends the slot once more on its next tick.
        }
    }

    // Also read from the network thread, so it is only published once accessible.
    private static Field carriedIndexField() throws NoSuchFieldException {
        Field field = carriedIndex;
        if (field == null) {
            field = MultiPlayerGameMode.class.getDeclaredField("carriedIndex");
            field.setAccessible(true);
            carriedIndex = field;
        }
        return field;
    }

    /** True while a dash press is on its way (levelling, swapping, or waiting to chain). */
    static boolean isDashing() {
        return dashesLeft > 0 || SWAP.busy();
    }

    static boolean hasLungeSpearInHotbar(Minecraft minecraft) {
        return minecraft.player != null && findLungeSpear(minecraft) >= 0;
    }

    private static boolean jabOnce(Minecraft minecraft) {
        try {
            if (startAttack == null) {
                startAttack = Minecraft.class.getDeclaredMethod("startAttack");
                startAttack.setAccessible(true);
            }
            if (minecraft.hitResult instanceof EntityHitResult hit) {
                AttackHook.markOwn(hit.getEntity().getId()); // a jab into someone is not a hit to swap
            }
            return Boolean.TRUE.equals(startAttack.invoke(minecraft));
        } catch (ReflectiveOperationException exception) {
            status = "Attack unavailable";
            return false;
        }
    }

    private static int findLungeSpear(Minecraft minecraft) {
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = minecraft.player.getInventory().getItem(slot);
            if (isSpear(stack) && hasLunge(stack)) {
                return slot;
            }
        }
        return -1;
    }

    private static boolean isSpear(ItemStack stack) {
        return stack.is(Items.WOODEN_SPEAR)
                || stack.is(Items.GOLDEN_SPEAR)
                || stack.is(Items.STONE_SPEAR)
                || stack.is(Items.COPPER_SPEAR)
                || stack.is(Items.IRON_SPEAR)
                || stack.is(Items.DIAMOND_SPEAR)
                || stack.is(Items.NETHERITE_SPEAR);
    }

    private static boolean hasLunge(ItemStack stack) {
        return lungeLevel(stack) > 0;
    }

    static int lungeLevel(ItemStack stack) {
        ItemEnchantments enchantments = stack.getEnchantments();
        for (Holder<Enchantment> enchantment : enchantments.keySet()) {
            if (enchantment.unwrapKey().filter(Enchantments.LUNGE::equals).isPresent()) {
                return enchantments.getLevel(enchantment);
            }
        }
        return 0;
    }

    /** The Lunge spear a dash would use, or an empty stack if the hotbar has none. */
    static ItemStack lungeSpear(Minecraft minecraft) {
        int slot = minecraft.player == null ? -1 : findLungeSpear(minecraft);
        return slot < 0 ? ItemStack.EMPTY : minecraft.player.getInventory().getItem(slot);
    }

    static boolean isEnabled() {
        return enabled;
    }

    static void toggleEnabled() {
        enabled = !enabled;
        if (!enabled) {
            cancel("Ready");
        }
    }

    static String dashCountLabel() {
        return DASH_COUNTS[dashCount - 1];
    }

    static void cycleDashCount() {
        dashCount = dashCount % DASH_COUNTS.length + 1;
    }

    // Used to restore saved settings.

    static int dashCount() {
        return dashCount;
    }

    static void setDashCount(int count) {
        dashCount = Math.max(1, Math.min(DASH_COUNTS.length, count));
    }

    static int flatMode() {
        return flatMode;
    }

    static void setFlatMode(int mode) {
        flatMode = Math.floorMod(mode, FLAT_MODES.length);
    }

    static void setEnabled(boolean on) {
        enabled = on;
    }

    static void setBinding(int key, int button) {
        keyCode = key;
        mouseButton = button;
    }

    static boolean isReadySound() {
        return readySound;
    }

    static void setReadySound(boolean on) {
        readySound = on;
    }

    static void toggleReadySound() {
        readySound = !readySound;
    }

    static boolean isOnlyFromWindCharge() {
        return onlyFromWindCharge;
    }

    static void setOnlyFromWindCharge(boolean on) {
        onlyFromWindCharge = on;
    }

    static void toggleOnlyFromWindCharge() {
        onlyFromWindCharge = !onlyFromWindCharge;
    }

    static boolean isFlatLungeOn() {
        return flatMode != 0;
    }

    static String flatModeLabel() {
        return FLAT_MODES[flatMode];
    }

    static void cycleFlatMode() {
        flatMode = (flatMode + 1) % FLAT_MODES.length;
    }

    /** Turns the module off when this injection is unloaded, swapping back mid-dash. */
    static void reset(Minecraft minecraft) {
        SWAP.cancel(minecraft);
        enabled = false;
        awaitingBinding = false;
        cancel("Ready");
    }

    static int keyCode() {
        return keyCode;
    }

    static int mouseButton() {
        return mouseButton;
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

    static String status() {
        return status;
    }

    static void setStatus(String newStatus) {
        status = newStatus;
    }

    /** Shows a warning on screen for a moment and records it in the log. */
    static void alert(String message) {
        status = message;
        alert = message;
        alertUntilMs = System.nanoTime() / 1_000_000 + ALERT_MS;
        SpearLog.log("alert: " + message);
    }

    /** The warning to show now, or null. */
    static String activeAlert() {
        return System.nanoTime() / 1_000_000 < alertUntilMs ? alert : null;
    }
}
