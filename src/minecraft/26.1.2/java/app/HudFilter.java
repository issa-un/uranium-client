package app;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Hide other clients: hides what other clients draw on your HUD (their module list,
 * watermark and so on), whatever client it is. While on, every HUD draw first checks who is
 * drawing. Minecraft and Uranium always draw. Every other source that draws on the HUD (a mod
 * jar, or code injected from outside the game) is listed in the module's settings with its
 * own switch: injected code starts hidden, installed mods start shown, and you can flip any
 * of them, so a client disguised as a mod can be hidden too. Only the HUD: anything drawn
 * while a menu screen is rendering still shows, so another client's own menu (its Right
 * Shift GUI and so on) opens and works as normal. Their modules keep running; only their
 * on-screen list is hidden. Each new source is logged once with the class that drew.
 *
 * The check sits at the start of the GUI drawing calls (GuiGraphicsExtractor) and of the
 * GUI render state's add methods, which every 2D draw ends in, however a client makes it.
 * It is a class rewrite through the agent's Instrumentation, done only once you turn it on
 * and taken out again on Unload. Wrappers in between (subclasses of the drawing class,
 * Minecraft's own helper classes made by mixins) are looked through to find who drew.
 */
public final class HudFilter implements ClassFileTransformer {
    private static final String EXTRACTOR = "net/minecraft/client/gui/GuiGraphicsExtractor";
    private static final String RENDER_STATE = "net/minecraft/client/renderer/state/gui/GuiRenderState";
    // The drawing calls; cursor, scissor and stratum bookkeeping are left alone.
    private static final Map<String, Set<String>> DRAW_METHODS = Map.of(
            EXTRACTOR, Set.of("horizontalLine", "verticalLine", "fill", "fillGradient", "outline", "textHighlight",
                    "text", "centeredText", "textWithWordWrap", "textWithBackdrop", "blit", "blitSprite",
                    "item", "fakeItem", "itemDecorations", "map", "entity", "skin", "book", "bannerPattern",
                    "sign"),
            RENDER_STATE, Set.of("addItem", "addText", "addPicturesInPictureState", "addGuiElement",
                    "addBlitToCurrentLayer", "addGlyphToCurrentLayer"));
    // Code from these is the game, the JDK or Uranium.
    private static final String[] GAME_PACKAGES = {"net.minecraft.", "com.mojang.", "java.", "javax.", "jdk.",
            "sun.", "com.sun.", "org.joml.", "it.unimi.", "org.lwjgl.", "net.fabricmc.", "org.spongepowered.",
            "app."};
    // Where installed mods and the game's libraries live.
    private static final String[] GAME_PLACES = {"/mods/", "/.fabric/", "/libraries/", "/versions/"};
    // Renderers some clients bring to draw their HUD themselves instead of through the game.
    private static final String[] OWN_RENDERERS = {"org.lwjgl.nanovg.", "imgui.", "io.github.humbleui.skija.",
            "org.lwjgl.nuklear.", "org.jetbrains.skija."};
    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    /** Where a drawing class comes from: a jar name, or injected code; injected starts hidden. */
    private record Source(String name, boolean injected) {}

    private static final Source GAME = new Source("", false);
    private static final Map<Class<?>, Source> SOURCES = new ConcurrentHashMap<>();
    private static final Map<String, Source> MIXIN_SOURCES = new ConcurrentHashMap<>();
    private static final Pattern MIXIN_METHOD = Pattern.compile("^[A-Za-z]+\\$[0-9a-z]+\\$([A-Za-z0-9_\\-]+)\\$");
    // Every source seen drawing on the HUD, in the order seen, and your switch for each.
    private static final List<String> SEEN = new CopyOnWriteArrayList<>();
    private static final Map<String, Boolean> HIDDEN = new ConcurrentHashMap<>();
    private static final HudFilter INSTANCE = new HudFilter();
    private static volatile boolean enabled;
    // Hide UI: every source but the game itself is hidden, whatever its switch says.
    private static volatile boolean hideAll;
    private static volatile Instrumentation instrumentation;
    private static volatile boolean installed;
    // Turned on from saved settings before the agent handed Instrumentation over.
    private static volatile boolean pending;
    private static volatile String status = "";
    // Diagnostics for the first seconds after turning it on: who draws on the HUD, and which
    // loaded classes do not come from the game, a mod or Uranium.
    private static final long TRACE_MS = 10_000;
    private static final int TRACE_LIMIT = 150;
    private static final Set<String> TRACED = ConcurrentHashMap.newKeySet();
    private static volatile long traceUntilMs;

    private HudFilter() {}

    /** Handed over by the agent when this version is injected. */
    static void setInstrumentation(Instrumentation value) {
        instrumentation = value;
        if (pending) {
            pending = false;
            setEnabled(true);
        }
    }

    /** Called from the rewritten drawing methods: true to skip this draw. */
    public static boolean skip() {
        if (!enabled && !hideAll) {
            return false;
        }
        try {
            if (System.nanoTime() / 1_000_000 < traceUntilMs && TRACED.size() < TRACE_LIMIT) {
                WALKER.walk(HudFilter::trace);
            }
            return WALKER.walk(HudFilter::fromOtherClientOnHud);
        } catch (Throwable error) {
            return false; // never break the game's drawing
        }
    }

    /**
     * Drawing plumbing between a draw and whoever asked for it: this class, the drawing class
     * and its subclasses, the render state, and Minecraft's helper classes made by mixins.
     */
    private static boolean isPlumbing(Class<?> type) {
        return type == HudFilter.class || type == GuiRenderState.class
                || GuiGraphicsExtractor.class.isAssignableFrom(type)
                || (type.getName().startsWith("net.minecraft.") && type.getName().contains("$Anonymous$"));
    }

    /** Logs each distinct drawing call site once (non-game ones only), with the frames that led to it. */
    private static Void trace(Stream<StackWalker.StackFrame> frames) {
        List<StackWalker.StackFrame> list = frames.limit(16).toList();
        StackWalker.StackFrame caller = null;
        for (StackWalker.StackFrame frame : list) {
            if (!isPlumbing(frame.getDeclaringClass())) {
                caller = frame;
                break;
            }
        }
        if (caller == null) {
            return null;
        }
        Source source = sourceOf(caller.getDeclaringClass());
        if (source == GAME) {
            source = mixinSource(caller.getMethodName());
        }
        if (source == GAME) {
            for (StackWalker.StackFrame frame : list) {
                if (isHudRoot(frame.getDeclaringClass())) {
                    break;
                }
                Source deeper = sourceOf(frame.getDeclaringClass());
                if (deeper != GAME && deeper.injected()) {
                    source = deeper;
                    caller = frame;
                    break;
                }
            }
        }
        String key = caller.getClassName() + "#" + caller.getMethodName();
        if (source == GAME || !TRACED.add(key)) {
            return null;
        }
        StringBuilder out = new StringBuilder("hide other clients: trace [").append(source.name()).append(']');
        for (StackWalker.StackFrame frame : list) {
            if (frame.getDeclaringClass() != HudFilter.class) {
                out.append(" <- ").append(frame.getClassName()).append('#').append(frame.getMethodName());
            }
        }
        SpearLog.log(out.toString());
        return null;
    }

    private static boolean fromOtherClientOnHud(Stream<StackWalker.StackFrame> frames) {
        Iterator<StackWalker.StackFrame> it = frames.iterator();
        Class<?> caller = null;
        String method = "";
        while (it.hasNext()) {
            StackWalker.StackFrame frame = it.next();
            if (!isPlumbing(frame.getDeclaringClass())) {
                caller = frame.getDeclaringClass();
                method = frame.getMethodName();
                break;
            }
        }
        if (caller == null) {
            return false;
        }
        Source source = sourceOf(caller);
        if (source == GAME) {
            source = mixinSource(method); // a mod's code spliced into a Minecraft class
        }
        while (source == GAME && it.hasNext()) {
            // Injected clients without mixins (Doomsday and the like) splice a call to their
            // own code into Minecraft's HUD methods, so the frame right above the draw is the
            // game's. Look further up for their code, but only up to the HUD's own entry
            // points: code wrapped around the whole HUD from above must not hide the hotbar.
            StackWalker.StackFrame frame = it.next();
            Class<?> type = frame.getDeclaringClass();
            if (isHudRoot(type)) {
                return false;
            }
            if (Screen.class.isAssignableFrom(type)) {
                return false;
            }
            Source deeper = sourceOf(type);
            if (deeper != GAME && deeper.injected()) {
                source = deeper;
                caller = type;
            }
        }
        if (source == GAME) {
            return false;
        }
        while (it.hasNext()) {
            if (Screen.class.isAssignableFrom(it.next().getDeclaringClass())) {
                return false; // a menu is drawing (theirs too): leave it
            }
        }
        if (!HIDDEN.containsKey(source.name())) {
            HIDDEN.putIfAbsent(source.name(), source.injected());
        }
        if (!SEEN.contains(source.name())) {
            SEEN.add(source.name());
            SpearLog.log("hide other clients: " + source.name() + " draws on the HUD (" + caller.getName() + "), "
                    + (HIDDEN.get(source.name()) ? "hidden" : "shown"));
        }
        return hideAll || HIDDEN.get(source.name());
    }

    /** Where Minecraft starts drawing the HUD: anything found above these wraps all of it. */
    private static boolean isHudRoot(Class<?> type) {
        String name = type.getName();
        return name.equals("net.minecraft.client.gui.Gui") || name.equals("net.minecraft.client.renderer.GameRenderer")
                || name.equals("net.minecraft.client.gui.render.GuiRenderer") || name.equals("net.minecraft.client.Minecraft");
    }

    /** The game, the JDK and Uranium (GAME), an installed mod or library jar, or code injected from outside. */
    private static Source sourceOf(Class<?> type) {
        return SOURCES.computeIfAbsent(type, t -> {
            String name = t.getName();
            for (String prefix : GAME_PACKAGES) {
                if (name.startsWith(prefix)) {
                    return GAME;
                }
            }
            ClassLoader loader = t.getClassLoader();
            if (loader == null || loader == ClassLoader.getPlatformClassLoader()) {
                return GAME; // part of Java itself
            }
            ProtectionDomain domain = t.getProtectionDomain();
            CodeSource code = domain == null ? null : domain.getCodeSource();
            if (code == null || code.getLocation() == null) {
                String pack = t.getPackageName();
                return new Source("Injected: " + (pack.isEmpty() ? "(no package)" : pack), true);
            }
            String place = code.getLocation().toString().replace('\\', '/');
            int inside = place.indexOf("!/");
            if (inside >= 0) {
                place = place.substring(0, inside);
            }
            while (place.endsWith("/")) {
                place = place.substring(0, place.length() - 1);
            }
            String file = place.substring(place.lastIndexOf('/') + 1);
            String lower = place.toLowerCase(Locale.ROOT) + "/";
            for (String known : GAME_PLACES) {
                if (lower.contains(known)) {
                    return new Source(file, false);
                }
            }
            return new Source("Injected: " + file, true);
        });
    }

    /**
     * A mixin method spliced into a Minecraft class is named "handler$<id>$<mod id>$<name>"
     * (or redirect$, wrapOperation$ and so on), so its draws are put down to that mod.
     */
    private static Source mixinSource(String method) {
        if (method.indexOf('$') < 0) {
            return GAME;
        }
        return MIXIN_SOURCES.computeIfAbsent(method, m -> {
            Matcher match = MIXIN_METHOD.matcher(m);
            return match.find() ? new Source("Mod: " + match.group(1), false) : GAME;
        });
    }

    /**
     * Logs loaded classes that come from outside the game, by source. It only looks at the
     * class list: re-reading another client's classes (a retransform) crashed the game.
     */
    private static void scanLoaded(Instrumentation inst) {
        Map<String, Integer> counts = new TreeMap<>();
        Map<String, String> samples = new TreeMap<>();
        Set<String> memoryClasses = new java.util.HashSet<>();
        for (Class<?> type : inst.getAllLoadedClasses()) {
            if (type.isArray() || type.isPrimitive()) {
                continue;
            }
            Source source;
            try {
                source = sourceOf(type);
            } catch (Throwable error) {
                continue;
            }
            if (source == GAME || !source.injected()) {
                continue;
            }
            counts.merge(source.name(), 1, Integer::sum);
            if (!source.name().endsWith(".jar")) {
                memoryClasses.add(type.getName()); // defined from memory: another client
            }
            samples.merge(source.name(), type.getName(), (a, b) -> a.split(", ").length < 3 ? a + ", " + b : a);
        }
        if (counts.isEmpty()) {
            SpearLog.log("hide other clients: no injected classes found besides Uranium");
        }
        Map<String, Integer> renderers = new TreeMap<>();
        for (Class<?> type : inst.getAllLoadedClasses()) {
            String name = type.getName();
            for (String lib : OWN_RENDERERS) {
                if (name.startsWith(lib)) {
                    renderers.merge(lib, 1, Integer::sum);
                }
            }
        }
        renderers.forEach((lib, count) -> SpearLog.log("hide other clients: " + count + " classes of " + lib
                + " loaded: a client drawing with its own renderer, past Minecraft's HUD"));
        counts.forEach((name, count) -> SpearLog.log("hide other clients: " + count + " classes from "
                + printable(name) + " (" + printable(samples.get(name)) + ")"));
        // No render-thread sampling here: snapshotting the render thread every few ms for
        // seconds paused the game at each snapshot, a heavy stutter every time this turned on.
    }

    /** Names with tabs and other control characters (obfuscated clients use them) made readable. */
    private static String printable(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (c < 0x20 || c == 0x7f) {
                out.append('<').append(String.format(Locale.ROOT, "%02x", (int) c)).append('>');
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** The sources seen drawing on the HUD since it was turned on, for the menu. */
    static List<String> seen() {
        return SEEN;
    }

    static boolean isHidden(String source) {
        return HIDDEN.getOrDefault(source, false);
    }

    static void toggleHidden(String source) {
        HIDDEN.put(source, !isHidden(source));
    }

    /** Your switches, saved as "source=1|source=0". */
    static String hiddenSetting() {
        StringBuilder out = new StringBuilder();
        HIDDEN.forEach((source, hidden) -> out.append(out.length() == 0 ? "" : "|").append(source)
                .append('=').append(hidden ? '1' : '0'));
        return out.toString();
    }

    static void setHiddenSetting(String value) {
        for (String entry : value.split("\\|")) {
            int equals = entry.lastIndexOf('=');
            // Older builds named each injected class on its own; those entries are dropped.
            if (equals > 0 && !entry.matches("Injected: [a-z0-9]+\\..*")) {
                HIDDEN.put(entry.substring(0, equals), entry.endsWith("1"));
            }
        }
    }

    static boolean isEnabled() {
        return enabled || pending;
    }

    static void setEnabled(boolean on) {
        pending = on && instrumentation == null;
        if (pending) {
            return;
        }
        if (on && !install()) {
            enabled = false;
            return;
        }
        if (on && !enabled) {
            try {
                scanLoaded(instrumentation);
            } catch (Throwable error) {
                SpearLog.log("hide other clients: class scan failed: " + error);
            }
            TRACED.clear();
            traceUntilMs = System.nanoTime() / 1_000_000 + TRACE_MS;
        }
        enabled = on;
    }

    static boolean isHideAll() {
        return hideAll;
    }

    /**
     * Hide UI: only Minecraft's own HUD stays on screen. Uranium's HUD and wheels are left out
     * where they draw; every other mod or client is hidden through the same drawing check
     * as Hide other clients. Menus still open and show as normal.
     */
    static void toggleHideAll() {
        boolean on = !hideAll;
        if (on && instrumentation != null && !install()) {
            MinecraftSpearDash.setStatus(status);
        }
        hideAll = on;
        SpearLog.log("hide ui: " + (on ? "on" : "off"));
        MinecraftSpearDash.setStatus(on ? "UI hidden" : "UI shown");
    }

    static void toggleEnabled() {
        setEnabled(!enabled);
        MinecraftSpearDash.setStatus(enabled ? "Other clients' HUD hidden" : status.isEmpty() ? "Other clients' HUD shown" : status);
    }

    /** Adds the check to the drawing methods, the first time it is turned on. */
    private static synchronized boolean install() {
        if (installed) {
            return true;
        }
        Instrumentation inst = instrumentation;
        if (inst == null || !inst.isRetransformClassesSupported()) {
            status = "Hide other clients needs a fresh Update";
            return false;
        }
        try {
            inst.addTransformer(INSTANCE, true);
            inst.retransformClasses(GuiGraphicsExtractor.class, GuiRenderState.class);
            installed = true;
            SpearLog.log("hide other clients: drawing check added");
            return true;
        } catch (Throwable error) {
            inst.removeTransformer(INSTANCE);
            status = "Hide other clients failed: " + error.getClass().getSimpleName();
            SpearLog.log("hide other clients: could not add the drawing check: " + error);
            return false;
        }
    }

    /** Takes the check out again (Unload or Update), leaving the drawing methods as they were. */
    static synchronized void uninstall() {
        enabled = false;
        hideAll = false;
        pending = false;
        Instrumentation inst = instrumentation;
        if (!installed || inst == null) {
            return;
        }
        inst.removeTransformer(INSTANCE);
        try {
            inst.retransformClasses(GuiGraphicsExtractor.class, GuiRenderState.class);
        } catch (Throwable error) {
            SpearLog.log("hide other clients: could not take the drawing check out: " + error);
        }
        installed = false;
    }

    @Override
    public byte[] transform(Module module, ClassLoader loader, String className, Class<?> redefining,
            ProtectionDomain domain, byte[] bytes) {
        Set<String> methods = className == null ? null : DRAW_METHODS.get(className);
        if (methods == null) {
            return null;
        }
        try {
            return addCheck(bytes, methods);
        } catch (Throwable error) {
            SpearLog.log("hide other clients: rewrite of " + className + " failed: " + error);
            return null; // leave the class unchanged
        }
    }

    /** Puts "if (HudFilter.skip()) return;" at the start of every drawing method. */
    private static byte[] addCheck(byte[] bytes, Set<String> methods) {
        String hook = HudFilter.class.getName().replace('.', '/');
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                MethodVisitor visitor = super.visitMethod(access, name, descriptor, signature, exceptions);
                boolean draws = (access & Opcodes.ACC_STATIC) == 0 && (access & Opcodes.ACC_PUBLIC) != 0
                        && descriptor.endsWith(")V") && methods.contains(name);
                if (!draws) {
                    return visitor;
                }
                return new MethodVisitor(Opcodes.ASM9, visitor) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        Label draw = new Label();
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, hook, "skip", "()Z", false);
                        super.visitJumpInsn(Opcodes.IFEQ, draw);
                        super.visitInsn(Opcodes.RETURN);
                        super.visitLabel(draw);
                        // Same locals as on entry, nothing on the stack.
                        super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                    }
                };
            }
        }, 0);
        return writer.toByteArray();
    }
}
