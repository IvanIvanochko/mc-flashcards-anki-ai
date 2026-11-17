package io.github.dennisochulor.flashcards.progress;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * Rewards and penalties that escalate with a per-deck score.
 * <p>
 * Every answer changes the deck's score by its grade's points (times a factor for how long ago the card was last seen),
 * then runs the rules of that grade whose condition holds: "score at most -5", "every 10th Good", "always"...
 * Whether a grade rewards or punishes is only a matter of the commands its rules run.
 * <p>
 * Pure logic, no Minecraft: {@link #answer} takes a setup and a deck's state and returns the new state plus the
 * commands to run, so it can be previewed (what would Again do now?) and tested on its own.
 */
public final class Progression {
    private Progression() {}

    public static final String[] GRADE_KEYS = {"again", "hard", "good", "easy"};
    public static final String[] GRADE_NAMES = {"Again", "Hard", "Good", "Easy"};

    // ---------------------------------------------------------------- setup (saved in the config)

    public enum Condition {
        ALWAYS("Always", "every time you give this grade"),
        SCORE_AT_LEAST("Score at least", "when the score is at least A"),
        SCORE_AT_MOST("Score at most", "when the score is at most A"),
        SCORE_BETWEEN("Score between", "when the score is between A and B"),
        GIVEN_TIMES("Given N times", "when this grade has been given A times"),
        EVERY_NTH("Every Nth time", "every A-th time this grade is given");

        public final String friendlyName;
        public final String explanation;

        Condition(String friendlyName, String explanation) {
            this.friendlyName = friendlyName;
            this.explanation = explanation;
        }

        public boolean usesScore() {
            return this == SCORE_AT_LEAST || this == SCORE_AT_MOST || this == SCORE_BETWEEN;
        }

        public boolean usesCount() {
            return this == GIVEN_TIMES || this == EVERY_NTH;
        }
    }

    public enum Trigger {
        ONCE("Once", "the first time it becomes true (again after it stops being true)"),
        EVERY_TIME("Every time", "on every answer while it's true");

        public final String friendlyName;
        public final String explanation;

        Trigger(String friendlyName, String explanation) {
            this.friendlyName = friendlyName;
            this.explanation = explanation;
        }
    }

    /** How a rule's command lines are used. */
    public enum Pick {
        ALL("Run all lines"), ONE_RANDOM("One line at random");

        public final String friendlyName;

        Pick(String friendlyName) {
            this.friendlyName = friendlyName;
        }
    }

    /** When several of a grade's rules hold at once. */
    public enum Overlap {
        ALL("All that match"), STRONGEST("Only the strongest");

        public final String friendlyName;

        Overlap(String friendlyName) {
            this.friendlyName = friendlyName;
        }
    }

    /**
     * One rule of a grade.
     * @param a            the threshold (score) or N (count conditions); unused for ALWAYS
     * @param b            the upper end for SCORE_BETWEEN
     * @param resetTo      if set, the score becomes this after the rule fires
     * @param resetCount   if true, this grade's count goes back to 0 after the rule fires
     */
    public record Rule(String id, String name, Condition condition, Double a, Double b, List<String> commands,
                       Pick pick, Trigger trigger, @Nullable Double resetTo, Boolean resetCount) {
        @SuppressWarnings("ConstantValue")
        public Rule {
            if (id == null || id.isBlank()) id = newId();
            if (name == null) name = "";
            if (condition == null) condition = Condition.ALWAYS;
            if (a == null) a = 0.0;
            if (b == null) b = a;
            commands = commands == null ? List.of() : commands.stream().map(String::strip).filter(c -> !c.isEmpty())
                    .map(c -> c.startsWith("/") ? c.substring(1) : c).toList();
            if (pick == null) pick = Pick.ALL;
            if (trigger == null) trigger = Trigger.ONCE;
            if (resetCount == null) resetCount = false;
        }

        public static Rule of(String name, Condition condition, double a, double b, Trigger trigger, @Nullable Double resetTo,
                              boolean resetCount, Pick pick, String... commands) {
            return new Rule(null, name, condition, a, b, List.of(commands), pick, trigger, resetTo, resetCount);
        }

        public Rule withId(String newId) {
            return new Rule(newId, name, condition, a, b, commands, pick, trigger, resetTo, resetCount);
        }

        /** How "big" a rule is, for "only the strongest": the furthest score threshold, or the largest count. */
        double strength() {
            return switch (condition) {
                case ALWAYS -> Double.NEGATIVE_INFINITY;
                case SCORE_AT_LEAST -> a;
                case SCORE_AT_MOST -> -a;
                case SCORE_BETWEEN -> Math.max(Math.abs(a), Math.abs(b));
                case GIVEN_TIMES, EVERY_NTH -> a;
            };
        }
    }

    /** @param points how much this grade changes the score (before the time multiplier) */
    public record GradeSetup(Double points, Overlap overlap, List<Rule> rules) {
        @SuppressWarnings("ConstantValue")
        public GradeSetup {
            if (points == null) points = 0.0;
            if (overlap == null) overlap = Overlap.ALL;
            rules = rules == null ? List.of() : List.copyOf(rules);
        }

        public GradeSetup withPoints(double newPoints) {
            return new GradeSetup(newPoints, overlap, rules);
        }

        public GradeSetup withOverlap(Overlap newOverlap) {
            return new GradeSetup(points, newOverlap, rules);
        }

        public GradeSetup withRules(List<Rule> newRules) {
            return new GradeSetup(points, overlap, newRules);
        }
    }

    /**
     * Points are multiplied by how long ago the card was last seen.
     * @param upToHours the band covers cards last seen less than this many hours ago; null = longer than the band before
     * @param gain      multiplier when the points are positive
     * @param loss      multiplier when the points are negative
     */
    public record TimeBand(@Nullable Double upToHours, Double gain, Double loss) {
        @SuppressWarnings("ConstantValue")
        public TimeBand {
            if (gain == null) gain = 1.0;
            if (loss == null) loss = 1.0;
        }
    }

    public static final List<TimeBand> DEFAULT_BANDS = List.of(
            new TimeBand(1.0, 0.5, 0.5),    // under an hour: still learning
            new TimeBand(24.0, 1.0, 1.0),   // under a day
            new TimeBand(168.0, 1.5, 1.5),  // under a week
            new TimeBand(null, 2.0, 2.0));  // longer: remembering it is worth more, forgetting it costs more

    /**
     * A whole rewards & penalties setup (the main one, a deck's own, or a preset).
     * @param presetName       the preset this came from, or "" when edited
     * @param bands            time multipliers, by how long ago the card was last seen (ascending, the last one open-ended)
     * @param newCardMultiplier multiplier for a card never seen before
     * @param fadeHalfLifeHours the score and counts halve toward 0 every this many hours without answers; 0 = no fading
     */
    public record Setup(String presetName, GradeSetup again, GradeSetup hard, GradeSetup good, GradeSetup easy,
                        List<TimeBand> bands, Double newCardMultiplier, Double fadeHalfLifeHours) {
        @SuppressWarnings("ConstantValue")
        public Setup {
            if (presetName == null) presetName = "";
            if (again == null) again = new GradeSetup(-1.0, null, null);
            if (hard == null) hard = new GradeSetup(0.0, null, null);
            if (good == null) good = new GradeSetup(1.0, null, null);
            if (easy == null) easy = new GradeSetup(2.0, null, null);
            bands = bands == null || bands.isEmpty() ? DEFAULT_BANDS : List.copyOf(bands);
            if (newCardMultiplier == null) newCardMultiplier = 1.0;
            if (fadeHalfLifeHours == null || fadeHalfLifeHours < 0) fadeHalfLifeHours = 0.0;
        }

        public static Setup empty() {
            return new Setup("Off", null, null, null, null, null, null, 0.0);
        }

        /** @param ease4 1 = Again ... 4 = Easy */
        public GradeSetup grade(int ease4) {
            return switch (ease4) {
                case 1 -> again;
                case 2 -> hard;
                case 3 -> good;
                default -> easy;
            };
        }

        public Setup withGrade(int ease4, GradeSetup grade) {
            return new Setup("", ease4 == 1 ? grade : again, ease4 == 2 ? grade : hard, ease4 == 3 ? grade : good,
                    ease4 == 4 ? grade : easy, bands, newCardMultiplier, fadeHalfLifeHours);
        }

        public Setup withTiming(List<TimeBand> newBands, double newCardMultiplier) {
            return new Setup("", again, hard, good, easy, newBands, newCardMultiplier, fadeHalfLifeHours);
        }

        public Setup withFade(double halfLifeHours) {
            return new Setup("", again, hard, good, easy, bands, newCardMultiplier, halfLifeHours);
        }

        public Setup named(String name) {
            return new Setup(name, again, hard, good, easy, bands, newCardMultiplier, fadeHalfLifeHours);
        }

        /** Whether any grade has any rule. */
        public boolean hasRules() {
            return !(again.rules().isEmpty() && hard.rules().isEmpty() && good.rules().isEmpty() && easy.rules().isEmpty());
        }

        public List<Rule> allRules() {
            List<Rule> all = new ArrayList<>();
            for (int g = 1; g <= 4; g++) all.addAll(grade(g).rules());
            return all;
        }
    }

    // ---------------------------------------------------------------- a deck's state (saved in progress.json)

    /**
     * @param counts          how many times each grade was given (fades like the score), by {@link #GRADE_KEYS}
     * @param latched         ids of ONCE rules that have fired and whose condition still holds (they re-arm when it stops)
     * @param lastUpdateMillis when the score last changed, for fading
     */
    public record DeckState(Double score, Map<String, Double> counts, List<String> latched, Long lastUpdateMillis) {
        @SuppressWarnings("ConstantValue")
        public DeckState {
            if (score == null) score = 0.0;
            counts = counts == null ? Map.of() : Map.copyOf(counts);
            latched = latched == null ? List.of() : List.copyOf(latched);
            if (lastUpdateMillis == null) lastUpdateMillis = 0L;
        }

        public static DeckState fresh() {
            return new DeckState(0.0, Map.of(), List.of(), 0L);
        }

        public double count(int ease4) {
            return counts.getOrDefault(GRADE_KEYS[ease4 - 1], 0.0);
        }

        /** This state as it would be now, with fading applied (for showing the score; nothing is saved). */
        public DeckState fadedTo(long nowMillis, double halfLifeHours) {
            double factor = fadeFactor(lastUpdateMillis, nowMillis, halfLifeHours);
            if (factor == 1) return this;
            Map<String, Double> faded = new HashMap<>();
            counts.forEach((k, v) -> faded.put(k, v * factor));
            return new DeckState(score * factor, faded, latched, lastUpdateMillis);
        }
    }

    /** A rule that fires: its name and the command lines to run. */
    public record Fired(String grade, String name, List<String> commands) {}

    /**
     * What an answer did.
     * @param multiplier the time multiplier that was applied to the points
     * @param change     the score change from the points (before any rule's reset)
     */
    public record Result(DeckState state, double before, double after, double change, double multiplier, List<Fired> fired) {}

    static double fadeFactor(long fromMillis, long toMillis, double halfLifeHours) {
        if (halfLifeHours <= 0 || fromMillis <= 0 || toMillis <= fromMillis) return 1;
        double hours = (toMillis - fromMillis) / 3_600_000.0;
        return Math.pow(0.5, hours / halfLifeHours);
    }

    /** @param hoursSinceLastReview hours since the card was last seen, or a negative number for a new (or unknown) card */
    public static double multiplier(Setup setup, double hoursSinceLastReview, boolean gain) {
        if (hoursSinceLastReview < 0) return setup.newCardMultiplier();
        TimeBand band = setup.bands().getLast();
        for (TimeBand candidate : setup.bands()) {
            if (candidate.upToHours() == null || hoursSinceLastReview < candidate.upToHours()) {
                band = candidate;
                break;
            }
        }
        return gain ? band.gain() : band.loss();
    }

    /** Whether a score condition holds (count conditions are handled in {@link #answer}). */
    static boolean scoreHolds(Rule rule, double score) {
        return switch (rule.condition()) {
            case SCORE_AT_LEAST -> score >= rule.a() - 1e-9;
            case SCORE_AT_MOST -> score <= rule.a() + 1e-9;
            case SCORE_BETWEEN -> score >= Math.min(rule.a(), rule.b()) - 1e-9 && score <= Math.max(rule.a(), rule.b()) + 1e-9;
            default -> false;
        };
    }

    /**
     * Applies one answer.
     * @param ease4                1 = Again ... 4 = Easy
     * @param hoursSinceLastReview hours since the card was last seen, negative for a new or unknown card
     * @param random               used for rules that run one line at random
     */
    public static Result answer(Setup setup, DeckState stored, int ease4, double hoursSinceLastReview, long nowMillis,
                                RandomGenerator random) {
        // 1. fading since the last answer
        DeckState state = stored.fadedTo(nowMillis, setup.fadeHalfLifeHours());
        double before = state.score();

        // 2. the grade's points, times how long ago the card was last seen
        GradeSetup grade = setup.grade(ease4);
        double points = grade.points();
        double multiplier = points == 0 ? 1 : multiplier(setup, hoursSinceLastReview, points > 0);
        double change = points * multiplier;
        double score = before + change;

        String key = GRADE_KEYS[ease4 - 1];
        Map<String, Double> counts = new HashMap<>(state.counts());
        double countBefore = counts.getOrDefault(key, 0.0);
        double countAfter = countBefore + 1;
        counts.put(key, countAfter);
        Set<String> latched = new HashSet<>(state.latched());

        // 3. score rules of the other grades re-arm when their condition no longer holds
        for (int g = 1; g <= 4; g++) {
            if (g == ease4) continue;
            for (Rule rule : setup.grade(g).rules()) {
                if (rule.condition().usesScore() && !scoreHolds(rule, score)) latched.remove(rule.id());
            }
        }

        // 4. this grade's rules
        List<Rule> firing = new ArrayList<>();
        for (Rule rule : grade.rules()) {
            boolean holds;
            boolean fire;
            switch (rule.condition()) {
                case ALWAYS -> { holds = true; fire = true; }
                case EVERY_NTH -> {
                    int n = Math.max(1, (int) Math.round(rule.a()));
                    int crossedTo = (int) Math.floor(countAfter + 1e-9);
                    holds = crossedTo > (int) Math.floor(countBefore + 1e-9) && crossedTo % n == 0;
                    fire = holds;
                }
                case GIVEN_TIMES -> {
                    holds = countAfter >= rule.a() - 1e-9;
                    fire = holds && (rule.trigger() == Trigger.EVERY_TIME || !latched.contains(rule.id()));
                    if (holds && rule.trigger() == Trigger.ONCE) latched.add(rule.id());
                    if (!holds) latched.remove(rule.id());
                }
                default -> { // score conditions
                    holds = scoreHolds(rule, score);
                    fire = holds && (rule.trigger() == Trigger.EVERY_TIME || !latched.contains(rule.id()));
                    if (holds && rule.trigger() == Trigger.ONCE) latched.add(rule.id());
                    if (!holds) latched.remove(rule.id());
                }
            }
            if (fire) firing.add(rule);
        }
        if (grade.overlap() == Overlap.STRONGEST && firing.size() > 1) {
            Rule strongest = firing.getFirst();
            for (Rule rule : firing) if (rule.strength() >= strongest.strength()) strongest = rule; // ties: the later one
            for (Rule rule : firing) if (rule != strongest) latched.remove(rule.id()); // didn't fire, so stays armed
            firing = List.of(strongest);
        }

        // 5. commands and resets
        List<Fired> fired = new ArrayList<>();
        for (Rule rule : firing) {
            List<String> commands = rule.commands();
            if (rule.pick() == Pick.ONE_RANDOM && commands.size() > 1) commands = List.of(commands.get(random.nextInt(commands.size())));
            fired.add(new Fired(GRADE_NAMES[ease4 - 1], rule.name(), commands));
            if (rule.resetTo() != null) score = rule.resetTo();
            if (rule.resetCount()) counts.put(key, 0.0);
        }
        if (firing.stream().anyMatch(r -> r.resetTo() != null)) {
            // after a reset, score rules that no longer hold re-arm right away
            for (Rule rule : setup.allRules()) if (rule.condition().usesScore() && !scoreHolds(rule, score)) latched.remove(rule.id());
        }
        if (firing.stream().anyMatch(Rule::resetCount)) {
            for (Rule rule : grade.rules()) if (rule.condition() == Condition.GIVEN_TIMES) latched.remove(rule.id());
        }

        double rounded = Math.round(score * 100) / 100.0;
        DeckState next = new DeckState(rounded, counts, new ArrayList<>(latched), nowMillis);
        return new Result(next, Math.round(before * 100) / 100.0, rounded, change, multiplier, fired);
    }

    public static String newId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    // ---------------------------------------------------------------- words

    /** e.g. "-4", "+2.5", "0" */
    public static String formatScore(double score) {
        double rounded = Math.round(score * 10) / 10.0;
        String text = rounded == Math.rint(rounded) ? String.valueOf((long) rounded) : String.valueOf(rounded);
        return rounded > 0 ? "+" + text : text;
    }

    /** A rule in a few words, e.g. "score ≤ -5", "every 10th", "always". */
    public static String describeCondition(Rule rule) {
        return switch (rule.condition()) {
            case ALWAYS -> "always";
            case SCORE_AT_LEAST -> "score ≥ " + formatScore(rule.a());
            case SCORE_AT_MOST -> "score ≤ " + formatScore(rule.a());
            case SCORE_BETWEEN -> "score " + formatScore(Math.min(rule.a(), rule.b())) + " to " + formatScore(Math.max(rule.a(), rule.b()));
            case GIVEN_TIMES -> "given " + Math.round(rule.a()) + "×";
            case EVERY_NTH -> "every " + ordinal((int) Math.round(rule.a()));
        };
    }

    public static String ordinal(int n) {
        int mod100 = n % 100;
        String suffix = mod100 >= 11 && mod100 <= 13 ? "th" : switch (n % 10) { case 1 -> "st"; case 2 -> "nd"; case 3 -> "rd"; default -> "th"; };
        return n + suffix;
    }
}
