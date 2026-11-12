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
    /** Keep the model in memory between cards so grading doesn't wait for it to load each time. */
    private static final String KEEP_ALIVE = "30m";
    /** Enough for the checklist, the grade and a few sentences of feedback. */
    private static final int MAX_REPLY_TOKENS = 600;
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

    /** @return the names of the installed models, e.g. "qwen2.5:7b" */
    public List<String> models() throws OllamaException {
        JsonObject json = send(HttpRequest.newBuilder(uri("/api/tags")).timeout(Duration.ofSeconds(5)).GET().build(), "list models");
        List<String> names = new ArrayList<>();
        JsonArray models = json.getAsJsonArray("models");
        if (models != null) models.forEach(m -> names.add(m.getAsJsonObject().get("name").getAsString()));
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    /**
     * Gets the model ready while the player is still typing: loads it into memory and has it read the grading
     * instructions once. Ollama keeps what it has already read, so when the real answer arrives it only has to read
     * the card and the answer. That moves the heaviest burst of work to before the player is back in the game.
     */
    public void warmUp(ModConfig.AiGrading ai) throws OllamaException {
        JsonObject body = chatBody(ai, "(warming up, reply with OK)", 1);
        chat(body, Duration.ofSeconds(120), "load " + ai.model());
    }

    public Grade grade(ModConfig.AiGrading ai, String question, String reference, String answer) throws OllamaException {
        JsonObject body = chatBody(ai, GradingPrompt.user(question, reference, answer), MAX_REPLY_TOKENS);
        body.add("format", GradingPrompt.schema()); // structured output: Ollama forces the reply into this JSON shape

        JsonObject json = chat(body, Duration.ofSeconds(180), "grade the answer");
        JsonObject message = json.getAsJsonObject("message");
        if (message == null || !message.has("content")) throw new OllamaException("Ollama sent an empty reply.");
        return GradingPrompt.parse(message.get("content").getAsString(), ai);
    }

    /** The request shared by warm-up and grading. The system message must be identical in both for Ollama to reuse it. */
    private static JsonObject chatBody(ModConfig.AiGrading ai, String userMessage, int maxTokens) {
        JsonObject body = new JsonObject();
        body.addProperty("model", ai.model());
        body.addProperty("stream", false);
        body.addProperty("keep_alive", KEEP_ALIVE);
        if (!NO_THINK_PARAMETER.contains(ai.model())) {
            // "Reasoning" models (qwen3, deepseek-r1...) would think out loud for many seconds first; the checklist
            // in the reply already makes the model reason, so skip that phase.
            body.addProperty("think", false);
        }

        JsonArray messages = new JsonArray();
        messages.add(message("system", GradingPrompt.system(ai)));
        messages.add(message("user", userMessage));
        body.add("messages", messages);

        JsonObject options = new JsonObject();
        options.addProperty("temperature", 0); // the same answer should get the same grade
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
