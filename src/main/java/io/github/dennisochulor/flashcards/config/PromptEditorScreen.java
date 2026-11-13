package io.github.dennisochulor.flashcards.config;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.CommonColors;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Edits the instructions the grading AI gets, either the main ones or one deck's.
 * The box always shows the full text that would be sent; leaving it equal to the default is saved as "use the default",
 * so an untouched prompt keeps following the default when that changes.
 */
class PromptEditorScreen extends Screen {
    private static final int GRAY = 0xFFAAAAAA;
    private static final int MAX_WIDTH = 520;
    private static final int CHARACTER_LIMIT = 20000;

    private final Screen parent;
    private final String defaultText;
    private final Consumer<String> onSave;

    private final StringWidget titleText;
    private final StringWidget stateText = new StringWidget(Component.empty(), Minecraft.getInstance().font);
    private final MultiLineEditBox box;
    private final MultiLineTextWidget helpText = new MultiLineTextWidget(Component.literal(
            "{hard} {good} {easy} are replaced by your score cut-offs. Paste works (Ctrl/Cmd+V). "
            + "The reply format is enforced separately, so rewording this can't break reading the grade.").withColor(GRAY),
            Minecraft.getInstance().font);
    private final Button resetButton;
    private final Button doneButton = Button.builder(Component.literal("Done"), _ -> this.onClose()).size(100, 20).build();

    /**
     * @param title       shown at the top, e.g. "AI prompt" or "AI prompt · Statistics"
     * @param current     the saved text, or "" when the default is in use
     * @param defaultText what "use the default" means here: the built-in prompt, or the main prompt for a deck
     * @param defaultName how to call that default on the reset button, e.g. "built-in" or "main"
     * @param onSave      gets the new text, or "" for "use the default"
     */
    PromptEditorScreen(String title, String current, String defaultText, String defaultName, Consumer<String> onSave) {
        super(Component.literal(title));
        this.parent = Objects.requireNonNull(Minecraft.getInstance().gui.screen());
        this.defaultText = defaultText.strip();
        this.onSave = onSave;

        titleText = new StringWidget(Component.literal(title), Minecraft.getInstance().font);
        box = MultiLineEditBox.builder().build(Minecraft.getInstance().font, 300, 100, Component.literal("AI grading prompt"));
        box.setCharacterLimit(CHARACTER_LIMIT);
        box.setValue(current.isBlank() ? this.defaultText : current);
        box.setValueListener(_ -> updateState());

        resetButton = Button.builder(Component.literal("Reset to " + defaultName), _ -> {
            box.setValue(this.defaultText);
            updateState();
        }).tooltip(Tooltip.create(Component.literal("Throw away your changes and use the " + defaultName + " prompt again"))).size(130, 20).build();

        helpText.setCentered(true);
        updateState();
    }

    private boolean isDefault() {
        return box.getValue().strip().equals(defaultText);
    }

    private void updateState() {
        boolean isDefault = isDefault();
        resetButton.active = !isDefault;
        stateText.setMessage(isDefault ? Component.literal("Default prompt").withColor(GRAY)
                : Component.literal("Your own prompt").withColor(CommonColors.GREEN));
        stateText.setX(width / 2 - stateText.getWidth() / 2);
    }

    @Override
    protected void init() {
        doneButton.setTooltip(Tooltip.create(Component.literal("Keep this prompt and go back")));
        int boxWidth = Math.min(MAX_WIDTH, width - 24);
        int x = width / 2 - boxWidth / 2;

        titleText.setPosition(width / 2 - titleText.getWidth() / 2, 6);
        stateText.setPosition(width / 2 - stateText.getWidth() / 2, 17);

        int buttonsY = height - 26;
        resetButton.setPosition(width / 2 - 134, buttonsY);
        doneButton.setPosition(width / 2 + 4, buttonsY);
        helpText.setMaxWidth(boxWidth);
        int helpY = buttonsY - 4 - helpText.getHeight();
        helpText.setPosition(width / 2 - helpText.getWidth() / 2, helpY);

        int boxTop = 30;
        box.setRectangle(boxWidth, Math.max(40, helpY - 5 - boxTop), x, boxTop);
        box.refreshScrollAmount();

        addRenderableWidget(titleText);
        addRenderableWidget(stateText);
        addRenderableWidget(box);
        addRenderableWidget(helpText);
        addRenderableWidget(resetButton);
        addRenderableWidget(doneButton);
        setInitialFocus(box);
    }

    @Override
    public void onClose() {
        onSave.accept(isDefault() ? "" : box.getValue().strip());
        Minecraft.getInstance().gui.setScreen(parent);
    }
}
