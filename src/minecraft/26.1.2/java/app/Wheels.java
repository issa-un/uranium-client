package app;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * Wheels: a wheel floating above players' heads, drawn on your screen only (other players
 * never see it). Spin: Notch per hit turns a player's wheel one notch each time they take
 * a hit, so you can see who has been hit how many times; Always spins it nonstop. Who:
 * everyone, only who you're fighting (whoever last hit you or you last hit), or just you
 * (seen in third person). Helmet: Replace hides their helmet on your screen and puts the
 * wheel in its place; Above keeps the helmet and floats the wheel over it. Your own helmet
 * is never hidden, so your armor slot stays as it is. Drawn through Fabric API's level
 * rendering, after the world's see-through parts.
 */
final class Wheels {
    private static final String[] SPINS = {"Notch per hit", "Always"};
    private static final String[] WHO = {"Everyone", "Who you fight", "Just you"};
    private static final String[] HELMETS = {"Replace", "Above"};
    private static final double RADIUS = 0.42;
    private static final double RIM = 0.06;
    private static final double SPOKE = 0.018;
    private static final long NOTCH_MS = 450;
    private static final long FIGHT_MS = 10_000;
    private static final int RIM_COLOR = 0xFFE8D9A8;
    private static final int GOLD = 0xFFB89B5E;
    private static final int DARK = 0xFF8A6F3A;
    private static volatile boolean enabled;
    private static volatile int spin;
    private static volatile int who;
    private static volatile int helmet;
    // Once per injected version: Fabric API events cannot be taken back, so Unload only
    // switches this off.
    private static boolean registered;
    private static volatile boolean active;
    // The drawing failed to link on this game version; set once, for the session.
    private static volatile boolean broken;
    private static boolean drawFailed;

    /** One player's wheel: how many hits it has turned for, and when the last one came. */
    private static final class Wheel {
        int notches;
        long notchAtMs;
        int lastHurtTime;
    }

    // Render thread only.
    private static final Map<Integer, Wheel> WHEELS = new HashMap<>();
    private static final Map<Integer, ItemStack> HIDDEN_HELMETS = new HashMap<>();
    private static Player fighting;
    private static long fightingAtMs;

    private Wheels() {}

    /** Called from the input loop: counts hits, keeps helmets hidden, hooks the drawing once. */
    static void update(Minecraft minecraft) {
        if (!registered) {
            registered = true;
            try {
                LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(Wheels::render);
                active = true;
            } catch (RuntimeException | LinkageError exception) {
                SpearLog.log("wheels: could not hook world drawing (" + exception + ")");
            }
        }
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null) {
            WHEELS.clear();
            HIDDEN_HELMETS.clear();
            return;
        }
        if (!enabled || broken || HudFilter.isHideAll()) {
            restoreHelmets(minecraft); // no hidden helmets without wheels to replace them
            return;
        }
        long now = nowMs();
        trackFight(player, now);
        for (Player other : minecraft.level.players()) {
            Wheel wheel = WHEELS.computeIfAbsent(other.getId(), id -> new Wheel());
            if (other.hurtTime > wheel.lastHurtTime) {
                wheel.notches++;
                wheel.notchAtMs = now;
            }
            wheel.lastHurtTime = other.hurtTime;
            boolean hide = helmet == 0 && other != player && shows(minecraft, other, now);
            ItemStack head = other.getItemBySlot(EquipmentSlot.HEAD);
            if (hide && !head.isEmpty()) {
                // The server's copy changes when they swap helmets; keep the newest.
                HIDDEN_HELMETS.put(other.getId(), head.copy());
                other.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
            } else if (!hide && HIDDEN_HELMETS.containsKey(other.getId())) {
                restore(other);
            }
        }
        WHEELS.keySet().removeIf(id -> minecraft.level.getEntity(id) == null);
        HIDDEN_HELMETS.keySet().removeIf(id -> minecraft.level.getEntity(id) == null);
    }

    private static void trackFight(LocalPlayer player, long now) {
        Player attacker = player.getLastHurtByMob() instanceof Player p ? p : null;
        Player hit = player.getLastHurtMob() instanceof Player p
                && player.tickCount - player.getLastHurtMobTimestamp() < FIGHT_MS / 50 ? p : null;
        Player latest = attacker != null ? attacker : hit;
        if (latest != null && latest != player && latest.isAlive()) {
            fighting = latest;
            fightingAtMs = now;
        }
        if (fighting != null && (!fighting.isAlive() || fighting.isRemoved() || now - fightingAtMs > FIGHT_MS)) {
            fighting = null;
        }
    }

    private static boolean shows(Minecraft minecraft, Player other, long now) {
        return switch (who) {
            case 0 -> true;
            case 1 -> other == fighting;
            default -> other == minecraft.player;
        };
    }

    /**
     * Fabric calls this every frame. On a game version whose drawing code moved (26.2 dropped
     * MultiBufferSource and getMainCamera), the first frame fails to link; the wheels then
     * switch off for the session instead of taking the frame, or the game, down with them.
     */
    private static void render(LevelRenderContext context) {
        if (HudFilter.isHideAll()) {
            return;
        }
        if (!active || !enabled || broken) {
            return;
        }
        try {
            draw(context);
        } catch (RuntimeException error) {
            // Skips this frame instead of crashing the game; logged once.
            if (!drawFailed) {
                drawFailed = true;
                SpearLog.log("wheels: drawing failed, skipped this frame: " + error);
            }
        } catch (LinkageError error) {
            broken = true;
            MinecraftSpearDash.setStatus("Wheels don't work on this version");
            SpearLog.log("wheels: switched off, this game version draws differently (" + error + ")");
        }
    }

    private static void draw(LevelRenderContext context) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        long now = nowMs();
        float partialTick = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        Vec3 camera = minecraft.gameRenderer.getMainCamera().position();
        boolean firstPerson = minecraft.options.getCameraType().isFirstPerson();
        PoseStack pose = context.poseStack();
        RenderType type = RenderTypes.debugQuads();
        VertexConsumer buffer = context.bufferSource().getBuffer(type);
        for (Player other : minecraft.level.players()) {
            if (!shows(minecraft, other, now) || other.isInvisible() || other == minecraft.player && firstPerson) {
                continue;
            }
            Wheel wheel = WHEELS.get(other.getId());
            float angle;
            if (spin == 1) {
                angle = (float) ((now % 100_000) / 1000.0 * 1.6);
            } else {
                int notches = wheel == null ? 0 : wheel.notches;
                double turn = wheel == null ? 1 : Math.min(1, (now - wheel.notchAtMs) / (double) NOTCH_MS);
                double eased = notches == 0 ? 0 : 1 + 2.2 * Math.pow(turn - 1, 3) + 1.2 * Math.pow(turn - 1, 2);
                angle = (float) ((Math.max(0, notches - 1) + Math.min(1.1, eased)) * Math.PI / 4);
            }
            double lift = helmet == 0 ? 0.18 : 0.45;
            Vec3 at = other.getPosition(partialTick).add(0, other.getBbHeight() + lift, 0).subtract(camera);
            pose.pushPose();
            pose.translate(at.x, at.y, at.z);
            pose.mulPose(new Quaternionf().rotateY(angle));
            drawWheel(pose.last(), buffer);
            pose.popPose();
        }
        context.bufferSource().endBatch(type);
    }

    /** The wheel, flat like a halo: a rim, eight spokes with knobs, and a hub. */
    private static void drawWheel(PoseStack.Pose pose, VertexConsumer buffer) {
        int segments = 28;
        for (int i = 0; i < segments; i++) {
            double a1 = i * Math.PI * 2 / segments;
            double a2 = (i + 1) * Math.PI * 2 / segments;
            quad(pose, buffer,
                    Math.cos(a1) * RADIUS, Math.sin(a1) * RADIUS,
                    Math.cos(a2) * RADIUS, Math.sin(a2) * RADIUS,
                    Math.cos(a2) * (RADIUS - RIM), Math.sin(a2) * (RADIUS - RIM),
                    Math.cos(a1) * (RADIUS - RIM), Math.sin(a1) * (RADIUS - RIM), 0, RIM_COLOR);
        }
        for (int k = 0; k < 8; k++) {
            double a = k * Math.PI / 4;
            double c = Math.cos(a);
            double s = Math.sin(a);
            double px = -s * SPOKE;
            double pz = c * SPOKE;
            double inner = 0.06;
            double outer = RADIUS + 0.04;
            quad(pose, buffer, c * inner + px, s * inner + pz, c * outer + px, s * outer + pz,
                    c * outer - px, s * outer - pz, c * inner - px, s * inner - pz, 0.002, RIM_COLOR);
            double knob = 0.045;
            double kx = c * (RADIUS + 0.08);
            double kz = s * (RADIUS + 0.08);
            quad(pose, buffer, kx - knob, kz - knob, kx + knob, kz - knob, kx + knob, kz + knob,
                    kx - knob, kz + knob, 0.003, GOLD);
        }
        quad(pose, buffer, -0.07, -0.07, 0.07, -0.07, 0.07, 0.07, -0.07, 0.07, 0.004, RIM_COLOR);
        quad(pose, buffer, -0.03, -0.03, 0.03, -0.03, 0.03, 0.03, -0.03, 0.03, 0.005, DARK);
    }

    /** A flat four-corner shape at height y, both sides so it shows from above and below. */
    private static void quad(PoseStack.Pose pose, VertexConsumer buffer, double x1, double z1, double x2, double z2,
            double x3, double z3, double x4, double z4, double y, int color) {
        float fy = (float) y;
        buffer.addVertex(pose, (float) x1, fy, (float) z1).setColor(color);
        buffer.addVertex(pose, (float) x2, fy, (float) z2).setColor(color);
        buffer.addVertex(pose, (float) x3, fy, (float) z3).setColor(color);
        buffer.addVertex(pose, (float) x4, fy, (float) z4).setColor(color);
        buffer.addVertex(pose, (float) x4, fy, (float) z4).setColor(color);
        buffer.addVertex(pose, (float) x3, fy, (float) z3).setColor(color);
        buffer.addVertex(pose, (float) x2, fy, (float) z2).setColor(color);
        buffer.addVertex(pose, (float) x1, fy, (float) z1).setColor(color);
    }

    private static void restoreHelmets(Minecraft minecraft) {
        if (HIDDEN_HELMETS.isEmpty() || minecraft.level == null) {
            HIDDEN_HELMETS.clear();
            return;
        }
        for (Iterator<Integer> it = HIDDEN_HELMETS.keySet().iterator(); it.hasNext(); ) {
            Entity entity = minecraft.level.getEntity(it.next());
            if (entity instanceof Player other && other.getItemBySlot(EquipmentSlot.HEAD).isEmpty()) {
                other.setItemSlot(EquipmentSlot.HEAD, HIDDEN_HELMETS.get(other.getId()));
            }
            it.remove();
        }
    }

    private static void restore(Player other) {
        ItemStack saved = HIDDEN_HELMETS.remove(other.getId());
        if (saved != null && other.getItemBySlot(EquipmentSlot.HEAD).isEmpty()) {
            other.setItemSlot(EquipmentSlot.HEAD, saved);
        }
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

    static int spin() {
        return spin;
    }

    static String spinLabel() {
        return SPINS[spin];
    }

    static void setSpin(int value) {
        spin = Math.floorMod(value, SPINS.length);
    }

    static void cycleSpin() {
        spin = (spin + 1) % SPINS.length;
    }

    static int who() {
        return who;
    }

    static String whoLabel() {
        return WHO[who];
    }

    static void setWho(int value) {
        who = Math.floorMod(value, WHO.length);
    }

    static void cycleWho() {
        who = (who + 1) % WHO.length;
    }

    static int helmet() {
        return helmet;
    }

    static String helmetLabel() {
        return HELMETS[helmet];
    }

    static void setHelmet(int value) {
        helmet = Math.floorMod(value, HELMETS.length);
    }

    static void cycleHelmet() {
        helmet = (helmet + 1) % HELMETS.length;
    }

    /** Puts helmets back and stops drawing when this injection is unloaded. */
    static void reset(Minecraft minecraft) {
        restoreHelmets(minecraft);
        enabled = false;
        active = false;
        WHEELS.clear();
        fighting = null;
    }
}
