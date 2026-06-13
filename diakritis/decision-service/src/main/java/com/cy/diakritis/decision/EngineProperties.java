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

