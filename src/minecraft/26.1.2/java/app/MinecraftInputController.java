package app;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongBinaryOperator;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWKeyCallback;
import org.lwjgl.glfw.GLFWMouseButtonCallback;
import org.lwjgl.glfw.GLFWScrollCallback;
import org.lwjgl.system.Callback;

final class MinecraftInputController {
    private static final int NUMPAD_8_KEY = InputConstants.KEY_NUMPAD8;
    // Long enough for the server's correction to arrive on a high ping.
    private static final long GUARD_MS = 1500;
    private static final int MAX_GUARD_FIXES = 3;
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final AtomicBoolean TICK_QUEUED = new AtomicBoolean();
    // A queued tick that has not run after this long was dropped (see scheduleNext).
    private static final long STALE_TICK_MS = 1000;
    private static volatile long tickQueuedAtMs;
    // When the loop last ran a tick; the injector's Status reads it to tell a live loop.
    private static volatile long lastTickMs;
    private static ScheduledExecutorService scheduler;
    // Modules whose update has thrown, each logged once.
    private static final java.util.Set<String> FAILED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static volatile boolean numpad8WasDown;
    private static volatile boolean dashKeyWasDown;
    private static volatile boolean shieldKeyWasDown;
    private static volatile boolean webTrapKeyWasDown;
    private static volatile boolean keyEventsInstalled;
    private static volatile boolean mouseEventsInstalled;
    // False after Unload: any of our listeners still chained become pure pass-throughs.
    private static volatile boolean active;
    private static volatile boolean unloadRequested;
    private static GLFWKeyCallback ownKeyCallback;
    private static GLFWMouseButtonCallback ownMouseCallback;
    private static GLFWScrollCallback ownScrollCallback;
    private static GLFWKeyCallback previousKeyCallback;
    private static GLFWMouseButtonCallback previousMouseCallback;
    private static GLFWScrollCallback previousScrollCallback;
    private static volatile boolean userPickedSlot;
    private static int guardPreviousSlot = -1;
    private static int guardSpearSlot = -1;
    private static long guardUntil;
    private static long guardStartedMs;
    private static int guardFixes;

    private MinecraftInputController() {}

    static void install(Minecraft minecraft) {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        SpearSettings.load(minecraft);
        unloadRequested = false;
        active = true;
        scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task,
                    "minecraft-input-controller-" + MinecraftInputController.class.getPackageName());
            thread.setDaemon(true);
            return thread;
        });
        installPressListeners(minecraft);
        scheduleNext(minecraft);
    }

    // Presses arrive as events, so a tap is caught even when the press and release
    // both land inside one lagged frame (polling only sees the final "released" state).
    // Every event is still passed on to Minecraft's own handler afterwards.
    private static void installPressListeners(Minecraft minecraft) {
        // A listener left in the chain by an earlier Unload is simply switched back on.
        long window = minecraft.getWindow().handle();
        try {
            if (ownKeyCallback == null) {
                ownKeyCallback = GLFWKeyCallback.create((handle, key, scancode, action, mods) -> {
                    if (active && action == GLFW.GLFW_PRESS
                            && onKeyPressed(minecraft, key, scancode, mods)) {
                        return; // a module used the press; the game must not also act on it
                    }
                    if (previousKeyCallback != null) {
                        previousKeyCallback.invoke(handle, key, scancode, action, mods);
                    }
                });
                previousKeyCallback = GLFW.glfwSetKeyCallback(window, ownKeyCallback);
            }
            keyEventsInstalled = true;
        } catch (RuntimeException | LinkageError exception) {
            keyEventsInstalled = false;
        }
        try {
            if (ownMouseCallback == null) {
                ownMouseCallback = GLFWMouseButtonCallback.create((handle, button, action, mods) -> {
                    if (active && action == GLFW.GLFW_PRESS && onMousePressed(minecraft, button, mods)) {
                        return; // a module used the click; the game must not also act on it
                    }
                    if (previousMouseCallback != null) {
                        previousMouseCallback.invoke(handle, button, action, mods);
                    }
                });
                previousMouseCallback = GLFW.glfwSetMouseButtonCallback(window, ownMouseCallback);
            }
            mouseEventsInstalled = true;
        } catch (RuntimeException | LinkageError exception) {
            mouseEventsInstalled = false;
        }
        try {
            if (ownScrollCallback == null) {
                ownScrollCallback = GLFWScrollCallback.create((handle, xOffset, yOffset) -> {
                    if (active && Mc.screen(minecraft) == null) {
                        userPickedSlot = true;
                    }
                    if (previousScrollCallback != null) {
                        previousScrollCallback.invoke(handle, xOffset, yOffset);
                    }
                });
                previousScrollCallback = GLFW.glfwSetScrollCallback(window, ownScrollCallback);
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Without it, a scroll right after a dash may be switched back once.
        }
    }

    /**
     * Asks this injection to switch itself off. It happens on the next tick rather than
     * here, because the Unload button is clicked from inside our own mouse listener,
     * which must not be freed while it is running.
     */
    static void requestUnload() {
        unloadRequested = true;
    }

    /** Switches off only this injection; other injected versions are left alone. */
    private static void unload(Minecraft minecraft) {
        active = false;
        guardSpearSlot = -1;
        AttackHook.uninstall();
        HudFilter.uninstall();
        long window = minecraft.getWindow().handle();
        if (ownKeyCallback != null && unhook(window, ownKeyCallback,
                previousKeyCallback, GLFW::nglfwSetKeyCallback)) {
            ownKeyCallback = null;
            previousKeyCallback = null;
        }
        if (ownMouseCallback != null && unhook(window, ownMouseCallback,
                previousMouseCallback, GLFW::nglfwSetMouseButtonCallback)) {
            ownMouseCallback = null;
            previousMouseCallback = null;
        }
        if (ownScrollCallback != null && unhook(window, ownScrollCallback,
                previousScrollCallback, GLFW::nglfwSetScrollCallback)) {
            ownScrollCallback = null;
            previousScrollCallback = null;
        }
        MinecraftSpearDash.reset(minecraft);
        ShieldBreak.reset(minecraft);
        MaceSwap.reset();
        MaceLog.reset();
        Hit.reset();
        AimHelper.reset();
        SlamMeter.reset();
        SlamSteer.reset(minecraft);
        SlamSaver.reset();
        SlamFollowUp.reset();
        PunishAlerts.reset();
        WorldSlash.reset();
        Wheels.reset(minecraft);
        WebEscape.reset();
        WebTrap.reset();
        MlgWater.reset();
        WindClutch.reset();
        ElytraMace.reset();
        View.reset();
        AutoTotem.reset(minecraft);
        AutoWeapon.reset(minecraft);
        ReachSwap.reset(minecraft);
        AutoGapple.reset(minecraft);
        AutoTool.reset();
        Utilities.reset(minecraft);
        QuickThrow.reset(minecraft);
        PearlCatch.reset(minecraft);
        ChestSwap.reset(minecraft);
        AutoEat.reset(minecraft);
        AutoFish.reset();
        WTap.reset(minecraft);
        JumpReset.reset(minecraft);
        VelocityHud.uninstall();
        if (Mc.screen(minecraft) instanceof ClickGuiScreen) {
            Mc.setScreen(minecraft, null);
        }
        scheduler.shutdown();
        INSTALLED.set(false);
    }

    /**
     * Puts back the listener that was there before ours, if ours is still the newest.
     * If a later injection chained on top of ours, removing ours would cut it off too,
     * so that one is put back and ours stays in the chain as a pass-through instead.
     */
    private static boolean unhook(long window, Callback own, Callback previous,
            LongBinaryOperator setNativeCallback) {
        long previousAddress = previous == null ? 0L : previous.address();
        long current = setNativeCallback.applyAsLong(window, previousAddress);
        if (current != own.address()) {
            setNativeCallback.applyAsLong(window, current);
            return false;
        }
        own.free();
        return true;
    }

    /**
     * Handles a key press; true if a module used it, so the game must not also act on it.
     * That matters when a module shares its key with a game binding: a dash on the key that
     * also selects the spear's hotbar slot would otherwise put you back on the spear a tick
     * after every dash. When the module does not fire, the key goes to the game as usual.
     */
    private static boolean onKeyPressed(Minecraft minecraft, int key, int scancode, int mods) {
        try {
            KeyEvent event = new KeyEvent(key, scancode, mods);
            if (key == NUMPAD_8_KEY) {
                toggleGui(minecraft);
                return false;
            }
            if (toggleHudModule(minecraft, VelocityHud.toggleFor(key, -1))) {
                return true;
            }
            // The attack key can be rebound to the keyboard.
            boolean consumed = minecraft.options.keyAttack.matches(event) && attackClick(minecraft);
            if (MinecraftSpearDash.mouseButton() < 0 && key >= 0 && key == MinecraftSpearDash.keyCode()
                    && dash(minecraft)) {
                consumed = true;
            }
            if (!consumed && ShieldBreak.mouseButton() < 0 && key >= 0 && key == ShieldBreak.keyCode()
                    && shieldBreak(minecraft)) {
                consumed = true;
            }
            // Web Trap starts unbound (-1), which is also the code of keys GLFW does not know.
            if (!consumed && WebTrap.mouseButton() < 0 && key >= 0 && key == WebTrap.keyCode()
                    && WebTrap.activate(minecraft)) {
                consumed = true;
            }
            if (!consumed && QuickThrow.mouseButton() < 0 && key >= 0 && key == QuickThrow.keyCode()
                    && QuickThrow.activate(minecraft)) {
                consumed = true;
            }
            if (AimHelper.mouseButton() < 0 && key >= 0 && key == AimHelper.keyCode()) {
                AimHelper.onKey(minecraft);
            }
            if (!consumed && PearlCatch.mouseButton() < 0 && key >= 0 && key == PearlCatch.keyCode()
                    && PearlCatch.activate(minecraft)) {
                consumed = true;
            }
            if (!consumed && minecraft.options.keyUse.matches(event) && PearlCatch.onUseClick(minecraft)) {
                consumed = true;
            }
            if (!consumed && ChestSwap.mouseButton() < 0 && key >= 0 && key == ChestSwap.keyCode()
                    && ChestSwap.activate(minecraft)) {
                consumed = true;
            }
            // A hotbar key the game will act on is your own pick; the swap-back watch leaves it.
            if (!consumed && Mc.screen(minecraft) == null && isHotbarKey(minecraft, event)) {
                userPickedSlot = true;
            }
            return consumed;
        } catch (RuntimeException ignored) {
            return false; // never let a module error swallow the key for Minecraft
        }
    }

    /** Handles a mouse press; true if a module used it (see onKeyPressed). */
    private static boolean onMousePressed(Minecraft minecraft, int button, int mods) {
        try {
            MouseButtonEvent event = new MouseButtonEvent(0, 0, new MouseButtonInfo(button, mods));
            if (toggleHudModule(minecraft, VelocityHud.toggleFor(-1, button))) {
                return true;
            }
            boolean consumed = minecraft.options.keyAttack.matchesMouse(event) && attackClick(minecraft);
            if (button == MinecraftSpearDash.mouseButton() && dash(minecraft)) {
                consumed = true;
            }
            if (!consumed && button == ShieldBreak.mouseButton() && shieldBreak(minecraft)) {
                consumed = true;
            }
            if (!consumed && button == WebTrap.mouseButton() && WebTrap.activate(minecraft)) {
                consumed = true;
            }
            if (!consumed && button == QuickThrow.mouseButton() && QuickThrow.activate(minecraft)) {
                consumed = true;
            }
            if (button == AimHelper.mouseButton()) {
                AimHelper.onKey(minecraft);
            }
            if (!consumed && button == PearlCatch.mouseButton() && PearlCatch.activate(minecraft)) {
                consumed = true;
            }
            if (!consumed && minecraft.options.keyUse.matchesMouse(event) && PearlCatch.onUseClick(minecraft)) {
                consumed = true;
            }
            if (!consumed && button == ChestSwap.mouseButton() && ChestSwap.activate(minecraft)) {
                consumed = true;
            }
            if (!consumed && button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE && Utilities.middleClick(minecraft)) {
                consumed = true;
            }
            if (!consumed && Mc.screen(minecraft) == null && isHotbarButton(minecraft, event)) {
                userPickedSlot = true;
            }
            return consumed;
        } catch (RuntimeException ignored) {
            return false; // never let a module error swallow the click for Minecraft
        }
    }

    /** A HUD module's toggle key, outside menus; saved like a menu change. */
    private static boolean toggleHudModule(Minecraft minecraft, int module) {
        if (module < 0 || Mc.screen(minecraft) != null) {
            return false;
        }
        VelocityHud.toggleModule(module);
        SpearSettings.save(minecraft);
        return true;
    }

    static long lastTickMs() {
        return lastTickMs;
    }

    /**
     * Your attack click: a Shield Break on a target blocking toward you, else a Mace Swap
     * hit. True if one of them made the hit, so the game must not also hit. Otherwise Auto
     * Weapon may switch to a weapon first, and the game makes the hit with it (or, with its
     * delays on, Auto Weapon makes the hit itself after them).
     */
    private static boolean attackClick(Minecraft minecraft) {
        WTap.onAttackClick(minecraft);
        Utilities.onAttackClick(minecraft);
        return ShieldBreak.handleAttackClick(minecraft) || SlamSaver.onAttackClick(minecraft)
                || ReachSwap.handleAttackClick(minecraft) || AutoWeapon.onAttackClick(minecraft);
    }

    private static boolean shieldBreak(Minecraft minecraft) {
        return Mc.screen(minecraft) == null && ShieldBreak.activate(minecraft);
    }

    private static boolean isHotbarButton(Minecraft minecraft, MouseButtonEvent event) {
        for (KeyMapping hotbarKey : minecraft.options.keyHotbarSlots) {
            if (hotbarKey.matchesMouse(event)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isHotbarKey(Minecraft minecraft, KeyEvent event) {
        for (KeyMapping hotbarKey : minecraft.options.keyHotbarSlots) {
            if (hotbarKey.matches(event)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Numpad 8 opens the menu only from the game itself or our own screens, so typing an 8
     * in chat or a sign never swaps that screen for the menu.
     */
    private static void toggleGui(Minecraft minecraft) {
        if (Mc.screen(minecraft) != null && !(Mc.screen(minecraft) instanceof ClickGuiScreen)
                && !(Mc.screen(minecraft) instanceof HudEditorScreen)) {
            return;
        }
        Mc.setScreen(minecraft, Mc.screen(minecraft) instanceof ClickGuiScreen
                ? null
                : new ClickGuiScreen());
    }

    /** True if a dash went out (or is levelling first). */
    private static boolean dash(Minecraft minecraft) {
        return Mc.screen(minecraft) == null && MinecraftSpearDash.hasLungeSpearInHotbar(minecraft)
                && MinecraftSpearDash.activate(minecraft);
    }

    /**
     * Runs the next tick on the game thread. The scheduler thread keeps the loop going by
     * itself: Minecraft throws away queued tasks when you disconnect, and a loop that only
     * continued from inside its queued task stopped for good when that happened (delayed
     * swaps back and Unload then never ran). A queued tick that has not run
     * after STALE_TICK_MS is treated as dropped.
     */
    private static void scheduleNext(Minecraft minecraft) {
        scheduleOn(minecraft, scheduler);
    }

    /** Each loop stays on the scheduler it started on, so it ends when that one is shut down. */
    private static void scheduleOn(Minecraft minecraft, ScheduledExecutorService current) {
        if (current.isShutdown()) {
            return;
        }
        long delayMs = 12 + (long) (Math.random() * 21);
        try {
            current.schedule(() -> {
                try {
                    long now = System.nanoTime() / 1_000_000;
                    if (TICK_QUEUED.get() && now - tickQueuedAtMs > STALE_TICK_MS) {
                        TICK_QUEUED.set(false);
                    }
                    if (TICK_QUEUED.compareAndSet(false, true)) {
                        tickQueuedAtMs = now;
                        minecraft.execute(() -> {
                            try {
                                if (unloadRequested) {
                                    unload(minecraft);
                                } else {
                                    tick(minecraft);
                                }
                            } finally {
                                TICK_QUEUED.set(false);
                            }
                        });
                    }
                } finally {
                    scheduleOn(minecraft, current);
                }
            }, delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException stopped) {
            // Unload shut the scheduler down between the check and here.
        }
    }

    private static void tick(Minecraft minecraft) {
        lastTickMs = System.nanoTime() / 1_000_000;
        SpearSettings.followServer(minecraft);
        guard("AttackHook", () -> AttackHook.update(minecraft));
        guard("Spear Dash ready sound", () -> MinecraftSpearDash.updateReadySound(minecraft));
        guard("switch-back watch", () -> enforceSwitchBack(minecraft));
        guard("Spear Dash", () -> MinecraftSpearDash.updateDash(minecraft, true));
        guard("ShieldBreak", () -> ShieldBreak.update(minecraft));
        guard("AutoWeapon", () -> AutoWeapon.update(minecraft));
        guard("ReachSwap", () -> ReachSwap.update(minecraft));
        guard("QuickThrow", () -> QuickThrow.update(minecraft));
        guard("PearlCatch", () -> PearlCatch.update(minecraft));
        guard("MaceLog", () -> MaceLog.update(minecraft));
        guard("Hit", () -> Hit.update(minecraft));
        guard("AimHelper", () -> AimHelper.update(minecraft));
        guard("WorldSlash", () -> WorldSlash.update(minecraft));
        guard("SlamSteer", () -> SlamSteer.update(minecraft));
        guard("SlamSaver", () -> SlamSaver.update(minecraft));
        guard("SlamFollowUp", () -> SlamFollowUp.update(minecraft));
        guard("PunishAlerts", () -> PunishAlerts.update(minecraft));
        guard("Wheels", () -> Wheels.update(minecraft));
        guard("ChestSwap", () -> ChestSwap.update(minecraft));
        guard("WebEscape", () -> WebEscape.update(minecraft));
        guard("MlgWater", () -> MlgWater.update(minecraft));
        guard("WindClutch", () -> WindClutch.update(minecraft));
        guard("ElytraMace", () -> ElytraMace.update(minecraft));
        guard("WTap", () -> WTap.update(minecraft));
        guard("JumpReset", () -> JumpReset.update(minecraft));
        guard("AutoTotem", () -> AutoTotem.update(minecraft));
        guard("AutoGapple", () -> AutoGapple.update(minecraft));
        guard("AutoTool", () -> AutoTool.update(minecraft));
        guard("Utilities", () -> Utilities.update(minecraft));
        guard("AutoEat", () -> AutoEat.update(minecraft));
        guard("AutoFish", () -> AutoFish.update(minecraft));
        guard("View", () -> View.update(minecraft));

        // Polling is only the fallback for when the event listeners could not be installed.
        if (!keyEventsInstalled) {
            boolean numpad8Down = Mc.isKeyDown(minecraft, NUMPAD_8_KEY);
            if (numpad8Down && !numpad8WasDown) {
                toggleGui(minecraft);
            }
            numpad8WasDown = numpad8Down;
        }

        boolean bindingUsesEvents = MinecraftSpearDash.mouseButton() < 0
                ? keyEventsInstalled
                : mouseEventsInstalled;
        boolean dashDown = !bindingUsesEvents && isBindingDown(minecraft,
                MinecraftSpearDash.keyCode(), MinecraftSpearDash.mouseButton());
        if (dashDown && !dashKeyWasDown) {
            dash(minecraft);
        }
        dashKeyWasDown = dashDown;

        boolean shieldUsesEvents = ShieldBreak.mouseButton() < 0
                ? keyEventsInstalled
                : mouseEventsInstalled;
        boolean shieldDown = !shieldUsesEvents && isBindingDown(minecraft,
                ShieldBreak.keyCode(), ShieldBreak.mouseButton());
        if (shieldDown && !shieldKeyWasDown) {
            shieldBreak(minecraft);
        }
        shieldKeyWasDown = shieldDown;

        boolean webTrapBound = WebTrap.keyCode() >= 0 || WebTrap.mouseButton() >= 0;
        boolean webTrapUsesEvents = WebTrap.mouseButton() < 0 ? keyEventsInstalled : mouseEventsInstalled;
        boolean webTrapDown = webTrapBound && !webTrapUsesEvents && isBindingDown(minecraft,
                WebTrap.keyCode(), WebTrap.mouseButton());
        if (webTrapDown && !webTrapKeyWasDown) {
            WebTrap.activate(minecraft);
        }
        webTrapKeyWasDown = webTrapDown;
    }

    /**
     * Runs one module's part of the tick. A module that throws only loses its own turn: the
     * rest of the tick still runs (Auto Totem included), and the error is logged once.
     */
    private static void guard(String module, Runnable update) {
        try {
            update.run();
        } catch (RuntimeException error) {
            if (FAILED.add(module)) {
                SpearLog.log("error in " + module + ", the other modules keep running: " + error);
            }
        }
    }

    /**
     * Watches the hotbar for a moment after switching back. If it lands on the spear
     * again without you picking a slot (the server can force the slot), it switches
     * back and tells the server explicitly.
     */
    static void guardSwitchBack(int previous, int spear) {
        guardPreviousSlot = previous;
        guardSpearSlot = spear;
        guardStartedMs = System.nanoTime() / 1_000_000;
        guardUntil = guardStartedMs + GUARD_MS;
        guardFixes = 0;
        userPickedSlot = false;
    }

    /** Ends the swap-back watch, for a slot change that is ours on purpose. */
    static void stopGuard() {
        guardSpearSlot = -1;
    }

    private static void enforceSwitchBack(Minecraft minecraft) {
        if (guardSpearSlot < 0) {
            return;
        }
        if (MinecraftSpearDash.isDashing()) {
            return; // the spear in your hand is a dash of ours, still showing
        }
        if (minecraft.player == null || userPickedSlot
                || System.nanoTime() / 1_000_000 > guardUntil) {
            guardSpearSlot = -1;
            return;
        }
        int selected = minecraft.player.getInventory().getSelectedSlot();
        if (selected == guardPreviousSlot) {
            return;
        }
        long afterMs = System.nanoTime() / 1_000_000 - guardStartedMs;
        if (selected != guardSpearSlot) {
            SpearLog.log("guard: hotbar moved to " + (selected + 1) + " " + afterMs
                    + " ms after the dash, not the spear; left alone");
            guardSpearSlot = -1;
            return;
        }
        if (guardFixes >= MAX_GUARD_FIXES) {
            MinecraftSpearDash.alert("Server keeps spear!");
            guardSpearSlot = -1;
            return;
        }
        guardFixes++;
        SpearLog.log("guard: server put the spear back " + afterMs + " ms after the dash"
                + " (fix " + guardFixes + " of " + MAX_GUARD_FIXES + ")");
        minecraft.player.getInventory().setSelectedSlot(guardPreviousSlot);
        MinecraftSpearDash.sendSelectedSlot(minecraft, true);
        MinecraftSpearDash.alert("Server undid swap, fixed");
    }

    private static boolean isBindingDown(Minecraft minecraft, int keyCode, int mouseButton) {
        if (mouseButton < 0) {
            return Mc.isKeyDown(minecraft, keyCode);
        }
        return GLFW.glfwGetMouseButton(minecraft.getWindow().handle(), mouseButton)
                == GLFW.GLFW_PRESS;
    }

    static String mouseLabel(int button) {
        return switch (button) {
            case 0 -> "Mouse 1";
            case 1 -> "Mouse 2";
            case 2 -> "Middle click";
            case 3 -> "Mouse 4";
            case 4 -> "Mouse 5";
            default -> "Mouse " + (button + 1);
        };
    }
}
