package io.github.dennisochulor.flashcards.config;

import io.github.dennisochulor.flashcards.FileManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.FrameLayout;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.CommonColors;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Optional rewards for right answers and penalties for wrong ones, as commands run on the player.
 * Presets fill in the command boxes; editing a box (or the mode) turns the preset into "Custom",
 * and what you typed under Custom is kept while you browse the other presets.
 */
class RewardsConfigScreen extends Screen {
    private static final int WIDTH = 350;
    private static final int GRAY = 0xFFAAAAAA;

    private final Screen parent;
    private final ModConfig config = FileManager.getConfig();

    private final StringWidget title = new StringWidget(Component.literal("Rewards & Penalties"), Minecraft.getInstance().font);
    private final MultiLineTextWidget description = new MultiLineTextWidget(Component.empty(), Minecraft.getInstance().font);
    private final StringWidget correctLabel = new StringWidget(
            Component.literal("Right answer").withColor(CommonColors.GREEN).append(Component.literal("  (in Anki mode: Hard, Good or Easy)").withColor(GRAY)),
            Minecraft.getInstance().font);
    private final StringWidget wrongLabel = new StringWidget(
            Component.literal("Wrong answer").withColor(CommonColors.SOFT_RED).append(Component.literal("  (in Anki mode: Again)").withColor(GRAY)),
            Minecraft.getInstance().font);
    private final MultiLineTextWidget note = new MultiLineTextWidget(Component.literal(
            "Singleplayer: works even with cheats off. Multiplayer: you need permission to run these commands.").withColor(GRAY),
            Minecraft.getInstance().font);

    private final MultiLineEditBox correctBox = MultiLineEditBox.builder()
            .setPlaceholder(Component.literal("One command per line, without the /").withColor(GRAY))
            .build(Minecraft.getInstance().font, WIDTH, 50, Component.literal("Commands for a right answer"));
    private final MultiLineEditBox wrongBox = MultiLineEditBox.builder()
            .setPlaceholder(Component.literal("One command per line, without the /").withColor(GRAY))
            .build(Minecraft.getInstance().font, WIDTH, 50, Component.literal("Commands for a wrong answer"));

    private final CycleButton<RewardPreset> presetButton;
    private final CycleButton<ModConfig.CommandSelectionStrategy> modeButton;
    private final Button doneButton = Button.builder(Component.literal("Done"), _ -> this.onClose()).size(100, 20).build();

    private RewardPreset preset;
    private boolean applying = false; // true while a preset fills the boxes, so that doesn't count as the player editing
    // what the player wrote under "Custom", kept while they look at other presets
    private String customCorrect, customWrong;
    private ModConfig.CommandSelectionStrategy customMode;

    protected RewardsConfigScreen() {
        super(Component.literal("Rewards & Penalties"));
        parent = Objects.requireNonNull(Minecraft.getInstance().gui.screen());

        RewardPreset saved = RewardPreset.byName(config.rewardPreset());
        preset = saved.matches(config.correctAnswerCommands(), config.wrongAnswerCommands(), config.commandSelectionStrategy())
                || (saved == RewardPreset.NONE && config.commandSelectionStrategy() == ModConfig.CommandSelectionStrategy.OFF)
                ? saved : RewardPreset.CUSTOM;
        customCorrect = String.join("\n", config.correctAnswerCommands());
        customWrong = String.join("\n", config.wrongAnswerCommands());
        customMode = config.commandSelectionStrategy();

        presetButton = new CycleButton.Builder<RewardPreset>(p -> Component.literal(p.name()), () -> preset)
                .withValues(RewardPreset.ALL)
                .withTooltip(p -> Tooltip.create(Component.literal(p.description())))
                .create(0, 0, 200, 20, Component.literal("Preset"), (_, value) -> selectPreset(value));
        modeButton = new CycleButton.Builder<ModConfig.CommandSelectionStrategy>(strategy -> Component.literal(strategy.friendlyName), config::commandSelectionStrategy)
                .withValues(List.of(ModConfig.CommandSelectionStrategy.values()))
                .withTooltip(strategy -> strategy.tooltip)
                .create(0, 0, WIDTH - 200 - 4, 20, Component.literal("Mode"), (_, _) -> edited());

        description.setMaxWidth(WIDTH).setCentered(true);
        note.setMaxWidth(WIDTH).setCentered(true);
        correctBox.setValue(customCorrect);
        wrongBox.setValue(customWrong);
        correctBox.setValueListener(_ -> edited());
        wrongBox.setValueListener(_ -> edited());
        updateDescription();
    }

    private void selectPreset(RewardPreset selected) {
        if (preset == RewardPreset.CUSTOM) { // leaving Custom: remember it
            customCorrect = correctBox.getValue();
            customWrong = wrongBox.getValue();
            customMode = modeButton.getValue();
        }
        preset = selected;
        applying = true;
        if (selected == RewardPreset.CUSTOM) {
            correctBox.setValue(customCorrect);
            wrongBox.setValue(customWrong);
            modeButton.setValue(customMode);
        }
        else {
            correctBox.setValue(String.join("\n", selected.correct()));
            wrongBox.setValue(String.join("\n", selected.wrong()));
            modeButton.setValue(selected.strategy());
        }
        applying = false;
        updateDescription();
        rebuildWidgets(); // the description may need more or fewer lines
    }

    /** The player changed a box or the mode: it's their own set now. */
    private void edited() {
        if (applying || preset == RewardPreset.CUSTOM) return;
        preset = RewardPreset.CUSTOM;
        presetButton.setValue(RewardPreset.CUSTOM);
        updateDescription();
    }

    private void updateDescription() {
        description.setMessage(Component.literal(preset == RewardPreset.CUSTOM
                ? "Your own commands. Pick a preset to start from, then change anything you like."
                : preset.description()).withColor(GRAY));
    }

    @Override
    public void init() {
        int boxHeight = Math.max(36, (int) (this.height * 0.17));
        correctBox.setHeight(boxHeight);
        correctBox.refreshScrollAmount(); // prevent weird scrollbar overflow
        wrongBox.setHeight(boxHeight);
        wrongBox.refreshScrollAmount();

        LinearLayout topRow = LinearLayout.horizontal().spacing(4);
        topRow.addChild(presetButton);
        topRow.addChild(modeButton);

        LinearLayout content = LinearLayout.vertical().spacing(4);
        content.defaultCellSetting().alignHorizontallyCenter();
        content.addChild(topRow);
        content.addChild(description, content.newCellSettings().paddingBottom(3));
        content.addChild(correctLabel);
        content.addChild(correctBox, content.newCellSettings().paddingBottom(3));
        content.addChild(wrongLabel);
        content.addChild(wrongBox, content.newCellSettings().paddingBottom(3));
        content.addChild(note);

        HeaderAndFooterLayout root = new HeaderAndFooterLayout(this, 25, 34);
        root.addToHeader(title);
        root.addToContents(content);
        root.addToFooter(doneButton);

        root.arrangeElements();
        FrameLayout.alignInRectangle(root, 0, 0, this.width, this.height, 0.5F, 0.1F);
        root.visitWidgets(this::addRenderableWidget);
    }

    @Override
    public void onClose() {
        List<String> correct = Arrays.asList(correctBox.getValue().split("\n"));
        List<String> wrong = Arrays.asList(wrongBox.getValue().split("\n"));
        FileManager.updateConfig(FileManager.getConfig().withCommands(correct, wrong, modeButton.getValue(),
                preset == RewardPreset.CUSTOM ? "" : preset.name()));
        Minecraft.getInstance().gui.setScreen(parent);
    }
}
