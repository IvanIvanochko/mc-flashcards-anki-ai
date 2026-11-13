package io.github.dennisochulor.flashcards.ai;

import io.github.dennisochulor.flashcards.Flashcards;
import io.github.dennisochulor.flashcards.config.ModConfig;
import net.minecraft.client.Minecraft;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Runs Ollama requests on a background thread and hands results back to the client thread.
 * Separate from the Anki thread so a slow grade never delays sending reviews to Anki.
 */
public final class AiGrader {
    private AiGrader() {}

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Flashcards-Ollama");
        t.setDaemon(true);
        return t;
    });

    /** AI grading is used only for Anki cards, and only once a model is picked. */
    public static boolean isEnabled(ModConfig config, ModConfig.AiGrading ai) {
        return config.ankiMode() && ai.enabled() && !ai.model().isBlank();
    }

    /**
     * Gets the model ready for this card while the player is still typing (see {@link Ollama#warmUp}).
     * Errors are ignored here; grading reports them if they persist.
     */
    public static void preload(ModConfig.AiGrading ai, String systemPrompt, String question, String reference) {
        EXECUTOR.execute(() -> {
            try {
                new Ollama(ai.ollamaUrl()).warmUp(ai, systemPrompt, question, reference);
            }
            catch (Ollama.OllamaException | RuntimeException e) {
                Flashcards.LOGGER.debug("Couldn't warm up Ollama model {}", ai.model(), e);
            }
        });
    }

    /**
     * Grades the answer; exactly one callback runs, on the client thread.
     * @param ai     the settings for this card's deck (model, cut-offs)
     * @param systemPrompt the complete grading instructions for this deck
     */
    public static void grade(ModConfig.AiGrading ai, String systemPrompt, String question, String reference, String answer,
                             Consumer<Ollama.Grade> onSuccess, Consumer<String> onError) {
        EXECUTOR.execute(() -> {
            try {
                Ollama.Grade grade = new Ollama(ai.ollamaUrl()).grade(ai, systemPrompt, question, reference, answer);
                Minecraft.getInstance().execute(() -> onSuccess.accept(grade));
            }
            catch (Ollama.OllamaException e) {
                Flashcards.LOGGER.warn("AI grading failed", e);
                Minecraft.getInstance().execute(() -> onError.accept(e.getMessage()));
            }
            catch (RuntimeException e) {
                Flashcards.LOGGER.error("Unexpected error during AI grading", e);
                Minecraft.getInstance().execute(() -> onError.accept("Unexpected error while grading, see the log."));
            }
        });
    }

    /**
     * Has the local model write deck notes from sample cards; exactly one callback runs, on the client thread.
     * @param soFar what the player has already written, or ""
     */
    public static void writeDeckNotes(ModConfig.AiGrading ai, String deck, List<DeckNotesWriter.Sample> samples, String soFar,
                                      Consumer<String> onSuccess, Consumer<String> onError) {
        EXECUTOR.execute(() -> {
            try {
                String reply = new Ollama(ai.ollamaUrl()).complete(ai, DeckNotesWriter.localSystem(),
                        DeckNotesWriter.localUser(deck, samples, soFar), 350);
                String notes = DeckNotesWriter.clean(reply);
                if (notes.isBlank()) throw new Ollama.OllamaException("The AI wrote nothing. Try again or use another model.");
                Minecraft.getInstance().execute(() -> onSuccess.accept(notes));
            }
            catch (Ollama.OllamaException e) {
                Flashcards.LOGGER.warn("Writing deck notes failed", e);
                Minecraft.getInstance().execute(() -> onError.accept(e.getMessage()));
            }
            catch (RuntimeException e) {
                Flashcards.LOGGER.error("Unexpected error while writing deck notes", e);
                Minecraft.getInstance().execute(() -> onError.accept("Unexpected error while writing the notes, see the log."));
            }
        });
    }

    /** Loads the installed models for the config screens; exactly one callback runs, on the client thread. */
    public static void loadModels(String url, Consumer<List<Ollama.ModelInfo>> onSuccess, Consumer<String> onError) {
        EXECUTOR.execute(() -> {
            try {
                List<Ollama.ModelInfo> models = new Ollama(url).models();
                Minecraft.getInstance().execute(() -> onSuccess.accept(models));
            }
            catch (Ollama.OllamaException e) {
                Minecraft.getInstance().execute(() -> onError.accept(e.getMessage()));
            }
            catch (RuntimeException e) {
                Minecraft.getInstance().execute(() -> onError.accept("Couldn't load models: " + e.getMessage()));
            }
        });
    }

    /** Asks Ollama which models are in memory right now; exactly one callback runs, on the client thread. */
    public static void loadMemory(String url, Consumer<List<Ollama.LoadedModel>> onSuccess, Consumer<String> onError) {
        EXECUTOR.execute(() -> {
            try {
                List<Ollama.LoadedModel> loaded = new Ollama(url).loaded();
                Minecraft.getInstance().execute(() -> onSuccess.accept(loaded));
            }
            catch (Ollama.OllamaException e) {
                Minecraft.getInstance().execute(() -> onError.accept(e.getMessage()));
            }
            catch (RuntimeException e) {
                Minecraft.getInstance().execute(() -> onError.accept("Couldn't read Ollama's memory use: " + e.getMessage()));
            }
        });
    }
}
