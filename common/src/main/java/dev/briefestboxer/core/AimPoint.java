package dev.briefestboxer.core;

/** A candidate point supplied by a version-specific player model adapter. */
public final class AimPoint {
    public final String part;
    public final Vec3 position;

    public AimPoint(String part, Vec3 position) {
        if (part == null || position == null) throw new IllegalArgumentException("part and position are required");
        this.part = part;
        this.position = position;
    }
}
