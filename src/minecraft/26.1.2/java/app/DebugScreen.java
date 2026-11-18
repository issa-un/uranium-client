package app;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * The Debug screen: Human % for the last minute, every mouse move the modules made (click
 * one to see its path and its speed, report by report), and the list of things that could
 * get you flagged, newest first. The test buttons make the same moves on your view right
 * here (a flick, stutter, glide or shake, out and back) or a Replenish cursor trip across
 * the screen, so you can look at them without a fight.
 */
public final class DebugScreen extends Screen {
    private static final int GREEN = 0xFF39FF14;
    private static final int YELLOW = 0xFFFFD54F;
    private static final int RED = 0xFFFF5252;
    private static final int TEXT = 0xFFE8F5E9;
    private static final int SOFT = 0xFF9CB8A0;
    private static final int PANEL = 0xC0101A12;
    private final HumanAim test = new HumanAim();
    // The test's way back once it is out: {yaw, pitch, kind}; null if none.
    private float[] back;
    private String backKind;
    // A test cursor trip: window pixels, ms.
    private double cFromX;
    private double cFromY;
    private double cToX;
    private double cToY;
    private long cStartMs = -1;
    private long cMs;
    private double cBend;
    private Debug.Move cRec;
    private int selected;
    // Rows of the move list this frame: {y top, index}.
    private final int[] rowTops = new int[16];
    private int rowCount;
    private int listX;
    private int listW;

    public DebugScreen() {
        super(Component.literal("Debug"));
    }

    @Override
    protected void init() {
        Debug.setEnabled(true);
        int x = 8;
        String[] tests = {"Flick", "Stutter", "Glide", "Shake", "Cursor"};
        for (String name : tests) {
            addRenderableWidget(Button.builder(Component.literal("Test " + name), button -> runTest(name))
                    .bounds(x, 26, 62, 16).build());
            x += 66;
        }
        addRenderableWidget(Button.builder(Component.literal("Clear"), button -> {
            Debug.clear();
            selected = 0;
        }).bounds(width - 112, 6, 50, 16).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
                .bounds(width - 58, 6, 50, 16).build());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        test.stop();
        Mc.setScreen(Minecraft.getInstance(), new ClickGuiScreen());
    }

    private void runTest(String name) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        float yaw = (float) (random.nextDouble(20, 45) * (random.nextBoolean() ? 1 : -1));
        float pitch = (float) random.nextDouble(-8, 8);
        test.stop();
        back = null;
        switch (name) {
            case "Flick" -> test.flick(yaw, pitch, 3.0F, true);
            case "Stutter" -> test.stutter(yaw, pitch, 160);
            case "Glide" -> test.glide(yaw, pitch, 300);
            case "Shake" -> test.shake(1.2F, 160);
            default -> {
                startCursor();
                return;
            }
        }
        if (!name.equals("Shake")) {
            back = new float[] {-yaw, -pitch};
            backKind = name;
        }
        selected = 0;
    }

    /** A Replenish-style cursor trip across the screen: bowed, wobbly, a minimum-jerk speed. */
    private void startCursor() {
        Minecraft minecraft = Minecraft.getInstance();
        double[] x = new double[1];
        double[] y = new double[1];
        GLFW.glfwGetCursorPos(minecraft.getWindow().handle(), x, y);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        cFromX = x[0];
        cFromY = y[0];
        cToX = minecraft.getWindow().getScreenWidth() * random.nextDouble(0.2, 0.8);
        cToY = minecraft.getWindow().getScreenHeight() * random.nextDouble(0.3, 0.8);
        double distance = Math.hypot(cToX - cFromX, cToY - cFromY);
        cMs = Math.round((110 + 0.25 * distance) * random.nextDouble(0.8, 1.25));
        cBend = distance * random.nextDouble(0.04, 0.16) * (random.nextBoolean() ? 1 : -1);
        cStartMs = Debug.now();
        cRec = Debug.beginCursor("Replenish test", distance, cMs);
        selected = 0;
    }

    private void stepCursor() {
        if (cStartMs < 0) {
            return;
        }
        double t = Math.min(1.0, (Debug.now() - cStartMs) / (double) Math.max(1, cMs));
        double s = t * t * t * (10 + t * (-15 + 6 * t));
        double dx = cToX - cFromX;
        double dy = cToY - cFromY;
        double length = Math.max(1, Math.hypot(dx, dy));
        double side = cBend * Math.sin(Math.PI * s);
        double px = cFromX + dx * s - dy / length * side;
        double py = cFromY + dy * s + dx / length * side;
        GLFW.glfwSetCursorPos(Minecraft.getInstance().getWindow().handle(), px, py);
        Debug.point(cRec, px, py);
        if (t >= 1) {
            Debug.end(cRec);
            cRec = null;
            cStartMs = -1;
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null && test.active()) {
            test.step(player);
        } else if (player != null && back != null) {
            float[] way = back;
            back = null;
            switch (backKind) {
                case "Flick" -> test.flick(way[0], way[1], 3.0F, true);
                case "Stutter" -> test.stutter(way[0], way[1], 160);
                default -> test.glide(way[0], way[1], 300);
            }
        }
        stepCursor();

        graphics.fill(0, 0, width, height, 0xB0000000);
        int human = Debug.humanPercent();
        int color = human >= 85 ? GREEN : human >= 60 ? YELLOW : RED;
        graphics.text(font, "☢ Debug", 8, 10, GREEN, false);
        String score = "Human " + human + "%  ·  " + Debug.flagsLastMinute() + " could flag in the last minute";
        graphics.text(font, score, 60, 10, color, false);
        graphics.text(font, "Your hand: " + (HandProfile.isMeasured() ? HandProfile.describe() : "average (take the mouse test in Play Style)"),
                8, height - 12, SOFT, false);

        List<Debug.Move> moves = Debug.moves();
        int top = 48;
        int half = width / 2;
        listX = 8;
        listW = half - 14;
        graphics.fill(listX - 2, top - 2, listX + listW, height - 18, PANEL);
        graphics.text(font, "Mouse moves (click one)", listX + 2, top + 1, SOFT, false);
        rowCount = 0;
        int y = top + 13;
        for (int i = 0; i < moves.size() && rowCount < rowTops.length && y < height - 30; i++) {
            Debug.Move move = moves.get(i);
            String line = String.format(Locale.ROOT, "%s · %s %.0f%s · %d ms · %d steps",
                    move.who, move.kind, move.plannedDegrees, move.cursor ? "px" : "°", move.tookMs(), move.points.size());
            if (i == selected) {
                graphics.fill(listX, y - 2, listX + listW - 2, y + 10, 0x6039FF14);
            }
            graphics.text(font, font.plainSubstrByWidth(line, listW - 30), listX + 3, y, TEXT, false);
            if (!move.issues.isEmpty()) {
                graphics.text(font, "!" + move.issues.size(), listX + listW - 22, y, RED, false);
            }
            rowTops[rowCount++] = y;
            y += 13;
        }
        if (moves.isEmpty()) {
            graphics.text(font, "Nothing yet: use a module, or a test button.", listX + 3, y, SOFT, false);
        }

        // The selected move's path and speed.
        int boxX = half;
        int boxW = width - half - 8;
        int boxH = Math.min(110, (height - 80) / 2);
        graphics.fill(boxX - 2, top - 2, boxX + boxW, top + boxH + 40, PANEL);
        if (!moves.isEmpty()) {
            Debug.Move move = moves.get(Math.min(selected, moves.size() - 1));
            graphics.text(font, move.cursor ? "Cursor path" : "View path (yaw across, pitch down)", boxX + 2, top + 1, SOFT, false);
            drawPath(graphics, move, boxX + 4, top + 12, boxW - 10, boxH - 14);
            drawSpeed(graphics, move, boxX + 4, top + boxH + 2, boxW - 10, 34);
            int iy = top + boxH + 40;
            for (String issue : move.issues) {
                iy += 10;
                graphics.text(font, font.plainSubstrByWidth("! " + issue, boxW), boxX, iy, RED, false);
            }
        }

        // Could flag, newest first.
        int notesTop = top + boxH + 44 + 10 * (moves.isEmpty() ? 0 : moves.get(Math.min(selected, moves.size() - 1)).issues.size()) + 6;
        graphics.fill(boxX - 2, notesTop - 2, boxX + boxW, height - 18, PANEL);
        graphics.text(font, "What happened (red/yellow could flag)", boxX + 2, notesTop + 1, SOFT, false);
        int ny = notesTop + 13;
        long now = Debug.now();
        for (Debug.Note note : Debug.notes()) {
            if (ny > height - 30) {
                break;
            }
            int c = note.weight() >= 10 ? RED : note.weight() > 0 ? YELLOW : SOFT;
            String line = String.format(Locale.ROOT, "%.1fs  %s: %s", (now - note.ms()) / 1000.0, note.who(), note.text());
            graphics.text(font, font.plainSubstrByWidth(line, boxW - 4), boxX + 2, ny, c, false);
            ny += 10;
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    /** The move's path, scaled to the box, with a dot at each report. */
    private void drawPath(GuiGraphicsExtractor graphics, Debug.Move move, int x, int y, int w, int h) {
        List<double[]> points = move.points;
        if (points.size() < 2) {
            return;
        }
        double minX = 0;
        double maxX = 0;
        double minY = 0;
        double maxY = 0;
        double originX = move.cursor ? points.get(0)[1] : 0;
        double originY = move.cursor ? points.get(0)[2] : 0;
        for (double[] p : points) {
            minX = Math.min(minX, p[1] - originX);
            maxX = Math.max(maxX, p[1] - originX);
            minY = Math.min(minY, p[2] - originY);
            maxY = Math.max(maxY, p[2] - originY);
        }
        double span = Math.max(1.0E-3, Math.max(maxX - minX, maxY - minY));
        double scale = Math.min(w, h) / span;
        int ox = x + (int) ((w - (maxX - minX) * scale) / 2);
        int oy = y + (int) ((h - (maxY - minY) * scale) / 2);
        int lastX = ox + (int) ((0 - minX) * scale);
        int lastY = oy + (int) ((0 - minY) * scale);
        graphics.fill(lastX - 2, lastY - 2, lastX + 2, lastY + 2, SOFT);
        for (double[] p : points) {
            int px = ox + (int) ((p[1] - originX - minX) * scale);
            int py = oy + (int) ((p[2] - originY - minY) * scale);
            int steps = Math.max(1, Math.max(Math.abs(px - lastX), Math.abs(py - lastY)));
            for (int i = 1; i <= steps; i++) {
                int ix = lastX + (px - lastX) * i / steps;
                int iy = lastY + (py - lastY) * i / steps;
                graphics.fill(ix, iy, ix + 1, iy + 1, 0xFF7CE38B);
            }
            graphics.fill(px - 1, py - 1, px + 1, py + 1, GREEN);
            lastX = px;
            lastY = py;
        }
        graphics.fill(lastX - 2, lastY - 2, lastX + 2, lastY + 2, YELLOW);
    }

    /** One bar per report: how far it moved, so the speed curve shows. */
    private void drawSpeed(GuiGraphicsExtractor graphics, Debug.Move move, int x, int y, int w, int h) {
        List<double[]> points = move.points;
        if (points.size() < 2) {
            return;
        }
        double[] sizes = new double[points.size()];
        double max = 1.0E-6;
        double[] before = move.cursor ? points.get(0) : new double[] {0, 0, 0};
        for (int i = 0; i < points.size(); i++) {
            double[] p = points.get(i);
            sizes[i] = Math.hypot(p[1] - before[1], p[2] - before[2]);
            max = Math.max(max, sizes[i]);
            before = p;
        }
        int bar = Math.max(1, w / sizes.length);
        for (int i = 0; i < sizes.length; i++) {
            int bh = (int) Math.round(sizes[i] / max * h);
            graphics.fill(x + i * bar, y + h - bh, x + i * bar + Math.max(1, bar - 1), y + h, 0xFF5EE0C1);
        }
        graphics.text(font, "speed per report", x, y - 1, SOFT, false);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0 && event.x() >= listX && event.x() < listX + listW) {
            for (int i = 0; i < rowCount; i++) {
                if (event.y() >= rowTops[i] - 2 && event.y() < rowTops[i] + 11) {
                    selected = i;
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }
}
