package dev.briefestboxer.core;

import java.util.Collection;

/** Selects the candidate closest to the player's current view ray. */
public final class AimPointSelector {
    private AimPointSelector() {}

    public static AimPoint nearestToViewRay(Vec3 eye, Vec3 look, Collection<AimPoint> candidates,
                                            double maximumDistance) {
        Vec3 direction = look.normalized();
        double maxDistanceSquared = maximumDistance * maximumDistance;
        AimPoint nearest = null;
        double nearestAngle = Double.POSITIVE_INFINITY;

        for (AimPoint candidate : candidates) {
            Vec3 offset = candidate.position.subtract(eye);
            double distanceSquared = offset.lengthSquared();
            if (distanceSquared < 1.0E-12 || distanceSquared > maxDistanceSquared) continue;

            double alignment = direction.dot(offset.scale(1.0 / Math.sqrt(distanceSquared)));
            if (alignment <= 0.0) continue;

            // Compare angular error (monotonic with angle on [0, pi]) without acos.
            double angularError = 1.0 - alignment;
            if (angularError < nearestAngle) {
                nearestAngle = angularError;
                nearest = candidate;
            }
        }
        return nearest;
    }
}
