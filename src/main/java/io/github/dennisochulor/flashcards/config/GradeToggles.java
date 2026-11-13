package io.github.dennisochulor.flashcards.config;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.CommonColors;

import java.util.List;
import java.util.function.Consumer;

/**
 * Four switches in the look of the card's grade buttons (Again, Hard, Good, Easy): a switched-on grade looks like a
 * pressable button with a bright strip in its colour, a switched-off one like a locked button with a faint strip.
 * Used to choose which AI grades are applied on their own.
 */
public final class GradeToggles {
    private GradeToggles() {}

    public static final String[] NAMES = {"Again", "Hard", "Good", "Easy"};
    public static final int[] COLORS = {CommonColors.SOFT_RED, CommonColors.SOFT_YELLOW, CommonColors.GREEN, 0xFF55AAFF};
    private static final int GAP = 4;

    private static final Identifier BUTTON = Identifier.withDefaultNamespace("widget/button");
    private static final Identifier BUTTON_LOCKED = Identifier.withDefaultNamespace("widget/button_disabled");
    private static final Identifier BUTTON_HOVERED = Identifier.withDefaultNamespace("widget/button_highlighted");

    /**
     * One grade's switch. Draws Minecraft's own button pictures itself (it can't extend the button class, whose
     * drawing method this mod's access settings change), so on/off can look like pressable/locked while staying clickable.
     */
    public static final class Toggle extends AbstractWidget {
        private final int index;
        private final Consumer<Toggle> onChange;
        private boolean on;

        Toggle(int index, boolean on, Consumer<Toggle> onChange) {
            super(0, 0, 50, 20, Component.literal(NAMES[index]));
            this.index = index;
            this.on = on;
            this.onChange = onChange;
            updateTooltip();
        }

        public boolean isOn() {
            return on;
        }

        public void setOn(boolean on) {
            this.on = on;
            updateTooltip();
        }

        private void updateTooltip() {
            setTooltip(Tooltip.create(Component.literal(NAMES[index] + (on
                    ? ": ON. When the AI gives this grade while you play, it's applied on its own after the countdown."
                    : ": OFF. When the AI gives this grade while you play, the card comes back for you to grade.")
                    + "\nClick to switch.")));
        }

        private void toggle() {
            on = !on;
            updateTooltip();
            onChange.accept(this);
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            toggle();
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            if (isFocused() && (event.key() == InputConstants.KEY_SPACE || event.key() == InputConstants.KEY_RETURN)) {
                playDownSound(Minecraft.getInstance().getSoundManager());
                toggle();
                return true;
            }
            return false;
        }

        @Override
        public void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            // "pressable" when on and "locked" when off, like the card's grade buttons
            Identifier sprite = !on ? BUTTON_LOCKED : isHoveredOrFocused() ? BUTTON_HOVERED : BUTTON;
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, getX(), getY(), getWidth(), getHeight());

            Font font = Minecraft.getInstance().font;
            int color = COLORS[index];
            String label = on ? "✔ " + NAMES[index] : NAMES[index];
            int textColor = on ? color : (0x90 << 24) | (color & 0x00FFFFFF);
            graphics.text(font, label, getX() + (getWidth() - font.width(label)) / 2, getY() + (getHeight() - 8) / 2, textColor, true);

            int stripAlpha = on ? 0xE0 : 0x40;
            graphics.fill(getX() + 1, getBottom() + 1, getRight() - 1, getBottom() + 3, (stripAlpha << 24) | (color & 0x00FFFFFF));
            if (!on && isHoveredOrFocused()) graphics.outline(getX(), getY(), getWidth(), getHeight(), 0xA0FFFFFF); // still clickable
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            output.add(NarratedElementType.TITLE, Component.literal(NAMES[index] + (on ? ", on" : ", off")));
        }
    }

    /**
     * Makes the four switches.
     * @param grades   which are on to start with
     * @param onChange gets the new choice whenever one is clicked
     */
    public static List<Toggle> create(ModConfig.AutoGrades grades, Consumer<ModConfig.AutoGrades> onChange) {
        Toggle[] toggles = new Toggle[4];
        Consumer<Toggle> changed = _ -> onChange.accept(new ModConfig.AutoGrades(
                toggles[0].isOn(), toggles[1].isOn(), toggles[2].isOn(), toggles[3].isOn()));
        boolean[] on = {grades.again(), grades.hard(), grades.good(), grades.easy()};
        for (int i = 0; i < 4; i++) toggles[i] = new Toggle(i, on[i], changed);
        return List.of(toggles);
    }

    /** Shows a choice on the switches without reporting it as a change. */
    public static void show(List<Toggle> toggles, ModConfig.AutoGrades grades) {
        boolean[] on = {grades.again(), grades.hard(), grades.good(), grades.easy()};
        for (int i = 0; i < 4; i++) toggles.get(i).setOn(on[i]);
    }

    /** Lays the four switches out in a row of the given width. @return the row's height, strips included */
    public static int place(List<Toggle> toggles, int x, int y, int width) {
        int each = (width - GAP * 3) / 4;
        for (int i = 0; i < 4; i++) toggles.get(i).setRectangle(each, 20, x + i * (each + GAP), y);
        return 20 + 3;
    }
}
