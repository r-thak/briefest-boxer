package dev.briefestboxer.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Smooth AABB surface geometry inside the attack-reach sphere. */
public final class ReachableSurface {
    // High tessellation keeps the edge of the spherical reach boundary smooth at
    // normal entity render distances and prevents the segmented/pixelated look.
    private static final int CIRCLE_SEGMENTS = 256;
    private static final double[] CIRCLE_U = new double[CIRCLE_SEGMENTS];
    private static final double[] CIRCLE_V = new double[CIRCLE_SEGMENTS];

    static {
        for (int i = 0; i < CIRCLE_SEGMENTS; i++) {
            double angle = Math.PI * 2.0 * i / CIRCLE_SEGMENTS;
            CIRCLE_U[i] = Math.cos(angle);
            CIRCLE_V[i] = Math.sin(angle);
        }
    }

    private ReachableSurface() {}

    /** Returns a smooth, hitbox-clipped triangle mesh for the reachable parts of all box faces. */
    public static List<Triangle> mesh(EntityHighlightSelector.Bounds box, Vec3 camera, double reach) {
        if (reach <= 0.0) return Collections.emptyList();
        List<Triangle> result = new ArrayList<Triangle>();
        double[] min = {box.minX, box.minY, box.minZ};
        double[] max = {box.maxX, box.maxY, box.maxZ};
        double reachSquared = reach * reach;

        for (int axis = 0; axis < 3; axis++) {
            int u = (axis + 1) % 3;
            int v = (axis + 2) % 3;
            for (int side = 0; side < 2; side++) {
                double fixed = side == 0 ? min[axis] : max[axis];
                // Only the outward-facing surface can be reached by a ray from the
                // camera. Rendering the far faces on top turns a small reachable
                // patch into a translucent-looking wash over the whole entity.
                double cameraAxis = cameraCoordinate(camera, axis);
                if (side == 0 ? cameraAxis > fixed : cameraAxis < fixed) continue;
                double normalDistance = cameraCoordinate(camera, axis) - fixed;
                double radiusSquared = reachSquared - normalDistance * normalDistance;
                if (radiusSquared <= 0.0) continue;
                double radius = Math.sqrt(radiusSquared);
                double centerU = cameraCoordinate(camera, u);
                double centerV = cameraCoordinate(camera, v);
                double nearU = Math.max(min[u], Math.min(centerU, max[u])) - centerU;
                double nearV = Math.max(min[v], Math.min(centerV, max[v])) - centerV;
                if (nearU * nearU + nearV * nearV >= radiusSquared) continue;

                // Most close targets have a wholly reachable face. Emit that
                // rectangle directly instead of constructing/clipping a circle.
                double farU = Math.max(Math.abs(min[u] - centerU), Math.abs(max[u] - centerU));
                double farV = Math.max(Math.abs(min[v] - centerV), Math.abs(max[v] - centerV));
                if (farU * farU + farV * farV <= radiusSquared) {
                    Vec3 a = point(axis, fixed, u, min[u], v, min[v]);
                    Vec3 b = point(axis, fixed, u, max[u], v, min[v]);
                    Vec3 c = point(axis, fixed, u, max[u], v, max[v]);
                    Vec3 d = point(axis, fixed, u, min[u], v, max[v]);
                    result.add(side == 0 ? new Triangle(a, c, b) : new Triangle(a, b, c));
                    result.add(side == 0 ? new Triangle(a, d, c) : new Triangle(a, c, d));
                    continue;
                }
                List<Point2> polygon = new ArrayList<Point2>(CIRCLE_SEGMENTS);
                for (int i = 0; i < CIRCLE_SEGMENTS; i++) {
                    polygon.add(new Point2(centerU + CIRCLE_U[i] * radius,
                            centerV + CIRCLE_V[i] * radius));
                }
                polygon = clip(polygon, 0, min[u], true);
                polygon = clip(polygon, 0, max[u], false);
                polygon = clip(polygon, 1, min[v], true);
                polygon = clip(polygon, 1, max[v], false);
                if (polygon.size() < 3) continue;

                Vec3 origin = point(axis, fixed, u, polygon.get(0).u, v, polygon.get(0).v);
                for (int i = 1; i + 1 < polygon.size(); i++) {
                    Vec3 current = point(axis, fixed, u, polygon.get(i).u, v, polygon.get(i).v);
                    Vec3 next = point(axis, fixed, u, polygon.get(i + 1).u, v, polygon.get(i + 1).v);
                    // The (u, v) axes always produce the positive axis normal. Reverse
                    // the min face so back-face culling keeps only the outward surface.
                    result.add(side == 0
                            ? new Triangle(origin, next, current)
                            : new Triangle(origin, current, next));
                }
            }
        }
        return result;
    }

    /** Optional relative-distance coloring for renderers on earlier game versions. */
    public static List<Triangle> coloredMesh(EntityHighlightSelector.Bounds box, Vec3 camera, double reach) {
        List<Triangle> mesh = mesh(box, camera, reach);
        return BriefestBoxerConfig.multicolorHighlights && !mesh.isEmpty()
                ? HighlightGradient.triangles(mesh, camera) : mesh;
    }

    private static List<Point2> clip(List<Point2> input, int axis, double edge, boolean keepGreater) {
        if (input.isEmpty()) return input;
        List<Point2> output = new ArrayList<Point2>();
        Point2 previous = input.get(input.size() - 1);
        boolean previousInside = inside(previous, axis, edge, keepGreater);
        for (Point2 current : input) {
            boolean currentInside = inside(current, axis, edge, keepGreater);
            if (currentInside != previousInside) output.add(intersection(previous, current, axis, edge));
            if (currentInside) output.add(current);
            previous = current;
            previousInside = currentInside;
        }
        return output;
    }

    private static boolean inside(Point2 point, int axis, double edge, boolean keepGreater) {
        double value = axis == 0 ? point.u : point.v;
        return keepGreater ? value >= edge : value <= edge;
    }

    private static Point2 intersection(Point2 a, Point2 b, int axis, double edge) {
        double aValue = axis == 0 ? a.u : a.v;
        double bValue = axis == 0 ? b.u : b.v;
        double amount = (edge - aValue) / (bValue - aValue);
        return new Point2(a.u + (b.u - a.u) * amount, a.v + (b.v - a.v) * amount);
    }

    private static Vec3 point(int axis, double fixed, int u, double uValue, int v, double vValue) {
        if (axis == 0) return new Vec3(fixed, uValue, vValue);
        if (axis == 1) return new Vec3(vValue, fixed, uValue);
        return new Vec3(uValue, vValue, fixed);
    }

    private static double cameraCoordinate(Vec3 camera, int axis) {
        return axis == 0 ? camera.x : axis == 1 ? camera.y : camera.z;
    }

    public static final class Triangle {
        public final Vec3 a, b, c;
        public final int gradientColor;
        private Triangle(Vec3 a, Vec3 b, Vec3 c) { this(a, b, c, -1); }
        Triangle(Vec3 a, Vec3 b, Vec3 c, int gradientColor) {
            this.a = a; this.b = b; this.c = c; this.gradientColor = gradientColor;
        }
    }

    private static final class Point2 {
        private final double u, v;
        private Point2(double u, double v) { this.u = u; this.v = v; }
    }
}
