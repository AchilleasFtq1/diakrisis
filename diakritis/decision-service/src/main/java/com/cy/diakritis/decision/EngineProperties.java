package com.cy.diakritis.decision;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Engine wiring settings bound from the {@code diakrisis} prefix.
 *
 * <p>{@code modelsDir} is the directory holding the pre-trained M1 artifacts
 * ({@code m1/m1.model}, {@code m1/columns.txt}, {@code m1/isotonic.csv}, {@code m1/percentiles.csv}).
 * The model is loaded once at startup; a missing directory degrades M1 to a constant-0 signal
 * (the engine still scores on its rule signals).
 */
@ConfigurationProperties(prefix = "diakrisis")
public class EngineProperties {

    /** Absolute path to the pre-trained models directory. */
    private String modelsDir = "/Users/achilleaseftychiou/Documents/Projects/diakrisis/diakrisis-models";

    public String getModelsDir() {
        return modelsDir;
    }

    public void setModelsDir(String modelsDir) {
        this.modelsDir = modelsDir;
    }
}
