package io.github.dennisochulor.flashcards.config;

import java.util.List;

/**
 * A ready-made set of reward and penalty commands. Picking one fills in the command boxes on the
 * Rewards &amp; Penalties screen; the player can then edit them freely (which turns the preset into "Custom").
 * Commands run as the player, at the player's position, without the leading slash.
 */
public record RewardPreset(String name, String description, ModConfig.CommandSelectionStrategy strategy,
                           List<String> correct, List<String> wrong) {

    /** Shown when the commands were written or edited by hand. Selecting it restores what the player typed. */
    public static final RewardPreset CUSTOM = new RewardPreset("Custom", "Your own commands.",
            ModConfig.CommandSelectionStrategy.EXECUTE_ALL, List.of(), List.of());

    public static final RewardPreset NONE = new RewardPreset("None", "No rewards and no penalties.",
            ModConfig.CommandSelectionStrategy.OFF, List.of(), List.of());

    public static final RewardPreset GENTLE = new RewardPreset("Gentle",
            "A little XP and a moment of regeneration for a right answer; a short slow-down for a wrong one.",
            ModConfig.CommandSelectionStrategy.EXECUTE_ALL,
            List.of("xp add @s 5 points",
                    "effect give @s minecraft:regeneration 5 0 true"),
            List.of("effect give @s minecraft:slowness 10 0 true"));

    public static final RewardPreset BALANCED = new RewardPreset("Balanced",
            "XP, food and a speed boost when you're right; hunger and slow mining when you're wrong.",
            ModConfig.CommandSelectionStrategy.EXECUTE_ALL,
            List.of("xp add @s 10 points",
                    "effect give @s minecraft:saturation 1 2 true",
                    "effect give @s minecraft:speed 30 0 true"),
            List.of("effect give @s minecraft:hunger 20 1 true",
                    "effect give @s minecraft:mining_fatigue 30 0 true"));

    public static final RewardPreset TOUGH = new RewardPreset("Tough",
            "Bigger XP and extra hearts when you're right; 3 hearts of damage, weakness and darkness when you're wrong.",
            ModConfig.CommandSelectionStrategy.EXECUTE_ALL,
            List.of("xp add @s 15 points",
                    "effect give @s minecraft:absorption 60 1 true"),
            List.of("damage @s 6 minecraft:magic",
                    "effect give @s minecraft:weakness 30 0 true",
                    "effect give @s minecraft:darkness 8 0 true"));

    public static final RewardPreset LEARN_OR_DIE = new RewardPreset("Learn or Die",
            "Right: XP, regeneration and resistance. Wrong: you die. Not recommended in Hardcore worlds.",
            ModConfig.CommandSelectionStrategy.EXECUTE_ALL,
            List.of("xp add @s 20 points",
                    "effect give @s minecraft:regeneration 10 1 true",
                    "effect give @s minecraft:resistance 30 0 true"),
            List.of("kill @s"));

    public static final RewardPreset LUCKY_DIP = new RewardPreset("Lucky Dip",
            "One random prize when you're right (ingots, a diamond, a golden apple...), one random mishap when you're wrong.",
            ModConfig.CommandSelectionStrategy.RANDOMISE_ONE,
            List.of("give @s minecraft:iron_ingot 3",
                    "give @s minecraft:gold_ingot 2",
                    "give @s minecraft:diamond 1",
                    "give @s minecraft:emerald 2",
                    "give @s minecraft:golden_apple 1",
                    "give @s minecraft:experience_bottle 4",
                    "xp add @s 25 points"),
            List.of("effect give @s minecraft:nausea 10 0 true",
                    "effect give @s minecraft:hunger 30 1 true",
                    "effect give @s minecraft:levitation 3 1 true",
                    "damage @s 4 minecraft:magic",
                    "summon minecraft:lightning_bolt ~ ~ ~",
                    "summon minecraft:zombie ~2 ~ ~"));

    /** In the order the preset button cycles through them. */
    public static final List<RewardPreset> ALL = List.of(CUSTOM, NONE, GENTLE, BALANCED, TOUGH, LEARN_OR_DIE, LUCKY_DIP);

    /** @return the preset with this name, or {@link #CUSTOM} if there's none (including the empty name) */
    public static RewardPreset byName(String name) {
        for (RewardPreset preset : ALL) if (preset.name.equals(name)) return preset;
        return CUSTOM;
    }

    /** Whether these commands and mode are exactly what this preset sets (i.e. the player hasn't changed anything). */
    public boolean matches(List<String> correct, List<String> wrong, ModConfig.CommandSelectionStrategy strategy) {
        return this != CUSTOM && this.strategy == strategy && this.correct.equals(correct) && this.wrong.equals(wrong);
    }
}
