package app;

import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.phys.Vec3;

/**
 * Elytra Mace: diving at a player with an elytra on and a mace in your hotbar, look at them
 * and your elytra comes off by itself as you close in, early enough that the server has
 * seen you stop gliding by the time they are in reach. Your hit then counts as a mace slam
 * (a slam while gliding does not). Only while you are above them and falling fast: a flat
 * glide keeps resetting your fall to 1 block, so there would be no slam to save.
 *
 * The elytra swaps with the best chestplate in your hotbar (or into an empty hotbar slot)
 * in one inventory click, with no slot change. With Glide again on, once you have hit (or
 * missed) and are still in the air, it goes back on and you glide again.
 */
final class ElytraMace {
    private static final int CHEST_MENU_SLOT = 6;
    private static final double LOOK_ANGLE = 12;
    private static final double MIN_ABOVE = 1.5;
    // Falling at least this fast (blocks/tick): slower, the game keeps your fall at 1 block.
    private static final double MIN_DIVE = 0.5;
    private static final double REACH = 3;
    // Spare ticks on top of your ping before you reach them, for the server's tick.
    private static final double SPARE_TICKS = 2;
    private static final long GIVE_UP_MS = 1500;
    private static final long REARM_MS = 1000;
    private static volatile boolean enabled;
    private static volatile boolean glideAgain = true;
    // Render thread only.
    private static boolean dropped;
    private static int elytraSlot = -1;
    private static long droppedMs;
    private static long lastDoneMs;
    private static Player target;
    private static int targetHurt;
    private static boolean reglide;

    private ElytraMace() {}

    /** Called from the input loop. */
    static void update(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.gameMode == null || minecraft.level == null) {
            return;
        }
        long now = System.nanoTime() / 1_000_000;
        if (reglide) {
            reglide = false;
            startGliding(minecraft, player);
        }
        if (dropped) {
            watchHit(minecraft, player, now);
            return;
        }
        if (!enabled || Mc.screen(minecraft) != null || !SpearSettings.combatAllowed(minecraft)
                || now - lastDoneMs < REARM_MS || !player.isFallFlying()
                || !player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA) || MaceSwap.bestMace(player) < 0
                // The hit has to go out with a mace: held, or swapped in by Mace Swap.
                || !player.getMainHandItem().is(Items.MACE) && !MaceSwap.isEnabled()) {
            return;
        }
        double fall = -player.getDeltaMovement().y;
        if (fall < MIN_DIVE) {
            return;
        }
        Player looked = lookedAt(minecraft, player);
        if (looked == null) {
            return;
        }
        double distance = player.getEyePosition().distanceTo(looked.getBoundingBox().getCenter());
        double speed = player.getDeltaMovement().length();
        double leadTicks = ShieldBreak.pingMs(minecraft) / 50.0 + SPARE_TICKS;
        if (distance > REACH + speed * leadTicks) {
            return;
        }
        int slot = swapSlot(player);
        if (slot < 0) {
            MinecraftSpearDash.setStatus("No chestplate or free hotbar slot");
            lastDoneMs = now;
            return;
        }
        swapChest(minecraft, player, slot);
        dropped = true;
        elytraSlot = slot;
        droppedMs = now;
        target = looked;
        targetHurt = looked.hurtTime;
        MinecraftSpearDash.setStatus("Elytra off");
        SpearLog.log(String.format(Locale.ROOT,
                "elytra mace: elytra off %.1f blocks from %s, %.1f blocks above, falling %.2f/tick, ping %d ms",
                distance, looked.getName().getString(), player.getY() - looked.getY(), fall,
                ShieldBreak.pingMs(minecraft)));
    }

    /** After the drop: the hit landing, a miss, or the ground ends it; then maybe glide again. */
    private static void watchHit(Minecraft minecraft, LocalPlayer player, long now) {
        boolean hit = target != null && target.hurtTime > targetHurt;
        if (target != null) {
            targetHurt = target.hurtTime;
        }
        boolean landed = player.onGround() || player.isInWater();
        if (!hit && !landed && now - droppedMs < GIVE_UP_MS) {
            return;
        }
        dropped = false;
        lastDoneMs = now;
        SpearLog.log("elytra mace: " + (hit ? "hit landed" : landed ? "landed without a hit" : "no hit in time")
                + String.format(Locale.ROOT, " (fall %.1f blocks)", player.fallDistance));
        if (glideAgain && !landed && elytraSlot >= 0
                && player.getInventory().getItem(elytraSlot).is(Items.ELYTRA)) {
            swapChest(minecraft, player, elytraSlot);
            reglide = true; // next tick, once the elytra shows as worn
        }
        target = null;
    }

    /** Takes off again in the air, as pressing jump would. */
    private static void startGliding(Minecraft minecraft, LocalPlayer player) {
        if (player.onGround() || !player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)) {
            return;
        }
        ClientPacketListener connection = minecraft.getConnection();
        if (connection != null && player.tryToStartFallFlying()) {
            connection.send(new ServerboundPlayerCommandPacket(player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
            MinecraftSpearDash.setStatus("Gliding again");
        }
    }

    /** One inventory click: the chest slot swaps with this hotbar slot. */
    private static void swapChest(Minecraft minecraft, LocalPlayer player, int hotbarSlot) {
        minecraft.gameMode.handleContainerInput(player.inventoryMenu.containerId, CHEST_MENU_SLOT, hotbarSlot,
                ContainerInput.SWAP, player);
    }

    /** The hotbar slot with your best chestplate, else an empty one; -1 if neither. */
    private static int swapSlot(LocalPlayer player) {
        int best = -1;
        double bestArmor = 0;
        int empty = -1;
        int held = player.getInventory().getSelectedSlot();
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.isEmpty()) {
                empty = empty < 0 && i != held ? i : empty; // never into the hand you hit with
                continue;
            }
            double armor = chestArmor(stack);
            if (armor > bestArmor) {
                best = i;
                bestArmor = armor;
            }
        }
        return best >= 0 ? best : empty;
    }

    private static double chestArmor(ItemStack stack) {
        Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
        ItemAttributeModifiers modifiers = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
        if (equippable == null || equippable.slot() != EquipmentSlot.CHEST || modifiers == null) {
            return 0;
        }
        return modifiers.compute(Attributes.ARMOR, 0, EquipmentSlot.CHEST);
    }

    /** The player you are looking at (within LOOK_ANGLE), below you; null if none. */
    private static Player lookedAt(Minecraft minecraft, LocalPlayer player) {
        Vec3 eye = player.getEyePosition();
        Vec3 view = player.getViewVector(1.0F);
        Player best = null;
        double bestAngle = LOOK_ANGLE;
        for (Player other : minecraft.level.players()) {
            if (other == player || !other.isAlive() || other.isSpectator() || VelocityHud.isFriend(other)
                    || player.getY() - other.getY() < MIN_ABOVE) {
                continue;
            }
            Vec3 to = other.getBoundingBox().getCenter().subtract(eye).normalize();
            double angle = Math.toDegrees(Math.acos(Mth.clamp(view.dot(to), -1, 1)));
            if (angle < bestAngle) {
                bestAngle = angle;
                best = other;
            }
        }
        return best;
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

    static boolean isGlideAgain() {
        return glideAgain;
    }

    static void setGlideAgain(boolean on) {
        glideAgain = on;
    }

    static void toggleGlideAgain() {
        glideAgain = !glideAgain;
    }

    /** Turns the module off when this injection is unloaded. */
    static void reset() {
        enabled = false;
        dropped = false;
        reglide = false;
        target = null;
    }
}
