package app;

import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;

/**
 * Wind Clutch: when a fall would leave you within 4 hearts of death (or kill you), throws a
 * wind charge straight down right before you land. Its burst at your feet cancels the fall,
 * so you take no fall damage. The landing is predicted with the same calculation as the
 * Landing readout; the charge goes out once the ground is about a tick away, so the burst
 * happens with you right on top of it. Only the throw looks down (your camera never moves),
 * and your slot goes back straight after. Once per fall.
 */
final class WindClutch {
    // Left with this much health or less after the fall: 4 hearts.
    private static final float DANGER_LEFT = 8;
    // Throw when the ground is within this many blocks, or one tick of your fall speed.
    private static final double MIN_THROW_DROP = 2.5;
    private static volatile boolean enabled;
    // Render thread only: already thrown during this fall.
    private static boolean thrown;

    private WindClutch() {}

    /** Called from the input loop. */
    static void update(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.gameMode == null || minecraft.level == null) {
            return;
        }
        if (player.onGround() || player.isInWater()) {
            thrown = false;
            return;
        }
        if (!enabled || thrown || Mc.screen(minecraft) != null || !SpearSettings.combatAllowed(minecraft)
                || MlgWater.isActive()) { // MLG Water already has this landing
            return;
        }
        VelocityHud.Landing landing = VelocityHud.predictLanding(minecraft, player);
        if (landing.kind() != VelocityHud.LandingKind.DAMAGE) {
            return;
        }
        float health = player.getHealth() + player.getAbsorptionAmount();
        if (health - landing.damage() > DANGER_LEFT) {
            return;
        }
        double fallSpeed = -player.getDeltaMovement().y;
        if (landing.drop() > Math.max(MIN_THROW_DROP, fallSpeed * 1.5)) {
            return; // not yet: the burst has to happen right under you
        }
        int slot = BucketUse.findSlot(player, Items.WIND_CHARGE);
        if (slot < 0) {
            return;
        }
        if (player.getCooldowns().isOnCooldown(player.getInventory().getItem(slot))) {
            return;
        }
        thrown = true;
        int previous = player.getInventory().getSelectedSlot();
        select(minecraft, slot);
        throwDown(minecraft, player);
        select(minecraft, previous);
        MinecraftSpearDash.setStatus("Wind clutch");
        SpearLog.log(String.format(Locale.ROOT, "wind clutch: thrown %.1f blocks up, the fall would have done %d of your %.0f hp",
                landing.drop(), landing.damage(), health));
    }

    /** Throws the held wind charge straight down; the server is told you look down just for it. */
    private static void throwDown(Minecraft minecraft, LocalPlayer player) {
        float pitch = player.getXRot();
        float pitchO = player.xRotO;
        ClientPacketListener connection = minecraft.getConnection();
        if (connection != null) {
            connection.send(new ServerboundMovePlayerPacket.Rot(player.getYRot(), 90, player.onGround(),
                    player.horizontalCollision));
        }
        player.setXRot(90);
        try {
            InteractionResult result = minecraft.gameMode.useItem(player, InteractionHand.MAIN_HAND);
            if (Mc.swingsOnClient(result)) {
                Mc.swing(player, InteractionHand.MAIN_HAND);
            }
        } finally {
            player.setXRot(pitch);
            player.xRotO = pitchO;
            if (connection != null) {
                connection.send(new ServerboundMovePlayerPacket.Rot(player.getYRot(), pitch, player.onGround(),
                        player.horizontalCollision));
            }
        }
    }

    private static void select(Minecraft minecraft, int slot) {
        if (minecraft.player.getInventory().getSelectedSlot() != slot) {
            minecraft.player.getInventory().setSelectedSlot(slot);
            MinecraftSpearDash.sendSelectedSlot(minecraft, false);
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

    /** Turns the module off when this injection is unloaded. */
    static void reset() {
        enabled = false;
        thrown = false;
    }
}
