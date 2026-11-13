package io.github.dennisochulor.flashcards.ai;

import java.util.List;

/**
 * Three ways to get good deck notes without knowing how to write a prompt:
 * a request the player can paste into any AI, the same request for the local model, and a form whose answers are
 * turned into notes directly.
 * <p>
 * Deck notes are a few sentences added to the grading prompt (see {@link GradingPrompt#system}); they describe the deck,
 * they don't restate how grading works.
 */
public final class DeckNotesWriter {
    private DeckNotesWriter() {}

    /** Longest notes the editor accepts: enough for a solid paragraph, short enough to stay "notes". */
    public static final int MAX_NOTES_LENGTH = 1500;
    public static final int SAMPLE_CARDS = 8;
    private static final int SAMPLE_SIDE_LENGTH = 280;

    /** A card shown to the note-writing AI so it can see what the deck is like. */
    public record Sample(String question, String answer) {}

    private static final String WHAT_AND_RULES = """
            I study flashcards with an app in which a small local AI grades my typed answers against each card's answer. \
            That grader already has complete general instructions: it grades by meaning rather than wording, lists the \
            key points of the card, and gives one of four grades (again, hard, good, easy) with short feedback.

            Please write short "deck notes" for the deck below. They are added to the grader's instructions so that it \
            grades THIS deck well.

            Rules for the notes:
            - 3 to 6 short sentences of plain text, at most 600 characters. No headings, no lists, no markdown.
            - Speak to the grader directly ("These cards are...", "Count ... as ...").
            - Cover what helps grading here: the subject and level; what kind of answer the cards expect (definitions, \
            explanations, formulas, translations, dates...); how strict to be about exact terms, numbers or formulas; \
            and anything students of this subject often get subtly wrong that should count as a mistake.
            - Do NOT restate general grading rules, define the grades or scores, mention JSON or the reply format, \
            or include the answers to specific cards.
            - Reply with the notes only: nothing before them, nothing after.""";

    /**
     * The complete message to paste into any AI assistant.
     * @param soFar what the player has already written as notes, or "" (the AI is asked to improve on it)
     */
    public static String requestForAnyAi(String deck, List<Sample> samples, String soFar) {
        return WHAT_AND_RULES + "\n\n" + deckPart(deck, samples, soFar);
    }

    /** The same request split for a chat model: the fixed part as its instructions... */
    public static String localSystem() {
        return WHAT_AND_RULES;
    }

    /** ...and the deck as the question. */
    public static String localUser(String deck, List<Sample> samples, String soFar) {
        return deckPart(deck, samples, soFar);
    }

    private static String deckPart(String deck, List<Sample> samples, String soFar) {
        StringBuilder text = new StringBuilder("Deck: \"").append(deck.replace("::", " > ")).append('"');
        if (!soFar.isBlank()) {
            text.append("\n\nWhat I've written so far (keep what's useful, improve the rest):\n").append(soFar.strip());
        }
        if (samples.isEmpty()) {
            text.append("\n\n(No sample cards could be attached. Work from the deck name and what I wrote.)");
        }
        else {
            text.append("\n\nSample cards from this deck:");
            for (int i = 0; i < samples.size(); i++) {
                Sample sample = samples.get(i);
                text.append('\n').append(i + 1).append(". Q: ").append(shorten(sample.question()))
                        .append("\n   A: ").append(shorten(sample.answer()));
            }
        }
        return text.toString();
    }

    private static String shorten(String side) {
        String oneLine = side.replaceAll("\\s+", " ").strip();
        if (oneLine.isEmpty()) return "(image only)";
        return oneLine.length() <= SAMPLE_SIDE_LENGTH ? oneLine : oneLine.substring(0, SAMPLE_SIDE_LENGTH).stripTrailing() + "...";
    }

    /**
     * Tidies what an AI sent back: models like to wrap the notes in quotes, a code fence or a "Here are your notes:" line.
     * Also used on text pasted in by the player, where it does no harm.
     */
    public static String clean(String reply) {
        String text = reply.strip();
        text = text.replaceAll("(?s)^```[a-zA-Z]*\\s*", "").replaceAll("(?s)\\s*```$", "").strip();
        // a short lead-in line ending in a colon, e.g. "Here are the deck notes:"
        int firstBreak = text.indexOf('\n');
        if (firstBreak > 0 && firstBreak < 80 && text.substring(0, firstBreak).strip().endsWith(":")) {
            text = text.substring(firstBreak + 1).strip();
        }
        if (text.length() >= 2 && (text.startsWith("\"") && text.endsWith("\"") || text.startsWith("“") && text.endsWith("”"))) {
            text = text.substring(1, text.length() - 1).strip();
        }
        text = text.replaceAll("(?m)^\\s*[-*•]\\s+", "").replace("**", ""); // bullets and bold markers, if it used them anyway
        text = text.replaceAll("\\n{2,}", "\n").strip();
        if (text.length() > MAX_NOTES_LENGTH) {
            int cut = text.lastIndexOf('.', MAX_NOTES_LENGTH - 1); // end on a whole sentence where possible
            text = cut > MAX_NOTES_LENGTH / 2 ? text.substring(0, cut + 1) : text.substring(0, MAX_NOTES_LENGTH);
        }
        return text;
    }

    // ---------------------------------------------------------------- guided form

    /** What kind of answer the deck's cards expect. */
    public enum CardKind {
        MIXED("Mixed", "No special rule.", ""),
        EXPLANATIONS("Explanations", "Cards that ask you to explain an idea in your own words.",
                "Answers are explanations in the student's own words: judge whether the reasoning is right, not the phrasing."),
        DEFINITIONS("Definitions", "Cards that ask what a term means.",
                "Answers are definitions: the defining property must be stated, and an example alone is not enough."),
        VOCABULARY("Vocabulary", "Cards that ask for a translation of a word or phrase.",
                "Each card asks for a translation: any one accepted translation is fully correct, and a slip of a letter or two is fine."),
        FACTS("Facts", "Cards that ask for a name, a date, a number.",
                "Answers are specific facts such as names, dates or numbers: the fact itself must be right.");

        public final String label;
        public final String explanation;
        final String sentence;

        CardKind(String label, String explanation, String sentence) {
            this.label = label;
            this.explanation = explanation;
            this.sentence = sentence;
        }
    }

    public enum Strictness {
        LENIENT("Lenient", "The main idea is enough.",
                "Be lenient: when the main idea is right, grade it good even if details are missing."),
        NORMAL("Normal", "The built-in rules, nothing added.", ""),
        STRICT("Strict", "Exact terms and details matter.",
                "Be strict: a missing technical term or an imprecise statement counts as a missing key point.");

        public final String label;
        public final String explanation;
        final String sentence;

        Strictness(String label, String explanation, String sentence) {
            this.label = label;
            this.explanation = explanation;
            this.sentence = sentence;
        }
    }

    /**
     * Turns the guided form's answers into notes. Every part is optional; an untouched form gives "".
     * @param subject        e.g. "university econometrics" or "Spanish A2 vocabulary"
     * @param answerLanguage the language the player answers in, when it differs from the cards, e.g. "Ukrainian"
     */
    public static String fromForm(String subject, CardKind kind, Strictness strictness, boolean formulasRequired,
                                  boolean exactNumbers, String answerLanguage) {
        StringBuilder notes = new StringBuilder();
        if (!subject.isBlank()) sentence(notes, "These cards are about " + stripEnd(subject) + ".");
        sentence(notes, kind.sentence);
        sentence(notes, strictness.sentence);
        if (formulasRequired) {
            sentence(notes, "When a card's answer contains a formula, the student must state it, in symbols or in words, to get more than hard.");
        }
        if (exactNumbers) sentence(notes, "Numbers, dates and units must be exact.");
        if (!answerLanguage.isBlank()) {
            String language = stripEnd(answerLanguage);
            sentence(notes, "The student answers in " + language + ", even when a card is in another language, and that is fine. Write the feedback in " + language + ".");
        }
        return notes.toString();
    }

    private static void sentence(StringBuilder notes, String sentence) {
        if (sentence.isEmpty()) return;
        if (!notes.isEmpty()) notes.append(' ');
        notes.append(sentence);
    }

    private static String stripEnd(String text) {
        return text.strip().replaceAll("[.\\s]+$", "");
    }
}
