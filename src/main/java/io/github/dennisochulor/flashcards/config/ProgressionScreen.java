package io.github.dennisochulor.flashcards.config;

import io.github.dennisochulor.flashcards.FileManager;
import io.github.dennisochulor.flashcards.anki.AnkiSession;
import io.github.dennisochulor.flashcards.progress.ProgressStore;
import io.github.dennisochulor.flashcards.progress.Progression;
import io.github.dennisochulor.flashcards.progress.ProgressionPresets;
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
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Rewards & Penalties: the score system's editor.
 * <p>
 * Left: "Main setup" and your decks (a dot marks decks with their own setup, the number on the right is their score).
 * Right: the selected setup. A tab per grade (Again, Hard, Good, Easy) with its points and its rules, presets
 * (built-in and your own, which you can also copy and paste as text), fading, timing, and the deck's current score.
 * A deck without its own setup follows its nearest parent's, or the main one; it can be given its own from a copy or a preset.
 */
public class ProgressionScreen extends Screen {
    private static final String MAIN = "Main setup";
    private static final int GRAY = 0xFFAAAAAA;
    private static final double[] FADE_CHOICES = {0, 6, 12, 24, 72, 168};

    private final Screen parent;
    private Progression.Setup main;
    private final Map<String, Progression.Setup> own = new HashMap<>();
    private String selected;
    private int grade = 1; // 1 = Again ... 4 = Easy
    private int selectedRule = -1;
    private ModConfig.ScoreDisplay display;
    private List<String> ankiDecks = List.of();
    private boolean needsRebuild = false;
    private String status = "";
    private int statusColor = GRAY;

    private final StringWidget titleText = new StringWidget(Component.literal("Rewards & Penalties"), Minecraft.getInstance().font);
    private final DeckListWidget deckList;
    private final StringWidget headerText = new StringWidget(Component.empty(), Minecraft.getInstance().font);
    private final StringWidget stateText = new StringWidget(Component.empty(), Minecraft.getInstance().font);
    private final StringWidget scoreText = new StringWidget(Component.empty(), Minecraft.getInstance().font);
    private final MultiLineTextWidget explanation = new MultiLineTextWidget(Component.empty(), Minecraft.getInstance().font);
    private final StringWidget statusText = new StringWidget(Component.empty(), Minecraft.getInstance().font);
    private final List<GradeToggles.Tab> tabs = new ArrayList<>();
    private final StringWidget pointsLabel = new StringWidget(Component.literal("Points").withColor(GRAY), Minecraft.getInstance().font);
    private final EditBox pointsBox = new EditBox(Minecraft.getInstance().font, 46, 20, Component.literal("Points"));
    private final RuleListWidget ruleList = new RuleListWidget(200, 60, this::editRule);
    private boolean fillingPoints = false;

    private final Button resetScoreButton = Button.builder(Component.literal("Reset score"), _ -> resetScore())
            .tooltip(Tooltip.create(Component.literal("Set this deck's score and counts back to 0. The setup itself doesn't change."))).build();
    private final Button copyMainButton = Button.builder(Component.empty(), _ -> makeOwn(null))
            .tooltip(Tooltip.create(Component.literal("Give this deck its own setup, starting as a copy of the one it uses now. Then change anything."))).build();
    private final Button fromPresetButton = Button.builder(Component.literal("Own setup from a preset..."), _ -> openPresets(true))
            .tooltip(Tooltip.create(Component.literal("Give this deck its own setup, starting from a built-in preset or one of yours."))).build();
    private final Button presetButton = Button.builder(Component.empty(), _ -> openPresets(false))
            .tooltip(Tooltip.create(Component.literal(
                    "Replace this setup with a preset: built-in ones (Classic ladder, Strikes, Treasure hunt...) or your own. "
                    + "Shows what each one does before you pick it."))).build();
    private final Button saveButton = Button.builder(Component.literal("Save as mine"), _ -> saveAsMine())
            .tooltip(Tooltip.create(Component.literal("Save this setup as one of your presets, to use on other decks."))).build();
    private final Button copyButton = Button.builder(Component.literal("Copy"), _ -> copyToClipboard())
            .tooltip(Tooltip.create(Component.literal("Copy this setup to the clipboard as text, to send to a friend (or keep somewhere)."))).build();
    private final Button pasteButton = Button.builder(Component.literal("Paste"), _ -> pasteFromClipboard())
            .tooltip(Tooltip.create(Component.literal("Read a setup someone copied. You'll see everything it runs before using or saving it."))).build();
    private final Button defaultButton = Button.builder(Component.literal("Back to default"), _ -> {
        own.remove(selected);
        setStatus("This deck follows " + inheritedSourceName() + " again.", GRAY);
        needsRebuild = true;
    }).tooltip(Tooltip.create(Component.literal("Drop this deck's own setup: it follows its parent deck's (or the main one) again."))).build();
    private final CycleButton<Progression.Overlap> overlapButton;
    private final Button addButton = Button.builder(Component.literal("Add rule"), _ -> editRule(-1))
            .tooltip(Tooltip.create(Component.literal("A new rule for this grade: when it runs (e.g. score -5 or lower) and which commands."))).build();
    private final Button editButton = Button.builder(Component.literal("Edit"), _ -> editRule(ruleList.selectedIndex()))
            .tooltip(Tooltip.create(Component.literal("Change the selected rule (double-clicking it works too)."))).build();
    private final Button deleteButton = Button.builder(Component.literal("Delete"), _ -> deleteRule())
            .tooltip(Tooltip.create(Component.literal("Remove the selected rule."))).build();
    private final Button upButton = Button.builder(Component.literal("▲"), _ -> moveRule(-1))
            .tooltip(Tooltip.create(Component.literal("Move the selected rule up. Rules run in this order; with \"only the strongest\", a later rule wins a tie."))).build();
    private final Button downButton = Button.builder(Component.literal("▼"), _ -> moveRule(1))
            .tooltip(Tooltip.create(Component.literal("Move the selected rule down."))).build();
    private final CycleButton<Double> fadeButton;
    private final Button timingButton = Button.builder(Component.literal("Timing..."), _ -> openTiming())
            .tooltip(Tooltip.create(Component.literal(
                    "How much points count by how long ago the card was last seen in Anki: a card you knew for weeks is worth more "
                    + "(and costs more to forget) than one you saw a minute ago."))).build();
    private final CycleButton<Boolean> hudButton;
    private final CycleButton<Boolean> cardButton;
    private final Button doneButton = Button.builder(Component.literal("Done"), _ -> this.onClose())
            .tooltip(Tooltip.create(Component.literal("Save everything and go back"))).build();

    /** @param preselect the deck to show first (e.g. coming from Deck Presets), or null for the main setup */
    public ProgressionScreen(@Nullable String preselect) {
        super(Component.literal("Rewards & Penalties"));
        this.parent = Objects.requireNonNull(Minecraft.getInstance().gui.screen());
        ModConfig config = FileManager.getConfig();
        this.main = config.progression();
        for (ModConfig.DeckProfile profile : config.deckProfiles()) {
            if (profile.progression() != null) own.put(profile.deck(), profile.progression());
        }
        this.selected = preselect == null || preselect.isBlank() ? MAIN : preselect;
        this.display = config.scoreDisplay();

        deckList = new DeckListWidget(160, 100, 0, row -> {
            selected = row;
            selectedRule = -1;
            status = "";
            needsRebuild = true;
        });
        deckList.keepOrder();
        deckList.markInsteadOfTick(own::containsKey);
        deckList.setDetail(row -> row.equals(MAIN) ? "" : scoreLabel(row), row -> {
            double score = currentScore(row);
            return score < 0 ? 0xFFFF9090 : score > 0 ? 0xFF8FE0A0 : GRAY;
        });

        for (int i = 0; i < 4; i++) {
            tabs.add(new GradeToggles.Tab(i, i == 0, g -> {
                grade = g;
                selectedRule = -1;
                needsRebuild = true;
            }));
        }

        ruleList.onSelect(index -> selectedRule = index);
        pointsBox.setMaxLength(6);
        pointsBox.setTooltip(Tooltip.create(Component.literal(
                "How much this grade changes the deck's score (before the timing multiplier). Negative lowers it, 0 leaves it alone.")));
        pointsBox.setResponder(this::pointsTyped);

        overlapButton = new CycleButton.Builder<Progression.Overlap>(o -> Component.literal(o.friendlyName), () -> Progression.Overlap.ALL)
                .withValues(Progression.Overlap.values())
                .withTooltip(_ -> Tooltip.create(Component.literal(
                        "When several of this grade's rules hold at once (e.g. score -6 with rules for -1 and -5):\n"
                        + "All that match: they all run, effects stack.\nOnly the strongest: only the one with the furthest threshold runs.")))
                .create(0, 0, 150, 20, Component.literal("If several match"), (_, value) -> updateGrade(currentGrade().withOverlap(value)));
        List<Double> fades = new ArrayList<>();
        for (double f : FADE_CHOICES) fades.add(f);
        fadeButton = new CycleButton.Builder<Double>(ProgressionScreen::fadeLabel, () -> 0.0)
                .withValues(fades)
                .withTooltip(_ -> Tooltip.create(Component.literal(
                        "The score (and the grade counts) drift back toward 0 while you don't answer: halfway every this long. "
                        + "So a bad session yesterday doesn't count fully today. Off: nothing fades.")))
                .create(0, 0, 150, 20, Component.literal("Fading"), (_, value) -> {
                    Progression.Setup setup = current();
                    if (setup != null) setCurrent(setup.withFade(value));
                });
        hudButton = CycleButton.onOffBuilder(display.hud())
                .withTooltip(_ -> Tooltip.create(Component.literal("Show the deck's score in the study panel on the HUD.")))
                .create(0, 0, 150, 20, Component.literal("Score on HUD"), (_, value) -> display = new ModConfig.ScoreDisplay(value, display.card()));
        cardButton = CycleButton.onOffBuilder(display.card())
                .withTooltip(_ -> Tooltip.create(Component.literal(
                        "Show the score on cards: in the corner, what each grade would do under its button, and what happened after you grade.")))
                .create(0, 0, 150, 20, Component.literal("Score on cards"), (_, value) -> display = new ModConfig.ScoreDisplay(display.hud(), value));
        explanation.setCentered(true);

        refreshDecks();
        AnkiSession.loadDecks(config.ankiConnectUrl(), decks -> {
            ankiDecks = decks;
            refreshDecks();
        }, _ -> {});
    }

    private static Component fadeLabel(double hours) {
        if (hours <= 0) return Component.literal("Off");
        if (hours >= 24 && hours % 24 == 0) return Component.literal((long) (hours / 24) + (hours == 24 ? " day" : " days"));
        return Component.literal((long) hours + " hours");
    }

    // ---------------------------------------------------------------- what's selected

    /** @return the selected setup to edit, or null for a deck on Default */
    private Progression.@Nullable Setup current() {
        return selected.equals(MAIN) ? main : own.get(selected);
    }

    private void setCurrent(Progression.Setup setup) {
        if (selected.equals(MAIN)) main = setup;
        else own.put(selected, setup);
        needsRebuild = true;
    }

    private Progression.GradeSetup currentGrade() {
        Progression.Setup setup = current();
        return setup == null ? new Progression.GradeSetup(0.0, null, null) : setup.grade(grade);
    }

    private void updateGrade(Progression.GradeSetup updated) {
        Progression.Setup setup = current();
        if (setup != null) setCurrent(setup.withGrade(grade, updated));
    }

    private static @Nullable String parentOf(String deck) {
        int i = deck.lastIndexOf("::");
        return i < 0 ? null : deck.substring(0, i);
    }

    /** The deck whose setup the selected deck uses while on Default, or null for the main setup. */
    private @Nullable String inheritedSource() {
        for (String name = parentOf(selected); name != null; name = parentOf(name)) {
            if (own.containsKey(name)) return name;
        }
        return null;
    }

    private String inheritedSourceName() {
        String source = inheritedSource();
        return source == null ? "the main setup" : "\"" + source + "\"";
    }

    /** The setup a deck actually uses (its own, its parent's, or the main one). */
    private Progression.Setup effective(String deck) {
        if (deck.equals(MAIN)) return main;
        for (String name = deck; name != null; name = parentOf(name)) {
            Progression.Setup setup = own.get(name);
            if (setup != null) return setup;
        }
        return main;
    }

    private static String scoreKey(String row) {
        return row.equals(MAIN) ? ProgressStore.OWN_QUESTIONS : row;
    }

    private double currentScore(String row) {
        return ProgressStore.get(scoreKey(row)).fadedTo(System.currentTimeMillis(), effective(row).fadeHalfLifeHours()).score();
    }

    private String scoreLabel(String row) {
        Progression.DeckState state = ProgressStore.get(scoreKey(row));
        return state.lastUpdateMillis() == 0 ? "" : Progression.formatScore(currentScore(row));
    }

    private void refreshDecks() {
        TreeSet<String> decks = new TreeSet<>(DeckListWidget.TREE_ORDER);
        decks.addAll(ankiDecks);
        decks.addAll(own.keySet());
        String configured = FileManager.getConfig().ankiDeck();
        if (!configured.isBlank()) decks.add(configured);
        if (!selected.equals(MAIN)) decks.add(selected);
        List<String> rows = new ArrayList<>();
        rows.add(MAIN);
        rows.addAll(decks);
        deckList.setDecks(rows, selected);
    }

    private void setStatus(String text, int color) {
        status = text;
        statusColor = color;
        needsRebuild = true;
    }

    // ---------------------------------------------------------------- actions

    private void resetScore() {
        ProgressStore.reset(scoreKey(selected));
        setStatus("Score reset to 0.", CommonColors.GREEN);
    }

    /** @param preset the preset to start from, or null to copy what the deck uses now */
    private void makeOwn(Progression.@Nullable Setup preset) {
        Progression.Setup start = preset != null ? preset : effective(selected);
        own.put(selected, start);
        setStatus("Own setup now: changes affect this deck and its subdecks without their own.", CommonColors.GREEN);
        needsRebuild = true;
    }

    private void openPresets(boolean makeOwn) {
        Minecraft.getInstance().gui.setScreen(new PresetPickerScreen(setup -> {
            if (makeOwn) makeOwn(setup);
            else setCurrent(setup);
            setStatus("Using the preset \"" + setup.presetName() + "\".", CommonColors.GREEN);
        }));
    }

    private void saveAsMine() {
        Progression.Setup setup = current();
        if (setup != null) Minecraft.getInstance().gui.setScreen(new PresetSaveScreen(setup, false, _ -> {}));
    }

    private void copyToClipboard() {
        Progression.Setup setup = current();
        if (setup == null) return;
        Minecraft.getInstance().keyboardHandler.setClipboard(ProgressionPresets.toShareText(setup));
        setStatus("Copied to the clipboard. Paste it anywhere to share it.", CommonColors.GREEN);
    }

    private void pasteFromClipboard() {
        Progression.Setup pasted = ProgressionPresets.fromShareText(Minecraft.getInstance().keyboardHandler.getClipboard());
        if (pasted == null) {
            setStatus("The clipboard doesn't hold a preset. Copy one with \"Copy\" first.", CommonColors.SOFT_YELLOW);
            return;
        }
        Minecraft.getInstance().gui.setScreen(new PresetSaveScreen(pasted, true, setup -> {
            setCurrent(setup);
            setStatus("Using the pasted preset.", CommonColors.GREEN);
        }));
    }

    private void openTiming() {
        Progression.Setup setup = current();
        if (setup == null) return;
        Minecraft.getInstance().gui.setScreen(new TimeBandsScreen(setup.bands(), setup.newCardMultiplier(), (bands, newCard) -> {
            Progression.Setup now = current();
            if (now != null) setCurrent(now.withTiming(bands, newCard));
        }));
    }

    private void editRule(int index) {
        List<Progression.Rule> rules = currentGrade().rules();
        Progression.Rule rule = index >= 0 && index < rules.size() ? rules.get(index) : null;
        if (index >= 0 && rule == null) return;
        Minecraft.getInstance().gui.setScreen(new ProgressionRuleScreen(grade, rule, saved -> {
            List<Progression.Rule> updated = new ArrayList<>(currentGrade().rules());
            if (rule == null) {
                updated.add(saved);
                selectedRule = updated.size() - 1;
            }
            else {
                updated.set(index, saved);
                selectedRule = index;
            }
            updateGrade(currentGrade().withRules(updated));
        }));
    }

    private void deleteRule() {
        int index = ruleList.selectedIndex();
        List<Progression.Rule> updated = new ArrayList<>(currentGrade().rules());
        if (index < 0 || index >= updated.size()) return;
        updated.remove(index);
        selectedRule = Math.min(index, updated.size() - 1);
        updateGrade(currentGrade().withRules(updated));
    }

    private void moveRule(int by) {
        int index = ruleList.selectedIndex();
        List<Progression.Rule> updated = new ArrayList<>(currentGrade().rules());
        int target = index + by;
        if (index < 0 || target < 0 || target >= updated.size()) return;
        Progression.Rule rule = updated.remove(index);
        updated.add(target, rule);
        selectedRule = target;
        updateGrade(currentGrade().withRules(updated));
    }

    private void pointsTyped(String text) {
        if (fillingPoints) return;
        try {
            double points = Double.parseDouble(text.strip().replace(',', '.'));
            pointsBox.setTextColor(CommonColors.WHITE);
            Progression.Setup setup = current();
            if (setup != null && points != currentGrade().points()) {
                // not setCurrent(): rebuilding now would take the typing focus away
                Progression.Setup updated = setup.withGrade(grade, currentGrade().withPoints(points));
                if (selected.equals(MAIN)) main = updated;
                else own.put(selected, updated);
                tabs.get(grade - 1).setDetail(Progression.formatScore(points));
                presetButton.setMessage(presetLabel(updated));
            }
        }
        catch (NumberFormatException e) {
            pointsBox.setTextColor(CommonColors.SOFT_RED);
        }
    }

    private static Component presetLabel(Progression.Setup setup) {
        return Component.literal("Preset: " + (setup.presetName().isBlank() ? "Custom" : setup.presetName()) + "...");
    }

    @Override
    public void tick() {
        super.tick();
        if (needsRebuild) {
            needsRebuild = false;
            refreshDecks();
            rebuildWidgets();
        }
    }

    // ---------------------------------------------------------------- layout

    @Override
    protected void init() {
        int gap = 10;
        int leftWidth = Math.max(130, Math.min(180, (width - 30) / 3));
        int rightWidth = Math.min(340, width - 20 - leftWidth - gap);
        int leftX = width / 2 - (leftWidth + gap + rightWidth) / 2;
        int x = leftX + leftWidth + gap;
        int top = 22;
        int bottomY = height - 26;

        titleText.setPosition(width / 2 - titleText.getWidth() / 2, 7);
        addRenderableWidget(titleText);

        // bottom row: where the score shows, and Done
        int third = (rightWidth + leftWidth + gap - 8) / 3;
        hudButton.setRectangle(third, 20, leftX, bottomY);
        cardButton.setRectangle(third, 20, leftX + third + 4, bottomY);
        doneButton.setRectangle(leftWidth + gap + rightWidth - 2 * (third + 4), 20, leftX + 2 * (third + 4), bottomY);
        addRenderableWidget(hudButton);
        addRenderableWidget(cardButton);
        addRenderableWidget(doneButton);
        int contentBottom = bottomY - 6;

        deckList.updateSizeAndPosition(leftWidth, contentBottom - top, leftX, top);
        addRenderableWidget(deckList);

        // right: header, state, score
        int y = top;
        headerText.setMessage(Component.literal(selected.equals(MAIN) ? MAIN : shortName(selected)).withColor(CommonColors.WHITE));
        headerText.setMaxWidth(rightWidth);
        headerText.setPosition(x, y);
        addRenderableWidget(headerText);
        Progression.Setup setup = current();
        stateText.setMessage(selected.equals(MAIN)
                ? Component.literal("for decks without their own, and your own question lists").withColor(GRAY)
                : setup != null ? Component.literal("own setup").withColor(CommonColors.GREEN)
                : Component.literal("follows " + inheritedSourceName()).withColor(GRAY));
        boolean ownDeck = setup != null && !selected.equals(MAIN);
        stateText.setMaxWidth(rightWidth - headerText.getWidth() - 6 - (ownDeck ? 96 : 0));
        stateText.setPosition(x + headerText.getWidth() + 6, y);
        addRenderableWidget(stateText);
        if (ownDeck) {
            defaultButton.setRectangle(90, 16, x + rightWidth - 90, y - 4);
            addRenderableWidget(defaultButton);
        }
        y += 14;

        Progression.DeckState state = ProgressStore.get(scoreKey(selected)).fadedTo(System.currentTimeMillis(), effective(selected).fadeHalfLifeHours());
        StringBuilder score = new StringBuilder("Score " + Progression.formatScore(state.score()));
        for (int g = 1; g <= 4; g++) {
            long count = Math.round(state.count(g));
            if (count > 0) score.append(" · ").append(Progression.GRADE_NAMES[g - 1]).append(' ').append(count).append('×');
        }
        if (selected.equals(MAIN)) score.append("  (own questions)");
        scoreText.setMessage(Component.literal(score.toString()).withColor(state.score() < 0 ? 0xFFFF9090 : state.score() > 0 ? 0xFF8FE0A0 : GRAY));
        resetScoreButton.setRectangle(76, 16, x + rightWidth - 76, y);
        scoreText.setMaxWidth(rightWidth - 82);
        scoreText.setPosition(x, y + 4);
        addRenderableWidget(scoreText);
        addRenderableWidget(resetScoreButton);
        y += 20;

        if (!status.isEmpty()) {
            statusText.setMessage(Component.literal(status).withColor(statusColor));
            statusText.setMaxWidth(rightWidth);
            statusText.setPosition(x, y);
            addRenderableWidget(statusText);
            y += 12;
        }

        if (setup == null) { // a deck on Default: offer to give it its own
            explanation.setMessage(Component.literal("This deck uses " + inheritedSourceName() + ": the same points and rules. "
                    + "Its score is its own either way. Give it its own setup to make it different.").withColor(GRAY));
            explanation.setMaxWidth(rightWidth);
            explanation.setPosition(x + (rightWidth - explanation.getWidth()) / 2, y + 4);
            addRenderableWidget(explanation);
            y += explanation.getHeight() + 12;
            copyMainButton.setMessage(Component.literal("Own setup, copied from " + (inheritedSource() == null ? "main" : shortName(Objects.requireNonNull(inheritedSource())))));
            copyMainButton.setRectangle(rightWidth, 20, x, y);
            fromPresetButton.setRectangle(rightWidth, 20, x, y + 24);
            addRenderableWidget(copyMainButton);
            addRenderableWidget(fromPresetButton);
            return;
        }

        // presets: [Preset: name...] [Save as mine...] [Copy] [Paste]
        int half = (rightWidth - 4) / 2;
        int small = 42, save = 84;
        presetButton.setMessage(presetLabel(setup));
        presetButton.setRectangle(rightWidth - save - 2 * small - 12, 20, x, y);
        saveButton.setRectangle(save, 20, x + rightWidth - save - 2 * small - 8, y);
        copyButton.setRectangle(small, 20, x + rightWidth - 2 * small - 4, y);
        pasteButton.setRectangle(small, 20, x + rightWidth - small, y);
        addRenderableWidget(presetButton);
        addRenderableWidget(saveButton);
        addRenderableWidget(copyButton);
        addRenderableWidget(pasteButton);
        y += 26;

        // grade tabs
        for (int i = 0; i < 4; i++) {
            tabs.get(i).setSelected(i == grade - 1);
            tabs.get(i).setDetail(Progression.formatScore(setup.grade(i + 1).points()));
        }
        y += GradeToggles.place(tabs, x, y, rightWidth) + 4;
        tabs.forEach(this::addRenderableWidget);

        // the grade: points and overlap
        Progression.GradeSetup gradeSetup = setup.grade(grade);
        pointsLabel.setPosition(x, y + 6);
        addRenderableWidget(pointsLabel);
        fillingPoints = true;
        String pointsText = Progression.formatScore(gradeSetup.points()).replace("+", "");
        if (!pointsBox.getValue().equals(pointsText)) pointsBox.setValue(pointsText);
        pointsBox.setTextColor(CommonColors.WHITE);
        fillingPoints = false;
        pointsBox.setPosition(x + pointsLabel.getWidth() + 4, y);
        addRenderableWidget(pointsBox);
        int overlapX = x + pointsLabel.getWidth() + 4 + 46 + 6;
        overlapButton.setValue(gradeSetup.overlap());
        overlapButton.setRectangle(x + rightWidth - overlapX, 20, overlapX, y);
        addRenderableWidget(overlapButton);
        y += 24;

        // fading and timing (whole setup), at the bottom of the column
        int fadeY = contentBottom - 20;
        fadeButton.setValue(fadeChoice(setup.fadeHalfLifeHours()));
        fadeButton.setRectangle(half, 20, x, fadeY);
        timingButton.setRectangle(half, 20, x + half + 4, fadeY);
        addRenderableWidget(fadeButton);
        addRenderableWidget(timingButton);

        // rule buttons above that, the list takes the rest
        int buttonsY = fadeY - 24;
        int arrow = 20;
        int third2 = (rightWidth - 2 * arrow - 4 * 4) / 3;
        addButton.setRectangle(third2, 20, x, buttonsY);
        editButton.setRectangle(third2, 20, x + third2 + 4, buttonsY);
        deleteButton.setRectangle(third2, 20, x + 2 * (third2 + 4), buttonsY);
        upButton.setRectangle(arrow, 20, x + rightWidth - 2 * arrow - 4, buttonsY);
        downButton.setRectangle(arrow, 20, x + rightWidth - arrow, buttonsY);
        addRenderableWidget(addButton);
        addRenderableWidget(editButton);
        addRenderableWidget(deleteButton);
        addRenderableWidget(upButton);
        addRenderableWidget(downButton);

        ruleList.setRules(gradeSetup.rules(), selectedRule);
        ruleList.updateSizeAndPosition(rightWidth, Math.max(30, buttonsY - 4 - y), x, y);
        addRenderableWidget(ruleList);
        if (gradeSetup.rules().isEmpty()) {
            explanation.setMessage(Component.literal("No rules for " + Progression.GRADE_NAMES[grade - 1]
                    + " yet: it only moves the score. \"Add rule\" makes it reward or punish.").withColor(GRAY));
            explanation.setMaxWidth(rightWidth - 20);
            explanation.setPosition(x + (rightWidth - explanation.getWidth()) / 2, y + 8);
            addRenderableOnly(explanation);
        }
    }

    private static double fadeChoice(double hours) {
        double best = FADE_CHOICES[0];
        for (double choice : FADE_CHOICES) if (Math.abs(choice - hours) < Math.abs(best - hours)) best = choice;
        return best;
    }

    private static String shortName(String deck) {
        int i = deck.lastIndexOf("::");
        return i < 0 ? deck : deck.substring(i + 2);
    }

    // ---------------------------------------------------------------- saving

    @Override
    public void onClose() {
        ModConfig config = FileManager.getConfig();
        Map<String, ModConfig.DeckProfile> profiles = new HashMap<>();
        for (ModConfig.DeckProfile profile : config.deckProfiles()) profiles.put(profile.deck(), profile.withProgression(null));
        own.forEach((deck, setup) -> profiles.put(deck,
                profiles.getOrDefault(deck, ModConfig.DeckProfile.empty(deck)).withProgression(setup)));
        List<ModConfig.DeckProfile> list = new ArrayList<>();
        profiles.values().forEach(profile -> {
            if (!profile.isDefault()) list.add(profile);
        });
        list.sort((a, b) -> a.deck().toLowerCase(Locale.ROOT).compareTo(b.deck().toLowerCase(Locale.ROOT)));
        FileManager.updateConfig(config.withProgression(main).withDeckProfiles(list).withScoreDisplay(display));
        Minecraft.getInstance().gui.setScreen(parent);
    }
}
