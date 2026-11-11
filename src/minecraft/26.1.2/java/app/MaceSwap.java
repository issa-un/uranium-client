package app;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;

/**
 * Mace Swap: a slam worth the mace goes out with one. The attack packet is caught on its way
 * out (see AttackHook) and the mace is selected for it, the attack goes out and your slot
 * goes back, in one write: no delay and nothing to set. Only a drop of WORTH_FALL or more
 * swaps, since anything less does more with the weapon you hold. A Density mace is picked,
 * else a Breach mace, else any mace. Hits you already make with that mace, hits into a
 * shield raised toward you (they would waste the slam) and a second attack on the same
 * target right after are left alone.
 */
final class MaceSwap {
    // A mace hit is a slam once you have fallen more than this (MaceItem.canSmashAttack).
    private static final double SMASH_FALL_DISTANCE = 1.5;
    // Attacks on the same target this close together count as one hit.
    private static final long REPEAT_MS = 50;
    // The fall a slam needs before the mace beats your weapon: through full netherite, a
    // 3.8-block slam did 3.4 health and a 5.4-block one 9.6 (logged in fights).
    static final double WORTH_FALL = 4.5;
    private static volatile boolean enabled;
    // The last swap, on the network thread only.
    private static int lastTargetId = -1;
    private static long lastSwapMs;

    private MaceSwap() {}

    /**
     * An attack your client sends, on the network thread (see AttackHook): the mace to
     * select for that one packet, or -1 to let it go out as it is.
     */
    static int maceForAnyAttack(Minecraft minecraft, LivingEntity target, int heldSlot) {
        LocalPlayer player = minecraft.player;
        if (player == null) {
            return -1;
        }
        // Every hit that goes out holding a mace is logged too, swapped or not (see MaceLog).
        boolean holdingMace = heldSlot >= 0 && heldSlot < 9 && player.getInventory().getItem(heldSlot).is(Items.MACE);
        if (!enabled || Mc.screen(minecraft) != null || !SpearSettings.combatAllowed(minecraft)) {
            if (holdingMace) {
                MaceLog.attack(minecraft, target, heldSlot, heldSlot, "held, Mace Swap off");
            }
            return -1;
        }
        // A raised shield takes the hit, slam and all; leave it to Shield Break or a plain hit.
        if (ShieldBreak.blockingToward(minecraft, target)) {
            MaceLog.attack(minecraft, target, holdingMace ? heldSlot : -1, heldSlot, "not swapped: shield up toward you");
            return -1;
        }
        // Your client can send two attacks for one click; the second one lands in the
        // target's damage immunity anyway, so one swap is enough.
        long now = System.nanoTime() / 1_000_000;
        if (target.getId() == lastTargetId && now - lastSwapMs < REPEAT_MS) {
            return -1;
        }
        int mace = bestMace(player);
        if (mace < 0) {
            return -1;
        }
        // Only a slam worth more than about 3 hearts gets the mace: a plain mace hit or a short
        // drop does next to nothing through armor, less than the weapon you hold.
        if (player.fallDistance < WORTH_FALL || player.isFallFlying()) {
            if (holdingMace) {
                MaceLog.attack(minecraft, target, heldSlot, heldSlot, "held, fall under " + WORTH_FALL);
            } else if (player.fallDistance > SMASH_FALL_DISTANCE || player.isFallFlying()) {
                SpearLog.log(String.format(java.util.Locale.ROOT,
                        "mace swap: not swapped on %s, %s", target.getName().getString(), player.isFallFlying()
                                ? "gliding (no slam while flying an elytra)"
                                : "fell only " + String.format(java.util.Locale.ROOT, "%.1f", player.fallDistance)
                                        + " blocks, a swap needs " + WORTH_FALL));
            }
            return -1;
        }
        if (mace == heldSlot) {
            MaceLog.attack(minecraft, target, mace, heldSlot, "already held");
            return -1;
        }
        lastTargetId = target.getId();
        lastSwapMs = now;
        MaceLog.attack(minecraft, target, mace, heldSlot, "swapped in");
        return mace;
    }

    /** After AttackHook sent an attack with the mace, on the render thread. */
    static void afterAnyAttack(Minecraft minecraft, int previousSlot, int maceSlot, LivingEntity target) {
        LocalPlayer player = minecraft.player;
        if (player == null) {
            return;
        }
        MinecraftInputController.guardSwitchBack(previousSlot, maceSlot);
        String kind = describe(player.getInventory().getItem(maceSlot));
        MinecraftSpearDash.setStatus(kind + " hit");
        SpearLog.log("mace swap: hotbar " + (previousSlot + 1) + " -> " + kind + " " + (maceSlot + 1)
                + " -> back to " + (previousSlot + 1) + (isSlam(player) ? " (slam)" : "")
                + " on " + target.getName().getString());
    }

    /** The best mace for a hit: Density for a slam, else Breach, else any mace; -1 if you have none. */
    static int bestMace(LocalPlayer player) {
        if (isSlam(player)) {
            int density = findMace(player, Enchantments.DENSITY);
            if (density >= 0) {
                return density;
            }
        }
        int breach = findMace(player, Enchantments.BREACH);
        return breach >= 0 ? breach : findMace(player, null);
    }

    static boolean isSlam(LocalPlayer player) {
        return player.fallDistance > SMASH_FALL_DISTANCE && !player.isFallFlying();
    }

    /** First hotbar mace with the enchantment, or any mace if it is null. */
    private static int findMace(LocalPlayer player, ResourceKey<Enchantment> enchantment) {
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(Items.MACE) && (enchantment == null || hasEnchantment(stack, enchantment))) {
                return slot;
            }
        }
        return -1;
    }

    private static boolean hasEnchantment(ItemStack stack, ResourceKey<Enchantment> enchantment) {
        return enchantmentLevel(stack, enchantment) > 0;
    }

    static int enchantmentLevel(ItemStack stack, ResourceKey<Enchantment> enchantment) {
        ItemEnchantments enchantments = stack.getEnchantments();
        for (Holder<Enchantment> holder : enchantments.keySet()) {
            if (holder.unwrapKey().filter(enchantment::equals).isPresent()) {
                return enchantments.getLevel(holder);
            }
        }
        return 0;
    }

    static String describe(ItemStack mace) {
        if (hasEnchantment(mace, Enchantments.DENSITY)) {
            return "Density mace";
        }
        return hasEnchantment(mace, Enchantments.BREACH) ? "Breach mace" : "Mace";
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

    /** Turns the module off when this injection is unloaded. */
    static void reset() {
        enabled = false;
    }
}
