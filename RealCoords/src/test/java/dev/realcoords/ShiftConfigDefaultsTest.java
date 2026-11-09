package dev.realcoords;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ShiftConfigDefaultsTest {
    @Test
    void legacyConfigWithoutNewThresholdKeysLoadsWithProductionDefaults() {
        List<String> warnings = new ArrayList<>();
        ShiftConfig config = ShiftConfig.parse(
                Map.of("origin-shift", Map.of("enabled", true)),
                warnings::add);

        assertEquals(100_000, config.dim(0).trigger);
        assertEquals(50_000, config.dim(0).reset);
        assertEquals(2_048, config.recenterOnReload);
        assertFalse(config.shiftWhileMoving);
        assertFalse(config.debug, "Safety → Debug Log must default to off");
        assertTrue(warnings.isEmpty());
    }

    @Test
    void debugLogCanBeEnabledExplicitly() {
        ShiftConfig config = ShiftConfig.parse(
                Map.of("safety", Map.of("debug-log", true)), ignored -> { });

        assertTrue(config.debug);
    }

    @Test
    void javaModeBedrockAuxHookDefaultsEnabledAndCanBeDisabled() throws Exception {
        java.lang.reflect.Field field;
        try {
            field = ShiftConfig.class.getDeclaredField("javaBedrockAuxHook");
        } catch (NoSuchFieldException missing) {
            fail("ShiftConfig must expose javaBedrockAuxHook");
            return;
        }
        field.setAccessible(true);

        ShiftConfig defaults = new ShiftConfig();
        assertTrue(field.getBoolean(defaults), "Bedrock auxiliary hook must remain enabled by default");

        ShiftConfig disabled = ShiftConfig.parse(
                Map.of("java-mode", Map.of("bedrock-aux-hook", false)), ignored -> { });
        assertFalse(field.getBoolean(disabled), "configuration must disable the auxiliary hook");
    }

    @Test
    void resetDistanceAtOrAboveTriggerIsAdjustedAndWarned() {
        List<String> warnings = new ArrayList<>();
        ShiftConfig config = ShiftConfig.parse(
                Map.of("origin-shift", Map.of(
                        "trigger-distance", 100_000,
                        "reset-distance", 100_000)),
                warnings::add);

        assertEquals(50_000, config.dim(0).reset);
        assertTrue(warnings.stream().anyMatch(w -> w.contains("reset-distance 必須小於 trigger-distance")));
    }

    @Test
    void triggerDistanceAboveFourMillionIsWarned() {
        List<String> warnings = new ArrayList<>();
        ShiftConfig config = ShiftConfig.parse(
                Map.of("origin-shift", Map.of("trigger-distance", 4_000_001)),
                warnings::add);

        assertEquals(4_000_001, config.dim(0).trigger);
        assertTrue(warnings.stream().anyMatch(w -> w.contains("trigger-distance > 4,000,000")));
    }

    @Test
    void invalidDimensionOverrideIsAlsoAdjustedAndWarned() {
        List<String> warnings = new ArrayList<>();
        ShiftConfig config = ShiftConfig.parse(
                Map.of("origin-shift", Map.of(
                        "dimensions", Map.of("the_nether", Map.of(
                                "trigger-distance", 10_000,
                                "reset-distance", 10_000)))),
                warnings::add);

        assertEquals(5_000, config.dim(1).reset);
        assertTrue(warnings.stream().anyMatch(w -> w.contains("the_nether")));
    }
}
