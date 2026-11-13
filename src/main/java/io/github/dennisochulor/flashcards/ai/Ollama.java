package io.github.dennisochulor.flashcards.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.dennisochulor.flashcards.config.ModConfig;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal blocking client for a local Ollama server (https://ollama.com).
 * All methods do network I/O and must NOT be called on the render thread.
 */
public final class Ollama {
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    /** A warm-up keeps the model loaded at least this long, so it's still there when the player finishes typing. */
    private static final int WARM_UP_KEEP_MINUTES = 10;
    /** Enough for the checklist, the grade and a few sentences of feedback. */
    private static final int MAX_REPLY_TOKENS = 500;
    private static final int CONTEXT_TOKENS = 4096;
    /** Models that turned out not to accept the "think" switch. */
    private static final Set<String> NO_THINK_PARAMETER = ConcurrentHashMap.newKeySet();

    private final String baseUrl;

    public Ollama(String baseUrl) {
        String url = baseUrl.strip();
        this.baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** Thrown for anything that prevents grading; the message is shown to the player. */
    public static final class OllamaException extends Exception {
        public OllamaException(String message) { super(message); }
        public OllamaException(String message, Throwable cause) { super(message, cause); }
    }

    /** @param status one of {@link GradingPrompt#CORRECT}, PARTIAL, MISSING, WRONG */
    public record KeyPoint(String point, String status) {}

    /**
     * @param score     0 (wrong) to 1 (fully correct), inside the band of {@code ease}
     * @param ease      the AI's grade as an Anki ease: 1 = Again ... 4 = Easy
     * @param vague     the answer is too general to judge
     * @param vagueHint what to be more specific about (doesn't give the answer away)
     */
    public record Grade(double score, int ease, String feedback, boolean vague, String vagueHint, List<KeyPoint> keyPoints) {}

    /** An installed model. @param fileBytes its size on disk, which is close to the RAM it needs */
    public record ModelInfo(String name, long fileBytes) {}

    /**
     * A model that is in memory right now.
     * @param bytes        RAM it takes in total
     * @param gpuBytes     the part of that held by the graphics card (equal to {@code bytes} when fully on the GPU)
     * @param expiresMillis when Ollama will unload it (epoch milliseconds), or 0 if unknown
     */
    public record LoadedModel(String name, long bytes, long gpuBytes, long expiresMillis) {}

    /** @return the installed models, e.g. "qwen2.5:7b", sorted by name */
    public List<ModelInfo> models() throws OllamaException {
        JsonObject json = send(HttpRequest.newBuilder(uri("/api/tags")).timeout(Duration.ofSeconds(5)).GET().build(), "list models");
        List<ModelInfo> list = new ArrayList<>();
        JsonArray models = json.getAsJsonArray("models");
        if (models != null) models.forEach(m -> {
            JsonObject o = m.getAsJsonObject();
            list.add(new ModelInfo(o.get("name").getAsString(), o.has("size") ? o.get("size").getAsLong() : 0));
        });
        list.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.name(), b.name()));
        return list;
    }

    /** @return the models Ollama currently holds in memory (usually none or one) */
    public List<LoadedModel> loaded() throws OllamaException {
        JsonObject json = send(HttpRequest.newBuilder(uri("/api/ps")).timeout(Duration.ofSeconds(5)).GET().build(), "check memory");
        List<LoadedModel> list = new ArrayList<>();
        JsonArray models = json.getAsJsonArray("models");
        if (models != null) models.forEach(m -> {
            JsonObject o = m.getAsJsonObject();
            long bytes = o.has("size") ? o.get("size").getAsLong() : 0;
            long gpu = o.has("size_vram") ? o.get("size_vram").getAsLong() : 0;
            long expires = 0;
            try {
                if (o.has("expires_at")) expires = OffsetDateTime.parse(o.get("expires_at").getAsString()).toInstant().toEpochMilli();
            }
            catch (RuntimeException ignored) {
                // an unexpected date format only costs the "unloads in" countdown
            }
            list.add(new LoadedModel(o.get("name").getAsString(), bytes, gpu, expires));
        });
        return list;
    }

    /**
     * Gets everything ready while the player is still typing: loads the model and has it read the grading
     * instructions, the question and the card's answer. Ollama keeps what it has already read (as long as the next
     * request starts with exactly the same text), so when the player's answer arrives the model only has to read
     * that answer before it starts writing the grade. The heavy reading happens while the card is still on screen.
     */
    public void warmUp(ModConfig.AiGrading ai, String systemPrompt, String question, String reference) throws OllamaException {
        // stays loaded at least long enough for the player to type, whatever "keep loaded" says
        int keepMinutes = Math.max(WARM_UP_KEEP_MINUTES, ai.performance().keepAliveMinutes());
        JsonObject body = chatBody(ai, systemPrompt, GradingPrompt.userPrefix(question, reference) + "(not answered yet)", 1, keepMinutes);
        chat(body, Duration.ofSeconds(120), "load " + ai.model());
    }

    /** @param systemPrompt the complete grading instructions, see {@code DeckSettings.systemPrompt()} */
    public Grade grade(ModConfig.AiGrading ai, String systemPrompt, String question, String reference, String answer) throws OllamaException {
        // the last request decides when the model is unloaded: "keep loaded" counts from this grade
        JsonObject body = chatBody(ai, systemPrompt, GradingPrompt.user(question, reference, answer), MAX_REPLY_TOKENS, ai.performance().keepAliveMinutes());
        body.add("format", GradingPrompt.schema()); // structured output: Ollama forces the reply into this JSON shape

        JsonObject json = chat(body, Duration.ofSeconds(180), "grade the answer");
        JsonObject message = json.getAsJsonObject("message");
        if (message == null || !message.has("content")) throw new OllamaException("Ollama sent an empty reply.");
        return GradingPrompt.parse(message.get("content").getAsString(), ai);
    }

    /**
     * A plain (not JSON-shaped) answer to one question, used for writing deck notes.
     * @return the model's reply text
     */
    public String complete(ModConfig.AiGrading ai, String systemPrompt, String userMessage, int maxTokens) throws OllamaException {
        JsonObject body = chatBody(ai, systemPrompt, userMessage, maxTokens, Math.max(WARM_UP_KEEP_MINUTES, ai.performance().keepAliveMinutes()));
        body.getAsJsonObject("options").addProperty("temperature", 0.3); // a little freedom reads better than the grader's 0
        JsonObject json = chat(body, Duration.ofSeconds(180), "write the notes");
        JsonObject message = json.getAsJsonObject("message");
        if (message == null || !message.has("content")) throw new OllamaException("Ollama sent an empty reply.");
        return message.get("content").getAsString();
    }

    /** The request shared by warm-up and grading. Everything but the end of the user message must be identical in both. */
    private static JsonObject chatBody(ModConfig.AiGrading ai, String systemPrompt, String userMessage, int maxTokens, int keepMinutes) {
        JsonObject body = new JsonObject();
        body.addProperty("model", ai.model());
        body.addProperty("stream", false);
        body.addProperty("keep_alive", keepMinutes + "m");
        if (!NO_THINK_PARAMETER.contains(ai.model())) {
            // "Reasoning" models (qwen3, deepseek-r1...) would think out loud for many seconds first; the checklist
            // in the reply already makes the model reason, so skip that phase.
            body.addProperty("think", false);
        }

        JsonArray messages = new JsonArray();
        messages.add(message("system", systemPrompt));
        messages.add(message("user", userMessage));
        body.add("messages", messages);

        JsonObject options = new JsonObject();
        options.addProperty("temperature", 0); // the same answer should get the same grade
        // Fixed working memory: enough for the instructions, a long card and the reply, and no more. A bigger window
        // only costs RAM (which the game needs too), and keeping it constant stops Ollama reloading the model.
        options.addProperty("num_ctx", CONTEXT_TOKENS);
        if (ai.performance().threads() > 0) options.addProperty("num_thread", ai.performance().threads()); // leave CPU cores for the game
        // A grade is short; never let a confused model ramble on. A model that can't switch its thinking off
        // spends tokens on that first, so it gets more room.
        options.addProperty("num_predict", NO_THINK_PARAMETER.contains(ai.model()) ? maxTokens * 4 : maxTokens);
        body.add("options", options);
        return body;
    }

    /** Sends a chat request; if this model/Ollama version rejects the "think" switch, remembers that and retries without it. */
    private JsonObject chat(JsonObject body, Duration timeout, String what) throws OllamaException {
        try {
            return send(post("/api/chat", body, timeout), what);
        }
        catch (OllamaException e) {
            String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
            if (!body.has("think") || !message.contains("think")) throw e;
            NO_THINK_PARAMETER.add(body.get("model").getAsString());
            body.remove("think");
            return send(post("/api/chat", body, timeout), what);
        }
    }

    private static JsonObject message(String role, String content) {
        JsonObject o = new JsonObject();
        o.addProperty("role", role);
        o.addProperty("content", content);
        return o;
    }

    private URI uri(String path) throws OllamaException {
        try {
            return URI.create(baseUrl + path);
        }
        catch (IllegalArgumentException e) {
            throw new OllamaException("Invalid Ollama address: " + baseUrl, e);
        }
    }

    private HttpRequest post(String path, JsonObject body, Duration timeout) throws OllamaException {
        return HttpRequest.newBuilder(uri(path))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
    }

    private static JsonObject send(HttpRequest request, String what) throws OllamaException {
        HttpResponse<String> response;
        try {
            response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        }
        catch (ConnectException | HttpConnectTimeoutException e) {
            throw new OllamaException("Can't reach Ollama. Is the Ollama app running?", e);
        }
        catch (HttpTimeoutException e) {
            throw new OllamaException("Ollama took too long to " + what + ".", e);
        }
        catch (IOException e) {
            throw new OllamaException("Couldn't talk to Ollama: " + e.getMessage(), e);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OllamaException("Interrupted while talking to Ollama.", e);
        }

        JsonObject json = null;
        try {
            JsonElement parsed = JsonParser.parseString(response.body());
            if (parsed.isJsonObject()) json = parsed.getAsJsonObject();
        }
        catch (RuntimeException ignored) {
            // handled below
        }
        @Nullable String error = json != null && json.has("error") ? json.get("error").getAsString() : null;
        if (response.statusCode() != 200 || error != null) {
            if (error != null && error.contains("not found")) {
                throw new OllamaException("Ollama: " + error + ". Install it with \"ollama pull <model>\" or pick another model.");
            }
            throw new OllamaException("Ollama couldn't " + what + ": " + (error != null ? error : "HTTP " + response.statusCode()));
        }
        if (json == null) throw new OllamaException("Ollama sent an unexpected response.");
        return json;
    }
}
