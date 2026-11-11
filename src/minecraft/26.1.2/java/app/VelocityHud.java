package app;

import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3x2fStack;

/**
 * The SPEAR category's HUD pieces. Both rings turn with your view (up = the way you face).
 * Speed HUD, above the hotbar: the orange arrow is your velocity, the red arrow the push
 * the next Lunge would give (dim while charging), and a red arc around the ring fills as
 * the spear charges. Above it: speed and predicted push in blocks per second, plus food
 * and durability warnings; your up/down look angle sits beside the hotbar.
 * Radar, around the crosshair: a triangle outside the ring points at the nearest player in
 * range, flashing when they are out of view behind you or dropping on you from above.
 * Landing, under the crosshair while falling: distance to the ground and the fall damage.
 */
final class VelocityHud {
    // Every build lives in its own package (see build.gradle.kts), and Fabric keeps the
    // name of a removed element, so each build's HUD needs a name of its own.
    private static final Identifier ID = Identifier.fromNamespaceAndPath("app",
            "velocity_hud_" + VelocityHud.class.getPackageName().replace('.', '_'));
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static final int ALERT_ABOVE_CROSSHAIR = 44;
    // Right of the radar ring and its triangles, level with the crosshair.
    private static final int BLOCKING_RIGHT_OF_CROSSHAIR = 34;
    // Distance from the bottom of the screen to the speed ring's center, Low and High.
    // Low sits just above the hearts and food; High also clears action bar messages.
    private static final int LOW_CENTER_ABOVE_BOTTOM = 84;
    private static final int HIGH_CENTER_ABOVE_BOTTOM = 108;
    private static final int RADIUS = 16;
    private static final int RING_SEGMENTS = 48;
    private static final int ARROW_MAX = RADIUS - 2;
    private static final int ARROW_HEAD = 4;
    // The orange arrow reaches the ring at this speed (sprint-jumping is about 7).
    private static final double FULL_SCALE_SPEED = 10;
    // Below this you are standing still and the direction is just noise.
    private static final double MIN_SPEED = 0.05;
    // Lunge adds 0.458 blocks per tick of push per level and burns 4 exhaustion per level,
    // which is one point of saturation or food (data/minecraft/enchantment/lunge.json).
    private static final double LUNGE_PUSH_PER_LEVEL = 0.458 * 20;
    private static final int LUNGE_MIN_FOOD = 7;
    private static final int DURABILITY_WARN = 15;
    private static final int DURABILITY_DANGER = 3;
    private static final int HOTBAR_HALF_WIDTH = 91;
    // Clears the attack indicator (Hotbar mode) and a left-handed offhand slot.
    private static final int ANGLE_GAP_FROM_HOTBAR = 33;
    // Wide enough to leave the crosshair itself clear.
    private static final int RADAR_RADIUS = 22;
    private static final int RADAR_TRIANGLE_ROWS = 6;
    private static final int[] RADAR_RANGES = {6, 10, 16};
    private static final double DANGER_RANGE = 4;
    private static final double ABOVE_HEIGHT = 2;
    // Falling faster than this many blocks per tick counts as dropping on you.
    private static final double DROPPING_SPEED = 0.2;
    // Farthest a player can be for the menu to offer them as a friend.
    private static final double FRIEND_PICK_RANGE = 64;
    private static final int LANDING_BELOW_CROSSHAIR = 44;
    private static final double LANDING_SCAN_DEPTH = 64;
    private static final int DISC_COLOR = 0x66000000;
    private static final int RING_COLOR = 0xB0FFFFFF;
    private static final int RADAR_RING_COLOR = 0x40FFFFFF;
    private static final int ORANGE = 0xFFFF9A2E;
    private static final int RED = 0xFFFF4545;
    private static final int RED_DIM = 0x80FF4545;
    private static final int YELLOW = 0xFFFFD23F;
    private static final int GREEN = 0xFF6BE07A;
    private static final int WHITE = 0xFFFFFFFF;

    private static volatile boolean speedHudOn = true;
    private static volatile boolean speedHudHigh;
    private static volatile boolean warningsOn = true;
    private static volatile boolean radarOn = true;
    private static volatile int radarRangeIndex = 1;
    private static volatile boolean ignoreTeam = true;
    private static volatile boolean landingOn = true;

    // HUD pieces you can move in the HUD editor, as offsets from where they sit by default.
    static final int ELEMENTS = 4;
    static final int SPEED_RING = 0;
    static final int LOOK_ANGLE = 1;
    static final int LANDING = 2;
    static final int COMBAT_STATS = 3;
    private static final String[] ELEMENT_KEYS = {"speed", "angle", "landing", "combatStats"};
    private static final String[] ELEMENT_NAMES = {"Speed HUD", "Look angle", "Landing", "Combat stats"};
    private static final int[] OFFSET_X = new int[ELEMENTS];
    private static final int[] OFFSET_Y = new int[ELEMENTS];

    // Keys that switch a HUD module on or off without the menu; -1 means none.
    static final int TOGGLES = 4;
    static final int TOGGLE_SPEED = 0;
    static final int TOGGLE_RADAR = 1;
    static final int TOGGLE_LANDING = 2;
    static final int TOGGLE_HIDE = 3;
    private static final String[] TOGGLE_NAMES = {"speed", "radar", "landing", "hide"};
    private static final int[] TOGGLE_KEY = {-1, -1, -1, -1};
    private static final int[] TOGGLE_MOUSE = {-1, -1, -1, -1};
    private static volatile int awaitingToggle = -1;
    private static volatile boolean available = true;
    private static volatile boolean active;
    private static boolean drawFailed;
    // Lower-case names; read on the render thread, changed from the menu on the same thread.
    private static final Set<String> FRIENDS = new TreeSet<>();

    private VelocityHud() {}

    /**
     * Fabric's removeElement leaves a placeholder that keeps the name, so adding the same
     * name again (Unload, then Open GUI) would fail. The element is registered once and
     * drawing is switched on and off instead.
     */
    static void install() {
        active = true;
        if (!REGISTERED.compareAndSet(false, true)) {
            return;
        }
        try {
            HudElementRegistry.addLast(ID, VelocityHud::extractRenderState);
        } catch (RuntimeException | LinkageError exception) {
            available = false; // Fabric API's HUD module is missing or changed
        }
    }

    /** Takes the HUD off the screen when this injection is unloaded. */
    static void uninstall() {
        active = false;
    }

    private static void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        if (!active) {
            return;
        }
        try {
            draw(graphics, deltaTracker);
        } catch (RuntimeException error) {
            // A HUD bug must never take the frame, or the game, down; logged once.
            if (!drawFailed) {
                drawFailed = true;
                SpearLog.log("hud: drawing failed, skipped this frame: " + error);
            }
        }
    }

    private static void draw(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        // Every frame, so a Flat lunge glide is smooth; the input loop covers it when the
        // HUD is not drawn (F1).
        MinecraftSpearDash.updateDash(minecraft, false);
        AimHelper.update(minecraft);
        WorldSlash.update(minecraft);
        if (HudFilter.isHideAll()) {
            return; // Hide UI: nothing of Uranium's on screen, only the game's own HUD
        }
        LocalPlayer player = minecraft.player;
        if (player == null || Mc.hudHidden(minecraft)) {
            return;
        }
        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(true);
        double yawRadians = Math.toRadians(player.getViewYRot(partialTick));
        if (speedHudOn) {
            drawSpeedRing(graphics, minecraft, player, partialTick, yawRadians);
        }
        if (radarOn) {
            drawRadar(graphics, minecraft, player, yawRadians);
        }
        if (landingOn) {
            drawLanding(graphics, minecraft, player);
        }
        SlamMeter.draw(graphics, minecraft);
        PunishAlerts.draw(graphics, minecraft);
        if (CombatStats.isOn()) {
            int[] panel = position(COMBAT_STATS, graphics.guiWidth(), graphics.guiHeight());
            CombatStats.draw(graphics, minecraft, player, panel[0], panel[1]);
        }
        if (ShieldBreak.isEnabled() && ShieldBreak.blockingTarget(minecraft) != null) {
            boolean axe = ShieldBreak.hasAxe(minecraft);
            graphics.text(minecraft.font, axe ? "BLOCKING" : "BLOCKING (no axe)",
                    graphics.guiWidth() / 2 + BLOCKING_RIGHT_OF_CROSSHAIR,
                    graphics.guiHeight() / 2 - 4, axe ? YELLOW : RED);
        }
        String alert = MinecraftSpearDash.activeAlert();
        if (alert != null) {
            graphics.centeredText(minecraft.font, alert, graphics.guiWidth() / 2,
                    graphics.guiHeight() / 2 - ALERT_ABOVE_CROSSHAIR, RED);
        }
    }

    private static void drawSpeedRing(GuiGraphicsExtractor graphics, Minecraft minecraft,
            LocalPlayer player, float partialTick, double yawRadians) {
        // Distance moved during the last tick, so knockback, lunges and falling all count.
        double velocityX = (player.getX() - player.xo) * 20;
        double velocityZ = (player.getZ() - player.zo) * 20;
        double speed = Math.sqrt(velocityX * velocityX + velocityZ * velocityZ);
        // Angle of your velocity from where you look, clockwise.
        float moveAngle = (float) (Math.atan2(-velocityX, velocityZ) - yawRadians);
        float pitch = player.getViewXRot(partialTick);

        ItemStack spear = MinecraftSpearDash.lungeSpear(minecraft);
        int lungeLevel = MinecraftSpearDash.lungeLevel(spear);
        double fullPush = lungeLevel * LUNGE_PUSH_PER_LEVEL;
        double push = 0;
        if (lungeLevel > 0 && MinecraftSpearDash.lungeCanPush(player)) {
            // Lunge pushes along your look with the vertical part removed; Flat lunge
            // levels you first, so it always gets the full push.
            double lookFactor = MinecraftSpearDash.isFlatLungeOn()
                    ? 1
                    : Math.cos(Math.toRadians(pitch));
            push = fullPush * lookFactor;
        }
        // Spears need a full charge to jab, so the arc is full exactly when one can go.
        float charge = player.getAttackStrengthScale(0);
        boolean charged = lungeLevel > 0 && !player.cannotAttackWithItem(spear, 0);
        int pushColor = charged ? RED : RED_DIM;

        int[] ring = position(SPEED_RING, graphics.guiWidth(), graphics.guiHeight());
        int centerX = ring[0];
        int centerY = ring[1];
        drawDisc(graphics, centerX, centerY, RADIUS);
        drawRing(graphics, centerX, centerY, RADIUS, RING_COLOR, RING_SEGMENTS);
        if (lungeLevel > 0) {
            drawRing(graphics, centerX, centerY, RADIUS, pushColor,
                    Math.round(charge * RING_SEGMENTS));
        }
        if (push >= MIN_SPEED) {
            // Full length is this spear's full push, so a short arrow means a weak lunge.
            drawArrow(graphics, centerX, centerY, 0, push / fullPush, pushColor);
        }
        if (speed >= MIN_SPEED) {
            drawArrow(graphics, centerX, centerY, moveAngle, speed / FULL_SCALE_SPEED, ORANGE);
        } else {
            graphics.fill(centerX - 1, centerY - 1, centerX + 1, centerY + 1, ORANGE);
        }
        Font font = minecraft.font;
        int numbersY = centerY - RADIUS - 11;
        String pushLabel = lungeLevel > 0 ? "+" + format(push) + " push" : "no Lunge";
        drawNumbers(graphics, font, centerX, numbersY,
                new String[] {format(speed) + " b/s", pushLabel}, new int[] {ORANGE, pushColor});
        if (warningsOn && lungeLevel > 0) {
            drawWarnings(graphics, font, centerX, numbersY - 10, player, spear, lungeLevel);
        }

        // Minecraft counts looking down as positive; shown the other way round.
        int[] angle = position(LOOK_ANGLE, graphics.guiWidth(), graphics.guiHeight());
        graphics.text(font, formatDegrees(-pitch), angle[0], angle[1], WHITE);
    }

    /** Food and spear durability warnings, stacked upwards above the numbers. */
    private static void drawWarnings(GuiGraphicsExtractor graphics, Font font, int centerX, int y,
            LocalPlayer player, ItemStack spear, int lungeLevel) {
        if (!player.isCreative()) {
            int food = player.getFoodData().getFoodLevel();
            // Each lunge takes one point per level, from saturation first.
            double foodAfter = food - Math.max(0, lungeLevel - player.getFoodData().getSaturationLevel());
            if (food < LUNGE_MIN_FOOD) {
                graphics.centeredText(font, "Too hungry to lunge", centerX, y, RED);
                y -= 10;
            } else if (foodAfter < LUNGE_MIN_FOOD) {
                graphics.centeredText(font, "Last lunge: eat", centerX, y, YELLOW);
                y -= 10;
            }
        }
        if (spear.isDamageableItem()) {
            int usesLeft = spear.getMaxDamage() - spear.getDamageValue();
            if (usesLeft <= DURABILITY_DANGER) {
                graphics.centeredText(font, "Spear breaking: " + usesLeft, centerX, y, RED);
            } else if (usesLeft <= DURABILITY_WARN) {
                graphics.centeredText(font, "Spear: " + usesLeft + " uses", centerX, y, YELLOW);
            }
        }
    }

    private static void drawRadar(GuiGraphicsExtractor graphics, Minecraft minecraft,
            LocalPlayer player, double yawRadians) {
        Player threat = null;
        double distance = RADAR_RANGES[radarRangeIndex];
        for (Player other : minecraft.level.players()) {
            if (!isRadarTarget(player, other)) {
                continue;
            }
            double otherDistance = player.distanceTo(other);
            if (otherDistance < distance) {
                distance = otherDistance;
                threat = other;
            }
        }

        // Centred on the crosshair; faint so it does not get in the way of aiming.
        int centerX = graphics.guiWidth() / 2;
        int centerY = graphics.guiHeight() / 2;
        drawRing(graphics, centerX, centerY, RADAR_RADIUS, RADAR_RING_COLOR, RING_SEGMENTS);
        if (threat == null) {
            return;
        }

        double dx = threat.getX() - player.getX();
        double dz = threat.getZ() - player.getZ();
        float angle = (float) (Math.atan2(-dx, dz) - yawRadians);
        // More than 60 degrees off where you face, so most likely off screen.
        boolean behind = Math.cos(angle) < 0.5;
        boolean dropping = threat.getY() - player.getY() > ABOVE_HEIGHT
                && threat.yo - threat.getY() > DROPPING_SPEED;
        boolean danger = distance < DANGER_RANGE || dropping;
        int color = danger ? RED : YELLOW;
        long flashPeriodMs = danger ? 100 : 250;
        boolean shown = !(behind || dropping)
                || (System.nanoTime() / 1_000_000 / flashPeriodMs) % 2 == 0;
        if (shown) {
            drawTriangle(graphics, centerX, centerY, angle, color);
        }
        String label = (dropping ? "↑ " : "") + String.format(Locale.ROOT, "%.1fm", distance);
        graphics.centeredText(minecraft.font, label, centerX, centerY + RADAR_RADIUS + 9, color);
    }

    private static boolean isRadarTarget(LocalPlayer player, Player other) {
        return other != player && other.isAlive() && !other.isSpectator() && !other.isInvisible()
                && !(ignoreTeam && player.isAlliedTo(other))
                && !FRIENDS.contains(nameOf(other));
    }

    /** What a fall will end in, for the Landing readout and MLG Water. */
    enum LandingKind { NOT_FALLING, BEYOND_SCAN, WATER, LAVA, SLOW_FALLING, SAFE, DAMAGE }

    /** The ground below you while falling: how far, what it is, and the fall damage there. */
    record Landing(LandingKind kind, double drop, int damage, BlockPos ground) {
        boolean fatal(LocalPlayer player) {
            return kind == LandingKind.DAMAGE && damage >= player.getHealth() + player.getAbsorptionAmount();
        }
    }

    /**
     * Predicts the landing while falling, with the game's own formula: floor(fall distance so
     * far + drop - safe fall distance) x fall damage multiplier. Feather Falling, Protection
     * and soft blocks such as hay or slime lower the real damage further.
     */
    static Landing predictLanding(Minecraft minecraft, LocalPlayer player) {
        if (player.onGround() || player.getY() >= player.yo || player.getAbilities().flying
                || player.isFallFlying() || player.isPassenger() || player.isInWater()) {
            return new Landing(LandingKind.NOT_FALLING, 0, 0, null);
        }
        Vec3 feet = player.position();
        BlockHitResult ground = minecraft.level.clip(new ClipContext(feet,
                feet.subtract(0, LANDING_SCAN_DEPTH, 0), ClipContext.Block.COLLIDER,
                ClipContext.Fluid.ANY, player));
        if (ground.getType() == HitResult.Type.MISS) {
            return new Landing(LandingKind.BEYOND_SCAN, LANDING_SCAN_DEPTH, 0, null);
        }
        double drop = feet.y - ground.getLocation().y;
        BlockPos groundPos = ground.getBlockPos();
        FluidState fluid = minecraft.level.getFluidState(groundPos);
        if (fluid.is(FluidTags.WATER)) {
            return new Landing(LandingKind.WATER, drop, 0, groundPos);
        }
        if (fluid.is(FluidTags.LAVA)) {
            return new Landing(LandingKind.LAVA, drop, 0, groundPos);
        }
        if (player.hasEffect(MobEffects.SLOW_FALLING)) {
            return new Landing(LandingKind.SLOW_FALLING, drop, 0, groundPos);
        }
        double fall = player.fallDistance + drop;
        int damage = (int) Math.floor((fall + 1.0E-6 - player.getAttributeValue(Attributes.SAFE_FALL_DISTANCE))
                * player.getAttributeValue(Attributes.FALL_DAMAGE_MULTIPLIER));
        return new Landing(damage <= 0 ? LandingKind.SAFE : LandingKind.DAMAGE, drop, Math.max(0, damage), groundPos);
    }

    /** Distance to the ground below and what the landing will do, while falling. */
    private static void drawLanding(GuiGraphicsExtractor graphics, Minecraft minecraft, LocalPlayer player) {
        Landing landing = predictLanding(minecraft, player);
        if (landing.kind() == LandingKind.NOT_FALLING) {
            return;
        }
        int[] line = position(LANDING, graphics.guiWidth(), graphics.guiHeight());
        int centerX = line[0];
        int y = line[1];
        Font font = minecraft.font;
        if (landing.kind() == LandingKind.BEYOND_SCAN) {
            graphics.centeredText(font, "\u2193 over " + (int) LANDING_SCAN_DEPTH + "m", centerX, y, YELLOW);
            return;
        }
        String distance = String.format(Locale.ROOT, "\u2193 %.1fm  ", landing.drop());
        switch (landing.kind()) {
            case WATER -> graphics.centeredText(font, distance + "water, safe", centerX, y, GREEN);
            case LAVA -> graphics.centeredText(font, distance + "LAVA", centerX, y, RED);
            case SLOW_FALLING -> graphics.centeredText(font, distance + "slow falling", centerX, y, GREEN);
            case SAFE -> graphics.centeredText(font, distance + "safe", centerX, y, GREEN);
            default -> {
                boolean fatal = landing.fatal(player);
                String hearts = String.format(Locale.ROOT, "-%.1f\u2764", landing.damage() / 2.0);
                graphics.centeredText(font, distance + (fatal ? "FATAL " : "") + hearts,
                        centerX, y, fatal ? RED : YELLOW);
            }
        }
    }

    private static void drawNumbers(GuiGraphicsExtractor graphics, Font font, int centerX, int y,
            String[] labels, int[] colors) {
        int gap = 6;
        int totalWidth = gap * (labels.length - 1);
        for (String label : labels) {
            totalWidth += font.width(label);
        }
        int x = centerX - totalWidth / 2;
        for (int i = 0; i < labels.length; i++) {
            graphics.text(font, labels[i], x, y, colors[i]);
            x += font.width(labels[i]) + gap;
        }
    }

    private static String format(double value) {
        // Avoid "-0.00" when a tiny negative value rounds to zero.
        return String.format(Locale.ROOT, "%.2f", Math.abs(value) < 0.005 ? 0 : value);
    }

    private static String formatDegrees(double degrees) {
        return String.format(Locale.ROOT, "%.1f°", Math.abs(degrees) < 0.05 ? 0 : degrees);
    }

    private static void drawDisc(GuiGraphicsExtractor graphics, int centerX, int centerY, int radius) {
        for (int dy = -radius; dy < radius; dy++) {
            double rowMiddle = dy + 0.5;
            int half = (int) Math.round(Math.sqrt(radius * radius - rowMiddle * rowMiddle));
            graphics.fill(centerX - half, centerY + dy, centerX + half, centerY + dy + 1, DISC_COLOR);
        }
    }

    /** Draws the first few segments of a ring, clockwise from the top. */
    private static void drawRing(GuiGraphicsExtractor graphics, int centerX, int centerY,
            int radius, int color, int segments) {
        Matrix3x2fStack pose = graphics.pose();
        for (int segment = 0; segment < segments; segment++) {
            pose.pushMatrix();
            pose.translate(centerX, centerY);
            pose.rotate((float) (2 * Math.PI * segment / RING_SEGMENTS));
            graphics.fill(-1, -radius, 1, -radius + 1, color);
            pose.popMatrix();
        }
    }

    /**
     * Draws an arrow from the center pointing up, turned clockwise by the angle, reaching
     * the given share of the way to the ring.
     */
    private static void drawArrow(GuiGraphicsExtractor graphics, int centerX, int centerY,
            float angle, double share, int color) {
        int length = (int) Math.round(ARROW_MAX * Math.min(1, share));
        length = Math.max(length, ARROW_HEAD + 1); // keep the head visible when short
        Matrix3x2fStack pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(centerX, centerY);
        pose.rotate(angle);
        graphics.fill(-1, -length + ARROW_HEAD, 1, 0, color);
        for (int row = 0; row < ARROW_HEAD; row++) {
            int y = -length + row;
            graphics.fill(-row - 1, y, row + 1, y + 1, color);
        }
        pose.popMatrix();
    }

    /** A warning triangle just outside the radar ring, pointing out toward the angle. */
    private static void drawTriangle(GuiGraphicsExtractor graphics, int centerX, int centerY,
            float angle, int color) {
        Matrix3x2fStack pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(centerX, centerY);
        pose.rotate(angle);
        int tip = -RADAR_RADIUS - 2 - RADAR_TRIANGLE_ROWS;
        for (int row = 0; row < RADAR_TRIANGLE_ROWS; row++) {
            graphics.fill(-row - 1, tip + row, row + 1, tip + row + 1, color);
        }
        pose.popMatrix();
    }

    private static String nameOf(Player player) {
        return player.getName().getString().toLowerCase(Locale.ROOT);
    }

    /** The player the menu offers to friend: the one you look at, else the nearest one. */
    static Player friendCandidate(Minecraft minecraft) {
        LocalPlayer self = minecraft.player;
        if (self == null || minecraft.level == null) {
            return null;
        }
        if (minecraft.crosshairPickEntity instanceof Player looked && looked != self) {
            return looked;
        }
        Player nearest = null;
        double nearestDistance = FRIEND_PICK_RANGE;
        for (Player other : minecraft.level.players()) {
            if (other != self && other.distanceTo(self) < nearestDistance) {
                nearestDistance = other.distanceTo(self);
                nearest = other;
            }
        }
        return nearest;
    }

    static boolean isFriend(Player player) {
        return FRIENDS.contains(nameOf(player));
    }

    static void toggleFriend(Player player) {
        String name = nameOf(player);
        if (!FRIENDS.remove(name)) {
            FRIENDS.add(name);
        }
    }

    static int friendCount() {
        return FRIENDS.size();
    }

    static Set<String> friends() {
        return FRIENDS;
    }

    static void setFriends(Iterable<String> names) {
        FRIENDS.clear();
        for (String name : names) {
            if (!name.isBlank()) {
                FRIENDS.add(name.trim().toLowerCase(Locale.ROOT));
            }
        }
    }

    /**
     * Where a movable piece sits by default: the speed ring's center above the hotbar, the
     * look angle's left edge beside the hotbar (which sits on the bottom edge), and the
     * landing line's center under the crosshair, and the Combat stats panel's top-left corner
     * on the left edge, above the middle.
     */
    static int[] anchor(int element, int guiWidth, int guiHeight) {
        return switch (element) {
            case SPEED_RING -> new int[] {guiWidth / 2,
                    guiHeight - (speedHudHigh ? HIGH_CENTER_ABOVE_BOTTOM : LOW_CENTER_ABOVE_BOTTOM)};
            case LOOK_ANGLE -> new int[] {guiWidth / 2 + HOTBAR_HALF_WIDTH + ANGLE_GAP_FROM_HOTBAR, guiHeight - 15};
            case COMBAT_STATS -> new int[] {6, guiHeight / 2 - 90};
            default -> new int[] {guiWidth / 2, guiHeight / 2 + LANDING_BELOW_CROSSHAIR};
        };
    }

    /** Where a movable piece is drawn: its default spot plus your offset. */
    static int[] position(int element, int guiWidth, int guiHeight) {
        int[] spot = anchor(element, guiWidth, guiHeight);
        return new int[] {spot[0] + OFFSET_X[element], spot[1] + OFFSET_Y[element]};
    }

    static String elementKey(int element) {
        return ELEMENT_KEYS[element];
    }

    static String elementName(int element) {
        return ELEMENT_NAMES[element];
    }

    static int offsetX(int element) {
        return OFFSET_X[element];
    }

    static int offsetY(int element) {
        return OFFSET_Y[element];
    }

    static void setOffset(int element, int x, int y) {
        OFFSET_X[element] = x;
        OFFSET_Y[element] = y;
    }

    static String toggleKeyName(int module) {
        return TOGGLE_NAMES[module];
    }

    static int toggleKey(int module) {
        return TOGGLE_KEY[module];
    }

    static int toggleMouse(int module) {
        return TOGGLE_MOUSE[module];
    }

    static void setToggleBinding(int module, int key, int mouse) {
        TOGGLE_KEY[module] = key;
        TOGGLE_MOUSE[module] = mouse;
    }

    /** Which toggle key the menu is waiting for, or -1. */
    static int awaitingToggle() {
        return awaitingToggle;
    }

    static void awaitToggleBinding(int module) {
        awaitingToggle = module;
    }

    /** Binds the key pressed while waiting; Escape clears the binding instead. */
    static void bindToggleKeyboard(int key) {
        if (awaitingToggle >= 0) {
            setToggleBinding(awaitingToggle, key == 256 ? -1 : key, -1);
        }
        awaitingToggle = -1;
    }

    static void bindToggleMouse(int button) {
        if (awaitingToggle >= 0) {
            setToggleBinding(awaitingToggle, -1, button);
        }
        awaitingToggle = -1;
    }

    /** The HUD module bound to this key or mouse button, or -1. */
    static int toggleFor(int key, int mouseButton) {
        for (int module = 0; module < TOGGLES; module++) {
            boolean keyMatch = key >= 0 && TOGGLE_MOUSE[module] < 0 && TOGGLE_KEY[module] == key;
            boolean mouseMatch = mouseButton >= 0 && TOGGLE_MOUSE[module] == mouseButton;
            if (keyMatch || mouseMatch) {
                return module;
            }
        }
        return -1;
    }

    static void toggleModule(int module) {
        switch (module) {
            case TOGGLE_SPEED -> toggleSpeedHud();
            case TOGGLE_RADAR -> toggleRadar();
            case TOGGLE_HIDE -> HudFilter.toggleHideAll();
            default -> toggleLanding();
        }
    }

    static boolean isAvailable() {
        return available;
    }

    static boolean isSpeedHudOn() {
        return speedHudOn;
    }

    static void setSpeedHudOn(boolean on) {
        speedHudOn = on;
    }

    static void toggleSpeedHud() {
        speedHudOn = !speedHudOn;
    }

    static boolean isSpeedHudHigh() {
        return speedHudHigh;
    }

    static void setSpeedHudHigh(boolean high) {
        speedHudHigh = high;
    }

    static String positionLabel() {
        return speedHudHigh ? "High" : "Low";
    }

    static void togglePosition() {
        speedHudHigh = !speedHudHigh;
    }

    static boolean isWarningsOn() {
        return warningsOn;
    }

    static void setWarningsOn(boolean on) {
        warningsOn = on;
    }

    static void toggleWarnings() {
        warningsOn = !warningsOn;
    }

    static boolean isRadarOn() {
        return radarOn;
    }

    static void setRadarOn(boolean on) {
        radarOn = on;
    }

    static void toggleRadar() {
        radarOn = !radarOn;
    }

    static int radarRange() {
        return RADAR_RANGES[radarRangeIndex];
    }

    static void setRadarRange(int blocks) {
        for (int i = 0; i < RADAR_RANGES.length; i++) {
            if (RADAR_RANGES[i] == blocks) {
                radarRangeIndex = i;
            }
        }
    }

    static void cycleRadarRange() {
        radarRangeIndex = (radarRangeIndex + 1) % RADAR_RANGES.length;
    }

    static boolean isIgnoreTeam() {
        return ignoreTeam;
    }

    static void setIgnoreTeam(boolean ignore) {
        ignoreTeam = ignore;
    }

    static void toggleIgnoreTeam() {
        ignoreTeam = !ignoreTeam;
    }

    static boolean isLandingOn() {
        return landingOn;
    }

    static void setLandingOn(boolean on) {
        landingOn = on;
    }

    static void toggleLanding() {
        landingOn = !landingOn;
    }
}
