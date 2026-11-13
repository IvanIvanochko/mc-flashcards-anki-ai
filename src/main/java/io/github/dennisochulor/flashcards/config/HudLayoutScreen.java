package io.github.dennisochulor.flashcards.config;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.dennisochulor.flashcards.FileManager;
import io.github.dennisochulor.flashcards.StudyHud;
import io.github.dennisochulor.flashcards.questions.AnkiQuestionScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.CommonColors;
import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.Objects;

/**
 * Drag the study panel, the "AI is thinking" indicator and the AI result panel anywhere on screen and resize them
 * (mouse wheel over a panel, or +/- for the selected one). Panels snap to the screen centre lines and edges.
 */
class HudLayoutScreen extends Screen {
    private static final int SNAP = 5;
    private static final double SCALE_STEP = 0.1;
    private static final int SELECTED = 0xFFFFFFFF;
    private static final int HOVERED = 0x90FFFFFF;
    private static final int IDLE = 0x50FFFFFF;
    private static final int GUIDE = 0x6055CCFF;

    /** One movable panel: its unscaled size and where it currently sits. */
    private final class Item {
        final String name;
        ModConfig.Placement placement;
        int width, height; // unscaled, refreshed every frame

        Item(String name, ModConfig.Placement placement) {
            this.name = name;
            this.placement = placement;
        }

        int left() { return placement.left(HudLayoutScreen.this.width, width); }
        int top() { return placement.top(HudLayoutScreen.this.height, height); }
        int scaledWidth() { return (int) Math.ceil(width * placement.scale()); }
        int scaledHeight() { return (int) Math.ceil(height * placement.scale()); }

        boolean contains(double x, double y) {
            return x >= left() && x < left() + scaledWidth() && y >= top() && y < top() + scaledHeight();
        }

        void moveTo(double left, double top) {
            placement = ModConfig.Placement.at(left, top, placement.scale(), HudLayoutScreen.this.width, HudLayoutScreen.this.height, width, height);
        }

        /** Resizes around the panel's centre, so it grows in place instead of drifting. */
        void scaleBy(double delta) {
            double centreX = left() + width * placement.scale() / 2;
            double centreY = top() + height * placement.scale() / 2;
            double scale = new ModConfig.Placement(0.0, 0.0, placement.scale() + delta).scale(); // clamped + rounded
            placement = ModConfig.Placement.at(centreX - width * scale / 2, centreY - height * scale / 2, scale,
                    HudLayoutScreen.this.width, HudLayoutScreen.this.height, width, height);
        }
    }

    private final Screen parent;
    private final Item stats;
    private final Item indicator;
    private final Item result;
    private final Item[] items;
    private final String sampleModel;
    private final long openedMillis = System.currentTimeMillis();

    private @Nullable Item selected;
    private @Nullable Item dragging = null;
    private double grabX, grabY; // where inside the panel the mouse grabbed it
    private boolean guideX = false, guideY = false;

    private final Button resetButton = Button.builder(Component.literal("Reset"), _ -> reset())
            .tooltip(Tooltip.create(Component.literal("Put all three panels back where they started, at normal size"))).size(70, 20).build();
    private final Button doneButton = Button.builder(Component.literal("Done"), _ -> this.onClose()).size(70, 20).build();

    HudLayoutScreen() {
        super(Component.literal("HUD Layout"));
        parent = Objects.requireNonNull(Minecraft.getInstance().gui.screen());
        ModConfig config = FileManager.getConfig();
        stats = new Item("Study panel", config.hudPlacement());
        indicator = new Item("AI thinking indicator", config.indicatorPlacement());
        result = new Item("AI result (auto-grading)", config.resultPlacement());
        items = new Item[] {result, indicator, stats}; // drawn in this order; the first one that contains the mouse wins
        selected = stats;
        String model = config.aiGrading().model();
        sampleModel = model.isBlank() ? "qwen2.5:7b" : model;
    }

    private void reset() {
        stats.placement = ModConfig.Placement.DEFAULT_HUD;
        indicator.placement = ModConfig.Placement.DEFAULT_INDICATOR;
        result.placement = ModConfig.Placement.DEFAULT_RESULT;
    }

    @Override
    public void added() {
        super.added();
        StudyHud.editingLayout = true;
    }

    @Override
    public void removed() {
        StudyHud.editingLayout = false;
        super.removed();
    }

    @Override
    protected void init() {
        doneButton.setTooltip(Tooltip.create(Component.literal("Save the layout and go back")));
        // bottom centre, out of the way of the default panel positions
        resetButton.setPosition(width / 2 - 74, height - 26);
        doneButton.setPosition(width / 2 + 4, height - 26);
        addRenderableWidget(resetButton);
        addRenderableWidget(doneButton);
    }

    private void measure() {
        Minecraft minecraft = Minecraft.getInstance();
        int[] s = StudyHud.statsSize(minecraft, FileManager.getConfig());
        stats.width = s[0];
        stats.height = s[1];
        int[] i = StudyHud.indicatorSize(minecraft.font, sampleModel);
        indicator.width = i[0];
        indicator.height = i[1];
        int[] r = StudyHud.statusSize(minecraft.font, AnkiQuestionScreen.sampleStatus(0));
        result.width = r[0];
        result.height = r[1];
    }

    private @Nullable Item itemAt(double x, double y) {
        for (Item item : items) if (item.contains(x, y)) return item;
        return null;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        measure();
        Minecraft minecraft = Minecraft.getInstance();
        Font font = minecraft.font;

        if (dragging != null) { // snapping guides
            if (guideX) graphics.fill(width / 2, 0, width / 2 + 1, height, GUIDE);
            if (guideY) graphics.fill(0, height / 2, width, height / 2 + 1, GUIDE);
        }

        // the real panels, exactly as they'll look in game (the indicator loops a sample animation)
        StudyHud.drawStats(graphics, minecraft, FileManager.getConfig(), stats.placement);
        long sampleElapsed = (System.currentTimeMillis() - openedMillis) % 9000;
        StudyHud.drawIndicator(graphics, font, indicator.placement, sampleModel, sampleElapsed, 0);
        StudyHud.drawStatus(graphics, font, result.placement, AnkiQuestionScreen.sampleStatus(System.currentTimeMillis() - openedMillis));

        Item hovered = dragging != null ? dragging : itemAt(mouseX, mouseY);
        for (Item item : items) {
            int color = item == selected ? SELECTED : item == hovered ? HOVERED : IDLE;
            graphics.outline(item.left() - 1, item.top() - 1, item.scaledWidth() + 2, item.scaledHeight() + 2, color);
        }
        if (selected != null) { // label under (or above) the selected panel
            String label = selected.name + "  " + String.format(Locale.ROOT, "%.0f%%", selected.placement.scale() * 100);
            int labelWidth = font.width(label);
            int x = Math.max(2, Math.min(width - labelWidth - 2, selected.left()));
            int below = selected.top() + selected.scaledHeight() + 3;
            int y = below + font.lineHeight > height - 30 ? selected.top() - font.lineHeight - 2 : below;
            graphics.text(font, label, x, y, CommonColors.WHITE, true);
        }

        graphics.centeredText(font, Component.literal("HUD Layout"), width / 2, 8, CommonColors.WHITE);
        graphics.centeredText(font, Component.literal("Drag to move  ·  scroll or + / - to resize  ·  arrow keys nudge").withColor(0xFFAAAAAA),
                width / 2, height - 40, CommonColors.WHITE);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true; // buttons first
        measure();
        Item item = itemAt(event.x(), event.y());
        if (item != null && event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            selected = item;
            dragging = item;
            grabX = event.x() - item.left();
            grabY = event.y() - item.top();
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        Item item = dragging;
        if (item == null) return super.mouseDragged(event, dragX, dragY);
        double left = event.x() - grabX;
        double top = event.y() - grabY;
        int w = item.scaledWidth();
        int h = item.scaledHeight();

        // snap the panel's centre to the screen centre lines
        guideX = Math.abs(left + w / 2.0 - width / 2.0) <= SNAP;
        guideY = Math.abs(top + h / 2.0 - height / 2.0) <= SNAP;
        if (guideX) left = width / 2.0 - w / 2.0;
        if (guideY) top = height / 2.0 - h / 2.0;
        item.moveTo(left, top); // edges are handled by the placement itself: it can't leave the screen
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (dragging != null) {
            dragging = null;
            guideX = guideY = false;
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        measure();
        Item item = itemAt(mouseX, mouseY);
        if (item == null) item = selected;
        if (item == null || scrollY == 0) return false;
        selected = item;
        item.scaleBy(scrollY > 0 ? SCALE_STEP : -SCALE_STEP);
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        Item item = selected;
        if (item != null) {
            measure();
            int step = event.hasShiftDown() ? 10 : 1;
            switch (event.key()) {
                case InputConstants.KEY_EQUALS, InputConstants.KEY_ADD -> { item.scaleBy(SCALE_STEP); return true; }
                case InputConstants.KEY_MINUS -> { item.scaleBy(-SCALE_STEP); return true; }
                case InputConstants.KEY_LEFT -> { item.moveTo(item.left() - step, item.top()); return true; }
                case InputConstants.KEY_RIGHT -> { item.moveTo(item.left() + step, item.top()); return true; }
                case InputConstants.KEY_UP -> { item.moveTo(item.left(), item.top() - step); return true; }
                case InputConstants.KEY_DOWN -> { item.moveTo(item.left(), item.top() + step); return true; }
                case InputConstants.KEY_TAB -> { selected = item == stats ? indicator : item == indicator ? result : stats; return true; }
                default -> {}
            }
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        FileManager.updateConfig(FileManager.getConfig().withPlacements(stats.placement, indicator.placement, result.placement));
        Minecraft.getInstance().gui.setScreen(parent);
    }
}
