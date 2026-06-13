package com.cy.diakritis.engine.judge;

import com.cy.diakritis.common.dto.ActionEvent;
import com.cy.diakritis.common.dto.Agreement;
import com.cy.diakritis.common.dto.EngineVerdict;
import com.cy.diakritis.common.dto.Signal;
import com.cy.diakritis.common.dto.Verdict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The live AI co-judge: an Ollama-backed local Gemma model giving a second, independent fraud-risk
 * opinion (SDD §9.4). It runs alongside the deterministic engine, never inside it — the engine
 * decision is authoritative and is never moved by this opinion (the combine rule, §8.3, can only ever
 * add one capped notch of friction on a confident stricter divergence).
 *
 * <p><b>Resilience over happy-path.</b> A local LLM is a best-effort, time-boxed advisor. Any
 * timeout, transport error, non-200, missing key, bad decision enum or unparseable body yields
 * {@link Opinion#unavailable()} so the engine proceeds unaffected. The {@code UNAVAILABLE} path is a
 * first-class, expected outcome — not a failure mode to hide.
 *
 * <p><b>Hard time budget.</b> The HTTP exchange is bounded by {@code requestTimeout} (the SDD's
 * ≈600 ms budget by default); the engine never waits beyond it. The orchestration tier runs this
 * call in parallel with scoring and abandons it on budget, but this class also self-bounds so a slow
 * model can never stall a caller that invokes it directly.
 *
 * <p><b>Warm-up.</b> Ollama lazily loads a model on first use (multi-hundred-ms activation), which
 * would blow the budget on the very first budgeted call. {@link #warmUp()} performs one untimed call
 * at boot so the model is resident before the first real decision is judged.
 */
public final class OllamaAiCoJudge implements AiCoJudge {

    private static final Logger LOG = LoggerFactory.getLogger(OllamaAiCoJudge.class);

    /** The four decisions the co-judge is allowed to emit (a strict subset of {@link Verdict}). */
    private static final String DECISION_ENUM = "ALLOW|CONFIRM|HOLD|BLOCK";

    /** Cap on the model's reason length once normalized (defence against a verbose model). */
    private static final int MAX_REASON_CHARS = 80;

    /** Generation cap: the response is a tiny fixed JSON object, so a small budget keeps it fast. */
    private static final int NUM_PREDICT = 48;

    /** Keep the model resident between calls so warm calls avoid a reload on the hot path. */
    private static final String KEEP_ALIVE = "30m";

    private final HttpClient httpClient;
    private final JsonMapper jsonMapper;
    private final URI generateUri;
    private final String model;
    private final Duration requestTimeout;

    /**
     * @param ollamaBaseUrl  base URL of the Ollama daemon, e.g. {@code http://localhost:11434}
     * @param model          a real, pulled Gemma model tag, e.g. {@code gemma4:e2b}
     * @param requestTimeout hard per-call budget; on expiry the opinion is {@code UNAVAILABLE}
     */
    public OllamaAiCoJudge(String ollamaBaseUrl, String model, Duration requestTimeout) {
        if (ollamaBaseUrl == null || ollamaBaseUrl.isBlank()) {
            throw new IllegalArgumentException("ollamaBaseUrl must be set");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model must be set");
        }
        if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        String base = ollamaBaseUrl.endsWith("/")
                ? ollamaBaseUrl.substring(0, ollamaBaseUrl.length() - 1)
                : ollamaBaseUrl;
        this.generateUri = URI.create(base + "/api/generate");
        this.model = model;
        this.requestTimeout = requestTimeout;
        this.jsonMapper = JsonMapper.builder().build();
        // The connect timeout is part of the overall budget; a request timeout bounds the whole
        // exchange so a dead daemon fails fast rather than hanging the (parallel) co-judge slot.
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(requestTimeout)
                .build();
    }

    /**
     * One untimed call at boot so Ollama loads the model into memory before the first budgeted
     * decision. Failures here are logged and swallowed: a co-judge that cannot warm up simply serves
     * {@code UNAVAILABLE} on real calls, which the engine tolerates.
     */
    public void warmUp() {
        try {
            String body = buildRequestBody(warmUpPrompt(), /* untimed */ false);
            HttpRequest request = HttpRequest.newBuilder(generateUri)
                    .timeout(Duration.ofSeconds(120))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                LOG.info("AI co-judge warm-up OK: model '{}' loaded at {}", model, generateUri);
            } else {
                LOG.warn("AI co-judge warm-up returned HTTP {} for model '{}'", response.statusCode(), model);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            LOG.warn("AI co-judge warm-up interrupted");
        } catch (RuntimeException | java.io.IOException ex) {
            LOG.warn("AI co-judge warm-up failed ({}); co-judge will serve UNAVAILABLE until reachable",
                    ex.toString());
        }
    }

    @Override
    public Opinion opine(ActionEvent event, EngineVerdict verdict) {
        if (verdict == null) {
            return Opinion.unavailable();
        }
        try {
            String prompt = buildPrompt(verdict);
            String body = buildRequestBody(prompt, /* timed */ true);
            HttpRequest request = HttpRequest.newBuilder(generateUri)
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                LOG.debug("Co-judge HTTP {}; serving UNAVAILABLE", response.statusCode());
                return Opinion.unavailable();
            }
            return parseOpinion(response.body(), verdict);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return Opinion.unavailable();
        } catch (RuntimeException | java.io.IOException ex) {
            // Timeout (HttpTimeoutException), connection refused, transport or JSON errors all land
            // here: the model is not usable for this decision, so the engine proceeds alone.
            LOG.debug("Co-judge call failed ({}); serving UNAVAILABLE", ex.toString());
            return Opinion.unavailable();
        }
    }

    // --- request building ---------------------------------------------------------------------

    /**
     * The Ollama {@code /api/generate} request body: deterministic ({@code temperature 0}), short
     * ({@code num_predict 48}), single-shot ({@code stream false}), JSON-forced ({@code format json}).
     */
    private String buildRequestBody(String prompt, boolean timed) {
        var root = jsonMapper.createObjectNode();
        root.put("model", model);
        root.put("prompt", prompt);
        root.put("stream", false);
        root.put("format", "json");
        root.put("keep_alive", KEEP_ALIVE);
        var options = root.putObject("options");
        options.put("temperature", 0);
        options.put("num_predict", NUM_PREDICT);
        return jsonMapper.writeValueAsString(root);
    }

    /**
     * Render the SDD §9.4 fraud co-judge template: the engine's score, band, typologies and key
     * signals in; a minified JSON verdict out. The model sees the engine's <em>reasoning</em> (its
     * signal vector), never raw customer money data — the privacy boundary the SDD requires.
     */
    private String buildPrompt(EngineVerdict verdict) {
        String band = bandOf(verdict.decision());
        String typologies = (verdict.typologies() == null || verdict.typologies().isEmpty())
                ? "none"
                : String.join(",", verdict.typologies());
        String signals = keySignals(verdict.signals());
        return "You are a second, independent fraud-risk co-judge reviewing a deterministic engine's "
                + "decision on a banking action. The engine is authoritative; you give an advisory "
                + "second opinion.\n"
                + "Engine score (0-100): " + verdict.score() + "\n"
                + "Engine band: " + band + "\n"
                + "Engine typologies: " + typologies + "\n"
                + "Key risk signals (id=strength 0-1): " + signals + "\n"
                + "Reply with ONLY a minified JSON object and nothing else, exactly these keys:\n"
                + "{\"score\":<integer 0-100>,\"decision\":\"" + DECISION_ENUM + "\","
                + "\"reason\":\"<at most 6 words>\",\"agreement\":<true if you agree with the engine band, "
                + "else false>}";
    }

    private String warmUpPrompt() {
        // A representative, fully-formed prompt so the warm-up exercises the same path as real calls.
        return "You are a fraud-risk co-judge. Engine score 0, band ALLOW, typologies none, no signals. "
                + "Reply ONLY minified JSON {\"score\":0,\"decision\":\"ALLOW\",\"reason\":\"low risk\","
                + "\"agreement\":true}.";
    }

    /**
     * The top signals by absolute weighted contribution (most influential first), formatted
     * {@code id=value}. Bounded to the few that matter so the prompt stays small and the eval fast.
     */
    private static String keySignals(List<Signal> signals) {
        if (signals == null || signals.isEmpty()) {
            return "none";
        }
        return signals.stream()
                .filter(s -> s.id() != null)
                .sorted((a, b) -> Double.compare(Math.abs(b.contribution()), Math.abs(a.contribution())))
                .limit(6)
                .map(s -> s.id() + "=" + round2(s.value()))
                .collect(Collectors.joining(","));
    }

    private static String round2(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    /** Map the engine's banded verdict to the four-band label the SDD prompt uses. */
    private static String bandOf(Verdict decision) {
        if (decision == null) {
            return "CONFIRM";
        }
        return switch (decision) {
            case ALLOW -> "ALLOW";
            case CONFIRM -> "CONFIRM";
            case HOLD, REQUIRE_APPROVAL -> "HOLD";
            case BLOCK -> "BLOCK";
        };
    }

    // --- response parsing ---------------------------------------------------------------------

    /**
     * Parse Ollama's envelope ({@code {"response":"<json string>", ...}}), then the inner verdict
     * JSON. Every required key must be present and the decision must be a valid enum value, or the
     * opinion is {@code UNAVAILABLE} (we never fabricate a verdict from a malformed model reply).
     */
    private Opinion parseOpinion(String envelope, EngineVerdict engineVerdict) {
        try {
            JsonNode root = jsonMapper.readTree(envelope);
            JsonNode responseField = root.get("response");
            if (responseField == null || !responseField.isString()) {
                return Opinion.unavailable();
            }
            String inner = responseField.asString();
            if (inner == null || inner.isBlank()) {
                return Opinion.unavailable();
            }
            JsonNode verdict = jsonMapper.readTree(inner);

            JsonNode scoreNode = verdict.get("score");
            JsonNode decisionNode = verdict.get("decision");
            JsonNode reasonNode = verdict.get("reason");
            JsonNode agreementNode = verdict.get("agreement");
            if (scoreNode == null || decisionNode == null || reasonNode == null || agreementNode == null) {
                return Opinion.unavailable();
            }

            Integer score = parseScore(scoreNode);
            Verdict decision = parseDecision(decisionNode);
            if (score == null || decision == null) {
                return Opinion.unavailable();
            }
            String reason = normalizeReason(reasonNode);

            // The model returns a boolean "agreement"; the combine rule needs the directional enum.
            // We derive direction deterministically by comparing the co-judge band severity with the
            // engine band severity, which makes the §8.3 escalation reproducible regardless of how the
            // model phrases its agreement flag.
            Agreement agreement = deriveAgreement(decision, engineVerdict.decision());
            return new Opinion(score, decision, reason, agreement, STATUS_AVAILABLE);
        } catch (RuntimeException ex) {
            LOG.debug("Co-judge response parse failed ({}); serving UNAVAILABLE", ex.toString());
            return Opinion.unavailable();
        }
    }

    private static Integer parseScore(JsonNode node) {
        int score;
        if (node.isNumber()) {
            score = node.asInt();
        } else if (node.isString()) {
            try {
                score = Integer.parseInt(node.asString().trim());
            } catch (NumberFormatException ex) {
                return null;
            }
        } else {
            return null;
        }
        if (score < 0) {
            score = 0;
        } else if (score > 100) {
            score = 100;
        }
        return score;
    }

    private static Verdict parseDecision(JsonNode node) {
        if (!node.isString()) {
            return null;
        }
        String raw = node.asString();
        if (raw == null) {
            return null;
        }
        String value = raw.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (value) {
            case "ALLOW" -> Verdict.ALLOW;
            case "CONFIRM" -> Verdict.CONFIRM;
            case "HOLD" -> Verdict.HOLD;
            case "BLOCK" -> Verdict.BLOCK;
            default -> null; // anything outside the allowed enum is a parse failure → UNAVAILABLE
        };
    }

    private static String normalizeReason(JsonNode node) {
        String reason = node.isString() ? node.asString() : node.toString();
        if (reason == null || reason.isBlank()) {
            return "no reason given";
        }
        reason = reason.trim();
        if (reason.length() > MAX_REASON_CHARS) {
            reason = reason.substring(0, MAX_REASON_CHARS).trim();
        }
        return reason;
    }

    /**
     * The directional agreement the §8.3 combine rule consumes, derived from band severity:
     * <ul>
     *   <li>same severity → {@link Agreement#CONCUR}</li>
     *   <li>co-judge stricter (higher severity) → {@link Agreement#DIVERGE_STRICTER}</li>
     *   <li>co-judge softer (lower severity) → {@link Agreement#DIVERGE_SOFTER}</li>
     * </ul>
     * Only a confident {@code DIVERGE_STRICTER} ever changes the combined outcome (one capped band);
     * everything else leaves the engine decision intact.
     */
    private static Agreement deriveAgreement(Verdict coJudge, Verdict engine) {
        int co = severity(coJudge);
        int eng = severity(engine);
        if (co == eng) {
            return Agreement.CONCUR;
        }
        return co > eng ? Agreement.DIVERGE_STRICTER : Agreement.DIVERGE_SOFTER;
    }

    /** Monotone severity ranking for band comparison (ALLOW &lt; CONFIRM &lt; HOLD/APPROVAL &lt; BLOCK). */
    private static int severity(Verdict decision) {
        if (decision == null) {
            return 0;
        }
        return switch (decision) {
            case ALLOW -> 0;
            case CONFIRM -> 1;
            case HOLD, REQUIRE_APPROVAL -> 2;
            case BLOCK -> 3;
        };
    }
}
