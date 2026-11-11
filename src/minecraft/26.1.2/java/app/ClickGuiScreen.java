package app;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Uranium's menu, in three panes like a macOS settings window: window buttons, a search field
 * and the categories in a sidebar; the selected category's modules in the middle, each with
 * a switch in its category's color; and the selected module's settings on the right. Click
 * a module to show its settings, or its switch to turn it on or off; the server at the
 * bottom of the sidebar turns combat on or off there (right-click: its own settings). Each
 * pane scrolls on its own. Settings are switches, choices (click to step through),
 * keys (click, then press a key or mouse button; Esc cancels), buttons and sliders (drag;
 * a delay range has a min and a max knob; right-click puts the default back). Typing shows the
 * modules that match from every category. Drag the window by its top edge. Every change is
 * saved straight away (see SpearSettings).
 */
public final class ClickGuiScreen extends Screen {
    private static final int WIDTH = 520;
    private static final int HEIGHT = 300;
    // The module list's width; the settings pane takes the rest.
    private static final int LIST = 196;
    private static final int RADIUS = 10;
    private static final int TOP_BAR = 30;
    private static final int SIDEBAR = 122;
    private static final int FOOTER = 24;
    private static final int MODULE_ROW = 32;
    private static final int SETTING_ROW = 20;
    private static final int MARGIN = 12;
    private static final int CATEGORY_ROW = 20;
    // A slider's track starts this far into its row and stops this far before the value.
    private static final int SLIDER_LEFT = 84;
    private static final int SLIDER_VALUE = 52;
    private static final int LABEL_INSET = 10;
    // macOS dark appearance.
    private static final int WINDOW = 0xF20B110C;
    private static final int SIDEBAR_COLOR = 0xF2101A12;
    private static final int GROUP = 0xFF16221A;
    private static final int GROUP_INSET = 0xFF111B14;
    private static final int SEPARATOR = 0x2239FF14;
    private static final int HOVER = 0x1439FF14;
    private static final int TEXT = 0xFFEAFFE4;
    private static final int TEXT_SOFT = 0xFFC2E3BA;
    private static final int SECONDARY = 0xFF86A882;
    private static final int TERTIARY = 0xFF5A7356;
    private static final int ACCENT = 0xFF39FF14;
    private static final int GREEN = 0xFF7CFC00;
    private static final int SWITCH_OFF = 0xFF243326;
    private static final int PILL = 0xFF1E2D21;
    private static final int PILL_HOVER = 0xFF2A3F2D;
    private static final int RED = 0xFFFF453A;
    private static final int[] LIGHTS = {0xFFFF5F57, 0xFFFEBC2E, 0xFF28C840};
    private static final String VERSION = ClickGuiScreen.class.getPackageName().replace("app.", "");
    // Kept while the game runs, and the position and category in the settings file.
    private static int windowX = -1;
    private static int windowY = -1;
    private static int category;
    // The module whose settings show on the right.
    private static String selected;
    private static final Map<String, Float> SWITCHES = new HashMap<>();
    private final List<Hit> hits = new ArrayList<>();
    private final List<SliderHit> sliders = new ArrayList<>();
    // The slider knob being dragged, if any.
    private Num draggingNum;
    private boolean draggingHigh;
    private int dragTrackX1;
    private int dragTrackX2;
    private final List<Binder> binders = List.of(
            new Binder(MinecraftSpearDash::isAwaitingBinding, MinecraftSpearDash::bindKeyboard,
                    MinecraftSpearDash::bindMouse, MinecraftSpearDash::cancelBinding),
            new Binder(ShieldBreak::isAwaitingBinding, ShieldBreak::bindKeyboard, ShieldBreak::bindMouse,
                    ShieldBreak::cancelBinding),
            new Binder(WebTrap::isAwaitingBinding, WebTrap::bindKeyboard, WebTrap::bindMouse, WebTrap::cancelBinding),
            new Binder(QuickThrow::isAwaitingBinding, QuickThrow::bindKeyboard, QuickThrow::bindMouse,
                    QuickThrow::cancelBinding),
            new Binder(AimHelper::isAwaitingBinding, AimHelper::bindKeyboard, AimHelper::bindMouse,
                    AimHelper::cancelBinding),
            new Binder(PearlCatch::isAwaitingBinding, PearlCatch::bindKeyboard, PearlCatch::bindMouse,
                    PearlCatch::cancelBinding),
            new Binder(ChestSwap::isAwaitingBinding, ChestSwap::bindKeyboard, ChestSwap::bindMouse,
                    ChestSwap::cancelBinding),
            new Binder(Utilities::isAwaitingZoomBinding, Utilities::bindZoomKeyboard, Utilities::bindZoomMouse,
                    Utilities::cancelZoomBinding),
            // Esc clears a HUD toggle key instead of cancelling (see VelocityHud).
            new Binder(() -> VelocityHud.awaitingToggle() >= 0, VelocityHud::bindToggleKeyboard,
                    VelocityHud::bindToggleMouse, null));
    private String query = "";
    // A key that just completed a binding also arrives as a typed character; it is not a search.
    private boolean swallowNextChar;
    private boolean searchFocused;
    private int scroll;
    private int maxScroll;
    private int settingsScroll;
    private int maxSettingsScroll;
    // Where the settings pane starts this frame; scrolling left of it moves the list.
    private int settingsX;
    // The color of the category being shown, for switches and sliders.
    private int accent = GREEN;
    private boolean dragging;
    private double dragOffsetX;
    private double dragOffsetY;
    private long lastFrameNs;
    private long openedNs;

    /** Somewhere clickable this frame, and what a left or right click there does. */
    private record Hit(int x1, int y1, int x2, int y2, Runnable left, Runnable right) {
        boolean contains(double x, double y) {
            return x >= x1 && x < x2 && y >= y1 && y < y2;
        }
    }

    /** A slider's row this frame, and its track. */
    private record SliderHit(int x1, int y1, int x2, int y2, int trackX1, int trackX2, Num num) {
        boolean contains(double x, double y) {
            return x >= x1 && x < x2 && y >= y1 && y < y2;
        }
    }

    /** A module setting that is waiting for a key or mouse button. */
    private record Binder(BooleanSupplier awaiting, IntConsumer key, IntConsumer mouse, Runnable cancel) {}

    public ClickGuiScreen() {
        super(Component.literal("Uranium"));
    }

    @Override
    protected void init() {
        Minecraft minecraft = Minecraft.getInstance();
        MinecraftInputController.install(minecraft);
        VelocityHud.install();
        openedNs = System.nanoTime();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private int windowWidth() {
        return Math.min(WIDTH, width - 16);
    }

    private int windowHeight() {
        return Math.min(HEIGHT, height - 16);
    }

    private int left() {
        int x = windowX < 0 ? (width - windowWidth()) / 2 : windowX;
        return clamp(x, 4, width - windowWidth() - 4);
    }

    private int top() {
        int y = windowY < 0 ? (height - windowHeight()) / 2 : windowY;
        return clamp(y, 4, height - windowHeight() - 4);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        long now = System.nanoTime();
        float frameSeconds = lastFrameNs == 0 ? 0 : Math.min(0.1F, (now - lastFrameNs) / 1.0E9F);
        lastFrameNs = now;
        hits.clear();
        sliders.clear();
        Minecraft minecraft = Minecraft.getInstance();
        List<Modules.Category> categories = Modules.categories(minecraft);
        category = clamp(category, 0, categories.size() - 1);
        int x = left();
        int y = top();
        int w = windowWidth();
        int h = windowHeight();

        // Opening: a quick ease from 96% to full size.
        float open = Math.min(1.0F, (now - openedNs) / 160.0E6F);
        float scale = 0.96F + 0.04F * (1 - (1 - open) * (1 - open) * (1 - open));
        graphics.pose().pushMatrix();
        graphics.pose().translate(x + w / 2.0F, y + h / 2.0F);
        graphics.pose().scale(scale, scale);
        graphics.pose().translate(-(x + w / 2.0F), -(y + h / 2.0F));

        for (int spread = 1; spread <= 5; spread++) {
            roundRect(graphics, x - spread, y - spread + 3, x + w + spread, y + h + spread + 3, RADIUS + spread,
                    0x14000000, true, true);
        }
        roundRect(graphics, x, y, x + w, y + h, RADIUS, WINDOW, true, true);
        drawSidebar(graphics, minecraft, categories, x, y, h, mouseX, mouseY);
        drawContent(graphics, minecraft, categories, x + SIDEBAR, y, w - SIDEBAR, h, mouseX, mouseY, frameSeconds);
        // A hairline around the window, as macOS draws it.
        outline(graphics, x, y, x + w, y + h, 0x6039FF14);

        graphics.pose().popMatrix();
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private void drawSidebar(GuiGraphicsExtractor graphics, Minecraft minecraft, List<Modules.Category> categories,
            int x, int y, int h, int mouseX, int mouseY) {
        roundRect(graphics, x, y, x + SIDEBAR, y + h, RADIUS, SIDEBAR_COLOR, true, true);
        graphics.fill(x + SIDEBAR - RADIUS, y, x + SIDEBAR, y + h, SIDEBAR_COLOR);
        graphics.fill(x + SIDEBAR, y, x + SIDEBAR + 1, y + h, 0x40000000);

        // Window buttons: red closes.
        boolean overLights = mouseX >= x + 8 && mouseX < x + 46 && mouseY >= y + 7 && mouseY < y + 19;
        for (int i = 0; i < LIGHTS.length; i++) {
            int cx = x + 14 + i * 13;
            roundRect(graphics, cx - 5, y + 8, cx + 5, y + 18, 5, LIGHTS[i], true, true);
            if (overLights) {
                graphics.centeredText(font, i == 0 ? "x" : i == 1 ? "-" : "+", cx, y + 9, 0x99000000);
            }
        }
        hit(x + 8, y + 7, x + 20, y + 19, this::onClose, null);

        // Search.
        int sx1 = x + 10;
        int sx2 = x + SIDEBAR - 10;
        int sy = y + TOP_BAR;
        roundRect(graphics, sx1, sy, sx2, sy + 16, 6, searchFocused ? 0xFF1E2D21 : 0xFF17241A, true, true);
        if (searchFocused) {
            outline(graphics, sx1, sy, sx2, sy + 16, 0x9039FF14);
        }
        String shown = query.isEmpty() ? "Search" : query;
        boolean caret = searchFocused && (System.nanoTime() / 500_000_000L) % 2 == 0;
        shown = font.plainSubstrByWidth(shown, sx2 - sx1 - 22, true);
        // A magnifying glass: a ring and a short handle.
        roundRect(graphics, sx1 + 5, sy + 4, sx1 + 11, sy + 10, 3, SECONDARY, true, true);
        roundRect(graphics, sx1 + 6, sy + 5, sx1 + 10, sy + 9, 2, searchFocused ? 0xFF1E2D21 : 0xFF17241A, true, true);
        graphics.fill(sx1 + 10, sy + 10, sx1 + 11, sy + 11, SECONDARY);
        graphics.fill(sx1 + 11, sy + 11, sx1 + 12, sy + 12, SECONDARY);
        graphics.text(font, shown + (caret ? "|" : ""), sx1 + 15, sy + 4, query.isEmpty() ? TERTIARY : TEXT, false);
        hit(sx1, sy, sx2, sy + 16, () -> searchFocused = true, () -> {
            query = "";
            scroll = 0;
        });

        // Categories.
        int cy = sy + 26;
        graphics.text(font, Component.literal("☢ URANIUM").withStyle(ChatFormatting.BOLD), x + 12, cy - 6, ACCENT, false);
        cy += 6;
        for (int i = 0; i < categories.size(); i++) {
            Modules.Category entry = categories.get(i);
            boolean current = i == category && query.isEmpty();
            boolean hovered = !current && inside(mouseX, mouseY, x + 6, cy, x + SIDEBAR - 6, cy + CATEGORY_ROW);
            if (current || hovered) {
                roundRect(graphics, x + 6, cy, x + SIDEBAR - 6, cy + CATEGORY_ROW, 6,
                        current ? ACCENT : HOVER, true, true);
            }
            roundRect(graphics, x + 12, cy + 4, x + 24, cy + 16, 3, entry.color(), true, true);
            graphics.centeredText(font, entry.icon(), x + 18, cy + 6, 0xFF0B110C);
            graphics.text(font, entry.name(), x + 30, cy + 6, current ? 0xFF0B110C : TEXT_SOFT, false);
            int on = 0;
            for (Modules.Module module : entry.modules()) {
                on += module.on().getAsBoolean() ? 1 : 0;
            }
            if (on > 0) {
                String count = String.valueOf(on);
                graphics.text(font, count, x + SIDEBAR - 12 - font.width(count), cy + 6,
                        current ? 0xCC0B110C : TERTIARY, false);
            }
            int index = i;
            hit(x + 6, cy, x + SIDEBAR - 6, cy + CATEGORY_ROW, () -> {
                category = index;
                query = "";
                searchFocused = false;
                scroll = 0;
                selected = null;
                settingsScroll = 0;
            }, null);
            cy += CATEGORY_ROW + 2;
        }

        // The server you are on, and whether combat modules may act there.
        boolean combat = SpearSettings.combatAllowed(minecraft);
        int fy = y + h - 22;
        roundRect(graphics, x + 10, fy + 6, x + 16, fy + 12, 3, combat ? GREEN : RED, true, true);
        String server = font.plainSubstrByWidth(SpearSettings.currentServer(minecraft), SIDEBAR - 34);
        graphics.text(font, server, x + 21, fy + 5, SECONDARY, false);
        hit(x + 6, fy, x + SIDEBAR - 6, fy + 18, () -> SpearSettings.toggleCombatHere(minecraft),
                () -> SpearSettings.toggleOwnSettingsHere(minecraft));
    }

    private void drawContent(GuiGraphicsExtractor graphics, Minecraft minecraft, List<Modules.Category> categories,
            int x, int y, int w, int h, int mouseX, int mouseY, float frameSeconds) {
        boolean searching = !query.isEmpty();
        List<Modules.Module> modules = new ArrayList<>();
        List<Modules.Category> owners = new ArrayList<>();
        if (searching) {
            String needle = query.toLowerCase(Locale.ROOT);
            for (Modules.Category entry : categories) {
                for (Modules.Module module : entry.modules()) {
                    if (module.name().toLowerCase(Locale.ROOT).contains(needle)
                            || module.about().toLowerCase(Locale.ROOT).contains(needle)) {
                        modules.add(module);
                        owners.add(entry);
                    }
                }
            }
        } else {
            for (Modules.Module module : categories.get(category).modules()) {
                modules.add(module);
                owners.add(categories.get(category));
            }
        }
        int shown = -1;
        for (int i = 0; i < modules.size(); i++) {
            if (modules.get(i).name().equals(selected)) {
                shown = i;
            }
        }
        if (shown < 0 && !modules.isEmpty()) {
            shown = 0;
            selected = modules.get(0).name();
        }

        // Title, large and bold, with the build on the right.
        String title = searching ? "Search" : categories.get(category).name();
        graphics.pose().pushMatrix();
        graphics.pose().translate(x + MARGIN + 2, y + 10);
        graphics.pose().scale(1.5F, 1.5F);
        graphics.text(font, Component.literal(title).withStyle(ChatFormatting.BOLD), 0, 0, TEXT, false);
        graphics.pose().popMatrix();
        graphics.text(font, "URANIUM " + VERSION, x + w - MARGIN - font.width("URANIUM " + VERSION), y + 13, SECONDARY, false);
        hit(x, y, x + w, y + TOP_BAR, this::startDrag, null);

        int viewTop = y + TOP_BAR + 4;
        int viewBottom = y + h - FOOTER;
        settingsX = x + LIST;
        drawModuleList(graphics, modules, owners, searching, x + MARGIN, x + LIST - 6, viewTop, viewBottom,
                mouseX, mouseY, frameSeconds);
        graphics.fill(x + LIST, viewTop, x + LIST + 1, viewBottom, SEPARATOR);
        if (shown >= 0) {
            accent = owners.get(shown).color();
            drawSettingsPane(graphics, modules.get(shown), x + LIST + 8, x + w - MARGIN, viewTop, viewBottom,
                    mouseX, mouseY, frameSeconds);
        }

        drawFooter(graphics, minecraft, x, y + h - FOOTER, w, mouseX, mouseY);
    }

    /** The middle pane: one row per module, clipped and scrolled between the title and footer. */
    private void drawModuleList(GuiGraphicsExtractor graphics, List<Modules.Module> modules,
            List<Modules.Category> owners, boolean searching, int gx1, int gx2, int viewTop, int viewBottom,
            int mouseX, int mouseY, float frameSeconds) {
        int total = modules.size() * MODULE_ROW;
        maxScroll = Math.max(0, total + 8 - (viewBottom - viewTop));
        scroll = clamp(scroll, 0, maxScroll);
        graphics.enableScissor(gx1 - MARGIN, viewTop, gx2 + 6, viewBottom);
        int rowY = viewTop + 2 - scroll;
        if (modules.isEmpty()) {
            graphics.centeredText(font, "No modules match \"" + query + "\"", (gx1 + gx2) / 2, viewTop + 30, SECONDARY);
        } else {
            roundRect(graphics, gx1, rowY, gx2, rowY + total, 8, GROUP, true, true);
        }
        for (int i = 0; i < modules.size(); i++) {
            if (rowY + MODULE_ROW > viewTop && rowY < viewBottom) {
                drawModuleRow(graphics, modules.get(i), owners.get(i), searching, gx1, rowY, gx2,
                        i == modules.size() - 1, i == 0, mouseX, mouseY, viewTop, viewBottom, frameSeconds);
            }
            rowY += MODULE_ROW;
        }
        graphics.disableScissor();
        scrollbar(graphics, gx2 + 2, viewTop, viewBottom, total, scroll, maxScroll);
    }

    /** The right pane: the selected module's name, what it does, its switch and its settings. */
    private void drawSettingsPane(GuiGraphicsExtractor graphics, Modules.Module module, int px1, int px2,
            int viewTop, int viewBottom, int mouseX, int mouseY, float frameSeconds) {
        int y = viewTop + 2;
        graphics.text(font, Component.literal(font.plainSubstrByWidth(module.name(), px2 - px1 - 40))
                .withStyle(ChatFormatting.BOLD), px1 + 2, y + 2, TEXT, false);
        boolean on = module.on().getAsBoolean();
        String state = on ? "On" : "Off";
        int pillLeft = px2 - font.width(state) - 14;
        boolean overPill = inside(mouseX, mouseY, pillLeft, y, px2, y + 13);
        roundRect(graphics, pillLeft, y, px2, y + 13, 6, overPill ? PILL_HOVER : PILL, true, true);
        graphics.text(font, state, pillLeft + 7, y + 3, on ? accent : SECONDARY, false);
        hit(pillLeft, y, px2, y + 13, module.flip(), null);
        y += 15;
        // What it does, over up to two lines.
        for (String line : wrap(module.about(), px2 - px1 - 4, 2)) {
            graphics.text(font, line, px1 + 2, y, SECONDARY, false);
            y += 10;
        }
        y += 4;

        List<Modules.Row> settings = module.settings();
        int top = y;
        if (settings.isEmpty()) {
            graphics.text(font, "Nothing to set", px1 + 2, y + 4, TERTIARY, false);
            maxSettingsScroll = 0;
            return;
        }
        int total = settings.size() * SETTING_ROW;
        maxSettingsScroll = Math.max(0, total + 4 - (viewBottom - top));
        settingsScroll = clamp(settingsScroll, 0, maxSettingsScroll);
        graphics.enableScissor(px1, top, px2 + 6, viewBottom);
        int rowY = top - settingsScroll;
        roundRect(graphics, px1, rowY, px2, rowY + total, 8, GROUP, true, true);
        for (int s = 0; s < settings.size(); s++) {
            if (rowY + SETTING_ROW > top && rowY < viewBottom) {
                drawSettingRow(graphics, module, settings.get(s), px1, rowY, px2, s == settings.size() - 1,
                        mouseX, mouseY, top, viewBottom, frameSeconds);
            }
            rowY += SETTING_ROW;
        }
        graphics.disableScissor();
        scrollbar(graphics, px2 + 2, top, viewBottom, total, settingsScroll, maxSettingsScroll);
    }

    private static void scrollbar(GuiGraphicsExtractor graphics, int x, int viewTop, int viewBottom, int total,
            int scrolled, int max) {
        if (max <= 0) {
            return;
        }
        int track = viewBottom - viewTop - 8;
        int thumb = Math.max(16, track * (viewBottom - viewTop) / (total + 8));
        int thumbY = viewTop + 4 + (track - thumb) * scrolled / max;
        roundRect(graphics, x, thumbY, x + 3, thumbY + thumb, 1, 0x50FFFFFF, true, true);
    }

    /** Splits text into lines that fit, at most this many (the last one cut short). */
    private List<String> wrap(String text, int width, int maxLines) {
        List<String> lines = new ArrayList<>();
        String rest = text;
        while (!rest.isEmpty() && lines.size() < maxLines) {
            String line = font.plainSubstrByWidth(rest, width);
            if (line.isEmpty()) {
                break;
            }
            if (line.length() < rest.length() && lines.size() < maxLines - 1) {
                int space = line.lastIndexOf(' ');
                if (space > 0) {
                    line = line.substring(0, space);
                }
            }
            lines.add(line);
            rest = rest.substring(line.length()).stripLeading();
        }
        return lines;
    }

    private void drawModuleRow(GuiGraphicsExtractor graphics, Modules.Module module, Modules.Category owner,
            boolean searching, int x1, int y, int x2, boolean roundBottom, boolean roundTop, int mouseX, int mouseY,
            int viewTop, int viewBottom, float frameSeconds) {
        boolean chosen = module.name().equals(selected);
        int switchX = x2 - 10 - 24;
        boolean overRow = inside(mouseX, mouseY, x1, y, x2, y + MODULE_ROW) && mouseY >= viewTop && mouseY < viewBottom;
        if (chosen || overRow) {
            roundRect(graphics, x1, y, x2, y + MODULE_ROW, 8, chosen ? 0x22FFFFFF : HOVER, roundTop, roundBottom);
        }
        if (chosen) {
            graphics.fill(x1 + 3, y + 8, x1 + 5, y + MODULE_ROW - 8, owner.color());
        }
        graphics.text(font, font.plainSubstrByWidth(module.name(), switchX - x1 - 16), x1 + 10, y + 7, TEXT, false);
        String about = searching ? owner.name() + "  ·  " + module.about() : module.about();
        graphics.text(font, font.plainSubstrByWidth(about, switchX - x1 - 16), x1 + 10, y + 18, SECONDARY, false);
        drawSwitch(graphics, module.name(), switchX, y + 9, 24, 13, module.on().getAsBoolean(), owner.color(),
                frameSeconds);
        if (!roundBottom) {
            graphics.fill(x1 + 10, y + MODULE_ROW - 1, x2, y + MODULE_ROW, SEPARATOR);
        }
        int top = Math.max(y, viewTop);
        int bottom = Math.min(y + MODULE_ROW, viewBottom);
        Runnable select = () -> {
            if (!module.name().equals(selected)) {
                selected = module.name();
                settingsScroll = 0;
            }
        };
        hit(switchX - 4, top, x2, bottom, module.flip(), module.flip());
        hit(x1, top, switchX - 4, bottom, select, module.flip());
    }

    private void drawSettingRow(GuiGraphicsExtractor graphics, Modules.Module module, Modules.Row row, int x1, int y,
            int x2, boolean lastRow, int mouseX, int mouseY, int viewTop, int viewBottom, float frameSeconds) {
        boolean over = inside(mouseX, mouseY, x1, y, x2, y + SETTING_ROW) && mouseY >= viewTop && mouseY < viewBottom;
        int top = Math.max(y, viewTop);
        int bottom = Math.min(y + SETTING_ROW, viewBottom);
        int textY = y + 6;
        int right = x2 - 12;
        switch (row) {
            case Modules.Toggle toggle -> {
                graphics.text(font, toggle.label(), x1 + LABEL_INSET, textY, TEXT_SOFT, false);
                drawSwitch(graphics, module.name() + "/" + toggle.label(), right - 20, y + 5, 20, 11,
                        toggle.on().getAsBoolean(), accent, frameSeconds);
                hit(x1, top, x2, bottom, toggle.flip(), null);
            }
            case Modules.Choice choice -> {
                graphics.text(font, choice.label(), x1 + LABEL_INSET, textY, TEXT_SOFT, false);
                String value = choice.value().get();
                int valueX = right - 8 - font.width(value);
                graphics.text(font, value, valueX, textY, over ? TEXT : SECONDARY, false);
                chevron(graphics, right - 3, y + 10, false, over ? TEXT_SOFT : TERTIARY);
                hit(x1, top, x2, bottom, choice.next(), null);
            }
            case Modules.Bind bind -> {
                graphics.text(font, bind.label(), x1 + LABEL_INSET, textY, TEXT_SOFT, false);
                boolean waiting = bind.awaiting().getAsBoolean();
                String value = waiting && !bind.value().get().startsWith("Press") ? "Press a key..." : bind.value().get();
                int pillWidth = font.width(value) + 12;
                int pillX = right - pillWidth;
                int pulse = waiting ? 0x60 + (int) (0x9F * (0.5 + 0.5 * Math.sin(System.nanoTime() / 1.5E8))) : 0;
                roundRect(graphics, pillX, y + 3, right, y + SETTING_ROW - 3, 7,
                        waiting ? (pulse << 24) | (ACCENT & 0xFFFFFF) : over ? PILL_HOVER : PILL, true, true);
                graphics.text(font, value, pillX + 6, textY, TEXT, false);
                hit(x1, top, x2, bottom, bind.await(), null);
            }
            case Modules.Action action -> {
                graphics.text(font, action.label().get(), x1 + LABEL_INSET, textY, over ? 0xFFB6FF8A : ACCENT, false);
                hit(x1, top, x2, bottom, action.run(), null);
            }
            case Modules.Slider slider -> {
                Num num = slider.num();
                boolean active = num == draggingNum;
                graphics.text(font, slider.label(), x1 + LABEL_INSET, textY, TEXT_SOFT, false);
                String value = num.label();
                graphics.text(font, value, right - font.width(value), textY, over || active ? TEXT : SECONDARY, false);
                int trackX1 = x1 + SLIDER_LEFT;
                int trackX2 = right - SLIDER_VALUE;
                if (trackX2 - trackX1 >= 20) {
                    int trackY = y + SETTING_ROW / 2;
                    int lowX = trackX1 + (trackX2 - trackX1) * num.low() / num.limit;
                    int highX = trackX1 + (trackX2 - trackX1) * num.high() / num.limit;
                    roundRect(graphics, trackX1, trackY - 1, trackX2, trackY + 2, 1, SWITCH_OFF, true, true);
                    roundRect(graphics, num.range ? lowX : trackX1, trackY - 1, highX, trackY + 2, 1, accent, true, true);
                    if (num.range) {
                        knob(graphics, lowX, trackY, active && !draggingHigh);
                    }
                    knob(graphics, highX, trackY, active && (draggingHigh || !num.range));
                    if (bottom > top) {
                        sliders.add(new SliderHit(x1, top, x2, bottom, trackX1, trackX2, num));
                    }
                }
            }
        }
        if (!lastRow) {
            graphics.fill(x1 + LABEL_INSET, y + SETTING_ROW - 1, x2, y + SETTING_ROW, SEPARATOR);
        }
    }

    private void drawFooter(GuiGraphicsExtractor graphics, Minecraft minecraft, int x, int y, int w, int mouseX,
            int mouseY) {
        graphics.fill(x + 1, y, x + w, y + 1, SEPARATOR);
        int right = x + w - MARGIN;
        right = pill(graphics, "Unload", right, y + 5, RED, mouseX, mouseY, () -> {
            MinecraftInputController.requestUnload();
            onClose();
        });
        right = pill(graphics, "Edit HUD", right - 6, y + 5, TEXT, mouseX, mouseY,
                () -> Mc.setScreen(minecraft, new HudEditorScreen()));
        String status = font.plainSubstrByWidth(MinecraftSpearDash.status(), right - x - MARGIN - 8);
        graphics.text(font, status, x + MARGIN, y + 8, SECONDARY, false);
    }

    /** A rounded button ending at right; returns its left edge. */
    private int pill(GuiGraphicsExtractor graphics, String label, int right, int y, int color, int mouseX, int mouseY,
            Runnable action) {
        int left = right - font.width(label) - 16;
        boolean over = inside(mouseX, mouseY, left, y, right, y + 14);
        roundRect(graphics, left, y, right, y + 14, 7, over ? PILL_HOVER : PILL, true, true);
        graphics.text(font, label, left + 8, y + 3, color, false);
        hit(left, y, right, y + 14, action, null);
        return left;
    }

    /** An iOS switch whose knob slides over about 0.1 s. */
    private void drawSwitch(GuiGraphicsExtractor graphics, String key, int x, int y, int w, int h, boolean on,
            int color, float frameSeconds) {
        float target = on ? 1 : 0;
        float position = SWITCHES.getOrDefault(key, target);
        position += (target - position) * Math.min(1, frameSeconds * 14);
        if (Math.abs(target - position) < 0.01F) {
            position = target;
        }
        SWITCHES.put(key, position);
        roundRect(graphics, x, y, x + w, y + h, h / 2, blend(SWITCH_OFF, color, position), true, true);
        int knob = h - 4;
        int knobX = x + 2 + Math.round((w - knob - 4) * position);
        roundRect(graphics, knobX, y + 3, knobX + knob, y + 3 + knob, knob / 2, 0x40000000, true, true);
        roundRect(graphics, knobX, y + 2, knobX + knob, y + 2 + knob, knob / 2, 0xFFFFFFFF, true, true);
    }

    /** A slider's knob, a little larger while it is dragged. */
    private static void knob(GuiGraphicsExtractor graphics, int x, int y, boolean active) {
        int r = active ? 4 : 3;
        roundRect(graphics, x - r, y - r + 1, x + r, y + r + 1, r, 0x40000000, true, true);
        roundRect(graphics, x - r, y - r, x + r, y + r, r, 0xFFFFFFFF, true, true);
    }

    /** The slider value under the mouse, snapped by the setting itself. */
    private static int sliderValue(Num num, double mouseX, int trackX1, int trackX2) {
        double along = Math.max(0, Math.min(1, (mouseX - trackX1) / (double) (trackX2 - trackX1)));
        return (int) Math.round(along * num.limit);
    }

    private void dragSlider(double mouseX) {
        int value = sliderValue(draggingNum, mouseX, dragTrackX1, dragTrackX2);
        if (draggingHigh) {
            draggingNum.setHigh(value);
        } else {
            draggingNum.setLow(value);
        }
    }

    /** A small chevron pointing right, or down when open. */
    private static void chevron(GuiGraphicsExtractor graphics, int x, int y, boolean down, int color) {
        for (int i = 0; i < 3; i++) {
            if (down) {
                graphics.fill(x - 3 + i, y - 1 + i, x - 2 + i, y + i, color);
                graphics.fill(x + 1 - i, y - 1 + i, x + 2 - i, y + i, color);
            } else {
                graphics.fill(x - 1 + i, y - 3 + i, x + i, y - 2 + i, color);
                graphics.fill(x - 1 + i, y + 1 - i, x + i, y + 2 - i, color);
            }
        }
    }

    private void startDrag() {
        dragging = true;
    }

    private void hit(int x1, int y1, int x2, int y2, Runnable left, Runnable right) {
        if (y2 > y1) {
            hits.add(new Hit(x1, y1, x2, y2, left, right));
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        for (Binder binder : binders) {
            if (binder.awaiting().getAsBoolean()) {
                binder.mouse().accept(event.button());
                changed();
                return true;
            }
        }
        searchFocused = false;
        for (SliderHit slider : sliders) {
            if (!slider.contains(event.x(), event.y())) {
                continue;
            }
            Num num = slider.num();
            if (event.button() == 1) {
                num.reset();
                changed();
            } else if (event.button() == 0) {
                // The knob nearer the click moves; past either end, that end's knob.
                int value = sliderValue(num, event.x(), slider.trackX1(), slider.trackX2());
                draggingHigh = num.range && (value > num.high()
                        || (value >= num.low() && value - num.low() > num.high() - value));
                draggingNum = num;
                dragTrackX1 = slider.trackX1();
                dragTrackX2 = slider.trackX2();
                dragSlider(event.x());
            }
            return true;
        }
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit area = hits.get(i);
            if (!area.contains(event.x(), event.y())) {
                continue;
            }
            Runnable action = event.button() == 1 ? area.right() : event.button() == 0 ? area.left() : null;
            if (action != null) {
                action.run();
                if (dragging) {
                    dragOffsetX = event.x() - left();
                    dragOffsetY = event.y() - top();
                } else {
                    changed();
                }
            }
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
        if (draggingNum != null) {
            dragSlider(event.x());
            return true;
        }
        if (dragging) {
            windowX = clamp((int) (event.x() - dragOffsetX), 4, width - windowWidth() - 4);
            windowY = clamp((int) (event.y() - dragOffsetY), 4, height - windowHeight() - 4);
            return true;
        }
        return super.mouseDragged(event, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (draggingNum != null) {
            draggingNum = null;
            changed();
            return true;
        }
        if (dragging) {
            dragging = false;
            SpearSettings.save(Minecraft.getInstance());
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX >= settingsX) {
            settingsScroll = clamp(settingsScroll - (int) Math.round(scrollY * 18), 0, maxSettingsScroll);
        } else {
            scroll = clamp(scroll - (int) Math.round(scrollY * 18), 0, maxScroll);
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        for (Binder binder : binders) {
            if (binder.awaiting().getAsBoolean()) {
                if (event.key() == 256 && binder.cancel() != null) {
                    binder.cancel().run();
                } else {
                    binder.key().accept(event.key());
                }
                swallowNextChar = true;
                changed();
                return true;
            }
        }
        if (!query.isEmpty() || searchFocused) {
            if (event.key() == 259 && !query.isEmpty()) { // Backspace
                query = query.substring(0, query.length() - 1);
                scroll = 0;
                return true;
            }
            if (event.key() == 256 || event.key() == 257) { // Esc or Enter
                if (event.key() == 256) {
                    query = "";
                }
                searchFocused = false;
                return true;
            }
        }
        return super.keyPressed(event);
    }

    /** Typing anywhere searches, like Spotlight. */
    @Override
    public boolean charTyped(CharacterEvent event) {
        if (swallowNextChar) {
            swallowNextChar = false;
            return true;
        }
        if (!event.isAllowedChatCharacter() || query.length() >= 24) {
            return false;
        }
        query += event.codepointAsString();
        searchFocused = true;
        scroll = 0;
        return true;
    }

    /** Saves the settings after any change. */
    private void changed() {
        SpearSettings.save(Minecraft.getInstance());
    }

    /**
     * A filled rectangle with rounded corners, drawn row by row so translucent colors stay
     * even; top and bottom choose which corners are rounded.
     */
    private static void roundRect(GuiGraphicsExtractor graphics, int x1, int y1, int x2, int y2, int radius,
            int color, boolean top, boolean bottom) {
        int r = Math.max(0, Math.min(radius, Math.min((x2 - x1) / 2, (y2 - y1) / 2)));
        int innerTop = top ? y1 + r : y1;
        int innerBottom = bottom ? y2 - r : y2;
        if (innerBottom > innerTop) {
            graphics.fill(x1, innerTop, x2, innerBottom, color);
        }
        for (int i = 0; i < r; i++) {
            double fromCenter = r - i - 0.5;
            int inset = (int) Math.round(r - Math.sqrt(r * r - fromCenter * fromCenter));
            if (top) {
                graphics.fill(x1 + inset, y1 + i, x2 - inset, y1 + i + 1, color);
            }
            if (bottom) {
                graphics.fill(x1 + inset, y2 - i - 1, x2 - inset, y2 - i, color);
            }
        }
    }

    /** A 1-pixel line around a rounded window, skipping the corners. */
    private static void outline(GuiGraphicsExtractor graphics, int x1, int y1, int x2, int y2, int color) {
        int r = Math.min(RADIUS, 6);
        graphics.fill(x1 + r, y1, x2 - r, y1 + 1, color);
        graphics.fill(x1 + r, y2 - 1, x2 - r, y2, color);
        graphics.fill(x1, y1 + r, x1 + 1, y2 - r, color);
        graphics.fill(x2 - 1, y1 + r, x2, y2 - r, color);
    }

    private static int blend(int from, int to, float amount) {
        int a = (int) (((from >>> 24) & 0xFF) + (((to >>> 24) & 0xFF) - ((from >>> 24) & 0xFF)) * amount);
        int r = (int) (((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * amount);
        int g = (int) (((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * amount);
        int b = (int) ((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * amount);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static boolean inside(double x, double y, int x1, int y1, int x2, int y2) {
        return x >= x1 && x < x2 && y >= y1 && y < y2;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(value, Math.max(minimum, maximum)));
    }

    static int panelX() {
        return windowX;
    }

    static int panelY() {
        return windowY;
    }

    static int tab() {
        return category;
    }

    static void setTab(int newTab) {
        category = Math.max(0, newTab);
    }

    static void setPosition(int x, int y) {
        windowX = x;
        windowY = y;
    }
}
