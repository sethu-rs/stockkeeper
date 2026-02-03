package com.stockkeeper.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Loads capacity-config.yml at application startup and holds the parsed
 * {@link CapacityConfig} in memory for the lifetime of the application.
 *
 * Design rationale:
 * - The config is read once at startup (fail-fast on bad config).
 * - All validation logic in the service layer reads from this in-memory object,
 *   so we never hit DynamoDB for business-rule checks like "is this capacity type
 *   supported?" or "is this state transition allowed?".
 * - Uses Jackson YAML parser (not Spring @ConfigurationProperties) because the
 *   file is a standalone YAML document separate from application.yml.
 */
@Slf4j
@Configuration
public class CapacityConfigLoader {

    @Value("${capacity.config-path}")
    private Resource configResource;

    /** The fully-parsed, immutable-in-practice config object. */
    @Getter
    private CapacityConfig capacityConfig;

    /**
     * Called after dependency injection. Reads and parses the YAML file,
     * then logs a summary for operator visibility.
     */
    @PostConstruct
    public void loadConfig() throws IOException {
        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

        try (InputStream is = configResource.getInputStream()) {
            capacityConfig = yamlMapper.readValue(is, CapacityConfig.class);
        }

        // Fail-fast: verify essential sections are present
        if (capacityConfig.getCapacityTypes() == null || capacityConfig.getCapacityTypes().isEmpty()) {
            throw new IllegalStateException("capacity-config.yml must define at least one capacity type");
        }
        if (capacityConfig.getAllowedTransitions() == null || capacityConfig.getAllowedTransitions().isEmpty()) {
            throw new IllegalStateException("capacity-config.yml must define allowed transitions");
        }

        log.info("Loaded capacity configuration: {} capacity types, {} transition rules, terminal states={}",
                capacityConfig.getCapacityTypes().size(),
                capacityConfig.getAllowedTransitions().size(),
                capacityConfig.getTerminalStates());
    }

    // -----------------------------------------------------------------------
    // Convenience query methods used by the service/validation layer
    // -----------------------------------------------------------------------

    /** Returns true if the given capacity type (e.g. "FLIGHT") is configured. */
    public boolean isSupportedCapacityType(String capacityType) {
        return capacityConfig.getCapacityTypes().containsKey(capacityType);
    }

    /** Returns the per-type config, or null if the type is not supported. */
    public CapacityTypeConfig getTypeConfig(String capacityType) {
        return capacityConfig.getCapacityTypes().get(capacityType);
    }

    /** Returns true if the given class flag is allowed for the given capacity type. */
    public boolean isClassFlagAllowed(String capacityType, String classFlag) {
        CapacityTypeConfig typeConfig = getTypeConfig(capacityType);
        if (typeConfig == null) {
            return false;
        }
        return typeConfig.getAllowedClassFlags().contains(classFlag);
    }

    /** Returns true if transitioning from currentState → targetState is allowed. */
    public boolean isTransitionAllowed(String currentState, String targetState) {
        List<String> allowed = capacityConfig.getAllowedTransitions().get(currentState);
        return allowed != null && allowed.contains(targetState);
    }

    /** Returns true if the given state is terminal (no further transitions). */
    public boolean isTerminalState(String state) {
        return capacityConfig.getTerminalStates() != null
                && capacityConfig.getTerminalStates().contains(state);
    }

    /** Returns the max hold duration in minutes for a given capacity type. */
    public int getMaxHoldDurationMinutes(String capacityType) {
        CapacityTypeConfig typeConfig = getTypeConfig(capacityType);
        if (typeConfig == null) {
            throw new IllegalArgumentException("Unknown capacity type: " + capacityType);
        }
        return typeConfig.getMaxHoldDurationMinutes();
    }
}
