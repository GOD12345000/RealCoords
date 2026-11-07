package dev.realcoords;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ShiftConfigTransitionTest {
    @Test
    void missingTransitionOptionsUseSafeDefaultsForExistingConfigs() {
        ShiftConfig config = ShiftConfig.parse(Map.of("origin-shift", Map.of("shift-while-moving", false)), ignored -> {});

        assertEquals(100, config.transitionTimeoutTicks);
        assertEquals(600, config.transitionCooldownTicks);
        assertEquals(20, config.transitionGraceTicks);
        assertEquals("none", config.walkFallback);
        assertFalse(config.shiftWhileMoving);
    }

    @Test
    void transitionOptionsLoadConfiguredValues() {
        Map<String, Object> originShift = new HashMap<>();
        originShift.put("transition-timeout-ticks", 240);
        originShift.put("transition-cooldown-ticks", 900);
        originShift.put("transition-grace-ticks", 35);
        originShift.put("walk-fallback", "ReOrigin");

        ShiftConfig config = ShiftConfig.parse(Map.of("origin-shift", originShift), ignored -> {});

        assertEquals(240, config.transitionTimeoutTicks);
        assertEquals(900, config.transitionCooldownTicks);
        assertEquals(35, config.transitionGraceTicks);
        assertEquals("reorigin", config.walkFallback);
    }

    @Test
    void invalidFallbackUsesNoneAndEmitsWarning() {
        AtomicInteger warnings = new AtomicInteger();
        ShiftConfig config = ShiftConfig.parse(
                Map.of("origin-shift", Map.of("walk-fallback", "teleport")),
                ignored -> warnings.incrementAndGet());

        assertEquals("none", config.walkFallback);
        assertEquals(1, warnings.get());
    }

    @Test
    void transitionTickValuesAreClampedToValidRanges() {
        ShiftConfig config = ShiftConfig.parse(
                Map.of("origin-shift", Map.of(
                        "transition-timeout-ticks", 0,
                        "transition-cooldown-ticks", -1,
                        "transition-grace-ticks", -1)),
                ignored -> {});

        assertEquals(1, config.transitionTimeoutTicks);
        assertEquals(0, config.transitionCooldownTicks);
        assertEquals(0, config.transitionGraceTicks);
    }
    @Test
    void transitionGraceMaxJumpDefaultsTo256AndLoadsConfiguredValue() {
        ShiftConfig defaults = ShiftConfig.parse(Map.of(
                "safety", Map.of("max-serverbound-jump", 64.0)), ignored -> {});
        java.lang.reflect.Field field = java.util.Arrays.stream(ShiftConfig.class.getDeclaredFields())
                .filter(candidate -> candidate.getName().equals("transitionGraceMaxJump"))
                .findFirst().orElse(null);
        assertNotNull(field, "safety.transition-grace-max-jump must have a dedicated config field");
        field.setAccessible(true);
        try {
            assertEquals(256.0, field.getDouble(defaults));
            ShiftConfig configured = ShiftConfig.parse(Map.of("safety", Map.of(
                    "max-serverbound-jump", 64.0, "transition-grace-max-jump", 300.0)), ignored -> {});
            assertEquals(300.0, field.getDouble(configured));
        } catch (IllegalAccessException exception) {
            fail(exception);
        }
    }

    @Test
    void transitionGraceMaxJumpIsNeverBelowNormalJumpAndOldConfigsRemainValid() throws Exception {
        java.lang.reflect.Field field = ShiftConfig.class.getDeclaredField("transitionGraceMaxJump");
        field.setAccessible(true);

        ShiftConfig oldConfig = ShiftConfig.parse(Map.of("safety",
                Map.of("max-serverbound-jump", 300.0)), ignored -> {});
        assertEquals(300.0, field.getDouble(oldConfig),
                "a missing grace limit must be raised to the normal limit when max-serverbound-jump exceeds 256");

        AtomicInteger warnings = new AtomicInteger();
        ShiftConfig invalid = ShiftConfig.parse(Map.of("safety", Map.of(
                "max-serverbound-jump", 128.0,
                "transition-grace-max-jump", 64.0)), ignored -> warnings.incrementAndGet());
        assertEquals(128.0, field.getDouble(invalid));
        assertEquals(1, warnings.get(), "an undersized transition grace limit must emit a warning");

        ShiftConfig nonFinite = ShiftConfig.parse(Map.of("safety", Map.of(
                "max-serverbound-jump", 64.0,
                "transition-grace-max-jump", Double.POSITIVE_INFINITY)), ignored -> {});
        assertEquals(64.0, field.getDouble(nonFinite),
                "non-finite values must fall back to the normal movement limit");
    }

}
