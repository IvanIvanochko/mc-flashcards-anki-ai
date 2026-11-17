package io.github.dennisochulor.flashcards.config;

import io.github.dennisochulor.flashcards.progress.Progression;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.CommonColors;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Adds or edits one rule of a grade: when it runs (its condition), what it runs (command lines), and what happens after.
 * A sentence at the bottom says the whole rule in words.
 */
class ProgressionRuleScreen extends Screen {
    private static final int GRAY = 0xFFAAAAAA;
    private static final int WIDTH = 330;

    private final Screen parent;
    private final String gradeName;
    private final String ruleId;
    private final Consumer<Progression.Rule> onSave;

    private final StringWidget titleText;
    private final EditBox nameBox = new EditBox(Minecraft.getInstance().font, WIDTH, 18, Component.literal("Name"));
    private final CycleButton<Progression.Condition> conditionButton;
    private final EditBox aBox = new EditBox(Minecraft.getInstance().font, 60, 20, Component.literal("A"));
    private final EditBox bBox = new EditBox(Minecraft.getInstance().font, 60, 20, Component.literal("B"));
    private final StringWidget aLabel = new StringWidget(Component.empty(), Minecraft.getInstance().font);
    private final StringWidget bLabel = new StringWidget(Component.literal("and").withColor(GRAY), Minecraft.getInstance().font);
    private final CycleButton<Progression.Trigger> triggerButton;
    private final StringWidget commandsLabel = new StringWidget(Component.literal("Command lines (without /):"), Minecraft.getInstance().font);
    private final MultiLineEditBox commandsBox;
    private final CycleButton<Progression.Pick> pickButton;
    private final EditBox resetBox = new EditBox(Minecraft.getInstance().font, 60, 20, Component.literal("Then set score to"));
    private final StringWidget resetLabel = new StringWidget(Component.literal("Then set the score to").withColor(GRAY), Minecraft.getInstance().font);
    private final CycleButton<Boolean> resetCountButton;
    private final MultiLineTextWidget summary = new MultiLineTextWidget(Component.empty(), Minecraft.getInstance().font);
    private final Button saveButton = Button.builder(Component.literal("Save rule"), _ -> save())
            .tooltip(Tooltip.create(Component.literal("Keep this rule and go back"))).size(100, 20).build();
    private final Button cancelButton = Button.builder(Component.literal("Cancel"), _ -> this.onClose())
            .tooltip(Tooltip.create(Component.literal("Go back without changing anything"))).size(80, 20).build();

    /**
     * @param ease4 the grade this rule belongs to (1 = Again ... 4 = Easy)
     * @param rule  the rule to edit, or null for a new one
     */
    ProgressionRuleScreen(int ease4, Progression.@Nullable Rule rule, Consumer<Progression.Rule> onSave) {
        super(Component.literal("Rule"));
        this.parent = Objects.requireNonNull(Minecraft.getInstance().gui.screen());
        this.gradeName = Progression.GRADE_NAMES[ease4 - 1];
        this.onSave = onSave;
        Progression.Rule start = rule != null ? rule
                : Progression.Rule.of("", ease4 == 1 ? Progression.Condition.SCORE_AT_MOST : Progression.Condition.SCORE_AT_LEAST,
                        ease4 == 1 ? -5 : 5, 0, Progression.Trigger.ONCE, null, false, Progression.Pick.ALL);
        this.ruleId = start.id();
        titleText = new StringWidget(Component.literal((rule == null ? "New rule for " : "Rule for ") + gradeName), Minecraft.getInstance().font);

        nameBox.setValue(start.name());
        nameBox.setMaxLength(40);
        nameBox.setHint(Component.literal("Name, e.g. Weakened (shown when it fires)").withColor(GRAY));
        nameBox.setTooltip(Tooltip.create(Component.literal("A short name, shown above the hotbar and under the grade buttons when this rule fires.")));
        nameBox.setResponder(_ -> updateSummary());

        conditionButton = new CycleButton.Builder<Progression.Condition>(c -> Component.literal(c.friendlyName), start::condition)
                .withValues(Progression.Condition.values())
                .withTooltip(c -> Tooltip.create(Component.literal("When this rule runs: " + c.explanation + ".\n\n"
                        + "Always: every time (like simple rewards). Score: depends on the deck's score after this answer. "
                        + "Given N times / every Nth: counts how often you gave " + gradeName + " (the count fades like the score).")))
                .create(0, 0, 150, 20, Component.literal("When"), (_, _) -> conditionChanged());
        aBox.setValue(number(start.a()));
        bBox.setValue(number(start.b()));
        aBox.setResponder(_ -> updateSummary());
        bBox.setResponder(_ -> updateSummary());

        triggerButton = new CycleButton.Builder<Progression.Trigger>(t -> Component.literal(t.friendlyName), start::trigger)
                .withValues(Progression.Trigger.values())
                .withTooltip(t -> Tooltip.create(Component.literal("Once: the first time it becomes true, and again only after it has stopped being true.\n"
                        + "Every time: on every " + gradeName + " while it's true.")))
                .create(0, 0, 150, 20, Component.literal("Runs"), (_, _) -> updateSummary());

        commandsBox = MultiLineEditBox.builder()
                .setPlaceholder(Component.literal("One command per line, e.g. effect give @s minecraft:slowness 10 0 true").withColor(GRAY))
                .build(Minecraft.getInstance().font, WIDTH, 60, Component.literal("Commands"));
        commandsBox.setValue(String.join("\n", start.commands()));
        commandsBox.setValueListener(_ -> updateSummary());

        pickButton = new CycleButton.Builder<Progression.Pick>(p -> Component.literal(p.friendlyName), start::pick)
                .withValues(Progression.Pick.values())
                .withTooltip(_ -> Tooltip.create(Component.literal("Run all the lines, or pick one of them at random each time (for lucky dips).")))
                .create(0, 0, WIDTH / 2 - 2, 20, Component.literal("Lines"), (_, _) -> updateSummary());

        resetBox.setValue(start.resetTo() == null ? "" : number(start.resetTo()));
        resetBox.setHint(Component.literal("—").withColor(GRAY));
        resetBox.setTooltip(Tooltip.create(Component.literal("Leave empty to leave the score alone. E.g. 0 to start over after a death or a jackpot.")));
        resetBox.setResponder(_ -> updateSummary());

        resetCountButton = CycleButton.onOffBuilder(start.resetCount())
                .withTooltip(_ -> Tooltip.create(Component.literal(
                        "ON: after this rule runs, the count of " + gradeName + " starts again from 0 (e.g. \"die on the 7th mistake\", then count again).")))
                .create(0, 0, WIDTH / 2 - 2, 20, Component.literal("Restart count"), (_, _) -> updateSummary());

        summary.setCentered(true);
        updateSummary();
    }

    private static String number(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    private static @Nullable Double parse(String text) {
        try {
            return text.isBlank() ? null : Double.parseDouble(text.strip().replace(',', '.'));
        }
        catch (NumberFormatException e) {
            return null;
        }
    }

    private void conditionChanged() {
        rebuildWidgets(); // the value boxes and the trigger depend on the condition
        updateSummary();
    }

    /** @return the rule as entered, or null if a number is missing or wrong (and the summary says so) */
    private Progression.@Nullable Rule build() {
        Progression.Condition condition = conditionButton.getValue();
        Double a = parse(aBox.getValue());
        Double b = parse(bBox.getValue());
        if (condition != Progression.Condition.ALWAYS && a == null) return null;
        if (condition == Progression.Condition.SCORE_BETWEEN && b == null) return null;
        if (condition.usesCount() && (a == null || a < 1)) return null;
        Double reset = parse(resetBox.getValue());
        if (!resetBox.getValue().isBlank() && reset == null) return null;
        List<String> commands = Arrays.asList(commandsBox.getValue().split("\n"));
        return new Progression.Rule(ruleId, nameBox.getValue().strip(), condition, a == null ? 0.0 : a, b == null ? a : b,
                commands, pickButton.getValue(), triggerButton.getValue(), reset, resetCountButton.getValue());
    }

    private void updateSummary() {
        Progression.Rule rule = build();
        saveButton.active = rule != null;
        if (rule == null) {
            summary.setMessage(Component.literal("Check the numbers: " + switch (conditionButton.getValue()) {
                case GIVEN_TIMES, EVERY_NTH -> "N must be a whole number of 1 or more.";
                case SCORE_BETWEEN -> "both ends are needed.";
                default -> "a number is needed.";
            } + (resetBox.getValue().isBlank() || parse(resetBox.getValue()) != null ? "" : " The score to reset to isn't a number.")).withColor(CommonColors.SOFT_RED));
            return;
        }
        StringBuilder text = new StringBuilder("When you answer ").append(gradeName);
        switch (rule.condition()) {
            case ALWAYS -> text.append(", every time");
            case SCORE_AT_LEAST -> text.append(" and the score is ").append(Progression.formatScore(rule.a())).append(" or higher");
            case SCORE_AT_MOST -> text.append(" and the score is ").append(Progression.formatScore(rule.a())).append(" or lower");
            case SCORE_BETWEEN -> text.append(" and the score is between ").append(Progression.formatScore(Math.min(rule.a(), rule.b())))
                    .append(" and ").append(Progression.formatScore(Math.max(rule.a(), rule.b())));
            case GIVEN_TIMES -> text.append(" for the ").append(Progression.ordinal((int) Math.round(rule.a()))).append(" time");
            case EVERY_NTH -> text.append(", every ").append(Progression.ordinal((int) Math.round(rule.a()))).append(" time");
        }
        boolean hasTrigger = rule.condition() != Progression.Condition.ALWAYS && rule.condition() != Progression.Condition.EVERY_NTH;
        if (hasTrigger) text.append(rule.trigger() == Progression.Trigger.ONCE ? " (once, until it stops being true)" : " (on every such answer)");
        int lines = rule.commands().size();
        text.append(": ").append(lines == 0 ? "nothing runs yet (add command lines)"
                : rule.pick() == Progression.Pick.ONE_RANDOM && lines > 1 ? "run one of " + lines + " lines at random"
                : "run " + (lines == 1 ? "1 line" : lines + " lines"));
        if (rule.resetTo() != null) text.append(", then set the score to ").append(Progression.formatScore(rule.resetTo()));
        if (rule.resetCount()) text.append(", then count ").append(gradeName).append(" from 0 again");
        text.append('.');
        summary.setMessage(Component.literal(text.toString()).withColor(lines == 0 ? CommonColors.SOFT_YELLOW : GRAY));
    }

    @Override
    protected void init() {
        int w = Math.min(WIDTH, width - 24);
        int x = width / 2 - w / 2;
        int half = (w - 4) / 2;
        int y = 8;
        titleText.setPosition(width / 2 - titleText.getWidth() / 2, y);
        addRenderableWidget(titleText);
        y += 14;

        nameBox.setWidth(w);
        nameBox.setPosition(x, y);
        addRenderableWidget(nameBox);
        y += 23;

        Progression.Condition condition = conditionButton.getValue();
        conditionButton.setRectangle(half, 20, x, y);
        addRenderableWidget(conditionButton);
        int vx = x + half + 4;
        if (condition != Progression.Condition.ALWAYS) {
            aLabel.setMessage(Component.literal(condition.usesCount() ? "N =" : condition == Progression.Condition.SCORE_BETWEEN ? "" : "score").withColor(GRAY));
            if (!aLabel.getMessage().getString().isEmpty()) {
                aLabel.setPosition(vx, y + 6);
                addRenderableWidget(aLabel);
                vx += aLabel.getWidth() + 4;
            }
            aBox.setPosition(vx, y);
            addRenderableWidget(aBox);
            vx += 64;
            if (condition == Progression.Condition.SCORE_BETWEEN) {
                bLabel.setPosition(vx, y + 6);
                addRenderableWidget(bLabel);
                bBox.setPosition(vx + bLabel.getWidth() + 4, y);
                addRenderableWidget(bBox);
            }
        }
        y += 24;
        if (condition != Progression.Condition.ALWAYS && condition != Progression.Condition.EVERY_NTH) {
            triggerButton.setRectangle(half, 20, x, y);
            addRenderableWidget(triggerButton);
            y += 24;
        }

        commandsLabel.setPosition(x, y + 2);
        addRenderableWidget(commandsLabel);
        y += 12;
        int bottomFixed = 24 + 24 + 6 + 30 + 26; // options rows, summary, buttons
        commandsBox.setRectangle(w, Math.max(30, Math.min(90, height - y - bottomFixed)), x, y);
        commandsBox.refreshScrollAmount();
        addRenderableWidget(commandsBox);
        y += commandsBox.getHeight() + 4;

        pickButton.setRectangle(half, 20, x, y);
        resetCountButton.setRectangle(half, 20, x + half + 4, y);
        addRenderableWidget(pickButton);
        addRenderableWidget(resetCountButton);
        y += 24;
        resetLabel.setPosition(x, y + 6);
        resetBox.setPosition(x + resetLabel.getWidth() + 6, y);
        addRenderableWidget(resetLabel);
        addRenderableWidget(resetBox);
        y += 26;

        summary.setMaxWidth(w);
        summary.setPosition(width / 2 - summary.getWidth() / 2, y);
        addRenderableWidget(summary);

        int buttonsY = Math.max(height - 26, y + summary.getHeight() + 4);
        saveButton.setPosition(width / 2 - 104, buttonsY);
        cancelButton.setPosition(width / 2 + 4, buttonsY);
        addRenderableWidget(saveButton);
        addRenderableWidget(cancelButton);
    }

    private void save() {
        Progression.Rule rule = build();
        if (rule == null) return;
        onSave.accept(rule);
        Minecraft.getInstance().gui.setScreen(parent);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(parent); // Cancel / Esc: nothing changes
    }
}
