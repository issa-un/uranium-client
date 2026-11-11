package app;

import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.EntityHitResult;

/**
 * Slam Meter: while you fall with a mace ready (in your hand, or Mace Swap on with one in
 * your hotbar), shows under your crosshair how hard a slam would hit the player you are
 * coming down on, with the game's own mace math: the fall bonus (4 per block up to 3, 2 per
 * block up to 8, then 1), Density's extra per block, the crit, then their armor, armor
 * toughness and Protection (Breach cuts their armor). It ends with what the hit does: KILL,
 * POP (they hold a totem) or the health it takes. It only reads, it never acts.
 */
final class SlamMeter {
    private static final double MIN_FALL = 1.5;
    private static final double LOOK_RANGE = 8;
    private static final double MACE_BASE = 6;
    private static final int GLOW = 0xFF39FF14;
    private static final int SOFT = 0xFFC2E3BA;
    private static final int HOT = 0xFFFF5040;
    private static volatile boolean enabled;

    private SlamMeter() {}

    static void draw(GuiGraphicsExtractor graphics, Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (!enabled || player == null || minecraft.level == null || player.onGround()
                || player.isFallFlying() || player.fallDistance < MIN_FALL) {
            return;
        }
        ItemStack mace = mace(player);
        if (mace.isEmpty()) {
            return;
        }
        LivingEntity target = target(minecraft, player);
        double fall = player.fallDistance
                + (target == null ? 0 : Math.max(0, player.getY() - (target.getBoundingBox().maxY + 0.5)));
        double raw = rawDamage(player, mace, fall);
        String line;
        int color;
        if (target == null) {
            line = String.format(Locale.ROOT, "SLAM  fall %.1f  %.0f dmg", fall, raw);
            color = SOFT;
        } else {
            double dealt = afterArmor(target, raw, MaceSwap.enchantmentLevel(mace, Enchantments.BREACH));
            float health = target.getHealth() + target.getAbsorptionAmount();
            String verdict;
            if (dealt >= health) {
                boolean totem = target.getOffhandItem().is(Items.TOTEM_OF_UNDYING)
                        || target.getMainHandItem().is(Items.TOTEM_OF_UNDYING);
                verdict = totem ? "POP" : "KILL";
                color = HOT;
            } else {
                verdict = String.format(Locale.ROOT, "-%.1f of %.1f hp", dealt, health);
                color = GLOW;
            }
            line = String.format(Locale.ROOT, "SLAM  fall %.1f  ~%.1f  %s", fall, dealt, verdict);
        }
        int x = graphics.guiWidth() / 2 - minecraft.font.width(line) / 2;
        int y = graphics.guiHeight() / 2 + 14;
        graphics.fill(x - 3, y - 2, x + minecraft.font.width(line) + 3, y + 9, 0x88000000);
        graphics.text(minecraft.font, line, x, y, color);
    }

    /** The mace a slam would go out with: the one held, or Mace Swap's pick. */
    private static ItemStack mace(LocalPlayer player) {
        if (player.getMainHandItem().is(Items.MACE)) {
            return player.getMainHandItem();
        }
        if (!MaceSwap.isEnabled()) {
            return ItemStack.EMPTY;
        }
        int slot = MaceSwap.bestMace(player);
        return slot < 0 ? ItemStack.EMPTY : player.getInventory().getItem(slot);
    }

    /** Who you are coming down on: under your crosshair, else the closest player below you. */
    private static LivingEntity target(Minecraft minecraft, LocalPlayer player) {
        if (minecraft.hitResult instanceof EntityHitResult hit && hit.getEntity() instanceof LivingEntity living) {
            return living;
        }
        LivingEntity best = null;
        double bestDistance = LOOK_RANGE;
        for (Player other : minecraft.level.players()) {
            if (other == player || !other.isAlive() || other.isSpectator() || VelocityHud.isFriend(other)
                    || other.getY() > player.getY()) {
                continue;
            }
            double distance = Math.hypot(other.getX() - player.getX(), other.getZ() - player.getZ());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = other;
            }
        }
        return best;
    }

    /** The slam before armor: the mace's own damage as a crit, the fall bonus and Density. */
    static double rawDamage(LocalPlayer player, ItemStack mace, double fall) {
        double base = player.getMainHandItem().is(Items.MACE)
                ? player.getAttributeValue(Attributes.ATTACK_DAMAGE) : MACE_BASE;
        double bonus = fall <= 3 ? 4 * fall : fall <= 8 ? 12 + 2 * (fall - 3) : 22 + (fall - 8);
        double density = 0.5 * MaceSwap.enchantmentLevel(mace, Enchantments.DENSITY) * fall;
        return base * 1.5 + bonus + density;
    }

    /** The game's armor formula, with Breach cutting armor 15% a level, then Protection. */
    static double afterArmor(LivingEntity target, double damage, int breach) {
        double armor = target.getArmorValue() * Math.max(0, 1 - 0.15 * breach);
        double toughness = target.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
        double cut = Math.max(armor * 0.2, Math.min(20, armor - damage / (2 + toughness / 4)));
        double dealt = damage * (1 - cut / 25);
        int protection = 0;
        for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            protection += MaceSwap.enchantmentLevel(target.getItemBySlot(slot), Enchantments.PROTECTION);
        }
        return dealt * (1 - Math.min(20, protection) / 25.0);
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
    }
}
