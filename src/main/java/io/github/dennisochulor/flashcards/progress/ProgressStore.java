package io.github.dennisochulor.flashcards.progress;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import io.github.dennisochulor.flashcards.FileManager;
import io.github.dennisochulor.flashcards.Flashcards;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import java.util.TreeMap;

/**
 * Each deck's score and counts, in config/flashcards/progress.json. Kept in memory; written in the background after
 * every change, so answering a card never waits for the disk.
 */
public final class ProgressStore {
    private ProgressStore() {}

    /** The "deck" that the mod's own question lists (not Anki) count towards. */
    public static final String OWN_QUESTIONS = "Own questions";
    private static final String FILE_NAME = "progress.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static @Nullable Map<String, Progression.DeckState> states = null;
    /** The deck of the last card answered, for the HUD. */
    private static @Nullable String lastDeck = null;

    private static Map<String, Progression.DeckState> states() {
        if (states == null) {
            Map<String, Progression.DeckState> loaded = new TreeMap<>();
            File file = FileManager.configFile(FILE_NAME);
            if (file.exists()) {
                try {
                    Map<String, Progression.DeckState> fromFile = GSON.fromJson(Files.readString(file.toPath()),
                            new TypeToken<Map<String, Progression.DeckState>>() {}.getType());
                    if (fromFile != null) loaded.putAll(fromFile);
                }
                catch (IOException | JsonParseException e) {
                    Flashcards.LOGGER.warn("Couldn't read {}, scores start from 0", FILE_NAME, e);
                }
            }
            states = loaded;
        }
        return states;
    }

    /** @return the deck's saved state (without fading applied) */
    public static Progression.DeckState get(String deck) {
        return states().getOrDefault(deck, Progression.DeckState.fresh());
    }

    public static void put(String deck, Progression.DeckState state) {
        states().put(deck, state);
        lastDeck = deck;
        save();
    }

    public static void reset(String deck) {
        states().remove(deck);
        save();
    }

    public static @Nullable String lastDeck() {
        return lastDeck;
    }

    private static void save() {
        FileManager.writeConfigAsync(FILE_NAME, GSON.toJson(states()));
    }
}
