package io.github.dennisochulor.flashcards.config;

import io.github.dennisochulor.flashcards.ai.GradingPrompt;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * The settings that actually apply to a card: the main settings, with the card's deck preset laid over them.
 * Each part (model, notes, prompt, rewards, time between cards) is looked up separately, from the deck itself up through its parents:
 * "A::B::C" uses its own model if it sets one, otherwise "A::B"'s, otherwise "A"'s, otherwise the main one.
 *
 * @param ai     AI grading settings, with the deck's model if it has one
 * @param prompt grading instructions as written by the player, or "" for the built-in default
 * @param intervalSeconds time until the next question after a card from this deck
 * @param notes  the deck's notes for the grader, or ""
 */
public record DeckSettings(ModConfig.AiGrading ai, String prompt, List<String> correctCommands, List<String> wrongCommands,
                           ModConfig.CommandSelectionStrategy strategy, int intervalSeconds, String notes) {

    /** The complete instructions the grading AI gets for a card from this deck. */
    public String systemPrompt() {
        return GradingPrompt.system(ai, prompt, notes);
    }

    /** @param deck the card's full Anki deck name, or null/blank for the mod's own question lists */
    public static DeckSettings forDeck(ModConfig config, @Nullable String deck) {
        ModConfig.AiGrading ai = config.aiGrading();
        String prompt = ai.prompt();
        List<String> correct = config.correctAnswerCommands();
        List<String> wrong = config.wrongAnswerCommands();
        ModConfig.CommandSelectionStrategy strategy = config.commandSelectionStrategy();

        int interval = config.intervalTotalSeconds();

        String notes = "";

        boolean modelSet = false, promptSet = false, rewardsSet = false, intervalSet = false, notesSet = false;
        String name = deck == null ? "" : deck;
        while (!name.isEmpty() && !(modelSet && promptSet && rewardsSet && intervalSet && notesSet)) {
            ModConfig.DeckProfile profile = exact(config.deckProfiles(), name);
            if (profile != null) {
                if (!modelSet && !profile.model().isBlank()) {
                    ai = ai.withModel(profile.model());
                    modelSet = true;
                }
                if (!promptSet && !profile.prompt().isBlank()) {
                    prompt = profile.prompt();
                    promptSet = true;
                }
                if (!rewardsSet && profile.customRewards()) {
                    correct = profile.correctCommands();
                    wrong = profile.wrongCommands();
                    strategy = profile.strategy();
                    rewardsSet = true;
                }
                if (!notesSet && !profile.notes().isBlank()) {
                    notes = profile.notes();
                    notesSet = true;
                }
                if (!intervalSet && profile.intervalSeconds() > 0) {
                    interval = profile.intervalSeconds();
                    intervalSet = true;
                }
            }
            int parentEnd = name.lastIndexOf("::");
            name = parentEnd < 0 ? "" : name.substring(0, parentEnd);
        }
        return new DeckSettings(ai, prompt, correct, wrong, strategy, interval, notes);
    }

    /** @return the profile saved for exactly this deck, or null */
    public static ModConfig.@Nullable DeckProfile exact(List<ModConfig.DeckProfile> profiles, String deck) {
        for (ModConfig.DeckProfile profile : profiles) {
            if (profile.deck().equals(deck)) return profile;
        }
        return null;
    }
}
