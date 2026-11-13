package io.github.dennisochulor.flashcards.config;

import io.github.dennisochulor.flashcards.FileManager;
import io.github.dennisochulor.flashcards.ai.AiGrader;
import io.github.dennisochulor.flashcards.ai.GradingPrompt;
import io.github.dennisochulor.flashcards.ai.Ollama;
import io.github.dennisochulor.flashcards.anki.AnkiSession;
import io.github.dennisochulor.flashcards.questions.QuestionScheduler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.CommonColors;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Per-deck presets: which AI model grades a deck, with which prompt, and which rewards and penalties apply.
 * Decks are listed on the left (a dot marks the ones that have a preset), the picked deck's settings are on the right.
 * Anything a deck leaves on "Default" comes from its parent deck, then from the main settings.
 */
class DeckPresetsScreen extends Screen {
    private static final int GRAY = 0xFFAAAAAA;
    private static final RewardsConfigScreen.Rewards NO_REWARDS =
            new RewardsConfigScreen.Rewards(List.of(), List.of(), ModConfig.CommandSelectionStrategy.EXECUTE_ALL, "");

    /** One deck's preset while it's being edited. */
    private static final class Draft {
        String model = "";
        String prompt = ""; // advanced: replaces the whole prompt
        String notes = "";  // the usual way: a few sentences added to the prompt
        boolean customRewards = false;
        RewardsConfigScreen.Rewards rewards = NO_REWARDS;
        int intervalSeconds = 0; // 0 = default
        ModConfig.@Nullable CardLayout layout = null; // null = default sizes
        ModConfig.@Nullable AutoGrades autoGrades = null; // null = the main setting

        boolean isDefault() {
            return model.isBlank() && prompt.isBlank() && notes.isBlank() && !customRewards && intervalSeconds == 0 && layout == null && autoGrades == null;
        }
    }

    private final Screen parent;
    private final ModConfig config = FileManager.getConfig();
    private final Map<String, Draft> drafts = new LinkedHashMap<>();
    private List<Ollama.ModelInfo> models = List.of();
    private String selected;
    private boolean needsRebuild = false;
    private boolean initialized = false;

    private final StringWidget titleText = new StringWidget(Component.literal("Deck Presets"), Minecraft.getInstance().font);
    private final MultiLineTextWidget statusText = new MultiLineTextWidget(Component.empty(), Minecraft.getInstance().font);
    private final DeckListWidget deckList;
    private final StringWidget deckTitle = new StringWidget(Component.empty(), Minecraft.getInstance().font);
    private final MultiLineTextWidget hintText = new MultiLineTextWidget(Component.empty(), Minecraft.getInstance().font);
    private final Button modelButton = Button.builder(Component.empty(), _ -> openModelPicker())
            .tooltip(Tooltip.create(Component.literal(
                    "Pick the Ollama model that grades this deck from a list: a bigger one for a hard subject, a small fast one for vocabulary..."))).build();
    private final Button notesButton = Button.builder(Component.empty(), _ -> openNotes())
            .tooltip(Tooltip.create(Component.literal(
                    "Tell the AI about this deck in a few sentences: the subject, how strict to be, what an answer must contain.\n"
                    + "You don't have to write them yourself: there's a guided form, a ready-made request for any AI, and your local AI can write them.\n"
                    + "(Replacing the whole grading prompt for this deck is in there too, as an advanced option.)"))).build();
    private final Button rewardsButton = Button.builder(Component.empty(), _ -> openRewards())
            .tooltip(Tooltip.create(Component.literal("Give this deck its own rewards and penalties"))).build();
    private final Button timeButton = Button.builder(Component.empty(), _ -> {
        Draft draft = draft();
        draft.intervalSeconds = draft.intervalSeconds > 0 ? 0 : inheritedInterval(); // Default <-> Own, starting from the current time
        needsRebuild = true;
    }).tooltip(Tooltip.create(Component.literal(
            "How long until the next question after a card from this deck.\nClick to switch between the default time and this deck's own."))).build();
    private final StringWidget everyLabel = new StringWidget(Component.literal("Every").withColor(GRAY), Minecraft.getInstance().font);
    private final StringWidget minutesLabel = new StringWidget(Component.literal("min").withColor(GRAY), Minecraft.getInstance().font);
    private final StringWidget secondsLabel = new StringWidget(Component.literal("sec").withColor(GRAY), Minecraft.getInstance().font);
    private final EditBox minutesBox = new EditBox(Minecraft.getInstance().font, 30, 20, Component.literal("Minutes"));
    private final EditBox secondsBox = new EditBox(Minecraft.getInstance().font, 30, 20, Component.literal("Seconds"));
    private String timeBoxesShow = ""; // which deck's time the two boxes currently hold
    private boolean fillingTimeBoxes = false;
    // auto-grade: the label says Default/Own, the four switches show what applies, ↺ goes back to Default
    private final StringWidget autoGradeLabel = new StringWidget(Component.empty(), Minecraft.getInstance().font);
    private final List<GradeToggles.Toggle> autoGradeToggles = GradeToggles.create(ModConfig.AutoGrades.DEFAULT, changed -> {
        draft().autoGrades = changed; // touching a switch gives the deck its own choice
        needsRebuild = true;
    });
    private final Button autoGradeResetButton = Button.builder(Component.literal("↺"), _ -> {
        draft().autoGrades = null;
        needsRebuild = true;
    }).tooltip(Tooltip.create(Component.literal("Back to Default: this deck follows the main choice (AI Grading → Auto-grade) again.")))
            .size(18, 12).build();
    private final Button sizeButton = Button.builder(Component.empty(), _ -> openCardSize())
            .tooltip(Tooltip.create(Component.literal(
                    "Give this deck its own card sizes: how far the answer area may grow, how tall pictures may be, how wide the card is.\n"
                    + "Handy for a deck full of pictures. You can also set this from a card of the deck: click the cog in its corner."))).build();
    private final Button clearButton = Button.builder(Component.literal("Clear this deck's preset"), _ -> {
        drafts.remove(selected);
        needsRebuild = true;
    }).tooltip(Tooltip.create(Component.literal("Back to Default for everything on this deck"))).build();
    private final Button doneButton = Button.builder(Component.literal("Done"), _ -> this.onClose()).size(100, 20).build();

    DeckPresetsScreen() {
        super(Component.literal("Deck Presets"));
        parent = Objects.requireNonNull(Minecraft.getInstance().gui.screen());

        for (ModConfig.DeckProfile profile : config.deckProfiles()) {
            Draft draft = new Draft();
            draft.model = profile.model();
            draft.prompt = profile.prompt();
            draft.customRewards = profile.customRewards();
            draft.rewards = new RewardsConfigScreen.Rewards(profile.correctCommands(), profile.wrongCommands(), profile.strategy(), profile.rewardPreset());
            draft.intervalSeconds = profile.intervalSeconds();
            draft.notes = profile.notes();
            draft.layout = profile.layout();
            draft.autoGrades = profile.autoGrades();
            drafts.put(profile.deck(), draft);
        }
        selected = config.ankiDeck();
        minutesBox.setMaxLength(4);
        secondsBox.setMaxLength(2);
        minutesBox.setTooltip(Tooltip.create(Component.literal("Minutes")));
        secondsBox.setTooltip(Tooltip.create(Component.literal("Seconds (0-59)")));
        minutesBox.setResponder(_ -> timeTyped());
        secondsBox.setResponder(_ -> timeTyped());

        deckList = new DeckListWidget(200, 100, 0, deck -> {
            selected = deck;
            needsRebuild = true; // rebuilt on the next tick, not in the middle of the list handling this click
        });
        deckList.markInsteadOfTick(deck -> {
            Draft draft = drafts.get(deck);
            return draft != null && !draft.isDefault();
        });
        statusText.setCentered(true);
        hintText.setCentered(true);
        showDecks(List.of()); // decks that already have a preset, until Anki answers

        setStatus(Component.literal("Loading decks from Anki...").withColor(GRAY));
        AnkiSession.loadDecks(config.ankiConnectUrl(), decks -> {
            showDecks(decks);
            setStatus(Component.empty());
        }, error -> setStatus(Component.literal(error + " Showing only decks that already have a preset.").withColor(CommonColors.SOFT_YELLOW)));

        // models for the per-deck model button; without Ollama the button still offers Default and the saved choice
        AiGrader.loadModels(config.aiGrading().ollamaUrl(), loaded -> {
            models = loaded;
            needsRebuild = true;
        }, _ -> {});
    }

    private void showDecks(List<String> fromAnki) {
        TreeSet<String> all = new TreeSet<>(fromAnki);
        all.addAll(drafts.keySet());
        if (!selected.isBlank()) all.add(selected);
        deckList.setDecks(new ArrayList<>(all), selected);
    }

    private void setStatus(Component status) {
        statusText.setMessage(status);
        if (initialized) needsRebuild = true;
    }

    private Draft draft() {
        return drafts.computeIfAbsent(selected, _ -> new Draft());
    }

    private static String shortName(String deck) {
        int i = deck.lastIndexOf("::");
        return i < 0 ? deck : deck.substring(i + 2);
    }

    private static @Nullable String parentOf(String deck) {
        int i = deck.lastIndexOf("::");
        return i < 0 ? null : deck.substring(0, i);
    }

    /** The model this deck gets when its own is left on Default: the nearest parent's, or the main one. */
    private String inheritedModel() {
        for (String name = parentOf(selected); name != null; name = parentOf(name)) {
            Draft draft = drafts.get(name);
            if (draft != null && !draft.model.isBlank()) return draft.model;
        }
        return config.aiGrading().model();
    }

    /** The time between cards this deck gets when its own is left on Default: the nearest parent's, or the main one. */
    private int inheritedInterval() {
        for (String name = parentOf(selected); name != null; name = parentOf(name)) {
            Draft draft = drafts.get(name);
            if (draft != null && draft.intervalSeconds > 0) return draft.intervalSeconds;
        }
        return FileManager.getConfig().intervalTotalSeconds();
    }

    private static String formatInterval(int totalSeconds) {
        int minutes = totalSeconds / 60, seconds = totalSeconds % 60;
        if (minutes == 0) return seconds + " sec";
        return seconds == 0 ? minutes + " min" : minutes + " min " + seconds + " sec";
    }

    /** The player edited the minutes/seconds boxes: keep the time if it's valid, show red if not. */
    private void timeTyped() {
        if (fillingTimeBoxes || selected.isBlank()) return;
        int total = -1;
        try {
            int minutes = minutesBox.getValue().isBlank() ? 0 : Integer.parseInt(minutesBox.getValue().trim());
            int seconds = secondsBox.getValue().isBlank() ? 0 : Integer.parseInt(secondsBox.getValue().trim());
            if (minutes >= 0 && seconds >= 0 && seconds <= 59 && minutes * 60 + seconds >= 1) total = minutes * 60 + seconds;
        }
        catch (NumberFormatException ignored) {
            // shown in red below
        }
        int color = total > 0 ? CommonColors.WHITE : CommonColors.SOFT_RED;
        minutesBox.setTextColor(color);
        secondsBox.setTextColor(color);
        if (total > 0) draft().intervalSeconds = total; // an invalid entry keeps the last valid time
    }

    /** The prompt text this deck gets when its own is left on Default, and what to call it. */
    private String[] inheritedPrompt() {
        for (String name = parentOf(selected); name != null; name = parentOf(name)) {
            Draft draft = drafts.get(name);
            if (draft != null && !draft.prompt.isBlank()) return new String[] {draft.prompt, "parent deck's"};
        }
        String main = FileManager.getConfig().aiGrading().prompt();
        return new String[] {main.isBlank() ? GradingPrompt.DEFAULT_TEMPLATE : main, "main"};
    }

    /** The sizes this deck gets when its own are left on Default: the nearest parent's, or the default ones. */
    private ModConfig.CardLayout inheritedLayout() {
        for (String name = parentOf(selected); name != null; name = parentOf(name)) {
            Draft draft = drafts.get(name);
            if (draft != null && draft.layout != null) return draft.layout;
        }
        return FileManager.getConfig().cardLayout();
    }

    /**
     * The saved config with the parent decks' unsaved choices applied, so "Default (...)" shows what the selected
     * deck would really get. The selected deck itself is left out, so its own choice doesn't count as its default.
     */
    private ModConfig withDraftsAboveSelected() {
        List<ModConfig.DeckProfile> profiles = new ArrayList<>();
        drafts.forEach((deck, draft) -> {
            if (deck.isBlank() || deck.equals(selected) || draft.autoGrades == null) return;
            profiles.add(ModConfig.DeckProfile.empty(deck).withAutoGrades(draft.autoGrades));
        });
        return FileManager.getConfig().withDeckProfiles(profiles);
    }

    private void openCardSize() {
        Draft draft = draft();
        Minecraft.getInstance().gui.setScreen(new CardLayoutScreen("Card Size · " + shortName(selected), draft.layout, inheritedLayout(), true,
                layout -> draft.layout = layout));
    }

    /** The deck's notes, with the whole-prompt editor reachable from there as the advanced option. */
    private void openNotes() {
        Draft draft = draft();
        ModConfig.AiGrading main = FileManager.getConfig().aiGrading();
        String model = draft.model.isBlank() ? inheritedModel() : draft.model;
        Minecraft.getInstance().gui.setScreen(new DeckNotesScreen(selected, shortName(selected), draft.notes, main.withModel(model),
                notes -> draft.notes = notes, this::openPromptEditor, () -> !draft.prompt.isBlank()));
    }

    /** Advanced: this deck's own whole prompt. Opened from the notes screen, and returns there. */
    private void openPromptEditor() {
        Draft draft = draft();
        String[] inherited = inheritedPrompt();
        Minecraft.getInstance().gui.setScreen(new PromptEditorScreen("Whole AI prompt · " + shortName(selected), draft.prompt,
                inherited[0], inherited[1], text -> draft.prompt = text));
    }

    private void openRewards() {
        Draft draft = draft();
        Minecraft.getInstance().gui.setScreen(RewardsConfigScreen.forDeck(shortName(selected), !draft.customRewards, draft.rewards,
                (sameAsMain, rewards) -> {
                    draft.customRewards = !sameAsMain;
                    draft.rewards = sameAsMain ? NO_REWARDS : rewards;
                }));
    }

    private void openModelPicker() {
        Draft draft = draft();
        Minecraft.getInstance().gui.setScreen(new ModelPickerScreen("AI model · " + shortName(selected), models, draft.model,
                inheritedModel(), model -> draft.model = model));
    }

    @Override
    public void tick() {
        super.tick();
        if (needsRebuild) {
            needsRebuild = false;
            rebuildWidgets();
        }
    }

    @Override
    protected void init() {
        doneButton.setTooltip(Tooltip.create(Component.literal("Save all deck presets and go back")));
        initialized = true;
        int gap = 10;
        int column = Math.max(150, Math.min(240, (width - 20 - gap) / 2));
        int leftX = width / 2 - gap / 2 - column;
        int rightX = width / 2 + gap / 2;
        int top = 26;

        titleText.setPosition(width / 2 - titleText.getWidth() / 2, 8);
        addRenderableWidget(titleText);
        int doneY = height - 26;
        doneButton.setPosition(width / 2 - doneButton.getWidth() / 2, doneY);
        addRenderableWidget(doneButton);
        int contentBottom = doneY - 8;

        // left: decks
        int y = top;
        if (!statusText.getMessage().getString().isEmpty()) {
            statusText.setMaxWidth(column);
            statusText.setPosition(leftX + (column - statusText.getWidth()) / 2, y);
            addRenderableWidget(statusText);
            y += statusText.getHeight() + 4;
        }
        deckList.updateSizeAndPosition(column, Math.max(3 * 14 + 4, contentBottom - y), leftX, y);
        addRenderableWidget(deckList);

        // right: the picked deck's preset
        y = top;
        hintText.setMaxWidth(column);
        if (selected.isBlank()) {
            hintText.setMessage(Component.literal("Pick a deck on the left to give it its own AI model, notes for the AI, rewards, time between cards or card size.").withColor(GRAY));
            hintText.setPosition(rightX + (column - hintText.getWidth()) / 2, y + 20);
            addRenderableWidget(hintText);
            return;
        }
        Draft draft = draft();

        deckTitle.setMessage(Component.literal(shortName(selected)).withColor(CommonColors.WHITE));
        deckTitle.setMaxWidth(column);
        deckTitle.setPosition(rightX + (column - deckTitle.getWidth()) / 2, y + 1);
        addRenderableWidget(deckTitle);
        y += 14;

        String inherited = inheritedModel();
        modelButton.setMessage(Component.literal("AI model: " + (draft.model.isBlank()
                ? "Default (" + (inherited.isBlank() ? "none" : inherited) + ")" : draft.model)));
        modelButton.setRectangle(column, 20, rightX, y);
        addRenderableWidget(modelButton);
        y += 24;

        String notesState = draft.notes.isBlank() ? "None" : "Set";
        if (!draft.prompt.isBlank()) notesState += " + own prompt";
        notesButton.setMessage(Component.literal("AI deck notes: " + notesState + "..."));
        notesButton.setRectangle(column, 20, rightX, y);
        addRenderableWidget(notesButton);
        y += 24;

        String rewards = !draft.customRewards ? "Same as main" : draft.rewards.presetName().isBlank() ? "Custom" : draft.rewards.presetName();
        rewardsButton.setMessage(Component.literal("Rewards: " + rewards));
        rewardsButton.setRectangle(column, 20, rightX, y);
        addRenderableWidget(rewardsButton);
        y += 24;

        boolean ownTime = draft.intervalSeconds > 0;
        timeButton.setMessage(Component.literal("Time between cards: " + (ownTime ? "Own" : "Default (" + formatInterval(inheritedInterval()) + ")")));
        timeButton.setRectangle(column, 20, rightX, y);
        addRenderableWidget(timeButton);
        y += 24;
        if (ownTime) {
            String show = selected + "\n" + draft.intervalSeconds;
            if (!show.equals(timeBoxesShow) && !minutesBox.isFocused() && !secondsBox.isFocused()) { // don't overwrite what's being typed
                fillingTimeBoxes = true;
                minutesBox.setValue(String.valueOf(draft.intervalSeconds / 60));
                secondsBox.setValue(String.valueOf(draft.intervalSeconds % 60));
                minutesBox.setTextColor(CommonColors.WHITE);
                secondsBox.setTextColor(CommonColors.WHITE);
                fillingTimeBoxes = false;
            }
            timeBoxesShow = show;
            int rowWidth = everyLabel.getWidth() + 4 + 30 + 3 + minutesLabel.getWidth() + 6 + 30 + 3 + secondsLabel.getWidth();
            int x = rightX + (column - rowWidth) / 2;
            everyLabel.setPosition(x, y + 6);
            x += everyLabel.getWidth() + 4;
            minutesBox.setPosition(x, y);
            x += 30 + 3;
            minutesLabel.setPosition(x, y + 6);
            x += minutesLabel.getWidth() + 6;
            secondsBox.setPosition(x, y);
            x += 30 + 3;
            secondsLabel.setPosition(x, y + 6);
            addRenderableWidget(everyLabel);
            addRenderableWidget(minutesBox);
            addRenderableWidget(minutesLabel);
            addRenderableWidget(secondsBox);
            addRenderableWidget(secondsLabel);
            y += 24;
        }
        else timeBoxesShow = "";

        boolean ownAuto = draft.autoGrades != null;
        ModConfig.AutoGrades shownAuto = ownAuto ? draft.autoGrades : DeckSettings.forDeck(withDraftsAboveSelected(), selected).autoGrades();
        autoGradeLabel.setMessage(Component.literal("Auto-grade: ").withColor(GRAY)
                .append(ownAuto ? Component.literal("own").withColor(CommonColors.GREEN) : Component.literal("default").withColor(GRAY)));
        autoGradeLabel.setTooltip(Tooltip.create(Component.literal(
                "Which AI grades are applied on their own when this deck's card is graded while you play. "
                + "Switched-off grades bring the card back so you see what you missed.\n"
                + (ownAuto ? "This deck has its own choice; ↺ goes back to the default." : "Showing the default; click a switch to give this deck its own."))));
        autoGradeLabel.setPosition(rightX, y + 2);
        addRenderableWidget(autoGradeLabel);
        if (ownAuto) {
            autoGradeResetButton.setPosition(rightX + column - autoGradeResetButton.getWidth(), y);
            addRenderableWidget(autoGradeResetButton);
        }
        y += 13;
        GradeToggles.show(autoGradeToggles, shownAuto);
        y += GradeToggles.place(autoGradeToggles, rightX, y, column) + 3;
        autoGradeToggles.forEach(this::addRenderableWidget);

        sizeButton.setMessage(Component.literal("Card size: " + (draft.layout == null ? "Default" : "Own") + "..."));
        sizeButton.setRectangle(column, 20, rightX, y);
        addRenderableWidget(sizeButton);
        y += 24;

        clearButton.active = !draft.isDefault();
        clearButton.setRectangle(column, 20, rightX, y);
        addRenderableWidget(clearButton);
        y += 26;

        hintText.setMessage(Component.literal("Anything left on Default follows the parent deck, then the main settings. "
                + "Subdecks use this preset too, unless they have their own.").withColor(GRAY));
        hintText.setPosition(rightX + (column - hintText.getWidth()) / 2, y);
        if (y + hintText.getHeight() <= contentBottom) addRenderableWidget(hintText);
    }

    @Override
    public void onClose() {
        List<ModConfig.DeckProfile> profiles = new ArrayList<>();
        drafts.forEach((deck, draft) -> {
            if (deck.isBlank() || draft.isDefault()) return;
            profiles.add(new ModConfig.DeckProfile(deck, draft.model, draft.prompt, draft.customRewards, draft.rewards.presetName(),
                    draft.rewards.correct(), draft.rewards.wrong(), draft.rewards.strategy(), draft.intervalSeconds, draft.notes, draft.layout, draft.autoGrades));
        });
        FileManager.updateConfig(FileManager.getConfig().withDeckProfiles(profiles));
        QuestionScheduler.deckPresetsChanged(); // a running countdown follows a changed time straight away
        Minecraft.getInstance().gui.setScreen(parent);
    }
}
