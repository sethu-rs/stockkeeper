package com.stockkeeper.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test that verifies capacity-config.yml is correctly loaded
 * and the convenience query methods work as expected.
 */
@SpringBootTest
class CapacityConfigLoaderTest {

    @Autowired
    private CapacityConfigLoader configLoader;

    @Test
    void shouldLoadAllCapacityTypes() {
        CapacityConfig config = configLoader.getCapacityConfig();
        assertNotNull(config);
        assertEquals(3, config.getCapacityTypes().size());
        assertTrue(config.getCapacityTypes().containsKey("FLIGHT"));
        assertTrue(config.getCapacityTypes().containsKey("WAREHOUSE"));
        assertTrue(config.getCapacityTypes().containsKey("ULD"));
    }

    @Test
    void shouldRecognizeSupportedCapacityTypes() {
        assertTrue(configLoader.isSupportedCapacityType("FLIGHT"));
        assertTrue(configLoader.isSupportedCapacityType("WAREHOUSE"));
        assertTrue(configLoader.isSupportedCapacityType("ULD"));
        assertFalse(configLoader.isSupportedCapacityType("TRUCK"));
    }

    @Test
    void shouldValidateClassFlags() {
        assertTrue(configLoader.isClassFlagAllowed("FLIGHT", "GENERAL"));
        assertTrue(configLoader.isClassFlagAllowed("FLIGHT", "DANGEROUS_GOODS"));
        assertFalse(configLoader.isClassFlagAllowed("FLIGHT", "COLD_STORAGE"));
        assertFalse(configLoader.isClassFlagAllowed("UNKNOWN_TYPE", "GENERAL"));
    }

    @Test
    void shouldValidateStateTransitions() {
        // Valid transitions
        assertTrue(configLoader.isTransitionAllowed("HELD", "COMMITTED"));
        assertTrue(configLoader.isTransitionAllowed("HELD", "RELEASED"));
        assertTrue(configLoader.isTransitionAllowed("COMMITTED", "LOADED"));
        assertTrue(configLoader.isTransitionAllowed("COMMITTED", "RELEASED"));
        assertTrue(configLoader.isTransitionAllowed("LOADED", "RELEASED"));

        // Invalid transitions
        assertFalse(configLoader.isTransitionAllowed("HELD", "LOADED"));
        assertFalse(configLoader.isTransitionAllowed("LOADED", "COMMITTED"));
        assertFalse(configLoader.isTransitionAllowed("RELEASED", "HELD"));
    }

    @Test
    void shouldIdentifyTerminalStates() {
        assertTrue(configLoader.isTerminalState("RELEASED"));
        assertFalse(configLoader.isTerminalState("HELD"));
        assertFalse(configLoader.isTerminalState("COMMITTED"));
    }

    @Test
    void shouldReturnCorrectMaxHoldDuration() {
        assertEquals(60, configLoader.getMaxHoldDurationMinutes("FLIGHT"));
        assertEquals(120, configLoader.getMaxHoldDurationMinutes("WAREHOUSE"));
        assertEquals(90, configLoader.getMaxHoldDurationMinutes("ULD"));
    }
}
