package io.github.dennisochulor.flashcards.questions;

import com.mojang.brigadier.ParseResults;
import io.github.dennisochulor.flashcards.FileManager;
import io.github.dennisochulor.flashcards.Flashcards;
import io.github.dennisochulor.flashcards.ModStats;
import io.github.dennisochulor.flashcards.anki.StudyStats;
import io.github.dennisochulor.flashcards.config.DeckSettings;
import io.github.dennisochulor.flashcards.progress.ProgressStore;
import io.github.dennisochulor.flashcards.progress.Progression;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.CommonColors;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Shared side effects of answering a question: sound, stats, and the rewards & penalties: the answer moves the deck's
 * score and runs the rules that fire (see {@link Progression}).
 */
public final class AnswerOutcome {
    private AnswerOutcome() {}

    public static void playSoundAndRecordStats(boolean correct) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) player.playSound(correct ? SoundEvents.PLAYER_LEVELUP : SoundEvents.ANVIL_LAND);
        ModStats stats = FileManager.getStats();
        FileManager.updateStats(correct ? stats.incrementCorrect() : stats.incrementWrong());
        StudyStats.recordAnswer(correct);
    }

    /** For the mod's own question lists: right counts as Good, wrong as Again, and there's no "last seen" time. */
    public static void runCommands(boolean correct) {
        Progression.Result result = apply(correct ? 3 : 1, null, -1);
        showInActionBar(correct ? 3 : 1, result);
    }

    /** @return the key a deck's score is saved under (the mod's own questions share one) */
    public static String scoreKey(@Nullable String deck) {
        return deck == null || deck.isBlank() ? ProgressStore.OWN_QUESTIONS : deck;
    }

    /** Hours since a card was last seen, or -1 for a new or unknown card. */
    public static double hoursSince(long lastReviewMillis) {
        return lastReviewMillis > 0 ? Math.max(0, (System.currentTimeMillis() - lastReviewMillis) / 3_600_000.0) : -1;
    }

    /**
     * What answering with this grade would do, without doing it: the score change and the rules that would fire.
     * Shown on the card before the player picks a grade.
     */
    public static Progression.Result preview(int ease4, @Nullable String deck, long lastReviewMillis) {
        Progression.Setup setup = DeckSettings.forDeck(FileManager.getConfig(), deck).progression();
        String key = scoreKey(deck);
        return Progression.answer(setup, ProgressStore.get(key), ease4, hoursSince(lastReviewMillis), System.currentTimeMillis(),
                new java.util.Random(0));
    }

    /**
     * Applies an answer to the deck's score and runs the rules that fire (the deck's own rewards & penalties, its parent's,
     * or the main ones).
     * @param ease4            1 = Again ... 4 = Easy
     * @param lastReviewMillis when the card was last seen (Anki), or a negative number if new / unknown
     * @return what happened, for showing it
     */
    public static Progression.Result apply(int ease4, @Nullable String deck, long lastReviewMillis) {
        Minecraft minecraft = Minecraft.getInstance();
        Progression.Setup setup = DeckSettings.forDeck(FileManager.getConfig(), deck).progression();
        String key = scoreKey(deck);
        Progression.Result result = Progression.answer(setup, ProgressStore.get(key), ease4, hoursSince(lastReviewMillis),
                System.currentTimeMillis(), ThreadLocalRandom.current());
        ProgressStore.put(key, result.state());

        List<String> commands = new ArrayList<>();
        for (Progression.Fired fired : result.fired()) commands.addAll(fired.commands());
        LocalPlayer player = minecraft.player;
        ClientPacketListener connection = minecraft.getConnection();
        if (commands.isEmpty() || player == null || connection == null) return result;

        IntegratedServer server = minecraft.getSingleplayerServer();
        if (server != null) {
            runOnIntegratedServer(server, player.getUUID(), commands);
        }
        else { // multiplayer: the server decides whether the player may run them
            commands.forEach(command -> connection.sendCommand("execute as @s at @s run " + command));
        }
        return result;
    }

    /** "Score -4 → -5 · Weakened", or null when there's nothing worth saying (no score change and no rule fired). */
    public static @Nullable Component describe(Progression.Result result) {
        boolean changed = Math.abs(result.after() - result.before()) > 1e-9;
        List<String> names = result.fired().stream().map(Progression.Fired::name).filter(n -> !n.isBlank()).toList();
        if (!changed && names.isEmpty()) return null;
        MutableComponent text = Component.literal("Score ").withColor(0xFFAAAAAA)
                .append(Component.literal(Progression.formatScore(result.before())).withColor(CommonColors.WHITE))
                .append(Component.literal(" → ").withColor(0xFFAAAAAA))
                .append(Component.literal(Progression.formatScore(result.after())).withColor(result.after() < result.before() ? 0xFFFF7070
                        : result.after() > result.before() ? 0xFF5FD07A : CommonColors.WHITE));
        if (!names.isEmpty()) text.append(Component.literal("  ·  " + String.join(", ", names)).withColor(CommonColors.YELLOW));
        return text;
    }

    /** Puts {@link #describe} above the hotbar, if the player wants to see the score. */
    public static void showInActionBar(int ease4, Progression.Result result) {
        LocalPlayer player = Minecraft.getInstance().player;
        Component text = describe(result);
        if (player != null && text != null && FileManager.getConfig().scoreDisplay().card()) player.sendOverlayMessage(text);
    }

    /**
     * Singleplayer (and LAN host): the world runs inside this game, so the commands are executed by the world itself,
     * on its own thread. That works whether or not the world has cheats enabled, and doesn't spam the chat.
     * A command that can't be understood is reported to the player instead of failing silently.
     */
    private static void runOnIntegratedServer(IntegratedServer server, UUID playerId, List<String> commands) {
        server.execute(() -> {
            CommandSourceStack source = server.createCommandSourceStack().withSuppressedOutput();
            for (String command : commands) {
                String full = "execute as " + playerId + " at @s run " + command;
                try {
                    ParseResults<CommandSourceStack> parsed = server.getCommands().getDispatcher().parse(full, source);
                    if (parsed.getReader().canRead() || !parsed.getExceptions().isEmpty()) {
                        reportBadCommand(command);
                        continue;
                    }
                    server.getCommands().performCommand(parsed, full);
                }
                catch (RuntimeException e) {
                    Flashcards.LOGGER.warn("Reward/penalty command failed: {}", command, e);
                    reportBadCommand(command);
                }
            }
        });
    }

    private static void reportBadCommand(String command) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            if (minecraft.player != null) {
                minecraft.player.sendSystemMessage(Component.literal("Flashcards: this command isn't valid, check Rewards & Penalties: /" + command)
                        .withColor(CommonColors.SOFT_RED));
            }
        });
    }
}
