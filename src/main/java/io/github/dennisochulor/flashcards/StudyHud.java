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

        AnkiQuestionScreen grading = AnkiQuestionScreen.backgroundCard();
        if (grading != null) {
            drawIndicator(graphics, minecraft.font, config.indicatorPlacement(), grading.modelName(),
                    grading.isGrading() ? grading.gradingElapsedMillis() : -1, grading.secondsUntilReturn());
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
        boolean grading = AnkiQuestionScreen.backgroundCard() != null;
        StudyStats.AnkiStats anki = StudyStats.anki();

        long key = seconds;
        key = key * 31 + state.ordinal();
        key = key * 31 + (paused ? 1 : 0) + (grading ? 2 : 0);
        key = key * 31 + StudyStats.sessionCorrect();
        key = key * 31 + StudyStats.sessionWrong();
        Panel cached = panel;
        if (cached != null && key == panelKey && config == panelConfig && anki == panelAnki) return cached;

        Panel built = build(minecraft.font, config, state, seconds, paused, grading, anki);
        panel = built;
        panelKey = key;
        panelConfig = config;
        panelAnki = anki;
        return built;
    }

    private static Panel build(Font font, ModConfig config, QuestionScheduler.State state, long seconds, boolean paused,
                               boolean grading, StudyStats.@Nullable AnkiStats anki) {
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
                title = Component.literal(grading ? "AI GRADING" : "CARD").withColor(GRAY);
                value = Component.literal(grading ? "thinking" : "on screen").withColor(grading ? ThinkingAnimation.PURPLE : CommonColors.WHITE);
                accent = grading ? ThinkingAnimation.PURPLE : BLUE;
            }
            default -> {
                title = Component.literal("NEXT CARD").withColor(GRAY);
                value = Component.literal("starting").withColor(DIM);
                accent = SLATE;
            }
        }

        List<Row> rows = new ArrayList<>(3);
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
}
