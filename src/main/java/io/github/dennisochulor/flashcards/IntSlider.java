package io.github.dennisochulor.flashcards;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

import java.util.function.IntConsumer;
import java.util.function.IntFunction;

/** Slider for a whole number between min and max; the label is built from the current value. */
public class IntSlider extends AbstractSliderButton {
    private final int min;
    private final int max;
    private final IntFunction<Component> label;
    private final IntConsumer onChange;

    public IntSlider(int width, int height, int min, int max, int value, IntFunction<Component> label) {
        this(width, height, min, max, value, label, _ -> {});
    }

    /** @param onChange called when the player moves the slider (not for {@link #setIntValueSilently}) */
    public IntSlider(int width, int height, int min, int max, int value, IntFunction<Component> label, IntConsumer onChange) {
        super(0, 0, width, height, Component.empty(), (double) (Math.clamp(value, min, max) - min) / Math.max(1, max - min));
        this.min = min;
        this.max = max;
        this.label = label;
        this.onChange = onChange;
        updateMessage();
    }

    /** Moves the slider without telling the listener, e.g. when a preset sets it. */
    public void setIntValueSilently(int newValue) {
        value = (double) (Math.clamp(newValue, min, max) - min) / Math.max(1, max - min);
        updateMessage();
    }

    public int intValue() {
        return min + (int) Math.round(value * (max - min));
    }

    @Override
    protected void updateMessage() {
        setMessage(label.apply(intValue()));
    }

    @Override
    protected void applyValue() {
        onChange.accept(intValue());
    }
}
