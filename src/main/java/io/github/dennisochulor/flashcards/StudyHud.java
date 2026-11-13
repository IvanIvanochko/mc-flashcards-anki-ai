package io.github.dennisochulor.flashcards;

import io.github.dennisochulor.flashcards.anki.AnkiSession;
import io.github.dennisochulor.flashcards.anki.StudyStats;
import io.github.dennisochulor.flashcards.config.ModConfig;
import io.github.dennisochulor.flashcards.questions.AnkiQuestionScreen;
import io.github.dennisochulor.flashcards.questions.QuestionScheduler;
import io.github.dennisochulor.flashcards.questions.ThinkingAnimation;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.CommonColors;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The on-screen study panel (countdown to the next question plus session / Anki numbers) and the
 * "AI is thinking" indicator shown while the player keeps playing. Both can be placed and scaled freely
 * (see {@link ModConfig.Placement} and the HUD layout screen).
 * <p>
 * This runs every frame, so the panel's text is built into a {@link Panel} only when something visible changes
 * (about once a second while counting down); a frame normally just draws the cached lines.
 */
public final class StudyHud {
    private StudyHud() {}

    // shared look of both panels
    private static final int PAD = 6;
    private static final int ACCENT_WIDTH = 2;
    private static final int ROW_HEIGHT = 11;
    private static final int COLUMN_GAP = 12;
    private static final int MIN_INNER_WIDTH = 92;
    private static final int PANEL_FILL = 0xCC0E0F14;
    private static final int PANEL_EDGE = 0x30FFFFFF;
    private static final int BAR_TRACK = 0x38FFFFFF;

    private static final int GRAY = 0xFFA0A4AE;
    private static final int DIM = 0xFF6F7480;
    private static final int GREEN = 0xFF5FD07A;
    private static final int YELLOW = 0xFFFFD75A;
    private static final int RED = 0xFFFF7070;
    private static final int BLUE = 0xFF6EA8FF;
    private static final int SLATE = 0xFF8A93A6;

    private record Row(Component label, Component value) {}

    /** Everything the stats panel shows, laid out once. */
    private record Panel(Component title, Component value, int accent, boolean showBar, List<Row> rows, int width, int height) {}

    /** True while the HUD layout screen is open: it draws the panels itself, so the live ones would only be in the way. */
    public static boolean editingLayout = false;

    private static @Nullable Panel panel = null;
    private static long panelKey = Long.MIN_VALUE;
    private static @Nullable ModConfig panelConfig = null;
    private static StudyStats.@Nullable AnkiStats panelAnki = null;

    // ---------------------------------------------------------------- HUD entry point

    public static void extract(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        if (editingLayout || minecraft.player == null || minecraft.getDebugOverlay().showDebugScreen()) return;
        ModConfig config = FileManager.getConfig();

        AnkiQuestionScreen waiting = AnkiQuestionScreen.backgroundCard();
        if (waiting != null) {
            if (waiting.isGrading()) {
                drawIndicator(graphics, minecraft.font, config.indicatorPlacement(), waiting.modelName(), waiting.gradingElapsedMillis(), 0);
            }
            else drawStatus(graphics, minecraft.font, config.resultPlacement(), waiting.backgroundStatus());
        }
        if (config.hudEnabled()) drawStats(graphics, minecraft, config, config.hudPlacement());
    }

    // ---------------------------------------------------------------- stats panel

    /** @return {width, height} of the stats panel before scaling */
    public static int[] statsSize(Minecraft minecraft, ModConfig config) {
        Panel p = panel(minecraft, config);
        return new int[] {p.width(), p.height()};
    }

    public static void drawStats(GuiGraphicsExtractor graphics, Minecraft minecraft, ModConfig config, ModConfig.Placement placement) {
        Panel p = panel(minecraft, config);
        Font font = minecraft.font;
        float scale = placement.scale().floatValue();
        int left = placement.left(graphics.guiWidth(), p.width());
        int top = placement.top(graphics.guiHeight(), p.height());

        graphics.pose().pushMatrix();
        graphics.pose().translate(left, top);
        graphics.pose().scale(scale, scale);

        drawPanelBackground(graphics, p.width(), p.height(), p.accent());
        int x = ACCENT_WIDTH + PAD;
        int right = p.width() - PAD;
        int y = PAD - 1;

        graphics.text(font, p.title(), x, y, CommonColors.WHITE, true);
        graphics.text(font, p.value(), right - font.width(p.value()), y, CommonColors.WHITE, true);
        y += font.lineHeight + 2;
        if (p.showBar()) {
            float done = progress();
            graphics.fill(x, y, right, y + 2, BAR_TRACK);
            graphics.fill(x, y, x + Math.round((right - x) * done), y + 2, p.accent());
        }
        y += 2 + 4;
        for (Row row : p.rows()) {
            graphics.text(font, row.label(), x, y, CommonColors.WHITE, true);
            graphics.text(font, row.value(), right - font.width(row.value()), y, CommonColors.WHITE, true);
            y += ROW_HEIGHT;
        }
        graphics.pose().popMatrix();
    }

    /** How much of the interval has passed, 0..1. Cheap, so it's read every frame to keep the bar smooth. */
    private static float progress() {
        return switch (QuestionScheduler.state()) {
            case COUNTING -> {
                long total = QuestionScheduler.totalMillis();
                yield total <= 0 ? 0 : 1 - Math.min(1f, QuestionScheduler.remainingMillis() / (float) total);
            }
            case DUE, QUESTION -> 1;
            case OFF -> 0;
        };
    }

    private static Panel panel(Minecraft minecraft, ModConfig config) {
        QuestionScheduler.State state = QuestionScheduler.state();
        long seconds = state == QuestionScheduler.State.COUNTING ? (QuestionScheduler.remainingMillis() + 999) / 1000 : -1;
        boolean paused = minecraft.isPaused();
        AnkiQuestionScreen offScreen = AnkiQuestionScreen.backgroundCard();
        boolean grading = offScreen != null && offScreen.isGrading();
        boolean graded = offScreen != null && !offScreen.isGrading();
        StudyStats.AnkiStats anki = StudyStats.anki();

        long key = seconds;
        key = key * 31 + state.ordinal();
        key = key * 31 + (paused ? 1 : 0) + (grading ? 2 : 0) + (graded ? 4 : 0);
        key = key * 31 + StudyStats.sessionCorrect();
        key = key * 31 + StudyStats.sessionWrong();
        String waiting = QuestionScheduler.waitingReason(minecraft);
        key = key * 31 + (waiting == null ? 0 : waiting.hashCode());
        Panel cached = panel;
        if (cached != null && key == panelKey && config == panelConfig && anki == panelAnki) return cached;

        Panel built = build(minecraft.font, config, state, seconds, paused, grading, graded, anki, waiting);
        panel = built;
        panelKey = key;
        panelConfig = config;
        panelAnki = anki;
        return built;
    }

    private static Panel build(Font font, ModConfig config, QuestionScheduler.State state, long seconds, boolean paused,
                               boolean grading, boolean graded, StudyStats.@Nullable AnkiStats anki, @Nullable String waiting) {
        Component title;
        Component value;
        int accent;
        boolean showBar = true;
        boolean gameTime = config.timerMode() == ModConfig.TimerMode.GAME_TIME;

        if (!config.intervalToggle()) {
            @Nullable Component key = Flashcards.promptKeyName();
            title = Component.literal("TIMER OFF").withColor(GRAY);
            value = key == null ? Component.empty() : Component.literal("press ").withColor(DIM).append(key.copy().withColor(CommonColors.WHITE));
            accent = SLATE;
            showBar = false;
        }
        else switch (state) {
            case COUNTING -> {
                boolean soon = seconds <= 10;
                title = Component.literal(gameTime ? "NEXT CARD · GAME TIME" : "NEXT CARD").withColor(GRAY);
                MutableComponent time = Component.literal(formatDuration(seconds)).withColor(soon ? YELLOW : CommonColors.WHITE);
                if (gameTime && paused) time.append(Component.literal(" paused").withColor(DIM));
                value = time;
                accent = soon ? YELLOW : GREEN;
            }
            case DUE -> {
                title = Component.literal("NEXT CARD").withColor(GRAY);
                value = Component.literal("now").withColor(YELLOW);
                accent = YELLOW;
            }
            case QUESTION -> {
                if (grading) {
                    title = Component.literal("AI GRADING").withColor(GRAY);
                    value = Component.literal("thinking").withColor(ThinkingAnimation.PURPLE);
                    accent = ThinkingAnimation.PURPLE;
                }
                else if (graded) { // the AI is done; the result is in the indicator panel
                    title = Component.literal("CARD").withColor(GRAY);
                    value = Component.literal("graded").withColor(GREEN);
                    accent = GREEN;
                }
                else if (AnkiSession.isFetching()) { // due, but not on screen yet: say so instead of "on screen"
                    title = Component.literal("NEXT CARD").withColor(GRAY);
                    value = Component.literal("now").withColor(YELLOW);
                    accent = YELLOW;
                }
                else {
                    title = Component.literal("CARD").withColor(GRAY);
                    value = Component.literal("on screen").withColor(CommonColors.WHITE);
                    accent = BLUE;
                }
            }
            default -> {
                title = Component.literal("NEXT CARD").withColor(GRAY);
                value = Component.literal("starting").withColor(DIM);
                accent = SLATE;
            }
        }

        List<Row> rows = new ArrayList<>(4);
        // a card that is due but not showing: say what it's waiting for
        if (waiting != null) rows.add(new Row(Component.literal("Waiting").withColor(YELLOW), Component.literal(waiting).withColor(CommonColors.WHITE)));
        int total = StudyStats.sessionTotal();
        MutableComponent session = Component.literal(String.valueOf(total)).withColor(CommonColors.WHITE);
        if (total > 0) {
            session.append(Component.literal("  " + StudyStats.sessionCorrect() + "✔").withColor(GREEN))
                    .append(Component.literal(" " + StudyStats.sessionWrong() + "✘").withColor(RED));
        }
        rows.add(new Row(Component.literal("Session").withColor(GRAY), session));

        if (AnkiSession.isEnabled()) {
            if (anki == null) {
                rows.add(new Row(Component.literal("Anki").withColor(GRAY), Component.literal("not connected").withColor(DIM)));
            }
            else {
                rows.add(new Row(Component.literal("Anki today").withColor(GRAY),
                        Component.literal(String.valueOf(anki.reviewedToday())).withColor(CommonColors.WHITE)));
                if (anki.hasDeckCounts()) {
                    // same colours and order as Anki's deck list: new, learning, to review
                    rows.add(new Row(Component.literal("Due").withColor(GRAY), anki.dueTotal() == 0
                            ? Component.literal("done ✔").withColor(GREEN)
                            : Component.literal(String.valueOf(anki.newCount())).withColor(BLUE)
                                .append(Component.literal("  " + anki.learnCount()).withColor(RED))
                                .append(Component.literal("  " + anki.reviewCount()).withColor(GREEN))));
                }
            }
        }

        int inner = Math.max(MIN_INNER_WIDTH, font.width(title) + COLUMN_GAP + font.width(value));
        for (Row row : rows) inner = Math.max(inner, font.width(row.label()) + COLUMN_GAP + font.width(row.value()));
        int width = ACCENT_WIDTH + PAD + inner + PAD;
        int height = (PAD - 1) + font.lineHeight + 2 + 2 + 4 + rows.size() * ROW_HEIGHT - (ROW_HEIGHT - font.lineHeight) + PAD - 1;
        return new Panel(title, value, accent, showBar, List.copyOf(rows), width, height);
    }

    /** m:ss, or h:mm:ss for long intervals. */
    static String formatDuration(long totalSeconds) {
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return hours > 0 ? hours + ":" + two(minutes) + ":" + two(seconds) : minutes + ":" + two(seconds);
    }

    private static String two(long n) {
        return n < 10 ? "0" + n : String.valueOf(n);
    }

    private static void drawPanelBackground(GuiGraphicsExtractor graphics, int width, int height, int accent) {
        graphics.fill(0, 0, width, height, PANEL_FILL);
        graphics.fill(ACCENT_WIDTH, 0, width, 1, PANEL_EDGE);                   // top highlight
        graphics.fill(width - 1, 1, width, height, 0x18FFFFFF);                 // faint right edge
        graphics.fill(ACCENT_WIDTH, height - 1, width - 1, height, 0x18FFFFFF); // faint bottom edge
        graphics.fill(0, 0, ACCENT_WIDTH, height, accent);                      // state colour
    }

    // ---------------------------------------------------------------- "AI is thinking" indicator

    /** @return {width, height} of the indicator before scaling */
    public static int[] indicatorSize(Font font, String model) {
        return new int[] {
                ACCENT_WIDTH + PAD + ThinkingAnimation.width(font, model) + PAD,
                PAD + ThinkingAnimation.height(font) + PAD
        };
    }

    /**
     * @param elapsedMillis how long the AI has been thinking, or -1 when it's done and the card is about to come back
     * @param secondsUntilReturn only used when done: the remaining minimum play time
     */
    public static void drawIndicator(GuiGraphicsExtractor graphics, Font font, ModConfig.Placement placement, String model,
                                     long elapsedMillis, int secondsUntilReturn) {
        int[] size = indicatorSize(font, model);
        float scale = placement.scale().floatValue();
        int left = placement.left(graphics.guiWidth(), size[0]);
        int top = placement.top(graphics.guiHeight(), size[1]);

        graphics.pose().pushMatrix();
        graphics.pose().translate(left, top);
        graphics.pose().scale(scale, scale);
        boolean thinking = elapsedMillis >= 0;
        drawPanelBackground(graphics, size[0], size[1], thinking ? ThinkingAnimation.PURPLE : GREEN);
        if (thinking) {
            ThinkingAnimation.draw(graphics, font, ACCENT_WIDTH + PAD, PAD, elapsedMillis, model);
        }
        else { // graded: waiting for the minimum play time, or for the player to be free
            String text = secondsUntilReturn > 0 ? "Graded · your card is back in " + secondsUntilReturn + "s" : "Graded · your card is coming back";
            int textWidth = font.width("✔ " + text);
            int x = ACCENT_WIDTH + (size[0] - ACCENT_WIDTH - textWidth) / 2;
            int y = (size[1] - font.lineHeight) / 2 + 1;
            graphics.text(font, "✔ ", x, y, GREEN, true);
            graphics.text(font, text, x + font.width("✔ "), y, CommonColors.WHITE, true);
        }
        graphics.pose().popMatrix();
    }

    // ---------------------------------------------------------------- the AI's result, while playing

    /** Inner width of the result panel. */
    private static final int RESULT_INNER_WIDTH = 236;
    private static final int METER_HEIGHT = 3;
    private static final int PIP = 4, PIP_GAP = 2, MAX_PIPS = 10;
    private static final int[] BAND_COLORS = {0xFFFF7070, 0xFFFFD75A, 0xFF5FD07A, 0xFF55AAFF}; // Again, Hard, Good, Easy

    private static String wrappedText = "";
    private static List<FormattedCharSequence> wrappedLines = List.of();

    /** Wraps the explanation once per text, not every frame. */
    private static List<FormattedCharSequence> wrap(Font font, String text) {
        if (!text.equals(wrappedText)) {
            wrappedText = text;
            wrappedLines = text.isEmpty() ? List.of() : font.split(Component.literal(text), RESULT_INNER_WIDTH);
        }
        return wrappedLines;
    }

    /** @return {width, height} of the result panel for this status, before scaling */
    public static int[] statusSize(Font font, AnkiQuestionScreen.BackgroundStatus status) {
        int height = PAD + badgeHeight(font)                                   // badge row
                + (status.score() >= 0 ? 5 + METER_HEIGHT : 0)                 // score meter
                + 5 + wrap(font, status.text()).size() * font.lineHeight       // explanation
                + 5 + keyCapHeight(font)                                       // key hint
                + PAD + (status.progress() >= 0 ? 2 : 0);                      // countdown line
        return new int[] {ACCENT_WIDTH + PAD + RESULT_INNER_WIDTH + PAD, height};
    }

    private static int badgeHeight(Font font) {
        return font.lineHeight + 5;
    }

    private static int keyCapHeight(Font font) {
        return font.lineHeight + 4;
    }

    /**
     * The AI's result for a card that's off screen, as its own HUD element:
     * <pre>
     * ┃ [ GOOD ] 0.83                 ■ ■ ■ □ □  auto in 3s
     * ┃ ▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬|▬▬▬▬▬     (Again / Hard / Good / Easy, marker at the score)
     * ┃ You explained the main idea correctly, but...
     * ┃ [R] open the card and grade it yourself
     * ▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔ (the countdown, shrinking)
     * </pre>
     */
    public static void drawStatus(GuiGraphicsExtractor graphics, Font font, ModConfig.Placement placement,
                                  AnkiQuestionScreen.BackgroundStatus status) {
        int[] size = statusSize(font, status);
        int width = size[0], height = size[1];
        float scale = placement.scale().floatValue();
        int left = placement.left(graphics.guiWidth(), width);
        int top = placement.top(graphics.guiHeight(), height);
        graphics.pose().pushMatrix();
        graphics.pose().translate(left, top);
        graphics.pose().scale(scale, scale);
        drawPanelBackground(graphics, width, height, status.color());

        int x = ACCENT_WIDTH + PAD;
        int right = width - PAD;
        int y = PAD;

        // badge: the grade in a pill of its colour, then the score
        int badgeWidth = font.width(status.label()) + 10;
        int badgeHeight = badgeHeight(font);
        graphics.fill(x, y, x + badgeWidth, y + badgeHeight, withAlpha(status.color(), 0x40));
        graphics.outline(x, y, badgeWidth, badgeHeight, withAlpha(status.color(), 0xC0));
        graphics.text(font, status.label(), x + 5, y + 3, status.color(), true);
        if (status.score() >= 0) {
            graphics.text(font, String.format(java.util.Locale.ROOT, "%.2f", status.score()), x + badgeWidth + 6, y + 3, CommonColors.WHITE, true);
        }

        // right side: what happens next, with one pip per second of the countdown
        int textX = right - font.width(status.right());
        graphics.text(font, status.right(), textX, y + 3, GRAY, true);
        if (status.progress() >= 0 && status.totalSeconds() <= MAX_PIPS) {
            int pipsWidth = status.totalSeconds() * (PIP + PIP_GAP) - PIP_GAP;
            int pipX = textX - 6 - pipsWidth;
            int pipY = y + (badgeHeight - PIP) / 2;
            for (int i = 0; i < status.totalSeconds(); i++) {
                boolean left_ = i < status.secondsLeft();
                graphics.fill(pipX, pipY, pipX + PIP, pipY + PIP, left_ ? status.color() : 0x40FFFFFF);
                pipX += PIP + PIP_GAP;
            }
        }
        y += badgeHeight;

        // score meter: Again / Hard / Good / Easy bands split at the cut-offs, the reached band bright, a marker at the score
        if (status.score() >= 0) {
            y += 5;
            double[] edges = {0, status.cutoffs()[0], status.cutoffs()[1], status.cutoffs()[2], 1};
            int meterWidth = right - x;
            int reached = 0;
            for (int band = 0; band < 4; band++) if (status.score() >= edges[band] - 1e-9) reached = band;
            for (int band = 0; band < 4; band++) {
                int from = x + (int) Math.round(edges[band] * meterWidth);
                int to = x + (int) Math.round(edges[band + 1] * meterWidth);
                if (to <= from) continue;
                graphics.fill(from, y, to - (band < 3 ? 1 : 0), y + METER_HEIGHT, withAlpha(BAND_COLORS[band], band == reached ? 0xFF : 0x50));
            }
            int marker = x + (int) Math.round(Math.min(1, Math.max(0, status.score())) * (meterWidth - 2));
            graphics.fill(marker - 1, y - 2, marker + 3, y + METER_HEIGHT + 2, 0xFF000000);
            graphics.fill(marker, y - 2, marker + 2, y + METER_HEIGHT + 2, CommonColors.WHITE);
            y += METER_HEIGHT;
        }

        // the start of the explanation
        y += 5;
        for (FormattedCharSequence line : wrap(font, status.text())) {
            graphics.text(font, line, x, y, CommonColors.WHITE, true);
            y += font.lineHeight;
        }

        // the key that opens the card, drawn as a key cap
        y += 5;
        String key = status.key();
        if (key != null) {
            int capWidth = Math.max(font.width(key) + 7, keyCapHeight(font));
            int capHeight = keyCapHeight(font);
            graphics.fill(x, y, x + capWidth, y + capHeight, 0xFF2A2D36);
            graphics.outline(x, y, capWidth, capHeight, 0xFF8A8F99);
            graphics.fill(x + 1, y + capHeight - 2, x + capWidth - 1, y + capHeight - 1, 0xFF1A1C22); // a little depth
            graphics.text(font, key, x + (capWidth - font.width(key)) / 2, y + 2, CommonColors.WHITE, false);
            graphics.text(font, status.keyAction(), x + capWidth + 5, y + 2, GRAY, true);
        }
        else {
            graphics.text(font, "Set \"Open graded card\" in Controls to open it", x, y + 2, DIM, true);
        }

        // countdown along the bottom edge
        if (status.progress() >= 0) {
            int barWidth = Math.round((width - ACCENT_WIDTH) * Math.min(1, status.progress()));
            graphics.fill(ACCENT_WIDTH, height - 2, ACCENT_WIDTH + barWidth, height, status.color());
        }
        graphics.pose().popMatrix();
    }

    private static int withAlpha(int color, int alpha) {
        return (alpha << 24) | (color & 0x00FFFFFF);
    }
}
