package io.github.dennisochulor.flashcards.config;

import java.lang.management.ManagementFactory;

/** What this computer has, and what Minecraft takes of it: the facts the AI memory settings are based on. */
public final class SystemMemory {
    private SystemMemory() {}

    public static final long GB = 1024L * 1024 * 1024;
    private static final long TOTAL_BYTES = readTotalBytes();

    private static long readTotalBytes() {
        try {
            if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
                return os.getTotalMemorySize();
            }
        }
        catch (Throwable ignored) {
            // not every Java runtime exposes this; the settings then work in GB only, without percentages
        }
        return -1;
    }

    /** @return installed RAM in bytes, or -1 if it can't be read */
    public static long totalBytes() {
        return TOTAL_BYTES;
    }

    public static double totalGb() {
        return TOTAL_BYTES <= 0 ? -1 : TOTAL_BYTES / (double) GB;
    }

    /** @return the most memory Minecraft's Java heap may grow to (its -Xmx setting) */
    public static long minecraftMaxBytes() {
        return Runtime.getRuntime().maxMemory();
    }

    /** @return the Java heap Minecraft is using right now */
    public static long minecraftUsedBytes() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    public static int cpuThreads() {
        return Math.max(1, Runtime.getRuntime().availableProcessors());
    }

    /** The largest budget the slider offers: most of the machine, or a sensible ceiling when RAM is unknown. */
    public static double maxBudgetGb() {
        return TOTAL_BYTES <= 0 ? 32 : Math.max(2, Math.floor(totalGb() * 0.85 * 2) / 2);
    }

    /**
     * What's left after Minecraft and the system, but never more than 45% of the machine.
     * Minecraft is counted as its full heap plus a quarter (it also uses memory outside the Java heap),
     * and the system and other apps as about 3.5 GB.
     */
    public static double recommendedBudgetGb() {
        if (TOTAL_BYTES <= 0) return 4;
        double left = totalGb() - minecraftMaxBytes() / (double) GB * 1.25 - 3.5;
        return clampBudget(Math.min(left, totalGb() * 0.45));
    }

    /** A quarter of the machine at most: room for a small model only. */
    public static double minecraftFirstBudgetGb() {
        if (TOTAL_BYTES <= 0) return 2.5;
        return clampBudget(Math.min(recommendedBudgetGb(), totalGb() * 0.25));
    }

    /** Everything but Minecraft and about 2 GB for the system, up to 65% of the machine. */
    public static double aiFirstBudgetGb() {
        if (TOTAL_BYTES <= 0) return 8;
        double left = totalGb() - minecraftMaxBytes() / (double) GB * 1.25 - 2;
        return clampBudget(Math.min(left, totalGb() * 0.65));
    }

    private static double clampBudget(double gb) {
        return Math.max(2, Math.min(maxBudgetGb(), Math.round(gb * 2) / 2.0));
    }

    /**
     * Roughly how much RAM a model needs once loaded: its file, plus working memory for the text it reads.
     * Ollama's own number is used instead whenever the model is actually loaded.
     */
    public static long estimateModelBytes(long fileBytes) {
        return (long) (fileBytes * 1.1) + GB / 2;
    }

    public static String formatGb(long bytes) {
        return String.format(java.util.Locale.ROOT, "%.1f GB", bytes / (double) GB);
    }
}
