package app;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Damage Particles, the old 1.8.9 style: every time a player near you takes damage, a
 * shower of red "-1"s bursts off them, one for every point of damage (half a heart), so a
 * big hit or a combo throws a lot of them. They pop out over the first second, fly up and
 * fall back down, and fade; a shower lasts about 2 seconds.
 *
 * Damage is read from the health and absorption the server shares for every player. A
 * server that hides other players' health shows nothing.
 */
final class DamageParticles {
    private record Bit(String text, Vec3 start, Vec3 velocity, long bornMs) {}

    private static final long SPREAD_MS = 1000;
    private static final long LIFE_MS = 1000;
    private static final double GRAVITY = 6.0;
    private static final int MAX_PER_HIT = 40;
    // Ticks after the hurt flash starts in which the health loss is measured.
    private static final int WATCH_TICKS = 4;
    private static final int MAX_BITS = 400;
    private static final double RANGE = 48;
    private static final int RED = 0xFFFF3B3B;
    private static volatile boolean enabled;
    private static boolean registered;
    private static boolean broken;
    private static boolean drawFailed;
    // Render thread only.
    private static final List<Bit> BITS = new ArrayList<>();
    private static final Map<Integer, Hurt> STATE = new HashMap<>();

    private DamageParticles() {}

    private static long now() {
        return System.nanoTime() / 1_000_000;
    }

    /** Called from the input loop: notices damage, hooks the drawing once. */
    static void update(Minecraft minecraft) {
        if (!registered) {
            registered = true;
            try {
                LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(DamageParticles::render);
            } catch (RuntimeException | LinkageError error) {
                broken = true;
                SpearLog.log("damage particles: could not hook world drawing (" + error + ")");
            }
        }
        LocalPlayer player = minecraft.player;
        if (!enabled || player == null || minecraft.level == null) {
            STATE.clear();
            BITS.clear();
            return;
        }
        long now = now();
        for (Player other : minecraft.level.players()) {
            if (other == player || player.distanceTo(other) > RANGE) {
                continue;
            }
            float total = other.getHealth() + other.getAbsorptionAmount();
            Hurt state = STATE.computeIfAbsent(other.getId(), id -> new Hurt(total));
            // A real hit: their red hurt flash starts. Health that drops on its own
            // (absorption running out, a server sending made-up health) does nothing.
            if (other.hurtTime > state.lastHurtTime && state.watchTicks == 0) {
                // The health update can come a tick or two before the flash, so the hit is
                // measured from the highest health of the last few ticks...
                state.watchTicks = WATCH_TICKS;
                state.before = state.recentHigh();
                state.lowest = total;
            }
            state.lastHurtTime = other.hurtTime;
            state.remember(total);
            if (state.watchTicks > 0) {
                // ...to the lowest point in the few ticks after it.
                state.lowest = Math.min(state.lowest, total);
                if (--state.watchTicks == 0) {
                    float damage = state.before - state.lowest;
                    if (damage > 0.05F) {
                        shower(other, damage, now);
                    }
                    state.clearHistory(total); // so the next hit starts from here
                }
            }
        }
        BITS.removeIf(bit -> now - bit.bornMs() > LIFE_MS);
        STATE.keySet().removeIf(id -> minecraft.level.getEntity(id) == null);
    }

    /** Per player: health before a hit, and the few ticks after one while the loss is measured. */
    private static final class Hurt {
        final float[] history = new float[3];
        int next;
        float before;
        float lowest;
        int lastHurtTime;
        int watchTicks;

        Hurt(float total) {
            clearHistory(total);
        }

        void remember(float total) {
            history[next] = total;
            next = (next + 1) % history.length;
        }

        float recentHigh() {
            float high = history[0];
            for (float value : history) {
                high = Math.max(high, value);
            }
            return high;
        }

        void clearHistory(float total) {
            java.util.Arrays.fill(history, total);
        }
    }

    /** One "-1" per point of damage (the rest as "-0.5" and the like), born over the next second. */
    private static void shower(Player target, float damage, long now) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int whole = (int) Math.floor(damage);
        float rest = damage - whole;
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < Math.min(whole, MAX_PER_HIT); i++) {
            texts.add("-1");
        }
        if (rest >= 0.25F || texts.isEmpty()) {
            texts.add(String.format(Locale.ROOT, "-%.1f", texts.isEmpty() ? damage : rest));
        }
        AABB box = target.getBoundingBox();
        for (String text : texts) {
            if (BITS.size() >= MAX_BITS) {
                break;
            }
            Vec3 start = new Vec3(random.nextDouble(box.minX, box.maxX), random.nextDouble(box.minY + 0.3, box.maxY),
                    random.nextDouble(box.minZ, box.maxZ));
            double angle = random.nextDouble(Math.PI * 2);
            double out = random.nextDouble(0.6, 1.6);
            Vec3 velocity = new Vec3(Math.cos(angle) * out, random.nextDouble(1.6, 3.2), Math.sin(angle) * out);
            BITS.add(new Bit(text, start, velocity, now + random.nextLong(SPREAD_MS)));
        }
    }

    private static void render(LevelRenderContext context) {
        if (!enabled || broken || BITS.isEmpty() || HudFilter.isHideAll()) {
            return;
        }
        try {
            draw(context);
        } catch (RuntimeException error) {
            if (!drawFailed) {
                drawFailed = true;
                SpearLog.log("damage particles: drawing failed, skipped this frame: " + error);
            }
        } catch (LinkageError error) {
            broken = true;
            SpearLog.log("damage particles: switched off, this game version draws differently (" + error + ")");
        }
    }

    private static void draw(LevelRenderContext context) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        long now = now();
        Vec3 camera = minecraft.gameRenderer.getMainCamera().position();
        PoseStack pose = context.poseStack();
        for (Bit bit : BITS) {
            double t = (now - bit.bornMs()) / 1000.0;
            if (t < 0 || t * 1000 > LIFE_MS) {
                continue; // not born yet, or gone
            }
            Vec3 at = bit.start().add(bit.velocity().x * t, bit.velocity().y * t - GRAVITY * t * t / 2,
                    bit.velocity().z * t).subtract(camera);
            double life = t * 1000 / LIFE_MS;
            int color = WorldDraw.fade(RED, life < 0.6 ? 1 : (1 - life) / 0.4);
            WorldDraw.text(minecraft, pose, context.bufferSource(), at, bit.text(), color, 0.022F);
        }
        context.bufferSource().endBatch();
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
        BITS.clear();
        STATE.clear();
    }
}
