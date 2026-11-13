package io.github.dennisochulor.flashcards.questions;

import com.mojang.brigadier.ParseResults;
import io.github.dennisochulor.flashcards.FileManager;
import io.github.dennisochulor.flashcards.Flashcards;
import io.github.dennisochulor.flashcards.ModStats;
import io.github.dennisochulor.flashcards.anki.StudyStats;
import io.github.dennisochulor.flashcards.config.DeckSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.CommonColors;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/** Shared side effects of answering a question: sound, stats and the configured reward/penalty commands. */
public final class AnswerOutcome {
    private AnswerOutcome() {}

    public static void playSoundAndRecordStats(boolean correct) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) player.playSound(correct ? SoundEvents.PLAYER_LEVELUP : SoundEvents.ANVIL_LAND);
        ModStats stats = FileManager.getStats();
        FileManager.updateStats(correct ? stats.incrementCorrect() : stats.incrementWrong());
        StudyStats.recordAnswer(correct);
    }

    /** Runs the main reward (right answer) or penalty (wrong answer) commands. For the mod's own question lists. */
    public static void runCommands(boolean correct) {
        runCommands(correct, null);
    }

    /**
     * Runs the reward (right answer) or penalty (wrong answer) commands that apply to this deck:
     * the deck's own if its preset has them, otherwise the main ones.
     */
    public static void runCommands(boolean correct, @Nullable String deck) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientPacketListener connection = minecraft.getConnection();
        if (player == null || connection == null) return;

        DeckSettings settings = DeckSettings.forDeck(FileManager.getConfig(), deck);
        List<String> commands = correct ? settings.correctCommands() : settings.wrongCommands();
        if (commands.isEmpty()) return;

        List<String> toRun = switch (settings.strategy()) {
            case EXECUTE_ALL -> commands;
            case RANDOMISE_ONE -> List.of(commands.get(player.getRandom().nextInt(commands.size())));
            case OFF -> List.of();
        };
        if (toRun.isEmpty()) return;

        IntegratedServer server = minecraft.getSingleplayerServer();
        if (server != null) {
            runOnIntegratedServer(server, player.getUUID(), toRun);
        }
        else { // multiplayer: the server decides whether the player may run them
            toRun.forEach(command -> connection.sendCommand("execute as @s at @s run " + command));
        }
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
