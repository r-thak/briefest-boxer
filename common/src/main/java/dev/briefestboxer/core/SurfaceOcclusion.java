package dev.briefestboxer.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Subtracts projected block outline boxes without joining separate visible regions. */
public final class SurfaceOcclusion {
    private SurfaceOcclusion() {}

    /** Reveal the reachable surface only when some of it is already visible. */
    public static List<Vec3[]> quads(List<ReachableSurface.Triangle> mesh, Vec3 camera,
                                    List<EntityHighlightSelector.Bounds> blockers, boolean revealPartial,
                                    List<ReachableSurface.Triangle> visibilityMesh) {
        if (!revealPartial) return quads(mesh, camera, blockers);
        // A visible head above a fence also qualifies when the reachable patch
        // itself is behind a rail. Inspect the whole hitbox for visibility.
        if (quads(visibilityMesh, camera, blockers).isEmpty()) return Collections.emptyList();
        // Keep the whole reachable mesh, never extending past the hitbox or reach.
        // Visibility is checked first so entities behind a wall remain excluded.
        return quads(mesh, camera, Collections.<EntityHighlightSelector.Bounds>emptyList());
    }

    public static List<Vec3[]> quads(List<ReachableSurface.Triangle> mesh, Vec3 camera,
                                    List<EntityHighlightSelector.Bounds> blockers) {
        List<Vec3[]> result = new ArrayList<Vec3[]>();
        // Reuse projections for every triangle on the same hitbox face.
        List<Face> faces = new ArrayList<Face>(6);
        for (ReachableSurface.Triangle triangle : mesh) {
            int axis = Math.abs(triangle.a.x - triangle.b.x) < 1e-9
                    && Math.abs(triangle.a.x - triangle.c.x) < 1e-9 ? 0
                    : Math.abs(triangle.a.y - triangle.b.y) < 1e-9
                    && Math.abs(triangle.a.y - triangle.c.y) < 1e-9 ? 1 : 2;
            double fixed = coordinate(triangle.a, axis);
            Face face = null;
            for (Face candidate : faces) {
                if (candidate.axis == axis && candidate.fixed == fixed) { face = candidate; break; }
            }
            if (face == null) {
                face = new Face(axis, fixed);
                for (EntityHighlightSelector.Bounds box : blockers) {
                    List<Vec3> shadow = project(box, camera, axis, fixed);
                    if (shadow.size() >= 3) face.shadows.add(shadow);
                }
                faces.add(face);
            }
            List<List<Vec3>> pieces = new ArrayList<List<Vec3>>();
            List<Vec3> initial = new ArrayList<Vec3>(3);
            initial.add(triangle.a); initial.add(triangle.b); initial.add(triangle.c);
            pieces.add(initial);
            for (List<Vec3> shadow : face.shadows) {
                List<List<Vec3>> next = new ArrayList<List<Vec3>>();
                for (List<Vec3> piece : pieces) subtract(piece, shadow, axis, next);
                pieces = next;
                if (pieces.isEmpty()) break;
            }
            for (List<Vec3> piece : pieces) {
                for (int i = 1; i + 1 < piece.size(); i++) {
                    Vec3 a = piece.get(0), b = piece.get(i), c = piece.get(i + 1);
                    if (Math.abs(cross(a, b, c, axis)) < 1e-12) continue;
                    Vec3 ab = a.add(b).scale(0.5), bc = b.add(c).scale(0.5), ca = c.add(a).scale(0.5);
                    Vec3 center = a.add(b).add(c).scale(1.0 / 3.0);
                    result.add(new Vec3[] {a, ab, center, ca});
                    result.add(new Vec3[] {ab, b, bc, center});
                    result.add(new Vec3[] {center, bc, c, ca});
                }
            }
        }
        return result;
    }

    private static void subtract(List<Vec3> polygon, List<Vec3> shadow, int axis,
                                 List<List<Vec3>> output) {
        if (!overlaps(polygon, shadow, axis)) { output.add(polygon); return; }
        // Each outside fragment is emitted separately; only the inside remainder
        // continues. A hole never becomes a single concave polygon/triangle fan.
        List<Vec3> remaining = polygon;
        for (int i = 0; i < shadow.size() && remaining.size() >= 3; i++) {
            Vec3 a = shadow.get(i), b = shadow.get((i + 1) % shadow.size());
            List<Vec3> outside = clip(remaining, a, b, axis, false);
            if (outside.size() >= 3) output.add(outside);
            remaining = clip(remaining, a, b, axis, true);
        }
    }

    private static boolean overlaps(List<Vec3> a, List<Vec3> b, int axis) {
        for (int offset = 1; offset <= 2; offset++) {
            int dimension = (axis + offset) % 3;
            double aMin = Double.POSITIVE_INFINITY, aMax = Double.NEGATIVE_INFINITY;
            double bMin = Double.POSITIVE_INFINITY, bMax = Double.NEGATIVE_INFINITY;
            for (Vec3 p : a) { double v = coordinate(p, dimension); aMin = Math.min(aMin, v); aMax = Math.max(aMax, v); }
            for (Vec3 p : b) { double v = coordinate(p, dimension); bMin = Math.min(bMin, v); bMax = Math.max(bMax, v); }
            if (aMax <= bMin || bMax <= aMin) return false;
        }
        return true;
    }

    private static List<Vec3> clip(List<Vec3> polygon, Vec3 a, Vec3 b, int axis, boolean inside) {
        List<Vec3> output = new ArrayList<Vec3>();
        if (polygon.isEmpty()) return output;
        Vec3 previous = polygon.get(polygon.size() - 1);
        double previousDistance = cross(a, b, previous, axis);
        boolean previousKept = inside ? previousDistance >= 0.0 : previousDistance <= 0.0;
        for (Vec3 current : polygon) {
            double distance = cross(a, b, current, axis);
            boolean kept = inside ? distance >= 0.0 : distance <= 0.0;
            if (kept != previousKept) {
                double t = previousDistance / (previousDistance - distance);
                output.add(previous.scale(1.0 - t).add(current.scale(t)));
            }
            if (kept) output.add(current);
            previous = current; previousDistance = distance; previousKept = kept;
        }
        return output;
    }

    private static List<Vec3> project(EntityHighlightSelector.Bounds box, Vec3 camera, int axis, double fixed) {
        double[] min = {box.minX, box.minY, box.minZ}, max = {box.maxX, box.maxY, box.maxZ};
        double origin = coordinate(camera, axis), delta = fixed - origin;
        if (Math.abs(delta) < 1e-7) return Collections.emptyList();
        // Only the portion between the camera and the target plane casts a shadow.
        double near = Math.min(origin, fixed), far = Math.max(origin, fixed);
        min[axis] = Math.max(min[axis], near);
        max[axis] = Math.min(max[axis], far);
        if (max[axis] - min[axis] <= 1e-9) return Collections.emptyList();
        if (delta > 0.0) min[axis] = Math.max(min[axis], origin + 1e-7);
        else max[axis] = Math.min(max[axis], origin - 1e-7);
        List<Vec3> points = new ArrayList<Vec3>(8);
        for (int x = 0; x < 2; x++) for (int y = 0; y < 2; y++) for (int z = 0; z < 2; z++) {
            Vec3 corner = new Vec3(x == 0 ? min[0] : max[0], y == 0 ? min[1] : max[1], z == 0 ? min[2] : max[2]);
            double t = delta / (coordinate(corner, axis) - origin);
            Vec3 projected = camera.add(corner.subtract(camera).scale(t));
            points.add(projected);
        }
        return hull(points, axis);
    }

    private static List<Vec3> hull(List<Vec3> points, final int axis) {
        final int u = (axis + 1) % 3, v = (axis + 2) % 3;
        Collections.sort(points, new Comparator<Vec3>() {
            public int compare(Vec3 a, Vec3 b) {
                int cmp = Double.compare(coordinate(a, u), coordinate(b, u));
                return cmp != 0 ? cmp : Double.compare(coordinate(a, v), coordinate(b, v));
            }
        });
        List<Vec3> hull = new ArrayList<Vec3>();
        for (Vec3 point : points) {
            while (hull.size() >= 2 && cross(hull.get(hull.size()-2), hull.get(hull.size()-1), point, axis) <= 0.0)
                hull.remove(hull.size()-1);
            hull.add(point);
        }
        int lowerSize = hull.size();
        for (int i = points.size()-2; i >= 0; i--) {
            Vec3 point = points.get(i);
            while (hull.size() > lowerSize && cross(hull.get(hull.size()-2), hull.get(hull.size()-1), point, axis) <= 0.0)
                hull.remove(hull.size()-1);
            hull.add(point);
        }
        if (!hull.isEmpty()) hull.remove(hull.size()-1);
        return hull;
    }

    private static double cross(Vec3 a, Vec3 b, Vec3 c, int axis) {
        int u = (axis + 1) % 3, v = (axis + 2) % 3;
        return (coordinate(b,u)-coordinate(a,u))*(coordinate(c,v)-coordinate(a,v))
                - (coordinate(b,v)-coordinate(a,v))*(coordinate(c,u)-coordinate(a,u));
    }
    private static double coordinate(Vec3 p, int axis) { return axis == 0 ? p.x : axis == 1 ? p.y : p.z; }
    private static final class Face {
        final int axis; final double fixed;
        final List<List<Vec3>> shadows = new ArrayList<List<Vec3>>();
        Face(int axis, double fixed) { this.axis = axis; this.fixed = fixed; }
    }
}
