package io.github.dennisochulor.flashcards.config;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.dennisochulor.flashcards.FileManager;
import io.github.dennisochulor.flashcards.StudyHud;
import io.github.dennisochulor.flashcards.questions.AnkiQuestionScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import io.github.dennisochulor.flashcards.IntSlider;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.CommonColors;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Drag the study panel, the "AI is thinking" indicator and the AI result panel anywhere on screen and resize them
 * (mouse wheel over a panel, or +/- for the selected one). Panels snap to the screen centre lines and edges.
 * <p>
 * The sliders at the bottom change how the selected panel looks: how solid its background is, how strong its text and
 * colours are, and for the study panel how much it fades while nothing is happening and how much it shows.
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
            placement = placement.at(left, top, placement.scale(), HudLayoutScreen.this.width, HudLayoutScreen.this.height, width, height);
        }

        /** Resizes around the panel's centre, so it grows in place instead of drifting. */
        void scaleBy(double delta) {
            double centreX = left() + width * placement.scale() / 2;
            double centreY = top() + height * placement.scale() / 2;
            double scale = placement.withScale(placement.scale() + delta).scale(); // clamped + rounded
            placement = placement.at(centreX - width * scale / 2, centreY - height * scale / 2, scale,
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

    // the selected panel's look
    private final StringWidget lookLabel = new StringWidget(Component.empty(), Minecraft.getInstance().font);
    private final IntSlider backgroundSlider = new IntSlider(120, 20, 0, 100, 80,
            percent -> Component.literal(percent == 0 ? "Background: none" : "Background: " + percent + "%"), _ -> lookChanged());
    private final IntSlider contentSlider = new IntSlider(120, 20, ModConfig.Placement.MIN_CONTENT, 100, 100,
            percent -> Component.literal("Text & colours: " + percent + "%"), _ -> lookChanged());
    private final IntSlider idleSlider = new IntSlider(120, 20, 0, 100, 100,
            percent -> Component.literal(percent == 100 ? "When idle: no change" : percent == 0 ? "When idle: hidden" : "When idle: " + percent + "%"),
            _ -> lookChanged());
    private ModConfig.HudDetail detail = FileManager.getConfig().hudDetail();
    private ModConfig previewConfig = FileManager.getConfig(); // the saved config with the "Show" choice applied, for the preview
    private final CycleButton<ModConfig.HudDetail> detailButton = new CycleButton.Builder<ModConfig.HudDetail>(
                    d -> Component.literal(d.friendlyName), () -> detail)
            .withValues(ModConfig.HudDetail.values())
            .withTooltip(d -> Tooltip.create(Component.literal("How much the study panel shows.\n\n" + d.friendlyName + ": " + d.description)))
            .create(0, 0, 120, 20, Component.literal("Show"), (_, value) -> {
                detail = value;
                previewConfig = FileManager.getConfig().withHudDetail(value);
            });
    private @Nullable Item lookShownFor = null; // which panel the sliders currently show

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
        detail = ModConfig.HudDetail.FULL;
        detailButton.setValue(detail);
        previewConfig = FileManager.getConfig().withHudDetail(detail);
        lookShownFor = null; // the sliders pick up the reset look
        rebuildWidgets();
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
        resetButton.setTooltip(Tooltip.create(Component.literal("Put all three panels back where they started, at normal size and with the normal look")));
        backgroundSlider.setTooltip(Tooltip.create(Component.literal(
                "How solid the dark panel behind the text is. Lower lets the world show through; 0 leaves just the text.")));
        contentSlider.setTooltip(Tooltip.create(Component.literal(
                "How strong the text, colours and bars are. Lower makes the panel quieter, for when you want it there but not in your face.")));
        idleSlider.setTooltip(Tooltip.create(Component.literal(
                "Study panel only. How visible it stays while nothing is happening (the next card is more than 10 seconds away). "
                + "It comes back to full when a card is close or waiting. 0% hides it until then. Hover this slider to preview.")));

        // bottom centre, out of the way of the default panel positions
        resetButton.setPosition(width / 2 - 74, height - 26);
        doneButton.setPosition(width / 2 + 4, height - 26);
        addRenderableWidget(resetButton);
        addRenderableWidget(doneButton);

        // the selected panel's look, in one row above the buttons (two rows on a narrow screen)
        Item item = selected != null ? selected : stats;
        if (lookShownFor != item) { // show this panel's values on the sliders
            lookShownFor = item;
            backgroundSlider.setIntValueSilently(item.placement.background());
            contentSlider.setIntValueSilently(item.placement.content());
            idleSlider.setIntValueSilently(item.placement.idle());
        }
        List<AbstractWidget> controls = new ArrayList<>(List.of(backgroundSlider, contentSlider));
        if (item == stats) {
            controls.add(idleSlider);
            controls.add(detailButton);
        }
        int gap = 4;
        int available = Math.min(width - 16, 520);
        int perRow = available >= controls.size() * 110 + (controls.size() - 1) * gap ? controls.size() : 2;
        int controlWidth = Math.min(130, (available - gap * (perRow - 1)) / perRow);
        int rows = (controls.size() + perRow - 1) / perRow;
        int y = height - 26 - 6 - rows * 24;
        lookTop = y - 12;
        for (int i = 0; i < controls.size(); i++) {
            int row = i / perRow, column = i % perRow;
            int inRow = Math.min(perRow, controls.size() - row * perRow);
            int rowWidth = inRow * controlWidth + (inRow - 1) * gap;
            AbstractWidget control = controls.get(i);
            control.setWidth(controlWidth);
            control.setPosition(width / 2 - rowWidth / 2 + column * (controlWidth + gap), y + row * 24);
            addRenderableWidget((AbstractWidget & net.minecraft.client.gui.narration.NarratableEntry) control);
        }
        lookLabel.setMessage(Component.literal("Look of the " + item.name.toLowerCase(Locale.ROOT) + " (click a panel to choose another)")
                .withColor(0xFFAAAAAA));
        lookLabel.setPosition(width / 2 - lookLabel.getWidth() / 2, lookTop);
        addRenderableWidget(lookLabel);
    }

    private int lookTop = 0;

    /** A look slider moved: apply it to the panel the sliders belong to. */
    private void lookChanged() {
        Item item = lookShownFor;
        if (item == null) return;
        item.placement = item.placement.withLook(backgroundSlider.intValue(), contentSlider.intValue(), idleSlider.intValue());
    }

    /** Shows the newly selected panel's look on the sliders (and the study panel's extra controls). */
    private void select(Item item) {
        if (selected == item && lookShownFor == item) return;
        selected = item;
        rebuildWidgets();
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
        // while the "when idle" slider is hovered or dragged, the study panel shows its idle look
        boolean previewIdle = lookShownFor == stats && (idleSlider.isHovered() || (getFocused() == idleSlider && isDragging()));
        StudyHud.drawStats(graphics, minecraft, previewConfig, stats.placement, previewIdle ? stats.placement.idle() / 100f : 1f);
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
            int y = below + font.lineHeight > lookTop - 2 ? selected.top() - font.lineHeight - 2 : below;
            graphics.text(font, label, x, y, CommonColors.WHITE, true);
        }

        graphics.centeredText(font, Component.literal("HUD Layout"), width / 2, 8, CommonColors.WHITE);
        graphics.centeredText(font, Component.literal("Drag to move  ·  scroll or + / - to resize  ·  arrow keys nudge  ·  Tab: next panel")
                .withColor(0xFFAAAAAA), width / 2, 20, CommonColors.WHITE);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true; // buttons first
        measure();
        Item item = itemAt(event.x(), event.y());
        if (item != null && event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            select(item);
            setFocused(null); // arrow keys now move the panel, not the last slider used
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
        select(item);
        item.scaleBy(scrollY > 0 ? SCALE_STEP : -SCALE_STEP);
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        // a focused slider or button uses the arrow keys itself
        if (getFocused() instanceof AbstractWidget && event.key() != InputConstants.KEY_TAB && super.keyPressed(event)) return true;
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
                case InputConstants.KEY_TAB -> { select(item == stats ? indicator : item == indicator ? result : stats); return true; }
                default -> {}
            }
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        FileManager.updateConfig(FileManager.getConfig().withPlacements(stats.placement, indicator.placement, result.placement)
                .withHudDetail(detail));
        Minecraft.getInstance().gui.setScreen(parent);
    }
}
