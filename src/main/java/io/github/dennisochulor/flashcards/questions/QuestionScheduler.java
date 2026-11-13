package io.github.dennisochulor.flashcards.questions;

import io.github.dennisochulor.flashcards.FileManager;
import io.github.dennisochulor.flashcards.anki.AnkiSession;
import io.github.dennisochulor.flashcards.config.DeckSettings;
import io.github.dennisochulor.flashcards.config.ModConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Counts down to the next question. Driven by {@link #tick(Minecraft)} every client tick, so everything runs on the client thread.
 * <p>
 * In {@link ModConfig.TimerMode#REAL_TIME} the countdown follows the wall clock. In {@link ModConfig.TimerMode#GAME_TIME}
 * it follows the world's game time, so it stops while the game is paused and speeds up/slows down with /tick rate.
 */
public class QuestionScheduler {
    private QuestionScheduler() {}

    private static final long TICKS_PER_SECOND = 20;
    private static final long MILLIS_PER_TICK = 50;

    public enum State {
        /** Timer off, or not in a world. */
        OFF,
        /** Counting down to the next question. */
        COUNTING,
        /** Time is up; waiting for the player to close other screens / stop taking damage. */
        DUE,
        /** A question is being fetched or is on screen; the countdown restarts once it's answered. */
        QUESTION
    }

    private static final List<Question> questions = new ArrayList<>();
    private static ModConfig config = FileManager.getConfig();
    public static long playerLastHurtTime = 0L;

    private static State state = State.OFF;
    private static long deadlineNanos;   // REAL_TIME
    private static long remainingTicks;  // GAME_TIME
    private static long lastGameTime = Long.MIN_VALUE;
    /** The deck whose time the current countdown uses (null = the main interval), and that time. */
    private static @Nullable String intervalDeck = null;
    private static long intervalSeconds = 0;

    public static void reload() {
        config = FileManager.getConfig();
        questions.clear();
        FileManager.getQuestions().forEach((category,list) -> {
            if (config.categoryToggle().getOrDefault(category,true)) questions.addAll(list);
        });
        Collections.shuffle(questions);
    }

    /**
     * (Re)starts the countdown when there's no card to go by (joining a world, a settings change, nothing left to study):
     * the time comes from the deck picked in Anki Sync, or the main interval outside Anki mode.
     */
    public static void schedule() {
        ModConfig latest = FileManager.getConfig();
        schedule(latest.ankiMode() && !latest.ankiDeck().isBlank() ? latest.ankiDeck() : null);
    }

    /**
     * (Re)starts the countdown after a card: the time until the next question is the one set for that card's deck
     * (Deck Presets), or the main interval if the deck and its parents don't set one.
     * @param deck the full Anki deck name of the card just answered, or null for the main interval
     */
    public static void schedule(@Nullable String deck) {
        ClientLevel level = Minecraft.getInstance().level;
        if (!config.intervalToggle() || level == null) {
            state = State.OFF;
            return;
        }
        intervalDeck = deck;
        intervalSeconds = DeckSettings.forDeck(FileManager.getConfig(), deck).intervalSeconds();
        state = State.COUNTING;
        deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(intervalSeconds);
        remainingTicks = intervalSeconds * TICKS_PER_SECOND;
        lastGameTime = level.getGameTime();
    }

    /** Call when deck presets were edited: a running countdown restarts if the time that applies to it changed. */
    public static void deckPresetsChanged() {
        if (state != State.COUNTING) return;
        if (DeckSettings.forDeck(FileManager.getConfig(), intervalDeck).intervalSeconds() != intervalSeconds) schedule(intervalDeck);
    }

    public static void stop() {
        state = State.OFF;
    }

    public static void close() {
        stop();
    }

    public static void updateConfig(ModConfig newConfig) {
        ModConfig old = config;
        config = newConfig;
        if (!newConfig.intervalToggle()) {
            stop();
            return;
        }
        boolean timingChanged = old.intervalTotalSeconds() != newConfig.intervalTotalSeconds() || old.intervalToggle() != newConfig.intervalToggle()
                || old.timerMode() != newConfig.timerMode();
        boolean deckChanged = !old.ankiDeck().equals(newConfig.ankiDeck()) || !old.ankiMode().equals(newConfig.ankiMode());
        if (state == State.OFF || deckChanged) schedule();      // start from the deck picked in Anki Sync
        else if (timingChanged) schedule(intervalDeck);
        else deckPresetsChanged();                              // otherwise keep the running countdown
    }

    public static State state() {
        return state;
    }

    public static ModConfig.TimerMode timerMode() {
        return config.timerMode();
    }

    /** @return the full interval between questions, in milliseconds */
    public static long totalMillis() {
        return intervalSeconds * 1000L;
    }

    /** @return milliseconds until the next question (game-time milliseconds in GAME_TIME mode), 0 when due. */
    public static long remainingMillis() {
        if (state != State.COUNTING) return 0;
        if (config.timerMode() == ModConfig.TimerMode.GAME_TIME) return Math.max(0, remainingTicks * MILLIS_PER_TICK);
        return Math.max(0, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
    }

    /** Call at the end of every client tick. */
    public static void tick(Minecraft minecraft) {
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.player == null) return;

        if (state == State.OFF && config.intervalToggle()) schedule(); // e.g. joined before the level existed

        if (state == State.COUNTING) {
            long gameTime = level.getGameTime();
            if (lastGameTime != Long.MIN_VALUE && gameTime > lastGameTime) remainingTicks -= gameTime - lastGameTime;
            lastGameTime = gameTime; // a backwards jump (e.g. a different server) is simply ignored

            if (remainingMillis() <= 0) state = State.DUE;
        }

        if (state == State.DUE && minecraft.gui.screen() == null && !wasPlayerHurtRecently()) {
            state = State.QUESTION;
            if (!promptQuestion()) schedule(); // nothing to ask, try again next interval
        }
    }

    /**
     * Opens a question now (or starts fetching one from Anki).
     * @return false if there was nothing to ask
     */
    public static boolean promptQuestion() {
        Objects.requireNonNull(Minecraft.getInstance().player); // sanity check to avoid questions outside a world

        if (AnkiQuestionScreen.backgroundCard() != null) { // a card is still being graded while the player plays
            Minecraft.getInstance().player.sendOverlayMessage(net.minecraft.network.chat.Component.literal("The AI is still grading your last answer"));
            return true;
        }

        if (AnkiSession.isEnabled()) {
            if (state == State.COUNTING || state == State.DUE) state = State.QUESTION; // e.g. prompted with the key
            return AnkiSession.promptCard(); // the card arrives asynchronously from Anki
        }

        if (questions.isEmpty()) reload();
        if (questions.isEmpty()) return false; // there are no questions... so just do nothing
        int rand = ThreadLocalRandom.current().nextInt(0,questions.size());
        Question question = questions.remove(rand);
        if (config.validationToggle()) Minecraft.getInstance().gui.setScreen(new AutoValidationQuestionScreen(question));
        else Minecraft.getInstance().gui.setScreen(new ManualValidationQuestionScreen(question));
        return true;
    }

    public static boolean wasPlayerHurtRecently() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return false;
        return (level.getGameTime() - playerLastHurtTime) < (level.tickRateManager().tickrate() * 10); // 10 seconds
    }
}
