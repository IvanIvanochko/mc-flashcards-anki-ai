# Flashcards: Anki + AI Edition
**Study your Anki deck in Minecraft, with answers graded by a local AI (Ollama).**

By [**IvanIvanochko**](https://github.com/IvanIvanochko). A fork of [Flashcards](https://modrinth.com/mod/flashcards) by **DennisOchulor** ([source](https://github.com/DennisOchulor/mc-flashcards)). All credit for the original mod goes to its author; this edition adds Anki sync, AI grading and more on top of it. It is **not** an official release of Flashcards, and it isn't affiliated with or endorsed by DennisOchulor, Anki/AnkiWeb, AnkiConnect, Ollama, Mojang or Microsoft.

## What this edition adds
- **Anki mode**: cards come from your real Anki deck and your answers are saved as real Anki reviews (Again / Hard / Good / Easy), so your spaced-repetition schedule keeps working while you play. Optional AnkiWeb sync on join/leave.
- **AI grading**: type your answer and a local AI grades it by meaning, with a key-point checklist, a score meter and an explanation. The score unlocks Anki's buttons. One second chance for answers that are on the right track but too vague.
- **Play while the AI thinks**: leave a slow grading with Space and keep playing. When the AI is done, its grade shows on the HUD and, if it's good enough, is applied on its own after a short countdown (auto-grading); otherwise the card comes back so you see what you missed.
- **Deck presets**: each Anki deck can have its own AI model, notes for the AI, rewards and time between cards.
- **Deck notes without prompt-writing**: tell the AI about a deck in a few sentences, with a guided form, a ready-made request for any AI, or your local AI writing them for you.
- **Editable AI prompt**: read or rewrite the whole set of instructions the AI grades by.
- **AI memory controls**: profiles and sliders for how much RAM the AI may use and how long the model stays loaded, with a live picture of your computer's memory.
- **Rewards & penalties**: optional commands for right and wrong answers, with presets from "Gentle" to "Learn or Die" that you can edit.
- **Readable cards**: normal-size text, every picture shown where it is on the card (click one for a zoomable full-screen view), and LaTeX/MathJax formulas converted to Unicode. The question and the answer each have their own scrolling area, and you choose how big things get, per deck if you like.
- **Timer & stats HUD**: countdown to the next card, session stats, Anki's due counts. Drag it anywhere and resize it.
- **Card keys you can change**, and protection against stray key presses: a card that pops up while you're running and jumping can't be skipped by the Space you were already pressing.
- **Real time or game time** for the question timer, and intervals in minutes **and seconds**.
- Searchable deck and model lists.

Everything from the original mod still works too: your own question lists and automatic/manual answer checking.

## Where everything is
Open the config with `H` (or through Mod Menu). The main screen has the question interval ("Prompt a question every [N] min [N] sec", ON/OFF) and these buttons:

| Button | What's inside |
|---|---|
| **Edit Questions** / **Open Folder** | the mod's own question lists (used when Anki mode is off) and its files |
| **Anki Sync...** | turn Anki mode on, pick your deck, AnkiWeb sync |
| **AI Grading...** | turn AI grading on, pick the model, score cut-offs, "keep playing" settings, **AI prompt...**, **Memory & performance...** |
| **Deck Presets...** | per-deck AI model, notes for the AI (and, advanced, a whole prompt), rewards, time between cards and card size |
| **Rewards & Penalties...** | commands for right and wrong answers, with presets |
| **HUD Layout...** | move and resize the study panel and the AI thinking indicator |
| **General...** | answer checking for the mod's own questions, real time / game time, HUD on/off, **Card Size...**, **Card Keys...** |
| **Stats** | your all-time totals |

All settings are saved in `config/flashcards/config.json`, so they survive restarts.

## Requirements
- Minecraft **26.3** with [Fabric Loader](https://fabricmc.net/) 0.19.3+ and [Fabric API](https://modrinth.com/mod/fabric-api). [Mod Menu](https://modrinth.com/mod/modmenu) is optional.
- For Anki mode: [Anki](https://apps.ankiweb.net/) desktop with the [AnkiConnect](https://ankiweb.net/shared/info/2055492159) add-on, open while you play.
- For AI grading: [Ollama](https://ollama.com) with a model downloaded. About 5 GB of free RAM for a 7–8B model (e.g. `qwen2.5:7b`), less for a 3B one.

## Install
1. Build the jar (see [Building](#building)) or take it from a release, then put `flashcards-anki-ai-<version>.jar` into your `mods` folder.
2. **Remove the original Flashcards jar.** This edition uses the same mod id (`flashcards`) so your existing questions, stats and settings in `config/flashcards/` carry over, which also means the two can't be installed together.
3. Open the config in game with `H` (or through Mod Menu).

## Privacy
Everything runs on your computer. The mod only talks to Anki (AnkiConnect, `127.0.0.1:8765`) and Ollama (`127.0.0.1:11434`) on your own machine; your cards and answers are never sent anywhere by the mod. The only internet traffic is Anki's own AnkiWeb sync, and only if you turn **Sync with AnkiWeb** on. No telemetry.

## Anki mode
1. Install the [AnkiConnect](https://ankiweb.net/shared/info/2055492159) add-on in Anki desktop (Tools → Add-ons → Get Add-ons → code `2055492159`) and restart Anki.
2. Keep Anki open while you play.
3. In Minecraft, open the Flashcards config (default key `H`) → **Anki Sync...**, turn **Use Anki cards** ON and click your deck in the list (type in the search box to filter; subdecks are indented under their parent).

How it works:
- Cards come from Anki's own review queue for that deck, so daily limits, learning steps and new cards behave exactly as in Anki.
- The card is shown at normal text size. The **question** has its own area: it grows to fit the question and scrolls when the screen runs out. The **answer** (and, with AI grading, your answer and the grade) appears in a second area below it, which grows only up to a limit you set and then scrolls on its own, so a long answer never pushes the question off the screen. Scroll with the mouse wheel over either area, or with the arrow keys, Page Up/Down, Home/End. See [Card size and pictures](#card-size-and-pictures).
- After **Show Answer** you grade with **Again / Hard / Good / Easy**, each showing Anki's next interval. Keys: Space/Enter shows the answer, 1-4 grades, Space/Enter = Good (all changeable, see [Card keys](#card-keys)).
- **Again** counts as a wrong answer for [rewards and penalties](#rewards-and-penalties), the other buttons count as correct.
- With **Sync with AnkiWeb on join/leave** ON, Anki syncs with AnkiWeb when you join and leave a world (you must be logged in to AnkiWeb inside Anki).
- Question screens pause the game in singleplayer.
- **All pictures** on a card are shown, in the place they have on the card, between the text. They're copied from Anki into `config/flashcards/media/anki/`. A picture in a format Minecraft can't draw (SVG, WebP) is replaced by a short note naming it. Audio isn't played.

### Card size and pictures
**Pictures** are shown at their natural size, scaled down only when they'd be wider than the card or taller than the limit below. **Click a picture** to open it nearly full screen:
- scroll (or `+` / `-`) zooms in and out around the mouse, dragging moves the picture, the arrow keys move it too,
- **Fit** (key `0`) shows the whole picture, **1:1** (key `1`) shows it at its real size, one picture pixel per screen pixel, which is the sharpest view; double-click switches between the two,
- **Close** or Esc returns to the card exactly as you left it (what you typed is kept).

**Sizes.** Three settings, each a percentage of the screen, so they hold at any window size:

| Setting | What it limits | Default |
|---|---|---|
| **Answer area** | how much of the screen's height the answer area may take before it scrolls on its own; the question gets the rest | 45% |
| **Pictures** | how tall a picture may be on the card | 40% |
| **Card width** | how wide the card is | 95% |

Where to change them:
- **On a card:** click the **cog** (⚙) in the card's top-right corner. The three sliders change the card you're looking at immediately. Then **This deck** keeps the sizes for that card's deck, **Default** keeps them for every deck without sizes of its own, and **Close** just closes the panel (unsaved sizes apply to that one card).
- **General... → Card Size...** for the default sizes.
- **Deck Presets... → Card size** for one deck, e.g. bigger pictures for a deck of diagrams.

### Math / LaTeX
Nothing to install. MathJax formulas (`\(...\)`, `\[...\]`, `$$...$$`) are converted to Unicode text that Minecraft's font can draw, for example:

| In Anki | In Minecraft |
|---|---|
| `\(\hat\beta_1\)` | βˆ₁ |
| `\(\sum_{i=1}^{n} x_i^2\)` | ∑ᵢ₌₁ⁿ xᵢ² |
| `\((X^\top X)^{-1}\)` | (Xᵀ X)⁻¹ |
| `\(\frac{RSS}{n-p-1}\)` | RSS/(n−p−1) |
| `\(\sqrt{n}\), \(\mathbb{R}^n\), \(\le\), \(\to\)` | √n, ℝⁿ, ≤, → |

Minecraft can't typeset real math, so this is an approximation: fractions are written `a/b`, letters with no Unicode superscript/subscript form fall back to `^(...)`/`_(...)`, accents that have no ready-made character are written after the letter (`βˆ` = β-hat, `x¯` = x-bar), and matrices are shown row by row. Anki's older `[latex]...[/latex]` tags are rendered by Anki itself as images, and those images are shown as normal card images.

## AI grading (type your answer)
Instead of only flipping the card, you can type your answer and a **local AI** grades it **by meaning** (0 to 1). Your wording doesn't have to match the card. Works with Anki mode.

Setup:
1. Install [Ollama](https://ollama.com) and keep it running (it serves at `http://127.0.0.1:11434`).
2. Download a model, e.g. `ollama pull qwen2.5:7b` (good grader, ~5 GB RAM). With 8 GB of RAM use a smaller one such as `qwen2.5:3b`.
3. Flashcards config → **AI Grading...**: turn **AI grading** ON, check that the status says *Connected to Ollama*, and click your model in the list.

On a card:
- Type your answer in the box. **Enter** checks it (Shift+Enter starts a new line). While the AI thinks you see what it's doing and a progress bar.
- **Slow AI?** If it thinks longer than your limit (default 15 s), a **Keep playing** button appears in the bottom-right corner (or press **Space** / Esc; the key can be changed). It closes the card and you play while the AI finishes, with a small "thinking" indicator on screen (just below the crosshair by default; move and resize it in **HUD Layout...**). When the grade is ready, the card comes back and the game pauses as usual, but never sooner than your minimum play time (default 5 s), and only once you've closed other menus and haven't just been hurt.
- With **Play while thinking** ON (default OFF) you're sent back to the game as soon as you check an answer, without waiting on the card.
- **When the AI finishes while you're playing**, see [Auto-grading](#auto-grading) below.
- **Show Answer** means "I don't know": you see the answer and only **Again** is available.
- **Second chance:** if your answer is *on the right track but too vague* (something true is in it, it just isn't pinned down) and it would fail (Again), the AI tells you what to be more specific about (without giving the answer away) and you can try once more; your first answer stays in the box to extend. There's no third try: after the second answer you get the grade, whatever it is. "I don't know", excuses, off-topic and wrong answers don't get a second chance, they're simply Again.
- After grading you see the question, your answer, the AI's grade (a badge, plus a meter showing where your score lands between your cut-offs) with a **key-point checklist** (✔ correct, ~ partly, ✘ missing/wrong) and an explanation, then the card's answer.
- All four buttons are shown, but the score unlocks them: **Again** is always clickable, **Hard** from 0.50, **Good** from 0.70, **Easy** from 0.90. For example, 0.73 allows Again, Hard or Good. **Enter** picks the best unlocked button; 1-4 also work.
- If Ollama isn't reachable or the reply can't be read, you can **Retry** or **Grade myself** (plain Anki buttons).

What the grades mean (the AI is given exactly these, and they're in the button tooltips). Only what the question asks for counts; extra material on the card is a bonus. Time taken doesn't matter.

| Grade | Meaning |
|---|---|
| **Again** | You didn't remember it: the core idea is missing or wrong, a key point is factually wrong, the answer is too vague to show understanding, or you didn't know. |
| **Hard** | You remembered only part of it: the core idea is right, but a key point is missing, or something the question explicitly asks for (an example, an analogy, a formula...) is missing. |
| **Good** | You remembered it properly: the core idea and most key points are right; only minor details or exact terms are missing, and nothing is wrong. |
| **Easy** | You know it completely: every key point the question asks for, stated precisely, no gaps. |

If the question explicitly asks for something (e.g. "with an analogy") and it's missing, the grade is at most Hard. The AI's score always lands inside its grade's band, using your cut-offs.

Settings in **AI Grading...** (saved in `config/flashcards/config.json`, so they survive restarts):
- **Score needed for each button**: the Hard / Good / Easy cut-offs (↺ resets to 0.50 / 0.70 / 0.90). They always stay in order Hard ≤ Good ≤ Easy.
- **Keep playing after N s** (default 15, 0 = at once): when the Keep playing button appears.
- **Play at least N s** (default 5): minimum time in the game before a graded card comes back.
- **Play while thinking** (default OFF): go back to the game right away every time.
- **HUD layout...**: move and resize the panel shown while you play (the thinking animation, then the AI's result).
- **Auto-grade...**: what happens when the AI finishes while you're playing, see [Auto-grading](#auto-grading).
- **AI prompt...**: read or rewrite the grading instructions, see [The AI prompt](#the-ai-prompt).
- **Memory & performance...**: how much of your computer the AI may use, see [Memory and performance](#memory-and-performance).
- The model list shows roughly how much RAM each model needs, green if it fits your AI memory budget.
- **Adjusting score** (default OFF): when ON, a slider under the result lets you change the score if you disagree with the AI, which unlocks buttons accordingly.

Small models sometimes misjudge, which is why Again is always available and score adjusting exists.

### Auto-grading
When the AI finishes grading while you're playing (with *Play while thinking*, or after *Keep playing*), the thinking animation is replaced by the **result panel**:

- a badge with the grade in its colour (e.g. **GOOD**) and the score,
- the countdown: one pip per second and "auto in 3s", plus a line along the panel's bottom edge that shrinks,
- a score meter: the Again / Hard / Good / Easy bands split at your cut-offs, the band you reached lit up, a marker at your score,
- the start of the AI's explanation, cut off with "..." after a set number of characters,
- a key cap showing which key opens the card: **R** by default ("Open graded card", changeable in Minecraft's Controls under the Flashcards category).

The result panel is its own element: move and resize it in **HUD Layout...** (it's the third panel there, next to the study panel and the thinking indicator).

If you don't press the key before the countdown ends, the AI's grade is applied on its own: the best button the score unlocks (0.83 → Good with the default cut-offs) is sent to Anki, and the action bar says "Auto-graded Good (0.83)". Press the key instead and the card opens as usual, so you can read everything and pick the grade yourself. The countdown pauses while a menu is open.

**Which grades are applied on their own** is up to you, for all decks and per deck: four switches, **Again**, **Hard**, **Good** and **Easy**, in the look of the card's grade buttons (a switched-on grade looks pressable, with a bright strip in its colour; a switched-off one looks locked, with a faint strip). A grade that's switched off brings the card back on screen instead, as before (after the minimum play time), so you see what you missed. By default **Good** and **Easy** are on. Any combination works: all off is the same as auto-grading off, all on applies every grade.

Settings in **AI Grading... → Auto-grade...**:
- **Auto-grading** ON/OFF (default ON). OFF: a graded card always comes back for you to grade.
- **Apply these grades on their own**: the four switches above, for decks that don't have their own choice.
- **Countdown**: 1-30 seconds (default 5).
- **Explanation on the HUD**: how many characters of the explanation to show before "..." (default 140).

Per deck: **Deck Presets... → Auto-grade**. The same four switches; they show the default until you click one, which gives the deck its own choice (the label then says *own*, and ↺ goes back to the default). For example, all on for an easy vocabulary deck, all off for a deck you want to review carefully.

A card that the AI found too vague (second chance) or couldn't grade always comes back on screen, and the panel says so. The key opens it straight away there too.

### The AI prompt
**AI Grading... → AI prompt** shows the full instructions the AI grades by, and lets you rewrite them. *Reset to built-in* brings the default back; while you haven't changed anything, you keep getting the improved default when the mod updates.

- `{hard}`, `{good}` and `{easy}` are replaced by your score cut-offs.
- The *shape* of the AI's reply (key points, vague flag, grade, score, feedback) is enforced separately from the prompt, so rewording can change how answers are judged but can't break reading the grade.
- Paste works (Ctrl/Cmd+V), so you can write the prompt in a normal text editor.

You rarely need to touch this. To make the AI grade one deck better, give that deck **notes** instead (next section): a few sentences, and three ways to get them without writing a prompt.

### Deck notes
**Deck Presets... → pick a deck → AI deck notes...** A deck's notes are a few sentences that tell the AI about that deck: the subject and level, how strict to be, what an answer must contain, the language you answer in. They're *added* to the grading prompt under a heading that tells the AI they can't change the grades or the reply format, so the grading rules stay intact whatever the notes say.

You can type them, or get them one of three ways. Every button on the screen says what it does when you hover it, and a status line tells you what just happened.

| Button | What it does | AI involved |
|---|---|---|
| **1. Guided form...** | Asks a few questions: what the deck is about, what kind of cards it has (explanations, definitions, vocabulary, facts), how strict to be, whether formulas and exact numbers are required, and which language you answer in. The notes are put together from your answers and shown as you go. | none, works offline |
| **2. Copy request for an AI** then **Paste the AI's answer** | Copies a ready-made message to your clipboard. It explains what deck notes are, gives the rules for writing them, and includes 8 sample cards spread across the deck (and whatever you've typed so far, to improve on). Paste it into any AI (ChatGPT, Claude, ...), copy what it answers, come back and press *Paste the AI's answer*, which also tidies the text (quotes, bullet points, "Here are your notes:"). | any AI you like; **pasting it into an online AI sends those sample cards to that service** |
| **3. Write with local AI** | Your own Ollama model (the deck's model, or the main one) reads the same 8 sample cards and writes the notes straight into the box. A small local model writes simpler notes than a big online AI, so read them through. | local only, nothing leaves your computer |

Also on the screen:
- **Undo** brings back the text from before the last button changed it (press again to redo); **Clear** removes the notes.
- **Whole prompt...** (advanced) replaces the *entire* grading prompt for this deck instead of adding notes. It opens as a copy of the main prompt for you to edit, with a reset button. Notes and a whole prompt can be used together; the notes are added to whichever prompt the deck uses.
- Reading sample cards doesn't touch your Anki schedule. If Anki isn't reachable, the request is copied (or the local AI asked) without samples, and you're told so.

What good notes look like (the guided form and the AI request both aim for this):
- **Say what the deck is about**: "These cards are from a university econometrics course." Models grade noticeably better when they know the subject and level.
- **Set the strictness**: "Exact terms matter here: count a missing technical term as a missing key point", or the opposite, "Only the main idea matters".
- **Say what must be there**: "For formula cards, the formula itself must be stated", "Dates must be within 5 years".
- **Languages**: "The cards are in English but I answer in Ukrainian; that's fine. Write feedback in Ukrainian."
- **Vocabulary decks**: "The question is a word; the answer is correct if it gives any one accepted translation."

### Memory and performance
The AI runs on the same computer as the game, and on most machines (all Apple Silicon Macs) it uses the same memory and graphics chip, so a model that's too big makes the game stutter. **AI Grading... → Memory & performance...** is where you decide who gets what.

**Profiles** set everything in one click (they're worked out from your computer's RAM and Minecraft's memory setting):

| Profile | AI memory budget | Model stays loaded | Best for |
|---|---|---|---|
| **Recommended** | what's left after Minecraft and the system (at most 45% of your RAM) | 10 minutes after a card | most people |
| **Minecraft first** | small (at most 25% of your RAM) | 1 minute, so its memory is free while you play | smoothest game; the model is loaded again while you type the next answer |
| **AI speed first** | large (up to 65% of your RAM) | 1 hour | fastest grading, with plenty of RAM |
| **Custom** | your own numbers | | appears when you move a slider |

The sliders:
- **AI memory budget** (in GB, with the percentage of your RAM). Ollama has no "use at most N GB" switch: the AI's memory is decided by *which model* is loaded. So the budget is the yardstick. Every model list shows roughly how much RAM each model needs, green if it fits the budget and yellow if it doesn't, and this screen warns you when the chosen model is too big and offers a **Switch to ...** button for the largest installed model that fits.
- **Keep loaded** (0-120 minutes after grading): the real lever for how long the AI holds its memory. Short frees memory while you play, at the cost of loading the model again (a few seconds, while you type). Long keeps it always ready.
- **CPU threads**: only matters when the model runs on the CPU; fewer threads leave more of the processor for the game.

**The live memory bar** shows your whole computer, refreshed every two seconds:
- green: Minecraft (solid = in use now, faint = what it may still grow to),
- the purple outline: your AI budget, with the solid purple inside it = the model that's loaded right now (red where it spills over the budget), and when it will be unloaded,
- the rest: the system and your other apps.

Open it while a card is being graded and you'll see the model appear in the bar; with a short "keep loaded" you'll see it leave again.

What the mod does on its own to keep grading quick:
- **While you type**, the model is loaded and reads its instructions, the question and the card's answer. When you press Enter it only has to read your answer, so it starts writing the grade almost at once, and the heavy reading happens while the card is still on screen.
- Reasoning models (qwen3, deepseek-r1...) are told to skip their "thinking out loud" phase.
- The reply is kept short and capped, and the AI's working memory (context) is fixed at a modest size.

If the game still stutters: a smaller model (`qwen2.5:3b`, `llama3.2:3b`) helps the most, then the *Minecraft first* profile, then lowering Minecraft's render distance. With [deck presets](#deck-presets) you can keep a big model only for the decks that need it.

## Deck presets
Flashcards config → **Deck Presets...** Pick a deck on the left and give it its own:
- **AI model**: opens a searchable list of your installed models, with the RAM each one needs. E.g. a bigger model for a hard subject, a small fast one for vocabulary.
- **AI deck notes**: a few sentences about the deck for the grading AI, with helpers that write them for you, see [Deck notes](#deck-notes). Replacing the whole prompt for a deck is in there too, as an advanced option.
- **Rewards**: its own rewards and penalties, from the same presets as the main ones, or *Same as main*.
- **Time between cards**: click to switch from *Default* to *Own*, then type the minutes and seconds. E.g. every 2 minutes for a deck you're cramming, every 10 for one you only maintain.
- **Card size**: its own limits for the answer area, pictures and card width, see [Card size and pictures](#card-size-and-pictures). The cog on a card of that deck sets the same thing.
- **Auto-grade**: which AI grades are applied on their own for this deck when it's graded while you play, see [Auto-grading](#auto-grading).

A blue dot marks decks that have a preset. How presets apply:
- The preset of the deck the **card** is in is used, so when you study a parent deck, each subdeck's cards follow that subdeck's preset.
- Anything a deck leaves on *Default* comes from its parent deck, then from the main settings. Each part is looked up separately: a subdeck can set only its own model and still use its parent's notes.
- **Time between cards** needs one extra rule, because the countdown runs before the next card is known: after you answer a card, the wait until the next question is the time of *that card's* deck. When no card has been shown yet (you just joined a world, or changed settings), it's the time of the deck picked in **Anki Sync...**. The HUD countdown and its progress bar follow whichever time is in use.
- *Clear this deck's preset* puts everything back to Default.

Score cut-offs, the second chance and the "keep playing" settings are shared by all decks.

## Timer and study HUD
A small panel shows:
- the countdown to the next question with a progress bar (yellow in the last 10 seconds),
- when a card is due but not showing yet, a **Waiting** line saying why: another menu is open ("close this menu"), you just took damage ("you were hurt · 7s"; cards wait 10 seconds after damage so they don't appear mid-fight), or the card is still coming from Anki,
- how many cards you answered this session (correct ✔ / wrong ✘),
- in Anki mode: cards reviewed today in Anki (all devices) and what's still **Due** in your deck as three numbers in Anki's own colours and order: blue = new, red = learning, green = to review (after Anki's daily limits).

**Moving and resizing:** Flashcards config → **HUD Layout...** Drag any of the three panels (the study panel, the AI thinking indicator, the AI result panel) anywhere; Tab switches between them; scroll over one (or press `+` / `-`) to resize it from 50% to 300%; arrow keys nudge by a pixel (Shift = 10). Panels snap to the screen's centre lines and can't leave the screen, and they keep their relative position if you resize the window. **Reset** puts all three back.

### Card keys
Flashcards config → **General... → Card Keys...** Click a key button and press the new key (Esc cancels).

| Action | Default |
|---|---|
| Show answer / pick the suggested grade | Space (Enter always works too) |
| 1st to 4th grade button (Again, Hard, Good, Easy) | 1, 2, 3, 4 (the number pad always works too) |
| Keep playing while the AI grades | Space (Esc always works too) |

**Stray key protection.** A card can pop up while you're running and jumping, with Space and W held down. So when a card appears (also when it comes back after AI grading), two delays apply, both adjustable on this screen:
- **Card keys** (the keys in the table, plus Enter) wait **1 second**, so the Space you were jumping with can't flip or answer the card. A small "Space unlocks in ..." note shows meanwhile.
- **Typing** waits only **0.2 seconds**, so you can start writing your answer straight away. A Space pressed into a still-empty answer box is ignored.

In both cases a key you're still holding (movement, jump, a card key) has to be let go first, so it can't type letters or count as a press. The mouse always works. After each step on a card (e.g. showing the answer) there's only a very short pause on the card keys, so one long key press can't count twice.

Other settings are in Flashcards config → **General...**:
- **Question timer: Real time / Game time**
  - *Real time* (default) counts real-world minutes, even while the game is paused or a menu is open.
  - *Game time* counts only while the world is running, so it stops while the game is paused and follows `/tick rate`. 1 minute = 1200 game ticks (a full Minecraft day is 20 minutes). In multiplayer the game never pauses, so this behaves almost like real time.
- **Timer & stats HUD: ON/OFF**. The HUD is hidden with F1 and while the F3 debug screen is open.

The interval itself is set on the main config screen: "Prompt a question every [N] min [N] sec" (at least 1 second in total). A deck can have its own time, see [Deck presets](#deck-presets).

## Rewards and penalties
Optional. Flashcards config → **Rewards & Penalties...** lets commands run when you answer right (in Anki mode: Hard, Good or Easy) or wrong (Again).

Pick a **preset**, then change anything you like in the two command boxes (one command per line, no `/`); editing turns the preset into *Custom*, and your custom commands are kept while you browse the other presets.

| Preset | Right answer | Wrong answer |
|---|---|---|
| **None** | nothing | nothing |
| **Gentle** | 5 XP, 5 s of regeneration | 10 s of slowness |
| **Balanced** | 10 XP, food, 30 s of speed | 20 s of hunger, 30 s of mining fatigue |
| **Tough** | 15 XP, extra (absorption) hearts for a minute | 3 hearts of damage, weakness, darkness |
| **Learn or Die** | 20 XP, regeneration, resistance | you die |
| **Lucky Dip** | one random prize: iron, gold, a diamond, emeralds, a golden apple, XP bottles or XP | one random mishap: nausea, hunger, levitation, 2 hearts of damage, a lightning strike or a zombie |

**Mode** decides how the boxes are used: *Execute All* runs every line, *Randomise One* picks one line at random (Lucky Dip uses this), *OFF* runs nothing.

- **Singleplayer** (and when you host a LAN world): the commands are run by the world itself, so they work even if the world was created with cheats off, and they don't fill your chat. If a line isn't a valid command, you get a chat message saying which one.
- **Multiplayer:** the commands are sent as you, so the server must allow you to run them (operator permission).
- Commands run as you, at your position: `@s` is you and `~ ~ ~` is where you stand.

## Building
Needs JDK 25 and [Gradle](https://gradle.org/install/) 9.5.1 (the repo has no `gradlew` script).
```
gradle build
```
The mod jar ends up in `build/libs/` (the `-sources` jar is not the one to install).

## Credits and license
- [IvanIvanochko](https://github.com/IvanIvanochko): author of the Anki + AI Edition.
- [Flashcards](https://github.com/DennisOchulor/mc-flashcards) by DennisOchulor: the original mod this edition is built on.
- [AnkiConnect](https://ankiweb.net/shared/info/2055492159) for talking to Anki, and [Ollama](https://ollama.com) for running the AI locally.

Released under the MIT License, like the original. The original copyright and license notice are kept in [LICENSE](LICENSE), as the MIT License requires.
