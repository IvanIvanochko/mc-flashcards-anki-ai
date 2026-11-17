package io.github.dennisochulor.flashcards.config;

import io.github.dennisochulor.flashcards.FileManager;
import io.github.dennisochulor.flashcards.questions.CardView;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The text of the Help screen: one page per tab, each with buttons that open the matching settings.
 * Kept in one place so it's easy to keep in step with the README.
 */
final class HelpPages {
    private static final int TEXT = 0xFFE0E0E0;
    private static final int HEADING = 0xFF9FC8FF;

    /** A button at the bottom of a page: opens a settings screen, which comes back to Help when closed. */
    record Link(String label, String tooltip, Supplier<Screen> screen) {}

    record Page(String title, String tooltip, List<CardView.Block> blocks, List<Link> links) {}

    private HelpPages() {}

    /** Builds a page from alternating "## heading" lines and paragraphs. A paragraph of "- " lines stays left-aligned. */
    private static List<CardView.Block> blocks(String... parts) {
        List<CardView.Block> blocks = new ArrayList<>();
        for (String part : parts) {
            if (part.startsWith("## ")) blocks.add(new CardView.Section(part.substring(3), HEADING));
            else blocks.add(new CardView.Text(Component.literal(part), TEXT, true));
        }
        return blocks;
    }

    private static Link ankiSync() {
        return new Link("Anki Sync...", "Turn Anki mode on and pick your deck", AnkiConfigScreen::new);
    }

    private static Link aiGrading() {
        return new Link("AI Grading...", "Turn AI grading on, pick a model, set the score cut-offs", AiGradingConfigScreen::new);
    }

    static List<Page> all() {
        List<Page> pages = new ArrayList<>();

        pages.add(new Page("Start", "What the mod does and how to set it up", blocks(
                "Flashcards pops a question up while you play, every few minutes. With Anki mode the questions are your real "
                + "Anki cards, scheduled by Anki itself. With AI grading you type the answer and a local AI grades it by meaning.",
                "## Setting up Anki",
                "- Install the AnkiConnect add-on in Anki desktop (Tools → Add-ons → Get Add-ons → code 2055492159) and restart Anki.\n"
                + "- Keep Anki open while you play.\n"
                + "- Here: Anki Sync... → turn Use Anki cards ON and click your deck (type to filter; subdecks sit under their parent).\n"
                + "- Sync with AnkiWeb ON syncs when you join and leave a world (log in to AnkiWeb inside Anki first).",
                "## Keys",
                "- H: this menu.\n"
                + "- G: a question right now.\n"
                + "- R: open a card the AI graded while you played, before it's applied on its own.\n"
                + "All three can be changed in Minecraft's Controls, under Flashcards.",
                "## The timer",
                "\"Prompt a question every N min N sec\" on the main screen sets the interval, ON/OFF next to it. General... chooses "
                + "real time (default, counts while paused) or game time. A deck can have its own interval in Deck Presets.",
                "## Privacy",
                "Everything runs on your computer: the mod only talks to Anki and Ollama on your own machine. No telemetry."),
                List.of(ankiSync(),
                        new Link("General...", "Real or game time, HUD on/off, card keys, card size", GeneralConfigScreen::new))));

        pages.add(new Page("Cards", "The card screen: areas, grades, keys, pictures and formulas", blocks(
                "## The card",
                "The question has its own area, which grows to fit. The answer (and, with AI grading, your answer and the grade) "
                + "comes in a second area below it that grows only up to a limit, then scrolls. Scroll with the wheel over an "
                + "area, or the arrow keys, Page Up/Down, Home/End.",
                "## Grades",
                "- Again: you didn't remember it (core idea missing or wrong, or too vague).\n"
                + "- Hard: only part of it: the core idea is right, a key point is missing.\n"
                + "- Good: properly: core idea and most key points, nothing wrong.\n"
                + "- Easy: completely: every key point, stated precisely.\n"
                + "Each button shows Anki's next interval. Space/Enter shows the answer, 1-4 grade, Space/Enter = Good.",
                "## Stray key protection",
                "A card can pop up while you jump around. So the card keys wait 1 second and typing 0.2 seconds, and a key you're "
                + "still holding must be let go first. Both delays can be changed in Card Keys.",
                "## Pictures",
                "All pictures on a card are shown where they are on the card. Click one to open it nearly full screen: scroll or "
                + "+/- zooms, drag moves, 0 = fit, 1 = real size, Esc goes back. SVG and WebP can't be drawn and are named instead.",
                "## Card size",
                "Three limits, in % of the screen: the answer area's height, pictures' height, the card's width. The cog in a "
                + "card's corner changes them on the spot, then This deck or Default keeps them.",
                "## Formulas",
                "MathJax formulas (\\(...\\), \\[...\\], $$...$$) are turned into Unicode text: ∑ᵢ₌₁ⁿ xᵢ², (Xᵀ X)⁻¹, √n, ℝⁿ. "
                + "Fractions become a/b. [latex] tags come as Anki's own pictures."),
                List.of(new Link("Card Keys...", "Change the card keys and the stray-key delays", CardKeysScreen::new),
                        new Link("Card Size...", "The default card sizes", () -> new CardLayoutScreen("Card Size", FileManager.getConfig().cardLayout(),
                                ModConfig.CardLayout.DEFAULT, false,
                                layout -> { if (layout != null) FileManager.updateConfig(FileManager.getConfig().withCardLayout(layout)); })))));

        pages.add(new Page("AI grading", "Typing your answers and letting a local AI grade them", blocks(
                "## Setting up",
                "- Install Ollama (ollama.com) and keep it running.\n"
                + "- Download a model, e.g. ollama pull qwen2.5:7b (about 5 GB of RAM), or qwen2.5:3b with 8 GB of RAM.\n"
                + "- AI Grading... → turn it ON, check it says Connected to Ollama, click your model.",
                "## On a card",
                "Type your answer, Enter checks it (Shift+Enter = new line). The result has a grade badge, a meter, a key-point "
                + "checklist (✔ ~ ✘) and an explanation, then the card's answer. The score unlocks the buttons: Again always, "
                + "Hard from 0.50, Good from 0.70, Easy from 0.90 (your cut-offs). Show Answer means \"I don't know\": only Again.",
                "## Second chance",
                "An answer that's on the right track but too vague, and would fail, gets one more try with a hint about what to "
                + "pin down. Wrong, off-topic and \"I don't know\" answers don't.",
                "## Playing while it thinks",
                "If the AI takes longer than your limit (15 s), Keep playing (or Space) sends you back to the game with a small "
                + "thinking indicator. With Play while thinking ON that happens straight away. The card comes back when graded, "
                + "but not before your minimum play time, not in another menu and not right after you got hurt.",
                "## Auto-grading",
                "When the grade arrives while you play, a result panel counts down. Press R to open the card; otherwise the best "
                + "unlocked grade is applied on its own. Four switches decide which grades may be applied that way (Good and Easy "
                + "by default); the others bring the card back. Per deck in Deck Presets.",
                "## Memory & performance",
                "Profiles (Recommended, Minecraft first, AI speed first) or sliders for the AI's memory budget, how long the "
                + "model stays loaded and CPU threads, with a live memory bar. If the game stutters, a smaller model helps most."),
                List.of(aiGrading(),
                        new Link("Auto-grade...", "What happens when the AI finishes while you play", AutoGradingScreen::new),
                        new Link("Memory...", "How much of your computer the AI may use", AiPerformanceScreen::new))));

        pages.add(new Page("Decks", "Different settings for different Anki decks", blocks(
                "Deck Presets gives a deck its own:",
                "- AI model, e.g. a big one for a hard subject, a small fast one for vocabulary.\n"
                + "- AI deck notes: a few sentences telling the AI about the deck (subject, strictness, language). A guided form, "
                + "a request to paste into any AI, or your local AI can write them. A whole own prompt is in there too.\n"
                + "- Time between cards.\n"
                + "- Card size.\n"
                + "- Auto-grade switches.\n"
                + "- Rewards & Penalties: its own score setup (the button opens that editor with the deck selected).",
                "## How it's looked up",
                "A dot marks decks with a preset. The preset of the card's own deck is used, so studying a parent deck, each "
                + "subdeck's cards follow their subdeck. Anything left on Default comes from the parent deck, then the main settings, "
                + "each part separately. Clear this deck's preset puts everything back to Default.",
                "## Time between cards",
                "After you answer, the wait until the next card is the time of the deck that card was in; before the first card, "
                + "it's the deck picked in Anki Sync."),
                List.of(new Link("Deck Presets...", "Give each deck its own settings", DeckPresetsScreen::new))));

        pages.add(new Page("Rewards", "The score, and commands that reward or punish you", blocks(
                "Every deck (and subdeck) has its own score. Each grade changes it by its points, by default Again −1, Hard 0, "
                + "Good +1, Easy +2. Rules then run commands: a rule on Again punishes, a rule on Good rewards. Nothing else "
                + "decides which is which.",
                "## Rules",
                "Each grade has its own list. A rule has a condition, commands (one per line, no /), and options:\n"
                + "- always, score ≥ X, score ≤ X, score between A and B, given N times, every Nth time.\n"
                + "- Once: runs when the condition becomes true, again only after it was false (e.g. the score went back up). "
                + "Every time: on every answer while it's true.\n"
                + "- Run all lines, or one line at random.\n"
                + "- Then set the score to X (e.g. 0 after a big punishment), restart the count.\n"
                + "If several rules of a grade hold at once: all that match run, or only the strongest (the furthest threshold).",
                "## Timing and fading",
                "Points are multiplied by how long ago Anki last showed the card: under 1 h ×0.5, under 1 day ×1, under 7 days "
                + "×1.5, longer ×2 (gains and losses separately, all editable, new cards ×1). Fading pulls the score and counts "
                + "halfway back to 0 every so often while you don't answer.",
                "## Presets",
                "Classic ladder, Gentle coach, Strikes, Treasure hunt, Redemption and the simple flat ones. Save your own setup "
                + "as a preset, or Copy it as text to share; Paste shows everything a pasted preset runs before you use it.",
                "## Per deck",
                "A deck follows its parent deck's setup, or the main one, until you give it its own: copied from what it uses "
                + "now, or from a preset. Its score is its own either way.",
                "## Where the score shows",
                "In the study panel, in the corner of a card, under each grade button (what it would do), and in the action bar "
                + "after you grade. Both switches are at the bottom of Rewards & Penalties.",
                "## Commands",
                "In singleplayer they run through the world, so they work with cheats off. On a server they're sent as you and "
                + "need operator permission. @s is you, ~ ~ ~ is where you stand."),
                List.of(new Link("Rewards & Penalties...", "Edit the score system", () -> new ProgressionScreen(null)))));

        pages.add(new Page("HUD", "The panels shown while you play", blocks(
                "## Study panel",
                "- The countdown to the next card, with a bar (yellow in the last 10 seconds).\n"
                + "- Waiting: why a due card isn't showing (a menu is open, you were hurt, Anki is still answering).\n"
                + "- This session: right ✔ / wrong ✘.\n"
                + "- Anki: reviewed today, and what's due as new / learning / review.\n"
                + "- The deck's score, if Score on HUD is on in Rewards & Penalties.",
                "## The other two",
                "The thinking indicator while the AI grades, and the result panel with the countdown before a grade is applied.",
                "## HUD Layout",
                "Drag a panel anywhere; Tab switches between them; scroll (or +/-) resizes 50-300%; arrows nudge (Shift = 10). "
                + "The sliders change the selected panel's look: background, text strength, how visible the study panel stays "
                + "while idle, and how much it shows. Reset puts everything back. F1 hides the HUD; General... turns it off."),
                List.of(new Link("HUD Layout...", "Move, resize and restyle the panels", HudLayoutScreen::new))));

        pages.add(new Page("Problems", "What to do when something doesn't work", blocks(
                "## \"Can't reach Anki\"",
                "Anki desktop must be open with AnkiConnect installed (code 2055492159), then restart Anki. The URL in Anki Sync "
                + "is normally http://127.0.0.1:8765.",
                "## \"Can't reach Ollama\"",
                "Start Ollama (the app, or ollama serve). The model list is empty until a model is downloaded: ollama pull qwen2.5:7b. "
                + "Retry and Grade myself on the card always work.",
                "## The game stutters while grading",
                "Use a smaller model (qwen2.5:3b), the Minecraft first profile in Memory & performance, or a lower render distance.",
                "## Grades seem off",
                "Give the deck notes in Deck Presets (subject, strictness, language). Small models misjudge sometimes; Again is "
                + "always available, and Adjusting score in AI Grading lets you move the score.",
                "## Pictures are missing",
                "SVG and WebP can't be drawn by Minecraft and are named instead. Pictures are copied from Anki the first time "
                + "the card shows; check that Anki is open.",
                "## Commands don't run",
                "On a server you need operator permission. A line that isn't a valid command shows a chat message naming it.",
                "## Keys don't respond on a card",
                "The card keys wait 1 second after a card appears, and a key you're holding must be let go first. Card Keys "
                + "changes both delays."),
                List.of(ankiSync(), aiGrading())));
        return pages;
    }
}
