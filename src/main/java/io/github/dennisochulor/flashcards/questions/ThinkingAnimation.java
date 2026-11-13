package io.github.dennisochulor.flashcards.questions;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.CommonColors;
import org.jspecify.annotations.Nullable;

import java.util.Locale;

/**
 * "⠹ qwen2.5:7b is comparing it with the card...  4.2s" over a gliding bar.
 * Used on the card screen and on the HUD while the player keeps playing.
 * <p>
 * Runs every frame, so the text is rebuilt only when the spinner advances (about 12 times a second)
 * and the width is measured once per model name.
 */
public final class ThinkingAnimation {
    private ThinkingAnimation() {}

    public static final int PURPLE = 0xFFC08CFF;
    private static final int GRAY = 0xFFAAAAAA;
    private static final int PURPLE_CORE = 0xFFE6D2FF;
    private static final String SPINNER = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏";
    private static final String[] STEPS = {
            "is reading your answer", "is finding the key points", "is comparing it with the card",
            "is checking for mistakes", "is choosing a grade"
    };
    private static final int FRAME_MILLIS = 80;
    private static final int BAR_GAP = 5;
    private static final int BAR_HEIGHT = 3;

    private static @Nullable String widthModel = null;
    private static int width = 0;
    private static @Nullable String lineModel = null;
    private static long lineFrame = -1;
    private static Component line = Component.empty();
    private static float alpha = 1f; // set by draw(), read by fg()

    private static int fg(int color) {
        return ((int) (((color >>> 24) & 0xFF) * alpha) << 24) | (color & 0x00FFFFFF);
    }

    /** Width that fits every step, so the animation doesn't jump around as the text changes. */
    public static int width(Font font, String model) {
        if (!model.equals(widthModel)) {
            int longest = 0;
            for (String step : STEPS) longest = Math.max(longest, font.width("⠋ " + model + " " + step + "...  000.0s"));
            width = longest;
            widthModel = model;
        }
        return width;
    }

    public static int height(Font font) {
        return font.lineHeight + BAR_GAP + BAR_HEIGHT;
    }

    /** Draws the animation with its top-left corner at (x, y), {@link #width} wide. */
    public static void draw(GuiGraphicsExtractor graphics, Font font, int x, int y, long elapsedMillis, String model) {
        draw(graphics, font, x, y, elapsedMillis, model, 1f);
    }

    /** @param alpha how strongly to draw it, 0-1 (the HUD's "text & colours" setting) */
    public static void draw(GuiGraphicsExtractor graphics, Font font, int x, int y, long elapsedMillis, String model, float alpha) {
        ThinkingAnimation.alpha = Math.max(0, Math.min(1, alpha));
        int width = width(font, model);
        long frame = elapsedMillis / FRAME_MILLIS;
        if (frame != lineFrame || !model.equals(lineModel)) {
            String spinner = String.valueOf(SPINNER.charAt((int) (frame % SPINNER.length())));
            String step = STEPS[(int) Math.min(STEPS.length - 1, elapsedMillis / 1800)];
            String dots = ".".repeat((int) (elapsedMillis / 350 % 4));
            line = Component.literal(spinner + " ").withColor(PURPLE)
                    .append(Component.literal(model + " ").withColor(CommonColors.WHITE))
                    .append(Component.literal(step + dots).withColor(GRAY))
                    .append(Component.literal(String.format(Locale.ROOT, "  %.1fs", elapsedMillis / 1000.0)).withColor(0xFF777777));
            lineFrame = frame;
            lineModel = model;
        }
        graphics.text(font, line, x, y, fg(CommonColors.WHITE), true);

        // indeterminate progress bar: a glowing segment gliding back and forth
        int barY = y + font.lineHeight + BAR_GAP;
        graphics.fill(x, barY, x + width, barY + BAR_HEIGHT, fg(0x40FFFFFF));
        int segment = Math.max(12, width / 4);
        double t = (Math.sin(elapsedMillis / 450.0) + 1) / 2; // 0..1..0
        int segX = x + (int) (t * (width - segment));
        graphics.fill(segX, barY, segX + segment, barY + BAR_HEIGHT, fg(PURPLE));
        graphics.fill(segX + segment / 4, barY, segX + segment * 3 / 4, barY + BAR_HEIGHT, fg(PURPLE_CORE));
    }
}
