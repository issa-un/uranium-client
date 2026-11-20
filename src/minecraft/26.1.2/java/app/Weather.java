package app;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostChainConfig;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.client.renderer.UniformValue;
import net.minecraft.resources.Identifier;
import org.joml.Vector3f;

/**
 * Weather, with two modes.
 *
 * Clear: no rain or thunder on your screen; the server's weather is unchanged and comes
 * back with its next weather change after you turn it off.
 *
 * Cinematic: a film look. The world (not the HUD) gets a real GPU color grade, run by the
 * game's own post-effect slot (the one the creeper and spider views use) right after the
 * world is drawn and before the HUD: a little warmer, a touch more color and a gentle
 * contrast. It uses only shaders the game ships (color_convolve and invert), so no
 * resource pack and no reload. On top of the world, under the crosshair and hotbar, sits a
 * soft warm vignette (the game's own vignette texture, one quad), and with Film bars two
 * thin black bars at the top and bottom.
 *
 * Cost per frame: two full-screen passes that read one pixel and do a few multiply-adds
 * each (the same work as the creeper view), one textured quad, and with Film bars four
 * fills. No per-pixel work on the CPU. The setup (building the effect) happens once, and
 * again only after a resource reload.
 *
 * If anything about the GPU part fails, it is switched off for this injection and logged
 * once; the vignette keeps working on its own.
 */
final class Weather {
    // Every build lives in its own package, and Fabric keeps the name of a removed HUD
    // element, so each build needs names of its own.
    private static final String SUFFIX = Weather.class.getPackageName().replace('.', '_').toLowerCase();
    private static final Identifier HUD_ID = Identifier.fromNamespaceAndPath("app", "cinematic_hud_" + SUFFIX);
    private static final Identifier CHAIN_ID = Identifier.fromNamespaceAndPath("app", "cinematic_" + SUFFIX);
    private static final Identifier SWAP = Identifier.withDefaultNamespace("swap");
    private static final Identifier SCREEN_QUAD = Identifier.withDefaultNamespace("core/screenquad");
    private static final Identifier COLOR_CONVOLVE = Identifier.withDefaultNamespace("post/color_convolve");
    private static final Identifier INVERT = Identifier.withDefaultNamespace("post/invert");
    private static final Identifier VIGNETTE = Identifier.withDefaultNamespace("textures/misc/vignette.png");

    // The grade: warmer (more red, less blue), a touch more saturated, gently more contrast.
    private static final float[] GAIN = {1.05F, 1.0F, 0.92F};
    private static final float SATURATION = 1.10F;
    // color_convolve always boosts saturation by 1.8 after its matrix; undone in the matrix.
    private static final float SHADER_SATURATION = 1.8F;
    private static final float[] GRAY = {0.3F, 0.59F, 0.11F};
    // invert computes mix(c, 1 - c, a); a negative a is contrast around mid-grey:
    // (1 + 2k)(c - 0.5) + 0.5 for a = -k. 0.035 gives 1.07x contrast.
    private static final float CONTRAST = 0.035F;
    // How much the vignette darkens the corners, per channel: blue most, so the edges go warm.
    private static final float[] VIGNETTE_DARKEN = {0.30F, 0.36F, 0.44F};
    // Film bars: each this share of the screen's height.
    private static final double BAR_SHARE = 0.045;

    private static volatile boolean enabled;
    private static volatile boolean cinematic;
    private static volatile boolean bars;

    private static final AtomicBoolean HUD_REGISTERED = new AtomicBoolean();
    private static volatile boolean hudActive;
    private static boolean hudFailed;
    // Render thread only.
    private static boolean shaderFailed;
    private static PostChain chain;
    private static Object chainCache;
    private static Field cacheField;
    private static Field cacheChainsField;
    private static Field projectionField;
    private static Field projectionBufferField;
    private static Field postEffectField;
    private static Field effectActiveField;

    private Weather() {}

    /** Called from the input loop, on the render thread. */
    static void update(Minecraft minecraft) {
        if (enabled && !cinematic && minecraft.level != null) {
            minecraft.level.setRainLevel(0);
            minecraft.level.setThunderLevel(0);
        }
        boolean want = enabled && cinematic;
        if (want) {
            registerHud();
        }
        hudActive = want;
        if (shaderFailed) {
            return;
        }
        try {
            if (want && minecraft.level != null) {
                applyGrade(minecraft);
            } else if (chain != null) {
                removeGrade(minecraft);
            }
        } catch (RuntimeException | LinkageError | ReflectiveOperationException error) {
            shaderFailed = true;
            UraniumLog.log("weather: cinematic color grade failed, switched off (" + error + ")");
            try {
                removeGrade(minecraft);
            } catch (RuntimeException | LinkageError | ReflectiveOperationException ignored) {
                // Already logged; the game's own effects are left as they are.
            }
        }
    }

    private static void resolveFields() throws ReflectiveOperationException {
        if (cacheField != null) {
            return;
        }
        Field cache = ShaderManager.class.getDeclaredField("compilationCache");
        Field chains = cache.getType().getDeclaredField("postChains");
        Field projection = ShaderManager.class.getDeclaredField("postChainProjection");
        Field projectionBuffer = ShaderManager.class.getDeclaredField("postChainProjectionMatrixBuffer");
        Field postEffect = GameRenderer.class.getDeclaredField("postEffectId");
        Field effectActive = GameRenderer.class.getDeclaredField("effectActive");
        for (Field field : new Field[] {cache, chains, projection, projectionBuffer, postEffect, effectActive}) {
            field.setAccessible(true);
        }
        cacheChainsField = chains;
        projectionField = projection;
        projectionBufferField = projectionBuffer;
        postEffectField = postEffect;
        effectActiveField = effectActive;
        cacheField = cache;
    }

    @SuppressWarnings("unchecked")
    private static Map<Identifier, Optional<PostChain>> chains(Object cache) throws ReflectiveOperationException {
        return (Map<Identifier, Optional<PostChain>>) cacheChainsField.get(cache);
    }

    /**
     * Puts the grade in the shader manager's post-chain cache under our own name and points
     * the game's post-effect slot at it. A resource reload makes a new cache (and closes the
     * old one, with our chain in it), so it is built again then.
     */
    private static void applyGrade(Minecraft minecraft) throws ReflectiveOperationException {
        resolveFields();
        ShaderManager shaders = minecraft.getShaderManager();
        Object cache = cacheField.get(shaders);
        Map<Identifier, Optional<PostChain>> cached = chains(cache);
        if (cache != chainCache || chain == null || !cached.containsKey(CHAIN_ID)) {
            chain = null;
            chainCache = null;
            try {
                chain = PostChain.load(config(), minecraft.getTextureManager(), LevelTargetBundle.MAIN_TARGETS,
                        CHAIN_ID, (Projection) projectionField.get(shaders),
                        (ProjectionMatrixBuffer) projectionBufferField.get(shaders));
            } catch (ShaderManager.CompilationException error) {
                throw new IllegalStateException("could not build the effect: " + error.getMessage(), error);
            }
            cached.put(CHAIN_ID, Optional.of(chain));
            chainCache = cache;
        }
        GameRenderer renderer = minecraft.gameRenderer;
        Identifier current = renderer.currentPostEffect();
        // The game's own effects (spectating a creeper, spider or enderman) win; ours comes
        // back when they clear.
        if (current == null || current.equals(CHAIN_ID)) {
            if (current == null) {
                postEffectField.set(renderer, CHAIN_ID);
            }
            if (!effectActiveField.getBoolean(renderer)) {
                effectActiveField.setBoolean(renderer, true);
            }
        }
    }

    private static void removeGrade(Minecraft minecraft) throws ReflectiveOperationException {
        GameRenderer renderer = minecraft.gameRenderer;
        if (CHAIN_ID.equals(renderer.currentPostEffect())) {
            renderer.clearPostEffect();
        }
        PostChain old = chain;
        Object oldCache = chainCache;
        chain = null;
        chainCache = null;
        if (old != null && cacheField != null && oldCache == cacheField.get(minecraft.getShaderManager())) {
            // Still in the live cache: take it out and free it. (A replaced cache already
            // closed it.)
            chains(oldCache).remove(CHAIN_ID);
            old.close();
        }
    }

    /** main -> swap: the color matrix; swap -> main: the contrast. */
    private static PostChainConfig config() {
        Vector3f[] rows = gradeRows();
        PostChainConfig.Pass grade = new PostChainConfig.Pass(SCREEN_QUAD, COLOR_CONVOLVE,
                List.of(new PostChainConfig.TargetInput("In", PostChain.MAIN_TARGET_ID, false, false)), SWAP,
                Map.of("ColorConfig", List.of(new UniformValue.Vec3Uniform(rows[0]),
                        new UniformValue.Vec3Uniform(rows[1]), new UniformValue.Vec3Uniform(rows[2]))));
        PostChainConfig.Pass contrast = new PostChainConfig.Pass(SCREEN_QUAD, INVERT,
                List.of(new PostChainConfig.TargetInput("In", SWAP, false, false)), PostChain.MAIN_TARGET_ID,
                Map.of("InvertConfig", List.<UniformValue>of(new UniformValue.FloatUniform(-CONTRAST))));
        return new PostChainConfig(
                Map.of(SWAP, new PostChainConfig.InternalTarget(Optional.empty(), Optional.empty(), false, 0)),
                List.of(grade, contrast));
    }

    /**
     * The rows of the color matrix. Wanted: saturation SATURATION after the GAIN tint.
     * The shader then scales saturation by 1.8 around the same grey, and saturation
     * changes multiply, so the matrix asks for SATURATION / 1.8:
     * row i, column j = (s * [i == j] + (1 - s) * gray[j]) * gain[j].
     */
    static Vector3f[] gradeRows() {
        float s = SATURATION / SHADER_SATURATION;
        Vector3f[] rows = new Vector3f[3];
        for (int i = 0; i < 3; i++) {
            float[] row = new float[3];
            for (int j = 0; j < 3; j++) {
                row[j] = ((i == j ? s : 0) + (1 - s) * GRAY[j]) * GAIN[j];
            }
            rows[i] = new Vector3f(row[0], row[1], row[2]);
        }
        return rows;
    }

    private static void registerHud() {
        if (!HUD_REGISTERED.compareAndSet(false, true)) {
            return;
        }
        try {
            // Over the world, under the crosshair, hotbar and the rest of the HUD.
            HudElementRegistry.attachElementBefore(VanillaHudElements.CROSSHAIR, HUD_ID, Weather::drawHud);
        } catch (RuntimeException | LinkageError error) {
            hudFailed = true; // Fabric API's HUD module is missing or changed
            UraniumLog.log("weather: could not add the cinematic vignette (" + error + ")");
        }
    }

    private static void drawHud(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        if (!hudActive || hudFailed) {
            return;
        }
        try {
            int width = graphics.guiWidth();
            int height = graphics.guiHeight();
            int darken = 0xFF000000 | channel(VIGNETTE_DARKEN[0]) << 16
                    | channel(VIGNETTE_DARKEN[1]) << 8 | channel(VIGNETTE_DARKEN[2]);
            graphics.blit(RenderPipelines.VIGNETTE, VIGNETTE, 0, 0, 0, 0, width, height, width, height, darken);
            if (bars) {
                int bar = Math.max(2, (int) Math.round(height * BAR_SHARE));
                graphics.fill(0, 0, width, bar, 0xFF000000);
                graphics.fillGradient(0, bar, width, bar + 2, 0x80000000, 0x00000000);
                graphics.fill(0, height - bar, width, height, 0xFF000000);
                graphics.fillGradient(0, height - bar - 2, width, height - bar, 0x00000000, 0x80000000);
            }
        } catch (RuntimeException error) {
            // A drawing bug must never take the frame down; logged once, then left off.
            hudFailed = true;
            UraniumLog.log("weather: cinematic vignette failed, switched off (" + error + ")");
        }
    }

    private static int channel(float value) {
        return Math.round(Math.max(0, Math.min(1, value)) * 255);
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

    static String modeLabel() {
        return cinematic ? "Cinematic" : "Clear";
    }

    static void setMode(String label) {
        cinematic = "Cinematic".equalsIgnoreCase(label);
    }

    static void cycleMode() {
        cinematic = !cinematic;
    }

    static boolean isBars() {
        return bars;
    }

    static void setBars(boolean on) {
        bars = on;
    }

    static void toggleBars() {
        bars = !bars;
    }

    /** Turns it off when this injection is unloaded and gives the game its post-effect slot back. */
    static void reset(Minecraft minecraft) {
        enabled = false;
        hudActive = false;
        try {
            removeGrade(minecraft);
        } catch (RuntimeException | LinkageError | ReflectiveOperationException error) {
            UraniumLog.log("weather: could not remove the cinematic grade on unload (" + error + ")");
        }
    }
}
