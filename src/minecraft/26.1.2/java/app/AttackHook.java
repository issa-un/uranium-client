package app;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import java.lang.reflect.Field;
import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.network.HandlerNames;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * Lets Auto Stun, Mace Swap, Auto Weapon and W-Tap react to every attack your client
 * sends (their Trigger on Any attack), not only to your own clicks: hits that a server's
 * hit system, another mod or a macro sends as a normal attack packet are caught on their
 * way out. It sits in the connection's Netty pipeline, so it sees each attack just before
 * it is encoded, on the network thread, whoever sent it.
 * - Auto Stun: the attack is held back and the shield break runs instead on the next
 *   frame, as a click would; if the break cannot start after all, the attack goes out.
 * - Mace Swap: the mace is selected for that one packet, the attack goes out and the slot
 *   goes back, in one write, so nothing is delayed or reordered.
 * - Auto Weapon: the weapon is selected right before the attack goes out, and stays (or,
 *   with a delay on, the attack is held back like Mace Swap's).
 * - W-Tap: notes the hit.
 * The modules' own hits are recognised and go straight through.
 */
final class AttackHook {
    private static final String[] TRIGGERS = {"Click", "Any attack"};
    // Our own hits are told apart by their target. An entry nobody claims (a hit that never
    // went out) expires.
    private static final long OWN_TTL_MS = 1000;
    private static final ConcurrentLinkedQueue<long[]> OWN_ATTACKS = new ConcurrentLinkedQueue<>();
    // Each injected version has its own handler, so an Update can remove the old one.
    private static final String NAME = "spear-attack-hook-" + AttackHook.class.getPackageName();
    private static volatile Field connectionChannel;
    // What went out, summed up in the log every few seconds of fighting: shows whether a
    // server's hits arrive here as attacks at all (a server that registers hits from your
    // swing alone sends swings with few or no attacks).
    private static final long SUMMARY_MS = 5000;
    private static final AtomicInteger ATTACKS = new AtomicInteger();
    private static final AtomicInteger OWN = new AtomicInteger();
    private static final AtomicInteger SWINGS = new AtomicInteger();
    private static final AtomicInteger HELD = new AtomicInteger();
    private static final AtomicInteger MACE_SWAPS = new AtomicInteger();
    private static final AtomicInteger WEAPON_SWITCHES = new AtomicInteger();
    // Render thread only.
    private static Channel hookedChannel;
    private static long summaryAtMs;

    private AttackHook() {}

    static String triggerLabel(boolean anyAttack) {
        return TRIGGERS[anyAttack ? 1 : 0];
    }

    /** Called from the input loop: hooks the current connection once you join a server. */
    static void update(Minecraft minecraft) {
        logSummary();
        ClientPacketListener listener = minecraft.getConnection();
        Channel channel = listener == null ? null : channelOf(listener.getConnection());
        if (channel == hookedChannel) {
            return;
        }
        hookedChannel = channel;
        if (channel == null) {
            return;
        }
        ChannelPipeline pipeline = channel.pipeline();
        try {
            if (pipeline.get(NAME) != null) {
                return;
            }
            // Before the game's own handler, so every packet it sends passes through here.
            if (pipeline.get(HandlerNames.PACKET_HANDLER) != null) {
                pipeline.addBefore(HandlerNames.PACKET_HANDLER, NAME, new Handler());
            } else {
                pipeline.addLast(NAME, new Handler());
            }
            UraniumLog.log("attack hook: on the connection to " + Settings.currentServer(minecraft));
        } catch (RuntimeException exception) {
            // The connection closed meanwhile; the next one is hooked when you join.
            UraniumLog.log("attack hook: could not hook the connection (" + exception + ")");
        }
    }

    private static void logSummary() {
        long now = nowMs();
        if (now < summaryAtMs) {
            return;
        }
        summaryAtMs = now + SUMMARY_MS;
        int attacks = ATTACKS.getAndSet(0);
        int swings = SWINGS.getAndSet(0);
        int own = OWN.getAndSet(0);
        int held = HELD.getAndSet(0);
        int maces = MACE_SWAPS.getAndSet(0);
        int weapons = WEAPON_SWITCHES.getAndSet(0);
        if (attacks + swings > 0) {
            UraniumLog.log("attack hook: last 5 s sent " + attacks + " attacks (" + own + " by modules) and "
                    + swings + " swings; shield break took " + held + ", mace swap " + maces
                    + ", auto weapon " + weapons);
        }
    }

    /** Takes the hook off the connection when this injection is unloaded. */
    static void uninstall() {
        Channel channel = hookedChannel;
        hookedChannel = null;
        OWN_ATTACKS.clear();
        if (channel == null) {
            return;
        }
        try {
            if (channel.pipeline().get(NAME) != null) {
                channel.pipeline().remove(NAME);
            }
        } catch (RuntimeException ignored) {
            // Already gone with the connection.
        }
    }

    /** Call right before a module's own attack, so the hook lets it through untouched. */
    static void markOwn(int entityId) {
        OWN_ATTACKS.add(new long[] {entityId, nowMs() + OWN_TTL_MS});
    }

    /** Sends a held-back attack after all, as ours so the hook lets it through. */
    static void resend(Minecraft minecraft, ServerboundAttackPacket attack) {
        ClientPacketListener connection = minecraft.getConnection();
        if (connection != null) {
            markOwn(attack.entityId());
            connection.send(attack);
        }
    }

    private static boolean claimOwn(int entityId) {
        long now = nowMs();
        for (Iterator<long[]> own = OWN_ATTACKS.iterator(); own.hasNext(); ) {
            long[] entry = own.next();
            if (entry[1] < now) {
                own.remove();
            } else if (entry[0] == entityId) {
                own.remove();
                return true;
            }
        }
        return false;
    }

    /**
     * An attack nobody here sent, on the network thread. True if it was written (or held
     * back) here; false lets it go out as it is. All game state is read before anything is
     * written, so a read that fails midway leaves the attack untouched.
     */
    private static boolean onAttack(ChannelHandlerContext context, ServerboundAttackPacket attack,
            ChannelPromise promise) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null || minecraft.gameMode == null) {
            return false;
        }
        Entity entity = minecraft.level.getEntity(attack.entityId());
        if (!(entity instanceof LivingEntity target)) {
            return false;
        }
        if (WTap.watchesAnyAttack()) {
            boolean sprinting = WTap.wasSprinting();
            minecraft.execute(() -> WTap.onAnyAttack(minecraft, sprinting));
        }
        if (AutoStun.takesAnyAttack(minecraft, target)) {
            HELD.incrementAndGet();
            promise.trySuccess();
            minecraft.execute(() -> AutoStun.runFromAttack(minecraft, target, attack));
            return true;
        }
        AutoStun.explainSkip(minecraft, target, AutoStun.isAnyAttack() ? "any attack" : "attack packet");
        int held = SpearDash.sentSlot(minecraft);
        int mace = MaceSwap.maceForAnyAttack(minecraft, target, held);
        if (mace >= 0) {
            MACE_SWAPS.incrementAndGet();
            context.write(new ServerboundSetCarriedItemPacket(mace));
            context.write(attack, promise);
            context.write(new ServerboundSetCarriedItemPacket(held));
            minecraft.execute(() -> MaceSwap.afterAnyAttack(minecraft, held, mace, target));
            return true;
        }
        int weapon = AutoWeapon.weaponForAnyAttack(minecraft);
        if (weapon >= 0 && weapon != held) {
            WEAPON_SWITCHES.incrementAndGet();
            if (AutoWeapon.isDelayed()) {
                promise.trySuccess();
                minecraft.execute(() -> AutoWeapon.runFromAttack(minecraft, weapon, attack));
                return true;
            }
            context.write(new ServerboundSetCarriedItemPacket(weapon));
            context.write(attack, promise);
            minecraft.execute(() -> AutoWeapon.afterAnyAttack(minecraft, held, weapon));
            return true;
        }
        return false;
    }

    private static Channel channelOf(Connection connection) {
        try {
            Field field = connectionChannel;
            if (field == null) {
                field = Connection.class.getDeclaredField("channel");
                field.setAccessible(true);
                connectionChannel = field;
            }
            return (Channel) field.get(connection);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return null;
        }
    }

    private static long nowMs() {
        return System.nanoTime() / 1_000_000;
    }

    private static final class Handler extends ChannelOutboundHandlerAdapter {
        @Override
        public void write(ChannelHandlerContext context, Object message, ChannelPromise promise)
                throws Exception {
            if (Mc.isSwingPacket(message)) {
                SWINGS.incrementAndGet();
            } else if (message instanceof ServerboundAttackPacket attack) {
                ATTACKS.incrementAndGet();
                if (claimOwn(attack.entityId())) {
                    OWN.incrementAndGet();
                } else {
                    try {
                        if (onAttack(context, attack, promise)) {
                            return;
                        }
                    } catch (RuntimeException ignored) {
                        // Game state read from this thread can be mid-change; the hit goes out as it was.
                    }
                }
            }
            context.write(message, promise);
        }
    }
}
