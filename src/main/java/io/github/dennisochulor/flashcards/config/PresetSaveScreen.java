package io.github.dennisochulor.flashcards.config;

import io.github.dennisochulor.flashcards.progress.Progression;
import io.github.dennisochulor.flashcards.progress.ProgressionPresets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.CommonColors;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Saves a rewards & penalties setup as one of your presets, under a name. Also used for a pasted preset, in which case
 * it first lists everything the preset would run, so nothing unexpected gets in.
 */
class PresetSaveScreen extends Screen {
    private static final int GRAY = 0xFFAAAAAA;
    private static final int WIDTH = 340;

    private final Screen parent;
    private final Progression.Setup setup;
    private final boolean pasted;
    private final Consumer<Progression.Setup> onUseHere;

    private final StringWidget titleText;
    private final MultiLineTextWidget contents;
    private final EditBox nameBox = new EditBox(Minecraft.getInstance().font, WIDTH, 18, Component.literal("Preset name"));
    private final StringWidget status = new StringWidget(Component.empty(), Minecraft.getInstance().font);
    private final Button saveButton = Button.builder(Component.literal("Save to my presets"), _ -> save())
            .tooltip(Tooltip.create(Component.literal("Keep it in your presets, to use on any deck. A preset of yours with the same name is replaced.")))
            .size(130, 20).build();
    private final Button useButton;
    private final Button cancelButton = Button.builder(Component.literal("Cancel"), _ -> this.onClose())
            .tooltip(Tooltip.create(Component.literal("Go back without saving"))).size(70, 20).build();

    /**
     * @param pasted    whether the setup came from the clipboard (shows its contents, and offers "Use here")
     * @param onUseHere for a pasted setup: puts it into the setup being edited
     */
    PresetSaveScreen(Progression.Setup setup, boolean pasted, Consumer<Progression.Setup> onUseHere) {
        super(Component.literal(pasted ? "Pasted preset" : "Save as my preset"));
        this.parent = Objects.requireNonNull(Minecraft.getInstance().gui.screen());
        this.setup = setup;
        this.pasted = pasted;
        this.onUseHere = onUseHere;
        titleText = new StringWidget(Component.literal(pasted ? "Pasted preset: check what it runs" : "Save as my preset"), Minecraft.getInstance().font);
        contents = new MultiLineTextWidget(Component.literal(PresetPickerScreen.describe(setup)).withColor(pasted ? CommonColors.WHITE : GRAY),
                Minecraft.getInstance().font);
        contents.setMaxRows(pasted ? 30 : 12);

        nameBox.setMaxLength(40);
        nameBox.setValue(setup.presetName().isBlank() || ProgressionPresets.builtIn(setup.presetName()) != null
                ? (setup.presetName().isBlank() ? "" : setup.presetName() + " (mine)") : setup.presetName());
        nameBox.setHint(Component.literal("Name, e.g. Hardcore German").withColor(GRAY));
        nameBox.setResponder(_ -> validate());

        useButton = Button.builder(Component.literal("Use here"), _ -> {
                    onUseHere.accept(setup.named(nameBox.getValue().strip()));
                    Minecraft.getInstance().gui.setScreen(parent);
                })
                .tooltip(Tooltip.create(Component.literal("Put this preset into the setup you're editing (without saving it to your presets).")))
                .size(80, 20).build();
        validate();
    }

    private void validate() {
        String name = nameBox.getValue().strip();
        boolean builtInName = ProgressionPresets.builtIn(name) != null;
        boolean ok = !name.isEmpty() && !builtInName;
        saveButton.active = ok;
        status.setMessage(name.isEmpty() ? Component.literal("Give it a name").withColor(GRAY)
                : builtInName ? Component.literal("That's a built-in preset's name, pick another").withColor(CommonColors.SOFT_RED)
                : ProgressionPresets.userPreset(name) != null ? Component.literal("Replaces your preset with this name").withColor(CommonColors.SOFT_YELLOW)
                : Component.literal("New preset").withColor(CommonColors.GREEN));
        status.setX(width / 2 - status.getWidth() / 2);
    }

    private void save() {
        String name = nameBox.getValue().strip();
        if (name.isEmpty() || ProgressionPresets.builtIn(name) != null) return;
        ProgressionPresets.saveUserPreset(setup.named(name));
        Minecraft.getInstance().gui.setScreen(parent);
    }

    @Override
    protected void init() {
        int w = Math.min(WIDTH, width - 24);
        int x = width / 2 - w / 2;
        int y = 8;
        titleText.setPosition(width / 2 - titleText.getWidth() / 2, y);
        addRenderableWidget(titleText);
        y += 16;
        nameBox.setWidth(w);
        nameBox.setPosition(x, y);
        addRenderableWidget(nameBox);
        y += 21;
        status.setPosition(width / 2 - status.getWidth() / 2, y);
        addRenderableWidget(status);
        y += 14;
        contents.setMaxWidth(w);
        contents.setMaxRows(Math.max(3, (height - 30 - y) / 9));
        contents.setPosition(x, y);
        addRenderableWidget(contents);

        int buttonsY = height - 26;
        if (pasted) {
            saveButton.setPosition(width / 2 - 145, buttonsY);
            useButton.setPosition(width / 2 - 10, buttonsY);
            cancelButton.setPosition(width / 2 + 75, buttonsY);
            addRenderableWidget(useButton);
        }
        else {
            saveButton.setPosition(width / 2 - 104, buttonsY);
            cancelButton.setPosition(width / 2 + 34, buttonsY);
        }
        addRenderableWidget(saveButton);
        addRenderableWidget(cancelButton);
        setInitialFocus(nameBox);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(parent);
    }
}
