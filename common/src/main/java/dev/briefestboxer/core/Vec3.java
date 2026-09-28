package dev.briefestboxer.core;

/** Small immutable vector type kept independent of Minecraft mappings. */
public final class Vec3 {
    public final double x;
    public final double y;
    public final double z;

    public Vec3(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public Vec3 add(Vec3 other) {
        return new Vec3(x + other.x, y + other.y, z + other.z);
    }

    public Vec3 scale(double amount) {
        return new Vec3(x * amount, y * amount, z * amount);
    }

    public double dot(Vec3 other) {
        return x * other.x + y * other.y + z * other.z;
    }

    public double lengthSquared() {
        return dot(this);
    }

    public double distanceSquared(Vec3 other) {
        return subtract(other).lengthSquared();
    }

    public Vec3 subtract(Vec3 other) {
        return new Vec3(x - other.x, y - other.y, z - other.z);
    }

    public Vec3 normalized() {
        double length = Math.sqrt(lengthSquared());
        if (length < 1.0E-12) return new Vec3(0.0, 0.0, 0.0);
        return scale(1.0 / length);
    }
}
