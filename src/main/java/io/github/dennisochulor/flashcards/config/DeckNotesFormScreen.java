package io.github.dennisochulor.flashcards.config;

import io.github.dennisochulor.flashcards.ai.DeckNotesWriter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.CommonColors;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Deck notes without writing a prompt and without any AI: a few questions about the deck, and the notes are
 * put together from the answers. The result is shown live at the bottom.
 */
class DeckNotesFormScreen extends Screen {
    private static final int GRAY = 0xFFAAAAAA;
    private static final int WIDTH = 300;

    private final Screen parent;
    private final Consumer<String> onUse;

    private final StringWidget titleText;
    private final MultiLineTextWidget intro = new MultiLineTextWidget(Component.literal(
            "Answer what applies and skip the rest. The notes below are built from your answers, and you can edit them afterwards.")
            .withColor(GRAY), Minecraft.getInstance().font);
    private final StringWidget subjectLabel = new StringWidget(Component.literal("What is this deck about? (subject and level)"), Minecraft.getInstance().font);
    private final EditBox subjectBox = new EditBox(Minecraft.getInstance().font, WIDTH, 18, Component.literal("Subject"));
    private final CycleButton<DeckNotesWriter.CardKind> kindButton = new CycleButton.Builder<DeckNotesWriter.CardKind>(
                    kind -> Component.literal(kind.label), () -> DeckNotesWriter.CardKind.MIXED)
            .withValues(DeckNotesWriter.CardKind.values())
            .withTooltip(kind -> Tooltip.create(Component.literal("What the cards ask for. This tells the AI what a complete answer looks like.\n\n"
                    + kind.label + ": " + kind.explanation)))
            .create(0, 0, WIDTH / 2 - 2, 20, Component.literal("Cards"), (_, _) -> updatePreview());
    private final CycleButton<DeckNotesWriter.Strictness> strictnessButton = new CycleButton.Builder<DeckNotesWriter.Strictness>(
                    strictness -> Component.literal(strictness.label), () -> DeckNotesWriter.Strictness.NORMAL)
            .withValues(DeckNotesWriter.Strictness.values())
            .withTooltip(strictness -> Tooltip.create(Component.literal("How demanding the AI should be on this deck.\n\n"
                    + strictness.label + ": " + strictness.explanation)))
            .create(0, 0, WIDTH / 2 - 2, 20, Component.literal("Grading"), (_, _) -> updatePreview());
    private final CycleButton<Boolean> formulasButton = CycleButton.onOffBuilder(false)
            .withTooltip(_ -> Tooltip.create(Component.literal(
                    "ON: when a card's answer has a formula, you must state it (in symbols or in words) to get more than Hard.")))
            .create(0, 0, WIDTH / 2 - 2, 20, Component.literal("Formulas required"), (_, _) -> updatePreview());
    private final CycleButton<Boolean> numbersButton = CycleButton.onOffBuilder(false)
            .withTooltip(_ -> Tooltip.create(Component.literal("ON: numbers, dates and units have to be exactly right.")))
            .create(0, 0, WIDTH / 2 - 2, 20, Component.literal("Exact numbers"), (_, _) -> updatePreview());
    private final StringWidget languageLabel = new StringWidget(Component.literal("Which language do you answer in?"), Minecraft.getInstance().font);
    private final EditBox languageBox = new EditBox(Minecraft.getInstance().font, WIDTH, 18, Component.literal("Answer language"));
    private final StringWidget previewLabel = new StringWidget(Component.literal("Your notes:").withColor(GRAY), Minecraft.getInstance().font);
    private final MultiLineTextWidget preview = new MultiLineTextWidget(Component.empty(), Minecraft.getInstance().font);
    private final Button useButton = Button.builder(Component.literal("Use these notes"), _ -> use())
            .tooltip(Tooltip.create(Component.literal("Put these notes into the deck notes box. What was in the box can be brought back there with Undo.")))
            .size(130, 20).build();
    private final Button cancelButton = Button.builder(Component.literal("Cancel"), _ -> this.onClose())
            .tooltip(Tooltip.create(Component.literal("Go back without changing the notes"))).size(80, 20).build();

    private String notes = "";

    /** @param onUse gets the finished notes when the player presses "Use these notes" */
    DeckNotesFormScreen(String deckLabel, Consumer<String> onUse) {
        super(Component.literal("Guided notes"));
        this.parent = Objects.requireNonNull(Minecraft.getInstance().gui.screen());
        this.onUse = onUse;
        titleText = new StringWidget(Component.literal("Guided notes · " + deckLabel), Minecraft.getInstance().font);

        subjectBox.setMaxLength(120);
        subjectBox.setHint(Component.literal("e.g. university econometrics, Spanish A2 vocabulary").withColor(GRAY));
        subjectBox.setTooltip(Tooltip.create(Component.literal("Knowing the subject and level helps the AI judge what counts as a correct, complete answer.")));
        subjectBox.setResponder(_ -> updatePreview());
        languageBox.setMaxLength(40);
        languageBox.setHint(Component.literal("leave empty if it's the same as the cards").withColor(GRAY));
        languageBox.setTooltip(Tooltip.create(Component.literal("E.g. Ukrainian. The AI is told that answering in it is fine, and to write its feedback in it.")));
        languageBox.setResponder(_ -> updatePreview());
        intro.setCentered(true);
        preview.setMaxRows(6);
        updatePreview();
    }

    private void updatePreview() {
        notes = DeckNotesWriter.fromForm(subjectBox.getValue(), kindButton.getValue(), strictnessButton.getValue(),
                formulasButton.getValue(), numbersButton.getValue(), languageBox.getValue());
        preview.setMessage(notes.isEmpty()
                ? Component.literal("(nothing yet: fill in at least one answer above)").withColor(GRAY)
                : Component.literal(notes).withColor(CommonColors.WHITE));
        useButton.active = !notes.isEmpty();
    }

    private void use() {
        onUse.accept(notes);
        Minecraft.getInstance().gui.setScreen(parent);
    }

    @Override
    protected void init() {
        int w = Math.min(WIDTH, width - 24);
        int x = width / 2 - w / 2;
        int half = (w - 4) / 2;
        int y = 8;

        titleText.setPosition(width / 2 - titleText.getWidth() / 2, y);
        y += 13;
        intro.setMaxWidth(w);
        intro.setPosition(width / 2 - intro.getWidth() / 2, y);
        y += intro.getHeight() + 6;

        subjectLabel.setPosition(x, y);
        y += 11;
        subjectBox.setWidth(w);
        subjectBox.setPosition(x, y);
        y += 23;

        kindButton.setRectangle(half, 20, x, y);
        strictnessButton.setRectangle(half, 20, x + half + 4, y);
        y += 23;
        formulasButton.setRectangle(half, 20, x, y);
        numbersButton.setRectangle(half, 20, x + half + 4, y);
        y += 26;

        languageLabel.setPosition(x, y);
        y += 11;
        languageBox.setWidth(w);
        languageBox.setPosition(x, y);
        y += 24;

        previewLabel.setPosition(x, y);
        y += 11;
        preview.setMaxWidth(w);
        preview.setPosition(x, y);

        int buttonsY = height - 26;
        useButton.setPosition(width / 2 - 107, buttonsY);
        cancelButton.setPosition(width / 2 + 27, buttonsY);

        addRenderableWidget(titleText);
        addRenderableWidget(intro);
        addRenderableWidget(subjectLabel);
        addRenderableWidget(subjectBox);
        addRenderableWidget(kindButton);
        addRenderableWidget(strictnessButton);
        addRenderableWidget(formulasButton);
        addRenderableWidget(numbersButton);
        addRenderableWidget(languageLabel);
        addRenderableWidget(languageBox);
        addRenderableWidget(previewLabel);
        addRenderableWidget(preview);
        addRenderableWidget(useButton);
        addRenderableWidget(cancelButton);
        setInitialFocus(subjectBox);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(parent); // closing without "Use these notes" changes nothing
    }
}
