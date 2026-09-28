package dev.briefestboxer.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReachableSurfaceTest {
    private static final EntityHighlightSelector.Bounds BOX =
            new EntityHighlightSelector.Bounds(-0.3, 0.0, -0.3, 0.3, 1.8, 0.3);

    @Test
    void reachableCoverageGrowsAsCameraApproaches() {
        List<ReachableSurface.Triangle> far = ReachableSurface.mesh(BOX, new Vec3(0, 0.9, -1.7), 1.5);
        List<ReachableSurface.Triangle> near = ReachableSurface.mesh(BOX, new Vec3(0, 0.9, -1.0), 1.5);
        assertTrue(!far.isEmpty());
        assertTrue(area(near) > area(far));
    }

    @Test
    void nearReachBoundaryCoversOnlyTheReachablePartOfTheHitbox() {
        List<ReachableSurface.Triangle> reachable =
                ReachableSurface.mesh(BOX, new Vec3(0, 0.9, -2.9), 3.0);
        double fullSurfaceArea = 2.0 * (0.6 * 0.6 + 0.6 * 1.8 + 0.6 * 1.8);
        assertFalse(reachable.isEmpty());
        assertTrue(area(reachable) < fullSurfaceArea);
    }

    @Test
    void everyTileRemainsOnOrInsideHitboxBounds() {
        Vec3 camera = new Vec3(0, 0.9, -1.0);
        List<ReachableSurface.Triangle> triangles = ReachableSurface.mesh(BOX, camera, 3.0);
        assertFalse(triangles.isEmpty());
        for (ReachableSurface.Triangle triangle : triangles) {
            for (Vec3 p : new Vec3[]{triangle.a, triangle.b, triangle.c}) {
                assertTrue(p.x >= BOX.minX - 1.0E-10 && p.x <= BOX.maxX + 1.0E-10);
                assertTrue(p.y >= BOX.minY - 1.0E-10 && p.y <= BOX.maxY + 1.0E-10);
                assertTrue(p.z >= BOX.minZ - 1.0E-10 && p.z <= BOX.maxZ + 1.0E-10);
                assertTrue(distanceSquared(p, camera) <= 9.0 + 1.0E-9);
            }
        }
    }

    @Test
    void everyFaceTriangleWindsOutward() {
        List<ReachableSurface.Triangle> triangles = ReachableSurface.mesh(BOX, new Vec3(0, 0.9, -1.0), 3.0);
        assertFalse(triangles.isEmpty());
        for (ReachableSurface.Triangle triangle : triangles) {
            Vec3 normal = cross(triangle.b.subtract(triangle.a), triangle.c.subtract(triangle.a));
            if (Math.abs(triangle.a.x - triangle.b.x) < 1.0E-10
                    && Math.abs(triangle.a.x - triangle.c.x) < 1.0E-10) {
                assertTrue(normal.x * (triangle.a.x == BOX.minX ? -1.0 : 1.0) >= -1.0E-12);
            } else if (Math.abs(triangle.a.y - triangle.b.y) < 1.0E-10
                    && Math.abs(triangle.a.y - triangle.c.y) < 1.0E-10) {
                assertTrue(normal.y * (triangle.a.y == BOX.minY ? -1.0 : 1.0) >= -1.0E-12);
            } else {
                assertTrue(normal.z * (triangle.a.z == BOX.minZ ? -1.0 : 1.0) >= -1.0E-12);
            }
        }
    }

    @Test
    void onlyCameraFacingReachableFacesAreIncluded() {
        Vec3 camera = new Vec3(2.0, 2.0, -2.0);
        List<ReachableSurface.Triangle> triangles = ReachableSurface.mesh(BOX, camera, 3.0);
        assertFalse(triangles.isEmpty());
        for (ReachableSurface.Triangle triangle : triangles) {
            boolean xFace = same(triangle.a.x, triangle.b.x) && same(triangle.a.x, triangle.c.x);
            boolean yFace = same(triangle.a.y, triangle.b.y) && same(triangle.a.y, triangle.c.y);
            boolean zFace = same(triangle.a.z, triangle.b.z) && same(triangle.a.z, triangle.c.z);
            assertTrue((xFace ? 1 : 0) + (yFace ? 1 : 0) + (zFace ? 1 : 0) == 1);
            if (xFace) assertTrue(same(triangle.a.x, BOX.maxX) && camera.x >= triangle.a.x);
            if (yFace) assertTrue(same(triangle.a.y, BOX.maxY) && camera.y >= triangle.a.y);
            if (zFace) assertTrue(same(triangle.a.z, BOX.minZ) && camera.z <= triangle.a.z);
        }
    }

    private static boolean same(double a, double b) {
        return Math.abs(a - b) <= 1.0E-10;
    }

    private static double area(List<ReachableSurface.Triangle> triangles) {
        double area = 0.0;
        for (ReachableSurface.Triangle t : triangles) {
            double ax = t.b.x - t.a.x, ay = t.b.y - t.a.y, az = t.b.z - t.a.z;
            double bx = t.c.x - t.a.x, by = t.c.y - t.a.y, bz = t.c.z - t.a.z;
            double cx = ay * bz - az * by, cy = az * bx - ax * bz, cz = ax * by - ay * bx;
            area += 0.5 * Math.sqrt(cx * cx + cy * cy + cz * cz);
        }
        return area;
    }

    private static double distanceSquared(Vec3 a, Vec3 b) {
        double x = a.x - b.x, y = a.y - b.y, z = a.z - b.z;
        return x * x + y * y + z * z;
    }

    private static Vec3 cross(Vec3 a, Vec3 b) {
        return new Vec3(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x);
    }
}
