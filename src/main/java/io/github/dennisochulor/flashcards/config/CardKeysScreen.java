package io.github.dennisochulor.flashcards.config;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.dennisochulor.flashcards.FileManager;
import io.github.dennisochulor.flashcards.IntSlider;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.CommonColors;

import java.util.Locale;
import java.util.Objects;

/**
 * The keys used on an Anki card, and how long the keyboard is ignored when a card appears.
 * Click a key button, then press the new key (Esc cancels).
 */
class CardKeysScreen extends Screen {
    private static final int GRAY = 0xFFAAAAAA;
    private static final int WIDTH = 300;
    private static final int KEY_BUTTON_WIDTH = 90;
    private static final String[] ACTIONS = {
            "Show answer / pick the suggested grade", "1st button (Again)", "2nd button (Hard)",
            "3rd button (Good)", "4th button (Easy)", "Keep playing (while the AI grades)"
    };

    private final Screen parent;
    private final int[] keyCodes;
    private int listening = -1; // which action is waiting for a key press, or -1

    private final StringWidget titleText = new StringWidget(Component.literal("Card Keys"), Minecraft.getInstance().font);
    private final StringWidget[] labels = new StringWidget[ACTIONS.length];
    private final Button[] keyButtons = new Button[ACTIONS.length];
    private final IntSlider graceSlider; // tenths of a second
    private final MultiLineTextWidget note = new MultiLineTextWidget(Component.literal(
            "Enter always shows the answer / confirms, and the number pad's 1-4 always grade. "
            + "While you type an answer, only Enter checks it. The mouse always works.").withColor(GRAY), Minecraft.getInstance().font);
    private final Button resetButton = Button.builder(Component.literal("Reset"), _ -> reset())
            .tooltip(Tooltip.create(Component.literal("Space, 1, 2, 3, 4, Space and a 1 second pause"))).size(70, 20).build();
    private final Button doneButton = Button.builder(Component.literal("Done"), _ -> this.onClose()).size(100, 20).build();

    CardKeysScreen() {
        super(Component.literal("Card Keys"));
        parent = Objects.requireNonNull(Minecraft.getInstance().gui.screen());
        ModConfig.CardKeys keys = FileManager.getConfig().cardKeys();
        keyCodes = new int[] {keys.confirm(), keys.again(), keys.hard(), keys.good(), keys.easy(), keys.keepPlaying()};

        for (int i = 0; i < ACTIONS.length; i++) {
            int index = i;
            labels[i] = new StringWidget(Component.literal(ACTIONS[i]), Minecraft.getInstance().font);
            keyButtons[i] = Button.builder(Component.empty(), _ -> {
                listening = listening == index ? -1 : index;
                updateButtons();
            }).size(KEY_BUTTON_WIDTH, 20).build();
        }

        graceSlider = new IntSlider(WIDTH, 20, 0, ModConfig.CardKeys.MAX_GRACE_MILLIS / 100, keys.graceMillis() / 100,
                tenths -> Component.literal(tenths == 0 ? "Ignore keys when a card appears: off"
                        : "Ignore keys for " + String.format(Locale.ROOT, "%.1f", tenths / 10.0) + " s when a card appears"));
        graceSlider.setTooltip(Tooltip.create(Component.literal(
                "A card can pop up while you're running and jumping. For this long (and until you let go of the keys you were holding), "
                + "the keyboard is ignored on the card, so a stray Space can't flip or answer it.")));
        note.setCentered(true);
        updateButtons();
    }

    private void reset() {
        ModConfig.CardKeys d = ModConfig.CardKeys.DEFAULT;
        int[] defaults = {d.confirm(), d.again(), d.hard(), d.good(), d.easy(), d.keepPlaying()};
        System.arraycopy(defaults, 0, keyCodes, 0, keyCodes.length);
        graceSlider.setIntValueSilently(d.graceMillis() / 100);
        listening = -1;
        updateButtons();
    }

    private void updateButtons() {
        for (int i = 0; i < keyButtons.length; i++) {
            keyButtons[i].setMessage(i == listening
                    ? Component.literal("> press a key <").withColor(CommonColors.YELLOW)
                    : InputConstants.Type.KEYBOARD.getOrCreate(keyCodes[i]).getDisplayName());
        }
    }

    @Override
    protected void init() {
        doneButton.setTooltip(Tooltip.create(Component.literal("Save the keys and go back")));
        int w = Math.min(WIDTH, width - 24);
        int x = width / 2 - w / 2;
        int y = 8;
        titleText.setPosition(width / 2 - titleText.getWidth() / 2, y);
        addRenderableWidget(titleText);
        y += 16;

        for (int i = 0; i < ACTIONS.length; i++) {
            labels[i].setMaxWidth(w - KEY_BUTTON_WIDTH - 6);
            labels[i].setPosition(x, y + 6);
            keyButtons[i].setPosition(x + w - KEY_BUTTON_WIDTH, y);
            addRenderableWidget(labels[i]);
            addRenderableWidget(keyButtons[i]);
            y += 22;
        }
        y += 2;
        graceSlider.setRectangle(w, 20, x, y);
        addRenderableWidget(graceSlider);
        y += 24;

        int buttonsY = Math.max(height - 26, y + 22);
        note.setMaxWidth(w);
        note.setPosition(width / 2 - note.getWidth() / 2, y);
        if (y + note.getHeight() <= buttonsY - 2) addRenderableWidget(note);

        resetButton.setPosition(width / 2 - 87, buttonsY);
        doneButton.setPosition(width / 2 - 13, buttonsY);
        addRenderableWidget(resetButton);
        addRenderableWidget(doneButton);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (listening >= 0) {
            if (event.key() != InputConstants.KEY_ESCAPE) keyCodes[listening] = event.key(); // Esc cancels
            listening = -1;
            updateButtons();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return listening < 0;
    }

    @Override
    public void onClose() {
        FileManager.updateConfig(FileManager.getConfig().withCardKeys(new ModConfig.CardKeys(
                keyCodes[0], keyCodes[1], keyCodes[2], keyCodes[3], keyCodes[4], keyCodes[5], graceSlider.intValue() * 100)));
        Minecraft.getInstance().gui.setScreen(parent);
    }
}
