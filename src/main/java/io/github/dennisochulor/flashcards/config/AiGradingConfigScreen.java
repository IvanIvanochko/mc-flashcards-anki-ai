package io.github.dennisochulor.flashcards.config;

import io.github.dennisochulor.flashcards.FileManager;
import io.github.dennisochulor.flashcards.IntSlider;
import io.github.dennisochulor.flashcards.ScoreSlider;
import io.github.dennisochulor.flashcards.ai.AiGrader;
import io.github.dennisochulor.flashcards.ai.GradingPrompt;
import io.github.dennisochulor.flashcards.ai.Ollama;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.CommonColors;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

class AiGradingConfigScreen extends Screen {
    private static final int WIDGET_WIDTH = 240; // widest a column gets; init() sizes everything to the real column
    private static final int SLIDER_WIDTH = 66;
    private static final int GRAY = 0xFFAAAAAA;

    private final Screen parent;
    private final ModConfig.AiGrading ai = FileManager.getConfig().aiGrading();

    private final StringWidget titleText = new StringWidget(Component.literal("AI Grading"), Minecraft.getInstance().font);

    private final CycleButton<Boolean> enabledButton = CycleButton.onOffBuilder(ai.enabled())
            .withTooltip(_ -> Tooltip.create(Component.literal(
                    "ON: type your answer and a local AI (Ollama) scores it by meaning from 0 to 1.\n" +
                    "The score decides which of Anki's buttons you can press.\n" +
                    "Works with Anki cards only (Anki Sync...).")))
            .create(0, 0, WIDGET_WIDTH, 20, Component.literal("AI grading"));

    private final EditBox urlBox = new EditBox(Minecraft.getInstance().font, WIDGET_WIDTH - 75, 20, Component.literal("Ollama address"));
    private final Button connectButton = Button.builder(Component.literal("Connect"), _ -> loadModels())
            .tooltip(Tooltip.create(Component.literal("Load your installed models from Ollama")))
            .size(70, 20).build();
    private final MultiLineTextWidget statusText = new MultiLineTextWidget(Component.empty(), Minecraft.getInstance().font);
    private final StringWidget modelLabel = new StringWidget(Component.empty(), Minecraft.getInstance().font);
    private final DeckListWidget modelList;

    private final StringWidget cutoffLabel = new StringWidget(
            Component.literal("Score needed (Again: always)").withColor(GRAY), Minecraft.getInstance().font);
    // not final: each slider's listener refers to the others
    private ScoreSlider hardSlider;
    private ScoreSlider goodSlider;
    private ScoreSlider easySlider;
    private final Button resetButton = Button.builder(Component.literal("↺"), _ -> resetCutoffs())
            .tooltip(Tooltip.create(Component.literal("Reset to Hard 0.50, Good 0.70, Easy 0.90")))
            .size(20, 20).build();

    private final CycleButton<Boolean> adjustButton = CycleButton.onOffBuilder(ai.adjustScore())
            .withTooltip(_ -> Tooltip.create(Component.literal(
                    "ON: after the AI grades you, a slider lets you change the score if you disagree.\n" +
                    "OFF: the AI's score is final (you can still always press Again).")))
            .create(0, 0, WIDGET_WIDTH, 20, Component.literal("Adjusting score"));

    private final IntSlider quitAfterSlider = new IntSlider(WIDGET_WIDTH / 2 - 2, 20, 0, ModConfig.AiGrading.MAX_QUIT_AFTER_SECONDS,
            ai.quitAfterSeconds(), s -> Component.literal(s == 0 ? "Keep playing button: at once" : "Keep playing button after " + s + "s"));

    private final IntSlider minPlaySlider = new IntSlider(WIDGET_WIDTH / 2 - 2, 20, 0, ModConfig.AiGrading.MAX_MIN_PLAY_SECONDS,
            ai.minPlaySeconds(), s -> Component.literal(s == 0 ? "Play at least: 0s" : "Play at least " + s + "s"));

    private final CycleButton<Boolean> autoKeepPlayingButton = CycleButton.onOffBuilder(ai.autoKeepPlaying())
            .withTooltip(_ -> Tooltip.create(Component.literal(
                    "ON: as soon as you check an answer, you're back in the game while the AI thinks.\n" +
                    "OFF: you wait on the card, and \"Keep playing\" appears if it takes too long.")))
            .create(0, 0, WIDGET_WIDTH / 2 - 2, 20, Component.literal("Play while thinking"));

    private final Button indicatorButton = Button.builder(Component.literal("HUD layout..."), _ -> Minecraft.getInstance().gui.setScreen(new HudLayoutScreen()))
            .tooltip(Tooltip.create(Component.literal("Move and resize what shows while you keep playing: the \"AI is thinking\" animation, and the AI's result (grade, countdown, explanation).")))
            .size(WIDGET_WIDTH / 2 - 2, 20).build();

    private final Button promptButton = Button.builder(Component.empty(), _ -> Minecraft.getInstance().gui.setScreen(
                    new PromptEditorScreen("AI prompt", FileManager.getConfig().aiGrading().prompt(), GradingPrompt.DEFAULT_TEMPLATE, "built-in",
                            text -> FileManager.updateConfig(FileManager.getConfig().withAiGrading(FileManager.getConfig().aiGrading().withPrompt(text))))))
            .tooltip(Tooltip.create(Component.literal(
                    "Read or rewrite the whole set of instructions the AI grades by (there's a reset button).\n"
                    + "To tune grading for one deck you usually don't need this: give the deck a few notes in main config → Deck Presets...")))
            .build();

    private final Button autoGradingButton = Button.builder(Component.empty(), _ -> Minecraft.getInstance().gui.setScreen(new AutoGradingScreen()))
            .tooltip(Tooltip.create(Component.literal(
                    "When the AI finishes while you play, its grade shows on the HUD with a countdown, then is applied on its own "
                    + "unless you open the card. Choose which grades, how long, and how much text.")))
            .build();

    private final Button performanceButton = Button.builder(Component.literal("Memory & performance..."), _ -> {
                saveModelChoice(); // so that screen measures the model picked here
                modelMayHaveChanged = true; // ...and it can switch to a model that fits
                Minecraft.getInstance().gui.setScreen(new AiPerformanceScreen());
            })
            .tooltip(Tooltip.create(Component.literal(
                    "How much RAM the AI may use, how long the model stays loaded, and a live picture of where your memory is going.")))
            .build();

    private final MultiLineTextWidget helpText = new MultiLineTextWidget(Component.literal(
            "Needs the Ollama app running (ollama.com) with a model downloaded, e.g. \"ollama pull qwen2.5:7b\".")
            .withColor(GRAY), Minecraft.getInstance().font);

    private final Button doneButton = Button.builder(Component.literal("Done"), _ -> this.onClose()).size(100, 20).build();

    private String selectedModel = ai.model();
    private final Map<String, Long> modelSizes = new HashMap<>();
    private List<String> modelNames = List.of();
    private boolean modelMayHaveChanged = false;
    private boolean initialized = false;
    private int leftColumnX = 0;
    private int columnWidth = WIDGET_WIDTH;

    protected AiGradingConfigScreen() {
        super(Component.literal("AI Grading Config"));
        parent = Objects.requireNonNull(Minecraft.getInstance().gui.screen());

        urlBox.setMaxLength(200);
        urlBox.setValue(ai.ollamaUrl());
        urlBox.setHint(Component.literal(ModConfig.DEFAULT_OLLAMA_URL));
        urlBox.setTooltip(Tooltip.create(Component.literal("Ollama address. The default is " + ModConfig.DEFAULT_OLLAMA_URL)));
        statusText.setCentered(true);
        helpText.setCentered(true);

        modelList = new DeckListWidget(WIDGET_WIDTH, 100, 0, model -> {
            selectedModel = model;
            updateModelLabel();
        });
        // each model's rough RAM need, green if it fits the AI memory budget (Memory & performance...)
        modelList.setDetail(model -> {
            Long bytes = modelSizes.get(model);
            return bytes == null || bytes <= 0 ? "" : "~" + SystemMemory.formatGb(SystemMemory.estimateModelBytes(bytes));
        }, model -> {
            Long bytes = modelSizes.get(model);
            double budgetGb = FileManager.getConfig().aiGrading().performance().memoryBudgetGb();
            return bytes != null && SystemMemory.estimateModelBytes(bytes) <= budgetGb * SystemMemory.GB ? CommonColors.GREEN : CommonColors.SOFT_YELLOW;
        });

        hardSlider = new ScoreSlider(SLIDER_WIDTH, 20, "Hard ", ai.hardFrom(), v -> {
            if (goodSlider.score() < v) goodSlider.setScoreSilently(v);
            if (easySlider.score() < v) easySlider.setScoreSilently(v);
        });
        goodSlider = new ScoreSlider(SLIDER_WIDTH, 20, "Good ", ai.goodFrom(), v -> {
            if (hardSlider.score() > v) hardSlider.setScoreSilently(v);
            if (easySlider.score() < v) easySlider.setScoreSilently(v);
        });
        easySlider = new ScoreSlider(SLIDER_WIDTH, 20, "Easy ", ai.easyFrom(), v -> {
            if (goodSlider.score() > v) goodSlider.setScoreSilently(v);
            if (hardSlider.score() > v) hardSlider.setScoreSilently(v);
        });
        hardSlider.setTooltip(Tooltip.create(Component.literal("Lowest score that unlocks Hard")));
        goodSlider.setTooltip(Tooltip.create(Component.literal("Lowest score that unlocks Good")));
        easySlider.setTooltip(Tooltip.create(Component.literal("Lowest score that unlocks Easy")));

        quitAfterSlider.setTooltip(Tooltip.create(Component.literal(
                "If the AI thinks longer than this, a \"Keep playing\" button (or Space) lets you leave the card and play;\n" +
                "the card comes back when it's graded.")));
        minPlaySlider.setTooltip(Tooltip.create(Component.literal(
                "After \"Keep playing\", you get at least this long in the game before the card comes back.")));

        updateModelLabel();
        loadModels();
    }

    private String url() {
        String url = urlBox.getValue().trim();
        return url.isEmpty() ? ModConfig.DEFAULT_OLLAMA_URL : url;
    }

    private void resetCutoffs() {
        hardSlider.setScoreSilently(ModConfig.AiGrading.DEFAULT_HARD_FROM);
        goodSlider.setScoreSilently(ModConfig.AiGrading.DEFAULT_GOOD_FROM);
        easySlider.setScoreSilently(ModConfig.AiGrading.DEFAULT_EASY_FROM);
    }

    private void updateModelLabel() {
        modelLabel.setMessage(Component.literal("Model: ").withColor(GRAY)
                .append(selectedModel.isBlank() ? Component.literal("(none, pick one below)").withColor(CommonColors.SOFT_YELLOW)
                        : Component.literal(selectedModel).withColor(CommonColors.WHITE)));
        modelLabel.setX(leftColumnX + (columnWidth - modelLabel.getWidth()) / 2); // re-center (no rebuild: this runs while the list handles a click)
    }

    private void loadModels() {
        connectButton.active = false;
        setStatus(Component.literal("Connecting to Ollama...").withColor(GRAY));

        AiGrader.loadModels(url(), infos -> {
            connectButton.active = true;
            modelSizes.clear();
            List<String> models = new ArrayList<>();
            for (Ollama.ModelInfo info : infos) {
                models.add(info.name());
                modelSizes.put(info.name(), info.fileBytes());
            }
            if (selectedModel.isBlank() && models.size() == 1) {
                selectedModel = models.getFirst();
                updateModelLabel();
            }
            modelNames = models;
            modelList.setDecks(models, selectedModel);
            if (models.isEmpty()) {
                setStatus(Component.literal("Connected, but no models are installed. Run \"ollama pull qwen2.5:7b\".").withColor(CommonColors.SOFT_YELLOW));
            }
            else if (!selectedModel.isBlank() && !models.contains(selectedModel)) {
                setStatus(Component.literal("Connected. Model \"" + selectedModel + "\" isn't installed, pick another.").withColor(CommonColors.SOFT_YELLOW));
            }
            else {
                setStatus(Component.literal("Connected to Ollama (" + models.size() + (models.size() == 1 ? " model)" : " models)")).withColor(CommonColors.GREEN));
            }
        }, error -> {
            connectButton.active = true;
            setStatus(Component.literal("Not connected: " + error).withColor(CommonColors.SOFT_RED));
        });
    }

    private void setStatus(Component status) {
        statusText.setMessage(status);
        if (initialized) rebuildWidgets(); // the status arrives asynchronously and may change height
    }

    /**
     * Two columns, so everything fits on small (high GUI scale) screens:
     * left = connection and model list, right = grading and "keep playing" settings.
     */
    @Override
    protected void init() {
        doneButton.setTooltip(Tooltip.create(Component.literal("Save these settings and go back")));
        initialized = true;
        if (modelMayHaveChanged) { // back from Memory & performance
            modelMayHaveChanged = false;
            selectedModel = FileManager.getConfig().aiGrading().model();
            modelList.setDecks(modelNames, selectedModel);
            updateModelLabel();
        }
        int gap = 10;
        columnWidth = Math.max(150, Math.min(240, (width - 20 - gap) / 2));
        leftColumnX = width / 2 - gap / 2 - columnWidth;
        int rightX = width / 2 + gap / 2;
        int top = 26;

        titleText.setPosition(width / 2 - titleText.getWidth() / 2, 8);

        // bottom: help text and Done
        int doneY = height - 26;
        doneButton.setPosition(width / 2 - doneButton.getWidth() / 2, doneY);
        helpText.setMaxWidth(Math.min(width - 20, columnWidth * 2 + gap));
        int helpY = doneY - 5 - helpText.getHeight();
        helpText.setPosition(width / 2 - helpText.getWidth() / 2, helpY);
        int contentBottom = helpY - 6;

        // left column: connection, then the model list takes whatever height is left
        int y = top;
        enabledButton.setRectangle(columnWidth, 20, leftColumnX, y);
        y += 24;
        urlBox.setWidth(columnWidth - 60);
        urlBox.setPosition(leftColumnX, y);
        connectButton.setRectangle(56, 20, leftColumnX + columnWidth - 56, y);
        y += 24;
        statusText.setMaxWidth(columnWidth);
        statusText.setPosition(leftColumnX + (columnWidth - statusText.getWidth()) / 2, y);
        y += statusText.getHeight() + 5;
        modelLabel.setMaxWidth(columnWidth);
        modelLabel.setPosition(leftColumnX + (columnWidth - modelLabel.getWidth()) / 2, y);
        y += 12;
        modelList.updateSizeAndPosition(columnWidth, Math.max(3 * 14 + 4, contentBottom - y), leftColumnX, y);

        // right column: settings
        y = top;
        cutoffLabel.setMaxWidth(columnWidth);
        cutoffLabel.setPosition(rightX + (columnWidth - cutoffLabel.getWidth()) / 2, y + 1);
        y += 12;
        int sliderWidth = (columnWidth - 20 - 3 * 4) / 3;
        hardSlider.setRectangle(sliderWidth, 20, rightX, y);
        goodSlider.setRectangle(sliderWidth, 20, rightX + sliderWidth + 4, y);
        easySlider.setRectangle(sliderWidth, 20, rightX + 2 * (sliderWidth + 4), y);
        resetButton.setPosition(rightX + columnWidth - 20, y);
        y += 24;
        adjustButton.setRectangle(columnWidth, 20, rightX, y);
        y += 24;
        quitAfterSlider.setRectangle(columnWidth, 20, rightX, y);
        y += 24;
        minPlaySlider.setRectangle(columnWidth, 20, rightX, y);
        y += 24;
        autoKeepPlayingButton.setRectangle(columnWidth, 20, rightX, y);
        y += 24;
        int halfColumn = (columnWidth - 4) / 2;
        indicatorButton.setRectangle(halfColumn, 20, rightX, y);
        autoGradingButton.setMessage(Component.literal("Auto-grade: " + (FileManager.getConfig().aiGrading().autoGrading().enabled() ? "ON" : "OFF") + "..."));
        autoGradingButton.setRectangle(columnWidth - halfColumn - 4, 20, rightX + halfColumn + 4, y);
        y += 24;
        promptButton.setMessage(Component.literal("AI prompt: " + (FileManager.getConfig().aiGrading().prompt().isBlank() ? "Default" : "Own") + "..."));
        promptButton.setRectangle(columnWidth, 20, rightX, y);
        y += 24;
        performanceButton.setRectangle(columnWidth, 20, rightX, y);
        boolean roomForHelp = y + 20 + 2 <= helpY; // on very small windows the settings matter more than the help line

        addRenderableWidget(titleText);
        addRenderableWidget(enabledButton);
        addRenderableWidget(urlBox);
        addRenderableWidget(connectButton);
        addRenderableWidget(statusText);
        addRenderableWidget(modelLabel);
        addRenderableWidget(modelList);
        addRenderableWidget(cutoffLabel);
        addRenderableWidget(hardSlider);
        addRenderableWidget(goodSlider);
        addRenderableWidget(easySlider);
        addRenderableWidget(resetButton);
        addRenderableWidget(adjustButton);
        addRenderableWidget(quitAfterSlider);
        addRenderableWidget(minPlaySlider);
        addRenderableWidget(autoKeepPlayingButton);
        addRenderableWidget(indicatorButton);
        addRenderableWidget(promptButton);
        addRenderableWidget(performanceButton);
        addRenderableWidget(autoGradingButton);
        if (roomForHelp) addRenderableWidget(helpText);
        addRenderableWidget(doneButton);
    }

    /** Saves just the picked model right away, for screens opened from here that look at it. */
    private void saveModelChoice() {
        ModConfig config = FileManager.getConfig();
        if (!config.aiGrading().model().equals(selectedModel)) {
            FileManager.updateConfig(config.withAiGrading(config.aiGrading().withModel(selectedModel)));
        }
    }

    @Override
    public void onClose() {
        ModConfig.AiGrading newAi = new ModConfig.AiGrading(enabledButton.getValue(), url(), selectedModel,
                hardSlider.score(), goodSlider.score(), easySlider.score(), adjustButton.getValue(),
                quitAfterSlider.intValue(), minPlaySlider.intValue(), autoKeepPlayingButton.getValue(),
                FileManager.getConfig().aiGrading().prompt(),        // saved by its own editor
                FileManager.getConfig().aiGrading().performance(),   // saved by its own screen
                FileManager.getConfig().aiGrading().autoGrading());  // saved by its own screen
        FileManager.updateConfig(FileManager.getConfig().withAiGrading(newAi)); // written to config/flashcards, so it survives restarts
        Minecraft.getInstance().gui.setScreen(parent);
    }
}
