package dev.briefestboxer.core;

/** Camera-relative geometry used to select and size an entity highlight. */
public final class EntityHighlightSelector {
    private EntityHighlightSelector() {}

    public static Vec3 closestPoint(Vec3 camera, Bounds bounds) {
        return new Vec3(clamp(camera.x, bounds.minX, bounds.maxX),
                clamp(camera.y, bounds.minY, bounds.maxY), clamp(camera.z, bounds.minZ, bounds.maxZ));
    }

    public static double distanceSquared(Vec3 camera, Bounds bounds) {
        double dx = clamp(camera.x, bounds.minX, bounds.maxX) - camera.x;
        double dy = clamp(camera.y, bounds.minY, bounds.maxY) - camera.y;
        double dz = clamp(camera.z, bounds.minZ, bounds.maxZ) - camera.z;
        return dx * dx + dy * dy + dz * dz;
    }

    public static boolean isWithinReach(Vec3 camera, Bounds bounds, double reach) {
        return reach > 0.0 && distanceSquared(camera, bounds) <= reach * reach;
    }

    /** Returns 1 at the camera and falls linearly to 0 at the edge of reach. */
    public static double proximity(Vec3 camera, Bounds bounds, double reach) {
        if (!isWithinReach(camera, bounds, reach)) return 0.0;
        return clamp(1.0 - Math.sqrt(distanceSquared(camera, bounds)) / reach, 0.0, 1.0);
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    public static final class Bounds {
        public final double minX, minY, minZ, maxX, maxY, maxZ;

        public Bounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
            if (minX > maxX || minY > maxY || minZ > maxZ) throw new IllegalArgumentException("Inverted bounds");
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
        }
    }
}
