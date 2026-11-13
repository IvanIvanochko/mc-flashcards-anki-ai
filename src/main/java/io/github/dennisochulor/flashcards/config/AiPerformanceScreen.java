package io.github.dennisochulor.flashcards.config;

import io.github.dennisochulor.flashcards.FileManager;
import io.github.dennisochulor.flashcards.IntSlider;
import io.github.dennisochulor.flashcards.ai.AiGrader;
import io.github.dennisochulor.flashcards.ai.Ollama;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.CommonColors;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * How much of the computer the grading AI may use, with a live picture of where the memory is going:
 * Minecraft, the AI model that's loaded right now, the AI budget, and everything else.
 * <p>
 * Profiles set the sliders; moving a slider makes the profile "Custom". The picture refreshes every two seconds.
 */
class AiPerformanceScreen extends Screen {
    private static final int MAX_WIDTH = 300;
    private static final int REFRESH_TICKS = 40;
    private static final int GRAY = 0xFFAAAAAA;
    private static final int DIM = 0xFF777C88;
    private static final int MC_GREEN = 0xFF5FD07A;
    private static final int MC_RESERVED = 0x705FD07A;
    private static final int AI_PURPLE = 0xFFC08CFF;
    private static final int AI_BUDGET_FILL = 0x30C08CFF;
    private static final int OVER_RED = 0xFFFF7070;
    private static final int TRACK = 0x38FFFFFF;
    private static final int PANEL_HEIGHT = 72;

    private final Screen parent;
    private ModConfig.PerformanceProfile profile;
    private List<Ollama.ModelInfo> models = List.of();
    private List<Ollama.LoadedModel> loaded = List.of();
    private boolean ollamaReachable = true;
    private Ollama.@Nullable ModelInfo betterFit = null;
    private int ticks = 0;
    private boolean needsRebuild = false;
    private String layoutKey = "";
    private int panelX, panelY, panelWidth;

    private final StringWidget titleText = new StringWidget(Component.literal("AI Memory & Performance"), Minecraft.getInstance().font);
    private final MultiLineTextWidget description = new MultiLineTextWidget(Component.empty(), Minecraft.getInstance().font);
    private final MultiLineTextWidget advice = new MultiLineTextWidget(Component.empty(), Minecraft.getInstance().font);
    private final CycleButton<ModConfig.PerformanceProfile> profileButton;
    private final IntSlider budgetSlider;   // in half gigabytes
    private final IntSlider keepSlider;     // minutes
    private final IntSlider threadsSlider;  // 0 = auto
    private final Button fitButton = Button.builder(Component.empty(), _ -> switchToBetterFit()).build();
    private final Button doneButton = Button.builder(Component.literal("Done"), _ -> this.onClose()).size(100, 20).build();

    AiPerformanceScreen() {
        super(Component.literal("AI Memory & Performance"));
        parent = Objects.requireNonNull(Minecraft.getInstance().gui.screen());
        ModConfig.AiPerformance performance = FileManager.getConfig().aiGrading().performance();
        profile = performance.profile();

        profileButton = new CycleButton.Builder<ModConfig.PerformanceProfile>(p -> Component.literal(p.friendlyName), () -> profile)
                .withValues(ModConfig.PerformanceProfile.values())
                .withTooltip(p -> Tooltip.create(Component.literal(p.description)))
                .create(0, 0, MAX_WIDTH, 20, Component.literal("Profile"), (_, value) -> applyProfile(value));

        budgetSlider = new IntSlider(MAX_WIDTH, 20, (int) (ModConfig.AiPerformance.MIN_BUDGET_GB * 2), (int) Math.round(SystemMemory.maxBudgetGb() * 2),
                (int) Math.round(performance.memoryBudgetGb() * 2), AiPerformanceScreen::budgetLabel, _ -> sliderMoved());
        budgetSlider.setTooltip(Tooltip.create(Component.literal(
                "How much RAM the AI model may take. Ollama can't be given a hard limit, so this is the yardstick: "
                + "models are marked as fitting or not, and you're warned (with a suggestion) when the chosen one is too big.")));

        keepSlider = new IntSlider(MAX_WIDTH / 2 - 2, 20, 0, ModConfig.AiPerformance.MAX_KEEP_ALIVE_MINUTES, performance.keepAliveMinutes(),
                minutes -> Component.literal(minutes == 0 ? "Unload right after grading" : "Keep loaded: " + minutes + " min"), _ -> sliderMoved());
        keepSlider.setTooltip(Tooltip.create(Component.literal(
                "How long the model stays in memory after it grades a card.\n"
                + "Short: its memory is free while you play, but it's loaded again (a few seconds) while you type the next answer.\n"
                + "Long: always ready, but the memory stays taken.")));

        threadsSlider = new IntSlider(MAX_WIDTH / 2 - 2, 20, 0, SystemMemory.cpuThreads(), performance.threads(),
                threads -> Component.literal(threads == 0 ? "CPU threads: Auto" : "CPU threads: " + threads + " of " + SystemMemory.cpuThreads()), _ -> sliderMoved());
        threadsSlider.setTooltip(Tooltip.create(Component.literal(
                "Only matters when the model runs on the CPU (no or small graphics card). Fewer threads leave more of the processor for the game.\n"
                + "On Apple Silicon and most graphics cards the model runs on the GPU, and this changes little.")));

        description.setCentered(true);
        advice.setCentered(true);
        updateTexts();

        String url = FileManager.getConfig().aiGrading().ollamaUrl();
        AiGrader.loadModels(url, list -> {
            models = list;
            updateTexts();
        }, _ -> {});
        refreshMemory();
    }

    private static Component budgetLabel(int halfGigabytes) {
        double gb = halfGigabytes / 2.0;
        String text = "AI memory budget: " + String.format(Locale.ROOT, "%.1f", gb) + " GB";
        if (SystemMemory.totalBytes() > 0) {
            text += String.format(Locale.ROOT, "  (%d%% of %.0f GB)", Math.round(gb / SystemMemory.totalGb() * 100), SystemMemory.totalGb());
        }
        return Component.literal(text);
    }

    private double budgetGb() {
        return budgetSlider.intValue() / 2.0;
    }

    private long budgetBytes() {
        return (long) (budgetGb() * SystemMemory.GB);
    }

    private void applyProfile(ModConfig.PerformanceProfile chosen) {
        profile = chosen;
        if (chosen != ModConfig.PerformanceProfile.CUSTOM) {
            ModConfig.AiPerformance preset = ModConfig.AiPerformance.forProfile(chosen);
            budgetSlider.setIntValueSilently((int) Math.round(preset.memoryBudgetGb() * 2));
            keepSlider.setIntValueSilently(preset.keepAliveMinutes());
            threadsSlider.setIntValueSilently(preset.threads());
        }
        updateTexts();
    }

    private void sliderMoved() {
        if (profile != ModConfig.PerformanceProfile.CUSTOM) {
            profile = ModConfig.PerformanceProfile.CUSTOM;
            profileButton.setValue(profile);
        }
        updateTexts();
    }

    private void refreshMemory() {
        AiGrader.loadMemory(FileManager.getConfig().aiGrading().ollamaUrl(), list -> {
            loaded = list;
            ollamaReachable = true;
        }, _ -> {
            loaded = List.of();
            ollamaReachable = false;
        });
    }

    private Ollama.@Nullable ModelInfo info(String name) {
        for (Ollama.ModelInfo info : models) if (info.name().equals(name)) return info;
        return null;
    }

    /** What the chosen model needs: Ollama's real number if it's loaded, otherwise an estimate from its file size. */
    private long neededBytes(String model) {
        for (Ollama.LoadedModel l : loaded) if (l.name().equals(model)) return l.bytes();
        Ollama.ModelInfo info = info(model);
        return info == null ? -1 : SystemMemory.estimateModelBytes(info.fileBytes());
    }

    /** Rewrites the profile description and the advice, and decides whether to offer a better-fitting model. */
    private void updateTexts() {
        description.setMessage(Component.literal(profile.description).withColor(GRAY));

        String model = FileManager.getConfig().aiGrading().model();
        betterFit = null;
        MutableComponent text;
        long needed = model.isBlank() ? -1 : neededBytes(model);
        if (model.isBlank()) {
            text = Component.literal("No model picked yet: choose one in AI Grading.").withColor(CommonColors.SOFT_YELLOW);
        }
        else if (needed < 0) {
            text = Component.literal(ollamaReachable ? model + ": size unknown (is it installed?)" : "Can't reach Ollama, so only Minecraft's memory is shown.")
                    .withColor(CommonColors.SOFT_YELLOW);
        }
        else if (needed <= budgetBytes()) {
            text = Component.literal(model + " needs about " + SystemMemory.formatGb(needed) + ": fits your budget.").withColor(MC_GREEN);
            if (keepSlider.intValue() <= 1) {
                text.append(Component.literal(" It's unloaded soon after each card, so that memory is free while you play.").withColor(GRAY));
            }
        }
        else {
            text = Component.literal(model + " needs about " + SystemMemory.formatGb(needed) + ", more than your "
                    + String.format(Locale.ROOT, "%.1f", budgetGb()) + " GB budget. The game may stutter while it's loaded.").withColor(CommonColors.SOFT_YELLOW);
            for (Ollama.ModelInfo candidate : models) { // the biggest installed model that does fit
                long size = SystemMemory.estimateModelBytes(candidate.fileBytes());
                if (candidate.fileBytes() > 0 && size <= budgetBytes() && !candidate.name().equals(model)
                        && (betterFit == null || candidate.fileBytes() > betterFit.fileBytes())) {
                    betterFit = candidate;
                }
            }
            if (betterFit == null && !models.isEmpty()) {
                text.append(Component.literal(" None of your installed models fits: raise the budget, or download a smaller one (e.g. ollama pull qwen2.5:3b).").withColor(GRAY));
            }
        }
        advice.setMessage(text);
        Ollama.ModelInfo fit = betterFit;
        if (fit != null) {
            fitButton.setMessage(Component.literal("Switch to " + fit.name() + "  (~" + SystemMemory.formatGb(SystemMemory.estimateModelBytes(fit.fileBytes())) + ")"));
            fitButton.setTooltip(Tooltip.create(Component.literal("The largest of your installed models that fits the budget. Changes the main AI model.")));
        }
        // only lay the screen out again when something changes size or appears/disappears
        String layout = description.getMessage().getString().length() / 40 + ":" + text.getString().length() / 40 + ":" + (fit != null);
        if (!layout.equals(layoutKey)) {
            layoutKey = layout;
            needsRebuild = true;
        }
    }

    private void switchToBetterFit() {
        Ollama.ModelInfo fit = betterFit;
        if (fit == null) return;
        ModConfig config = FileManager.getConfig();
        FileManager.updateConfig(config.withAiGrading(config.aiGrading().withModel(fit.name())));
        updateTexts();
    }

    @Override
    public void tick() {
        super.tick();
        if (++ticks % REFRESH_TICKS == 0) refreshMemory();
        if (needsRebuild) {
            needsRebuild = false;
            // keep hold of the slider being dragged: rebuilding would otherwise let go of it mid-drag
            GuiEventListener focused = getFocused();
            boolean dragging = isDragging();
            rebuildWidgets();
            if (focused != null && children().contains(focused)) {
                setFocused(focused);
                setDragging(dragging);
            }
        }
    }

    @Override
    protected void init() {
        doneButton.setTooltip(Tooltip.create(Component.literal("Save these settings and go back")));
        int w = Math.min(MAX_WIDTH, width - 24);
        int x = width / 2 - w / 2;
        int half = (w - 4) / 2;
        int y = 8;

        titleText.setPosition(width / 2 - titleText.getWidth() / 2, y);
        y += 15;
        profileButton.setRectangle(w, 20, x, y);
        y += 23;
        description.setMaxWidth(w);
        description.setPosition(width / 2 - description.getWidth() / 2, y);
        y += description.getHeight() + 4;
        budgetSlider.setRectangle(w, 20, x, y);
        y += 23;
        keepSlider.setRectangle(half, 20, x, y);
        threadsSlider.setRectangle(half, 20, x + half + 4, y);
        y += 26;

        panelX = x;
        panelY = y;
        panelWidth = w;
        y += PANEL_HEIGHT + 4;

        advice.setMaxWidth(w);
        advice.setPosition(width / 2 - advice.getWidth() / 2, y);
        y += advice.getHeight() + 4;

        int doneY = Math.max(height - 26, y + (betterFit != null ? 24 : 0));
        doneButton.setPosition(width / 2 - doneButton.getWidth() / 2, doneY);

        addRenderableWidget(titleText);
        addRenderableWidget(profileButton);
        addRenderableWidget(description);
        addRenderableWidget(budgetSlider);
        addRenderableWidget(keepSlider);
        addRenderableWidget(threadsSlider);
        addRenderableWidget(advice);
        if (betterFit != null) {
            fitButton.setRectangle(w, 20, x, y);
            addRenderableWidget(fitButton);
        }
        addRenderableWidget(doneButton);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        drawMemory(graphics, Minecraft.getInstance().font, panelX, panelY, panelWidth);
    }

    /**
     * One bar for the whole computer: Minecraft (in use, then reserved), the AI budget right after it with the loaded
     * model inside (red where it spills over the budget), and the rest. A legend with the numbers sits underneath.
     */
    private void drawMemory(GuiGraphicsExtractor graphics, Font font, int x, int y, int w) {
        long mcUsed = SystemMemory.minecraftUsedBytes();
        long mcMax = Math.max(mcUsed, SystemMemory.minecraftMaxBytes());
        long budget = budgetBytes();
        Ollama.LoadedModel model = loaded.isEmpty() ? null : loaded.getFirst();
        long ai = 0;
        for (Ollama.LoadedModel l : loaded) ai += l.bytes();
        long total = SystemMemory.totalBytes();
        boolean knowTotal = total > 0;
        long scale = knowTotal ? total : mcMax + Math.max(budget, ai) + SystemMemory.GB;

        graphics.text(font, "MEMORY", x, y, GRAY, false);
        String totalText = knowTotal ? SystemMemory.formatGb(total) + " in this computer" : "total unknown";
        graphics.text(font, totalText, x + w - font.width(totalText), y, DIM, false);

        int barY = y + 12;
        int barHeight = 10;
        graphics.fill(x, barY, x + w, barY + barHeight, TRACK);
        int usedEnd = x + px(mcUsed, scale, w);
        int mcEnd = x + px(mcMax, scale, w);
        graphics.fill(x, barY, usedEnd, barY + barHeight, MC_GREEN);
        graphics.fill(usedEnd, barY, mcEnd, barY + barHeight, MC_RESERVED);

        int budgetEnd = Math.min(x + w, mcEnd + px(budget, scale, w));
        graphics.fill(mcEnd + 1, barY, budgetEnd, barY + barHeight, AI_BUDGET_FILL);
        graphics.outline(mcEnd + 1, barY, Math.max(2, budgetEnd - mcEnd - 1), barHeight, AI_PURPLE);
        if (ai > 0) {
            int aiEnd = Math.min(x + w, mcEnd + 1 + px(ai, scale, w));
            graphics.fill(mcEnd + 1, barY + 2, Math.min(aiEnd, budgetEnd), barY + barHeight - 2, AI_PURPLE);
            if (aiEnd > budgetEnd) graphics.fill(budgetEnd, barY + 2, aiEnd, barY + barHeight - 2, OVER_RED); // over budget
        }

        int rowY = barY + barHeight + 6;
        legend(graphics, font, x, rowY, MC_GREEN, "Minecraft",
                SystemMemory.formatGb(mcUsed) + " in use · may grow to " + SystemMemory.formatGb(mcMax));
        rowY += 11;
        String aiText;
        if (model != null) {
            aiText = model.name() + " · " + SystemMemory.formatGb(ai);
            if (model.bytes() > 0 && model.gpuBytes() < model.bytes() * 0.95) aiText += " · " + Math.round(model.gpuBytes() * 100.0 / model.bytes()) + "% on GPU";
            long left = model.expiresMillis() - System.currentTimeMillis();
            if (model.expiresMillis() > 0) aiText += left > 24 * 3600_000L ? " · stays loaded" : left > 0 ? " · unloads in " + minutesSeconds(left) : " · unloading";
        }
        else aiText = ollamaReachable ? "nothing loaded right now" : "can't reach Ollama";
        legend(graphics, font, x, rowY, ai > budget ? OVER_RED : AI_PURPLE, "AI model", aiText);
        rowY += 11;
        graphics.outline(x, rowY + 1, 6, 6, AI_PURPLE);
        graphics.text(font, "AI budget", x + 10, rowY, GRAY, false);
        String budgetText = SystemMemory.formatGb(budget);
        graphics.text(font, budgetText, x + w - font.width(budgetText), rowY, CommonColors.WHITE, false);
        rowY += 11;
        if (knowTotal) {
            long rest = Math.max(0, total - mcMax - Math.max(ai, 0));
            legend(graphics, font, x, rowY, 0xFF8A8F99, "Everything else", "about " + SystemMemory.formatGb(rest) + " (system, other apps)");
        }
    }

    private static int px(long bytes, long scale, int width) {
        return (int) Math.round(Math.min(1.0, bytes / (double) scale) * width);
    }

    private static void legend(GuiGraphicsExtractor graphics, Font font, int x, int y, int color, String name, String value) {
        graphics.fill(x, y + 1, x + 6, y + 7, color);
        graphics.text(font, name, x + 10, y, GRAY, false);
        graphics.text(font, value, x + 10 + font.width(name) + 8, y, CommonColors.WHITE, false);
    }

    private static String minutesSeconds(long millis) {
        long seconds = millis / 1000;
        return seconds / 60 + ":" + (seconds % 60 < 10 ? "0" : "") + seconds % 60;
    }

    @Override
    public void onClose() {
        ModConfig config = FileManager.getConfig();
        ModConfig.AiPerformance performance = new ModConfig.AiPerformance(profile, budgetGb(), keepSlider.intValue(), threadsSlider.intValue());
        FileManager.updateConfig(config.withAiGrading(config.aiGrading().withPerformance(performance)));
        Minecraft.getInstance().gui.setScreen(parent);
    }
}
