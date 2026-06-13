package com.cy.diakritis.decision;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Engine wiring settings bound from the {@code diakrisis} prefix.
 *
 * <p>{@code modelsDir} is the directory holding the pre-trained M1 artifacts
 * ({@code m1/m1.model}, {@code m1/columns.txt}, {@code m1/isotonic.csv}, {@code m1/percentiles.csv}).
 * The model is loaded once at startup; a missing directory degrades M1 to a constant-0 signal
 * (the engine still scores on its rule signals).
 *
 * <p>{@code geoCidrs} is the local IP→country table backing the {@code CidrGeoResolver} (the §5/§6
 * geo seam). It is seeded with a Cyprus home range and a foreign (Jordan) range so G1 fires when an
 * action's IP country is new for the account; a deployment may override it under
 * {@code diakrisis.geo-cidrs} without touching code.
 */
@ConfigurationProperties(prefix = "diakrisis")
public class EngineProperties {

    /** Absolute path to the pre-trained models directory. */
    private String modelsDir = "/Users/achilleaseftychiou/Documents/Projects/diakrisis/diakrisis-models";

    /**
     * Local CIDR → ISO country table for the geo resolver. Defaults to a Cyprus home block and a
     * foreign Jordan block so the geo signals are testable without an external geolocation source.
     * The two blocks are disjoint /16s in the documentation/test ranges (TEST-NET-3 carved by /16).
     */
    private Map<String, String> geoCidrs = defaultGeoCidrs();

    /** AI co-judge (local Gemma via Ollama, SDD §9.4) wiring. */
    private CoJudge cojudge = new CoJudge();

    /** §9.2 M2 vector-similarity backend wiring (Qdrant production path + KDTree fallback). */
    private M2 m2 = new M2();

    public String getModelsDir() {
        return modelsDir;
    }

    public void setModelsDir(String modelsDir) {
        this.modelsDir = modelsDir;
    }

    public Map<String, String> getGeoCidrs() {
        return geoCidrs;
    }

    public void setGeoCidrs(Map<String, String> geoCidrs) {
        this.geoCidrs = geoCidrs;
    }

    public CoJudge getCojudge() {
        return cojudge;
    }

    public void setCojudge(CoJudge cojudge) {
        this.cojudge = cojudge;
    }

    public M2 getM2() {
        return m2;
    }

    public void setM2(M2 m2) {
        this.m2 = m2;
    }

    /**
     * §9.2 M2 vector-similarity settings (bound from {@code diakrisis.m2.*}). {@code backend} selects
     * the {@code ExemplarIndex} impl: {@code qdrant} (live cosine k-NN over a running Qdrant),
     * {@code kdtree} (the in-JVM Smile fallback), or {@code auto} (prefer Qdrant when reachable, else
     * KDTree). The nested {@link Qdrant} carries the gRPC connection settings used when the backend
     * resolves to Qdrant. M2 is additive and capped — an unreachable Qdrant never fails the service;
     * it degrades to the KDTree fallback at boot, or to a 0 signal per-query on a transient fault.
     */
    public static class M2 {

        /** {@code qdrant} | {@code kdtree} | {@code auto}. Default {@code qdrant} per the §9.2 contract. */
        private String backend = "qdrant";

        /**
         * Path (relative to {@code models-dir} when not absolute) of the in-JVM KDTree fallback exemplar
         * table — the {@code label,f0..f15} CSV the Smile KDTree indexes. Default
         * {@code m2/exemplars.csv}; the canonical models dir ships WITHOUT this file so the KDTree
         * fallback is dormant (M2 scores 0, the §9.2 resilience default that the golden-path baseline is
         * calibrated against). The M2 exemplar loader writes a dedicated parity table
         * ({@code m2/exemplars.kdtree.csv}) that an operator can point this at to run the live KDTree
         * fallback against the same exemplars Qdrant holds.
         */
        private String kdtreeTable = "m2/exemplars.csv";

        /** Qdrant gRPC connection settings, used when {@code backend} resolves to qdrant/auto. */
        private Qdrant qdrant = new Qdrant();

        public String getBackend() {
            return backend;
        }

        public void setBackend(String backend) {
            this.backend = backend;
        }

        public String getKdtreeTable() {
            return kdtreeTable;
        }

        public void setKdtreeTable(String kdtreeTable) {
            this.kdtreeTable = kdtreeTable;
        }

        public Qdrant getQdrant() {
            return qdrant;
        }

        public void setQdrant(Qdrant qdrant) {
            this.qdrant = qdrant;
        }

        /** Qdrant gRPC connection settings (bound from {@code diakrisis.m2.qdrant.*}). */
        public static class Qdrant {

            /** Qdrant host. */
            private String host = "localhost";

            /** Qdrant gRPC port (the REST port is 6333; gRPC is 6334). */
            private int port = 6334;

            /** Whether the gRPC channel uses TLS (false for the local container). */
            private boolean useTls = false;

            /** The exemplar collection name (cosine, payload {@code {fraud:0|1}}). */
            private String collection = "fraud_exemplars";

            /** Hard budget for the boot reachability + count probe, in milliseconds. */
            private long connectTimeoutMs = 1500L;

            /** Hard per-query budget for the live k-NN search, in milliseconds. */
            private long queryTimeoutMs = 250L;

            public String getHost() {
                return host;
            }

            public void setHost(String host) {
                this.host = host;
            }

            public int getPort() {
                return port;
            }

            public void setPort(int port) {
                this.port = port;
            }

            public boolean isUseTls() {
                return useTls;
            }

            public void setUseTls(boolean useTls) {
                this.useTls = useTls;
            }

            public String getCollection() {
                return collection;
            }

            public void setCollection(String collection) {
                this.collection = collection;
            }

            public long getConnectTimeoutMs() {
                return connectTimeoutMs;
            }

            public void setConnectTimeoutMs(long connectTimeoutMs) {
                this.connectTimeoutMs = connectTimeoutMs;
            }

            public long getQueryTimeoutMs() {
                return queryTimeoutMs;
            }

            public void setQueryTimeoutMs(long queryTimeoutMs) {
                this.queryTimeoutMs = queryTimeoutMs;
            }
        }
    }

    /**
     * Local-Gemma co-judge settings (bound from {@code diakrisis.cojudge.*}). The co-judge is the
     * SDD §9.4 second opinion: a real Ollama-hosted Gemma when {@code enabled} and reachable, else the
     * resilience-default {@code UnavailableAiCoJudge}. It is advisory-only — the engine decision is
     * authoritative regardless of this setting.
     */
    public static class CoJudge {

        /** Whether to wire the live Ollama co-judge. When false, the UNAVAILABLE default is used. */
        private boolean enabled = true;

        /** Base URL of the Ollama daemon. */
        private String ollamaUrl = "http://localhost:11434";

        /** A real, pulled Gemma model tag (verify with {@code ollama list}; e.g. {@code gemma4:e2b}). */
        private String model = "gemma4:e2b";

        /** Hard per-call budget in milliseconds (SDD ≈600 ms); on expiry the opinion is UNAVAILABLE. */
        private long timeoutMs = 600L;

        /**
         * Whether to perform the boot warm-up call so the model is resident before the first budgeted
         * decision. Disabling it only means the first real call is likely to time out to UNAVAILABLE.
         */
        private boolean warmUpOnBoot = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getOllamaUrl() {
            return ollamaUrl;
        }

        public void setOllamaUrl(String ollamaUrl) {
            this.ollamaUrl = ollamaUrl;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public long getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public boolean isWarmUpOnBoot() {
            return warmUpOnBoot;
        }

        public void setWarmUpOnBoot(boolean warmUpOnBoot) {
            this.warmUpOnBoot = warmUpOnBoot;
        }
    }

    private static Map<String, String> defaultGeoCidrs() {
        Map<String, String> cidrs = new LinkedHashMap<>();
        // Cyprus home range: the documentation-reserved 203.0.113.0/24 (TEST-NET-3) the golden-path
        // device sessions originate from, plus its enclosing /16 so any 203.0.x.x reads as Cyprus.
        cidrs.put("203.0.0.0/16", "CY");
        // Foreign range (Jordan): a disjoint documentation /16 used by the stacked-signal foreign-IP
        // scenario so G1 fires on an IP whose country is new for the account.
        cidrs.put("198.51.0.0/16", "JO");
        return cidrs;
    }
}

