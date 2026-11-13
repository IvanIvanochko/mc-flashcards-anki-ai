package io.github.dennisochulor.flashcards.questions;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.dennisochulor.flashcards.FileManager;
import io.github.dennisochulor.flashcards.Flashcards;
import io.github.dennisochulor.flashcards.IntSlider;
import io.github.dennisochulor.flashcards.ScoreSlider;
import io.github.dennisochulor.flashcards.ai.AiGrader;
import io.github.dennisochulor.flashcards.ai.GradingPrompt;
import io.github.dennisochulor.flashcards.ai.Ollama;
import io.github.dennisochulor.flashcards.anki.AnkiCard;
import io.github.dennisochulor.flashcards.anki.AnkiSession;
import io.github.dennisochulor.flashcards.config.DeckSettings;
import io.github.dennisochulor.flashcards.config.ModConfig;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.CommonColors;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Shows a card from Anki's review queue in a scrollable panel at normal text size; the grade is sent back to Anki as a
 * normal review. Again counts as a wrong answer for the reward/penalty commands, the other buttons count as correct.
 * <p>
 * Classic mode, like Anki: Space/Enter shows the answer, then 1-4 (or Space/Enter = Good) grades it.
 * The keys can be changed (Card Keys screen), and the keyboard is ignored for a moment when a card appears and until
 * keys that were already held are released, so mashing Space while running and jumping can't skip a card unseen.
 * <p>
 * AI grading mode: the player types an answer and a local AI grades it by meaning. The score unlocks the buttons
 * (Again always; Hard/Good/Easy from the configured cut-offs). "Show Answer" means "I don't know": only Again is offered.
 * An answer the AI finds too vague (and would fail) gets one second chance, with a hint about what to be specific about.
 * <p>
 * Layout: the question has its own panel that grows to fit and scrolls when the screen runs out; everything shown after
 * it (the answer, your typed answer, the AI's grade) is in a second panel that grows only up to a set share of the
 * screen and then scrolls on its own. The cog in the corner changes those sizes on the spot, for this deck or as the default.
 * Pictures are shown where they are on the card, and clicking one opens it in a zoomable viewer.
 * <p>
 * Mouse wheel and Page Up/Down scroll long cards (arrow keys too, when not typing).
 */
public class AnkiQuestionScreen extends Screen {
    private static final int AGAIN = 1;
    private static final int MIN_PANEL_HEIGHT = 30;
    private static final int PANEL_GAP = 4;
    private static final int POPOVER_WIDTH = 200;
    private static final int HEADER_HEIGHT = 22;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_GAP = 6;
    private static final int ANSWER_BOX_HEIGHT = 56;
    private static final int GRAY = 0xFFAAAAAA;
    private static final int EASY_BLUE = 0xFF55AAFF;
    private static final int ACCENT_BLUE = 0xFF6EA8FF;
    private static final int SECTION_GRAY = 0xFF8A93A6;
    private static final String[] METER_NAMES = {"Again", "Hard", "Good", "Easy"};
    private static final int[] METER_COLORS = {CommonColors.SOFT_RED, CommonColors.SOFT_YELLOW, CommonColors.GREEN, EASY_BLUE};

    /** A card the player left (Quit) while the AI was grading; it reopens once graded. */
    private static @Nullable AnkiQuestionScreen background = null;

    private enum Phase {
        /** Question shown; in AI mode the player is typing. */
        QUESTION,
        /** AI mode: the first answer was too vague, try once more. */
        SECOND_CHANCE,
        /** Classic mode after Show Answer: all buttons. */
        REVEALED,
        /** AI mode after Show Answer ("I don't know"): only Again. */
        GAVE_UP,
        /** Waiting for the AI. */
        GRADING,
        /** The AI couldn't grade: retry, or grade it yourself. */
        GRADE_ERROR,
        /** The AI's score decides which buttons are unlocked. */
        GRADED
    }

    private final AnkiCard card;
    /** The main settings with this card's deck preset applied (model, prompt). */
    private final DeckSettings settings;
    private final ModConfig.AiGrading ai;
    private final boolean aiMode;
    /** The question; grows to fit, scrolls when the screen runs out. */
    private final CardView questionView = new CardView(Minecraft.getInstance().font);
    /** Everything after the question; grows up to the layout's answer share of the screen, then scrolls. */
    private final CardView answerView = new CardView(Minecraft.getInstance().font);
    private boolean answerShown = false;
    /** The sizes in use on this card; starts as the deck's (or default) layout, changed live by the cog panel. */
    private ModConfig.CardLayout layout;
    private boolean layoutOpen = false;
    private boolean needsRelayout = false;
    private String layoutStatus = "";
    private int layoutStatusColor = GRAY;
    private int popoverX, popoverY, popoverHeight;
    private @Nullable IntSlider answerSizeSlider, imageSizeSlider, widthSlider;
    private final Button layoutButton;
    private final Button saveForDeckButton;
    private final Button saveAsDefaultButton;
    private final Button closeLayoutButton;
    private final StringWidget header;
    private final MultiLineEditBox answerBox;
    private final Button showAnswerButton;
    private final Button checkButton;
    private final Button retryButton;
    private final Button gradeMyselfButton;
    private final Button quitButton;
    private final List<Button> gradeButtons = new ArrayList<>();
    private final List<Integer> gradeEases = new ArrayList<>();
    private final List<String> gradeNames = new ArrayList<>();
    private @Nullable ScoreSlider scoreSlider = null;

    private Phase phase = Phase.QUESTION;
    private boolean answered = false;
    private int attempt = 1;
    private String typedAnswer = "";
    private String firstAnswer = "";
    private String secondChanceHint = "";
    private Ollama.@Nullable Grade aiGrade = null;
    private double score = 0;
    private String gradeError = "";
    private long gradingStartedMillis = 0;
    private int thinkingY = 0;
    private boolean quitShown = false;
    private final ModConfig.CardKeys keys = FileManager.getConfig().cardKeys();
    /** While true, the card's own keys (Space, Enter, the grade keys, Keep playing) are ignored, see {@link #updateInputLocks}. */
    private boolean actionLocked = true;
    /** While true, every other key (typing, scrolling) is ignored. Lifts much sooner than {@link #actionLocked}. */
    private boolean typingLocked = true;
    private long inputLockedAtMillis = System.currentTimeMillis();
    private long actionGraceMillis = keys.graceMillis();
    private long typingGraceMillis = keys.typingGraceMillis();
    private long backgroundSinceMillis = 0;
    /** True while this card's grade is waiting on the HUD to be applied on its own (see {@link #tickBackground}). */
    private boolean autoPending = false;
    private long autoRemainingMillis = 0;
    private long lastBackgroundTickMillis = 0;

    public AnkiQuestionScreen(AnkiCard card) {
        super(Component.literal("Anki card"));
        this.card = card;
        ModConfig config = FileManager.getConfig();
        this.settings = DeckSettings.forDeck(config, card.deckName());
        this.ai = settings.ai();
        // the AI only sees text: a card whose answer is just an image can't be graded by it
        this.aiMode = AiGrader.isEnabled(config, ai) && !card.answerForAi().isBlank();
        this.layout = settings.layout();
        Font font = Minecraft.getInstance().font;

        String deck = card.deckName().isBlank() ? "Anki" : card.deckName();
        header = new StringWidget(Component.literal(deck).withColor(GRAY), font);

        answerBox = MultiLineEditBox.builder()
                .setPlaceholder(Component.literal("Type your answer...  (Enter: check, Shift+Enter: new line)").withColor(GRAY))
                .build(font, 300, ANSWER_BOX_HEIGHT, Component.literal("Your answer"));
        answerBox.setCharacterLimit(4000);
        answerBox.setValueListener(_ -> updateCheckButton());

        showAnswerButton = Button.builder(Component.literal("Show Answer"), _ -> reveal())
                .tooltip(Tooltip.create(Component.literal(aiMode ? "I don't know: see the answer, it's graded as Again" : keyName(keys.confirm()) + " / Enter")))
                .build();
        checkButton = Button.builder(Component.literal("Check answer"), _ -> checkAnswer())
                .tooltip(Tooltip.create(Component.literal("Let the AI grade your answer (Enter)")))
                .build();
        retryButton = Button.builder(Component.literal("Retry"), _ -> checkAnswer()).build();
        gradeMyselfButton = Button.builder(Component.literal("Grade myself"), _ -> setPhase(Phase.REVEALED))
                .tooltip(Tooltip.create(Component.literal("Show the answer with all four buttons, like plain Anki")))
                .build();

        quitButton = Button.builder(Component.literal("Keep playing"), _ -> quitToGame())
                .tooltip(Tooltip.create(Component.literal("Play while the AI finishes; this card comes back when it's graded. (" + keyName(keys.keepPlaying()) + " / Esc)")))
                .size(90, 20).build();

        layoutButton = Button.builder(Component.literal("⚙"), _ -> {
                    layoutOpen = !layoutOpen;
                    layoutStatus = "";
                    needsRelayout = true;
                })
                .tooltip(Tooltip.create(Component.literal(
                        "Card size. Change, while looking at this card, how tall the answer area and the pictures may get and how wide the card is. "
                        + "Then keep it for this deck, or as the default.")))
                .size(18, 16).build();
        String deckLabel = card.deckName().isBlank() ? "this deck" : shortDeckName();
        saveForDeckButton = Button.builder(Component.literal("This deck"), _ -> saveLayoutForDeck())
                .tooltip(Tooltip.create(Component.literal(card.deckName().isBlank()
                        ? "This card's deck isn't known, so sizes can only be saved as the default."
                        : "Keep these sizes for \"" + deckLabel + "\" only (and its subdecks without sizes of their own). "
                          + "You can change or remove them later in Deck Presets.")))
                .build();
        saveForDeckButton.active = !card.deckName().isBlank();
        saveAsDefaultButton = Button.builder(Component.literal("Default"), _ -> saveLayoutAsDefault())
                .tooltip(Tooltip.create(Component.literal("Keep these sizes as the default, for every deck that doesn't have sizes of its own.")))
                .build();
        closeLayoutButton = Button.builder(Component.literal("Close"), _ -> {
                    layoutOpen = false;
                    needsRelayout = true;
                })
                .tooltip(Tooltip.create(Component.literal("Close this panel. Sizes you didn't save apply to this card only.")))
                .build();
        questionView.setAccent(ACCENT_BLUE);
        questionView.setOnImageClick(this::openPicture);
        answerView.setOnImageClick(this::openPicture);

        List<Integer> buttons = card.buttons().isEmpty() ? List.of(1, 2, 3, 4) : card.buttons();
        for (int i = 0; i < buttons.size(); i++) {
            int ease = buttons.get(i);
            String name = buttonName(ease, buttons.size());
            String interval = i < card.nextReviews().size() ? card.nextReviews().get(i) : "";
            Component label = Component.literal(name).withColor(buttonColor(name))
                    .append(interval.isEmpty() ? Component.empty() : Component.literal("  " + interval).withColor(CommonColors.LIGHT_GRAY));
            Button button = Button.builder(label, _ -> grade(ease)).build();
            gradeButtons.add(button);
            gradeEases.add(ease);
            gradeNames.add(name);
        }
        updateCheckButton();
        updateGradeButtons();

        // while the player types: load the model and let it read the instructions and this card
        if (aiMode) AiGrader.preload(ai, settings.systemPrompt(), card.questionForAi(), card.answerForAi());
    }

    /** Anki shows 4 buttons in the v3 scheduler; older schedulers can offer only Again/Good(/Easy) for learning cards. */
    private static String buttonName(int ease, int buttonCount) {
        if (buttonCount == 4) return switch (ease) { case 1 -> "Again"; case 2 -> "Hard"; case 3 -> "Good"; default -> "Easy"; };
        return switch (ease) { case 1 -> "Again"; case 2 -> "Good"; default -> "Easy"; };
    }

    /** 1-4 like Anki's 4-button layout, so names map to cut-offs and definitions even on 3-button cards. */
    private static int nameToEase4(String name) {
        return switch (name) { case "Again" -> 1; case "Hard" -> 2; case "Good" -> 3; default -> 4; };
    }

    private static String ease4ToName(int ease4) {
        return switch (ease4) { case 1 -> "Again"; case 2 -> "Hard"; case 3 -> "Good"; default -> "Easy"; };
    }

    private static int buttonColor(String name) {
        return switch (name) {
            case "Again" -> CommonColors.SOFT_RED;
            case "Hard" -> CommonColors.SOFT_YELLOW;
            case "Good" -> CommonColors.GREEN;
            default -> EASY_BLUE;
        };
    }

    private double minimumScore(String name) {
        return ai.minimumScore(nameToEase4(name));
    }

    private boolean isUnlocked(int index) {
        return switch (phase) {
            case REVEALED -> true;
            case GAVE_UP -> gradeEases.get(index) == AGAIN;
            case GRADED -> score >= minimumScore(gradeNames.get(index)) - 1e-9;
            default -> false;
        };
    }

    /** The button Enter picks: Good in classic mode, the best unlocked button after AI grading. */
    private int suggestedIndex() {
        if (phase == Phase.GRADED || phase == Phase.GAVE_UP) {
            for (int i = gradeEases.size() - 1; i >= 0; i--) if (isUnlocked(i)) return i;
            return 0;
        }
        return gradeEases.indexOf(gradeEases.size() == 4 ? 3 : 2);
    }

    private boolean isTyping() {
        return aiMode && (phase == Phase.QUESTION || phase == Phase.SECOND_CHANCE);
    }

    private void updateCheckButton() {
        checkButton.active = !answerBox.getValue().isBlank();
    }

    private void updateGradeButtons() {
        for (int i = 0; i < gradeButtons.size(); i++) {
            Button button = gradeButtons.get(i);
            boolean unlocked = isUnlocked(i);
            button.active = unlocked;
            String interval = i < card.nextReviews().size() ? card.nextReviews().get(i) : "";
            String name = gradeNames.get(i);
            StringBuilder tip = new StringBuilder();
            if (!unlocked && phase == Phase.GRADED) tip.append("Locked: needs a score of ").append(format(minimumScore(name))).append("\n\n");
            tip.append(GradingPrompt.DEFINITIONS[nameToEase4(name) - 1]).append("\n\nKey: ").append(keyName(gradeKey(i)));
            if (!interval.isEmpty()) tip.append(" · next review in ").append(interval);
            button.setTooltip(Tooltip.create(Component.literal(tip.toString())));
        }
    }

    private static String keyName(int keyCode) {
        return InputConstants.Type.KEYBOARD.getOrCreate(keyCode).getDisplayName().getString();
    }

    /** The key for the grade button at this position (1st to 4th), like Anki's 1-4. */
    private int gradeKey(int index) {
        return switch (index) { case 0 -> keys.again(); case 1 -> keys.hard(); case 2 -> keys.good(); default -> keys.easy(); };
    }

    // ---------------------------------------------------------------- stray key protection

    /**
     * Ignore the keyboard for a while. A card can appear while the player is sprinting and jumping, with W held and
     * Space being hammered, so:
     * <ul>
     * <li>the card's own keys (Space, Enter, the grade keys...) wait the full grace period, because one stray press
     * would flip or answer the card;</li>
     * <li>everything else (typing an answer) only waits a moment, so the player can start writing straight away.</li>
     * </ul>
     * When the card merely changes state (e.g. the answer was just shown), only a short pause on the card's keys is
     * needed, so that the key press that caused the change can't count twice.
     */
    private void lockInput(boolean cardJustAppeared) {
        actionLocked = true;
        typingLocked = cardJustAppeared;
        inputLockedAtMillis = System.currentTimeMillis();
        actionGraceMillis = cardJustAppeared ? keys.graceMillis() : Math.min(keys.graceMillis(), 300);
        typingGraceMillis = cardJustAppeared ? keys.typingGraceMillis() : 0;
    }

    /** @return how long the card's own keys stay ignored, 0 once the grace period is over */
    private long actionLockRemainingMillis() {
        return actionLocked ? Math.max(0, actionGraceMillis - (System.currentTimeMillis() - inputLockedAtMillis)) : 0;
    }

    /**
     * Lifts the locks when their time is up AND the keys the player was holding have been let go (a held key keeps
     * sending presses: W held from walking would type "wwww", a held Space would answer the card).
     * <p>
     * Called every frame, never from a key press: while a key press is being handled that key is of course down, so
     * checking "is a card key down?" there could never come out false, and the keyboard would stay dead.
     */
    private void updateInputLocks() {
        if (!actionLocked && !typingLocked) return;
        long elapsed = System.currentTimeMillis() - inputLockedAtMillis;
        var options = Minecraft.getInstance().options;
        if (typingLocked && elapsed >= typingGraceMillis
                && !anyDown(options.keyUp, options.keyDown, options.keyLeft, options.keyRight, options.keyJump)) {
            typingLocked = false;
        }
        if (actionLocked && elapsed >= actionGraceMillis && !anyCardKeyDown() && !anyDown(options.keyJump)) {
            actionLocked = false;
        }
    }

    private boolean anyCardKeyDown() {
        int[] cardKeys = {keys.confirm(), keys.again(), keys.hard(), keys.good(), keys.easy(), keys.keepPlaying(),
                InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER};
        for (int key : cardKeys) if (InputConstants.isKeyDown(key)) return true;
        return false;
    }

    /** @return whether any of these game controls is physically held down right now (keyboard keys only) */
    private static boolean anyDown(KeyMapping... mappings) {
        for (KeyMapping mapping : mappings) {
            InputConstants.Key bound = KeyMappingHelper.getBoundKeyOf(mapping);
            if (bound.getType() == InputConstants.Type.KEYBOARD && InputConstants.isKeyDown(bound.getValue())) return true;
        }
        return false;
    }

    /** Keys that do something to the card, as opposed to typing or scrolling. */
    private boolean isCardKey(int key) {
        if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) return true; // Enter: confirm / check
        if (isTyping()) return false; // while typing, Space and the digits are just characters
        return key == keys.confirm() || key == keys.keepPlaying() || key == InputConstants.KEY_ESCAPE
                || key == keys.again() || key == keys.hard() || key == keys.good() || key == keys.easy()
                || key == InputConstants.KEY_NUMPAD1 || key == InputConstants.KEY_NUMPAD2
                || key == InputConstants.KEY_NUMPAD3 || key == InputConstants.KEY_NUMPAD4;
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (typingLocked) return true; // letters from keys held since before the card appeared
        // a jumping player's Space shouldn't fill an empty answer with blanks
        if (isTyping() && event.codepoint() == ' ' && answerBox.getValue().isEmpty()) return true;
        return super.charTyped(event);
    }

    private static String format(double score) {
        return String.format(Locale.ROOT, "%.2f", score);
    }

    /** How tall a picture may be on the card, from the layout's share of the screen. */
    private int imageMaxHeight() {
        return Math.max(24, height * layout.imagePercent() / 100);
    }

    private String shortDeckName() {
        String deck = card.deckName();
        int i = deck.lastIndexOf("::");
        return i < 0 ? deck : deck.substring(i + 2);
    }

    private void openPicture(String imageName) {
        Minecraft.getInstance().gui.setScreen(new ImageViewerScreen(imageName)); // comes back to this card when closed
    }

    // ---------------------------------------------------------------- card content

    private static Component heading(String text) {
        return Component.literal(text).withColor(GRAY);
    }

    private static Component userText(String text) {
        return Component.literal(text.replace('§', '$')); // a typed § would be read as a formatting code
    }

    /** Text and pictures in the order they are on the card. */
    private void addParts(List<CardView.Block> blocks, List<AnkiCard.Part> parts) {
        for (AnkiCard.Part part : parts) {
            blocks.add(part.isImage()
                    ? new CardView.Image(part.image(), imageMaxHeight())
                    : new CardView.Text(Component.literal(part.text()), CommonColors.WHITE));
        }
    }

    private List<CardView.Block> questionBlocks() {
        List<CardView.Block> blocks = new ArrayList<>();
        addParts(blocks, card.questionParts());
        return blocks;
    }

    private void addCardAnswer(List<CardView.Block> blocks) {
        if (card.answerParts().isEmpty()) blocks.add(new CardView.Text(heading("(the answer side is empty)"), GRAY));
        else addParts(blocks, card.answerParts());
    }

    private static CardView.Section section(String label) {
        return new CardView.Section(label, SECTION_GRAY);
    }

    private void addTypedAnswer(List<CardView.Block> blocks) {
        blocks.add(section(attempt == 2 ? "YOUR ANSWER · SECOND TRY" : "YOUR ANSWER"));
        blocks.add(new CardView.Text(userText(typedAnswer), CommonColors.LIGHT_GRAY));
    }

    /** The AI's verdict: grade badge, score meter, key-point checklist, vagueness note and feedback. */
    private void addVerdict(List<CardView.Block> blocks, Ollama.Grade grade) {
        String name = ease4ToName(grade.ease());
        blocks.add(new CardView.Badge(Component.literal(name.toUpperCase(Locale.ROOT)).withColor(buttonColor(name))
                .append(Component.literal("  " + format(grade.score())).withColor(CommonColors.WHITE)), buttonColor(name)));
        // the marker follows the score that unlocks the buttons (the AI's, or yours when score adjusting is on)
        blocks.add(meter());

        if (!grade.keyPoints().isEmpty()) {
            MutableComponent list = Component.empty();
            for (int i = 0; i < grade.keyPoints().size(); i++) {
                Ollama.KeyPoint point = grade.keyPoints().get(i);
                if (i > 0) list.append("\n");
                list.append(switch (point.status()) {
                    case GradingPrompt.CORRECT -> Component.literal("✔ ").withColor(CommonColors.GREEN);
                    case GradingPrompt.PARTIAL -> Component.literal("~ ").withColor(CommonColors.SOFT_YELLOW);
                    case GradingPrompt.WRONG -> Component.literal("✘ ").withColor(CommonColors.RED);
                    default -> Component.literal("✘ ").withColor(CommonColors.SOFT_RED);
                });
                list.append(Component.literal(point.point().replace('§', '$')).withColor(CommonColors.WHITE));
                if (point.status().equals(GradingPrompt.PARTIAL)) list.append(Component.literal(" (partly)").withColor(GRAY));
                if (point.status().equals(GradingPrompt.WRONG)) list.append(Component.literal(" (wrong)").withColor(CommonColors.RED));
                if (point.status().equals(GradingPrompt.MISSING)) list.append(Component.literal(" (missing)").withColor(GRAY));
            }
            blocks.add(new CardView.Text(list, CommonColors.WHITE, true));
        }
        if (grade.vague() && !grade.vagueHint().isBlank()) {
            blocks.add(new CardView.Text(Component.literal((attempt == 2 ? "Still too vague: " : "Too vague: ") + grade.vagueHint().replace('§', '$')),
                    CommonColors.SOFT_YELLOW));
        }
        if (!grade.feedback().isBlank()) blocks.add(new CardView.Text(userText(grade.feedback()), CommonColors.WHITE));
    }

    private CardView.Meter meter() {
        return new CardView.Meter(score, ai.hardFrom(), ai.goodFrom(), ai.easyFrom(), METER_NAMES, METER_COLORS);
    }

    /** What goes in the second panel, below the question. Empty while only the question is shown. */
    private List<CardView.Block> answerBlocks() {
        List<CardView.Block> blocks = new ArrayList<>();
        switch (phase) {
            case QUESTION -> {}
            case SECOND_CHANCE -> {
                blocks.add(section("YOUR FIRST ANSWER"));
                blocks.add(new CardView.Text(userText(firstAnswer), CommonColors.LIGHT_GRAY));
                blocks.add(new CardView.Section("SECOND CHANCE · BE MORE SPECIFIC", CommonColors.SOFT_YELLOW));
                blocks.add(new CardView.Text(userText(secondChanceHint), CommonColors.WHITE));
            }
            case REVEALED, GAVE_UP -> {
                blocks.add(section("ANSWER"));
                addCardAnswer(blocks);
            }
            case GRADING -> addTypedAnswer(blocks); // the thinking animation is drawn below the panels
            case GRADE_ERROR -> {
                addTypedAnswer(blocks);
                blocks.add(new CardView.Text(Component.literal("Couldn't grade: " + gradeError), CommonColors.SOFT_RED));
            }
            case GRADED -> {
                addTypedAnswer(blocks);
                blocks.add(section("AI GRADE"));
                if (aiGrade != null) addVerdict(blocks, aiGrade);
                blocks.add(section("CARD ANSWER"));
                addCardAnswer(blocks);
            }
        }
        return blocks;
    }

    /** The panel the keyboard and a wheel turn outside both panels scroll: the answer once it's there, else the question. */
    private CardView scrollTarget() {
        return answerShown ? answerView : questionView;
    }

    /** The colour of the thin line along the top of the card: what state this card is in. */
    private int accentForPhase() {
        return switch (phase) {
            case QUESTION, REVEALED -> ACCENT_BLUE;
            case SECOND_CHANCE -> CommonColors.SOFT_YELLOW;
            case GRADING -> ThinkingAnimation.PURPLE;
            case GRADE_ERROR, GAVE_UP -> CommonColors.SOFT_RED;
            case GRADED -> aiGrade == null ? ACCENT_BLUE : buttonColor(ease4ToName(aiGrade.ease()));
        };
    }

    // ---------------------------------------------------------------- layout

    @Override
    public void init() {
        retryButton.setTooltip(Tooltip.create(Component.literal("Ask the AI to grade your answer again")));
        Font font = Minecraft.getInstance().font;

        int cardWidth = Math.max(160, Math.min(width - 16, width * layout.widthPercent() / 100));
        int cardX = (width - cardWidth) / 2;
        boolean typing = isTyping();
        boolean slider = phase == Phase.GRADED && ai.adjustScore();
        int bottomBlock = (phase == Phase.GRADING ? ThinkingAnimation.height(font) + 4 : BUTTON_HEIGHT)
                + (typing ? ANSWER_BOX_HEIGHT + 6 : 0) + (slider ? BUTTON_HEIGHT + 4 : 0);
        int available = height - HEADER_HEIGHT - 8 - bottomBlock - 8;

        // The cog's panel floats over the card, so its controls must be asked about clicks before the panels under it
        // (added first), but drawn after them (see the end of this method).
        boolean popover = layoutOpen;
        if (popover) {
            createLayoutSliders();
            popoverX = Math.max(4, cardX + cardWidth - POPOVER_WIDTH);
            popoverY = HEADER_HEIGHT + 2;
            int inner = POPOVER_WIDTH - 8;
            int y = popoverY + 4;
            for (IntSlider layoutSlider : List.of(answerSizeSlider, imageSizeSlider, widthSlider)) {
                layoutSlider.setRectangle(inner, 20, popoverX + 4, y);
                addWidget(layoutSlider);
                y += 22;
            }
            y += 2;
            int third = (inner - 8) / 3;
            saveForDeckButton.setRectangle(third, 20, popoverX + 4, y);
            saveAsDefaultButton.setRectangle(third, 20, popoverX + 4 + third + 4, y);
            closeLayoutButton.setRectangle(inner - 2 * (third + 4), 20, popoverX + 4 + 2 * (third + 4), y);
            addWidget(saveForDeckButton);
            addWidget(saveAsDefaultButton);
            addWidget(closeLayoutButton);
            popoverHeight = y + 20 + 4 + font.lineHeight + 4 - popoverY;
        }

        header.setMaxWidth(cardWidth - 44);
        header.setPosition(width / 2 - header.getWidth() / 2, (HEADER_HEIGHT - 9) / 2);
        addRenderableWidget(header);
        layoutButton.setPosition(cardX + cardWidth - layoutButton.getWidth(), 3);
        addRenderableWidget(layoutButton);

        // blocks depend on the layout and the screen size (picture heights), so they're rebuilt here
        List<CardView.Block> answerBlocks = answerBlocks();
        answerShown = !answerBlocks.isEmpty();
        questionView.setBlocks(questionBlocks());
        questionView.setRectangle(cardWidth, MIN_PANEL_HEIGHT, cardX, HEADER_HEIGHT); // width first, so the content can be measured
        int questionHeight;
        int answerHeight = 0;
        if (answerShown) {
            answerView.setBlocks(answerBlocks);
            answerView.setAccent(accentForPhase());
            answerView.setRectangle(cardWidth, MIN_PANEL_HEIGHT, cardX, HEADER_HEIGHT);
            // the answer area grows with its content, but only up to its share of the screen; then it scrolls
            int cap = Math.max(MIN_PANEL_HEIGHT, height * layout.answerPercent() / 100);
            answerHeight = Math.max(MIN_PANEL_HEIGHT, Math.min(Math.min(answerView.preferredHeight(), cap), available - PANEL_GAP - MIN_PANEL_HEIGHT));
            // the question gets what's left, and scrolls if that isn't enough
            questionHeight = Math.max(MIN_PANEL_HEIGHT, Math.min(questionView.preferredHeight(), available - PANEL_GAP - answerHeight));
        }
        else questionHeight = Math.max(MIN_PANEL_HEIGHT, Math.min(questionView.preferredHeight(), available));

        questionView.setHeight(questionHeight);
        questionView.refreshScrollAmount();
        addRenderableWidget(questionView);
        int y = HEADER_HEIGHT + questionHeight;
        if (answerShown) {
            y += PANEL_GAP;
            answerView.setRectangle(cardWidth, answerHeight, cardX, y);
            answerView.refreshScrollAmount();
            addRenderableWidget(answerView);
            y += answerHeight;
        }
        y += 8;

        if (typing) {
            answerBox.setRectangle(cardWidth, ANSWER_BOX_HEIGHT, cardX, y);
            addRenderableWidget(answerBox);
            if (!popover) setInitialFocus(answerBox);
            y += ANSWER_BOX_HEIGHT + 6;
        }
        if (slider) {
            if (scoreSlider == null) {
                scoreSlider = new ScoreSlider(200, BUTTON_HEIGHT, "Your score: ", score, value -> {
                    score = value;
                    updateGradeButtons();
                    answerView.replaceMeter(meter()); // move the marker; nothing else on the card changes
                });
                scoreSlider.setTooltip(Tooltip.create(Component.literal("Disagree with the AI? Drag to change the score; it unlocks the buttons.")));
            }
            scoreSlider.setPosition(width / 2 - 100, y);
            addRenderableWidget(scoreSlider);
            y += BUTTON_HEIGHT + 4;
        }
        thinkingY = y;

        switch (phase) {
            case QUESTION, SECOND_CHANCE -> {
                if (aiMode) {
                    checkButton.setMessage(Component.literal(phase == Phase.SECOND_CHANCE ? "Check again" : "Check answer"));
                    placeRow(y, cardWidth, List.of(showAnswerButton, checkButton), 120);
                    addRenderableWidget(showAnswerButton);
                    addRenderableWidget(checkButton);
                }
                else {
                    showAnswerButton.setRectangle(120, BUTTON_HEIGHT, width / 2 - 60, y);
                    addRenderableWidget(showAnswerButton);
                }
            }
            case GRADING -> { // the thinking animation goes here, see extractRenderState
                if (quitShown) {
                    quitButton.setPosition(width - quitButton.getWidth() - 6, height - quitButton.getHeight() - 6);
                    addRenderableWidget(quitButton);
                }
            }
            case GRADE_ERROR -> {
                placeRow(y, cardWidth, List.of(retryButton, gradeMyselfButton), 120);
                addRenderableWidget(retryButton);
                addRenderableWidget(gradeMyselfButton);
            }
            case GAVE_UP -> {
                int again = gradeEases.indexOf(AGAIN);
                Button button = gradeButtons.get(Math.max(0, again));
                button.setRectangle(120, BUTTON_HEIGHT, width / 2 - 60, y);
                addRenderableWidget(button);
                if (!popover) setInitialFocus(button);
            }
            case REVEALED, GRADED -> {
                placeRow(y, cardWidth, gradeButtons, 110);
                gradeButtons.forEach(this::addRenderableWidget);
            }
        }

        if (popover) { // drawn last, so it sits on top of everything
            addRenderableOnly(this::extractPopoverBackground);
            addRenderableOnly(answerSizeSlider);
            addRenderableOnly(imageSizeSlider);
            addRenderableOnly(widthSlider);
            addRenderableOnly(saveForDeckButton);
            addRenderableOnly(saveAsDefaultButton);
            addRenderableOnly(closeLayoutButton);
        }
    }

    // ---------------------------------------------------------------- the cog's size panel

    private void createLayoutSliders() {
        if (answerSizeSlider != null) return;
        answerSizeSlider = new IntSlider(POPOVER_WIDTH - 8, 20, ModConfig.CardLayout.MIN_ANSWER, ModConfig.CardLayout.MAX_ANSWER, layout.answerPercent(),
                percent -> Component.literal("Answer area: up to " + percent + "%"), percent -> changeLayout(
                        new ModConfig.CardLayout(percent, layout.imagePercent(), layout.widthPercent())));
        answerSizeSlider.setTooltip(Tooltip.create(Component.literal(
                "How much of the screen's height the answer area may take. A longer answer scrolls inside it. "
                + "The question gets the rest of the screen.")));
        imageSizeSlider = new IntSlider(POPOVER_WIDTH - 8, 20, ModConfig.CardLayout.MIN_IMAGE, ModConfig.CardLayout.MAX_IMAGE, layout.imagePercent(),
                percent -> Component.literal("Pictures: up to " + percent + "% tall"), percent -> changeLayout(
                        new ModConfig.CardLayout(layout.answerPercent(), percent, layout.widthPercent())));
        imageSizeSlider.setTooltip(Tooltip.create(Component.literal(
                "How tall a picture may be on the card, as a share of the screen's height. Click any picture to see it full size.")));
        widthSlider = new IntSlider(POPOVER_WIDTH - 8, 20, ModConfig.CardLayout.MIN_WIDTH, ModConfig.CardLayout.MAX_WIDTH, layout.widthPercent(),
                percent -> Component.literal("Card width: " + percent + "%"), percent -> changeLayout(
                        new ModConfig.CardLayout(layout.answerPercent(), layout.imagePercent(), percent)));
        widthSlider.setTooltip(Tooltip.create(Component.literal("How wide the card is, as a share of the screen's width.")));
    }

    private void changeLayout(ModConfig.CardLayout changed) {
        layout = changed;
        layoutStatus = "";
        needsRelayout = true; // applied on the next tick, so dragging a slider stays smooth
    }

    private void saveLayoutForDeck() {
        String deck = card.deckName();
        if (deck.isBlank()) return;
        ModConfig config = FileManager.getConfig();
        List<ModConfig.DeckProfile> profiles = new ArrayList<>(config.deckProfiles());
        boolean found = false;
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).deck().equals(deck)) {
                profiles.set(i, profiles.get(i).withLayout(layout));
                found = true;
            }
        }
        if (!found) profiles.add(ModConfig.DeckProfile.empty(deck).withLayout(layout));
        FileManager.updateConfig(config.withDeckProfiles(profiles));
        layoutStatus = "Saved for " + shortDeckName();
        layoutStatusColor = CommonColors.GREEN;
    }

    private void saveLayoutAsDefault() {
        ModConfig config = FileManager.getConfig();
        FileManager.updateConfig(config.withCardLayout(layout));
        // a deck with sizes of its own keeps them, so say so rather than let "default" look broken later
        boolean deckHasOwn = !DeckSettings.forDeck(FileManager.getConfig(), card.deckName()).layout().equals(layout);
        layoutStatus = deckHasOwn ? "Saved as default (this deck has its own)" : "Saved as the default";
        layoutStatusColor = deckHasOwn ? CommonColors.SOFT_YELLOW : CommonColors.GREEN;
    }

    private void extractPopoverBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        Font font = Minecraft.getInstance().font;
        graphics.fill(popoverX, popoverY, popoverX + POPOVER_WIDTH, popoverY + popoverHeight, 0xF00E0F14);
        graphics.outline(popoverX, popoverY, POPOVER_WIDTH, popoverHeight, 0x60FFFFFF);
        String status = layoutStatus.isEmpty() ? "Applies to this card until you save" : layoutStatus;
        int color = layoutStatus.isEmpty() ? 0xFF777C88 : layoutStatusColor;
        graphics.text(font, status, popoverX + (POPOVER_WIDTH - font.width(status)) / 2, popoverY + popoverHeight - font.lineHeight - 3, color, false);
    }

    private boolean overPopover(double x, double y) {
        return layoutOpen && x >= popoverX && x < popoverX + POPOVER_WIDTH && y >= popoverY && y < popoverY + popoverHeight;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        return overPopover(event.x(), event.y()); // a click on the panel itself mustn't reach the card under it
    }

    private void placeRow(int y, int availableWidth, List<Button> buttons, int maxWidth) {
        int count = buttons.size();
        int buttonWidth = Math.min(maxWidth, (availableWidth - BUTTON_GAP * (count - 1)) / Math.max(1, count));
        int totalWidth = count * buttonWidth + (count - 1) * BUTTON_GAP;
        for (int i = 0; i < count; i++) {
            buttons.get(i).setRectangle(buttonWidth, BUTTON_HEIGHT, width / 2 - totalWidth / 2 + i * (buttonWidth + BUTTON_GAP), y);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        if (phase == Phase.GRADING) extractThinking(graphics);

        updateInputLocks();
        long lockLeft = actionLockRemainingMillis();
        if (lockLeft > 150) { // tell the player why a key isn't responding yet (typing and the mouse already work)
            String lockedKey = isTyping() ? "Enter" : keyName(keys.confirm());
            String hint = lockedKey + " unlocks in " + String.format(Locale.ROOT, "%.1f", lockLeft / 1000.0) + "s";
            graphics.text(Minecraft.getInstance().font, hint, questionView.getRight() - 22 - Minecraft.getInstance().font.width(hint),
                    (HEADER_HEIGHT - 9) / 2, 0xFF777C88, false);
        }

        // a strip in each grade button's colour: bright if you can press it, faint if it's locked
        if (phase == Phase.REVEALED || phase == Phase.GRADED || phase == Phase.GAVE_UP) {
            for (int i = 0; i < gradeButtons.size(); i++) {
                Button button = gradeButtons.get(i);
                if (phase == Phase.GAVE_UP && gradeEases.get(i) != AGAIN) continue; // not on screen
                int color = buttonColor(gradeNames.get(i));
                int alpha = button.active ? 0xE0 : 0x40;
                graphics.fill(button.getX() + 1, button.getBottom() + 1, button.getRight() - 1, button.getBottom() + 3,
                        (alpha << 24) | (color & 0x00FFFFFF));
            }
        }
    }

    private void extractThinking(GuiGraphicsExtractor graphics) {
        Font font = Minecraft.getInstance().font;
        int animWidth = ThinkingAnimation.width(font, ai.model());
        ThinkingAnimation.draw(graphics, font, width / 2 - animWidth / 2, thinkingY, gradingElapsedMillis(), ai.model());
    }

    public long gradingElapsedMillis() {
        return System.currentTimeMillis() - gradingStartedMillis;
    }

    @Override
    public void tick() {
        super.tick();
        // Thinking too long: offer a way back to the game
        if (phase == Phase.GRADING && !quitShown && gradingElapsedMillis() >= ai.quitAfterSeconds() * 1000L) {
            quitShown = true;
            rebuildWidgets();
        }
        if (needsRelayout) {
            needsRelayout = false;
            // keep hold of the slider being dragged: rebuilding would otherwise let go of it mid-drag
            GuiEventListener focused = getFocused();
            boolean dragging = isDragging();
            rebuildWidgets();
            if (focused != null && children().contains(focused)) {
                setFocused(focused);
                setDragging(dragging);
            }
        }
    }

    // ---------------------------------------------------------------- background grading

    /** Leaves the card while the AI keeps grading; {@link #tickBackground} brings it back when done. */
    private void quitToGame() {
        if (phase != Phase.GRADING) return;
        background = this;
        backgroundSinceMillis = System.currentTimeMillis();
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.gui.setScreen(null);
        if (minecraft.player != null) {
            minecraft.player.sendOverlayMessage(Component.literal("Keep playing, the card comes back when the AI has graded it").withColor(GRAY));
        }
    }

    /** @return the card being graded while the player plays, or null */
    public static @Nullable AnkiQuestionScreen backgroundCard() {
        return background;
    }

    public boolean isGrading() {
        return phase == Phase.GRADING;
    }

    public String modelName() {
        return ai.model();
    }

    /** @return seconds until the card may come back (the configured minimum play time), 0 if it may come back now */
    public int secondsUntilReturn() {
        long left = ai.minPlaySeconds() * 1000L - (System.currentTimeMillis() - backgroundSinceMillis);
        return left <= 0 ? 0 : (int) ((left + 999) / 1000);
    }

    // ---------------------------------------------------------------- auto-grading

    /** The grade the AI's score gives, as the 4-button ease (1 = Again ... 4 = Easy), and the button that stands for it. */
    private int suggestedEase4() {
        return nameToEase4(gradeNames.get(suggestedIndex()));
    }

    /**
     * The AI finished while the player was playing. If auto-grading is on and this grade is one the deck lets through,
     * the result goes on the HUD with a countdown instead of the card coming back.
     */
    private void startAutoGradeIfAllowed() {
        ModConfig.AutoGrading auto = ai.autoGrading();
        if (!auto.enabled() || !settings.autoGrades().applies(suggestedEase4())) return;
        autoPending = true;
        autoRemainingMillis = auto.seconds() * 1000L;
        lastBackgroundTickMillis = System.currentTimeMillis();
    }

    /** Applies the AI's grade without opening the card, and tells the player what happened. */
    private void applyAutoGrade() {
        autoPending = false;
        int index = suggestedIndex();
        int ease = gradeEases.get(index);
        String name = gradeNames.get(index);
        if (!submit(ease)) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.sendOverlayMessage(Component.literal("Auto-graded ").withColor(GRAY)
                    .append(Component.literal(name).withColor(buttonColor(name)))
                    .append(Component.literal("  (" + format(score) + ")").withColor(GRAY)));
        }
        AnswerOutcome.runCommands(ease != AGAIN, card.deckName());
    }

    /**
     * What the HUD shows for a card that is waiting off screen.
     *
     * @param label       the grade's name, or what went wrong ("TOO VAGUE", "NOT GRADED")
     * @param color       the grade's colour
     * @param score       the AI's score, or -1 when there is none
     * @param cutoffs     Hard, Good and Easy cut-offs, for the score meter
     * @param right       what happens next, e.g. "auto in 4s" or "back in 3s"
     * @param text        the start of the AI's explanation
     * @param key         the key that opens the card, or null if none is bound
     * @param keyAction   what that key does here, e.g. "open the card and grade it yourself"
     * @param progress    how much of the countdown is left (1 to 0), or -1 when there's no countdown
     * @param secondsLeft whole seconds left on the countdown
     * @param totalSeconds the countdown's full length in seconds
     */
    public record BackgroundStatus(String label, int color, double score, double[] cutoffs, String right, String text,
                                   @Nullable String key, String keyAction, float progress, int secondsLeft, int totalSeconds) {}

    /** @return what to show on the HUD for this card while it's off screen (it's no longer "thinking") */
    public BackgroundStatus backgroundStatus() {
        String key = Flashcards.openCardKeyName();
        double[] cutoffs = {ai.hardFrom(), ai.goodFrom(), ai.easyFrom()};
        int textChars = ai.autoGrading().textChars();
        int back = secondsUntilReturn();
        String backText = back > 0 ? "back in " + back + "s" : "coming back";
        if (phase == Phase.GRADED && aiGrade != null) {
            String name = gradeNames.get(suggestedIndex());
            String text = shorten(aiGrade.feedback(), textChars);
            if (autoPending) {
                int total = ai.autoGrading().seconds();
                int seconds = (int) ((autoRemainingMillis + 999) / 1000);
                boolean paused = Minecraft.getInstance().gui.screen() != null;
                return new BackgroundStatus(name.toUpperCase(Locale.ROOT), buttonColor(name), score, cutoffs,
                        paused ? "paused" : "auto in " + seconds + "s", text, key, "open the card and grade it yourself",
                        total <= 0 ? 0 : autoRemainingMillis / (total * 1000f), seconds, total);
            }
            return new BackgroundStatus(name.toUpperCase(Locale.ROOT), buttonColor(name), score, cutoffs, backText, text,
                    key, "open it now", -1, 0, 0);
        }
        if (phase == Phase.SECOND_CHANCE) {
            return new BackgroundStatus("TOO VAGUE", CommonColors.SOFT_YELLOW, -1, cutoffs, backText,
                    "Second chance: " + shorten(secondChanceHint, textChars), key, "try again now", -1, 0, 0);
        }
        return new BackgroundStatus("NOT GRADED", CommonColors.SOFT_RED, -1, cutoffs, backText, shorten(gradeError, textChars),
                key, "open it now", -1, 0, 0);
    }

    /** A made-up result, for previewing the panel in the HUD layout screen. */
    public static BackgroundStatus sampleStatus(long millis) {
        int total = 5;
        float progress = 1 - (millis % (total * 1000L)) / (total * 1000f);
        int seconds = (int) Math.ceil(progress * total);
        return new BackgroundStatus("GOOD", CommonColors.GREEN, 0.83, new double[] {0.5, 0.7, 0.9}, "auto in " + seconds + "s",
                "You explained the main idea correctly, but missed what makes one line better than another...",
                Flashcards.openCardKeyName(), "open the card and grade it yourself", progress, seconds, total);
    }

    /** Cuts a text to {@code max} characters, at a word break where possible, and ends it with "...". */
    private static String shorten(String text, int max) {
        String oneLine = text.replace('§', '$').replaceAll("\\s+", " ").strip();
        if (oneLine.length() <= max) return oneLine;
        int cut = oneLine.lastIndexOf(' ', max);
        return (cut > max / 2 ? oneLine.substring(0, cut) : oneLine.substring(0, max)).stripTrailing() + "...";
    }

    /**
     * Call every client tick. For a card waiting off screen:
     * <ul>
     * <li>the "open card" key brings it on screen straight away;</li>
     * <li>a grade waiting to be applied counts down (only while no menu is open) and is then applied;</li>
     * <li>otherwise the card comes back once graded, after the minimum play time, when the player is free
     * (same rules as new questions).</li>
     * </ul>
     * @param openKeyPressed whether the player pressed the "open card" key since the last tick
     */
    public static void tickBackground(Minecraft minecraft, boolean openKeyPressed) {
        AnkiQuestionScreen screen = background;
        if (screen == null) {
            if (openKeyPressed && minecraft.player != null) {
                minecraft.player.sendOverlayMessage(Component.literal("No graded card is waiting").withColor(GRAY));
            }
            return;
        }
        if (minecraft.player == null) return;

        if (openKeyPressed && screen.phase != Phase.GRADING && minecraft.gui.screen() == null) {
            screen.autoPending = false; // the player wants to grade it themselves
            reopen(minecraft, screen);
            return;
        }
        if (openKeyPressed && screen.phase == Phase.GRADING) {
            minecraft.player.sendOverlayMessage(Component.literal("The AI is still grading this card").withColor(GRAY));
        }

        if (screen.autoPending) {
            long now = System.currentTimeMillis();
            if (minecraft.gui.screen() == null) screen.autoRemainingMillis -= now - screen.lastBackgroundTickMillis; // paused while a menu is open
            screen.lastBackgroundTickMillis = now;
            if (screen.autoRemainingMillis <= 0) {
                background = null;
                screen.applyAutoGrade();
            }
            return;
        }

        if (screen.phase == Phase.GRADING || screen.secondsUntilReturn() > 0) return;
        if (minecraft.gui.screen() != null || QuestionScheduler.wasPlayerHurtRecently()) return;
        reopen(minecraft, screen);
    }

    private static void reopen(Minecraft minecraft, AnkiQuestionScreen screen) {
        background = null;
        screen.lockInput(true); // the player is in the middle of playing: same protection as a new card
        minecraft.gui.setScreen(screen);
    }

    /** Call when leaving the world: the unanswered card simply stays due in Anki. */
    public static void clearBackground() {
        background = null;
    }

    // ---------------------------------------------------------------- actions

    private void setPhase(Phase newPhase) {
        phase = newPhase;
        updateGradeButtons();
        if (background == this) return; // not on screen: tickBackground reopens it, and init lays it out then
        lockInput(false);
        setFocused(null);
        answerView.setScrollAmount(0); // whatever comes next is read from its top
        rebuildWidgets();
    }

    private void reveal() {
        if (phase != Phase.QUESTION && phase != Phase.SECOND_CHANCE) return;
        setPhase(aiMode ? Phase.GAVE_UP : Phase.REVEALED);
    }

    private void checkAnswer() {
        if (isTyping()) typedAnswer = answerBox.getValue().strip();
        else if (phase != Phase.GRADE_ERROR) return;
        if (typedAnswer.isEmpty()) return;

        gradingStartedMillis = System.currentTimeMillis();
        quitShown = false;
        setPhase(Phase.GRADING);
        if (ai.autoKeepPlaying()) quitToGame(); // play while the AI thinks, without asking
        AiGrader.grade(ai, settings.systemPrompt(), card.questionForAi(), card.answerForAi(), typedAnswer, grade -> {
            if (phase != Phase.GRADING) return;
            if (attempt == 1 && grade.ease() == AGAIN && isVagueButOnTrack(grade)) {
                // One second chance: say what to be specific about, keep the answer so it can be extended
                attempt = 2;
                firstAnswer = typedAnswer;
                secondChanceHint = grade.vagueHint().isBlank() ? "Be more specific about the key ideas the question asks for." : grade.vagueHint();
                // the model may be set to unload soon after grading: keep it ready while the second answer is typed
                AiGrader.preload(ai, settings.systemPrompt(), card.questionForAi(), card.answerForAi());
                setPhase(Phase.SECOND_CHANCE);
                return;
            }
            aiGrade = grade;
            score = grade.score();
            setPhase(Phase.GRADED);
            if (background == this) startAutoGradeIfAllowed(); // graded while the player plays
        }, error -> {
            if (phase != Phase.GRADING) return;
            gradeError = error;
            setPhase(Phase.GRADE_ERROR);
        });
    }

    /**
     * A second chance is only for answers that are on the right track but too unspecific: the AI must call it vague,
     * and the key points must show some truth (something correct/partly correct) and nothing wrong.
     * "I don't know", excuses, off-topic and wrong answers fail straight away.
     */
    private static boolean isVagueButOnTrack(Ollama.Grade grade) {
        if (!grade.vague()) return false;
        boolean somethingRight = false;
        for (Ollama.KeyPoint point : grade.keyPoints()) {
            if (point.status().equals(GradingPrompt.WRONG)) return false;
            if (point.status().equals(GradingPrompt.CORRECT) || point.status().equals(GradingPrompt.PARTIAL)) somethingRight = true;
        }
        return somethingRight;
    }

    private void grade(int ease) {
        if (!submit(ease)) return;
        this.onClose();
        AnswerOutcome.runCommands(ease != AGAIN, card.deckName());
    }

    /**
     * Sends the grade to Anki and records it. Doesn't touch the screen, so it also works for a card that's only on the HUD.
     * @return false if the grade isn't allowed (locked button) or the card was already answered
     */
    private boolean submit(int ease) {
        int index = gradeEases.indexOf(ease);
        if (answered || index < 0 || !isUnlocked(index)) return false;
        answered = true;
        AnkiSession.answer(card, ease);
        AnswerOutcome.playSoundAndRecordStats(ease != AGAIN);
        QuestionScheduler.schedule(card.deckName()); // this deck's own time until the next question, if it has one
        return true;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) return true;
        if (overPopover(mouseX, mouseY)) return true;
        scrollTarget().scrollBy(-scrollY * Minecraft.getInstance().font.lineHeight * 2); // scroll the card from anywhere on screen
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        // a card just appeared (or changed): stray keys don't count yet. The card's own keys wait longer than typing.
        if (isCardKey(key) ? actionLocked : typingLocked) return true;

        boolean typing = isTyping();
        // Enter always confirms; the configurable key (Space by default) too, except while typing, where it's a letter
        boolean confirm = key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER
                || (!typing && key == keys.confirm());

        if (phase == Phase.GRADING && quitShown && (key == keys.keepPlaying() || key == InputConstants.KEY_ESCAPE)) {
            quitToGame();
            return true;
        }

        if (typing) {
            if (confirm && !event.hasShiftDown()) {
                checkAnswer(); // Shift+Enter falls through to the text box as a new line
                return true;
            }
            switch (key) {
                case InputConstants.KEY_PAGEUP -> { scrollTarget().scrollBy(-scrollTarget().pageHeight()); return true; }
                case InputConstants.KEY_PAGEDOWN -> { scrollTarget().scrollBy(scrollTarget().pageHeight()); return true; }
                default -> { return super.keyPressed(event); } // everything else is typing
            }
        }

        boolean sliderFocused = scoreSlider != null && getFocused() == scoreSlider;
        int line = Minecraft.getInstance().font.lineHeight;
        switch (key) {
            case InputConstants.KEY_UP -> { scrollTarget().scrollBy(-line * 2); return true; }
            case InputConstants.KEY_DOWN -> { scrollTarget().scrollBy(line * 2); return true; }
            case InputConstants.KEY_PAGEUP -> { scrollTarget().scrollBy(-scrollTarget().pageHeight()); return true; }
            case InputConstants.KEY_PAGEDOWN -> { scrollTarget().scrollBy(scrollTarget().pageHeight()); return true; }
            case InputConstants.KEY_HOME -> { scrollTarget().setScrollAmount(0); return true; }
            case InputConstants.KEY_END -> { scrollTarget().setScrollAmount(scrollTarget().maxScrollAmount()); return true; }
            default -> {}
        }

        switch (phase) {
            case QUESTION, SECOND_CHANCE -> {
                if (confirm) {
                    reveal();
                    return true;
                }
            }
            case GRADE_ERROR -> {
                if (confirm) {
                    checkAnswer();
                    return true;
                }
            }
            case REVEALED, GAVE_UP, GRADED -> {
                int index = -1;
                for (int i = 0; i < 4; i++) if (key == gradeKey(i)) index = i;
                switch (key) { // the number pad always works too
                    case InputConstants.KEY_NUMPAD1 -> index = 0;
                    case InputConstants.KEY_NUMPAD2 -> index = 1;
                    case InputConstants.KEY_NUMPAD3 -> index = 2;
                    case InputConstants.KEY_NUMPAD4 -> index = 3;
                    default -> {}
                }
                if (confirm && !sliderFocused) index = suggestedIndex();
                if (index >= 0 && index < gradeEases.size()) {
                    grade(gradeEases.get(index)); // ignored if that button is locked
                    return true;
                }
            }
            case GRADING -> {}
        }
        return super.keyPressed(event);
    }
}
