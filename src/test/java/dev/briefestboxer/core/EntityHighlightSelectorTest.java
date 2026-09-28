package dev.briefestboxer.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityHighlightSelectorTest {
    @Test
    void measuresFromCameraToNearestHitboxSurface() {
        EntityHighlightSelector.Bounds bounds = new EntityHighlightSelector.Bounds(-0.3, 0.0, 4.0, 0.3, 1.8, 4.6);
        Vec3 camera = new Vec3(0.0, 1.6, 2.0);

        assertEquals(4.0, EntityHighlightSelector.distanceSquared(camera, bounds), 1.0E-9);
        assertTrue(EntityHighlightSelector.isWithinReach(camera, bounds, 2.0));
    }

    @Test
    void proximityGrowsAsCameraMovesCloserAndStopsAtReachBoundary() {
        EntityHighlightSelector.Bounds near = new EntityHighlightSelector.Bounds(0, 0, 1, 1, 2, 2);
        EntityHighlightSelector.Bounds far = new EntityHighlightSelector.Bounds(0, 0, 2.5, 1, 2, 3.5);
        Vec3 camera = new Vec3(0.5, 1.0, 0.0);

        assertEquals(0.5, EntityHighlightSelector.proximity(camera, near, 2.0), 1.0E-9);
        assertEquals(0.0, EntityHighlightSelector.proximity(camera, far, 2.0), 1.0E-9);
        assertFalse(EntityHighlightSelector.isWithinReach(camera, far, 2.0));
        assertTrue(EntityHighlightSelector.isWithinReach(camera, far, 3.0));
    }

    @Test
    void cameraInsideBoundsHasMaximumProximity() {
        EntityHighlightSelector.Bounds bounds = new EntityHighlightSelector.Bounds(-1, -1, -1, 1, 1, 1);
        assertEquals(1.0, EntityHighlightSelector.proximity(new Vec3(0, 0, 0), bounds, 3.0), 1.0E-9);
    }
}
