package app;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.component.DataComponents;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Punish Alerts: tells you, above your crosshair, the moments a nearby player is open for a
 * slam: they are EATING (standing still for the rest of the bite, with the time left), just
 * POPPED a totem (1 hp plus absorption), or their SHIELD is DOWN after your Shield Break
 * (with the seconds until they can block again). Optional ping sound when one starts. It
 * only reads what your game already shows.
 */
final class PunishAlerts {
    private static final double RANGE = 16;
    private static final long POP_MS = 2000;
    private static final long SHIELD_DOWN_MS = 5000;
    private static final float TOTEM_HEALTH = 1.5F;
    private static final float TOTEM_ABSORPTION = 7.5F;
    private static final int HOT = 0xFFFF5040;
    private static final int GLOW = 0xFF39FF14;
    private static volatile boolean enabled;
    private static volatile boolean sound = true;
    // Render thread only.
    private static final Map<Integer, Float> LAST_ABSORPTION = new HashMap<>();
    private static final Map<Integer, Long> POPPED_AT = new HashMap<>();
    private static String lastShown = "";

    private PunishAlerts() {}

    /** Called from the input loop: notices totem pops. */
    static void update(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (!enabled || player == null || minecraft.level == null) {
            LAST_ABSORPTION.clear();
            POPPED_AT.clear();
            return;
        }
        long now = nowMs();
        for (Player other : minecraft.level.players()) {
            if (other == player || player.distanceTo(other) > RANGE) {
                continue;
            }
            float absorption = other.getAbsorptionAmount();
            Float before = LAST_ABSORPTION.put(other.getId(), absorption);
            if (before != null && absorption >= TOTEM_ABSORPTION && absorption > before + 1
                    && other.getHealth() <= TOTEM_HEALTH) {
                POPPED_AT.put(other.getId(), now);
                SpearLog.log("punish alerts: " + other.getName().getString() + " popped a totem");
            }
        }
        POPPED_AT.values().removeIf(at -> now - at > POP_MS);
        LAST_ABSORPTION.keySet().removeIf(id -> minecraft.level.getEntity(id) == null);
    }

    static void draw(GuiGraphicsExtractor graphics, Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (!enabled || player == null || minecraft.level == null) {
            return;
        }
        String alert = alert(minecraft, player);
        if (alert == null) {
            lastShown = "";
            return;
        }
        String kind = alert.substring(0, alert.indexOf(' ') < 0 ? alert.length() : alert.indexOf(' '));
        if (sound && !kind.equals(lastShown)) {
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), 1.6F, 0.6F));
        }
        lastShown = kind;
        int width = minecraft.font.width(alert);
        int x = graphics.guiWidth() / 2 - width / 2;
        int y = graphics.guiHeight() / 2 - 26;
        graphics.fill(x - 3, y - 2, x + width + 3, y + 9, 0x88000000);
        graphics.text(minecraft.font, alert, x, y, alert.contains("POPPED") ? HOT : GLOW);
    }

    /** The most urgent opening right now, or null. */
    private static String alert(Minecraft minecraft, LocalPlayer player) {
        long now = nowMs();
        for (Map.Entry<Integer, Long> pop : POPPED_AT.entrySet()) {
            if (minecraft.level.getEntity(pop.getKey()) instanceof Player popped) {
                return name(popped) + " POPPED  hit now";
            }
        }
        LivingEntity broken = ShieldBreak.lastBroken();
        long sinceBreak = now - ShieldBreak.lastBrokenMs();
        if (broken != null && broken.isAlive() && sinceBreak < SHIELD_DOWN_MS && !broken.isBlocking()
                && player.distanceTo(broken) <= RANGE) {
            return String.format(Locale.ROOT, "%s SHIELD DOWN %.1fs", name(broken),
                    (SHIELD_DOWN_MS - sinceBreak) / 1000.0);
        }
        for (Player other : minecraft.level.players()) {
            if (other == player || player.distanceTo(other) > RANGE || !other.isUsingItem()) {
                continue;
            }
            ItemStack using = other.getUseItem();
            if (using.has(DataComponents.FOOD) || using.has(DataComponents.CONSUMABLE)) {
                return String.format(Locale.ROOT, "%s EATING %.1fs", name(other),
                        other.getUseItemRemainingTicks() / 20.0);
            }
        }
        return null;
    }

    private static String name(LivingEntity entity) {
        return entity.getName().getString();
    }

    private static long nowMs() {
        return System.nanoTime() / 1_000_000;
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

    static boolean isSound() {
        return sound;
    }

    static void setSound(boolean on) {
        sound = on;
    }

    static void toggleSound() {
        sound = !sound;
    }

    static void reset() {
        enabled = false;
        LAST_ABSORPTION.clear();
        POPPED_AT.clear();
    }
}
