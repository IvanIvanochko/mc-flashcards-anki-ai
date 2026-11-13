package io.github.dennisochulor.flashcards.config;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.CommonColors;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.function.Predicate;

/**
 * Scrollable list of Anki decks, shown as a tree (subdecks indented under their parent).
 * With a search filter the matching decks are listed flat with their full names.
 */
class DeckListWidget extends ObjectSelectionList<DeckListWidget.Entry> {
    private static final int ROW_HEIGHT = 14;
    private static final int INDENT = 10;
    private static final int GRAY = 0xFFAAAAAA;
    private static final int MARK_COLOR = 0xFF6EA8FF;

    /** Compares deck paths segment by segment so children always follow their parent ("A", "A::x", "A 2"). */
    private static final Comparator<String> TREE_ORDER = (a, b) -> {
        String[] pa = a.split("::");
        String[] pb = b.split("::");
        for (int i = 0; i < Math.min(pa.length, pb.length); i++) {
            int c = String.CASE_INSENSITIVE_ORDER.compare(pa[i], pb[i]);
            if (c != 0) return c;
        }
        return Integer.compare(pa.length, pb.length);
    };

    private final Consumer<String> onSelect;
    private List<String> decks = List.of();
    private String chosenDeck = "";
    private boolean tickChosen = true;
    private Predicate<String> marked = _ -> false;
    private boolean sort = true;
    private Function<String, String> detailText = _ -> "";
    private ToIntFunction<String> detailColor = _ -> GRAY;

    DeckListWidget(int width, int height, int y, Consumer<String> onSelect) {
        super(Minecraft.getInstance(), width, height, y, ROW_HEIGHT);
        this.onSelect = onSelect;
    }

    /**
     * For lists where picking a row means "edit this one" rather than "use this one": the picked row is only
     * highlighted (no green tick), and rows matching {@code marked} get a dot, e.g. decks that have their own preset.
     */
    void markInsteadOfTick(Predicate<String> marked) {
        this.tickChosen = false;
        this.marked = marked;
    }

    /** Shows the rows in the order given instead of sorting them (e.g. to keep a "Default" row first). */
    void keepOrder() {
        this.sort = false;
    }

    /** A short right-aligned note on each row, e.g. a model's size. An empty text shows nothing. */
    void setDetail(Function<String, String> text, ToIntFunction<String> color) {
        this.detailText = text;
        this.detailColor = color;
    }

    void setDecks(List<String> decks, String chosenDeck) {
        this.decks = sort ? decks.stream().sorted(TREE_ORDER).toList() : List.copyOf(decks);
        this.chosenDeck = chosenDeck;
        setFilter("");
    }

    void setFilter(String filter) {
        String needle = filter.strip().toLowerCase(Locale.ROOT);
        clearEntries();
        Entry toSelect = null;
        for (String deck : decks) {
            if (!needle.isEmpty() && !deck.toLowerCase(Locale.ROOT).contains(needle)) continue;
            Entry entry = new Entry(deck, needle.isEmpty());
            addEntry(entry);
            if (deck.equals(chosenDeck)) toSelect = entry;
        }
        setScrollAmount(0);
        if (toSelect != null) {
            setSelected(toSelect);
            centerScrollOn(toSelect);
        }
    }

    boolean isEmpty() {
        return children().isEmpty();
    }

    @Override
    public int getRowWidth() {
        return width - 14; // leave room for the scrollbar
    }

    private void choose(Entry entry) {
        setSelected(entry);
        chosenDeck = entry.fullName;
        onSelect.accept(entry.fullName);
    }

    class Entry extends ObjectSelectionList.Entry<Entry> {
        final String fullName;
        private final String label;
        private final int depth;

        Entry(String fullName, boolean tree) {
            this.fullName = fullName;
            String[] parts = fullName.split("::");
            this.depth = tree ? parts.length - 1 : 0;
            this.label = tree ? parts[parts.length - 1] : String.join(" › ", Arrays.asList(parts));
        }

        @Override
        public Component getNarration() {
            return Component.translatable("narrator.select", fullName);
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float delta) {
            Font font = Minecraft.getInstance().font;
            boolean chosen = tickChosen && fullName.equals(chosenDeck);
            boolean dot = !tickChosen && marked.test(fullName);
            int x = getContentX() + 2 + depth * INDENT;
            int y = getContentYMiddle() - font.lineHeight / 2;
            int rightEdge = getContentRight() - (chosen || dot ? 14 : 2);
            String detail = detailText.apply(fullName);
            if (!detail.isEmpty()) {
                int detailWidth = font.width(detail);
                graphics.text(font, detail, rightEdge - detailWidth, y, detailColor.applyAsInt(fullName), false);
                rightEdge -= detailWidth + 6;
            }
            int maxWidth = rightEdge - x;
            String text = font.plainSubstrByWidth(label, maxWidth);
            if (text.length() < label.length()) text = font.plainSubstrByWidth(label, maxWidth - font.width("…")) + "…";
            if (depth > 0) graphics.text(font, "·", x - 6, y, GRAY, false);
            graphics.text(font, text, x, y, chosen ? CommonColors.GREEN : CommonColors.WHITE, true);
            if (chosen) graphics.text(font, "✔", getContentRight() - 10, y, CommonColors.GREEN, true);
            if (dot) graphics.text(font, "●", getContentRight() - 10, y, MARK_COLOR, true);
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
            choose(this);
            return true;
        }
    }

    /** Keyboard selection (arrow keys) also picks the deck. */
    @Override
    public void setFocused(@Nullable GuiEventListener listener) {
        super.setFocused(listener);
        if (listener instanceof Entry entry && !entry.fullName.equals(chosenDeck)) choose(entry);
    }
}
