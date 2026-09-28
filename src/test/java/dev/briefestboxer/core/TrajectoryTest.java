package dev.briefestboxer.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TrajectoryTest {
    @Test
    void samplesOnePositionPerTickIncludingStart() {
        Trajectory path = Trajectory.freeFlight(
                new Vec3(0.0, 0.0, 0.0), new Vec3(1.0, 0.0, 0.0),
                new Vec3(0.0, 0.0, 0.0), 1.0, 3);

        assertEquals(4, path.getPositions().size());
        assertEquals(3.0, path.getPositions().get(3).x, 1.0e-9);
    }

    @Test
    void groundImpactUsesEntityAndBlockRestitution() {
        Trajectory path = Trajectory.withGroundBounces(
                new Vec3(0.0, 1.0, 0.0), new Vec3(2.0, -1.0, 0.0),
                new Vec3(0.0, 0.0, 0.0), 1.0, 0.5, 1.0,
                (x, y, z) -> new Trajectory.Surface(0.0, 0.8), 2);

        assertEquals(0.5, path.getPositions().get(1).y, 1.0e-9);
        assertEquals(1.3, path.getPositions().get(2).y, 1.0e-9);
        assertEquals(4.0, path.getPositions().get(2).x, 1.0e-9);
    }

    @Test
    void bounceSuppressionTagOverridesCubeBounciness() {
        Trajectory path = Trajectory.withGroundBounces(
                new Vec3(0.0, 1.0, 0.0), new Vec3(0.0, -1.0, 0.0),
                new Vec3(0.0, 0.0, 0.0), 1.0, 0.8, 1.0,
                (x, y, z) -> new Trajectory.Surface(0.0, 0.0, true), 2);

        assertEquals(0.5, path.getPositions().get(1).y, 1.0e-9);
        assertEquals(0.5, path.getPositions().get(2).y, 1.0e-9);
    }

    @Test
    void blockCollisionClipsMovementAndStopsBlockedAxis() {
        Trajectory path = Trajectory.withGroundBounces(
                new Vec3(0.5, 2.0, 0.5), new Vec3(1.0, 0.0, 0.0), new Vec3(0.0, 0.0, 0.0),
                1.0, 0.0, 1.0, (x, y, z) -> null,
                (position, movement) -> {
                    double distanceToWall = 1.0 - position.x;
                    return movement.x > distanceToWall
                            ? new Vec3(distanceToWall, movement.y, movement.z)
                            : movement;
                }, 5);

        assertEquals(6, path.getPositions().size());
        for (int i = 1; i < path.getPositions().size(); i++) {
            assertEquals(1.0, path.getPositions().get(i).x, 1.0e-9);
        }
    }

    @Test
    void appliesGravityAfterMovementAndUsesSeparateVerticalAirDrag() {
        Trajectory path = Trajectory.withGroundBounces(
                new Vec3(0.0, 10.0, 0.0), new Vec3(1.0, 0.0, 0.0), new Vec3(0.0, -1.0, 0.0),
                0.91, 0.98, 0.0, 1.0, (x, y, z) -> null, null, 2);

        assertEquals(10.0, path.getPositions().get(1).y, 1.0e-9);
        assertEquals(9.02, path.getPositions().get(2).y, 1.0e-9);
        assertEquals(1.91, path.getPositions().get(2).x, 1.0e-9);
    }

    @Test
    void horizontalBlockCollisionUsesEntityRestitution() {
        Trajectory path = Trajectory.withGroundBounces(
                new Vec3(0.5, 2.0, 0.5), new Vec3(1.0, 0.0, 0.0), new Vec3(0.0, 0.0, 0.0),
                1.0, 0.5, 1.0, (x, y, z) -> null,
                (position, movement) -> movement.x > 1.0 - position.x
                        ? new Vec3(1.0 - position.x, movement.y, movement.z) : movement,
                2);

        assertEquals(1.0, path.getPositions().get(1).x, 1.0e-9);
        assertEquals(0.5, path.getPositions().get(2).x, 1.0e-9);
    }

    @Test
    void verticalImpactMatchesLivingEntityRestitutionAndGravityOrder() {
        Trajectory path = Trajectory.withGroundBounces(
                new Vec3(0.0, 1.0, 0.0), new Vec3(0.0, -1.0, 0.0), new Vec3(0.0, -0.08, 0.0),
                0.91, 0.98, 0.5, 1.0,
                (x, y, z) -> new Trajectory.Surface(0.0, 0.8), null, 2);

        assertEquals(0.5, path.getPositions().get(1).y, 1.0e-9);
        assertEquals(1.2288064, path.getPositions().get(2).y, 1.0e-7);
    }

    @Test
    void groundedCubeAppliesBlockFrictionBeforeAirDrag() {
        Trajectory path = Trajectory.withGroundBounces(
                new Vec3(0.0, 0.5, 0.0), new Vec3(1.0, -0.01, 0.0),
                new Vec3(0.0, -0.08, 0.0), 0.91, 0.98, 0.0, 1.0,
                1.0, true, (x, y, z) -> new Trajectory.Surface(0.0, 0.0, false, 0.6), null, 2);

        assertEquals(1.0, path.getPositions().get(1).x, 1.0e-9);
        assertEquals(1.546, path.getPositions().get(2).x, 1.0e-9);
    }

    @Test
    void sweptCollisionContactOverridesSampledGroundHeight() {
        Trajectory path = Trajectory.withGroundBounces(
                new Vec3(0.0, 2.0, 0.0), new Vec3(0.0, -2.0, 0.0),
                new Vec3(0.0, 0.0, 0.0), 1.0, 1.0, 0.2, 1.0,
                1.0, false,
                (x, y, z) -> new Trajectory.Surface(0.4, 0.9),
                (position, movement) -> movement.y < -1.0
                        ? new Vec3(movement.x, -1.0, movement.z) : movement,
                2);

        // The sweep clips the cube at center y=1.0. The height sampler intentionally
        // reports a lower nearby surface; it must not snap the exact collision result.
        assertEquals(1.0, path.getPositions().get(1).y, 1.0e-9);
        assertEquals(2.8, path.getPositions().get(2).y, 1.0e-9);
    }
}
