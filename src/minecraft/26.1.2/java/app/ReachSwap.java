package app;

import java.util.Collection;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.AttackRange;
import net.minecraft.world.item.component.PiercingWeapon;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Reach Swap: when you click a player or mob that is out of your weapon's reach but within a
 * spear's (2 to 4.5 blocks along your view, not through blocks), the switch to a spear from
 * your hotbar and its jab go out together, in one step, so the jab always registers.
 *
 * Why together: switching items resets your attack charge on the server once it next updates
 * you (once per game tick), and a spear only jabs at full charge, so a spear switched to
 * earlier cannot hit for its whole charge time. Sent together, the server handles the jab
 * before that update: it is charged by, and deals the damage of, the weapon you held (your
 * sword or mace), at the spear's reach. Everything else about the hit comes from the spear:
 * its enchantments, and no mace smash, Density or Breach.
 *
 * Mode: Jab switches back to your weapon after SHOW_MS (or Switch back, if longer), so you
 * see the hotbar go to the spear and back; the jab itself has already gone out with your
 * weapon's charge. Switch only keeps the spear in your hand
 * while your crosshair stays on someone out of your weapon's reach but in the spear's, and
 * switches back (after Switch back) once you look away or they come into your weapon's
 * reach. Nothing is ever hit without your click. Each jab is logged with the health the
 * target lost.
 */
final class ReachSwap {
    // How long to wait for the target's health to arrive before logging the damage.
    private static final long DAMAGE_WAIT_MS = 400;
    private static final String[] MODES = {"Jab", "Switch only"};
    private static final int SWITCH_ONLY = 1;
    // Jab: how long the spear stays in your hand after the jab, so the swap shows.
    private static final long SHOW_MS = 120;
    private static volatile boolean enabled;
    private static volatile int mode;
    // Switch only: target gone or in your weapon's reach -> your weapon back.
    static final Num SWITCH_BACK = Num.ms(0, 0, 500);
    // Render thread only: the spear Switch only left in your hand, and your weapon's slot.
    private static int switchedTo = -1;
    private static int fromSlot = -1;
    private static long backAtMs = -1;
    // Render thread only: the last jab's target, to log what it lost.
    private static LivingEntity watched;
    private static float watchedHealth;
    private static long watchUntilMs;
    private static String watchedFrom = "";

    private ReachSwap() {}

    /** Your attack click: true if it became a spear jab, so the game must not also swing. */
    static boolean handleAttackClick(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (!enabled || player == null || minecraft.gameMode == null || Mc.screen(minecraft) != null
                || !SpearSettings.combatAllowed(minecraft) || player.isUsingItem()) {
            return false;
        }
        int previousSlot = player.getInventory().getSelectedSlot();
        int spearSlot = findSpear(player);
        if (spearSlot < 0 || spearSlot == previousSlot) {
            return false; // no spear, or it is already in your hand: the game's own jab
        }
        ItemStack weapon = player.getMainHandItem();
        ItemStack spear = player.getInventory().getItem(spearSlot);
        LivingEntity target = firstTarget(player, spear);
        if (target == null || inWeaponReach(player, weapon, target)) {
            return false; // nobody there, or in your own reach: a normal hit
        }
        boolean jabbed = false;
        try {
            player.getInventory().setSelectedSlot(spearSlot);
            // Charged by your attack speed of the moment, which is still the weapon's.
            if (player.cannotAttackWithItem(spear, 0)) {
                MinecraftSpearDash.setStatus("Reach swap: not charged");
            } else {
                watch(target, weapon);
                // Sends the spear's slot, then the jab, back to back.
                Mc.piercingAttack(minecraft.gameMode, spear, spear.get(DataComponents.PIERCING_WEAPON));
                Mc.swingAttack(player, InteractionHand.MAIN_HAND);
                jabbed = true;
            }
        } finally {
            if (!jabbed) {
                player.getInventory().setSelectedSlot(previousSlot);
                MinecraftSpearDash.sendSelectedSlot(minecraft, false);
            }
        }
        if (!jabbed) {
            return false;
        }
        // The spear stays in your hand on purpose: for good (Switch only), or for a moment
        // you can see (Jab), and the switch back comes from update.
        MinecraftInputController.stopGuard();
        MinecraftSpearDash.sendSelectedSlot(minecraft, false);
        switchedTo = spearSlot;
        fromSlot = previousSlot;
        backAtMs = mode == SWITCH_ONLY ? -1
                : System.nanoTime() / 1_000_000 + Math.max(SHOW_MS, SWITCH_BACK.pick());
        MinecraftSpearDash.setStatus("Reach jab");
        SpearLog.log("reach swap: hotbar " + (previousSlot + 1) + " -> spear " + (spearSlot + 1)
                + (mode == SWITCH_ONLY ? ", spear kept, " : " -> back, ")
                + String.format("%.2f", player.distanceTo(target)) + " blocks to " + target.getName().getString());
        return true;
    }

    /** Called from the input loop: Switch only's switch back, and the jab damage log. */
    static void update(Minecraft minecraft) {
        followTarget(minecraft);
        logDamage();
    }

    /**
     * Switch only: keeps the spear while your crosshair is on someone out of your weapon's
     * reach but in the spear's, and puts your weapon back otherwise. Jab: puts your weapon
     * back once the moment the spear is shown has passed.
     */
    private static void followTarget(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (switchedTo < 0 || player == null) {
            switchedTo = -1;
            return;
        }
        if (player.getInventory().getSelectedSlot() != switchedTo) {
            switchedTo = -1; // you picked a slot yourself
            return;
        }
        if (Mc.screen(minecraft) != null) {
            return;
        }
        ItemStack spear = player.getInventory().getItem(switchedTo);
        ItemStack weapon = player.getInventory().getItem(fromSlot);
        LivingEntity target = !enabled || mode != SWITCH_ONLY || !spear.has(DataComponents.PIERCING_WEAPON)
                ? null : firstTarget(player, spear);
        long now = System.nanoTime() / 1_000_000;
        if (target != null && !inWeaponReach(player, weapon, target)) {
            backAtMs = -1;
            return;
        }
        if (backAtMs < 0) {
            backAtMs = now + SWITCH_BACK.pick();
        }
        if (now >= backAtMs) {
            select(minecraft, fromSlot);
            SpearLog.log("reach swap: " + (mode != SWITCH_ONLY ? "jab shown" : target == null ? "looked away"
                    : "in weapon reach") + ", back to hotbar " + (fromSlot + 1));
            switchedTo = -1;
            backAtMs = -1;
        }
    }

    /** Whether the weapon reaches the target by itself (the same test the server makes). */
    private static boolean inWeaponReach(LocalPlayer player, ItemStack weapon, LivingEntity target) {
        return player.isWithinAttackRange(weapon, target.getBoundingBox(), 0);
    }

    private static void select(Minecraft minecraft, int slot) {
        minecraft.player.getInventory().setSelectedSlot(slot);
        MinecraftSpearDash.sendSelectedSlot(minecraft, false);
        MinecraftInputController.stopGuard(); // our own switch, not a server correction
    }

    private static void logDamage() {
        if (watched == null || System.nanoTime() / 1_000_000 < watchUntilMs) {
            return;
        }
        float lost = watchedHealth - (watched.getHealth() + watched.getAbsorptionAmount());
        SpearLog.log("reach swap result: " + watched.getName().getString() + " lost "
                + String.format("%.1f", lost) + " health (" + String.format("%.1f", lost / 2) + " hearts), damage from "
                + watchedFrom);
        MinecraftSpearDash.setStatus("Reach jab: " + String.format("%.1f", lost / 2) + " hearts");
        watched = null;
    }

    private static void watch(LivingEntity target, ItemStack held) {
        watched = target;
        watchedHealth = target.getHealth() + target.getAbsorptionAmount();
        watchUntilMs = System.nanoTime() / 1_000_000 + DAMAGE_WAIT_MS;
        watchedFrom = held.isEmpty() ? "an empty hand" : held.getHoverName().getString();
    }

    /**
     * The first player or mob the server will hit with the spear's jab: the same search
     * along your view, within the spear's reach and not through blocks. Friends are skipped.
     */
    private static LivingEntity firstTarget(LocalPlayer player, ItemStack spear) {
        AttackRange range = player.getAttackRangeWith(spear);
        Collection<EntityHitResult> hits = ProjectileUtil.getHitEntitiesAlong(player, range,
                entity -> entity instanceof LivingEntity && PiercingWeapon.canHitEntity(player, entity),
                ClipContext.Block.COLLIDER).map(block -> List.<EntityHitResult>of(), entities -> entities);
        for (EntityHitResult hit : hits) {
            if (hit.getType() == HitResult.Type.ENTITY && hit.getEntity() instanceof LivingEntity living
                    && !(living instanceof Player other && VelocityHud.isFriend(other))) {
                return living;
            }
        }
        return null;
    }

    /** A spear in the hotbar, preferring one without Lunge so the jab does not also push you. */
    private static int findSpear(LocalPlayer player) {
        int lungeSpear = -1;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.has(DataComponents.PIERCING_WEAPON)) {
                continue;
            }
            if (MinecraftSpearDash.lungeLevel(stack) == 0) {
                return slot;
            }
            if (lungeSpear < 0) {
                lungeSpear = slot;
            }
        }
        return lungeSpear;
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

    static int mode() {
        return mode;
    }

    static String modeLabel() {
        return MODES[mode];
    }

    static void setMode(int newMode) {
        mode = Math.floorMod(newMode, MODES.length);
    }

    static void cycleMode() {
        mode = (mode + 1) % MODES.length;
    }

    /** Turns the module off when this injection is unloaded, giving your weapon back. */
    static void reset(Minecraft minecraft) {
        if (switchedTo >= 0 && minecraft.player != null
                && minecraft.player.getInventory().getSelectedSlot() == switchedTo) {
            select(minecraft, fromSlot);
        }
        switchedTo = -1;
        enabled = false;
        watched = null;
    }
}
