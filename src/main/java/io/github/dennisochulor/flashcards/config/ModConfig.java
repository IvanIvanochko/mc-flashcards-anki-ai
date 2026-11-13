package io.github.dennisochulor.flashcards.config;

import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import java.util.HashMap;
import java.util.List;

public record ModConfig(int interval, boolean intervalToggle, Boolean validationToggle,
                        HashMap<String,Boolean> categoryToggle, List<String> correctAnswerCommands,
                        List<String> wrongAnswerCommands, CommandSelectionStrategy commandSelectionStrategy,
                        Boolean ankiMode, String ankiDeck, String ankiConnectUrl, Boolean ankiSync,
                        TimerMode timerMode, Boolean hudEnabled, Placement hudPlacement, AiGrading aiGrading,
                        Integer intervalSeconds, Placement indicatorPlacement, String rewardPreset,
                        List<DeckProfile> deckProfiles, CardKeys cardKeys)
{
    public static final String DEFAULT_ANKI_CONNECT_URL = "http://127.0.0.1:8765";

    public enum CommandSelectionStrategy {
        EXECUTE_ALL("Execute All", "Execute all listed commands in sequential order."),
        RANDOMISE_ONE("Randomise One", "Randomly select one command."),
        OFF("OFF", "Don't execute commands.");

        public final String friendlyName;
        public final Tooltip tooltip;

        CommandSelectionStrategy(String friendlyName, String tooltip) {
            this.friendlyName = friendlyName;
            this.tooltip = Tooltip.create(Component.nullToEmpty(tooltip));
        }

        public static CommandSelectionStrategy fromFriendlyName(String str) {
            return switch(str) {
                case "Execute All" -> EXECUTE_ALL;
                case "Randomise One" -> RANDOMISE_ONE;
                case "OFF" -> OFF;
                default -> throw new IllegalArgumentException("Unknown str: " + str);
            };
        }
    }

    public static final String DEFAULT_OLLAMA_URL = "http://127.0.0.1:11434";

    /**
     * Grading typed answers with a local AI (Ollama). The score (0-1) unlocks Anki's buttons:
     * Again is always allowed, Hard from {@code hardFrom}, Good from {@code goodFrom}, Easy from {@code easyFrom}.
     *
     * @param adjustScore       whether the player may change the AI's score before grading
     * @param quitAfterSeconds  how long the AI may think before a Quit button lets the player keep playing meanwhile
     * @param minPlaySeconds    after "Keep playing", the card comes back no sooner than this
     * @param autoKeepPlaying   go back to the game as soon as the AI starts thinking, without pressing "Keep playing"
     * @param prompt            the player's own grading instructions; "" = the built-in default (which then keeps improving with updates)
     * @param performance       how much of the computer the AI may use
     */
    public record AiGrading(Boolean enabled, String ollamaUrl, String model,
                            Double hardFrom, Double goodFrom, Double easyFrom, Boolean adjustScore,
                            Integer quitAfterSeconds, Integer minPlaySeconds, Boolean autoKeepPlaying, String prompt,
                            AiPerformance performance) {
        public static final double DEFAULT_HARD_FROM = 0.5, DEFAULT_GOOD_FROM = 0.7, DEFAULT_EASY_FROM = 0.9;
        public static final int DEFAULT_QUIT_AFTER_SECONDS = 15, MAX_QUIT_AFTER_SECONDS = 120;
        public static final int DEFAULT_MIN_PLAY_SECONDS = 5, MAX_MIN_PLAY_SECONDS = 120;
        public static final AiGrading DEFAULT = new AiGrading(false, DEFAULT_OLLAMA_URL, "",
                DEFAULT_HARD_FROM, DEFAULT_GOOD_FROM, DEFAULT_EASY_FROM, false,
                DEFAULT_QUIT_AFTER_SECONDS, DEFAULT_MIN_PLAY_SECONDS, false, "", null);

        @SuppressWarnings("ConstantValue")
        public AiGrading {
            // null checks needed for when Gson deserializes
            if (enabled == null) enabled = false;
            if (ollamaUrl == null || ollamaUrl.isBlank()) ollamaUrl = DEFAULT_OLLAMA_URL;
            if (model == null) model = "";
            if (hardFrom == null) hardFrom = DEFAULT_HARD_FROM;
            if (goodFrom == null) goodFrom = DEFAULT_GOOD_FROM;
            if (easyFrom == null) easyFrom = DEFAULT_EASY_FROM;
            if (adjustScore == null) adjustScore = false;
            if (quitAfterSeconds == null) quitAfterSeconds = DEFAULT_QUIT_AFTER_SECONDS;
            quitAfterSeconds = Math.max(0, Math.min(MAX_QUIT_AFTER_SECONDS, quitAfterSeconds));
            if (minPlaySeconds == null) minPlaySeconds = DEFAULT_MIN_PLAY_SECONDS;
            minPlaySeconds = Math.max(0, Math.min(MAX_MIN_PLAY_SECONDS, minPlaySeconds));
            if (autoKeepPlaying == null) autoKeepPlaying = false;
            if (prompt == null) prompt = "";
            if (performance == null) performance = AiPerformance.recommended();
            hardFrom = clamp01(hardFrom);
            goodFrom = Math.max(hardFrom, clamp01(goodFrom)); // keep Hard <= Good <= Easy
            easyFrom = Math.max(goodFrom, clamp01(easyFrom));
        }

        private static double clamp01(double d) {
            return Math.round(Math.min(1, Math.max(0, d)) * 100) / 100.0;
        }

        public AiGrading withModel(String model) {
            return new AiGrading(enabled, ollamaUrl, model, hardFrom, goodFrom, easyFrom, adjustScore, quitAfterSeconds, minPlaySeconds, autoKeepPlaying, prompt, performance);
        }

        public AiGrading withPrompt(String prompt) {
            return new AiGrading(enabled, ollamaUrl, model, hardFrom, goodFrom, easyFrom, adjustScore, quitAfterSeconds, minPlaySeconds, autoKeepPlaying, prompt, performance);
        }

        public AiGrading withPerformance(AiPerformance performance) {
            return new AiGrading(enabled, ollamaUrl, model, hardFrom, goodFrom, easyFrom, adjustScore, quitAfterSeconds, minPlaySeconds, autoKeepPlaying, prompt, performance);
        }

        /** @return the lowest score that unlocks the button for this Anki ease (1 = Again ... 4 = Easy) */
        public double minimumScore(int ease) {
            return switch (ease) {
                case 1 -> 0;
                case 2 -> hardFrom;
                case 3 -> goodFrom;
                default -> easyFrom;
            };
        }
    }

    /** Ready-made answers to "who gets the computer: the game or the AI?". */
    public enum PerformanceProfile {
        RECOMMENDED("Recommended", "Balanced. The AI gets what's left after Minecraft and the system; the model stays loaded for 10 minutes."),
        MINECRAFT_FIRST("Minecraft first", "Smoothest game. Small AI budget; the model is unloaded a minute after each card and loaded again while you type."),
        AI_FIRST("AI speed first", "Fastest grading. Large AI budget; the model stays in memory for an hour. Best with plenty of RAM."),
        CUSTOM("Custom", "Your own numbers. Pick a profile to start from, then move the sliders.");

        public final String friendlyName;
        public final String description;

        PerformanceProfile(String friendlyName, String description) {
            this.friendlyName = friendlyName;
            this.description = description;
        }
    }

    /**
     * How much of the computer the grading AI may use.
     * <p>
     * Ollama has no "use at most N GB" switch. What actually decides the AI's memory is which model is loaded (its size)
     * and how long it stays loaded, so the budget here is the yardstick models are measured against (and the mod warns
     * and suggests a smaller model when the chosen one doesn't fit), while {@code keepAliveMinutes} is a real control.
     *
     * @param memoryBudgetGb   how much RAM the AI model may take
     * @param keepAliveMinutes how long the model stays in memory after grading; 0 = unload straight away
     * @param threads          CPU threads for the AI; 0 = let Ollama decide. Only matters when the model runs on the CPU.
     */
    public record AiPerformance(PerformanceProfile profile, Double memoryBudgetGb, Integer keepAliveMinutes, Integer threads) {
        public static final double MIN_BUDGET_GB = 1.0;
        public static final int MAX_KEEP_ALIVE_MINUTES = 120;

        @SuppressWarnings("ConstantValue")
        public AiPerformance {
            if (profile == null) profile = PerformanceProfile.RECOMMENDED;
            if (memoryBudgetGb == null) memoryBudgetGb = SystemMemory.recommendedBudgetGb();
            memoryBudgetGb = Math.max(MIN_BUDGET_GB, Math.round(memoryBudgetGb * 2) / 2.0); // steps of 0.5 GB
            if (keepAliveMinutes == null) keepAliveMinutes = 10;
            keepAliveMinutes = Math.max(0, Math.min(MAX_KEEP_ALIVE_MINUTES, keepAliveMinutes));
            if (threads == null) threads = 0;
            threads = Math.max(0, Math.min(SystemMemory.cpuThreads(), threads));
        }

        public static AiPerformance recommended() {
            return forProfile(PerformanceProfile.RECOMMENDED);
        }

        /** The numbers a profile stands for on this computer. CUSTOM has none of its own, so it starts from Recommended. */
        public static AiPerformance forProfile(PerformanceProfile profile) {
            return switch (profile) {
                case MINECRAFT_FIRST -> new AiPerformance(profile, SystemMemory.minecraftFirstBudgetGb(), 1, Math.max(2, SystemMemory.cpuThreads() / 2));
                case AI_FIRST -> new AiPerformance(profile, SystemMemory.aiFirstBudgetGb(), 60, 0);
                case RECOMMENDED, CUSTOM -> new AiPerformance(profile, SystemMemory.recommendedBudgetGb(), 10, 0);
            };
        }
    }

    /**
     * Keys on the card screen (keyboard key codes), and how long keys are ignored when a card appears,
     * so mashing Space while running and jumping can't skip a card unseen.
     *
     * @param confirm     show the answer; then pick the suggested grade. Enter always does this too.
     * @param keepPlaying leave a card that's being graded and keep playing
     * @param graceMillis keys are ignored for this long after a card (or its answer) appears
     */
    public record CardKeys(Integer confirm, Integer again, Integer hard, Integer good, Integer easy, Integer keepPlaying, Integer graceMillis) {
        // key codes as in com.mojang.blaze3d.platform.InputConstants: Space, 1, 2, 3, 4
        public static final CardKeys DEFAULT = new CardKeys(44, 30, 31, 32, 33, 44, 1000);
        public static final int MAX_GRACE_MILLIS = 5000;

        @SuppressWarnings("ConstantValue")
        public CardKeys {
            if (confirm == null) confirm = 44;
            if (again == null) again = 30;
            if (hard == null) hard = 31;
            if (good == null) good = 32;
            if (easy == null) easy = 33;
            if (keepPlaying == null) keepPlaying = 44;
            if (graceMillis == null) graceMillis = 1000;
            graceMillis = Math.max(0, Math.min(MAX_GRACE_MILLIS, graceMillis));
        }
    }

    /**
     * Settings for one Anki deck that replace the main ones while a card from that deck (or one of its subdecks
     * without a profile of its own) is on screen. Every part is optional.
     *
     * @param deck          full Anki deck name, e.g. "Statistics::Regression"
     * @param model         Ollama model for this deck; "" = the main model
     * @param prompt        advanced: a whole grading prompt that replaces the main one for this deck; "" = the main prompt
     * @param customRewards false = the main rewards and penalties; true = the commands below
     * @param rewardPreset  name of the preset the commands came from, or "" when written by hand
     * @param intervalSeconds time until the next question after a card from this deck; 0 = the main interval
     * @param notes         a few sentences about this deck (subject, strictness, what an answer must contain) that are
     *                      added to the grading prompt; "" = none. The usual way to tune grading for a deck.
     */
    public record DeckProfile(String deck, String model, String prompt, Boolean customRewards, String rewardPreset,
                              List<String> correctCommands, List<String> wrongCommands, CommandSelectionStrategy strategy,
                              Integer intervalSeconds, String notes) {
        @SuppressWarnings("ConstantValue")
        public DeckProfile {
            if (deck == null) deck = "";
            if (model == null) model = "";
            if (prompt == null) prompt = "";
            if (customRewards == null) customRewards = false;
            if (rewardPreset == null) rewardPreset = "";
            correctCommands = cleanCommands(correctCommands);
            wrongCommands = cleanCommands(wrongCommands);
            if (strategy == null) strategy = CommandSelectionStrategy.EXECUTE_ALL;
            if (intervalSeconds == null || intervalSeconds < 0) intervalSeconds = 0;
            notes = notes == null ? "" : notes.strip();
        }

        /** Whether this profile changes nothing, i.e. doesn't need to be saved. */
        public boolean isDefault() {
            return model.isBlank() && prompt.isBlank() && !customRewards && intervalSeconds == 0 && notes.isBlank();
        }
    }

    /** Drops blank lines and a leading slash. */
    static List<String> cleanCommands(List<String> commands) {
        if (commands == null) return List.of();
        return commands.stream().map(String::strip).filter(c -> !c.isEmpty()).map(c -> c.startsWith("/") ? c.substring(1) : c).toList();
    }

    /**
     * Where an on-screen element sits and how big it is. Position is a fraction of the free space, so the element
     * stays fully on screen at any window size or GUI scale: x 0 = left edge, 1 = right edge; y 0 = top, 1 = bottom.
     */
    public record Placement(Double x, Double y, Double scale) {
        public static final double MIN_SCALE = 0.5, MAX_SCALE = 3.0;
        public static final int MARGIN = 3;
        public static final Placement DEFAULT_HUD = new Placement(0.0, 0.0, 1.0);        // top left
        public static final Placement DEFAULT_INDICATOR = new Placement(0.5, 0.6, 1.0);  // centered, just below the crosshair

        @SuppressWarnings("ConstantValue")
        public Placement {
            if (x == null) x = 0.0;
            if (y == null) y = 0.0;
            if (scale == null) scale = 1.0;
            x = Math.min(1, Math.max(0, x));
            y = Math.min(1, Math.max(0, y));
            scale = Math.round(Math.min(MAX_SCALE, Math.max(MIN_SCALE, scale)) * 20) / 20.0; // steps of 0.05
        }

        /** @return the left edge in screen pixels for an element that is {@code width} wide before scaling */
        public int left(int screenWidth, int width) {
            return MARGIN + (int) Math.round(x * Math.max(0, screenWidth - width * scale - MARGIN * 2));
        }

        public int top(int screenHeight, int height) {
            return MARGIN + (int) Math.round(y * Math.max(0, screenHeight - height * scale - MARGIN * 2));
        }

        /** The placement that puts the element's top-left corner at the given screen pixel. */
        public static Placement at(double left, double top, double scale, int screenWidth, int screenHeight, int width, int height) {
            double freeX = screenWidth - width * scale - MARGIN * 2;
            double freeY = screenHeight - height * scale - MARGIN * 2;
            return new Placement(freeX <= 0 ? 0 : (left - MARGIN) / freeX, freeY <= 0 ? 0 : (top - MARGIN) / freeY, scale);
        }

        public Placement withScale(double scale) {
            return new Placement(x, y, scale);
        }
    }

    /** What the question interval is measured in. */
    public enum TimerMode {
        REAL_TIME("Real time", "Counts real-world minutes, even while the game is paused or a menu is open."),
        GAME_TIME("Game time", "Counts only while the world is running: stops while the game is paused and follows /tick rate.\n" +
                "1 minute = 1200 game ticks (a full Minecraft day is 20 minutes).");

        public final String friendlyName;
        public final String description;

        TimerMode(String friendlyName, String description) {
            this.friendlyName = friendlyName;
            this.description = description;
        }
    }

    @SuppressWarnings("ConstantValue")
    public ModConfig {
        // interval = minutes, intervalSeconds = the extra 0-59 seconds
        if (intervalSeconds == null) intervalSeconds = 0;
        if (interval < 0 || intervalSeconds < 0 || intervalSeconds > 59) throw new IllegalArgumentException("invalid interval!");
        if (interval == 0 && intervalSeconds == 0) throw new IllegalArgumentException("interval must be at least 1 second!");
        // null checks needed for when Gson deserializes
        if (correctAnswerCommands == null) correctAnswerCommands = List.of();
        if (wrongAnswerCommands == null) wrongAnswerCommands = List.of();
        if (validationToggle == null) validationToggle = true;
        if (commandSelectionStrategy == null) commandSelectionStrategy = CommandSelectionStrategy.EXECUTE_ALL;
        if (ankiMode == null) ankiMode = false;
        if (ankiDeck == null) ankiDeck = "";
        if (ankiConnectUrl == null || ankiConnectUrl.isBlank()) ankiConnectUrl = DEFAULT_ANKI_CONNECT_URL;
        if (ankiSync == null) ankiSync = true;
        if (timerMode == null) timerMode = TimerMode.REAL_TIME;
        if (hudEnabled == null) hudEnabled = true;
        if (hudPlacement == null) hudPlacement = Placement.DEFAULT_HUD;
        if (indicatorPlacement == null) indicatorPlacement = Placement.DEFAULT_INDICATOR;
        if (rewardPreset == null) rewardPreset = "";
        deckProfiles = deckProfiles == null ? List.of() : List.copyOf(deckProfiles);
        if (cardKeys == null) cardKeys = CardKeys.DEFAULT;
        if (aiGrading == null) aiGrading = AiGrading.DEFAULT;

        correctAnswerCommands = cleanCommands(correctAnswerCommands);
        wrongAnswerCommands = cleanCommands(wrongAnswerCommands);
    }

    /** @return the time between questions, in seconds */
    public int intervalTotalSeconds() {
        return interval * 60 + intervalSeconds;
    }

    public ModConfig withInterval(int interval, int intervalSeconds, boolean intervalToggle) {
        return new ModConfig(interval, intervalToggle, validationToggle, categoryToggle, correctAnswerCommands,
                wrongAnswerCommands, commandSelectionStrategy, ankiMode, ankiDeck, ankiConnectUrl, ankiSync, timerMode, hudEnabled, hudPlacement, aiGrading, intervalSeconds, indicatorPlacement, rewardPreset, deckProfiles, cardKeys);
    }

    public ModConfig withValidationToggle(boolean validationToggle) {
        return new ModConfig(interval, intervalToggle, validationToggle, categoryToggle, correctAnswerCommands,
                wrongAnswerCommands, commandSelectionStrategy, ankiMode, ankiDeck, ankiConnectUrl, ankiSync, timerMode, hudEnabled, hudPlacement, aiGrading, intervalSeconds, indicatorPlacement, rewardPreset, deckProfiles, cardKeys);
    }

    public ModConfig withCategoryToggle(HashMap<String, Boolean> categoryToggle) {
        return new ModConfig(interval, intervalToggle, validationToggle, categoryToggle, correctAnswerCommands,
                wrongAnswerCommands, commandSelectionStrategy, ankiMode, ankiDeck, ankiConnectUrl, ankiSync, timerMode, hudEnabled, hudPlacement, aiGrading, intervalSeconds, indicatorPlacement, rewardPreset, deckProfiles, cardKeys);
    }

    /** @param rewardPreset name of the preset the commands came from, or "" when they were written by hand */
    public ModConfig withCommands(List<String> correctAnswerCommands, List<String> wrongAnswerCommands, CommandSelectionStrategy commandSelectionStrategy, String rewardPreset) {
        return new ModConfig(interval, intervalToggle, validationToggle, categoryToggle, correctAnswerCommands,
                wrongAnswerCommands, commandSelectionStrategy, ankiMode, ankiDeck, ankiConnectUrl, ankiSync, timerMode, hudEnabled, hudPlacement, aiGrading, intervalSeconds, indicatorPlacement, rewardPreset, deckProfiles, cardKeys);
    }

    public ModConfig withAnki(boolean ankiMode, String ankiDeck, String ankiConnectUrl, boolean ankiSync) {
        return new ModConfig(interval, intervalToggle, validationToggle, categoryToggle, correctAnswerCommands,
                wrongAnswerCommands, commandSelectionStrategy, ankiMode, ankiDeck, ankiConnectUrl, ankiSync, timerMode, hudEnabled, hudPlacement, aiGrading, intervalSeconds, indicatorPlacement, rewardPreset, deckProfiles, cardKeys);
    }

    public ModConfig withAiGrading(AiGrading aiGrading) {
        return new ModConfig(interval, intervalToggle, validationToggle, categoryToggle, correctAnswerCommands,
                wrongAnswerCommands, commandSelectionStrategy, ankiMode, ankiDeck, ankiConnectUrl, ankiSync, timerMode, hudEnabled, hudPlacement, aiGrading, intervalSeconds, indicatorPlacement, rewardPreset, deckProfiles, cardKeys);
    }

    public ModConfig withCardKeys(CardKeys cardKeys) {
        return new ModConfig(interval, intervalToggle, validationToggle, categoryToggle, correctAnswerCommands,
                wrongAnswerCommands, commandSelectionStrategy, ankiMode, ankiDeck, ankiConnectUrl, ankiSync, timerMode, hudEnabled, hudPlacement, aiGrading, intervalSeconds, indicatorPlacement, rewardPreset, deckProfiles, cardKeys);
    }

    public ModConfig withDeckProfiles(List<DeckProfile> deckProfiles) {
        return new ModConfig(interval, intervalToggle, validationToggle, categoryToggle, correctAnswerCommands,
                wrongAnswerCommands, commandSelectionStrategy, ankiMode, ankiDeck, ankiConnectUrl, ankiSync, timerMode, hudEnabled, hudPlacement, aiGrading, intervalSeconds, indicatorPlacement, rewardPreset, deckProfiles, cardKeys);
    }

    public ModConfig withPlacements(Placement hudPlacement, Placement indicatorPlacement) {
        return new ModConfig(interval, intervalToggle, validationToggle, categoryToggle, correctAnswerCommands,
                wrongAnswerCommands, commandSelectionStrategy, ankiMode, ankiDeck, ankiConnectUrl, ankiSync, timerMode, hudEnabled, hudPlacement, aiGrading, intervalSeconds, indicatorPlacement, rewardPreset, deckProfiles, cardKeys);
    }

    public ModConfig withTimerAndHud(TimerMode timerMode, boolean hudEnabled) {
        return new ModConfig(interval, intervalToggle, validationToggle, categoryToggle, correctAnswerCommands,
                wrongAnswerCommands, commandSelectionStrategy, ankiMode, ankiDeck, ankiConnectUrl, ankiSync, timerMode, hudEnabled, hudPlacement, aiGrading, intervalSeconds, indicatorPlacement, rewardPreset, deckProfiles, cardKeys);
    }
}
