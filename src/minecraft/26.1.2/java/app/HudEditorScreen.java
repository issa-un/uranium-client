package app;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Drag the Speed HUD, look angle, landing line and Combat stats where you want them. Each shows as a box
 * at its current spot; drag a box to move it. Reset puts everything back, and Done (or
 * Escape) saves and returns to the menu. Positions are offsets from the default spots, so
 * the Speed HUD's Low/High setting still applies underneath.
 */
public final class HudEditorScreen extends Screen {
    // Each piece's box around its position: left, top, right, bottom. The speed ring's box
    // covers the numbers and warnings above it.
    private static final int[][] BOXES = {{-52, -48, 52, 17}, {-2, -2, 34, 10}, {-58, -2, 58, 10},
            {0, 0, CombatStats.WIDTH, CombatStats.height(13)}};
    private static final int BOX_COLOR = 0x6034A0C0;
    private static final int BOX_DRAGGED_COLOR = 0x905EE0C1;
    private static final int EDGE_COLOR = 0xFF5EE0C1;
    private static final int LABEL_COLOR = 0xFFFFFFFF;
    private int dragging = -1;
    private double dragStartX;
    private double dragStartY;
    private int startOffsetX;
    private int startOffsetY;

    public HudEditorScreen() {
        super(Component.literal("HUD editor"));
    }

    @Override
    protected void init() {
        int center = width / 2;
        addRenderableWidget(Button.builder(Component.literal("Reset"), button -> {
            for (int element = 0; element < UraniumHud.ELEMENTS; element++) {
                UraniumHud.setOffset(element, 0, 0);
            }
            Settings.save(Minecraft.getInstance());
        }).bounds(center - 62, 6, 60, 16).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
                .bounds(center + 2, 6, 60, 16).build());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        Settings.save(Minecraft.getInstance());
        Mc.setScreen(Minecraft.getInstance(), new ClickGuiScreen());
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0) {
            for (int element = UraniumHud.ELEMENTS - 1; element >= 0; element--) {
                if (insideBox(element, event.x(), event.y())) {
                    dragging = element;
                    dragStartX = event.x();
                    dragStartY = event.y();
                    startOffsetX = UraniumHud.offsetX(element);
                    startOffsetY = UraniumHud.offsetY(element);
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
        if (dragging >= 0) {
            // From where the drag started, so small mouse steps are not lost to rounding.
            UraniumHud.setOffset(dragging,
                    startOffsetX + (int) Math.round(event.x() - dragStartX),
                    startOffsetY + (int) Math.round(event.y() - dragStartY));
            return true;
        }
        return super.mouseDragged(event, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == 0 && dragging >= 0) {
            dragging = -1;
            Settings.save(Minecraft.getInstance());
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        for (int element = 0; element < UraniumHud.ELEMENTS; element++) {
            int[] box = box(element);
            graphics.fill(box[0], box[1], box[2], box[3], element == dragging ? BOX_DRAGGED_COLOR : BOX_COLOR);
            graphics.fill(box[0], box[1], box[2], box[1] + 1, EDGE_COLOR);
            graphics.fill(box[0], box[3] - 1, box[2], box[3], EDGE_COLOR);
            graphics.fill(box[0], box[1], box[0] + 1, box[3], EDGE_COLOR);
            graphics.fill(box[2] - 1, box[1], box[2], box[3], EDGE_COLOR);
            graphics.centeredText(font, UraniumHud.elementName(element),
                    (box[0] + box[2]) / 2, (box[1] + box[3]) / 2 - 4, LABEL_COLOR);
        }
        graphics.centeredText(font, "Drag the boxes to move the HUD", width / 2, 26, LABEL_COLOR);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private int[] box(int element) {
        int[] spot = UraniumHud.position(element, width, height);
        int[] edges = BOXES[element];
        return new int[] {spot[0] + edges[0], spot[1] + edges[1], spot[0] + edges[2], spot[1] + edges[3]};
    }

    private boolean insideBox(int element, double x, double y) {
        int[] box = box(element);
        return x >= box[0] && x < box[2] && y >= box[1] && y < box[3];
    }
}
