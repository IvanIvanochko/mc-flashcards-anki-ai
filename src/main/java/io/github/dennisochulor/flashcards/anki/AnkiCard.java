package io.github.dennisochulor.flashcards.anki;

import java.util.List;

/**
 * A card from Anki's review queue, ready to show in-game.
 *
 * @param question      all the question's text (without pictures)
 * @param answer        all the answer's text (without pictures)
 * @param buttons       the ease values Anki offers for this card (1 = Again ... 4 = Easy)
 * @param nextReviews   Anki's interval preview for each button, e.g. "&lt;10m", "4d" (same order as {@code buttons}, may be empty)
 * @param questionForAi the question as plain text with formulas as LaTeX, for the grading AI
 * @param answerForAi   the answer as plain text with formulas as LaTeX, for the grading AI
 * @param questionParts the question's text and pictures in the order they appear on the card
 * @param answerParts   the same for the answer
 */
public record AnkiCard(long cardId, String deckName, String question, String answer,
                       List<Integer> buttons, List<String> nextReviews,
                       String questionForAi, String answerForAi,
                       List<Part> questionParts, List<Part> answerParts) {

    /**
     * A run of text, or one picture; exactly one of the two is set.
     * @param image the picture's name relative to the mod's media folder
     *              (see {@link io.github.dennisochulor.flashcards.FileManager#getImageFile}), or "" for a text part
     */
    public record Part(String text, String image) {
        public boolean isImage() {
            return !image.isEmpty();
        }
    }
}
