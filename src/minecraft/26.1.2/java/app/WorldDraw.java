package app;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.Vec3;

/** Small drawing helpers for things shown in the world (Pop Chams, Damage Particles). */
final class WorldDraw {
    private WorldDraw() {}

    /** A see-through box from (x1, y1, z1) to (x2, y2, z2), all six sides, seen from both sides. */
    static void box(PoseStack.Pose pose, VertexConsumer buffer, float x1, float y1, float z1,
            float x2, float y2, float z2, int color) {
        quad(pose, buffer, x1, y1, z1, x2, y1, z1, x2, y1, z2, x1, y1, z2, color);
        quad(pose, buffer, x1, y2, z1, x2, y2, z1, x2, y2, z2, x1, y2, z2, color);
        quad(pose, buffer, x1, y1, z1, x2, y1, z1, x2, y2, z1, x1, y2, z1, color);
        quad(pose, buffer, x1, y1, z2, x2, y1, z2, x2, y2, z2, x1, y2, z2, color);
        quad(pose, buffer, x1, y1, z1, x1, y1, z2, x1, y2, z2, x1, y2, z1, color);
        quad(pose, buffer, x2, y1, z1, x2, y1, z2, x2, y2, z2, x2, y2, z1, color);
    }

    private static void quad(PoseStack.Pose pose, VertexConsumer buffer, float ax, float ay, float az,
            float bx, float by, float bz, float cx, float cy, float cz, float dx, float dy, float dz, int color) {
        buffer.addVertex(pose, ax, ay, az).setColor(color);
        buffer.addVertex(pose, bx, by, bz).setColor(color);
        buffer.addVertex(pose, cx, cy, cz).setColor(color);
        buffer.addVertex(pose, dx, dy, dz).setColor(color);
        buffer.addVertex(pose, dx, dy, dz).setColor(color);
        buffer.addVertex(pose, cx, cy, cz).setColor(color);
        buffer.addVertex(pose, bx, by, bz).setColor(color);
        buffer.addVertex(pose, ax, ay, az).setColor(color);
    }

    /** Text facing the camera at a spot in the world (relative to the camera), like a name tag. */
    static void text(Minecraft minecraft, PoseStack pose, MultiBufferSource buffers, Vec3 at, String text,
            int color, float scale) {
        pose.pushPose();
        pose.translate(at.x, at.y, at.z);
        pose.mulPose(minecraft.gameRenderer.getMainCamera().rotation());
        pose.scale(scale, -scale, scale);
        Font font = minecraft.font;
        font.drawInBatch(text, -font.width(text) / 2.0F, 0, color, true, pose.last().pose(), buffers,
                Font.DisplayMode.NORMAL, 0, LightCoordsUtil.FULL_BRIGHT);
        pose.popPose();
    }

    /** The color with its alpha scaled by this much (0 to 1). */
    static int fade(int color, double amount) {
        int alpha = (int) Math.round(((color >>> 24) & 0xFF) * Math.max(0, Math.min(1, amount)));
        return (Math.max(alpha, 0) << 24) | (color & 0xFFFFFF);
    }
}
