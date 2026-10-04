package dev.briefestboxer.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable sampled path. Samples are in world coordinates, one per game tick. */
public final class Trajectory {
    private final List<Vec3> positions;

    private Trajectory(List<Vec3> positions) {
        this.positions = Collections.unmodifiableList(positions);
    }

    public List<Vec3> getPositions() {
        return positions;
    }

    public interface GroundSampler {
        Surface sample(double x, double y, double z);
    }

    /** Clips a desired movement delta against the world's block collision shapes. */
    public interface CollisionSampler {
        Vec3 clipMovement(Vec3 position, Vec3 movement);
    }

    /** Optional version-specific travel hooks, such as an entity's liquid movement rules. */
    public interface StepPhysicsSampler {
        /** Return null to use the ordinary acceleration/air-drag step. */
        Vec3 beforeMovement(Vec3 position, Vec3 velocity, int tickIndex);
        /** Return null to use the ordinary acceleration/air-drag step. */
        Vec3 afterMovement(Vec3 from, Vec3 to, Vec3 collisionAdjustedVelocity,
                           int tickIndex, boolean wasFalling, boolean wasOnGround);
    }

    public static final class Surface {
        public final double height;
        public final double bounciness;
        public final boolean suppressesBounce;
        public final double friction;

        public Surface(double height) {
            this(height, 0.0, false);
        }

        public Surface(double height, double bounciness) {
            this(height, bounciness, false);
        }

        public Surface(double height, double bounciness, boolean suppressesBounce) {
            this(height, bounciness, suppressesBounce, 1.0);
        }

        public Surface(double height, double bounciness, boolean suppressesBounce, double friction) {
            this.height = height;
            this.bounciness = bounciness;
            this.suppressesBounce = suppressesBounce;
            this.friction = friction;
        }
    }

    /** Integrates flight and vertical ground bounces using world surfaces supplied by the adapter. */
    public static Trajectory withGroundBounces(Vec3 position, Vec3 velocity, Vec3 acceleration,
                                               double airDrag, double bounciness, double cubeHeight,
                                               GroundSampler groundSampler, int ticks) {
        return withGroundBounces(position, velocity, acceleration, airDrag, bounciness,
                cubeHeight, groundSampler, null, ticks);
    }

    /** Integrates flight while clipping each movement step against block collision shapes. */
    public static Trajectory withGroundBounces(Vec3 position, Vec3 velocity, Vec3 acceleration,
                                               double airDrag, double bounciness, double cubeHeight,
                                               GroundSampler groundSampler, CollisionSampler collisionSampler,
                                               int ticks) {
        return withGroundBounces(position, velocity, acceleration, airDrag, airDrag, bounciness,
                cubeHeight, groundSampler, collisionSampler, ticks);
    }

    /** Integrates with distinct air drag and entity/block restitution, as used by living entities. */
    public static Trajectory withGroundBounces(Vec3 position, Vec3 velocity, Vec3 acceleration,
                                               double horizontalAirDrag, double verticalAirDrag,
                                               double bounciness, double cubeHeight,
                                               GroundSampler groundSampler, CollisionSampler collisionSampler,
                                               int ticks) {
        return withGroundBounces(position, velocity, acceleration, horizontalAirDrag, verticalAirDrag,
                bounciness, cubeHeight, 1.0, false, groundSampler, collisionSampler, ticks);
    }

    /** Integrates with entity ground friction and initial grounded state from the game entity. */
    public static Trajectory withGroundBounces(Vec3 position, Vec3 velocity, Vec3 acceleration,
                                               double horizontalAirDrag, double verticalAirDrag,
                                               double bounciness, double cubeHeight, double frictionModifier,
                                               boolean initiallyOnGround, GroundSampler groundSampler,
                                               CollisionSampler collisionSampler, int ticks) {
        return withGroundBounces(position, velocity, acceleration, horizontalAirDrag, verticalAirDrag,
                bounciness, cubeHeight, frictionModifier, initiallyOnGround, groundSampler,
                collisionSampler, null, ticks);
    }

    /** Integrates with adapter-defined per-tick travel physics in addition to collision and bounce. */
    public static Trajectory withGroundBounces(Vec3 position, Vec3 velocity, Vec3 acceleration,
                                               double horizontalAirDrag, double verticalAirDrag,
                                               double bounciness, double cubeHeight, double frictionModifier,
                                               boolean initiallyOnGround, GroundSampler groundSampler,
                                               CollisionSampler collisionSampler,
                                               StepPhysicsSampler stepPhysicsSampler, int ticks) {
        if (position == null || velocity == null || acceleration == null || groundSampler == null) {
            throw new IllegalArgumentException("motion and ground sampler are required");
        }
        if (horizontalAirDrag < 0.0 || horizontalAirDrag > 1.0
                || verticalAirDrag < 0.0 || verticalAirDrag > 1.0
                || bounciness < 0.0 || cubeHeight < 0.0 || frictionModifier < 0.0 || ticks < 0) {
            throw new IllegalArgumentException("invalid trajectory physics values");
        }

        List<Vec3> samples = new ArrayList<Vec3>(ticks + 1);
        Vec3 currentPosition = position;
        Vec3 currentVelocity = velocity;
        Surface currentSurface = initiallyOnGround
                ? groundSampler.sample(currentPosition.x, currentPosition.y, currentPosition.z) : null;
        boolean onGround = initiallyOnGround;
        samples.add(currentPosition);
        for (int tick = 0; tick < ticks; tick++) {
            // LivingEntity moves using its current velocity, then applies gravity and air drag.
            Vec3 previousPosition = currentPosition;
            Vec3 movementVelocity = stepPhysicsSampler == null ? null
                    : stepPhysicsSampler.beforeMovement(currentPosition, currentVelocity, tick);
            if (movementVelocity == null) movementVelocity = currentVelocity;
            if (movementVelocity == null) throw new IllegalArgumentException("physics sampler returned null velocity");
            Vec3 movement = collisionSampler == null ? movementVelocity
                    : collisionSampler.clipMovement(currentPosition, movementVelocity);
            if (movement == null) throw new IllegalArgumentException("collision sampler returned null movement");
            boolean hitX = Math.abs(movement.x - movementVelocity.x) > 1.0E-7;
            boolean hitY = Math.abs(movement.y - movementVelocity.y) > 1.0E-7;
            boolean hitZ = Math.abs(movement.z - movementVelocity.z) > 1.0E-7;
            currentPosition = currentPosition.add(movement);
            // A swept entity collision tells us exactly when a ground sample is
            // needed. Avoid ray-sampling the terrain twice per airborne tick; long
            // Sulfur Cube predictions otherwise multiply that cost every frame.
            Surface surface = collisionSampler == null || (hitY && movementVelocity.y < 0.0)
                    ? groundSampler.sample(currentPosition.x, currentPosition.y, currentPosition.z) : null;
            boolean crossedSampledGround = surface != null && movementVelocity.y < 0.0
                    && currentPosition.y - cubeHeight * 0.5 <= surface.height + 1.0E-7;
            // The adapter's swept AABB clip is authoritative about contact. A nearby
            // sampled block can be under only part of the cube (or be a different
            // height), so do not snap a collision-clipped path to a guessed surface.
            // Keep the sampled-height fallback for callers without a collision sampler.
            // Avoid treating a sub-milliblock vertical clip as a settled ground
            // contact. The live entity can momentarily be marked airborne after
            // a near-zero bounce, and applies ground friction on the following
            // collision tick instead.
            boolean hitGround = movementVelocity.y < 0.0
                    && ((hitY && Math.abs(movement.y - movementVelocity.y) >= 1.0E-3)
                            || (collisionSampler == null && crossedSampledGround));
            if (hitGround) {
                if (!hitY) {
                    double clippedY = surface.height + cubeHeight * 0.5 - previousPosition.y;
                    movement = new Vec3(movement.x, clippedY, movement.z);
                    hitY = true;
                }
                if (collisionSampler == null) {
                    currentPosition = new Vec3(currentPosition.x, surface.height + cubeHeight * 0.5, currentPosition.z);
                }
            }

            // Entity.restituteMovementAfterCollisions reverses blocked horizontal axes
            // using entity bounciness. A zero bounciness naturally stops the axis.
            double nextX = hitX ? -movementVelocity.x * bounciness : movementVelocity.x;
            double nextY = movementVelocity.y;
            double nextZ = hitZ ? -movementVelocity.z * bounciness : movementVelocity.z;
            if (hitY) {
                double effectiveBounce = bounciness;
                if (hitGround && surface != null) {
                    if (surface.suppressesBounce || -movementVelocity.y < -acceleration.y) {
                        effectiveBounce = 0.0;
                    } else {
                        effectiveBounce = Math.max(effectiveBounce, surface.bounciness);
                    }
                }
                double impactFraction = Math.abs(movementVelocity.y) > 1.0E-12
                        ? movement.y / movementVelocity.y : 0.0;
                double gravityCorrection = impactFraction * Math.max(0.0, -acceleration.y);
                double impactDrag = 1.0 + (verticalAirDrag - 1.0) * impactFraction;
                nextY = (gravityCorrection - movementVelocity.y) * impactDrag * effectiveBounce;
            }
            Vec3 collisionAdjustedVelocity = new Vec3(nextX, nextY, nextZ);
            Vec3 sampledVelocity = stepPhysicsSampler == null ? null
                    : stepPhysicsSampler.afterMovement(previousPosition, currentPosition,
                            collisionAdjustedVelocity, tick, movementVelocity.y <= 0.0, onGround);
            currentVelocity = sampledVelocity == null
                    ? collisionAdjustedVelocity.add(acceleration) : sampledVelocity;
            double groundFriction = onGround && currentSurface != null
                    ? clamp(1.0 - (1.0 - currentSurface.friction) * frictionModifier, 0.0, 1.0)
                    : 1.0;
            if (sampledVelocity == null) {
                currentVelocity = new Vec3(currentVelocity.x * horizontalAirDrag * groundFriction,
                        currentVelocity.y * verticalAirDrag,
                        currentVelocity.z * horizontalAirDrag * groundFriction);
                // LivingEntity/Entity stop carrying sub-milliblock horizontal
                // motion once grounded. Keeping these tiny values forever makes
                // long previews drift across the floor after the real cube settles.
                if (onGround) {
                    currentVelocity = new Vec3(
                            Math.abs(currentVelocity.x) < 0.003 ? 0.0 : currentVelocity.x,
                            currentVelocity.y,
                            Math.abs(currentVelocity.z) < 0.003 ? 0.0 : currentVelocity.z);
                }
            }
            currentSurface = surface;
            onGround = hitGround;
            samples.add(currentPosition);
        }
        return new Trajectory(samples);
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    /**
     * Integrates free flight for a version adapter that supplies the game's per-tick
     * acceleration and drag. Collision and bounce handling remain adapter-owned so
     * prediction can match the target Minecraft version and world geometry.
     */
    public static Trajectory freeFlight(Vec3 position, Vec3 velocity, Vec3 acceleration,
                                        double dragPerTick, int ticks) {
        if (position == null || velocity == null || acceleration == null) {
            throw new IllegalArgumentException("position, velocity, and acceleration are required");
        }
        if (dragPerTick < 0.0 || dragPerTick > 1.0 || ticks < 0) {
            throw new IllegalArgumentException("drag must be in [0, 1] and ticks must be non-negative");
        }

        List<Vec3> samples = new ArrayList<Vec3>(ticks + 1);
        Vec3 currentPosition = position;
        Vec3 currentVelocity = velocity;
        samples.add(currentPosition);
        for (int tick = 0; tick < ticks; tick++) {
            currentPosition = currentPosition.add(currentVelocity);
            currentVelocity = currentVelocity.add(acceleration).scale(dragPerTick);
            samples.add(currentPosition);
        }
        return new Trajectory(samples);
    }
}
