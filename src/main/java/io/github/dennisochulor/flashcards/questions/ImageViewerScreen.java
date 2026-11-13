package io.github.dennisochulor.flashcards.questions;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.dennisochulor.flashcards.FileManager;
import io.github.dennisochulor.flashcards.ImageUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.CommonColors;

import java.util.Locale;
import java.util.Objects;

/**
 * A card's picture, as large as the screen allows. Scroll (or + / -) zooms around the mouse, dragging moves the picture,
 * "Fit" shows all of it and "1:1" shows it at its real size (one picture pixel per screen pixel, the sharpest view).
 * Closing returns to the card exactly as it was.
 */
public class ImageViewerScreen extends Screen {
    private static final int TOP_BAR = 18;
    private static final int BOTTOM_BAR = 30;
    private static final int BAR_COLOR = 0xD0101014;
    private static final int BACKDROP = 0xC0000000;
    private static final double ZOOM_STEP = 1.2;
    private static final int KEEP_VISIBLE = 40; // how much of the picture always stays on screen when dragging

    private final Screen parent;
    private final ImageUtils.ImagePackage picture;
    private final int pictureWidth, pictureHeight;

    /** GUI pixels per picture pixel. */
    private double zoom = 1;
    /** Where the picture's top-left corner is on screen. */
    private double offsetX, offsetY;
    private boolean placed = false;
    private boolean dragging = false;

    private final Button zoomOutButton = Button.builder(Component.literal("-"), _ -> zoomBy(1 / ZOOM_STEP, width / 2.0, viewCentreY()))
            .tooltip(Tooltip.create(Component.literal("Zoom out (mouse wheel down, or the - key)"))).size(20, 20).build();
    private final Button zoomInButton = Button.builder(Component.literal("+"), _ -> zoomBy(ZOOM_STEP, width / 2.0, viewCentreY()))
            .tooltip(Tooltip.create(Component.literal("Zoom in (mouse wheel up, or the + key)"))).size(20, 20).build();
    private final Button fitButton = Button.builder(Component.literal("Fit"), _ -> fit())
            .tooltip(Tooltip.create(Component.literal("Show the whole picture (key: 0)"))).size(40, 20).build();
    private final Button realSizeButton = Button.builder(Component.literal("1:1"), _ -> realSize())
            .tooltip(Tooltip.create(Component.literal("Real size: one picture pixel per screen pixel, the sharpest view (key: 1)"))).size(40, 20).build();
    private final Button closeButton = Button.builder(Component.literal("Close"), _ -> this.onClose())
            .tooltip(Tooltip.create(Component.literal("Back to the card (Esc)"))).size(60, 20).build();

    /** @param imageName the picture, relative to the mod's media folder */
    public ImageViewerScreen(String imageName) {
        super(Component.literal("Picture"));
        this.parent = Objects.requireNonNull(Minecraft.getInstance().gui.screen());
        this.picture = ImageUtils.getImagePackage(FileManager.getImageFile(imageName));
        this.pictureWidth = Math.max(1, picture.width());
        this.pictureHeight = Math.max(1, picture.height());
    }

    // ---------------------------------------------------------------- zoom maths

    private int viewHeight() {
        return Math.max(20, height - TOP_BAR - BOTTOM_BAR);
    }

    private double viewCentreY() {
        return TOP_BAR + viewHeight() / 2.0;
    }

    /** The zoom at which the whole picture just fits between the bars. */
    private double fitZoom() {
        return Math.min((width - 12.0) / pictureWidth, (viewHeight() - 8.0) / pictureHeight);
    }

    /** One picture pixel per physical screen pixel. */
    private double realSizeZoom() {
        return 1.0 / Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
    }

    private double minZoom() {
        return Math.min(fitZoom(), realSizeZoom()) * 0.5;
    }

    private double maxZoom() {
        return Math.max(fitZoom() * 8, 4);
    }

    private void centre() {
        offsetX = (width - pictureWidth * zoom) / 2;
        offsetY = TOP_BAR + (viewHeight() - pictureHeight * zoom) / 2;
    }

    private void fit() {
        zoom = Math.min(fitZoom(), 4); // a tiny picture is enlarged, but not into a blur
        centre();
    }

    private void realSize() {
        zoom = realSizeZoom();
        centre();
    }

    /** Zooms by a factor, keeping the picture point under (anchorX, anchorY) where it is. */
    private void zoomBy(double factor, double anchorX, double anchorY) {
        double newZoom = Math.max(minZoom(), Math.min(maxZoom(), zoom * factor));
        double pictureX = (anchorX - offsetX) / zoom;
        double pictureY = (anchorY - offsetY) / zoom;
        zoom = newZoom;
        offsetX = anchorX - pictureX * zoom;
        offsetY = anchorY - pictureY * zoom;
        keepOnScreen();
    }

    private void keepOnScreen() {
        double w = pictureWidth * zoom, h = pictureHeight * zoom;
        offsetX = Math.max(KEEP_VISIBLE - w, Math.min(width - KEEP_VISIBLE, offsetX));
        offsetY = Math.max(TOP_BAR + KEEP_VISIBLE - h, Math.min(height - BOTTOM_BAR - KEEP_VISIBLE, offsetY));
    }

    // ---------------------------------------------------------------- screen

    @Override
    protected void init() {
        if (!placed) { // first time: show the whole picture; a window resize keeps the player's view
            placed = true;
            fit();
        }
        keepOnScreen();

        // drawn in this order: the picture, then the bars over it, then the buttons
        addRenderableOnly(this::extractPicture);
        addRenderableOnly(this::extractBars);

        int total = 20 + 4 + 20 + 8 + 40 + 4 + 40 + 12 + 60;
        int x = width / 2 - total / 2;
        int y = height - BOTTOM_BAR + 5;
        zoomOutButton.setPosition(x, y);
        zoomInButton.setPosition(x + 24, y);
        fitButton.setPosition(x + 52, y);
        realSizeButton.setPosition(x + 96, y);
        closeButton.setPosition(x + 148, y);
        addRenderableWidget(zoomOutButton);
        addRenderableWidget(zoomInButton);
        addRenderableWidget(fitButton);
        addRenderableWidget(realSizeButton);
        addRenderableWidget(closeButton);
    }

    private void extractPicture(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, BACKDROP);
        int w = Math.max(1, (int) Math.round(pictureWidth * zoom));
        int h = Math.max(1, (int) Math.round(pictureHeight * zoom));
        var widget = ImageUtils.getImageWidget(picture, w, h);
        widget.setPosition((int) Math.round(offsetX), (int) Math.round(offsetY));
        widget.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    private void extractBars(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        Font font = Minecraft.getInstance().font;
        graphics.fill(0, 0, width, TOP_BAR, BAR_COLOR);
        graphics.fill(0, height - BOTTOM_BAR, width, height, BAR_COLOR);

        // 100% = real size on this screen
        String percent = String.format(Locale.ROOT, "%.0f%%", zoom / realSizeZoom() * 100);
        String size = pictureWidth + " x " + pictureHeight + "  ·  " + percent;
        graphics.text(font, size, width - font.width(size) - 6, 5, 0xFFAAAAAA, false);
        graphics.text(font, "Scroll to zoom  ·  drag to move", 6, 5, 0xFFAAAAAA, false);
        String name = font.plainSubstrByWidth(picture.name(), Math.max(0, width - 2 * (font.width("Scroll to zoom  ·  drag to move") + 16)));
        graphics.text(font, name, width / 2 - font.width(name) / 2, 5, CommonColors.WHITE, false);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true; // the buttons
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && event.y() >= TOP_BAR && event.y() < height - BOTTOM_BAR) {
            if (doubleClick) { // double-click: jump between the whole picture and real size
                if (Math.abs(zoom - realSizeZoom()) < 1e-6) fit();
                else {
                    double factor = realSizeZoom() / zoom;
                    zoomBy(factor, event.x(), event.y());
                }
            }
            else dragging = true;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (!dragging) return super.mouseDragged(event, dragX, dragY);
        offsetX += dragX;
        offsetY += dragY;
        keepOnScreen();
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (dragging) {
            dragging = false;
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY == 0) return false;
        zoomBy(Math.pow(ZOOM_STEP, scrollY), mouseX, Math.max(TOP_BAR, Math.min(height - BOTTOM_BAR, mouseY)));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int step = event.hasShiftDown() ? 60 : 20;
        switch (event.key()) {
            case InputConstants.KEY_EQUALS, InputConstants.KEY_ADD -> { zoomBy(ZOOM_STEP, width / 2.0, viewCentreY()); return true; }
            case InputConstants.KEY_MINUS -> { zoomBy(1 / ZOOM_STEP, width / 2.0, viewCentreY()); return true; }
            case InputConstants.KEY_0 -> { fit(); return true; }
            case InputConstants.KEY_1 -> { realSize(); return true; }
            case InputConstants.KEY_LEFT -> { offsetX += step; keepOnScreen(); return true; }
            case InputConstants.KEY_RIGHT -> { offsetX -= step; keepOnScreen(); return true; }
            case InputConstants.KEY_UP -> { offsetY += step; keepOnScreen(); return true; }
            case InputConstants.KEY_DOWN -> { offsetY -= step; keepOnScreen(); return true; }
            default -> { return super.keyPressed(event); }
        }
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(parent);
    }
}
