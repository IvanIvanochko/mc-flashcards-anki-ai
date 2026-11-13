package io.github.dennisochulor.flashcards.config;

import io.github.dennisochulor.flashcards.IntSlider;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.CommonColors;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * How big things are on a card: how far the answer area may grow, how tall pictures may be, how wide the card is.
 * Edits either the default sizes or one deck's own (which can also be "same as default").
 * The same three sliders are on the card itself, behind the cog, for adjusting while looking at a real card.
 */
class CardLayoutScreen extends Screen {
    private static final int GRAY = 0xFFAAAAAA;
    private static final int WIDTH = 300;

    private final Screen parent;
    private final boolean deckMode;
    private final Consumer<ModConfig.@Nullable CardLayout> onSave;
    private boolean sameAsDefault;

    private final StringWidget titleText;
    private final MultiLineTextWidget intro = new MultiLineTextWidget(Component.literal(
            "The question's area grows to fit the question and scrolls when the screen runs out. The answer's area grows only as far "
            + "as you allow here, then scrolls on its own. You can also change these on a card: click the cog in its corner.").withColor(GRAY),
            Minecraft.getInstance().font);
    private final StringWidget stateText = new StringWidget(Component.empty(), Minecraft.getInstance().font);
    private final IntSlider answerSlider;
    private final IntSlider imageSlider;
    private final IntSlider widthSlider;
    private final Button resetButton;
    private final Button doneButton = Button.builder(Component.literal("Done"), _ -> this.onClose())
            .tooltip(Tooltip.create(Component.literal("Save these sizes and go back"))).size(100, 20).build();

    /**
     * @param current  the sizes to start from; for a deck, null means it uses the default
     * @param fallback the default sizes (for a deck), or the built-in ones (for the default itself)
     * @param deckMode whether a deck is being edited, which adds the "same as default" choice
     * @param onSave   gets the sizes when the screen closes; null (deck only) = same as default
     */
    CardLayoutScreen(String title, ModConfig.@Nullable CardLayout current, ModConfig.CardLayout fallback, boolean deckMode,
                     Consumer<ModConfig.@Nullable CardLayout> onSave) {
        super(Component.literal(title));
        this.parent = Objects.requireNonNull(Minecraft.getInstance().gui.screen());
        this.deckMode = deckMode;
        this.onSave = onSave;
        this.sameAsDefault = deckMode && current == null;
        ModConfig.CardLayout start = current == null ? fallback : current;

        titleText = new StringWidget(Component.literal(title), Minecraft.getInstance().font);
        answerSlider = new IntSlider(WIDTH, 20, ModConfig.CardLayout.MIN_ANSWER, ModConfig.CardLayout.MAX_ANSWER, start.answerPercent(),
                percent -> Component.literal("Answer area: up to " + percent + "% of the screen's height"), _ -> edited());
        answerSlider.setTooltip(Tooltip.create(Component.literal(
                "How much of the screen's height the answer area may take. A longer answer scrolls inside it. "
                + "The question gets the rest of the screen.")));
        imageSlider = new IntSlider(WIDTH, 20, ModConfig.CardLayout.MIN_IMAGE, ModConfig.CardLayout.MAX_IMAGE, start.imagePercent(),
                percent -> Component.literal("Pictures: up to " + percent + "% of the screen's height"), _ -> edited());
        imageSlider.setTooltip(Tooltip.create(Component.literal(
                "How tall a picture may be shown on a card. Pictures are never stretched wider than the card. "
                + "Click any picture on a card to see it full size, with zoom.")));
        widthSlider = new IntSlider(WIDTH, 20, ModConfig.CardLayout.MIN_WIDTH, ModConfig.CardLayout.MAX_WIDTH, start.widthPercent(),
                percent -> Component.literal("Card width: " + percent + "% of the screen's width"), _ -> edited());
        widthSlider.setTooltip(Tooltip.create(Component.literal("How wide the card is. Narrower can be easier to read on a very wide screen.")));

        resetButton = Button.builder(Component.literal(deckMode ? "Same as default" : "Reset"), _ -> {
            answerSlider.setIntValueSilently(fallback.answerPercent());
            imageSlider.setIntValueSilently(fallback.imagePercent());
            widthSlider.setIntValueSilently(fallback.widthPercent());
            sameAsDefault = deckMode;
            updateState();
        }).tooltip(Tooltip.create(Component.literal(deckMode
                ? "Stop using sizes of its own for this deck: it follows the default sizes again."
                : "Back to the built-in sizes (answer 45%, pictures 40%, width 95%)."))).size(110, 20).build();

        intro.setCentered(true);
        updateState();
    }

    private void edited() {
        sameAsDefault = false;
        updateState();
    }

    private void updateState() {
        if (deckMode) {
            stateText.setMessage(sameAsDefault ? Component.literal("This deck uses the default sizes").withColor(GRAY)
                    : Component.literal("This deck has its own sizes").withColor(CommonColors.GREEN));
            stateText.setX(width / 2 - stateText.getWidth() / 2);
        }
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

        for (IntSlider slider : new IntSlider[] {answerSlider, imageSlider, widthSlider}) {
            slider.setRectangle(w, 20, x, y);
            addRenderableWidget(slider);
            y += 24;
        }
        if (deckMode) {
            stateText.setPosition(width / 2 - stateText.getWidth() / 2, y + 2);
            addRenderableWidget(stateText);
        }

        int buttonsY = Math.max(height - 26, y + 16);
        resetButton.setPosition(width / 2 - 107, buttonsY);
        doneButton.setPosition(width / 2 + 7, buttonsY);
        addRenderableWidget(resetButton);
        addRenderableWidget(doneButton);
    }

    @Override
    public void onClose() {
        onSave.accept(sameAsDefault ? null
                : new ModConfig.CardLayout(answerSlider.intValue(), imageSlider.intValue(), widthSlider.intValue()));
        Minecraft.getInstance().gui.setScreen(parent);
    }
}
