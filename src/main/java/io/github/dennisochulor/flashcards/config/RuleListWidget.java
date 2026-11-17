package io.github.dennisochulor.flashcards.config;

import io.github.dennisochulor.flashcards.progress.Progression;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.CommonColors;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.function.IntConsumer;

/**
 * A grade's rules in the Rewards & Penalties editor, one per row:
 * {@code score ≤ -5   Weakened   once · 3 lines · then 0}. Click selects, double-click edits.
 */
class RuleListWidget extends ObjectSelectionList<RuleListWidget.Entry> {
    private static final int ROW_HEIGHT = 14;
    private static final int GRAY = 0xFFAAAAAA;
    private static final int CONDITION = 0xFFFFD75A;

    private final IntConsumer onEdit;
    private IntConsumer onSelect = _ -> {};

    /** @param onEdit called with a row's index when it's double-clicked */
    RuleListWidget(int width, int height, IntConsumer onEdit) {
        super(Minecraft.getInstance(), width, height, 0, ROW_HEIGHT);
        this.onEdit = onEdit;
    }

    void setRules(List<Progression.Rule> rules, int select) {
        clearEntries();
        for (int i = 0; i < rules.size(); i++) addEntry(new Entry(i, rules.get(i)));
        IntConsumer listener = onSelect;
        onSelect = _ -> {}; // filling the list isn't a choice of the player's
        setSelected(select >= 0 && select < children().size() ? children().get(select) : null);
        onSelect = listener;
    }

    /** @return the selected row's index, or -1 */
    int selectedIndex() {
        Entry entry = getSelected();
        return entry == null ? -1 : entry.index;
    }

    @Override
    public int getRowWidth() {
        return width - 14;
    }

    /** Called with the row's index (or -1) whenever the player selects a row. */
    void onSelect(IntConsumer listener) {
        this.onSelect = listener;
    }

    @Override
    public void setSelected(@Nullable Entry entry) {
        super.setSelected(entry);
        onSelect.accept(entry == null ? -1 : entry.index);
    }

    class Entry extends ObjectSelectionList.Entry<Entry> {
        final int index;
        private final Progression.Rule rule;

        Entry(int index, Progression.Rule rule) {
            this.index = index;
            this.rule = rule;
        }

        @Override
        public Component getNarration() {
            return Component.literal(Progression.describeCondition(rule) + " " + rule.name());
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float delta) {
            Font font = Minecraft.getInstance().font;
            int x = getContentX() + 2;
            int y = getContentYMiddle() - font.lineHeight / 2;
            int right = getContentRight() - 2;

            String condition = Progression.describeCondition(rule);
            graphics.text(font, condition, x, y, CONDITION, true);
            x += font.width(condition) + 6;

            StringBuilder details = new StringBuilder();
            if (rule.condition() != Progression.Condition.ALWAYS && rule.condition() != Progression.Condition.EVERY_NTH) {
                details.append(rule.trigger() == Progression.Trigger.ONCE ? "once" : "every time").append(" · ");
            }
            details.append(rule.commands().size()).append(rule.commands().size() == 1 ? " line" : " lines");
            if (rule.pick() == Progression.Pick.ONE_RANDOM && rule.commands().size() > 1) details.append(", 1 random");
            if (rule.resetTo() != null) details.append(" · then ").append(Progression.formatScore(rule.resetTo()));
            if (rule.resetCount()) details.append(" · count restarts");
            String detailText = details.toString();
            int detailWidth = font.width(detailText);
            graphics.text(font, detailText, right - detailWidth, y, GRAY, false);

            String name = rule.name().isBlank() ? "(no name)" : rule.name();
            int nameSpace = right - detailWidth - 6 - x;
            if (nameSpace > 10) {
                String shown = font.plainSubstrByWidth(name, nameSpace);
                if (shown.length() < name.length()) shown = font.plainSubstrByWidth(name, nameSpace - font.width("…")) + "…";
                graphics.text(font, shown, x, y, rule.name().isBlank() ? GRAY : CommonColors.WHITE, true);
            }
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
            RuleListWidget.this.setSelected(this);
            if (doubled) onEdit.accept(index);
            return true;
        }
    }
}
