package app;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.phys.EntityHitResult;

/**
 * Auto Weapon: when your attack click lands on something living while you hold something
 * that is not a weapon (blocks, food, a pickaxe), switches to the hardest-hitting weapon in
 * your hotbar first, so that click already hits with it, and stays on it. A weapon is
 * anything that adds attack damage over a bare hand; the pick is by that damage. If you
 * already hold one, nothing changes, so your own sword/axe choice is kept. Auto Stun and
 * Mace Swap take the click first when they act. With Trigger on Any attack, it acts on
 * every attack your client sends (see AttackHook) instead of your click.
 */
final class AutoWeapon {
    // Anything at or below this is not worth switching for (a bare hand does 1).
    private static final double MIN_WEAPON_DAMAGE = 2.0;
    private static volatile boolean enabled;
    private static volatile boolean anyAttack;
    // Click -> weapon selected, weapon selected -> hit. At 0 the game's own hit uses it.
    static final Num SWAP_DELAY = Num.ms(0, 0, 300);
    static final Num HIT_DELAY = Num.ms(0, 0, 300);
    static final Num CHANCE = Num.percent(100);
    private static final SwapAction ACTION = new SwapAction();

    private AutoWeapon() {}

    /**
     * Your attack click: switches before the game turns it into a hit. With a delay on, the
     * click is taken: the switch and the hit follow after their delays, and true is returned
     * so the game does not also hit with what you hold.
     */
    static boolean onAttackClick(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (!enabled || anyAttack || player == null || Mc.screen(minecraft) != null || !SpearSettings.combatAllowed(minecraft)
                || !(minecraft.hitResult instanceof EntityHitResult hit) || !(hit.getEntity() instanceof LivingEntity target)) {
            return false;
        }
        if (ACTION.busy()) {
            return true; // the hit is already on its way
        }
        int best = bestWeapon(player);
        if (best < 0 || !CHANCE.roll()) {
            return false;
        }
        if (!isDelayed()) {
            player.getInventory().setSelectedSlot(best);
            SpearDash.sendSelectedSlot(minecraft, false);
            MinecraftInputController.stopGuard();
            SpearLog.log("auto weapon: hotbar " + (best + 1) + " (" + damage(player.getInventory().getItem(best)) + " damage)");
            return false;
        }
        ACTION.start(minecraft, best, false, SWAP_DELAY, HIT_DELAY, null, game -> {
            if (!target.isAlive() || !game.player.isWithinAttackRange(
                    game.player.getMainHandItem(), target.getBoundingBox(), 0)) {
                return false;
            }
            AttackHook.markOwn(target.getId());
            game.gameMode.attack(game.player, target);
            Mc.swingAttack(game.player, InteractionHand.MAIN_HAND);
            return true;
        }, AutoWeapon::finished);
        return true;
    }

    /**
     * With delays on, an attack AttackHook held back, on the render thread: switches after
     * Swap delay and sends the held attack after Hit delay.
     */
    static void runFromAttack(Minecraft minecraft, int weapon, ServerboundAttackPacket attack) {
        if (ACTION.busy() || minecraft.player == null || Mc.screen(minecraft) != null) {
            AttackHook.resend(minecraft, attack);
            return;
        }
        ACTION.start(minecraft, weapon, false, SWAP_DELAY, HIT_DELAY, null, game -> {
            AttackHook.resend(game, attack);
            return true;
        }, AutoWeapon::finished);
    }

    /** Called from the input loop: the steps of a delayed switch. */
    static void update(Minecraft minecraft) {
        ACTION.update(minecraft);
    }

    /** True if any delay is on, so an attack caught by AttackHook has to be held back. */
    static boolean isDelayed() {
        return !SWAP_DELAY.isZero() || !HIT_DELAY.isZero();
    }

    private static void finished(Minecraft minecraft, boolean acted, int fromSlot, int weapon) {
        if (minecraft.player != null && minecraft.player.getInventory().getSelectedSlot() == weapon) {
            MinecraftInputController.stopGuard();
            SpearLog.log("auto weapon (delayed): hotbar " + (weapon + 1)
                    + (acted ? ", hit" : ", no hit"));
        }
    }

    /**
     * Any attack on something living, with Trigger on Any attack, on the network thread (see
     * AttackHook): the weapon to select before it goes out, or -1 to leave it.
     */
    static int weaponForAnyAttack(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (!enabled || !anyAttack || player == null || Mc.screen(minecraft) != null || ACTION.busy()
                || !SpearSettings.combatAllowed(minecraft)) {
            return -1;
        }
        int best = bestWeapon(player);
        return best >= 0 && CHANCE.roll() ? best : -1;
    }

    /**
     * After AttackHook sent the weapon's slot, on the render thread: the game selects it too.
     * If you picked another slot meanwhile, the game sends yours, and that is kept.
     */
    static void afterAnyAttack(Minecraft minecraft, int heldSlot, int weapon) {
        LocalPlayer player = minecraft.player;
        if (player == null || player.getInventory().getSelectedSlot() != heldSlot) {
            return;
        }
        player.getInventory().setSelectedSlot(weapon);
        SpearDash.markSlotSent(minecraft, weapon);
        MinecraftInputController.stopGuard();
        SpearLog.log("auto weapon (any attack): hotbar " + (weapon + 1)
                + " (" + damage(player.getInventory().getItem(weapon)) + " damage)");
    }

    /** The hardest-hitting hotbar weapon if you hold something that is not one, else -1. */
    private static int bestWeapon(LocalPlayer player) {
        if (damage(player.getMainHandItem()) >= MIN_WEAPON_DAMAGE) {
            return -1;
        }
        int best = -1;
        double bestDamage = MIN_WEAPON_DAMAGE;
        for (int slot = 0; slot < 9; slot++) {
            double damage = damage(player.getInventory().getItem(slot));
            if (damage > bestDamage) {
                best = slot;
                bestDamage = damage;
            }
        }
        return best;
    }

    /** The attack damage the item gives in your main hand, counting a bare hand's 1. */
    static double damage(ItemStack stack) {
        ItemAttributeModifiers modifiers = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
        return modifiers == null ? 1.0 : modifiers.compute(Attributes.ATTACK_DAMAGE, 1.0, EquipmentSlot.MAINHAND);
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

    static boolean isAnyAttack() {
        return anyAttack;
    }

    static void setAnyAttack(boolean on) {
        anyAttack = on;
    }

    static void toggleAnyAttack() {
        anyAttack = !anyAttack;
    }

    static String triggerLabel() {
        return AttackHook.triggerLabel(anyAttack);
    }

    static void reset(Minecraft minecraft) {
        ACTION.cancel(minecraft);
        enabled = false;
    }
}
