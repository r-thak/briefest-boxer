package dev.briefestboxer.core;

import java.util.ArrayList;
import java.util.List;

/** Per-entity three-color distances, normalized over the submitted surface. */
public final class HighlightGradient {
    private final Vec3 camera;
    private final double near, span;
    private final double blendHalfWidth;
    private final int nearColor, middleColor, farColor;
    // At most 64 patches per input polygon; avoid unbounded work near entities.
    private static final int MAX_DEPTH = 3;
    private static final double COLOR_STEP = 1.0 / 24.0;

    private HighlightGradient(List<Vec3[]> polygons, Vec3 camera) {
        this.camera = camera;
        nearColor = BriefestBoxerConfig.multicolorNearColor();
        middleColor = BriefestBoxerConfig.multicolorMiddleColor();
        farColor = BriefestBoxerConfig.multicolorFarColor();
        blendHalfWidth = BriefestBoxerConfig.blendSmoothnessPercent() / 400.0;
        double min = Double.POSITIVE_INFINITY, max = 0.0;
        for (Vec3[] polygon : polygons) {
            min = Math.min(min, nearestSquared(polygon, camera));
            for (Vec3 p : polygon) max = Math.max(max, p.distanceSquared(camera));
        }
        near = Math.sqrt(min);
        span = Math.sqrt(max) - near;
    }

    public static List<ColoredQuad> quads(List<Vec3[]> visible, Vec3 camera) {
        HighlightGradient gradient = new HighlightGradient(visible, camera);
        List<ColoredQuad> result = new ArrayList<ColoredQuad>();
        for (Vec3[] quad : visible) gradient.splitQuad(quad, 0, result);
        return result;
    }

    public static List<ReachableSurface.Triangle> triangles(List<ReachableSurface.Triangle> mesh, Vec3 camera) {
        List<Vec3[]> polygons = new ArrayList<Vec3[]>(mesh.size());
        for (ReachableSurface.Triangle t : mesh) polygons.add(new Vec3[] {t.a, t.b, t.c});
        HighlightGradient gradient = new HighlightGradient(polygons, camera);
        List<ReachableSurface.Triangle> result = new ArrayList<ReachableSurface.Triangle>();
        for (ReachableSurface.Triangle t : mesh) gradient.splitTriangle(t.a, t.b, t.c, 0, result);
        return result;
    }

    private double fraction(double distance) {
        // Equal-distance/zero-area geometry has no closest/furthest distinction.
        return span > 1e-12 ? Math.max(0.0, Math.min(1.0, (distance - near) / span)) : 0.5;
    }

    private boolean split(Vec3[] polygon, int depth) {
        if (depth >= MAX_DEPTH) return false;
        double max = 0.0;
        for (Vec3 p : polygon) max = Math.max(max, p.distanceSquared(camera));
        // Include the interior minimum: a face's nearest point may be in its
        // center even when all four corners have the same distance.
        return fraction(Math.sqrt(max)) - fraction(Math.sqrt(nearestSquared(polygon, camera))) > COLOR_STEP;
    }

    private int color(Vec3 p) {
        double t = fraction(Math.sqrt(p.distanceSquared(camera)));
        // Give the extrema a small solid region rather than an invisible point.
        t = Math.max(0.0, Math.min(1.0, (t - COLOR_STEP) / (1.0 - 2.0 * COLOR_STEP)));
        // The slider controls transition width, not the reach/normalization or
        // mesh budget. Zero produces distinct bands; 100 blends across each half.
        return t <= 0.5 ? mix(nearColor, middleColor, transition(t, 0.25))
                : mix(middleColor, farColor, transition(t, 0.75));
    }

    private static int mix(int from, int to, double amount) {
        int rgb = 0;
        for (int shift = 0; shift <= 16; shift += 8) {
            int a = (from >>> shift) & 255, b = (to >>> shift) & 255;
            rgb |= ((int) Math.round(a + (b - a) * amount)) << shift;
        }
        return rgb;
    }

    private double transition(double t, double boundary) {
        if (blendHalfWidth == 0.0) return t >= boundary ? 1.0 : 0.0;
        double amount = Math.max(0.0, Math.min(1.0,
                (t - boundary + blendHalfWidth) / (2.0 * blendHalfWidth)));
        return amount * amount * (3.0 - 2.0 * amount);
    }

    private void splitQuad(Vec3[] q, int depth, List<ColoredQuad> result) {
        Vec3 center = q[0].add(q[1]).add(q[2]).add(q[3]).scale(0.25);
        if (!split(q, depth)) { result.add(new ColoredQuad(q, color(center))); return; }
        Vec3 ab = midpoint(q[0], q[1]), bc = midpoint(q[1], q[2]);
        Vec3 cd = midpoint(q[2], q[3]), da = midpoint(q[3], q[0]);
        splitQuad(new Vec3[] {q[0], ab, center, da}, depth + 1, result);
        splitQuad(new Vec3[] {ab, q[1], bc, center}, depth + 1, result);
        splitQuad(new Vec3[] {center, bc, q[2], cd}, depth + 1, result);
        splitQuad(new Vec3[] {da, center, cd, q[3]}, depth + 1, result);
    }

    private void splitTriangle(Vec3 a, Vec3 b, Vec3 c, int depth, List<ReachableSurface.Triangle> result) {
        if (!split(new Vec3[] {a, b, c}, depth)) {
            result.add(new ReachableSurface.Triangle(a, b, c, color(a.add(b).add(c).scale(1.0 / 3.0))));
            return;
        }
        Vec3 ab = midpoint(a, b), bc = midpoint(b, c), ca = midpoint(c, a);
        splitTriangle(a, ab, ca, depth + 1, result);
        splitTriangle(ab, b, bc, depth + 1, result);
        splitTriangle(ca, bc, c, depth + 1, result);
        splitTriangle(ab, bc, ca, depth + 1, result);
    }

    private static Vec3 midpoint(Vec3 a, Vec3 b) { return a.add(b).scale(0.5); }

    /** Exact minimum on a convex polygon, including an interior projection. */
    private static double nearestSquared(Vec3[] polygon, Vec3 camera) {
        Vec3 a = polygon[0];
        int axis = Math.abs(a.x - polygon[1].x) < 1e-9 && Math.abs(a.x - polygon[2].x) < 1e-9 ? 0
                : Math.abs(a.y - polygon[1].y) < 1e-9 && Math.abs(a.y - polygon[2].y) < 1e-9 ? 1 : 2;
        Vec3 projected = axis == 0 ? new Vec3(a.x, camera.y, camera.z)
                : axis == 1 ? new Vec3(camera.x, a.y, camera.z) : new Vec3(camera.x, camera.y, a.z);
        boolean positive = false, negative = false;
        double best = Double.POSITIVE_INFINITY;
        for (int i = 0; i < polygon.length; i++) {
            Vec3 p = polygon[i], q = polygon[(i + 1) % polygon.length];
            Vec3 edge = q.subtract(p), offset = projected.subtract(p);
            double cross = axis == 0 ? edge.y * offset.z - edge.z * offset.y
                    : axis == 1 ? edge.z * offset.x - edge.x * offset.z : edge.x * offset.y - edge.y * offset.x;
            positive |= cross > 1e-12;
            negative |= cross < -1e-12;
            double len = edge.lengthSquared();
            double t = len > 0.0 ? Math.max(0.0, Math.min(1.0, camera.subtract(p).dot(edge) / len)) : 0.0;
            best = Math.min(best, p.add(edge.scale(t)).distanceSquared(camera));
        }
        return positive && negative ? best : projected.distanceSquared(camera);
    }

    public static final class ColoredQuad {
        public final Vec3[] points;
        public final int rgb;
        private ColoredQuad(Vec3[] points, int rgb) { this.points = points; this.rgb = rgb; }
    }
}
