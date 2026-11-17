package io.github.dennisochulor.flashcards.config;

import io.github.dennisochulor.flashcards.progress.Progression;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.CommonColors;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * How much a grade's points count, by how long ago the card was last seen (from Anki): five rows (a new card, then four
 * time ranges), each with a multiplier for gains and one for losses.
 */
class TimeBandsScreen extends Screen {
    private static final int GRAY = 0xFFAAAAAA;
    private static final int FIELD = 48;

    private final Screen parent;
    private final BiConsumer<List<Progression.TimeBand>, Double> onSave;

    private final StringWidget titleText = new StringWidget(Component.literal("Timing: when was the card last seen?"), Minecraft.getInstance().font);
    private final MultiLineTextWidget intro = new MultiLineTextWidget(Component.literal(
            "A grade's points are multiplied by the factor of the row the card falls in. Knowing a card you last saw weeks ago "
            + "is worth more than one you saw a minute ago; forgetting it can cost more too. Gain = positive points, loss = negative.")
            .withColor(GRAY), Minecraft.getInstance().font);
    private final StringWidget gainHeader = new StringWidget(Component.literal("gain ×").withColor(CommonColors.GREEN), Minecraft.getInstance().font);
    private final StringWidget lossHeader = new StringWidget(Component.literal("loss ×").withColor(CommonColors.SOFT_RED), Minecraft.getInstance().font);
    private final StringWidget newLabel = new StringWidget(Component.literal("New card (never seen)"), Minecraft.getInstance().font);
    private final EditBox newBox = box("Multiplier for a new card");
    private final EditBox[] limitBoxes = new EditBox[3];   // the upper ends (hours) of the first three ranges
    private final EditBox[] gainBoxes = new EditBox[4];
    private final EditBox[] lossBoxes = new EditBox[4];
    private final StringWidget[] rowLabels = new StringWidget[4];
    private final StringWidget status = new StringWidget(Component.empty(), Minecraft.getInstance().font);
    private final Button resetButton;
    private final Button saveButton = Button.builder(Component.literal("Done"), _ -> save())
            .tooltip(Tooltip.create(Component.literal("Keep these multipliers and go back"))).size(80, 20).build();
    private final Button cancelButton = Button.builder(Component.literal("Cancel"), _ -> this.onClose())
            .tooltip(Tooltip.create(Component.literal("Go back without changing anything"))).size(80, 20).build();

    TimeBandsScreen(List<Progression.TimeBand> bands, double newCard, BiConsumer<List<Progression.TimeBand>, Double> onSave) {
        super(Component.literal("Timing"));
        this.parent = Objects.requireNonNull(Minecraft.getInstance().gui.screen());
        this.onSave = onSave;
        List<Progression.TimeBand> four = bands.size() == 4 ? bands : Progression.DEFAULT_BANDS;
        for (int i = 0; i < 4; i++) {
            gainBoxes[i] = box("Multiplier for positive points");
            lossBoxes[i] = box("Multiplier for negative points");
            rowLabels[i] = new StringWidget(Component.empty(), Minecraft.getInstance().font);
            if (i < 3) {
                limitBoxes[i] = box("Up to this many hours");
                Double up = four.get(i).upToHours();
                limitBoxes[i].setValue(number(up == null ? 24 : up));
            }
        }
        fill(four, newCard);
        resetButton = Button.builder(Component.literal("Reset"), _ -> fill(Progression.DEFAULT_BANDS, 1.0))
                .tooltip(Tooltip.create(Component.literal("Back to: new ×1; under 1 h ×0.5; under 1 day ×1; under 7 days ×1.5; longer ×2"))).size(70, 20).build();
        intro.setCentered(true);
        validate();
    }

    private EditBox box(String tooltip) {
        EditBox box = new EditBox(Minecraft.getInstance().font, FIELD, 18, Component.literal(tooltip));
        box.setMaxLength(8);
        box.setTooltip(Tooltip.create(Component.literal(tooltip)));
        box.setResponder(_ -> validate());
        return box;
    }

    private void fill(List<Progression.TimeBand> bands, double newCard) {
        newBox.setValue(number(newCard));
        for (int i = 0; i < 4; i++) {
            gainBoxes[i].setValue(number(bands.get(i).gain()));
            lossBoxes[i].setValue(number(bands.get(i).loss()));
            if (i < 3) {
                Double up = bands.get(i).upToHours();
                limitBoxes[i].setValue(number(up == null ? 24 : up));
            }
        }
        validate();
    }

    private static String number(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    private static @Nullable Double parse(EditBox box) {
        try {
            double value = Double.parseDouble(box.getValue().strip().replace(',', '.'));
            return value >= 0 ? value : null;
        }
        catch (NumberFormatException e) {
            return null;
        }
    }

    /** Checks every field; marks bad ones red; enables Done only when everything's fine. */
    private boolean validate() {
        boolean ok = true;
        List<EditBox> all = new ArrayList<>(List.of(newBox));
        for (int i = 0; i < 4; i++) {
            all.add(gainBoxes[i]);
            all.add(lossBoxes[i]);
            if (i < 3) all.add(limitBoxes[i]);
        }
        for (EditBox box : all) {
            boolean good = parse(box) != null;
            box.setTextColor(good ? CommonColors.WHITE : CommonColors.SOFT_RED);
            ok &= good;
        }
        Double l0 = parse(limitBoxes[0]), l1 = parse(limitBoxes[1]), l2 = parse(limitBoxes[2]);
        boolean ordered = l0 != null && l1 != null && l2 != null && l0 > 0 && l0 < l1 && l1 < l2;
        if (ok && !ordered) {
            status.setMessage(Component.literal("The hours must go up from row to row.").withColor(CommonColors.SOFT_RED));
            ok = false;
        }
        else status.setMessage(ok ? Component.empty() : Component.literal("Use numbers of 0 or more.").withColor(CommonColors.SOFT_RED));
        status.setX(width / 2 - status.getWidth() / 2);
        if (ordered) {
            rowLabels[0].setMessage(Component.literal("Seen less than"));
            rowLabels[1].setMessage(Component.literal(hours(l0) + " to"));
            rowLabels[2].setMessage(Component.literal(hours(l1) + " to"));
            rowLabels[3].setMessage(Component.literal("Longer than " + hours(l2)));
        }
        saveButton.active = ok;
        return ok;
    }

    private static String hours(double h) {
        if (h >= 24 && h % 24 == 0) return (long) (h / 24) + (h == 24 ? " day" : " days");
        return number(h) + " h";
    }

    @Override
    protected void init() {
        int w = Math.min(330, width - 24);
        int x = width / 2 - w / 2;
        int y = 8;
        titleText.setPosition(width / 2 - titleText.getWidth() / 2, y);
        addRenderableWidget(titleText);
        y += 14;
        intro.setMaxWidth(w);
        intro.setPosition(width / 2 - intro.getWidth() / 2, y);
        addRenderableWidget(intro);
        y += intro.getHeight() + 8;

        int gainX = x + w - FIELD * 2 - 6;
        int lossX = x + w - FIELD;
        gainHeader.setPosition(gainX + (FIELD - gainHeader.getWidth()) / 2, y);
        lossHeader.setPosition(lossX + (FIELD - lossHeader.getWidth()) / 2, y);
        addRenderableWidget(gainHeader);
        addRenderableWidget(lossHeader);
        y += 12;

        newLabel.setPosition(x, y + 5);
        addRenderableWidget(newLabel);
        newBox.setPosition(gainX, y);
        addRenderableWidget(newBox);
        y += 22;
        for (int i = 0; i < 4; i++) {
            rowLabels[i].setPosition(x, y + 5);
            addRenderableWidget(rowLabels[i]);
            if (i < 3) {
                limitBoxes[i].setPosition(x + 90, y);
                addRenderableWidget(limitBoxes[i]);
                StringWidget unit = new StringWidget(Component.literal("hours").withColor(GRAY), Minecraft.getInstance().font);
                unit.setPosition(x + 90 + FIELD + 4, y + 5);
                addRenderableWidget(unit);
            }
            gainBoxes[i].setPosition(gainX, y);
            lossBoxes[i].setPosition(lossX, y);
            addRenderableWidget(gainBoxes[i]);
            addRenderableWidget(lossBoxes[i]);
            y += 22;
        }
        status.setPosition(width / 2 - status.getWidth() / 2, y + 2);
        addRenderableWidget(status);

        int buttonsY = Math.max(height - 26, y + 16);
        resetButton.setPosition(width / 2 - 121, buttonsY);
        saveButton.setPosition(width / 2 - 47, buttonsY);
        cancelButton.setPosition(width / 2 + 37, buttonsY);
        addRenderableWidget(resetButton);
        addRenderableWidget(saveButton);
        addRenderableWidget(cancelButton);
    }

    private void save() {
        if (!validate()) return;
        List<Progression.TimeBand> bands = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Double up = i < 3 ? parse(limitBoxes[i]) : null;
            bands.add(new Progression.TimeBand(up, parse(gainBoxes[i]), parse(lossBoxes[i])));
        }
        Double newCard = parse(newBox);
        onSave.accept(bands, newCard == null ? 1.0 : newCard);
        Minecraft.getInstance().gui.setScreen(parent);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(parent);
    }
}
