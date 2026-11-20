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
 * saved straight away (see Settings).
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
    private static final int SETTING_ROW = 22;
    private static final int MARGIN = 12;
    private static final int CATEGORY_ROW = 22;
    // Corner sizes: cards, controls (search, sidebar rows, buttons) and small chips.
    private static final int R_CARD = 8;
    private static final int R_CONTROL = 6;
    private static final int R_SMALL = 4;
    // A slider's track starts this far into its row and stops this far before the value.
    private static final int SLIDER_LEFT = 84;
    private static final int SLIDER_VALUE = 52;
    private static final int LABEL_INSET = 10;
    // macOS dark appearance.
    private static final int WINDOW = 0xF20B110C;
    private static final int SIDEBAR_COLOR = 0xF2101A12;
    private static final int SIDEBAR_SOLID = 0xFF101A12;
    private static final int WINDOW_EDGE = 0x5539FF14;
    private static final int CARD_EDGE = 0x14FFFFFF;
    private static final int FIELD = 0xFF131E16;
    private static final int FIELD_ACTIVE = 0xFF1B2A1E;
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
    // Hover and fade amounts (0 to 1) by name, eased a little each frame.
    private final Map<String, Float> fades = new HashMap<>();
    // This frame's length in seconds, for the eases.
    private float dt;
    // Where the sidebar's selection highlight is, sliding toward the chosen category.
    private float selectionOffset = -1;
    // When each pane was last scrolled, so its scrollbar shows for a moment.
    private long listScrolledNs;
    private long settingsScrolledNs;
    private final List<Hit> hits = new ArrayList<>();
    private final List<SliderHit> sliders = new ArrayList<>();
    // The slider knob being dragged, if any.
    private Num draggingNum;
    private boolean draggingHigh;
    private int dragTrackX1;
    private int dragTrackX2;
    private final List<Binder> binders = List.of(
            new Binder(SpearDash::isAwaitingBinding, SpearDash::bindKeyboard,
                    SpearDash::bindMouse, SpearDash::cancelBinding),
            new Binder(AutoStun::isAwaitingBinding, AutoStun::bindKeyboard, AutoStun::bindMouse,
                    AutoStun::cancelBinding),
            new Binder(AutoWeb::isAwaitingBinding, AutoWeb::bindKeyboard, AutoWeb::bindMouse, AutoWeb::cancelBinding),
            new Binder(QuickThrow::isAwaitingBinding, QuickThrow::bindKeyboard, QuickThrow::bindMouse,
                    QuickThrow::cancelBinding),
            new Binder(AimAssist::isAwaitingBinding, AimAssist::bindKeyboard, AimAssist::bindMouse,
                    AimAssist::cancelBinding),
            new Binder(PearlCatch::isAwaitingBinding, PearlCatch::bindKeyboard, PearlCatch::bindMouse,
                    PearlCatch::cancelBinding),
            new Binder(ElytraSwap::isAwaitingBinding, ElytraSwap::bindKeyboard, ElytraSwap::bindMouse,
                    ElytraSwap::cancelBinding),
            new Binder(Utilities::isAwaitingZoomBinding, Utilities::bindZoomKeyboard, Utilities::bindZoomMouse,
                    Utilities::cancelZoomBinding),
            // Esc clears a HUD toggle key instead of cancelling (see UraniumHud).
            new Binder(() -> UraniumHud.awaitingToggle() >= 0, UraniumHud::bindToggleKeyboard,
                    UraniumHud::bindToggleMouse, null));
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
    // The open dropdown (a Choice's menu), if any.
    private Modules.Choice openChoice;
    private List<String> openOptions = List.of();
    private int openIndex;
    private int anchorRight;
    private int anchorY;
    private int anchorWidth;
    private long menuOpenedNs;
    private int menuX1;
    private int menuY1;
    private int menuX2;
    private int menuY2;
    // Each choice's options in the order first seen, so its menu keeps one order.
    private static final Map<String, List<String>> OPTIONS = new HashMap<>();
    private static final int MENU_ITEM = 16;
    private static final int MENU_PAD = 4;
    private static final int MENU = 0xF6141E16;
    private static final int MENU_EDGE = 0x40FFFFFF;

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
        InputLoop.install(minecraft);
        UraniumHud.install();
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
        if (!HandProfile.isAsked()) {
            // The very first time: the mouse test, so the aim moves like your hand.
            HandProfile.markAsked();
            Mc.setScreen(Minecraft.getInstance(), new MouseTestScreen());
            return;
        }
        long now = System.nanoTime();
        float frameSeconds = lastFrameNs == 0 ? 0 : Math.min(0.1F, (now - lastFrameNs) / 1.0E9F);
        lastFrameNs = now;
        dt = frameSeconds;
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

        // A soft drop shadow, a thin green edge, then the window itself.
        for (int spread = 2; spread <= 6; spread += 2) {
            roundRect(graphics, x - spread, y - spread + 4, x + w + spread, y + h + spread + 4, RADIUS + spread,
                    0x18000000, true, true);
        }
        roundRect(graphics, x - 1, y - 1, x + w + 1, y + h + 1, RADIUS + 1, WINDOW_EDGE, true, true);
        roundRect(graphics, x, y, x + w, y + h, RADIUS, WINDOW, true, true);
        drawSidebar(graphics, minecraft, categories, x, y, h, mouseX, mouseY);
        drawContent(graphics, minecraft, categories, x + SIDEBAR, y, w - SIDEBAR, h, mouseX, mouseY, frameSeconds);
        drawDropdown(graphics, mouseX, mouseY);

        graphics.pose().popMatrix();
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private void drawSidebar(GuiGraphicsExtractor graphics, Minecraft minecraft, List<Modules.Category> categories,
            int x, int y, int h, int mouseX, int mouseY) {
        roundRect(graphics, x, y, x + SIDEBAR, y + h, RADIUS, SIDEBAR_COLOR, true, true);
        graphics.fill(x + SIDEBAR - RADIUS, y, x + SIDEBAR, y + h, SIDEBAR_COLOR);
        graphics.fill(x + SIDEBAR, y, x + SIDEBAR + 1, y + h, 0x50000000);

        // Window buttons: red closes; hovering them shows their symbols.
        boolean overLights = inside(mouseX, mouseY, x + 8, y + 7, x + 50, y + 21);
        for (int i = 0; i < LIGHTS.length; i++) {
            int cx = x + 16 + i * 14;
            roundRect(graphics, cx - 5, y + 9, cx + 5, y + 19, 5, LIGHTS[i], true, true);
            if (overLights) {
                graphics.centeredText(font, i == 0 ? "x" : i == 1 ? "-" : "+", cx, y + 10, 0x99000000);
            }
        }
        hit(x + 10, y + 8, x + 22, y + 20, this::onClose, null);

        // Search: a magnifying glass, the placeholder or what you typed, a caret and a clear button.
        int sx1 = x + 10;
        int sx2 = x + SIDEBAR - 10;
        int sy = y + TOP_BAR;
        int sh = 18;
        float focus = ease("search.focus", searchFocused ? 1 : 0, 16);
        float hover = ease("search.hover", inside(mouseX, mouseY, sx1, sy, sx2, sy + sh) ? 1 : 0, 18);
        if (focus > 0) {
            roundRect(graphics, sx1 - 1, sy - 1, sx2 + 1, sy + sh + 1, R_CONTROL + 1, alpha(ACCENT, 0.7F * focus),
                    true, true);
        }
        int field = blend(FIELD, FIELD_ACTIVE, Math.max(focus, hover * 0.5F));
        roundRect(graphics, sx1, sy, sx2, sy + sh, R_CONTROL, field, true, true);
        boolean typing = searchFocused || !query.isEmpty();
        magnifier(graphics, sx1 + 6, sy + 5, 7, typing ? TEXT_SOFT : TERTIARY, field);
        int textX = sx1 + 19;
        int caretX = textX - 1;
        if (query.isEmpty()) {
            graphics.text(font, "Search", textX, sy + 5, TERTIARY, false);
        } else {
            // Longer than the field: show its end, where the typing is.
            String shown = font.plainSubstrByWidth(query, sx2 - textX - 16, true);
            graphics.text(font, shown, textX, sy + 5, TEXT, false);
            caretX = textX + font.width(shown) - 1;
        }
        if (searchFocused && (System.nanoTime() / 500_000_000L) % 2 == 0) {
            graphics.fill(caretX, sy + 4, caretX + 1, sy + 14, ACCENT);
        }
        Runnable clear = () -> {
            query = "";
            scroll = 0;
        };
        hit(sx1, sy, sx2, sy + sh, () -> searchFocused = true, clear);
        if (!query.isEmpty()) {
            int cx = sx2 - 9;
            int cy = sy + sh / 2;
            boolean overClear = inside(mouseX, mouseY, cx - 6, sy, sx2, sy + sh);
            roundRect(graphics, cx - 5, cy - 5, cx + 5, cy + 5, 5, overClear ? SECONDARY : TERTIARY, true, true);
            cross(graphics, cx, cy, field);
            hit(cx - 6, sy, sx2, sy + sh, clear, clear);
        }

        // Categories, with a highlight that slides to the one you pick.
        int labelY = sy + sh + 10;
        graphics.text(font, Component.literal("☢ URANIUM").withStyle(ChatFormatting.BOLD), x + 14, labelY, ACCENT, false);
        int listTop = labelY + 14;
        float target = category * (CATEGORY_ROW + 2);
        selectionOffset = selectionOffset < 0 ? target
                : selectionOffset + (target - selectionOffset) * Math.min(1, dt * 18);
        if (Math.abs(target - selectionOffset) < 0.5F) {
            selectionOffset = target;
        }
        float selection = ease("category.selection", query.isEmpty() ? 1 : 0, 14);
        if (selection > 0) {
            int selY = listTop + Math.round(selectionOffset);
            int tint = blend(SIDEBAR_SOLID, categories.get(category).color(), 0.24F);
            roundRect(graphics, x + 8, selY, x + SIDEBAR - 8, selY + CATEGORY_ROW, R_CONTROL, alpha(tint, selection),
                    true, true);
        }
        int cy = listTop;
        for (int i = 0; i < categories.size(); i++) {
            Modules.Category entry = categories.get(i);
            boolean current = i == category && query.isEmpty();
            boolean hovered = !current && inside(mouseX, mouseY, x + 8, cy, x + SIDEBAR - 8, cy + CATEGORY_ROW);
            float lit = ease("category.hover." + i, hovered ? 1 : 0, 20);
            if (lit > 0) {
                roundRect(graphics, x + 8, cy, x + SIDEBAR - 8, cy + CATEGORY_ROW, R_CONTROL, alpha(HOVER, lit),
                        true, true);
            }
            roundRect(graphics, x + 14, cy + 4, x + 28, cy + 18, R_SMALL, entry.color(), true, true);
            graphics.centeredText(font, entry.icon(), x + 21, cy + 7, 0xFF0B110C);
            graphics.text(font, entry.name(), x + 34, cy + 7, current ? TEXT : TEXT_SOFT, false);
            int on = 0;
            for (Modules.Module module : entry.modules()) {
                on += module.on().getAsBoolean() ? 1 : 0;
            }
            if (on > 0) {
                // How many are on, in a small badge.
                String count = String.valueOf(on);
                int badgeRight = x + SIDEBAR - 14;
                int badgeLeft = badgeRight - font.width(count) - 7;
                roundRect(graphics, badgeLeft, cy + 5, badgeRight, cy + 17, 6,
                        current ? alpha(entry.color(), 0.3F) : 0x14FFFFFF, true, true);
                graphics.text(font, count, badgeLeft + 4, cy + 7, current ? TEXT : SECONDARY, false);
            }
            int index = i;
            hit(x + 8, cy, x + SIDEBAR - 8, cy + CATEGORY_ROW, () -> {
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
        boolean combat = Settings.combatAllowed(minecraft);
        int fy = y + h - 26;
        graphics.fill(x + 12, fy - 4, x + SIDEBAR - 12, fy - 3, SEPARATOR);
        boolean overServer = inside(mouseX, mouseY, x + 8, fy, x + SIDEBAR - 8, fy + 20);
        float lit = ease("server.hover", overServer ? 1 : 0, 20);
        if (lit > 0) {
            roundRect(graphics, x + 8, fy, x + SIDEBAR - 8, fy + 20, R_CONTROL, alpha(HOVER, lit), true, true);
        }
        int dot = combat ? GREEN : RED;
        roundRect(graphics, x + 13, fy + 6, x + 21, fy + 14, 4, alpha(dot, 0.25F), true, true);
        roundRect(graphics, x + 15, fy + 8, x + 19, fy + 12, 2, dot, true, true);
        String server = font.plainSubstrByWidth(Settings.currentServer(minecraft), SIDEBAR - 42);
        graphics.text(font, server, x + 26, fy + 6, overServer ? TEXT_SOFT : SECONDARY, false);
        hit(x + 8, fy, x + SIDEBAR - 8, fy + 20, () -> Settings.toggleCombatHere(minecraft),
                () -> Settings.toggleOwnSettingsHere(minecraft));
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
                    if (module.name().equals("Debug") && !needle.contains("deb")) {
                        continue; // Debug only shows when you search for it
                    }
                    if (module.name().toLowerCase(Locale.ROOT).contains(needle)
                            || module.about().toLowerCase(Locale.ROOT).contains(needle)) {
                        modules.add(module);
                        owners.add(entry);
                    }
                }
            }
        } else {
            for (Modules.Module module : categories.get(category).modules()) {
                if (module.name().equals("Debug")) {
                    continue; // hidden until searched
                }
                modules.add(module);
                owners.add(categories.get(category));
            }
        }
        int shown = -1;
        int on = 0;
        for (int i = 0; i < modules.size(); i++) {
            if (modules.get(i).name().equals(selected)) {
                shown = i;
            }
            on += modules.get(i).on().getAsBoolean() ? 1 : 0;
        }
        if (shown < 0 && !modules.isEmpty()) {
            shown = 0;
            selected = modules.get(0).name();
        }

        // Title, large and bold, a count beside it, and the build on the right.
        String title = searching ? "Search" : categories.get(category).name();
        Component titleText = Component.literal(title).withStyle(ChatFormatting.BOLD);
        graphics.pose().pushMatrix();
        graphics.pose().translate(x + MARGIN, y + 10);
        graphics.pose().scale(1.5F, 1.5F);
        graphics.text(font, titleText, 0, 0, TEXT, false);
        graphics.pose().popMatrix();
        String count = searching
                ? modules.size() + (modules.size() == 1 ? " result" : " results")
                : modules.size() + " modules  ·  " + on + " on";
        graphics.text(font, count, x + MARGIN + Math.round(font.width(titleText) * 1.5F) + 8, y + 14, TERTIARY, false);
        String build = "URANIUM " + VERSION;
        int buildRight = x + w - MARGIN;
        int buildLeft = buildRight - font.width(build) - 11;
        roundRect(graphics, buildLeft, y + 10, buildRight, y + 22, 6, PILL, true, true);
        graphics.text(font, build, buildLeft + 6, y + 12, SECONDARY, false);
        hit(x, y, x + w, y + TOP_BAR, this::startDrag, null);

        int viewTop = y + TOP_BAR + 4;
        int viewBottom = y + h - FOOTER;
        settingsX = x + LIST;
        if (modules.isEmpty()) {
            maxScroll = 0;
            maxSettingsScroll = 0;
            drawEmptyState(graphics, x, viewTop, x + w, viewBottom);
        } else {
            drawModuleList(graphics, modules, owners, searching, x + MARGIN, x + LIST - 8, viewTop, viewBottom,
                    mouseX, mouseY, frameSeconds);
            graphics.fill(x + LIST, viewTop, x + LIST + 1, viewBottom, SEPARATOR);
            accent = owners.get(shown).color();
            drawSettingsPane(graphics, modules.get(shown), x + LIST + 12, x + w - MARGIN, viewTop, viewBottom,
                    mouseX, mouseY, frameSeconds);
        }

        drawFooter(graphics, minecraft, x, y + h - FOOTER, w, mouseX, mouseY);
    }

    /** Nothing matches the search: a big magnifying glass and what to do next. */
    private void drawEmptyState(GuiGraphicsExtractor graphics, int x1, int y1, int x2, int y2) {
        int cx = (x1 + x2) / 2;
        int cy = (y1 + y2) / 2 - 18;
        magnifier(graphics, cx - 12, cy - 14, 20, TERTIARY, WINDOW);
        graphics.centeredText(font, Component.literal("No results").withStyle(ChatFormatting.BOLD), cx, cy + 16, TEXT);
        String needle = font.plainSubstrByWidth(query, x2 - x1 - 120);
        graphics.centeredText(font, "Nothing matches \"" + needle + "\"", cx, cy + 30, SECONDARY);
        graphics.centeredText(font, "Backspace to edit  ·  Esc to clear", cx, cy + 44, TERTIARY);
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
        card(graphics, gx1, rowY, gx2, rowY + total);
        for (int i = 0; i < modules.size(); i++) {
            if (rowY + MODULE_ROW > viewTop && rowY < viewBottom) {
                drawModuleRow(graphics, modules.get(i), owners.get(i), searching, gx1, rowY, gx2,
                        i == modules.size() - 1, i == 0, mouseX, mouseY, viewTop, viewBottom, frameSeconds);
            }
            rowY += MODULE_ROW;
        }
        graphics.disableScissor();
        scrollbar(graphics, "list", gx2 + 2, viewTop, viewBottom, total, scroll, maxScroll, listScrolledNs,
                inside(mouseX, mouseY, gx1 - MARGIN, viewTop, gx2 + 6, viewBottom));
    }

    /** The right pane: the selected module's name, its switch, what it does and its settings. */
    private void drawSettingsPane(GuiGraphicsExtractor graphics, Modules.Module module, int px1, int px2,
            int viewTop, int viewBottom, int mouseX, int mouseY, float frameSeconds) {
        int y = viewTop + 4;
        boolean on = module.on().getAsBoolean();
        int switchX = px2 - 28;
        graphics.text(font, Component.literal(font.plainSubstrByWidth(module.name(), switchX - px1 - 12))
                .withStyle(ChatFormatting.BOLD), px1, y + 4, TEXT, false);
        drawSwitch(graphics, module.name() + "#pane", switchX, y, 28, 16, on, accent, frameSeconds);
        hit(switchX - 4, y - 2, px2, y + 18, module.flip(), null);
        y += 22;
        // What it does, over up to two lines.
        for (String line : wrap(module.about(), px2 - px1 - 4, 2)) {
            graphics.text(font, line, px1, y, SECONDARY, false);
            y += 10;
        }
        y += 6;

        List<Modules.Row> settings = Modules.visible(module.settings());
        int top = y;
        if (settings.isEmpty()) {
            graphics.text(font, "No settings for this one", px1, y + 4, TERTIARY, false);
            maxSettingsScroll = 0;
            return;
        }
        int total = settings.size() * SETTING_ROW;
        maxSettingsScroll = Math.max(0, total + 4 - (viewBottom - top));
        settingsScroll = clamp(settingsScroll, 0, maxSettingsScroll);
        graphics.enableScissor(px1 - 1, top - 1, px2 + 6, viewBottom);
        int rowY = top - settingsScroll;
        card(graphics, px1, rowY, px2, rowY + total);
        for (int s = 0; s < settings.size(); s++) {
            if (rowY + SETTING_ROW > top && rowY < viewBottom) {
                drawSettingRow(graphics, module, settings.get(s), s, px1, rowY, px2, s == 0, s == settings.size() - 1,
                        mouseX, mouseY, top, viewBottom, frameSeconds);
            }
            rowY += SETTING_ROW;
        }
        graphics.disableScissor();
        scrollbar(graphics, "settings", px2 + 2, top, viewBottom, total, settingsScroll, maxSettingsScroll,
                settingsScrolledNs, inside(mouseX, mouseY, px1, top, px2 + 6, viewBottom));
    }

    /** A grouped list's background: a faint edge around a rounded card. */
    private static void card(GuiGraphicsExtractor graphics, int x1, int y1, int x2, int y2) {
        roundRect(graphics, x1 - 1, y1 - 1, x2 + 1, y2 + 1, R_CARD + 1, CARD_EDGE, true, true);
        roundRect(graphics, x1, y1, x2, y2, R_CARD, GROUP, true, true);
    }

    /** A thin scrollbar that shows while you scroll or point at its pane, and fades away after. */
    private void scrollbar(GuiGraphicsExtractor graphics, String key, int x, int viewTop, int viewBottom, int total,
            int scrolled, int max, long scrolledNs, boolean hovered) {
        boolean recent = System.nanoTime() - scrolledNs < 900_000_000L;
        float shown = ease("scrollbar." + key, max > 0 && (hovered || recent) ? 1 : 0, 10);
        if (max <= 0 || shown <= 0) {
            return;
        }
        int track = viewBottom - viewTop - 8;
        int thumb = Math.min(track, Math.max(16, track * (viewBottom - viewTop) / (total + 8)));
        int thumbY = viewTop + 4 + (track - thumb) * scrolled / max;
        roundRect(graphics, x, thumbY, x + 3, thumbY + thumb, 1, alpha(0x70FFFFFF, shown), true, true);
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
        int switchX = x2 - 10 - 26;
        boolean overRow = inside(mouseX, mouseY, x1, y, x2, y + MODULE_ROW) && mouseY >= viewTop && mouseY < viewBottom;
        float lit = ease("module.hover." + module.name(), overRow && !chosen ? 1 : 0, 20);
        if (chosen) {
            // The selected module: tinted in its category's color, with a bar on the left.
            roundRect(graphics, x1, y, x2, y + MODULE_ROW, R_CARD, alpha(owner.color(), 0.13F), roundTop, roundBottom);
            roundRect(graphics, x1 + 4, y + 9, x1 + 7, y + MODULE_ROW - 9, 1, owner.color(), true, true);
        } else if (lit > 0) {
            roundRect(graphics, x1, y, x2, y + MODULE_ROW, R_CARD, alpha(HOVER, lit), roundTop, roundBottom);
        }
        int textX = x1 + 12;
        graphics.text(font, font.plainSubstrByWidth(module.name(), switchX - textX - 8), textX, y + 7, TEXT, false);
        if (searching) {
            // Which category it is in, in that category's color, then what it does.
            String tag = owner.name();
            graphics.text(font, tag, textX, y + 18, owner.color(), false);
            int aboutX = textX + font.width(tag) + 4;
            graphics.text(font, font.plainSubstrByWidth(module.about(), Math.max(0, switchX - aboutX - 8)), aboutX,
                    y + 18, SECONDARY, false);
        } else {
            graphics.text(font, font.plainSubstrByWidth(module.about(), switchX - textX - 8), textX, y + 18,
                    SECONDARY, false);
        }
        drawSwitch(graphics, module.name(), switchX, y + 9, 26, 14, module.on().getAsBoolean(), owner.color(),
                frameSeconds);
        if (!roundBottom) {
            graphics.fill(textX, y + MODULE_ROW - 1, x2, y + MODULE_ROW, SEPARATOR);
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

    private void drawSettingRow(GuiGraphicsExtractor graphics, Modules.Module module, Modules.Row row, int index,
            int x1, int y, int x2, boolean firstRow, boolean lastRow, int mouseX, int mouseY, int viewTop,
            int viewBottom, float frameSeconds) {
        boolean over = inside(mouseX, mouseY, x1, y, x2, y + SETTING_ROW) && mouseY >= viewTop && mouseY < viewBottom;
        boolean dragged = row instanceof Modules.Slider held && held.num() == draggingNum;
        float lit = ease("setting.hover." + module.name() + "#" + index, over || dragged ? 1 : 0, 20);
        if (lit > 0) {
            roundRect(graphics, x1, y, x2, y + SETTING_ROW, R_CARD, alpha(HOVER, lit), firstRow, lastRow);
        }
        int top = Math.max(y, viewTop);
        int bottom = Math.min(y + SETTING_ROW, viewBottom);
        int textY = y + 7;
        int labelX = x1 + LABEL_INSET;
        // Every control lines up on this right edge.
        int right = x2 - 8;
        switch (row) {
            case Modules.Toggle toggle -> {
                graphics.text(font, font.plainSubstrByWidth(toggle.label(), right - 22 - labelX - 8), labelX, textY,
                        TEXT_SOFT, false);
                if (toggle.more() != null) {
                    // Right-click shows more: a small chevron, down while open.
                    boolean open = toggle.open() != null && toggle.open().getAsBoolean();
                    int labelEnd = labelX + Math.min(font.width(toggle.label()), right - 22 - labelX - 8);
                    chevron(graphics, labelEnd + 7, y + 10, open, over ? TEXT_SOFT : TERTIARY);
                    if (over && !open) {
                        graphics.text(font, "right-click", right - 30 - font.width("right-click"), textY, TERTIARY,
                                false);
                    }
                }
                drawSwitch(graphics, module.name() + "/" + toggle.label(), right - 22, y + 5, 22, 12,
                        toggle.on().getAsBoolean(), accent, frameSeconds);
                hit(x1, top, x2, bottom, toggle.flip(), toggle.more());
            }
            case Modules.Choice choice -> {
                // A macOS popup button: the value in a pill with the up-down arrows.
                String value = choice.value().get();
                boolean open = choice == openChoice;
                int buttonRight = right;
                int buttonLeft = buttonRight - font.width(value) - 24;
                graphics.text(font, font.plainSubstrByWidth(choice.label(), Math.max(0, buttonLeft - labelX - 6)),
                        labelX, textY, TEXT_SOFT, false);
                roundRect(graphics, buttonLeft, y + 3, buttonRight, y + SETTING_ROW - 3, R_SMALL + 1,
                        open || over ? PILL_HOVER : PILL, true, true);
                graphics.text(font, value, buttonLeft + 7, textY, TEXT, false);
                roundRect(graphics, buttonRight - 14, y + 5, buttonRight - 2, y + SETTING_ROW - 5, 3,
                        accent, true, true);
                upDown(graphics, buttonRight - 8, y + SETTING_ROW / 2, 0xFF0B110C);
                int rowY = y;
                int width = buttonRight - buttonLeft;
                hit(x1, top, x2, bottom, () -> openDropdown(choice, buttonRight, rowY, width), null);
            }
            case Modules.Shown shown -> {
                // Unwrapped by Modules.visible before drawing.
            }
            case Modules.Bind bind -> {
                boolean waiting = bind.awaiting().getAsBoolean();
                String value = waiting && !bind.value().get().startsWith("Press") ? "Press a key..." : bind.value().get();
                int pillX = right - font.width(value) - 13;
                graphics.text(font, font.plainSubstrByWidth(bind.label(), Math.max(0, pillX - labelX - 6)), labelX,
                        textY, TEXT_SOFT, false);
                if (waiting) {
                    // Listening: a softly pulsing green ring.
                    float pulse = (float) (0.5 + 0.5 * Math.sin(System.nanoTime() / 1.5E8));
                    roundRect(graphics, pillX - 1, y + 2, right + 1, y + SETTING_ROW - 2, R_SMALL + 2,
                            alpha(accent, 0.45F + 0.55F * pulse), true, true);
                    roundRect(graphics, pillX, y + 3, right, y + SETTING_ROW - 3, R_SMALL + 1,
                            blend(PILL, accent, 0.12F + 0.12F * pulse), true, true);
                } else {
                    roundRect(graphics, pillX, y + 3, right, y + SETTING_ROW - 3, R_SMALL + 1,
                            over ? PILL_HOVER : PILL, true, true);
                }
                graphics.text(font, value, pillX + 7, textY, TEXT, false);
                hit(x1, top, x2, bottom, bind.await(), null);
            }
            case Modules.Action action -> {
                int color = over ? blend(accent, 0xFFFFFFFF, 0.35F) : accent;
                graphics.text(font, action.label().get(), labelX, textY, color, false);
                chevron(graphics, right - 2, y + SETTING_ROW / 2, false, color);
                hit(x1, top, x2, bottom, action.run(), null);
            }
            case Modules.Slider slider -> {
                Num num = slider.num();
                boolean active = num == draggingNum;
                graphics.text(font, font.plainSubstrByWidth(slider.label(), SLIDER_LEFT - LABEL_INSET - 6), labelX,
                        textY, TEXT_SOFT, false);
                String value = num.label();
                graphics.text(font, value, right - font.width(value) + 1, textY, over || active ? TEXT : SECONDARY,
                        false);
                int trackX1 = x1 + SLIDER_LEFT;
                int trackX2 = right - SLIDER_VALUE;
                if (trackX2 - trackX1 >= 20) {
                    int trackY = y + SETTING_ROW / 2;
                    int lowX = trackX1 + (trackX2 - trackX1) * num.low() / num.limit;
                    int highX = trackX1 + (trackX2 - trackX1) * num.high() / num.limit;
                    roundRect(graphics, trackX1, trackY - 2, trackX2, trackY + 2, 2, SWITCH_OFF, true, true);
                    roundRect(graphics, num.range ? lowX : trackX1, trackY - 2, highX, trackY + 2, 2, accent, true,
                            true);
                    if (num.range) {
                        knob(graphics, lowX, trackY, active && !draggingHigh, over, accent);
                    }
                    knob(graphics, highX, trackY, active && (draggingHigh || !num.range), over, accent);
                    if (bottom > top) {
                        sliders.add(new SliderHit(x1, top, x2, bottom, trackX1, trackX2, num));
                    }
                }
            }
        }
        if (!lastRow) {
            graphics.fill(labelX, y + SETTING_ROW - 1, x2, y + SETTING_ROW, SEPARATOR);
        }
    }

    private void drawFooter(GuiGraphicsExtractor graphics, Minecraft minecraft, int x, int y, int w, int mouseX,
            int mouseY) {
        graphics.fill(x + 1, y, x + w, y + 1, SEPARATOR);
        int right = x + w - MARGIN;
        right = pill(graphics, "Unload", right, y + 4, RED, mouseX, mouseY, () -> {
            InputLoop.requestUnload();
            onClose();
        });
        right = pill(graphics, "Edit HUD", right - 6, y + 4, TEXT, mouseX, mouseY,
                () -> Mc.setScreen(minecraft, new HudEditorScreen()));
        String status = font.plainSubstrByWidth(SpearDash.status(), right - x - MARGIN - 8);
        graphics.text(font, status, x + MARGIN, y + 8, SECONDARY, false);
    }

    /** A rounded button ending at right; returns its left edge. */
    private int pill(GuiGraphicsExtractor graphics, String label, int right, int y, int color, int mouseX, int mouseY,
            Runnable action) {
        int left = right - font.width(label) - 15;
        boolean over = inside(mouseX, mouseY, left, y, right, y + 16);
        float lit = ease("pill." + label, over ? 1 : 0, 20);
        // The red button warms up to red when pointed at; the others just lighten.
        int hoverColor = color == RED ? 0xFF3D2220 : PILL_HOVER;
        roundRect(graphics, left, y, right, y + 16, R_CONTROL, blend(PILL, hoverColor, lit), true, true);
        graphics.text(font, label, left + 8, y + 4, color, false);
        hit(left, y, right, y + 16, action, null);
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

    /** A slider's knob: white with a soft shadow, a bit larger when pointed at, and larger still while dragged. */
    private static void knob(GuiGraphicsExtractor graphics, int x, int y, boolean active, boolean over, int color) {
        int r = active ? 6 : over ? 5 : 4;
        roundRect(graphics, x - r, y - r + 1, x + r, y + r + 1, r, 0x50000000, true, true);
        roundRect(graphics, x - r, y - r, x + r, y + r, r, 0xFFFFFFFF, true, true);
        if (active) {
            roundRect(graphics, x - 2, y - 2, x + 2, y + 2, 2, color, true, true);
        }
    }

    /** A magnifying glass: a ring of this size, its middle in the background color, and a handle. */
    private static void magnifier(GuiGraphicsExtractor graphics, int x, int y, int size, int color, int background) {
        int t = size >= 14 ? 2 : 1;
        roundRect(graphics, x, y, x + size, y + size, size / 2, color, true, true);
        roundRect(graphics, x + t, y + t, x + size - t, y + size - t, size / 2 - t, background, true, true);
        int start = Math.round(size * 0.85F) - 1;
        int length = Math.max(3, size / 3);
        for (int i = 0; i < length; i++) {
            graphics.fill(x + start + i, y + start + i, x + start + i + t + 1, y + start + i + t + 1, color);
        }
    }

    /** A small x, 5 by 5 pixels, centered on x, y. */
    private static void cross(GuiGraphicsExtractor graphics, int x, int y, int color) {
        for (int i = -2; i <= 2; i++) {
            graphics.fill(x + i, y + i, x + i + 1, y + i + 1, color);
            graphics.fill(x + i, y - i, x + i + 1, y - i + 1, color);
        }
    }

    /** Eases a named value toward a target (about speed per second); for hover and fade effects. */
    private float ease(String key, float target, float speed) {
        float value = fades.getOrDefault(key, target);
        value += (target - value) * Math.min(1, dt * speed);
        if (Math.abs(target - value) < 0.01F) {
            value = target;
        }
        fades.put(key, value);
        return value;
    }

    /** The color with its opacity scaled by amount. */
    private static int alpha(int color, float amount) {
        int a = Math.round(((color >>> 24) & 0xFF) * Math.max(0, Math.min(1, amount)));
        return (a << 24) | (color & 0xFFFFFF);
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

    /** The small up and down arrows of a macOS popup button. */
    private static void upDown(GuiGraphicsExtractor graphics, int x, int y, int color) {
        for (int i = 0; i < 3; i++) {
            graphics.fill(x - i, y - 4 + i, x + i + 1, y - 3 + i, color);
            graphics.fill(x - 2 + i, y + 1 + i, x + 3 - i, y + 2 + i, color);
        }
    }

    /**
     * Opens a choice's menu. Its options are found by stepping it all the way round (back to
     * where it was), the first time, and kept in that order after.
     */
    private void openDropdown(Modules.Choice choice, int right, int rowY, int width) {
        String key = choice.label() + "@" + System.identityHashCode(choice.next().getClass());
        String current = choice.value().get();
        List<String> options = OPTIONS.get(key);
        if (options == null || !options.contains(current)) {
            options = new ArrayList<>();
            options.add(current);
            for (int i = 0; i < 16; i++) {
                choice.next().run();
                String value = choice.value().get();
                if (value.equals(current) || options.contains(value)) {
                    break;
                }
                options.add(value);
            }
            for (int guard = 0; guard < 32 && !choice.value().get().equals(current); guard++) {
                choice.next().run();
            }
            OPTIONS.put(key, options);
        }
        if (options.size() < 2) {
            choice.next().run(); // nothing to pick between: just steps
            changed();
            return;
        }
        openChoice = choice;
        openOptions = options;
        openIndex = options.indexOf(current);
        anchorRight = right;
        anchorY = rowY;
        anchorWidth = width;
        menuOpenedNs = System.nanoTime();
    }

    private void closeDropdown() {
        openChoice = null;
    }

    /**
     * The open menu, macOS style: it opens over the button with the current value right on
     * top of it, a check by the current one, and the row under the mouse lit up.
     */
    private void drawDropdown(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        if (openChoice == null) {
            return;
        }
        int widest = 0;
        for (String option : openOptions) {
            widest = Math.max(widest, font.width(option));
        }
        int w = Math.max(anchorWidth + 8, widest + 30);
        int h = openOptions.size() * MENU_ITEM + 2 * MENU_PAD;
        int x2 = Math.min(width - 4, anchorRight + 4);
        int x1 = Math.max(4, x2 - w);
        int y1 = anchorY + (SETTING_ROW - MENU_ITEM) / 2 - MENU_PAD - openIndex * MENU_ITEM;
        y1 = clamp(y1, 4, Math.max(4, height - h - 4));
        int y2 = y1 + h;
        menuX1 = x1;
        menuY1 = y1;
        menuX2 = x2;
        menuY2 = y2;
        // Pops in over about 0.08 s.
        float open = Math.min(1.0F, (System.nanoTime() - menuOpenedNs) / 80.0E6F);
        float scale = 0.94F + 0.06F * (1 - (1 - open) * (1 - open));
        float cx = (x1 + x2) / 2.0F;
        float cy = anchorY + SETTING_ROW / 2.0F;
        graphics.pose().pushMatrix();
        graphics.pose().translate(cx, cy);
        graphics.pose().scale(scale, scale);
        graphics.pose().translate(-cx, -cy);
        for (int spread = 2; spread <= 6; spread += 2) {
            roundRect(graphics, x1 - spread, y1 - spread + 3, x2 + spread, y2 + spread + 3, R_CONTROL + spread,
                    0x1C000000, true, true);
        }
        roundRect(graphics, x1 - 1, y1 - 1, x2 + 1, y2 + 1, R_CONTROL + 1, MENU_EDGE, true, true);
        roundRect(graphics, x1, y1, x2, y2, R_CONTROL, MENU, true, true);
        for (int i = 0; i < openOptions.size(); i++) {
            int iy = y1 + MENU_PAD + i * MENU_ITEM;
            boolean over = inside(mouseX, mouseY, x1 + MENU_PAD, iy, x2 - MENU_PAD, iy + MENU_ITEM);
            if (over) {
                roundRect(graphics, x1 + MENU_PAD, iy, x2 - MENU_PAD, iy + MENU_ITEM, R_SMALL, accent, true, true);
            }
            int color = over ? 0xFF0B110C : TEXT;
            if (i == openIndex) {
                check(graphics, x1 + MENU_PAD + 4, iy + 5, color);
            }
            graphics.text(font, openOptions.get(i), x1 + MENU_PAD + 14, iy + 4, color, false);
        }
        graphics.pose().popMatrix();
    }

    /** A small check mark, 7 by 5 pixels, its top-left at x, y. */
    private static void check(GuiGraphicsExtractor graphics, int x, int y, int color) {
        for (int i = 0; i < 7; i++) {
            int dy = i <= 2 ? 2 + i : 4 - (i - 2);
            graphics.fill(x + i, y + dy, x + i + 1, y + dy + 2, color);
        }
    }

    /** A click while the menu is open: picks the option under it, or just closes the menu. */
    private void clickDropdown(double mouseX, double mouseY) {
        Modules.Choice choice = openChoice;
        closeDropdown();
        if (!inside(mouseX, mouseY, menuX1, menuY1, menuX2, menuY2)) {
            return;
        }
        int picked = (int) Math.floor((mouseY - menuY1 - MENU_PAD) / MENU_ITEM);
        if (picked < 0 || picked >= openOptions.size() || picked == openIndex) {
            return;
        }
        int steps = Math.floorMod(picked - openIndex, openOptions.size());
        for (int i = 0; i < steps; i++) {
            choice.next().run();
        }
        changed();
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
        if (openChoice != null) {
            if (event.button() == 0) {
                clickDropdown(event.x(), event.y());
            } else {
                closeDropdown();
            }
            return true;
        }
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
            Settings.save(Minecraft.getInstance());
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        closeDropdown();
        if (mouseX >= settingsX) {
            settingsScroll = clamp(settingsScroll - (int) Math.round(scrollY * 18), 0, maxSettingsScroll);
            settingsScrolledNs = System.nanoTime();
        } else {
            scroll = clamp(scroll - (int) Math.round(scrollY * 18), 0, maxScroll);
            listScrolledNs = System.nanoTime();
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
        if (openChoice != null && event.key() == 256) { // Esc closes the menu, not the screen
            closeDropdown();
            return true;
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
        if (System.nanoTime() - openedNs < 200_000_000L) {
            // The Numpad 8 that opened the menu also arrives as a typed 8; not a search.
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
        Settings.save(Minecraft.getInstance());
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
