package app;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import com.mojang.blaze3d.platform.InputConstants;

/**
 * The small UTIL modules:
 * - Auto Sprint: holds sprint while you walk forward, the way holding the sprint key does
 *   (not while sneaking, using an item, or too hungry to sprint). Off with Toggle Sprint on.
 * - Fullbright: turns brightness far past the slider's maximum so caves and nights are lit;
 *   your own brightness comes back when it is turned off.
 * - Zoom: while its key is held, narrows the field of view by Zoom (2x, 4x, 8x) and slows the
 *   mouse to match; both come back on release. Smooth (default) glides in and out over about
 *   a sixth of a second, every frame; Snap jumps straight there.
 * - Auto Respawn: on the death screen, respawns at once, and with Death coords puts where you
 *   died in your chat (only you see it) and in the log.
 * - Auto Walk: holds forward for you until you turn it off (or open a screen).
 * - Weather: Clear (no rain or thunder on your screen) or Cinematic (a film look); see Weather.
 * - Hit Sound: a short ding when a hit you click lands (the target flinches).
 * - Middle Click Friend: middle-click a player to add or remove them as a Radar friend
 *   (friends never set off the Radar or get webbed by Web Trap).
 */
final class Utilities {
    private static final int[] ZOOM_FACTORS = {2, 4, 8};
    private static final double FULLBRIGHT_GAMMA = 15.0;
    private static volatile boolean autoSprint;
    private static volatile boolean fullbright;
    private static volatile boolean zoom;
    private static volatile int zoomIndex = 1;
    private static volatile int zoomKey = -1;
    private static volatile int zoomMouse = -1;
    private static volatile boolean awaitingZoomBinding;
    private static volatile boolean zoomSmooth = true;
    // How far zoomed in right now (1 = not at all); render thread only.
    private static double zoomLevel = 1;
    private static long zoomFrameNs;
    private static volatile boolean autoRespawn;
    private static volatile boolean deathCoords = true;
    private static volatile boolean autoWalk;
    private static volatile boolean hitSound;
    private static volatile boolean middleClickFriend;
    // How long after your click a flinch still counts as your hit.
    private static final long HIT_WINDOW_MS = 600;
    // Render thread only.
    private static boolean sprinting;
    private static Double savedGamma;
    private static Integer savedFov;
    private static Double savedSensitivity;
    private static long deathSeenMs = -1;
    private static boolean walking;
    private static LivingEntity hitTarget;
    private static long hitClickMs;
    private static int hitTargetHurtTime;

    private Utilities() {}

    /** Called from the input loop. */
    static void update(Minecraft minecraft) {
        updateSprint(minecraft);
        updateFullbright(minecraft);
        updateZoom(minecraft);
        updateRespawn(minecraft);
        updateWalk(minecraft);
        Weather.update(minecraft);
        updateHitSound(minecraft);
    }

    private static void updateWalk(Minecraft minecraft) {
        boolean want = autoWalk && minecraft.player != null && Mc.screen(minecraft) == null;
        if (want && WTap.isTapping()) {
            walking = true; // W-Tap has forward up for its tap; it puts it back after
        } else if (want) {
            minecraft.options.keyUp.setDown(true);
            walking = true;
        } else if (walking) {
            minecraft.options.keyUp.setDown(WTap.physicallyDown(minecraft, minecraft.options.keyUp));
            walking = false;
        }
    }

    /** Your attack click: remembers what you clicked, to ding when it flinches. */
    static void onAttackClick(Minecraft minecraft) {
        if (hitSound && minecraft.hitResult instanceof EntityHitResult hit
                && hit.getEntity() instanceof LivingEntity target) {
            hitTarget = target;
            hitClickMs = System.nanoTime() / 1_000_000;
            hitTargetHurtTime = target.hurtTime;
        }
    }

    private static void updateHitSound(Minecraft minecraft) {
        if (hitTarget == null) {
            return;
        }
        if (System.nanoTime() / 1_000_000 - hitClickMs > HIT_WINDOW_MS || !hitTarget.isAlive()) {
            hitTarget = null;
            return;
        }
        if (hitTarget.hurtTime > hitTargetHurtTime) {
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.ARROW_HIT_PLAYER, 1.0F, 0.6F));
            hitTarget = null;
            return;
        }
        hitTargetHurtTime = hitTarget.hurtTime;
    }

    /** A middle click: friends or unfriends the player you look at; true if it did. */
    static boolean middleClick(Minecraft minecraft) {
        if (!middleClickFriend || Mc.screen(minecraft) != null
                || !(minecraft.hitResult instanceof EntityHitResult hit) || !(hit.getEntity() instanceof Player target)) {
            return false;
        }
        UraniumHud.toggleFriend(target);
        Settings.save(minecraft);
        String name = target.getName().getString();
        SpearDash.setStatus((UraniumHud.isFriend(target) ? "Friended " : "Unfriended ") + name);
        return true;
    }

    private static void updateSprint(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        Options options = minecraft.options;
        boolean want = autoSprint && player != null && Mc.screen(minecraft) == null && !options.toggleSprint().get()
                && WTap.physicallyDown(minecraft, options.keyUp) && !player.isShiftKeyDown() && !player.isUsingItem()
                && player.getFoodData().getFoodLevel() > 6;
        if (want) {
            options.keySprint.setDown(true);
            sprinting = true;
        } else if (sprinting) {
            options.keySprint.setDown(WTap.physicallyDown(minecraft, options.keySprint));
            sprinting = false;
        }
    }

    private static void updateFullbright(Minecraft minecraft) {
        if (fullbright) {
            if (savedGamma == null) {
                savedGamma = minecraft.options.gamma().get();
            }
            RawOption.set(minecraft.options.gamma(), FULLBRIGHT_GAMMA);
        } else if (savedGamma != null) {
            RawOption.set(minecraft.options.gamma(), savedGamma);
            savedGamma = null;
        }
    }

    /** Called every frame by the HUD (and from the input loop when the HUD is hidden). */
    static void zoomFrame(Minecraft minecraft) {
        updateZoom(minecraft);
    }

    private static void updateZoom(Minecraft minecraft) {
        long now = System.nanoTime();
        double seconds = zoomFrameNs == 0 ? 0 : Math.min(0.1, (now - zoomFrameNs) / 1.0E9);
        zoomFrameNs = now;
        boolean held = zoom && Mc.screen(minecraft) == null && (zoomKey >= 0 || zoomMouse >= 0)
                && (zoomMouse >= 0
                        ? GLFW.glfwGetMouseButton(minecraft.getWindow().handle(), zoomMouse) == GLFW.GLFW_PRESS
                        : Mc.isKeyDown(minecraft, zoomKey));
        double target = held ? ZOOM_FACTORS[zoomIndex] : 1;
        if (zoomSmooth) {
            // Eases toward the target: most of the way in about a sixth of a second.
            zoomLevel += (target - zoomLevel) * (1 - Math.exp(-seconds * 16));
            if (Math.abs(target - zoomLevel) < 0.01) {
                zoomLevel = target;
            }
        } else {
            zoomLevel = target;
        }
        if (zoomLevel > 1.001) {
            if (savedFov == null) {
                savedFov = minecraft.options.fov().get();
                savedSensitivity = minecraft.options.sensitivity().get();
            }
            int fov = (int) Math.max(1, Math.round(savedFov / zoomLevel));
            if (minecraft.options.fov().get() != fov) {
                RawOption.set(minecraft.options.fov(), fov);
            }
            RawOption.set(minecraft.options.sensitivity(), savedSensitivity / zoomLevel);
        } else if (savedFov != null) {
            zoomLevel = 1;
            endZoom(minecraft);
        }
    }

    static String zoomStyleLabel() {
        return zoomSmooth ? "Smooth" : "Snap";
    }

    static boolean isZoomSmooth() {
        return zoomSmooth;
    }

    static void setZoomSmooth(boolean on) {
        zoomSmooth = on;
    }

    static void cycleZoomStyle() {
        zoomSmooth = !zoomSmooth;
    }

    private static void endZoom(Minecraft minecraft) {
        RawOption.set(minecraft.options.fov(), savedFov);
        RawOption.set(minecraft.options.sensitivity(), savedSensitivity);
        savedFov = null;
        savedSensitivity = null;
    }

    private static void updateRespawn(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (!autoRespawn || player == null || !(Mc.screen(minecraft) instanceof DeathScreen)) {
            deathSeenMs = -1;
            return;
        }
        long now = System.nanoTime() / 1_000_000;
        if (deathSeenMs < 0) {
            deathSeenMs = now;
            String where = player.getBlockX() + " " + player.getBlockY() + " " + player.getBlockZ();
            UraniumLog.log("auto respawn: died at " + where + " in " + minecraft.level.dimension().identifier());
            if (deathCoords) {
                Mc.chat(minecraft).addClientSystemMessage(Component.literal("Died at " + where));
            }
            return;
        }
        // A moment on the death screen, so the death packet has been handled.
        if (now - deathSeenMs > 100) {
            player.respawn();
            Mc.setScreen(minecraft, null);
            deathSeenMs = -1;
        }
    }

    static boolean isAutoSprint() {
        return autoSprint;
    }

    static void setAutoSprint(boolean on) {
        autoSprint = on;
    }

    static void toggleAutoSprint() {
        autoSprint = !autoSprint;
    }

    static boolean isFullbright() {
        return fullbright;
    }

    static void setFullbright(boolean on) {
        fullbright = on;
    }

    static void toggleFullbright() {
        fullbright = !fullbright;
    }

    static boolean isZoom() {
        return zoom;
    }

    static void setZoom(boolean on) {
        zoom = on;
    }

    static void toggleZoom() {
        zoom = !zoom;
    }

    static int zoomFactor() {
        return ZOOM_FACTORS[zoomIndex];
    }

    static void setZoomFactor(int factor) {
        for (int i = 0; i < ZOOM_FACTORS.length; i++) {
            if (ZOOM_FACTORS[i] == factor) {
                zoomIndex = i;
            }
        }
    }

    static void cycleZoomFactor() {
        zoomIndex = (zoomIndex + 1) % ZOOM_FACTORS.length;
    }

    static int zoomKey() {
        return zoomKey;
    }

    static int zoomMouse() {
        return zoomMouse;
    }

    static void setZoomBinding(int key, int button) {
        zoomKey = key;
        zoomMouse = button;
    }

    static void bindZoomKeyboard(int key) {
        zoomKey = key;
        zoomMouse = -1;
        awaitingZoomBinding = false;
    }

    static void bindZoomMouse(int button) {
        zoomMouse = button;
        awaitingZoomBinding = false;
    }

    static boolean isAwaitingZoomBinding() {
        return awaitingZoomBinding;
    }

    static void awaitZoomBinding() {
        awaitingZoomBinding = true;
    }

    static void cancelZoomBinding() {
        awaitingZoomBinding = false;
    }

    static boolean isAutoRespawn() {
        return autoRespawn;
    }

    static void setAutoRespawn(boolean on) {
        autoRespawn = on;
    }

    static void toggleAutoRespawn() {
        autoRespawn = !autoRespawn;
    }

    static boolean isDeathCoords() {
        return deathCoords;
    }

    static void setDeathCoords(boolean on) {
        deathCoords = on;
    }

    static void toggleDeathCoords() {
        deathCoords = !deathCoords;
    }

    static boolean isAutoWalk() {
        return autoWalk;
    }

    static void setAutoWalk(boolean on) {
        autoWalk = on;
    }

    static void toggleAutoWalk() {
        autoWalk = !autoWalk;
    }

    static boolean isHitSound() {
        return hitSound;
    }

    static void setHitSound(boolean on) {
        hitSound = on;
    }

    static void toggleHitSound() {
        hitSound = !hitSound;
    }

    static boolean isMiddleClickFriend() {
        return middleClickFriend;
    }

    static void setMiddleClickFriend(boolean on) {
        middleClickFriend = on;
    }

    static void toggleMiddleClickFriend() {
        middleClickFriend = !middleClickFriend;
    }

    /** Turns everything off when this injection is unloaded, giving back your own options. */
    static void reset(Minecraft minecraft) {
        if (walking) {
            minecraft.options.keyUp.setDown(WTap.physicallyDown(minecraft, minecraft.options.keyUp));
            walking = false;
        }
        autoWalk = false;
        Weather.reset(minecraft);
        hitSound = false;
        middleClickFriend = false;
        hitTarget = null;
        if (sprinting) {
            minecraft.options.keySprint.setDown(WTap.physicallyDown(minecraft, minecraft.options.keySprint));
            sprinting = false;
        }
        if (savedGamma != null) {
            RawOption.set(minecraft.options.gamma(), savedGamma);
            savedGamma = null;
        }
        if (savedFov != null) {
            endZoom(minecraft);
        }
        autoSprint = false;
        fullbright = false;
        zoom = false;
        autoRespawn = false;
        awaitingZoomBinding = false;
    }
}
