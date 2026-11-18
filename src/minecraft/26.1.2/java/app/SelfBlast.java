package app;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.Vec3;

/**
 * Your own TNT cart's blast on you, the game's way, so a cart never goes down where it
 * would kill you (low on health, say under 3 hearts, too close to it).
 * The game: a TNT cart set off by a burning arrow blows with power 4 + 1.5 * (a random 0 to
 * 1) * the arrow's speed (blocks a tick, up to 5). A crossbow arrow flies at about 3.15, so
 * a shot cart blows at up to power 8.7: a reach of 2 * power, over 17 blocks. This takes the
 * worst roll, since the roll can't be known. impact = (1 - distance / reach) * how much of
 * you it sees; damage = (impact^2 + impact) / 2 * 7 * reach + 1, half again on Hard, less
 * on Easy. Then your armor (armor and toughness),
 * Protection and Blast Protection (each level of Blast Protection counts twice, capped at
 * 20, 4% each) and Resistance take their cut.
 */
final class SelfBlast {
    private static final double BASE_POWER = 4.0;
    // How fast a crossbow arrow flies (blocks a tick); the cart blows harder the faster it is.
    static final double CROSSBOW_SPEED = 3.15;
    private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
            EquipmentSlot.FEET};

    private SelfBlast() {}

    // Self guard: 0 Off; 1 Block (High Tier puts a block between you and the cart, jumps and
    // shoots over it); 2 Block + refuse (also no cart where the blast would still kill you).
    private static final String[] MODES = {"Off", "Block", "Block + refuse"};
    private static volatile int mode = 1;

    static String modeLabel() {
        return MODES[mode];
    }

    static int mode() {
        return mode;
    }

    static void setMode(int value) {
        mode = Math.floorMod(value, MODES.length);
    }

    static void cycleMode() {
        mode = (mode + 1) % MODES.length;
    }

    /** Where a TNT cart on this rail blows: the cart's own position, its bottom middle. */
    static Vec3 cartCenter(net.minecraft.core.BlockPos rail) {
        return Vec3.atBottomCenterOf(rail).add(0, 0.0625, 0);
    }

    /**
     * The damage a TNT cart blowing at this point, set off by an arrow this fast, does to
     * you after armor and enchantments (the worst roll).
     */
    static double damage(Minecraft minecraft, LocalPlayer player, Vec3 center, double arrowSpeed) {
        double roll = 0.5; // the random boost's middle
        double power = BASE_POWER + roll * 1.5 * Math.min(5, Math.max(0, arrowSpeed));
        double reach = power * 2;
        double distance = Math.sqrt(player.distanceToSqr(center)) / reach;
        if (distance > 1) {
            return 0;
        }
        double seen = net.minecraft.world.level.ServerExplosion.getSeenPercent(center, player);
        double impact = (1 - distance) * seen;
        double damage = (impact * impact + impact) / 2 * 7 * reach + 1;
        Difficulty difficulty = minecraft.level.getDifficulty();
        if (difficulty == Difficulty.PEACEFUL) {
            damage = 0;
        } else if (difficulty == Difficulty.EASY) {
            damage = Math.min(damage / 2 + 1, damage);
        } else if (difficulty == Difficulty.HARD) {
            damage *= 1.5;
        }
        // armor
        double armor = player.getArmorValue();
        double toughness = player.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
        double cut = Math.max(armor * 0.2, Math.min(20, armor - damage / (2 + toughness / 4)));
        damage *= 1 - cut / 25;
        // Protection and Blast Protection
        int points = 0;
        for (EquipmentSlot slot : ARMOR) {
            ItemStack piece = player.getItemBySlot(slot);
            points += MaceSwap.enchantmentLevel(piece, Enchantments.PROTECTION);
            points += 2 * MaceSwap.enchantmentLevel(piece, Enchantments.BLAST_PROTECTION);
        }
        damage *= 1 - Math.min(20, points) / 25.0;
        // Resistance
        var resistance = player.getEffect(MobEffects.RESISTANCE);
        if (resistance != null) {
            damage *= Math.max(0, 1 - 0.2 * (resistance.getAmplifier() + 1));
        }
        return Math.max(0, damage);
    }

    /**
     * Why a cart at this point would be a bad idea for you, or null if it's fine: it would
     * kill you, or pop your totem.
     */
    static String danger(Minecraft minecraft, LocalPlayer player, Vec3 center, double arrowSpeed) {
        if (player.isCreative() || mode != 2) {
            return null;
        }
        double damage = damage(minecraft, player, center, arrowSpeed);
        double health = player.getHealth() + player.getAbsorptionAmount();
        if (damage < health - 0.5) {
            return null;
        }
        boolean totem = player.getOffhandItem().is(Items.TOTEM_OF_UNDYING)
                || player.getMainHandItem().is(Items.TOTEM_OF_UNDYING);
        return String.format(java.util.Locale.ROOT, "its blast would do %.1f to you (you have %.1f)%s",
                damage, health, totem ? " and pop your totem" : " and kill you");
    }
}
