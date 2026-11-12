package io.github.dennisochulor.flashcards.questions;

import io.github.dennisochulor.flashcards.FileManager;
import io.github.dennisochulor.flashcards.ImageUtils;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractScrollArea;
import net.minecraft.client.gui.components.ImageWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.Component;
import net.minecraft.util.CommonColors;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A scrollable panel that shows a flashcard at the normal font size: text, images, labelled sections,
 * a grade badge and a score meter, stacked vertically.
 * Text that wraps onto more than a few lines is left-aligned (easier to read); short text is centered like in Anki.
 * <p>
 * Everything is measured once per width in {@link #ensureLayout}; a frame only draws what's visible.
 */
public class CardView extends AbstractScrollArea {
    private static final int PADDING = 9;
    private static final int BLOCK_GAP = 8;
    private static final int CENTER_MAX_LINES = 3;
    private static final int BACKGROUND = 0xD80E0F14;
    private static final int EDGE = 0x30FFFFFF;
    private static final int RULE = 0x38FFFFFF;
    private static final int LABEL_GAP = 6;
    private static final int BADGE_PAD_X = 9, BADGE_PAD_Y = 3;
    private static final int METER_MAX_WIDTH = 260, METER_BAR = 5, METER_GAP = 3;

    /** Something to show on the card. */
    public sealed interface Block permits Text, Image, Divider, Section, Badge, Meter {}

    /** @param left always left-align (e.g. lists); otherwise short text is centered */
    public record Text(Component text, int color, boolean left) implements Block {
        public Text(Component text, int color) {
            this(text, color, false);
        }
    }

    /** @param imageName relative to the mod's media folder */
    public record Image(String imageName, int maxSize) implements Block {}

    /** A plain horizontal rule. */
    public record Divider() implements Block {}

    /** A rule with a small label in the middle, e.g. ──── YOUR ANSWER ────. */
    public record Section(String label, int color) implements Block {}

    /** A coloured pill with centered text, e.g. the AI's grade. */
    public record Badge(Component text, int color) implements Block {}

    /**
     * A 0-1 bar split at the cut-offs into Again / Hard / Good / Easy, with a marker at the score.
     * @param names  the four band names, lowest first
     * @param colors the four band colours, lowest first
     */
    public record Meter(double score, double hardFrom, double goodFrom, double easyFrom, String[] names, int[] colors) implements Block {}

    /** A block with its position and everything that had to be measured. */
    private record Laid(Block block, int y, int height, List<FormattedCharSequence> lines, int @Nullable [] lineX,
                        @Nullable ImageWidget image, int textWidth) {}

    private final Font font;
    private final List<Block> blocks = new ArrayList<>();
    private final List<Laid> laid = new ArrayList<>();
    private int laidWidth = -1;
    private int laidContentHeight = 0;
    private int accent = 0;

    public CardView(Font font) {
        super(0, 0, 100, 100, Component.empty(), AbstractScrollArea.defaultSettings(font.lineHeight * 2));
        this.font = font;
    }

    public void setBlocks(List<Block> newBlocks) {
        blocks.clear();
        blocks.addAll(newBlocks);
        laidWidth = -1;
        StringBuilder narration = new StringBuilder();
        for (Block block : blocks) {
            if (block instanceof Text t) narration.append(t.text().getString()).append('\n');
            else if (block instanceof Section s) narration.append(s.label()).append('\n');
            else if (block instanceof Badge b) narration.append(b.text().getString()).append('\n');
        }
        setMessage(Component.literal(narration.toString()));
    }

    /** Swaps in a new score meter without measuring the card again (the meter's size never changes). */
    public void replaceMeter(Meter meter) {
        for (int i = 0; i < blocks.size(); i++) {
            if (blocks.get(i) instanceof Meter) blocks.set(i, meter);
        }
        for (int i = 0; i < laid.size(); i++) {
            Laid item = laid.get(i);
            if (item.block() instanceof Meter) {
                laid.set(i, new Laid(meter, item.y(), item.height(), item.lines(), item.lineX(), item.image(), item.textWidth()));
            }
        }
    }

    /** A thin coloured line along the top that shows the card's state (0 = none). */
    public void setAccent(int color) {
        this.accent = color;
    }

    /** Height the content needs at the current width, so the screen can shrink the panel for short cards. */
    public int preferredHeight() {
        return contentHeight();
    }

    private int innerWidth() {
        return Math.max(20, width - PADDING * 2 - scrollbarWidth());
    }

    private void ensureLayout() {
        int innerWidth = innerWidth();
        if (laidWidth == innerWidth) return;
        laidWidth = innerWidth;
        laid.clear();

        int y = 0;
        for (Block block : blocks) {
            if (!laid.isEmpty()) y += BLOCK_GAP;
            int height;
            switch (block) {
                case Text text -> {
                    List<FormattedCharSequence> lines = font.split(text.text(), innerWidth);
                    height = lines.size() * font.lineHeight;
                    int[] lineX = null;
                    if (!text.left() && lines.size() <= CENTER_MAX_LINES) {
                        lineX = new int[lines.size()];
                        for (int i = 0; i < lineX.length; i++) lineX[i] = (innerWidth - font.width(lines.get(i))) / 2;
                    }
                    laid.add(new Laid(block, y, height, lines, lineX, null, 0));
                }
                case Image image -> {
                    ImageWidget widget = ImageUtils.getImageWidget(FileManager.getImageFile(image.imageName()), image.maxSize());
                    if (widget.getWidth() > innerWidth) { // too wide: shrink, keeping the aspect ratio
                        int size = Math.max(16, image.maxSize() * innerWidth / widget.getWidth());
                        widget = ImageUtils.getImageWidget(FileManager.getImageFile(image.imageName()), size);
                    }
                    widget.setTooltip(null);
                    height = widget.getHeight();
                    laid.add(new Laid(block, y, height, List.of(), null, widget, 0));
                }
                case Divider _ -> {
                    height = 1;
                    laid.add(new Laid(block, y, height, List.of(), null, null, 0));
                }
                case Section section -> {
                    height = font.lineHeight;
                    laid.add(new Laid(block, y, height, List.of(), null, null, font.width(section.label())));
                }
                case Badge badge -> {
                    height = font.lineHeight + BADGE_PAD_Y * 2;
                    laid.add(new Laid(block, y, height, List.of(), null, null, font.width(badge.text())));
                }
                case Meter _ -> {
                    height = METER_BAR + 2 + METER_GAP + font.lineHeight; // marker sticks out 2px above the bar
                    laid.add(new Laid(block, y, height, List.of(), null, null, 0));
                }
            }
            y += height;
        }
        laidContentHeight = y;
    }

    @Override
    protected int contentHeight() {
        ensureLayout();
        return laidContentHeight + PADDING * 2;
    }

    @Override
    public void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        ensureLayout();
        graphics.fill(getX(), getY(), getRight(), getBottom(), BACKGROUND);
        graphics.outline(getX(), getY(), width, height, EDGE);
        if (accent != 0) graphics.fill(getX(), getY(), getRight(), getY() + 2, accent);

        graphics.enableScissor(getX() + 1, getY() + 2, getRight() - 1, getBottom() - 1);
        int left = getX() + PADDING;
        int innerWidth = innerWidth();
        int top = getY() + PADDING - (int) scrollAmount();
        for (Laid item : laid) {
            int y = top + item.y();
            if (y > getBottom() || y + item.height() < getY()) continue; // off screen
            switch (item.block()) {
                case Text text -> {
                    int lineY = y;
                    List<FormattedCharSequence> lines = item.lines();
                    int[] lineX = item.lineX();
                    for (int i = 0; i < lines.size(); i++) {
                        if (lineY + font.lineHeight >= getY() && lineY <= getBottom()) {
                            graphics.text(font, lines.get(i), left + (lineX == null ? 0 : lineX[i]), lineY, text.color(), true);
                        }
                        lineY += font.lineHeight;
                    }
                }
                case Image _ -> {
                    ImageWidget image = item.image();
                    if (image != null) {
                        image.setPosition(left + (innerWidth - image.getWidth()) / 2, y);
                        image.extractRenderState(graphics, mouseX, mouseY, delta);
                    }
                }
                case Divider _ -> graphics.fill(left, y, left + innerWidth, y + 1, RULE);
                case Section section -> {
                    int labelX = left + (innerWidth - item.textWidth()) / 2;
                    int ruleY = y + font.lineHeight / 2;
                    graphics.fill(left, ruleY, labelX - LABEL_GAP, ruleY + 1, RULE);
                    graphics.fill(labelX + item.textWidth() + LABEL_GAP, ruleY, left + innerWidth, ruleY + 1, RULE);
                    graphics.text(font, section.label(), labelX, y, section.color(), false);
                }
                case Badge badge -> {
                    int badgeWidth = item.textWidth() + BADGE_PAD_X * 2;
                    int x = left + (innerWidth - badgeWidth) / 2;
                    graphics.fill(x, y, x + badgeWidth, y + item.height(), withAlpha(badge.color(), 0x38));
                    graphics.outline(x, y, badgeWidth, item.height(), withAlpha(badge.color(), 0xB0));
                    graphics.text(font, badge.text(), x + BADGE_PAD_X, y + BADGE_PAD_Y + 1, CommonColors.WHITE, true);
                }
                case Meter meter -> drawMeter(graphics, meter, left, innerWidth, y);
            }
        }
        graphics.disableScissor();
        extractScrollbar(graphics, mouseX, mouseY);
    }

    private void drawMeter(GuiGraphicsExtractor graphics, Meter meter, int left, int innerWidth, int y) {
        int meterWidth = Math.min(METER_MAX_WIDTH, innerWidth);
        int x = left + (innerWidth - meterWidth) / 2;
        int barY = y + 2;
        double[] edges = {0, meter.hardFrom(), meter.goodFrom(), meter.easyFrom(), 1};
        int reached = 0; // the band the score falls in
        for (int band = 0; band < 4; band++) if (meter.score() >= edges[band] - 1e-9) reached = band;

        for (int band = 0; band < 4; band++) {
            int from = x + (int) Math.round(edges[band] * meterWidth);
            int to = x + (int) Math.round(edges[band + 1] * meterWidth);
            if (to <= from) continue; // two cut-offs are equal
            // the band the score is in is bright, the others dim
            graphics.fill(from, barY, to - (band < 3 ? 1 : 0), barY + METER_BAR, withAlpha(meter.colors()[band], band == reached ? 0xFF : 0x58));
            String name = meter.names()[band];
            int nameWidth = font.width(name);
            if (nameWidth + 2 <= to - from) {
                graphics.text(font, name, from + (to - from - nameWidth) / 2, barY + METER_BAR + METER_GAP,
                        withAlpha(meter.colors()[band], band == reached ? 0xFF : 0x90), false);
            }
        }
        int markerX = x + (int) Math.round(Math.min(1, Math.max(0, meter.score())) * (meterWidth - 2));
        graphics.fill(markerX - 1, barY - 2, markerX + 3, barY + METER_BAR + 2, 0xFF000000); // dark outline so it reads on any colour
        graphics.fill(markerX, barY - 2, markerX + 2, barY + METER_BAR + 2, CommonColors.WHITE);
    }

    private static int withAlpha(int color, int alpha) {
        return (alpha << 24) | (color & 0x00FFFFFF);
    }

    public void scrollBy(double amount) {
        setScrollAmount(scrollAmount() + amount);
    }

    /** @return the block's top relative to the visible area when not scrolled, or -1 for a bad index */
    public int blockTop(int index) {
        ensureLayout();
        return index >= 0 && index < laid.size() ? PADDING + laid.get(index).y() : -1;
    }

    /** Scrolls so the given block (by index) starts near the top, e.g. to jump to the answer. */
    public void scrollToBlock(int index) {
        int top = blockTop(index);
        if (top >= 0) setScrollAmount(top - PADDING);
    }

    public int pageHeight() {
        return Math.max(font.lineHeight, height - PADDING * 2 - font.lineHeight);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        boolean scrollbar = updateScrolling(event); // starts dragging the scrollbar
        return super.mouseClicked(event, doubleClick) || scrollbar;
    }

    @Override
    public void playDownSound(SoundManager soundManager) {
        // clicking the card isn't a button press
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, getMessage());
    }
}
