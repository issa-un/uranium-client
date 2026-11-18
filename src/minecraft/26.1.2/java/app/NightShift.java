package app;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.time.LocalTime;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * 2AM: a quiet HUD for late sessions.
 *
 * The rules: quiet by default (out of a fight it fades almost to nothing over 3 s and comes
 * back in 0.2 s when one starts), one alert at a time (Pop Chams' screen line gives way to
 * the pop dots, Punish Alerts fades in instead of popping), one palette (brushed steel,
 * cream and a warm amber, low saturation), and after midnight on your clock it dims itself.
 *
 * The pieces: a thin record ring around the crosshair that fills with your crossbow's
 * charge (Auto reload too) or your attack charge, and sits still once full; a lowercase
 * ticker in the corner ("carts 7 · rails 12 · xbow loaded") that shows when a count changes
 * and then fades; and pop dots over players, amber for each totem they popped this life,
 * grey for the ones before.
 */
final class NightShift {
    static final int STEEL = 0x9AA0A6;
    static final int CREAM = 0xEFE6D2;
    static final int AMBER = 0xE0A84E;
    private static final int TRACK = 0x3A3F46;
    // A fight: you hit or got hit, or drew a bow or crossbow, within this long.
    private static final long FIGHT_MS = 8000;
    private static final long FADE_IN_MS = 200;
    private static final long FADE_OUT_MS = 3000;
    private static final double QUIET = 0.12;
    private static final long TICKER_SHOW_MS = 4000;
    private static final long TICKER_FADE_MS = 1500;
    private static final int RING_RADIUS = 8;
    private static final int MAX_DOTS = 12;
    private static volatile boolean enabled;
    private static boolean registered;
    private static boolean broken;
    private static boolean drawFailed;
    // Render thread only.
    private static long lastFightMs = -FIGHT_MS;
    private static double level = QUIET;
    private static long lastFrameMs;
    private static String ticker = "";
    private static long tickerChangedMs = -TICKER_SHOW_MS - TICKER_FADE_MS;
    private static long ringFullMs = -1;

    private NightShift() {}

    private static long now() {
        return System.nanoTime() / 1_000_000;
    }

    /** Called from the input loop: notices fights and count changes, hooks the dots once. */
    static void update(Minecraft minecraft) {
        if (!registered) {
            registered = true;
            try {
                LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(NightShift::render);
            } catch (RuntimeException | LinkageError error) {
                broken = true;
                SpearLog.log("2am: could not hook world drawing (" + error + ")");
            }
        }
        LocalPlayer player = minecraft.player;
        if (!enabled || player == null) {
            return;
        }
        long now = now();
        boolean shooting = player.isUsingItem()
                && (player.getUseItem().is(Items.BOW) || player.getUseItem().is(Items.CROSSBOW));
        boolean hitSomeone = player.getLastHurtMob() != null
                && player.tickCount - player.getLastHurtMobTimestamp() < FIGHT_MS / 50;
        if (player.hurtTime > 0 || hitSomeone || shooting) {
            lastFightMs = now;
        }
        String counts = counts(player);
        if (!counts.equals(ticker)) {
            ticker = counts;
            tickerChangedMs = now;
        }
    }

    /** How loud the HUD is right now, 0 to 1: up fast when a fight starts, down slow after. */
    static double level() {
        return enabled ? level : 1;
    }

    private static double night() {
        int hour = LocalTime.now().getHour();
        return hour < 6 ? 0.8 : 1.0;
    }

    private static void step() {
        long now = now();
        long elapsed = lastFrameMs == 0 ? 0 : Math.min(250, now - lastFrameMs);
        lastFrameMs = now;
        double target = now - lastFightMs < FIGHT_MS ? 1.0 : QUIET;
        if (level < target) {
            level = Math.min(target, level + elapsed / (double) FADE_IN_MS);
        } else {
            level = Math.max(target, level - elapsed * (1 - QUIET) / FADE_OUT_MS);
        }
    }

    static int color(int rgb, double alpha) {
        int a = (int) Math.round(255 * Math.max(0, Math.min(1, alpha)));
        return (a << 24) | (rgb & 0xFFFFFF);
    }

    /** Called by the HUD every frame: the ring and the ticker. */
    static void draw(GuiGraphicsExtractor graphics, Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (!enabled || player == null || player.isSpectator()) {
            return;
        }
        step();
        double night = night();
        drawRing(graphics, player, night);
        drawTicker(graphics, minecraft, night);
    }

    /** The record ring: the crossbow's charge while you load it, else your attack charge. */
    private static void drawRing(GuiGraphicsExtractor graphics, LocalPlayer player, double night) {
        double progress;
        ItemStack using = player.getUseItem();
        if (player.isUsingItem() && using.is(Items.CROSSBOW)) {
            progress = player.getTicksUsingItem() / (double) Math.max(1, CrossbowItem.getChargeDuration(using, player));
        } else {
            progress = player.getAttackStrengthScale(0.0F);
        }
        progress = Math.min(1, progress);
        long now = now();
        if (progress < 1) {
            ringFullMs = -1;
        } else if (ringFullMs < 0) {
            ringFullMs = now;
        }
        // Once full it stays still and fades out with the rest of the quiet HUD.
        double alpha = progress < 1 ? 1 : Math.max(0, 1 - (now - ringFullMs) / 600.0) * level;
        alpha *= night;
        if (alpha <= 0.02) {
            return;
        }
        int cx = graphics.guiWidth() / 2;
        int cy = graphics.guiHeight() / 2;
        int segments = 40;
        for (int i = 0; i < segments; i++) {
            double angle = -Math.PI / 2 + i * Math.PI * 2 / segments;
            int x = cx + (int) Math.round(Math.cos(angle) * RING_RADIUS);
            int y = cy + (int) Math.round(Math.sin(angle) * RING_RADIUS);
            boolean lit = i < Math.round(progress * segments);
            graphics.fill(x, y, x + 1, y + 1, color(lit ? AMBER : TRACK, alpha * (lit ? 0.95 : 0.6)));
        }
    }

    /** The liner notes: shows when a count changes, then fades. */
    private static void drawTicker(GuiGraphicsExtractor graphics, Minecraft minecraft, double night) {
        if (ticker.isEmpty()) {
            return;
        }
        long age = now() - tickerChangedMs;
        double alpha = age < TICKER_SHOW_MS ? 1 : 1 - (age - TICKER_SHOW_MS) / (double) TICKER_FADE_MS;
        alpha = Math.max(alpha, level * 0.6) * night; // in a fight it stays readable
        if (alpha <= 0.02) {
            return;
        }
        int y = graphics.guiHeight() - 34;
        graphics.text(minecraft.font, ticker, 8, y, color(STEEL, alpha));
        graphics.fill(8, y + 10, 8 + minecraft.font.width(ticker), y + 11, color(TRACK, alpha * 0.8));
    }

    private static String counts(LocalPlayer player) {
        int carts = 0;
        int rails = 0;
        int crossbows = 0;
        int loaded = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(Items.TNT_MINECART)) {
                carts += stack.getCount();
            } else if (Cart.isRail(stack)) {
                rails += stack.getCount();
            } else if (stack.is(Items.CROSSBOW)) {
                crossbows++;
                if (CrossbowItem.isCharged(stack)) {
                    loaded++;
                }
            }
        }
        StringBuilder line = new StringBuilder();
        line.append("carts ").append(carts).append("  ·  rails ").append(rails);
        if (crossbows > 0) {
            line.append("  ·  xbow ").append(loaded == crossbows ? "loaded" : loaded + "/" + crossbows + " loaded");
        }
        return line.toString();
    }

    /** The pop dots over players: amber this life, grey before. */
    private static void render(LevelRenderContext context) {
        if (!enabled || broken || HudFilter.isHideAll()) {
            return;
        }
        try {
            drawDots(context);
        } catch (RuntimeException error) {
            if (!drawFailed) {
                drawFailed = true;
                SpearLog.log("2am: drawing failed, skipped this frame: " + error);
            }
        } catch (LinkageError error) {
            broken = true;
            SpearLog.log("2am: dots switched off, this game version draws differently (" + error + ")");
        }
    }

    private static void drawDots(LevelRenderContext context) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        float partial = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        Vec3 camera = minecraft.gameRenderer.getMainCamera().position();
        boolean firstPerson = minecraft.options.getCameraType().isFirstPerson();
        double alpha = Math.max(0.55, level) * night();
        PoseStack pose = context.poseStack();
        RenderType type = RenderTypes.debugQuads();
        VertexConsumer buffer = context.bufferSource().getBuffer(type);
        for (Player other : minecraft.level.players()) {
            if (other.isInvisible() || other == minecraft.player && firstPerson
                    || other.distanceTo(minecraft.player) > 48) {
                continue;
            }
            int life = PopChams.lifePops(other.getUUID());
            int older = PopChams.olderPops(other.getUUID());
            int total = Math.min(MAX_DOTS, life + older);
            if (total == 0) {
                continue;
            }
            Vec3 at = other.getPosition(partial).add(0, other.getBbHeight() + 0.85, 0).subtract(camera);
            pose.pushPose();
            pose.translate(at.x, at.y, at.z);
            pose.mulPose(minecraft.gameRenderer.getMainCamera().rotation());
            float size = 0.045F;
            float gap = 0.11F;
            float start = -(total - 1) * gap / 2;
            int olderShown = total - Math.min(life, total);
            for (int i = 0; i < total; i++) {
                // older pops first (grey), then this life's (amber) on the right
                int color = i < olderShown ? color(STEEL, alpha * 0.6) : color(AMBER, alpha);
                float x = start + i * gap;
                WorldDraw.box(pose.last(), buffer, x - size, -size, 0, x + size, size, 0.001F, color);
            }
            pose.popPose();
        }
        context.bufferSource().endBatch(type);
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
