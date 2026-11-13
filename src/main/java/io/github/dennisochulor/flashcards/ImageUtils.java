package io.github.dennisochulor.flashcards;

import javax.imageio.ImageIO;
import javax.swing.filechooser.FileNameExtensionFilter;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ImageWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import com.mojang.blaze3d.platform.NativeImage;

import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class ImageUtils {
    private ImageUtils() {}

    public static final FileNameExtensionFilter FILE_NAME_EXTENSION_FILTER = new FileNameExtensionFilter("JPG/JPEG/PNG/GIF/BMP image files",
            "jpg", "jpeg", "png", "bmp", "gif");
    public static final Identifier MISSING_TEXTURE_ID = Identifier.withDefaultNamespace("textures/missing.png");
    public static final ImagePackage MISSING_TEXTURE = new ImagePackage(MISSING_TEXTURE_ID, "Missing Image File", 1, 1, 16, 16);

    // LRU map
    // String key is "filename_filesize", filesize included to avoid newly attached image with same name from using cached image
    private static final SequencedMap<String, ImagePackage> REGISTERED_IMAGES = new LinkedHashMap<>(16, 0.75F, true);
    private static final AtomicInteger IMAGE_ID_COUNTER = new AtomicInteger();
    /** How much decoded picture data (4 bytes per pixel, as it sits on the graphics card) is kept before old pictures are dropped. */
    private static final long CACHE_LIMIT_BYTES = 256L * 1024 * 1024;
    private static final Map<String, Long> REGISTERED_BYTES = new HashMap<>();
    private static long currentTotalImageBytes = 0;

    /**
     * Pictures decoded ahead of time on a background thread (see {@link #predecode}), waiting to be turned into textures
     * on the render thread. Decoding a big screenshot takes a noticeable moment; this keeps that moment off the game's frames.
     */
    private static final Map<String, NativeImage> PREDECODED = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int MAX_PREDECODED = 12;

    /**
     * @param widthScaler  width divided by the larger of width and height (so 1 for a landscape picture)
     * @param heightScaler height divided by the larger of width and height
     * @param width        the picture's real width in pixels
     * @param height       the picture's real height in pixels
     */
    public record ImagePackage(Identifier id, String name, float widthScaler, float heightScaler, int width, int height) {}

    private static String key(File file) {
        return file.getName() + "_" + file.length();
    }

    private static NativeImage decode(File file) throws IOException {
        BufferedImage bufferedImage = ImageIO.read(file);
        if (bufferedImage == null) throw new IOException("Not a readable image: " + file.getName());
        int width = bufferedImage.getWidth(), height = bufferedImage.getHeight();
        NativeImage img = new NativeImage(width, height, false);
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            bufferedImage.getRGB(0, y, width, 1, row, 0, width); // a row at a time is much quicker than pixel by pixel
            for (int x = 0; x < width; x++) img.setPixel(x, y, row[x]);
        }
        return img;
    }

    /**
     * Decodes a picture now, on the calling (background) thread, so that showing it later doesn't stall a frame.
     * Safe to call from any thread; does nothing for files that can't be shown.
     */
    public static void predecode(File file) {
        if (!file.exists() || !FILE_NAME_EXTENSION_FILTER.accept(file)) return;
        String key = key(file);
        if (PREDECODED.containsKey(key) || PREDECODED.size() >= MAX_PREDECODED) return;
        try {
            NativeImage previous = PREDECODED.put(key, decode(file));
            if (previous != null) previous.close();
        }
        catch (IOException | RuntimeException e) {
            Flashcards.LOGGER.debug("Couldn't decode {} ahead of time", file.getName(), e);
        }
    }

    /**
     * @param file The image file
     * @return an {@link ImagePackage} for the image. This returns {@link ImageUtils#MISSING_TEXTURE} if the image file does not exist or
     * if the image file is using an unsupported filename extension according to {@link ImageUtils#FILE_NAME_EXTENSION_FILTER}
     */
    public static ImagePackage getImagePackage(File file) {
        if (!file.exists() || !FILE_NAME_EXTENSION_FILTER.accept(file)) return MISSING_TEXTURE;

        String key = key(file);
        ImagePackage cachedImg = REGISTERED_IMAGES.get(key);
        if (cachedImg != null) {
            NativeImage unused = PREDECODED.remove(key); // decoded ahead of time for nothing: free it
            if (unused != null) unused.close();
            Flashcards.LOGGER.debug("from the cache {}", cachedImg.id);
            return cachedImg;
        }

        NativeImage img = PREDECODED.remove(key);
        if (img == null) {
            try {
                img = decode(file);
            }
            catch (IOException | RuntimeException e) { // a damaged picture shouldn't take the whole card down
                Flashcards.LOGGER.warn("Couldn't read image {}", file.getPath(), e);
                return MISSING_TEXTURE;
            }
        }

        DynamicTexture texture = new DynamicTexture(file::getName, img);
        float greaterDimension = Math.max(img.getHeight(), img.getWidth());
        Identifier id = Identifier.fromNamespaceAndPath("flashcards", "image_" + IMAGE_ID_COUNTER.getAndIncrement());
        ImagePackage imgPkg = new ImagePackage(id, file.getName(), img.getWidth()/greaterDimension, img.getHeight()/greaterDimension,
                img.getWidth(), img.getHeight());

        // Drop the least recently used pictures once the cache is full. Evicting by key (not by looking the file up again)
        // always makes progress, also for pictures in subfolders.
        long bytes = (long) img.getWidth() * img.getHeight() * 4;
        while (currentTotalImageBytes + bytes > CACHE_LIMIT_BYTES && !REGISTERED_IMAGES.isEmpty()) {
            evict(REGISTERED_IMAGES.firstEntry().getKey());
        }
        currentTotalImageBytes += bytes;
        REGISTERED_BYTES.put(key, bytes);

        REGISTERED_IMAGES.put(key, imgPkg);
        Minecraft.getInstance().getTextureManager().register(id, texture);
        Flashcards.LOGGER.debug("new one {}", imgPkg.id);
        return imgPkg;
    }

    private static void evict(String key) {
        ImagePackage imgPkg = REGISTERED_IMAGES.remove(key);
        Long bytes = REGISTERED_BYTES.remove(key);
        if (imgPkg == null) return;
        Minecraft.getInstance().getTextureManager().release(imgPkg.id());
        if (bytes != null) currentTotalImageBytes -= bytes;
        Flashcards.LOGGER.debug("RELEASE {} / Left with {}", imgPkg.id, currentTotalImageBytes);
    }

    public static ImagePackage getImagePackageFromAssets(InputStream input, String name) {
        try {
            BufferedImage bufferedImage = ImageIO.read(input);
            NativeImage img = new NativeImage(bufferedImage.getWidth(),bufferedImage.getHeight(),false);
            for (int y=0;y<bufferedImage.getHeight();y++) {
                for (int x=0;x<bufferedImage.getWidth();x++) {
                    int argb = bufferedImage.getRGB(x,y);
                    img.setPixel(x,y,argb);
                }
            }

            DynamicTexture texture = new DynamicTexture(() -> name, img);
            float greaterDimension = Math.max(img.getHeight(),img.getWidth());
            Identifier id = Identifier.fromNamespaceAndPath("flashcards", "assetimage_" + name);
            Minecraft.getInstance().getTextureManager().register(id,texture);
            return new ImagePackage(id, name, img.getWidth()/greaterDimension, img.getHeight()/greaterDimension, img.getWidth(), img.getHeight());
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static ImageWidget getImageWidget(File file, int size) {
        Objects.requireNonNull(file, "file cannot be null!");

        ImagePackage imgPkg = getImagePackage(file);
        if (imgPkg.id() == MISSING_TEXTURE_ID) {
            imgPkg = new ImagePackage(MISSING_TEXTURE_ID, file.getName(), 1, 1, 16, 16);
        }

        return getImageWidget(imgPkg, size);
    }

    public static ImageWidget getImageWidget(ImagePackage imgPkg, int size) {
        ImageWidget imageWidget;

        if (imgPkg.id() == MISSING_TEXTURE_ID) {
            imageWidget = ImageWidget.texture(size, size, imgPkg.id(), size, size);
            imageWidget.setTooltip(Tooltip.create(Component.literal(imgPkg.name() + " seems to be missing...")));
        }
        else {
            int width = (int)(size * imgPkg.widthScaler());
            int height = (int)(size * imgPkg.heightScaler());
            imageWidget = ImageWidget.texture(width, height, imgPkg.id(), width, height);
            imageWidget.setTooltip(Tooltip.create(Component.literal(imgPkg.name())));
        }

        return imageWidget;
    }

    /** A widget that draws the picture at exactly this size (in GUI pixels), without a tooltip. */
    public static ImageWidget getImageWidget(ImagePackage imgPkg, int width, int height) {
        return ImageWidget.texture(width, height, imgPkg.id(), width, height);
    }

    public static void release(File file) {
        evict(key(file));
    }
}
