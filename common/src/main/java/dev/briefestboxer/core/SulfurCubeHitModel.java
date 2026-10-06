package dev.briefestboxer.core;

/** Mirrors the 26.2 Sulfur Cube's default hurt knockback and player bonus knockback. */
public final class SulfurCubeHitModel {
    private SulfurCubeHitModel() {}

    /** Mirrors vanilla LivingEntity knockback for Sulfur Cubes without a body item. */
    public static Vec3 vanillaVelocityAfterHit(Vec3 currentVelocity, Vec3 attackerPosition,
                                               Vec3 attackerLook, Vec3 cubePosition,
                                               double damageSourceStrength, double extraKnockbackStrength,
                                               double knockbackResistance, boolean cubeOnGround) {
        Vec3 velocity = livingKnockback(currentVelocity, damageSourceStrength,
                attackerPosition.x - cubePosition.x, attackerPosition.z - cubePosition.z,
                knockbackResistance, cubeOnGround);
        if (extraKnockbackStrength <= 0.0) return velocity;
        Vec3 look = attackerLook.normalized();
        double horizontalLength = Math.hypot(look.x, look.z);
        double directionX = horizontalLength > 1.0E-12 ? -look.x / horizontalLength : 0.0;
        double directionZ = horizontalLength > 1.0E-12 ? -look.z / horizontalLength : 1.0;
        velocity = livingKnockback(velocity, extraKnockbackStrength, directionX, directionZ,
                knockbackResistance, cubeOnGround);
        // Player.causeExtraKnockback damps the attacker, not the target.
        return velocity;
    }

    private static Vec3 livingKnockback(Vec3 velocity, double strength, double directionX,
                                        double directionZ, double resistance, boolean onGround) {
        double scaledStrength = strength * (1.0 - resistance);
        if (scaledStrength <= 0.0) return velocity;
        double length = Math.hypot(directionX, directionZ);
        if (length < 1.0E-12) return velocity;
        directionX /= length;
        directionZ /= length;
        return new Vec3(velocity.x * 0.5 - directionX * scaledStrength,
                onGround ? Math.min(0.4, velocity.y * 0.5 + scaledStrength) : velocity.y * 0.5,
                velocity.z * 0.5 - directionZ * scaledStrength);
    }

    public static Vec3 velocityAfterHit(Vec3 currentVelocity, Vec3 attackerPosition, Vec3 attackerEye,
                                        Vec3 attackerLook, Vec3 cubePosition, Vec3 cubeCenter,
                                        double cubeHeight, double horizontalPower, double verticalPower,
                                        double damage, double extraKnockbackPower,
                                        double knockbackResistance) {
        Vec3 attackerToTarget = cubeCenter.subtract(attackerEye).normalized();
        Vec3 aim = attackerLook.normalized();
        double angleDiff = Math.atan2(aim.x * attackerToTarget.z - aim.z * attackerToTarget.x,
                aim.x * attackerToTarget.x + aim.z * attackerToTarget.z);

        // Player.causeExtraKnockback supplies the horizontal vector opposite its look direction.
        double yawLength = Math.hypot(aim.x, aim.z);
        double yawX = yawLength > 1.0e-12 ? -aim.x / yawLength : 0.0;
        double yawZ = yawLength > 1.0e-12 ? -aim.z / yawLength : 1.0;

        Vec3 upper = cubeCenter.add(new Vec3(0.0, cubeHeight * 0.5, 0.0)).subtract(attackerEye).normalized();
        Vec3 lower = cubeCenter.add(new Vec3(0.0, -cubeHeight * 0.5, 0.0)).subtract(attackerEye).normalized();
        double verticalFactor = clampedMap(aim.y, upper.y, lower.y, -1.0, 1.0);
        double transfer = verticalFactor * 0.5;
        // The game rotates the incoming x/z direction by the hit angle separately;
        // archetype powers remain unrotated until vertical aim/position adjustments.
        double originalHorizontalPower = horizontalPower;
        double originalVerticalPower = verticalPower;
        horizontalPower *= 1.0 - transfer;
        verticalPower *= 1.0 + transfer;

        Vec3 feetDelta = cubePosition.subtract(attackerPosition);
        double verticalPositionAngle = Math.atan2(-feetDelta.y,
                Math.sqrt(feetDelta.x * feetDelta.x + feetDelta.z * feetDelta.z));
        double rotation = -verticalPositionAngle * 0.8;
        double rotatedHorizontalPower = horizontalPower * Math.cos(rotation) - verticalPower * Math.sin(rotation);
        double rotatedVerticalPower = horizontalPower * Math.sin(rotation) + verticalPower * Math.cos(rotation);
        double horizontalRatio = originalHorizontalPower > 0.0
                ? Math.abs(rotatedHorizontalPower) / originalHorizontalPower : 0.0;
        double verticalRatio = originalVerticalPower > 0.0
                ? Math.abs(rotatedVerticalPower) / originalVerticalPower : 0.0;
        double maxRatio = Math.max(horizontalRatio, verticalRatio);
        if (maxRatio > 1.0) {
            rotatedHorizontalPower /= maxRatio;
            rotatedVerticalPower /= maxRatio;
        }

        // The ordinary hurt knockback uses the hit damage strength supplied by
        // LivingEntity; SulfurCube square-roots that value before applying archetype power.
        double damageImpulse = Math.sqrt(Math.max(0.0, damage))
                * (1.0 - knockbackResistance);

        // hurtServer() invokes dealDefaultKnockback() with the damage source vector and
        // the ordinary (non-player-bonus) overload. This is present even with no item KB.
        Vec3 defaultDirection = attackerPosition.subtract(cubePosition);
        double defaultYawLength = Math.hypot(defaultDirection.x, defaultDirection.z);
        double defaultX = defaultYawLength > 1.0e-12 ? defaultDirection.x / defaultYawLength : yawX;
        double defaultZ = defaultYawLength > 1.0e-12 ? defaultDirection.z / defaultYawLength : yawZ;
        double defaultRotatedX = defaultX * Math.cos(angleDiff * 1.6) - defaultZ * Math.sin(angleDiff * 1.6);
        double defaultRotatedZ = defaultX * Math.sin(angleDiff * 1.6) + defaultZ * Math.cos(angleDiff * 1.6);
        Vec3 velocity = applyImpulse(currentVelocity, rotatedHorizontalPower, rotatedVerticalPower,
                defaultRotatedX, defaultRotatedZ, damageImpulse);

        // Player.attack() then applies enchantment/attribute/sprint knockback separately.
        // SulfurCube scales this second call by strength * 0.25 when the bonus flag is set.
        double bonusAngle = angleDiff;
        double bonusRotatedX = yawX * Math.cos(bonusAngle * 1.6) - yawZ * Math.sin(bonusAngle * 1.6);
        double bonusRotatedZ = yawX * Math.sin(bonusAngle * 1.6) + yawZ * Math.cos(bonusAngle * 1.6);
        // Player.causeExtraKnockback makes another SulfurCube knockback call with
        // the same attack damage and a separate knockback strength. SulfurCube
        // square-roots that damage, then multiplies by this strength and 0.25.
        double bonusScale = Math.sqrt(Math.max(0.0, damage)) * extraKnockbackPower * 0.25
                * (1.0 - knockbackResistance);
        if (bonusScale <= 0.0) return velocity;
        velocity = applyImpulse(velocity, rotatedHorizontalPower, rotatedVerticalPower,
                bonusRotatedX, bonusRotatedZ, bonusScale);
        // Player.causeExtraKnockback damps the attacker, not the target.
        return velocity;
    }

    private static Vec3 applyImpulse(Vec3 velocity, double horizontalPower, double verticalPower,
                                     double directionX, double directionZ, double impulseScale) {
        double horizontalImpulse = clamp(horizontalPower * impulseScale * 0.4, -128.0, 128.0);
        double verticalImpulse = clamp(verticalPower * impulseScale, -128.0, 128.0) * 1.2;
        double length = Math.hypot(directionX, directionZ);
        if (length > 1.0e-12) {
            directionX /= length;
            directionZ /= length;
        }
        return new Vec3(velocity.x - directionX * horizontalImpulse,
                velocity.y + verticalImpulse, velocity.z - directionZ * horizontalImpulse);
    }

    private static double clampedMap(double value, double fromLow, double fromHigh,
                                     double toLow, double toHigh) {
        if (Math.abs(fromHigh - fromLow) < 1.0e-12) return toLow;
        double amount = clamp((value - fromLow) / (fromHigh - fromLow), 0.0, 1.0);
        return toLow + (toHigh - toLow) * amount;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
