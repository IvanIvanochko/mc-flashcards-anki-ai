package io.github.dennisochulor.flashcards.config;

import io.github.dennisochulor.flashcards.ai.Ollama;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.CommonColors;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Picks an Ollama model from a searchable list that shows each model's size and whether it fits the AI memory budget.
 * Click a model, then Done (or double-check with the search box when there are many).
 */
class ModelPickerScreen extends Screen {
    private static final int GRAY = 0xFFAAAAAA;
    private static final int WIDTH = 300;

    private final Screen parent;
    private final Consumer<String> onPick;
    private final Map<String, Ollama.ModelInfo> infos = new HashMap<>();
    private final @Nullable String defaultRow; // e.g. "Default (qwen2.5:7b)", shown first; picking it returns ""
    private String picked; // a row name

    private final StringWidget titleText;
    private final StringWidget legend = new StringWidget(Component.empty(), Minecraft.getInstance().font);
    private final EditBox searchBox = new EditBox(Minecraft.getInstance().font, WIDTH, 18, Component.literal("Search models"));
    private final DeckListWidget list;
    private final Button doneButton = Button.builder(Component.literal("Done"), _ -> this.onClose()).size(100, 20).build();

    /**
     * @param current        the model in use, or "" for the default
     * @param defaultLabel   what "" means here, e.g. the main model's name; null to offer no "Default" row
     * @param onPick         gets the picked model name, or "" for the default
     */
    ModelPickerScreen(String title, List<Ollama.ModelInfo> models, String current, @Nullable String defaultLabel, Consumer<String> onPick) {
        super(Component.literal(title));
        this.parent = Objects.requireNonNull(Minecraft.getInstance().gui.screen());
        this.onPick = onPick;
        this.titleText = new StringWidget(Component.literal(title), Minecraft.getInstance().font);
        this.defaultRow = defaultLabel == null ? null : "Default (" + (defaultLabel.isBlank() ? "none" : defaultLabel) + ")";

        List<String> rows = new ArrayList<>();
        if (defaultRow != null) rows.add(defaultRow);
        for (Ollama.ModelInfo info : models) {
            infos.put(info.name(), info);
            rows.add(info.name());
        }
        if (!current.isBlank() && !infos.containsKey(current)) rows.add(current); // saved, but not installed right now
        picked = current.isBlank() && defaultRow != null ? defaultRow : current;

        double budgetGb = io.github.dennisochulor.flashcards.FileManager.getConfig().aiGrading().performance().memoryBudgetGb();
        long budgetBytes = (long) (budgetGb * SystemMemory.GB);
        list = new DeckListWidget(WIDTH, 100, 0, row -> picked = row);
        list.keepOrder();
        list.setDetail(row -> {
            Ollama.ModelInfo info = infos.get(row);
            if (info == null) return row.equals(defaultRow) ? "" : "not installed";
            return info.fileBytes() <= 0 ? "" : "~" + SystemMemory.formatGb(SystemMemory.estimateModelBytes(info.fileBytes()));
        }, row -> {
            Ollama.ModelInfo info = infos.get(row);
            if (info == null) return row.equals(defaultRow) ? GRAY : CommonColors.SOFT_RED;
            return SystemMemory.estimateModelBytes(info.fileBytes()) <= budgetBytes ? CommonColors.GREEN : CommonColors.SOFT_YELLOW;
        });
        list.setDecks(rows, picked);

        legend.setMessage(Component.literal("~ RAM needed  ·  ").withColor(GRAY)
                .append(Component.literal("green").withColor(CommonColors.GREEN))
                .append(Component.literal(" fits your " + String.format(java.util.Locale.ROOT, "%.1f", budgetGb) + " GB AI budget, ").withColor(GRAY))
                .append(Component.literal("yellow").withColor(CommonColors.SOFT_YELLOW))
                .append(Component.literal(" doesn't").withColor(GRAY)));

        searchBox.setHint(Component.literal("Search models...").withColor(GRAY));
        searchBox.setMaxLength(100);
        searchBox.setResponder(list::setFilter);
    }

    @Override
    protected void init() {
        doneButton.setTooltip(Tooltip.create(Component.literal("Use the highlighted model and go back")));
        int listWidth = Math.min(WIDTH, width - 24);
        int x = width / 2 - listWidth / 2;
        titleText.setPosition(width / 2 - titleText.getWidth() / 2, 8);
        searchBox.setWidth(listWidth);
        searchBox.setPosition(x, 24);
        int doneY = height - 26;
        doneButton.setPosition(width / 2 - doneButton.getWidth() / 2, doneY);
        legend.setMaxWidth(width - 16);
        legend.setPosition(width / 2 - legend.getWidth() / 2, doneY - 14);
        int listTop = 46;
        list.updateSizeAndPosition(listWidth, Math.max(3 * 14 + 4, doneY - 18 - listTop), x, listTop);

        addRenderableWidget(titleText);
        addRenderableWidget(searchBox);
        addRenderableWidget(list);
        addRenderableWidget(legend);
        addRenderableWidget(doneButton);
        setInitialFocus(searchBox);
    }

    @Override
    public void onClose() {
        onPick.accept(picked.equals(defaultRow) ? "" : picked);
        Minecraft.getInstance().gui.setScreen(parent);
    }
}
