package io.github.dennisochulor.flashcards.config;

import io.github.dennisochulor.flashcards.progress.Progression;
import io.github.dennisochulor.flashcards.progress.ProgressionPresets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Pick a rewards & penalties preset: the built-in ones and your own. Shows what the highlighted one does
 * (every grade's points and rules) before you use it. Your own presets can be deleted here.
 */
class PresetPickerScreen extends Screen {
    private static final int GRAY = 0xFFAAAAAA;
    private static final String MINE = "Mine: ";

    private final Screen parent;
    private final Consumer<Progression.Setup> onPick;
    private String highlighted = "";

    private final StringWidget titleText = new StringWidget(Component.literal("Rewards & Penalties presets"), Minecraft.getInstance().font);
    private final DeckListWidget list;
    private final MultiLineTextWidget details = new MultiLineTextWidget(Component.empty(), Minecraft.getInstance().font);
    private final Button useButton = Button.builder(Component.literal("Use this preset"), _ -> use())
            .tooltip(Tooltip.create(Component.literal("Replace the current setup with this preset. The scores of your decks stay as they are.")))
            .size(120, 20).build();
    private final Button deleteButton = Button.builder(Component.literal("Delete"), _ -> deleteMine())
            .tooltip(Tooltip.create(Component.literal("Delete this preset of yours. Decks already using it keep their copy.")))
            .size(60, 20).build();
    private final Button cancelButton = Button.builder(Component.literal("Cancel"), _ -> this.onClose())
            .tooltip(Tooltip.create(Component.literal("Go back without changing anything"))).size(70, 20).build();

    PresetPickerScreen(Consumer<Progression.Setup> onPick) {
        super(Component.literal("Presets"));
        this.parent = Objects.requireNonNull(Minecraft.getInstance().gui.screen());
        this.onPick = onPick;
        list = new DeckListWidget(160, 100, 0, name -> {
            highlighted = name;
            showDetails();
        });
        list.keepOrder();
        details.setMaxRows(40);
        refreshList();
    }

    private void refreshList() {
        List<String> names = new ArrayList<>();
        for (ProgressionPresets.Preset preset : ProgressionPresets.BUILT_IN) names.add(preset.name());
        for (Progression.Setup mine : ProgressionPresets.userPresets()) names.add(MINE + mine.presetName());
        if (!names.contains(highlighted)) highlighted = names.getFirst();
        list.setDecks(names, highlighted);
        showDetails();
    }

    private Progression.@Nullable Setup setupOf(String row) {
        if (row.startsWith(MINE)) return ProgressionPresets.userPreset(row.substring(MINE.length()));
        ProgressionPresets.Preset preset = ProgressionPresets.builtIn(row);
        return preset == null ? null : preset.setup();
    }

    /** The highlighted preset in words: its description (built-in), then each grade's points and rules. */
    private void showDetails() {
        Progression.Setup setup = setupOf(highlighted);
        deleteButton.active = highlighted.startsWith(MINE);
        useButton.active = setup != null;
        if (setup == null) {
            details.setMessage(Component.empty());
            return;
        }
        StringBuilder text = new StringBuilder();
        ProgressionPresets.Preset builtIn = ProgressionPresets.builtIn(highlighted);
        if (builtIn != null) text.append(builtIn.description()).append("\n\n");
        else text.append("One of your own presets.\n\n");
        text.append(describe(setup));
        details.setMessage(Component.literal(text.toString()).withColor(GRAY));
    }

    /** Every grade's points and rules, one line per rule. */
    static String describe(Progression.Setup setup) {
        StringBuilder text = new StringBuilder();
        for (int g = 1; g <= 4; g++) {
            Progression.GradeSetup grade = setup.grade(g);
            text.append(Progression.GRADE_NAMES[g - 1]).append(" ").append(Progression.formatScore(grade.points()));
            if (grade.rules().isEmpty()) text.append(": no rules");
            text.append('\n');
            for (Progression.Rule rule : grade.rules()) {
                text.append("  ").append(Progression.describeCondition(rule));
                if (!rule.name().isBlank()) text.append(" · ").append(rule.name());
                text.append(": ").append(rule.commands().isEmpty() ? "(no commands)" : String.join("; ", rule.commands())).append('\n');
            }
        }
        if (setup.fadeHalfLifeHours() > 0) text.append("Fades: halves every ").append(Progression.formatScore(setup.fadeHalfLifeHours()).replace("+", "")).append(" hours");
        else text.append("No fading");
        return text.toString();
    }

    private void use() {
        Progression.Setup setup = setupOf(highlighted);
        if (setup == null) return;
        String name = highlighted.startsWith(MINE) ? highlighted.substring(MINE.length()) : highlighted;
        onPick.accept(setup.named(name));
        Minecraft.getInstance().gui.setScreen(parent);
    }

    private void deleteMine() {
        if (!highlighted.startsWith(MINE)) return;
        ProgressionPresets.deleteUserPreset(highlighted.substring(MINE.length()));
        highlighted = "";
        refreshList();
        rebuildWidgets();
    }

    @Override
    protected void init() {
        int gap = 10;
        int listWidth = Math.min(170, (width - 30) / 3);
        int detailsWidth = Math.min(340, width - 30 - listWidth - gap);
        int x = width / 2 - (listWidth + gap + detailsWidth) / 2;
        titleText.setPosition(width / 2 - titleText.getWidth() / 2, 8);
        addRenderableWidget(titleText);

        int top = 24;
        int bottom = height - 30;
        list.updateSizeAndPosition(listWidth, bottom - top, x, top);
        addRenderableWidget(list);
        details.setMaxWidth(detailsWidth);
        details.setPosition(x + listWidth + gap, top + 2);
        addRenderableWidget(details);

        useButton.setPosition(width / 2 - 130, height - 26);
        deleteButton.setPosition(width / 2 - 6, height - 26);
        cancelButton.setPosition(width / 2 + 58, height - 26);
        addRenderableWidget(useButton);
        addRenderableWidget(deleteButton);
        addRenderableWidget(cancelButton);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(parent);
    }
}
