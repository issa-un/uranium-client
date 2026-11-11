package app;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A detailed line for every mace hit, to find out why one did little or nothing: what you
 * and the target looked like the moment the attack went out, what probably cost the hit,
 * and then what happened. The moment is read on the thread that sends the attack (see
 * AttackHook); the result is watched on the render thread over your ping plus WATCH_MS:
 * - HIT: the target's hurt animation started, with the health it lost.
 * - LETHAL, totem popped: health fell to about 1 and the totem's 8 absorption appeared.
 * - NOT COUNTED: no hurt seen; the server threw it away (out of reach, immune, blocked).
 *
 * Your attack cooldown is the one from before the click: the game restarts it the moment
 * it attacks, before the packet reaches the sending thread, so reading it there shows
 * almost 0 every time. The input loop notes it each frame instead (strengthBeforeAttack).
 */
final class MaceLog {
    // A mace hit is a slam past this fall (MaceItem.canSmashAttack); the mace's reach, and
    // the extra the server allows on top of it (ServerGamePacketListenerImpl.handleAttack).
    private static final double SMASH_FALL = 1.5;
    private static final double REACH = 3.0;
    private static final double SERVER_SLACK = 3.0;
    // How long past your ping to watch for the result.
    private static final long WATCH_MS = 400;
    // A totem leaves you on 1 health with Absorption II (8).
    private static final float TOTEM_HEALTH = 2;
    private static final float TOTEM_ABSORPTION = 7.5F;
    private static final ConcurrentLinkedQueue<String> PENDING_LINES = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<Shot> NEW = new ConcurrentLinkedQueue<>();
    // Render thread only.
    private static final List<Shot> WATCHING = new ArrayList<>();
    private static volatile float strength = 1;
    private static int count;

    private MaceLog() {}

    /** One hit going out, and what its result is compared against. */
    private static final class Shot {
        final int number;
        final int targetId;
        final String name;
        final float startHealth;
        final float startAbsorption;
        final int startHurtTime;
        final boolean slam;
        final long startMs;
        final long untilMs;
        float lowestTotal;
        boolean hurtSeen;
        boolean totem;

        Shot(int number, LivingEntity target, boolean slam, long untilMs) {
            this.number = number;
            targetId = target.getId();
            name = target.getName().getString();
            startHealth = target.getHealth();
            startAbsorption = target.getAbsorptionAmount();
            startHurtTime = target.hurtTime;
            this.slam = slam;
            startMs = nowMs();
            this.untilMs = untilMs;
            lowestTotal = startHealth + startAbsorption;
        }
    }

    /** Your attack cooldown (0 to 1) as it was before your last attack restarted it. */
    static float strengthBeforeAttack() {
        return strength;
    }

    /**
     * An attack going out with a mace (or one that could have), on the thread that sends it.
     * maceSlot is the mace it goes out with, or -1 if it goes out without one; how says why.
     */
    static void attack(Minecraft minecraft, LivingEntity target, int maceSlot, int heldSlot, String how) {
        LocalPlayer player = minecraft.player;
        if (player == null) {
            return;
        }
        int number;
        synchronized (MaceLog.class) {
            number = ++count;
        }
        ItemStack mace = maceSlot >= 0 ? player.getInventory().getItem(maceSlot) : ItemStack.EMPTY;
        double fall = player.fallDistance;
        boolean slam = maceSlot >= 0 && fall > SMASH_FALL && !player.isFallFlying();
        Vec3 motion = player.getDeltaMovement();
        float cooldown = strength;
        double reach = distanceToBox(player.getEyePosition(), target.getBoundingBox());
        int ping = ShieldBreak.pingMs(minecraft);
        boolean shield = target.isBlocking();
        boolean shieldToward = ShieldBreak.blockingToward(minecraft, target);
        boolean immune = ShieldBreak.immuneWhenHitArrives(minecraft, target);

        StringBuilder line = new StringBuilder("mace #").append(number).append(" on ").append(target.getName().getString())
                .append(": ");
        if (maceSlot >= 0) {
            line.append(MaceSwap.describe(mace)).append(" slot ").append(maceSlot + 1);
            int density = MaceSwap.enchantmentLevel(mace, Enchantments.DENSITY);
            int breach = MaceSwap.enchantmentLevel(mace, Enchantments.BREACH);
            if (density > 0) {
                line.append(" (Density ").append(density).append(")");
            }
            if (breach > 0) {
                line.append(" (Breach ").append(breach).append(")");
            }
        } else {
            line.append("no mace");
        }
        line.append(", ").append(how).append(" (held slot ").append(heldSlot + 1).append(")")
                .append(" | you: fall ").append(f1(fall)).append(slam ? " SLAM" : " no slam")
                .append(", vy ").append(f2(motion.y)).append(player.onGround() ? ", on ground" : ", in air")
                .append(player.isFallFlying() ? ", gliding" : "")
                .append(", cooldown ").append(Math.round(cooldown * 100)).append("%")
                .append(player.isSprinting() ? ", sprinting" : "")
                .append(" | target: ").append(f2(reach)).append(" blocks, hp ").append(f1(target.getHealth()))
                .append(target.getAbsorptionAmount() > 0 ? "+" + f1(target.getAbsorptionAmount()) : "")
                .append(", armor ").append(target.getArmorValue())
                .append(", hurtTime ").append(target.hurtTime)
                .append(shield ? shieldToward ? ", SHIELD UP toward you" : ", shield up (not toward you)" : "")
                .append(target.isUsingItem() && !shield ? ", using " + target.getUseItem().getHoverName().getString() : "")
                .append(" | ping ").append(ping);

        List<String> problems = new ArrayList<>();
        if (maceSlot < 0) {
            problems.add("went out without a mace");
        }
        if (maceSlot >= 0 && !slam && fall > 0.5) {
            problems.add("fell only " + f1(fall) + ", a slam needs more than " + SMASH_FALL);
        }
        if (player.isFallFlying()) {
            problems.add("gliding: no slam while flying an elytra");
        }
        if (reach > REACH + SERVER_SLACK) {
            problems.add("out of reach (" + f2(reach) + " > " + (REACH + SERVER_SLACK) + ")");
        }
        if (!slam && cooldown < 0.9F) {
            problems.add("attack cooldown at " + Math.round(cooldown * 100) + "%: a plain hit's damage is cut");
        }
        if (immune) {
            problems.add("target still immune (hurtTime " + target.hurtTime + "): only a harder hit than its last counts");
        }
        if (shieldToward) {
            problems.add("shield up toward you: the hit is blocked, slam and all");
        }
        if (target.getArmorValue() >= 15 && maceSlot >= 0 && MaceSwap.enchantmentLevel(mace, Enchantments.BREACH) == 0) {
            problems.add("heavy armor (" + target.getArmorValue() + ") and no Breach");
        }
        line.append(problems.isEmpty() ? " | looks fine" : " | WATCH: " + String.join("; ", problems));

        PENDING_LINES.add(line.toString());
        NEW.add(new Shot(number, target, slam, nowMs() + ping + WATCH_MS));
        if (slam) {
            SlamFollowUp.onSlam(target);
        }
    }

    /** Called from the input loop: notes your cooldown, writes new lines and finished results. */
    static void update(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player != null) {
            strength = player.getAttackStrengthScale(0.5F);
        }
        for (String line; (line = PENDING_LINES.poll()) != null; ) {
            SpearLog.log(line);
        }
        for (Shot shot; (shot = NEW.poll()) != null; ) {
            WATCHING.add(shot);
        }
        long now = nowMs();
        for (Iterator<Shot> it = WATCHING.iterator(); it.hasNext(); ) {
            Shot shot = it.next();
            Entity entity = minecraft.level == null ? null : minecraft.level.getEntity(shot.targetId);
            if (entity instanceof LivingEntity target) {
                if (target.hurtTime > shot.startHurtTime) {
                    shot.hurtSeen = true;
                }
                if (target.getHealth() <= TOTEM_HEALTH && target.getAbsorptionAmount() >= TOTEM_ABSORPTION
                        && target.getAbsorptionAmount() > shot.startAbsorption + 1) {
                    shot.totem = true;
                }
                shot.lowestTotal = Math.min(shot.lowestTotal, target.getHealth() + target.getAbsorptionAmount());
            }
            if (now < shot.untilMs) {
                continue;
            }
            it.remove();
            SpearLog.log(result(shot, entity, now));
        }
    }

    private static String result(Shot shot, Entity entity, long now) {
        float start = shot.startHealth + shot.startAbsorption;
        float lost = start - shot.lowestTotal;
        StringBuilder line = new StringBuilder("mace #").append(shot.number).append(" result on ").append(shot.name)
                .append(" after ").append(now - shot.startMs).append(" ms: ");
        if (shot.totem) {
            line.append("LETHAL, totem popped (").append(f1(shot.startHealth)).append(" hp -> 1 hp + 8 absorption)");
        } else if (entity == null || !entity.isAlive()) {
            line.append(shot.hurtSeen ? "KILL (target gone)" : "target gone from view");
        } else if (shot.hurtSeen && lost >= 0.05F) {
            line.append("HIT, -").append(f1(lost)).append(" hp (").append(f1(start)).append(" -> ")
                    .append(f1(shot.lowestTotal)).append(")");
        } else if (shot.hurtSeen) {
            line.append("hurt animation but no health lost (blocked, resistance, or immune)");
        } else if (lost >= 0.05F) {
            line.append("lost ").append(f1(lost)).append(" hp but no hurt animation (maybe another hit)");
        } else {
            line.append("NOT COUNTED: no hurt seen (out of reach, immune, blocked, or missed on the server)");
        }
        if (shot.slam) {
            line.append(" [slam]");
        }
        return line.toString();
    }

    private static double distanceToBox(Vec3 point, AABB box) {
        double x = Math.max(box.minX, Math.min(point.x, box.maxX));
        double y = Math.max(box.minY, Math.min(point.y, box.maxY));
        double z = Math.max(box.minZ, Math.min(point.z, box.maxZ));
        return point.distanceTo(new Vec3(x, y, z));
    }

    private static String f1(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String f2(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static long nowMs() {
        return System.nanoTime() / 1_000_000;
    }

    /** Drops what is being watched when this injection is unloaded. */
    static void reset() {
        NEW.clear();
        PENDING_LINES.clear();
        WATCHING.clear();
    }
}
