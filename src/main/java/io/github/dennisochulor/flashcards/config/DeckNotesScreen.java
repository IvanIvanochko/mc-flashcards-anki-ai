package io.github.dennisochulor.flashcards.config;

import io.github.dennisochulor.flashcards.ai.AiGrader;
import io.github.dennisochulor.flashcards.ai.DeckNotesWriter;
import io.github.dennisochulor.flashcards.anki.AnkiSession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.CommonColors;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * A deck's notes for the grading AI: a few sentences about the deck that are added to the grading prompt.
 * <p>
 * Nobody should have to know how to write a prompt, so besides typing there are three ways to get notes:
 * a guided form (no AI), a ready-made request to paste into any AI plus a button to paste its answer back,
 * and the local Ollama model writing them from sample cards. Replacing the whole prompt stays possible, as "advanced".
 */
class DeckNotesScreen extends Screen {
    private static final int GRAY = 0xFFAAAAAA;
    private static final int MAX_WIDTH = 380;

    private final Screen parent;
    private final String deck;
    private final String deckLabel;
    private final ModConfig.AiGrading ai; // with this deck's model
    private final Consumer<String> onSave;
    private final Runnable openWholePrompt;
    private final BooleanSupplier wholePromptIsOwn;

    private @Nullable List<DeckNotesWriter.Sample> samples = null; // loaded from Anki the first time they're needed
    private @Nullable String undoText = null;
    private boolean busy = false;
    private long busySinceMillis = 0;
    private String busyText = "";

    private final StringWidget titleText;
    private final MultiLineTextWidget intro = new MultiLineTextWidget(Component.literal(
            "A few sentences that tell the AI about this deck: the subject, how strict to be, what an answer must contain. "
            + "They're added to the grading prompt, so the grading rules themselves stay as they are.").withColor(GRAY),
            Minecraft.getInstance().font);
    private final MultiLineEditBox box;
    private final StringWidget helpLabel = new StringWidget(Component.literal("Not sure what to write? Three ways to get notes:").withColor(GRAY),
            Minecraft.getInstance().font);
    private final MultiLineTextWidget statusText = new MultiLineTextWidget(Component.empty(), Minecraft.getInstance().font);

    private final Button formButton = Button.builder(Component.literal("1. Guided form..."), _ -> openForm())
            .tooltip(Tooltip.create(Component.literal(
                    "Answer a few questions (subject, kind of cards, how strict, your language) and the notes are put together for you.\n"
                    + "No AI involved, works offline."))).build();
    private final Button copyButton = Button.builder(Component.literal("2. Copy request for an AI"), _ -> copyRequest())
            .tooltip(Tooltip.create(Component.literal(
                    "Copies a ready-made message to your clipboard. It explains what deck notes are and includes "
                    + DeckNotesWriter.SAMPLE_CARDS + " sample cards from this deck.\n"
                    + "Paste it into any AI (ChatGPT, Claude, ...), copy its answer, then press \"Paste the AI's answer\".\n\n"
                    + "Note: pasting it into an online AI sends those sample cards to that service."))).build();
    private final Button pasteButton = Button.builder(Component.literal("Paste the AI's answer"), _ -> pasteAnswer())
            .tooltip(Tooltip.create(Component.literal(
                    "Takes the text on your clipboard (the AI's answer), tidies it (quotes, bullet points, \"Here are your notes:\") "
                    + "and puts it in the box.\nPasting straight into the box with Ctrl/Cmd+V works too, just without the tidying."))).build();
    private final Button localButton;
    private final Button wholePromptButton = Button.builder(Component.empty(), _ -> openWholePrompt())
            .tooltip(Tooltip.create(Component.literal(
                    "Advanced. Replace the entire grading prompt for this deck, instead of adding notes to it.\n"
                    + "Rarely needed, and riskier: with notes the grading rules stay intact; with your own prompt you're responsible for them."))).build();
    private final Button undoButton = Button.builder(Component.literal("Undo"), _ -> undo())
            .tooltip(Tooltip.create(Component.literal("Bring back the text from before the last change made by a button on this screen"))).build();
    private final Button clearButton = Button.builder(Component.literal("Clear"), _ -> replaceText("", "Notes cleared. Undo brings them back."))
            .tooltip(Tooltip.create(Component.literal("Remove this deck's notes, so it's graded with the prompt alone"))).build();
    private final Button doneButton = Button.builder(Component.literal("Done"), _ -> this.onClose())
            .tooltip(Tooltip.create(Component.literal("Keep these notes and go back"))).build();

    /**
     * @param deck             full Anki deck name (used to fetch sample cards)
     * @param ai               AI settings with this deck's model, for "Write with local AI"
     * @param onSave           gets the notes when the screen closes ("" = none)
     * @param openWholePrompt  opens the editor for this deck's whole prompt
     * @param wholePromptIsOwn whether this deck currently replaces the whole prompt
     */
    DeckNotesScreen(String deck, String deckLabel, String notes, ModConfig.AiGrading ai, Consumer<String> onSave,
                    Runnable openWholePrompt, BooleanSupplier wholePromptIsOwn) {
        super(Component.literal("Deck notes"));
        this.parent = Objects.requireNonNull(Minecraft.getInstance().gui.screen());
        this.deck = deck;
        this.deckLabel = deckLabel;
        this.ai = ai;
        this.onSave = onSave;
        this.openWholePrompt = openWholePrompt;
        this.wholePromptIsOwn = wholePromptIsOwn;

        titleText = new StringWidget(Component.literal("Deck notes · " + deckLabel), Minecraft.getInstance().font);
        box = MultiLineEditBox.builder()
                .setPlaceholder(Component.literal("e.g. These cards are from a university econometrics course. Exact terms matter: "
                        + "count a missing technical term as a missing key point.").withColor(GRAY))
                .build(Minecraft.getInstance().font, 300, 80, Component.literal("Deck notes"));
        box.setCharacterLimit(DeckNotesWriter.MAX_NOTES_LENGTH);
        box.setValue(notes);

        boolean hasModel = !ai.model().isBlank(); // writing notes only needs a model, not AI grading switched on
        localButton = Button.builder(Component.literal("3. Write with local AI"), _ -> writeWithLocalAi())
                .tooltip(Tooltip.create(Component.literal(hasModel
                        ? "Your own model (" + ai.model() + ") reads " + DeckNotesWriter.SAMPLE_CARDS + " sample cards from this deck and writes the notes into the box.\n"
                          + "Nothing leaves your computer. A small local model writes simpler notes than a big online AI, so read them through."
                        : "Needs an Ollama model: pick one in AI Grading..., or for this deck under AI model."))).build();
        localButton.active = hasModel;

        intro.setCentered(true);
        statusText.setCentered(true);
        undoButton.active = false;
    }

    // ---------------------------------------------------------------- helpers

    private void setStatus(String text, int color) {
        statusText.setMessage(Component.literal(text).withColor(color));
        statusText.setX(width / 2 - statusText.getWidth() / 2);
    }

    /** Puts new text in the box, remembering the old one for Undo. */
    private void replaceText(String text, String status) {
        undoText = box.getValue();
        box.setValue(text);
        undoButton.active = true;
        setStatus(status, CommonColors.GREEN);
    }

    private void undo() {
        String previous = undoText;
        if (previous == null) return;
        undoText = box.getValue(); // so Undo again redoes
        box.setValue(previous);
        setStatus("Undone. Press Undo again to redo.", GRAY);
    }

    private void setBusy(boolean busy, String text) {
        this.busy = busy;
        this.busyText = text;
        this.busySinceMillis = System.currentTimeMillis();
        formButton.active = !busy;
        copyButton.active = !busy;
        pasteButton.active = !busy;
        localButton.active = !busy && !ai.model().isBlank();
        if (busy) setStatus(text, GRAY);
    }

    /** Runs {@code then} with the deck's sample cards, fetching them from Anki the first time. */
    private void withSamples(Consumer<List<DeckNotesWriter.Sample>> then, Consumer<String> withoutSamples) {
        List<DeckNotesWriter.Sample> loaded = samples;
        if (loaded != null) {
            then.accept(loaded);
            return;
        }
        setBusy(true, "Reading sample cards from Anki...");
        AnkiSession.loadSamples(deck, DeckNotesWriter.SAMPLE_CARDS, list -> {
            samples = list;
            setBusy(false, "");
            then.accept(list);
        }, error -> {
            setBusy(false, "");
            withoutSamples.accept(error);
        });
    }

    // ---------------------------------------------------------------- the three ways

    private void openForm() {
        Minecraft.getInstance().gui.setScreen(new DeckNotesFormScreen(deckLabel,
                notes -> replaceText(notes, "Filled in from the form. Change anything you like.")));
    }

    private void copyRequest() {
        String soFar = box.getValue();
        withSamples(list -> {
            Minecraft.getInstance().keyboardHandler.setClipboard(DeckNotesWriter.requestForAnyAi(deck, list, soFar));
            setStatus("Copied, with " + list.size() + " sample card" + (list.size() == 1 ? "" : "s")
                    + ". Paste it into an AI, copy its answer, then press \"Paste the AI's answer\".", CommonColors.GREEN);
        }, error -> {
            Minecraft.getInstance().keyboardHandler.setClipboard(DeckNotesWriter.requestForAnyAi(deck, List.of(), soFar));
            setStatus("Copied without sample cards (" + error + "). It still works, the AI just sees less of your deck.", CommonColors.SOFT_YELLOW);
        });
    }

    private void pasteAnswer() {
        String clipboard = Minecraft.getInstance().keyboardHandler.getClipboard();
        String notes = DeckNotesWriter.clean(clipboard);
        if (notes.isBlank()) {
            setStatus("Your clipboard is empty. Copy the AI's answer first.", CommonColors.SOFT_YELLOW);
        }
        else if (clipboard.contains("Please write short \"deck notes\"")) {
            setStatus("That's still the request itself. Paste it into an AI first, then copy the AI's answer.", CommonColors.SOFT_YELLOW);
        }
        else replaceText(notes, "Pasted. Read it through and change anything that's off.");
    }

    private void writeWithLocalAi() {
        String soFar = box.getValue();
        withSamples(list -> askLocalAi(list, soFar), error -> {
            setStatus("Couldn't read cards from Anki (" + error + "), writing from the deck name only.", CommonColors.SOFT_YELLOW);
            askLocalAi(List.of(), soFar);
        });
    }

    private void askLocalAi(List<DeckNotesWriter.Sample> list, String soFar) {
        setBusy(true, ai.model() + " is writing the notes");
        AiGrader.writeDeckNotes(ai, deck, list, soFar, notes -> {
            setBusy(false, "");
            replaceText(notes, "Written by " + ai.model() + ". Read it through and change anything that's off.");
        }, error -> {
            setBusy(false, "");
            setStatus("Couldn't write the notes: " + error, CommonColors.SOFT_RED);
        });
    }

    private void openWholePrompt() {
        onSave.accept(box.getValue().strip()); // keep the notes typed so far
        openWholePrompt.run();
    }

    @Override
    public void tick() {
        super.tick();
        if (busy) { // a moving sign of life while Anki or the model works
            long seconds = (System.currentTimeMillis() - busySinceMillis) / 1000;
            setStatus(busyText + ".".repeat((int) (seconds % 3) + 1) + (seconds >= 3 ? "  " + seconds + "s" : ""), GRAY);
        }
    }

    // ---------------------------------------------------------------- layout

    @Override
    protected void init() {
        int w = Math.min(MAX_WIDTH, width - 24);
        int x = width / 2 - w / 2;
        int half = (w - 4) / 2;

        titleText.setPosition(width / 2 - titleText.getWidth() / 2, 7);
        intro.setMaxWidth(w);
        intro.setPosition(width / 2 - intro.getWidth() / 2, 19);
        int boxTop = 19 + intro.getHeight() + 5;

        // bottom-up: closing row, status, the two rows of helper buttons, their caption
        int bottomY = height - 26;
        int smallWidth = Math.min(64, (w - 12) / 4);
        wholePromptButton.setMessage(Component.literal(wholePromptIsOwn.getAsBoolean() ? "Whole prompt: Own..." : "Whole prompt..."));
        wholePromptButton.setRectangle(Math.min(130, w - 3 * smallWidth - 16), 20, x, bottomY); // left: the advanced option
        doneButton.setRectangle(smallWidth, 20, x + w - smallWidth, bottomY);                       // right: Undo, Clear, Done
        clearButton.setRectangle(smallWidth, 20, x + w - 2 * smallWidth - 4, bottomY);
        undoButton.setRectangle(smallWidth, 20, x + w - 3 * smallWidth - 8, bottomY);

        statusText.setMaxWidth(w);
        statusText.setMaxRows(2);
        int statusY = bottomY - 4 - 18;
        statusText.setPosition(width / 2 - statusText.getWidth() / 2, statusY);

        int row2 = statusY - 4 - 20;
        int row1 = row2 - 22;
        formButton.setRectangle(half, 20, x, row1);
        localButton.setRectangle(half, 20, x + half + 4, row1);
        copyButton.setRectangle(half, 20, x, row2);
        pasteButton.setRectangle(half, 20, x + half + 4, row2);
        helpLabel.setMaxWidth(w);
        int helpY = row1 - 11;
        helpLabel.setPosition(width / 2 - helpLabel.getWidth() / 2, helpY);

        box.setRectangle(w, Math.max(30, helpY - 5 - boxTop), x, boxTop);
        box.refreshScrollAmount();

        addRenderableWidget(titleText);
        addRenderableWidget(intro);
        addRenderableWidget(box);
        addRenderableWidget(helpLabel);
        addRenderableWidget(formButton);
        addRenderableWidget(localButton);
        addRenderableWidget(copyButton);
        addRenderableWidget(pasteButton);
        addRenderableWidget(statusText);
        addRenderableWidget(wholePromptButton);
        addRenderableWidget(undoButton);
        addRenderableWidget(clearButton);
        addRenderableWidget(doneButton);
        if (!busy) setInitialFocus(box);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return !busy; // let the local AI finish rather than lose its answer
    }

    @Override
    public void onClose() {
        onSave.accept(box.getValue().strip());
        Minecraft.getInstance().gui.setScreen(parent);
    }
}
