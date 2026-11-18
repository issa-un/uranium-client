package app;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * Pop Chams: when anyone pops a totem, you included, a see-through ghost of them stays
 * where they popped and floats up as it fades (2 s), with "Name popped 3" over it: red for
 * them, green for you. The middle of your screen says the same, so you always know who
 * popped. Counts go back to 0 when that player dies.
 *
 * A pop is seen the same way Punish Alerts sees it: their absorption jumps to the totem's
 * (or more) while their health is at about 1 heart. The server shares every player's health
 * and absorption, so this needs nothing from it.
 */
final class PopChams {
    private record Ghost(String label, boolean you, Vec3 feet, float bodyYaw, float height, long atMs) {}

    private static final long LIFE_MS = 2000;
    private static final double RISE = 0.6;
    private static final float TOTEM_ABSORPTION = 7.5F;
    private static final float TOTEM_HEALTH = 1.5F;
    private static final double RANGE = 64;
    private static final int THEM = 0x8CFF3030;
    private static final int YOU = 0x8C39FF14;
    private static volatile boolean enabled;
    private static boolean registered;
    private static boolean broken;
    private static boolean drawFailed;
    // Render thread only.
    private static final List<Ghost> GHOSTS = new ArrayList<>();
    private static final Map<Integer, Float> LAST_ABSORPTION = new HashMap<>();
    private static final Map<UUID, Integer> POPS = new HashMap<>();
    // Pops from that player's earlier lives (for 2AM's grey dots).
    private static final Map<UUID, Integer> OLDER = new HashMap<>();

    private PopChams() {}

    private static long now() {
        return System.nanoTime() / 1_000_000;
    }

    /** Called from the input loop: notices pops, hooks the drawing once. */
    static void update(Minecraft minecraft) {
        if (!registered) {
            registered = true;
            try {
                LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(PopChams::render);
            } catch (RuntimeException | LinkageError error) {
                broken = true;
                SpearLog.log("pop chams: could not hook world drawing (" + error + ")");
            }
        }
        LocalPlayer player = minecraft.player;
        // 2AM's pop dots need the counts even with the ghosts off.
        if (!enabled && !NightShift.isEnabled() || player == null || minecraft.level == null) {
            LAST_ABSORPTION.clear();
            GHOSTS.clear();
            return;
        }
        long now = now();
        for (Player other : minecraft.level.players()) {
            if (other != player && player.distanceTo(other) > RANGE) {
                continue;
            }
            if (other.isDeadOrDying()) {
                Integer life = POPS.remove(other.getUUID());
                if (life != null) {
                    OLDER.merge(other.getUUID(), life, Integer::sum);
                }
            }
            float absorption = other.getAbsorptionAmount();
            Float before = LAST_ABSORPTION.put(other.getId(), absorption);
            if (before != null && absorption >= TOTEM_ABSORPTION && absorption > before + 1
                    && other.getHealth() <= TOTEM_HEALTH && other.isAlive()) {
                int pops = POPS.merge(other.getUUID(), 1, Integer::sum);
                boolean you = other == player;
                String label = (you ? "You" : other.getName().getString()) + " popped " + pops;
                if (enabled) {
                    GHOSTS.add(new Ghost(label, you, other.position(), other.yBodyRot, other.getBbHeight(), now));
                }
                SpearLog.log("pop chams: " + label);
            }
        }
        GHOSTS.removeIf(ghost -> now - ghost.atMs() > LIFE_MS);
        LAST_ABSORPTION.keySet().removeIf(id -> minecraft.level.getEntity(id) == null);
    }

    /** Called by the HUD: who popped, under the crosshair, while their ghost is up. */
    static void drawHud(GuiGraphicsExtractor graphics, Minecraft minecraft) {
        if (!enabled || GHOSTS.isEmpty() || NightShift.isEnabled()) {
            return; // with 2AM on, the pop dots say it instead (one alert at a time)
        }
        long now = now();
        int y = graphics.guiHeight() / 2 - 34;
        for (int i = GHOSTS.size() - 1; i >= 0 && i >= GHOSTS.size() - 3; i--) {
            Ghost ghost = GHOSTS.get(i);
            double left = 1 - (now - ghost.atMs()) / (double) LIFE_MS;
            int color = WorldDraw.fade(ghost.you() ? 0xFF39FF14 : 0xFFFF4040, Math.min(1, left * 2.5));
            int x = graphics.guiWidth() / 2 - minecraft.font.width(ghost.label()) / 2;
            graphics.text(minecraft.font, ghost.label(), x, y, color);
            y -= 11;
        }
    }

    private static void render(LevelRenderContext context) {
        if (!enabled || broken || GHOSTS.isEmpty() || HudFilter.isHideAll()) {
            return;
        }
        try {
            draw(context);
        } catch (RuntimeException error) {
            if (!drawFailed) {
                drawFailed = true;
                SpearLog.log("pop chams: drawing failed, skipped this frame: " + error);
            }
        } catch (LinkageError error) {
            broken = true;
            SpearLog.log("pop chams: switched off, this game version draws differently (" + error + ")");
        }
    }

    private static void draw(LevelRenderContext context) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        long now = now();
        Vec3 camera = minecraft.gameRenderer.getMainCamera().position();
        boolean firstPerson = minecraft.options.getCameraType().isFirstPerson();
        PoseStack pose = context.poseStack();
        RenderType type = RenderTypes.debugQuads();
        VertexConsumer buffer = context.bufferSource().getBuffer(type);
        for (Iterator<Ghost> it = GHOSTS.iterator(); it.hasNext(); ) {
            Ghost ghost = it.next();
            double age = (now - ghost.atMs()) / (double) LIFE_MS;
            if (age > 1 || ghost.you() && firstPerson) {
                continue; // your own ghost would sit on your camera; the screen line says it
            }
            double eased = 1 - Math.pow(1 - age, 2);
            Vec3 at = ghost.feet().add(0, RISE * eased, 0).subtract(camera);
            int color = WorldDraw.fade(ghost.you() ? YOU : THEM, 1 - age);
            pose.pushPose();
            pose.translate(at.x, at.y, at.z);
            pose.mulPose(new Quaternionf().rotateY((float) -Math.toRadians(ghost.bodyYaw())));
            float scale = ghost.height() / 1.8F;
            pose.scale(scale, scale, scale);
            drawBody(pose.last(), buffer, color);
            pose.popPose();
        }
        context.bufferSource().endBatch(type);
        for (Ghost ghost : GHOSTS) {
            double age = (now - ghost.atMs()) / (double) LIFE_MS;
            if (age > 1 || ghost.you() && firstPerson) {
                continue;
            }
            Vec3 at = ghost.feet().add(0, ghost.height() + 0.35 + RISE * (1 - Math.pow(1 - age, 2)), 0).subtract(camera);
            WorldDraw.text(minecraft, pose, context.bufferSource(), at, ghost.label(),
                    WorldDraw.fade(ghost.you() ? 0xFF39FF14 : 0xFFFF4040, 1 - age), 0.025F);
        }
        context.bufferSource().endBatch();
    }

    /** A blocky player: head, body, two arms and two legs, in pixels of 1/16 block. */
    private static void drawBody(PoseStack.Pose pose, VertexConsumer buffer, int color) {
        float p = 1 / 16.0F;
        WorldDraw.box(pose, buffer, -4 * p, 24 * p, -4 * p, 4 * p, 32 * p, 4 * p, color);
        WorldDraw.box(pose, buffer, -4 * p, 12 * p, -2 * p, 4 * p, 24 * p, 2 * p, color);
        WorldDraw.box(pose, buffer, -8 * p, 12 * p, -2 * p, -4 * p, 24 * p, 2 * p, color);
        WorldDraw.box(pose, buffer, 4 * p, 12 * p, -2 * p, 8 * p, 24 * p, 2 * p, color);
        WorldDraw.box(pose, buffer, -4 * p, 0, -2 * p, 0, 12 * p, 2 * p, color);
        WorldDraw.box(pose, buffer, 0, 0, -2 * p, 4 * p, 12 * p, 2 * p, color);
    }

    /** Totems this player popped since they last died. */
    static int lifePops(UUID player) {
        return POPS.getOrDefault(player, 0);
    }

    /** Totems this player popped in earlier lives. */
    static int olderPops(UUID player) {
        return OLDER.getOrDefault(player, 0);
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
        GHOSTS.clear();
        LAST_ABSORPTION.clear();
        POPS.clear();
        OLDER.clear();
    }
}
