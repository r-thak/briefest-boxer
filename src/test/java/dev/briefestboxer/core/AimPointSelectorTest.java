package dev.briefestboxer.core;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AimPointSelectorTest {
    @Test
    void choosesPointWithSmallestAngleToViewRay() {
        AimPoint chest = new AimPoint("chest", new Vec3(0.2, 1.0, 4.0));
        AimPoint head = new AimPoint("head", new Vec3(0.0, 1.8, 4.0));

        AimPoint selected = AimPointSelector.nearestToViewRay(
                new Vec3(0.0, 0.0, 0.0), new Vec3(0.0, 0.45, 1.0),
                Arrays.asList(chest, head), 6.0);

        assertEquals("head", selected.part);
    }

    @Test
    void ignoresPointsBehindViewAndOutsideRange() {
        AimPoint behind = new AimPoint("behind", new Vec3(0.0, 0.0, -2.0));
        AimPoint far = new AimPoint("far", new Vec3(0.0, 0.0, 8.0));

        AimPoint selected = AimPointSelector.nearestToViewRay(
                new Vec3(0.0, 0.0, 0.0), new Vec3(0.0, 0.0, 1.0),
                Arrays.asList(behind, far), 6.0);

        assertNull(selected);
    }
}
