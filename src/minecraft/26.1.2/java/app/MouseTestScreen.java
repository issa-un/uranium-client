package app;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * The mouse test: dots pop up one at a time and you click each one, the way you'd flick
 * onto a player. It watches how your mouse moves between them, the shape of the move only
 * (where your main push stops before you correct, how often you go past, where your speed
 * peaks, how much the path bends), and the modules' aim moves the same way. It never looks
 * at how fast or how accurately you click: being slow or missing changes nothing.
 * Shown once, the first time the menu opens; Play Style has a button to take it again.
 */
public final class MouseTestScreen extends Screen {
    private static final int DOTS = 18;
    // Flicks shorter than this (window pixels) say too little about the shape.
    private static final double MIN_DISTANCE = 60;
    private static final int GREEN = 0xFF39FF14;
    private static final int TEXT = 0xFFE8F5E9;
    private static final int SOFT = 0xFF9CB8A0;

    private enum Stage { INTRO, RUN, DONE }

    private Stage stage = Stage.INTRO;
    // The dot, in screen (GUI) units.
    private int dotX;
    private int dotY;
    private int dotRadius;
    private int dotsDone;
    // The mouse path since the last click: window pixels and ms.
    private final List<double[]> path = new ArrayList<>();
    private double startX;
    private double startY;
    private final List<double[]> flicks = new ArrayList<>();
    private String result = "";
    private boolean saved;

    public MouseTestScreen() {
        super(Component.literal("Mouse test"));
    }

    @Override
    protected void init() {
        clearWidgets();
        int center = width / 2;
        if (stage == Stage.INTRO) {
            addRenderableWidget(Button.builder(Component.literal("Start"), button -> start())
                    .bounds(center - 104, height / 2 + 34, 100, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Skip"), button -> skip())
                    .bounds(center + 4, height / 2 + 34, 100, 20).build());
        } else if (stage == Stage.DONE) {
            if (!saved && !flicksTooFew()) {
                addRenderableWidget(Button.builder(Component.literal("Use my hand"), button -> save())
                        .bounds(center - 154, height / 2 + 40, 100, 20).build());
            }
            addRenderableWidget(Button.builder(Component.literal("Again"), button -> start())
                    .bounds(center - 50, height / 2 + 40, 100, 20).build());
            addRenderableWidget(Button.builder(Component.literal(saved ? "Done" : "Skip"), button -> close())
                    .bounds(center + 54, height / 2 + 40, 100, 20).build());
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void start() {
        stage = Stage.RUN;
        dotsDone = 0;
        flicks.clear();
        saved = false;
        double[] cursor = cursor();
        startX = cursor[0];
        startY = cursor[1];
        path.clear();
        placeDot();
        init();
    }

    private void skip() {
        HandProfile.markAsked();
        SpearSettings.save(Minecraft.getInstance());
        close();
    }

    private void close() {
        HandProfile.markAsked();
        SpearSettings.save(Minecraft.getInstance());
        Mc.setScreen(Minecraft.getInstance(), new ClickGuiScreen());
    }

    @Override
    public void onClose() {
        close();
    }

    private void placeDot() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int margin = 30;
        int oldX = dotX;
        int oldY = dotY;
        for (int tries = 0; tries < 40; tries++) {
            dotX = random.nextInt(margin, Math.max(margin + 1, width - margin));
            dotY = random.nextInt(margin + 20, Math.max(margin + 21, height - margin));
            if (dotsDone == 0 || Math.hypot(dotX - oldX, dotY - oldY) > Math.min(width, height) * 0.3) {
                break;
            }
        }
        dotRadius = random.nextInt(5, 12);
    }

    /** The cursor in window pixels, finer than screen units. */
    private double[] cursor() {
        double[] x = new double[1];
        double[] y = new double[1];
        GLFW.glfwGetCursorPos(Minecraft.getInstance().getWindow().handle(), x, y);
        return new double[] {x[0], y[0]};
    }

    private double scale() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.getWindow().getScreenWidth() / (double) Math.max(1, minecraft.getWindow().getGuiScaledWidth());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xE00B110C);
        int center = width / 2;
        if (stage == Stage.INTRO) {
            line(graphics, "☢ Mouse test", center, height / 2 - 50, GREEN);
            line(graphics, "Click the dots as they pop up, like flicking onto a player.", center, height / 2 - 30, TEXT);
            line(graphics, "Uranium learns how your hand moves the mouse (the shape of it),", center, height / 2 - 16, SOFT);
            line(graphics, "so its aim moves like you. Speed and misses don't matter.", center, height / 2 - 4, SOFT);
            line(graphics, "About 20 seconds. You can take it again from Play Style.", center, height / 2 + 12, SOFT);
        } else if (stage == Stage.RUN) {
            double[] at = cursor();
            path.add(new double[] {at[0], at[1], System.nanoTime() / 1.0E6});
            line(graphics, (dotsDone + 1) + " / " + DOTS, center, 12, SOFT);
            ring(graphics, dotX, dotY, dotRadius, GREEN);
        } else {
            line(graphics, "☢ Your hand", center, height / 2 - 40, GREEN);
            line(graphics, result, center, height / 2 - 20, TEXT);
            line(graphics, saved ? "Saved to humans.txt: the aim now moves like your hand." : flicksTooFew()
                    ? "Not enough clean moves. Try again, with full moves from dot to dot."
                    : "Use it, or go again for a better read.", center, height / 2, SOFT);
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private void line(GuiGraphicsExtractor graphics, String text, int centerX, int y, int color) {
        graphics.text(font, text, centerX - font.width(text) / 2, y, color, false);
    }

    /** A filled dot with a darker middle. */
    private static void ring(GuiGraphicsExtractor graphics, int x, int y, int radius, int color) {
        for (int dy = -radius; dy <= radius; dy++) {
            int half = (int) Math.sqrt(radius * radius - dy * dy);
            graphics.fill(x - half, y + dy, x + half + 1, y + dy + 1, color);
        }
        int inner = Math.max(1, radius / 3);
        for (int dy = -inner; dy <= inner; dy++) {
            int half = (int) Math.sqrt(inner * inner - dy * dy);
            graphics.fill(x - half, y + dy, x + half + 1, y + dy + 1, 0xFF0B110C);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (stage != Stage.RUN || event.button() != 0) {
            return super.mouseClicked(event, doubleClick);
        }
        if (Math.hypot(event.x() - dotX, event.y() - dotY) > dotRadius + 2) {
            return true; // a miss: just try again, it doesn't count against you
        }
        double[] at = cursor();
        double[] flick = analyze(startX, startY, at[0], at[1]);
        if (flick != null) {
            flicks.add(flick);
        }
        startX = at[0];
        startY = at[1];
        path.clear();
        dotsDone++;
        if (dotsDone >= DOTS) {
            finish();
        } else {
            placeDot();
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == 256) { // Esc
            if (stage == Stage.RUN) {
                stage = Stage.INTRO;
                init();
                return true;
            }
            close();
            return true;
        }
        return super.keyPressed(event);
    }

    /**
     * One move from the last click to this one, as {reach, past (0/1), peak, bend}: where
     * the main push stopped (share of the way), whether the move went past the end, where
     * its speed peaked (share of the push), and how far it bowed to a side. Null if the
     * move was too short or too messy to read.
     */
    private double[] analyze(double fromX, double fromY, double toX, double toY) {
        double dx = toX - fromX;
        double dy = toY - fromY;
        double distance = Math.hypot(dx, dy);
        if (distance < MIN_DISTANCE || path.size() < 6) {
            return null;
        }
        double ux = dx / distance;
        double uy = dy / distance;
        int n = path.size();
        double[] along = new double[n];
        double[] side = new double[n];
        double[] time = new double[n];
        for (int i = 0; i < n; i++) {
            double px = path.get(i)[0] - fromX;
            double py = path.get(i)[1] - fromY;
            along[i] = (px * ux + py * uy) / distance;
            side[i] = (px * -uy + py * ux) / distance;
            time[i] = path.get(i)[2];
        }
        // The push starts once the mouse has moved 3% of the way.
        int onset = -1;
        for (int i = 0; i < n; i++) {
            if (along[i] > 0.03) {
                onset = Math.max(0, i - 1);
                break;
            }
        }
        if (onset < 0) {
            return null;
        }
        double[] speed = new double[n];
        for (int i = onset + 1; i < n; i++) {
            speed[i] = (along[i] - along[i - 1]) / Math.max(1, time[i] - time[i - 1]);
        }
        int top = onset + 1;
        for (int i = onset + 1; i < n; i++) {
            if (speed[i] > speed[top]) {
                top = i;
            }
        }
        if (speed[top] <= 0) {
            return null;
        }
        // The main push ends where the speed first drops under a fifth of its peak.
        int end = n - 1;
        for (int i = top + 1; i < n; i++) {
            if (speed[i] < speed[top] * 0.2) {
                end = i;
                break;
            }
        }
        double reach = along[end];
        double furthest = 0;
        double bow = 0;
        for (int i = onset; i < n; i++) {
            furthest = Math.max(furthest, along[i]);
            if (i <= end && Math.abs(side[i]) > Math.abs(bow)) {
                bow = side[i];
            }
        }
        double span = time[end] - time[onset];
        if (span <= 0 || reach < 0.4) {
            return null;
        }
        double peakAt = (time[top] - time[onset]) / span;
        boolean past = furthest > 1.02;
        return new double[] {reach, past ? 1 : 0, peakAt, bow};
    }

    private boolean flicksTooFew() {
        return flicks.size() < 8;
    }

    private void finish() {
        stage = Stage.DONE;
        if (flicksTooFew()) {
            result = "Read " + flicks.size() + " clean moves, needs 8.";
            init();
            return;
        }
        List<Double> shortReach = new ArrayList<>();
        double pasts = 0;
        List<Double> peaks = new ArrayList<>();
        List<Double> bends = new ArrayList<>();
        for (double[] flick : flicks) {
            if (flick[1] == 0 && flick[0] <= 1.0) {
                shortReach.add(flick[0]);
            }
            pasts += flick[1];
            peaks.add(flick[2]);
            bends.add(flick[3]);
        }
        reachValue = shortReach.isEmpty() ? HandProfile.reach() : mean(shortReach);
        reachSpreadValue = shortReach.size() < 3 ? HandProfile.reachSpread() : spread(shortReach);
        overshootValue = pasts / flicks.size();
        peakValue = mean(peaks);
        peakSpreadValue = spread(peaks);
        bendValue = spread(bends);
        result = String.format(Locale.ROOT, "Stops %.0f%% of the way, goes past %.0f%%, fastest %.0f%% in, bends %.1f%%",
                reachValue * 100, overshootValue * 100, peakValue * 100, bendValue * 100);
        SpearLog.log("mouse test: " + flicks.size() + " moves read, " + result);
        init();
    }

    private double reachValue;
    private double reachSpreadValue;
    private double overshootValue;
    private double peakValue;
    private double peakSpreadValue;
    private double bendValue;

    private void save() {
        HandProfile.set(reachValue, reachSpreadValue, overshootValue, peakValue, peakSpreadValue, bendValue);
        HandProfile.saveHumans();
        SpearSettings.save(Minecraft.getInstance());
        saved = true;
        result = "Saved: " + HandProfile.describe();
        SpearLog.log("mouse test: saved, " + HandProfile.describe());
        init();
    }

    private static double mean(List<Double> values) {
        double sum = 0;
        for (double value : values) {
            sum += value;
        }
        return sum / values.size();
    }

    private static double spread(List<Double> values) {
        double average = mean(values);
        double sum = 0;
        for (double value : values) {
            sum += (value - average) * (value - average);
        }
        return Math.sqrt(sum / Math.max(1, values.size() - 1));
    }
}
