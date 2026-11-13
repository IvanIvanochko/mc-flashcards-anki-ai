package io.github.dennisochulor.flashcards.config;

import io.github.dennisochulor.flashcards.FileManager;
import io.github.dennisochulor.flashcards.Flashcards;
import io.github.dennisochulor.flashcards.IntSlider;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Objects;

/**
 * What happens when the AI finishes grading while you're playing: its result is shown on the HUD with a countdown,
 * and unless you open the card in time, the grade is applied on its own.
 */
class AutoGradingScreen extends Screen {
    private static final int GRAY = 0xFFAAAAAA;
    private static final int WIDTH = 300;

    private final Screen parent;

    private final StringWidget titleText = new StringWidget(Component.literal("Auto-grading"), Minecraft.getInstance().font);
    private final MultiLineTextWidget intro;
    private final CycleButton<Boolean> enabledButton;
    private final StringWidget gradesLabel = new StringWidget(Component.literal("Apply these grades on their own:"), Minecraft.getInstance().font);
    private final List<GradeToggles.Toggle> gradeToggles;
    private ModConfig.AutoGrades grades;
    private final MultiLineTextWidget gradesDescription = new MultiLineTextWidget(Component.empty(), Minecraft.getInstance().font);
    private final IntSlider secondsSlider;
    private final IntSlider textSlider;
    private final Button doneButton = Button.builder(Component.literal("Done"), _ -> this.onClose())
            .tooltip(Tooltip.create(Component.literal("Save these settings and go back"))).size(100, 20).build();

    AutoGradingScreen() {
        super(Component.literal("Auto-grading"));
        parent = Objects.requireNonNull(Minecraft.getInstance().gui.screen());
        ModConfig.AutoGrading auto = FileManager.getConfig().aiGrading().autoGrading();
        String key = Flashcards.openCardKeyName();

        intro = new MultiLineTextWidget(Component.literal(
                "When the AI finishes grading while you play (with Play while thinking, or after Keep playing), its grade, score and "
                + "explanation appear on the HUD with a countdown. Press " + (key == null ? "the \"Open graded card\" key" : key)
                + " to open the card and grade it yourself; otherwise the AI's grade is applied when the countdown ends. "
                + "The key can be changed in Minecraft's Controls.").withColor(GRAY), Minecraft.getInstance().font);
        intro.setCentered(true);

        enabledButton = CycleButton.onOffBuilder(auto.enabled())
                .withTooltip(_ -> Tooltip.create(Component.literal(
                        "ON: grades are applied on their own after the countdown (only the grades chosen below).\n"
                        + "OFF: a graded card always comes back on screen for you to grade, as before.")))
                .create(0, 0, WIDTH, 20, Component.literal("Auto-grading"));

        grades = auto.grades();
        gradeToggles = GradeToggles.create(grades, changed -> {
            grades = changed;
            updateDescription();
        });
        gradesDescription.setCentered(true);
        updateDescription();

        secondsSlider = new IntSlider(WIDTH, 20, 1, ModConfig.AutoGrading.MAX_SECONDS, auto.seconds(),
                seconds -> Component.literal("Countdown: " + seconds + " s"));
        secondsSlider.setTooltip(Tooltip.create(Component.literal(
                "How long the result stays on the HUD before the grade is applied. The countdown pauses while a menu is open.")));

        textSlider = new IntSlider(WIDTH, 20, ModConfig.AutoGrading.MIN_TEXT, ModConfig.AutoGrading.MAX_TEXT, auto.textChars(),
                chars -> Component.literal("Explanation on the HUD: up to " + chars + " characters"));
        textSlider.setTooltip(Tooltip.create(Component.literal(
                "How much of the AI's explanation the HUD shows. Longer explanations are cut off with \"...\"; "
                + "open the card to read all of it.")));
    }

    /** Says in words what the switches mean right now. */
    private void updateDescription() {
        String on = grades.describe();
        String text = switch (on) {
            case "none" -> "Every graded card comes back on screen for you to grade (like auto-grading off).";
            case "all" -> "Every grade is applied on its own, Again too. The card only comes back if you press the key.";
            default -> on + ": applied on their own. The other grades bring the card back, so you see what you missed.";
        };
        gradesDescription.setMessage(Component.literal(text + " Each deck can have its own choice (Deck Presets).").withColor(GRAY));
        gradesDescription.setX(width / 2 - gradesDescription.getWidth() / 2);
    }

    @Override
    protected void init() {
        int w = Math.min(WIDTH, width - 24);
        int x = width / 2 - w / 2;
        int y = 8;
        titleText.setPosition(width / 2 - titleText.getWidth() / 2, y);
        addRenderableWidget(titleText);
        y += 14;
        intro.setMaxWidth(w);
        intro.setPosition(width / 2 - intro.getWidth() / 2, y);
        addRenderableWidget(intro);
        y += intro.getHeight() + 8;

        enabledButton.setRectangle(w, 20, x, y);
        addRenderableWidget(enabledButton);
        y += 24;
        gradesLabel.setPosition(x, y);
        addRenderableWidget(gradesLabel);
        y += 12;
        y += GradeToggles.place(gradeToggles, x, y, w) + 3;
        gradeToggles.forEach(this::addRenderableWidget);
        gradesDescription.setMaxWidth(w);
        gradesDescription.setPosition(width / 2 - gradesDescription.getWidth() / 2, y);
        addRenderableWidget(gradesDescription);
        y += gradesDescription.getHeight() + 6;
        secondsSlider.setRectangle(w, 20, x, y);
        addRenderableWidget(secondsSlider);
        y += 24;
        textSlider.setRectangle(w, 20, x, y);
        addRenderableWidget(textSlider);
        y += 24;

        doneButton.setPosition(width / 2 - doneButton.getWidth() / 2, Math.max(height - 26, y + 4));
        addRenderableWidget(doneButton);
    }

    @Override
    public void onClose() {
        ModConfig config = FileManager.getConfig();
        ModConfig.AutoGrading auto = new ModConfig.AutoGrading(enabledButton.getValue(), secondsSlider.intValue(),
                grades, textSlider.intValue());
        FileManager.updateConfig(config.withAiGrading(config.aiGrading().withAutoGrading(auto)));
        Minecraft.getInstance().gui.setScreen(parent);
    }
}
