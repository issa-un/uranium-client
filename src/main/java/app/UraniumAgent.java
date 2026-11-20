package app;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Loaded into the game by the injector. Arguments: "agentJar|statusFile|command", where
 * command is "open" (the default), "unload", "update" or "status". The result goes to the
 * status file.
 */
public final class UraniumAgent {
    // Each build's classes live in their own package, so the GUI is found next to this class.
    private static final String GUI_CLASS = UraniumAgent.class.getPackageName() + ".ClickGuiScreen";
    // Every injected version has one: app.MinecraftInputControllerV18 and so on up to v31,
    // app.<build tag>.MinecraftInputController after that, and app.<build tag>.InputLoop since
    // the rename. v18 and later can switch themselves off.
    private static final Pattern CONTROLLER =
            Pattern.compile("app\\.(?:b[0-9a-f]+\\.)?(?:MinecraftInputController(?:V\\d+)?|InputLoop)");
    private static final long UNLOAD_WAIT_MS = 2000;

    private UraniumAgent() {}

    /** Which injected versions were asked to switch off, and how many are too old to. */
    private record UnloadResult(List<Class<?>> unloaded, int tooOld) {}

    public static void agentmain(String arguments, Instrumentation instrumentation) {
        Path statusFile = null;
        try {
            String[] parts = arguments.split("\\|", 3);
            if (parts.length < 2) {
                throw new IllegalArgumentException("Expected agent JAR and status file paths.");
            }
            statusFile = Path.of(parts[1]);
            Path agentJar = Path.of(parts[0]);
            String command = parts.length == 3 ? parts[2] : "open";
            String result = switch (command) {
                case "open" -> {
                    openGui(agentJar, instrumentation);
                    yield "GUI opened.";
                }
                case "unload" -> {
                    UnloadResult unloaded = unloadAll(instrumentation);
                    waitUntilOff(unloaded.unloaded(), instrumentation);
                    yield describe(unloaded);
                }
                case "update" -> {
                    UnloadResult unloaded = unloadAll(instrumentation);
                    waitUntilOff(unloaded.unloaded(), instrumentation);
                    openGui(agentJar, instrumentation);
                    yield "Updated. " + describe(unloaded);
                }
                case "status" -> status(instrumentation);
                default -> throw new IllegalArgumentException("Unknown command: " + command);
            };
            writeStatus(statusFile, result);
        } catch (Throwable exception) {
            if (statusFile != null) {
                writeFailure(statusFile, exception);
            }
            throw new IllegalStateException("The injector command failed.", exception);
        }
    }

    /** Asks every injected version that has Unload (v18 and later) to switch itself off. */
    private static UnloadResult unloadAll(Instrumentation instrumentation) throws Exception {
        List<Class<?>> unloaded = new ArrayList<>();
        int tooOld = 0;
        for (Class<?> type : instrumentation.getAllLoadedClasses()) {
            if (!CONTROLLER.matcher(type.getName()).matches()) {
                continue;
            }
            Method requestUnload;
            try {
                requestUnload = type.getDeclaredMethod("requestUnload");
            } catch (NoSuchMethodException exception) {
                tooOld++;
                continue;
            }
            if (!isInstalled(type)) {
                continue; // already switched off
            }
            // The game's classes live in another class loader, so package access needs this.
            requestUnload.setAccessible(true);
            requestUnload.invoke(null);
            unloaded.add(type);
        }
        return new UnloadResult(unloaded, tooOld);
    }

    /**
     * Unload happens on each version's next tick; wait so the new version starts clean.
     * Versions up to v23 stop ticking for good if you disconnect at the wrong moment
     * (Minecraft drops queued tasks), so they never see the request. Any still on after
     * the wait get their own unload run directly on the game thread.
     */
    private static void waitUntilOff(List<Class<?>> controllers, Instrumentation instrumentation)
            throws Exception {
        if (waitFor(controllers)) {
            return;
        }
        Class<?> minecraftClass = findLoaded(instrumentation, "net.minecraft.client.Minecraft");
        Object minecraft = minecraftClass.getMethod("getInstance").invoke(null);
        Method execute = minecraftClass.getMethod("execute", Runnable.class);
        for (Class<?> controller : controllers) {
            if (!isInstalled(controller)) {
                continue;
            }
            Method unload = controller.getDeclaredMethod("unload", minecraftClass);
            unload.setAccessible(true);
            execute.invoke(minecraft, (Runnable) () -> {
                try {
                    unload.invoke(null, minecraft);
                } catch (ReflectiveOperationException ignored) {
                    // Left on; the status line will not count it as unloaded.
                }
            });
        }
        waitFor(controllers);
    }

    /** True once every controller is off, false if UNLOAD_WAIT_MS passes first. */
    private static boolean waitFor(List<Class<?>> controllers) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(UNLOAD_WAIT_MS);
        while (controllers.stream().anyMatch(UraniumAgent::isInstalled)) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            Thread.sleep(20);
        }
        return true;
    }

    /** One line per injected version: on or off, and for one that is on, whether its loop runs. */
    private static String status(Instrumentation instrumentation) {
        List<String> lines = new ArrayList<>();
        for (Class<?> type : instrumentation.getAllLoadedClasses()) {
            if (CONTROLLER.matcher(type.getName()).matches()) {
                lines.add(versionName(type) + ": " + (isInstalled(type) ? "on, " + loopState(type) : "off"));
            }
        }
        if (lines.isEmpty()) {
            return "No injected versions in this game.";
        }
        lines.sort(null);
        return String.join("\n", lines);
    }

    /** "build bbc388d0c" since automatic versioning, else the old numbered controller. */
    private static String versionName(Class<?> controller) {
        String packageName = controller.getPackageName();
        String name = packageName.equals("app")
                ? "old " + controller.getSimpleName().replace("MinecraftInputController", "controller ")
                : "build " + packageName.substring("app.".length());
        return packageName.equals(UraniumAgent.class.getPackageName()) ? name + " (this build)" : name;
    }

    /** Whether the version's loop is still ticking; builds before the Status button cannot say. */
    private static String loopState(Class<?> controller) {
        try {
            Field lastTick = controller.getDeclaredField("lastTickMs");
            lastTick.setAccessible(true);
            long sinceMs = System.nanoTime() / 1_000_000 - lastTick.getLong(null);
            return sinceMs < 2000 ? "loop running" : "loop STOPPED (" + sinceMs / 1000 + " s since its last tick)";
        } catch (ReflectiveOperationException exception) {
            return "loop unknown (older build)";
        }
    }

    private static boolean isInstalled(Class<?> controller) {
        try {
            Field installed = controller.getDeclaredField("INSTALLED");
            installed.setAccessible(true);
            return ((AtomicBoolean) installed.get(null)).get();
        } catch (ReflectiveOperationException | ClassCastException exception) {
            return true; // cannot tell, so assume it is on
        }
    }

    private static String describe(UnloadResult result) {
        int count = result.unloaded().size();
        String text = count == 0
                ? "Nothing to unload."
                : "Unloaded " + count + (count == 1 ? " version." : " versions.");
        if (result.tooOld() > 0) {
            text += " " + result.tooOld() + " older (before v18) need a Minecraft restart.";
        }
        return text;
    }

    private static void openGui(Path agentJar, Instrumentation instrumentation) throws Exception {
        Class<?> minecraftClass = findLoaded(instrumentation, "net.minecraft.client.Minecraft");
        Class<?> screenClass = Class.forName(
                "net.minecraft.client.gui.screens.Screen", true, minecraftClass.getClassLoader());
        Class<?> guiClass = findLoadedOptional(instrumentation, GUI_CLASS);
        if (guiClass == null) {
            Class<?> launcherBase = Class.forName(
                    "net.fabricmc.loader.impl.launch.FabricLauncherBase", true,
                    minecraftClass.getClassLoader());
            Class<?> launcherApi = Class.forName(
                    "net.fabricmc.loader.impl.launch.FabricLauncher", true,
                    launcherBase.getClassLoader());
            Object launcher = launcherBase.getMethod("getLauncher").invoke(null);
            launcherApi.getMethod("addToClassPath", Path.class, String[].class)
                    .invoke(launcher, agentJar, new String[0]);
            guiClass = (Class<?>) launcherApi.getMethod("loadIntoTarget", String.class)
                    .invoke(launcher, GUI_CLASS);
        }
        if (!screenClass.isAssignableFrom(guiClass)) {
            throw new IllegalStateException("The GUI class is incompatible with this client.");
        }
        // Hide other clients rewrites a game class, which needs the agent's Instrumentation.
        try {
            Class<?> hudFilter = Class.forName(guiClass.getPackageName() + ".HudFilter", true,
                    guiClass.getClassLoader());
            Method setInstrumentation = hudFilter.getDeclaredMethod("setInstrumentation", Instrumentation.class);
            setInstrumentation.setAccessible(true);
            setInstrumentation.invoke(null, instrumentation);
        } catch (ClassNotFoundException | NoSuchMethodException ignored) {
            // A build without it.
        }

        Object minecraft = minecraftClass.getMethod("getInstance").invoke(null);
        // setScreen is on Minecraft up to 26.1 and on minecraft.gui from 26.2.
        Object screenOwner;
        Method setScreen;
        try {
            setScreen = minecraftClass.getMethod("setScreen", screenClass);
            screenOwner = minecraft;
        } catch (NoSuchMethodException newer) {
            screenOwner = minecraftClass.getField("gui").get(minecraft);
            setScreen = screenOwner.getClass().getMethod("setScreen", screenClass);
        }
        Method execute = minecraftClass.getMethod("execute", Runnable.class);
        CompletableFuture<Void> opened = new CompletableFuture<>();
        Class<?> finalGuiClass = guiClass;
        Object finalOwner = screenOwner;
        Method finalSetScreen = setScreen;
        execute.invoke(minecraft, (Runnable) () -> {
            try {
                finalSetScreen.invoke(finalOwner, finalGuiClass.getConstructor().newInstance());
                opened.complete(null);
            } catch (InvocationTargetException exception) {
                opened.completeExceptionally(exception.getCause());
            } catch (Exception exception) {
                opened.completeExceptionally(exception);
            }
        });
        opened.get(10, TimeUnit.SECONDS);
    }

    private static Class<?> findLoaded(Instrumentation instrumentation, String name) {
        return Arrays.stream(instrumentation.getAllLoadedClasses())
                .filter(type -> type.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(name + " is not loaded yet."));
    }

    private static Class<?> findLoadedOptional(Instrumentation instrumentation, String name) {
        return Arrays.stream(instrumentation.getAllLoadedClasses())
                .filter(type -> type.getName().equals(name))
                .findFirst()
                .orElse(null);
    }

    private static void writeFailure(Path file, Throwable exception) {
        StringWriter output = new StringWriter();
        exception.printStackTrace(new PrintWriter(output));
        writeStatus(file, output.toString());
    }

    private static void writeStatus(Path file, String message) {
        try {
            Files.writeString(file, message);
        } catch (Exception ignored) {
        }
    }
}
