package dev.briefestboxer.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class EntityTargetGraceTest {
    @Test
    void retainsTargetOnlyWithinConfiguredMissGrace() {
        EntityTargetGrace<Object> grace = new EntityTargetGrace<>();
        Object target = new Object();
        grace.remember(target, 1_000L);

        assertSame(target, grace.duringMiss(1_100L, 100L));
        assertNull(grace.duringMiss(1_101L, 100L));
    }

    @Test
    void newTargetReplacesPreviousTargetAndRestartsGraceClock() {
        EntityTargetGrace<Object> grace = new EntityTargetGrace<>();
        Object first = new Object();
        Object second = new Object();
        grace.remember(first, 1_000L);
        grace.remember(second, 5_000L);

        assertSame(second, grace.duringMiss(5_100L, 100L));
        assertNull(grace.duringMiss(5_101L, 100L));
    }

    @Test
    void backwardsClockInvalidatesRememberedTarget() {
        EntityTargetGrace<Object> grace = new EntityTargetGrace<>();
        grace.remember(new Object(), 1_000L);

        assertNull(grace.duringMiss(999L, 100L));
    }
}
