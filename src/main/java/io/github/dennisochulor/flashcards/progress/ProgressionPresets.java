package io.github.dennisochulor.flashcards.progress;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import io.github.dennisochulor.flashcards.FileManager;
import io.github.dennisochulor.flashcards.Flashcards;
import io.github.dennisochulor.flashcards.progress.Progression.Condition;
import io.github.dennisochulor.flashcards.progress.Progression.GradeSetup;
import io.github.dennisochulor.flashcards.progress.Progression.Overlap;
import io.github.dennisochulor.flashcards.progress.Progression.Pick;
import io.github.dennisochulor.flashcards.progress.Progression.Rule;
import io.github.dennisochulor.flashcards.progress.Progression.Setup;
import io.github.dennisochulor.flashcards.progress.Progression.Trigger;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Ready-made rewards & penalties setups, plus the player's own saved ones (config/flashcards/progression-presets.json),
 * and turning a setup into text for the clipboard and back.
 */
public final class ProgressionPresets {
    private ProgressionPresets() {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String SHARE_HEADER = "Flashcards rewards & penalties preset";
    private static final String FILE_NAME = "progression-presets.json";

    // ---------------------------------------------------------------- building blocks

    private static Rule always(String name, Pick pick, String... commands) {
        return Rule.of(name, Condition.ALWAYS, 0, 0, Trigger.EVERY_TIME, null, false, pick, commands);
    }

    private static Rule atMost(double score, String name, Trigger trigger, @Nullable Double resetTo, String... commands) {
        return Rule.of(name, Condition.SCORE_AT_MOST, score, score, trigger, resetTo, false, Pick.ALL, commands);
    }

    private static Rule atLeast(double score, String name, Trigger trigger, @Nullable Double resetTo, String... commands) {
        return Rule.of(name, Condition.SCORE_AT_LEAST, score, score, trigger, resetTo, false, Pick.ALL, commands);
    }

    private static Rule times(int n, String name, boolean restartCount, String... commands) {
        return Rule.of(name, Condition.GIVEN_TIMES, n, n, Trigger.ONCE, null, restartCount, Pick.ALL, commands);
    }

    private static Rule every(int n, String name, String... commands) {
        return Rule.of(name, Condition.EVERY_NTH, n, n, Trigger.EVERY_TIME, null, false, Pick.ALL, commands);
    }

    private static GradeSetup grade(double points, Overlap overlap, Rule... rules) {
        return new GradeSetup(points, overlap, List.of(rules));
    }

    private static Setup setup(String name, double fadeHours, GradeSetup again, GradeSetup hard, GradeSetup good, GradeSetup easy) {
        return new Setup(name, again, hard, good, easy, Progression.DEFAULT_BANDS, 1.0, fadeHours);
    }

    /** Same rules on several grades, with fresh ids so "once" rules of different grades don't share a latch. */
    private static GradeSetup copy(GradeSetup grade, double points) {
        List<Rule> rules = new ArrayList<>();
        for (Rule rule : grade.rules()) rules.add(rule.withId(Progression.newId()));
        return new GradeSetup(points, grade.overlap(), rules);
    }

    private static GradeSetup none(double points) {
        return new GradeSetup(points, Overlap.ALL, List.of());
    }

    // ---------------------------------------------------------------- built-in presets

    /** A built-in preset, with a line explaining it. */
    public record Preset(String name, String description, Setup setup) {}

    public static final List<Preset> BUILT_IN = buildBuiltIns();

    private static List<Preset> buildBuiltIns() {
        List<Preset> list = new ArrayList<>();
        list.add(new Preset("Off", "No rewards and no penalties. The score still counts, if you want to watch it.", Setup.empty()));

        GradeSetup classicGood = grade(1, Overlap.STRONGEST,
                atLeast(5, "XP bonus", Trigger.ONCE, null, "xp add @s 15 points"),
                atLeast(10, "Second wind", Trigger.ONCE, null, "effect give @s minecraft:regeneration 10 1 true", "effect give @s minecraft:absorption 60 1 true"),
                atLeast(20, "Jackpot", Trigger.ONCE, 0.0, "give @s minecraft:diamond 2"));
        list.add(new Preset("Classic ladder",
                "Mistakes pile up: slowness from -1, weakness, hunger and slow mining at -5, death at -10 (score back to 0). "
                + "Good answers climb: XP at +5, regeneration and extra hearts at +10, diamonds at +20 (back to 0). Fades over a day.",
                setup("Classic ladder", 24,
                        grade(-1, Overlap.ALL,
                                atMost(-1, "Slowed", Trigger.EVERY_TIME, null, "effect give @s minecraft:slowness 10 0 true"),
                                atMost(-5, "Weakened", Trigger.ONCE, null, "effect give @s minecraft:weakness 30 0 true",
                                        "effect give @s minecraft:hunger 20 1 true", "effect give @s minecraft:mining_fatigue 30 0 true"),
                                atMost(-10, "Learn or die", Trigger.ONCE, 0.0, "kill @s")),
                        none(0), classicGood, copy(classicGood, 2))));

        GradeSetup coachGood = grade(1, Overlap.ALL,
                always("Well done", Pick.ALL, "xp add @s 3 points"),
                every(5, "On a roll", "effect give @s minecraft:speed 30 0 true"),
                atLeast(10, "Golden", Trigger.ONCE, 0.0, "give @s minecraft:golden_apple 1"));
        list.add(new Preset("Gentle coach",
                "Rewards only. A little XP for every right answer, speed every 5th time, a golden apple at +10. Mistakes just cost points.",
                setup("Gentle coach", 24, none(-1), none(0), coachGood, copy(coachGood, 2))));

        list.add(new Preset("Strikes",
                "Punishments only, counted per Again: slowness every time, hunger on the 3rd, weakness, darkness and damage on the 5th, "
                + "death on the 7th (the count starts over). Mistakes fade over 12 hours.",
                setup("Strikes", 12,
                        grade(-1, Overlap.ALL,
                                always("Slowed", Pick.ALL, "effect give @s minecraft:slowness 10 0 true"),
                                times(3, "Hungry", false, "effect give @s minecraft:hunger 30 1 true"),
                                times(5, "Strike five", false, "effect give @s minecraft:weakness 30 0 true",
                                        "effect give @s minecraft:darkness 8 0 true", "damage @s 6 minecraft:magic"),
                                times(7, "Out", true, "kill @s")),
                        none(0), none(1), none(2))));

        GradeSetup huntGood = grade(1, Overlap.STRONGEST,
                every(5, "Iron", "give @s minecraft:iron_ingot 3"),
                atLeast(10, "Gold", Trigger.ONCE, null, "give @s minecraft:gold_ingot 3"),
                atLeast(15, "Emeralds", Trigger.ONCE, null, "give @s minecraft:emerald 3"),
                atLeast(25, "Diamond", Trigger.ONCE, null, "give @s minecraft:diamond 1"),
                atLeast(40, "Netherite", Trigger.ONCE, 0.0, "give @s minecraft:netherite_scrap 1"));
        list.add(new Preset("Treasure hunt",
                "Rewards only, as loot: iron every 5th right answer, then gold, emeralds, a diamond and netherite scrap as the score "
                + "climbs (back to 0 after netherite). Mistakes cost points, so they slow the hunt down.",
                setup("Treasure hunt", 0, none(-1), none(0), huntGood, copy(huntGood, 2))));

        GradeSetup redemptionGood = grade(1, Overlap.STRONGEST,
                atLeast(3, "Haste", Trigger.ONCE, null, "effect give @s minecraft:haste 60 0 true"),
                atLeast(6, "Lucky", Trigger.ONCE, 0.0, "effect give @s minecraft:luck 120 0 true", "xp add @s 10 points"));
        list.add(new Preset("Redemption",
                "Mild both ways, and it forgives quickly (halves every 6 hours): slowness from -3, slow mining at -6; "
                + "haste at +3, luck and XP at +6.",
                setup("Redemption", 6,
                        grade(-1, Overlap.STRONGEST,
                                atMost(-3, "Sluggish", Trigger.EVERY_TIME, null, "effect give @s minecraft:slowness 10 0 true"),
                                atMost(-6, "Tired", Trigger.ONCE, 0.0, "effect give @s minecraft:mining_fatigue 60 0 true")),
                        none(0), redemptionGood, copy(redemptionGood, 2))));

        // the earlier flat presets, as "always" rules, so they behave exactly as before
        list.add(flat("Gentle", "Flat: a little XP and regeneration for a right answer; a short slow-down for a wrong one.", Pick.ALL,
                List.of("xp add @s 5 points", "effect give @s minecraft:regeneration 5 0 true"),
                List.of("effect give @s minecraft:slowness 10 0 true")));
        list.add(flat("Balanced", "Flat: XP, food and speed when you're right; hunger and slow mining when you're wrong.", Pick.ALL,
                List.of("xp add @s 10 points", "effect give @s minecraft:saturation 1 2 true", "effect give @s minecraft:speed 30 0 true"),
                List.of("effect give @s minecraft:hunger 20 1 true", "effect give @s minecraft:mining_fatigue 30 0 true")));
        list.add(flat("Tough", "Flat: XP and extra hearts when you're right; 3 hearts of damage, weakness and darkness when you're wrong.", Pick.ALL,
                List.of("xp add @s 15 points", "effect give @s minecraft:absorption 60 1 true"),
                List.of("damage @s 6 minecraft:magic", "effect give @s minecraft:weakness 30 0 true", "effect give @s minecraft:darkness 8 0 true")));
        list.add(flat("Learn or Die", "Flat: XP, regeneration and resistance when you're right; death on every wrong answer. Not for Hardcore worlds.", Pick.ALL,
                List.of("xp add @s 20 points", "effect give @s minecraft:regeneration 10 1 true", "effect give @s minecraft:resistance 30 0 true"),
                List.of("kill @s")));
        list.add(flat("Lucky Dip", "Flat: one random prize when you're right, one random mishap when you're wrong.", Pick.ONE_RANDOM,
                List.of("give @s minecraft:iron_ingot 3", "give @s minecraft:gold_ingot 2", "give @s minecraft:diamond 1",
                        "give @s minecraft:emerald 2", "give @s minecraft:golden_apple 1", "give @s minecraft:experience_bottle 4", "xp add @s 25 points"),
                List.of("effect give @s minecraft:nausea 10 0 true", "effect give @s minecraft:hunger 30 1 true",
                        "effect give @s minecraft:levitation 3 1 true", "damage @s 4 minecraft:magic",
                        "summon minecraft:lightning_bolt ~ ~ ~", "summon minecraft:zombie ~2 ~ ~")));
        return List.copyOf(list);
    }

    private static Preset flat(String name, String description, Pick pick, List<String> right, List<String> wrong) {
        return new Preset(name, description, fromFlat(name, right, wrong, pick));
    }

    /**
     * The older kind of rewards (same commands for every right answer, same for every wrong one) as a setup:
     * "always" rules on Again (wrong) and Hard / Good / Easy (right). Also used to move old configs over.
     */
    public static Setup fromFlat(String name, List<String> right, List<String> wrong, Pick pick) {
        GradeSetup again = wrong.isEmpty() ? none(-1) : grade(-1, Overlap.ALL, always("Wrong answer", pick, wrong.toArray(String[]::new)));
        GradeSetup hard = right.isEmpty() ? none(0) : grade(0, Overlap.ALL, always("Right answer", pick, right.toArray(String[]::new)));
        return new Setup(name, again, hard, copy(hard, 1), copy(hard, 2), Progression.DEFAULT_BANDS, 1.0, 0.0);
    }

    public static @Nullable Preset builtIn(String name) {
        for (Preset preset : BUILT_IN) if (preset.name().equals(name)) return preset;
        return null;
    }

    // ---------------------------------------------------------------- the player's own presets

    private static @Nullable List<Setup> userPresets = null;

    private static File file() {
        return FileManager.configFile(FILE_NAME);
    }

    /** @return the player's saved presets (each setup's {@code presetName} is its name) */
    public static List<Setup> userPresets() {
        if (userPresets == null) {
            List<Setup> loaded = new ArrayList<>();
            File file = file();
            if (file.exists()) {
                try {
                    List<Setup> fromFile = GSON.fromJson(Files.readString(file.toPath()), new TypeToken<List<Setup>>() {}.getType());
                    if (fromFile != null) loaded.addAll(fromFile);
                }
                catch (IOException | JsonParseException e) {
                    Flashcards.LOGGER.warn("Couldn't read {}", FILE_NAME, e);
                }
            }
            userPresets = loaded;
        }
        return List.copyOf(userPresets);
    }

    /** Saves (or replaces, by name) one of the player's presets. */
    public static void saveUserPreset(Setup setup) {
        List<Setup> list = new ArrayList<>(userPresets());
        list.removeIf(existing -> existing.presetName().equals(setup.presetName()));
        list.add(setup);
        list.sort((x, y) -> String.CASE_INSENSITIVE_ORDER.compare(x.presetName(), y.presetName()));
        store(list);
    }

    public static void deleteUserPreset(String name) {
        List<Setup> list = new ArrayList<>(userPresets());
        list.removeIf(existing -> existing.presetName().equals(name));
        store(list);
    }

    public static @Nullable Setup userPreset(String name) {
        for (Setup setup : userPresets()) if (setup.presetName().equals(name)) return setup;
        return null;
    }

    public static boolean isNameTaken(String name) {
        return builtIn(name) != null || userPreset(name) != null;
    }

    private static void store(List<Setup> list) {
        userPresets = list;
        FileManager.writeConfigAsync(FILE_NAME, GSON.toJson(list));
    }

    // ---------------------------------------------------------------- sharing as text

    /** The setup as text to paste anywhere (a line saying what it is, then JSON). */
    public static String toShareText(Setup setup) {
        return SHARE_HEADER + ": " + setup.presetName() + "\n" + GSON.toJson(setup);
    }

    /**
     * Reads a setup from shared text.
     * @return the setup, or null if the text isn't one
     */
    public static @Nullable Setup fromShareText(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end < start) return null;
        try {
            Setup setup = GSON.fromJson(text.substring(start, end + 1), Setup.class);
            if (setup == null) return null;
            // fresh rule ids, so a pasted preset never shares "once" state with an existing one
            Setup fresh = setup;
            for (int g = 1; g <= 4; g++) {
                List<Rule> rules = new ArrayList<>();
                for (Rule rule : fresh.grade(g).rules()) rules.add(rule.withId(Progression.newId()));
                fresh = fresh.withGrade(g, fresh.grade(g).withRules(rules));
            }
            return fresh.named(setup.presetName());
        }
        catch (JsonParseException | IllegalStateException e) {
            return null;
        }
    }
}
