package dev.briefestboxer.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SulfurCubeHitModelTest {
    @Test
    void chargedHitLaunchesCubeAwayFromAttacker() {
        Vec3 velocity = SulfurCubeHitModel.velocityAfterHit(
                new Vec3(0.0, 0.0, 0.0),
                new Vec3(0.0, 0.0, 0.0), new Vec3(0.0, 1.6, 0.0),
                new Vec3(0.0, 0.0, 1.0), new Vec3(0.0, 0.0, 2.0),
                new Vec3(0.0, 0.5, 2.0), 1.0, 0.4125, 0.09,
                6.0, 0.0, 0.0);

        assertTrue(velocity.z > 0.0);
        assertTrue(velocity.lengthSquared() > 0.0);
    }

    @Test
    void greaterDamageProducesGreaterImpulse() {
        Vec3 weak = hitWithDamage(1.0);
        Vec3 strong = hitWithDamage(9.0);
        assertTrue(strong.lengthSquared() > weak.lengthSquared());
    }

    @Test
    void heldItemKnockbackAddsSeparateImpulseBeforeHorizontalDamping() {
        Vec3 withBonus = hitWithDamageAndKnockback(4.0, 2.0);
        // sqrt(damage) * strength * 0.25 supplies the bonus, then Player.attack
        // damps X/Z by 0.6.
        assertEquals(0.297, withBonus.z, 1.0e-6);
    }

    @Test
    void weaponKnockbackHasItsOwnSquareRootImpulseEvenWithoutDamageImpulse() {
        Vec3 velocity = hitWithDamageAndKnockback(4.0, 4.0);

        // The cube applies sqrt(damage) * knockbackStrength * 0.25 separately
        // from the ordinary damage impulse.
        assertEquals(0.396, velocity.z, 1.0e-6);
    }

    @Test
    void ordinaryDamageImpulseDoesNotUseThePlayerBonusQuarterStrengthFlag() {
        Vec3 velocity = hitWithDamageAndKnockback(4.0, 0.0);

        // The ordinary five-argument knockback call supplies false for that flag.
        assertEquals(0.33, velocity.z, 1.0e-6);
    }

    @Test
    void existingCubeMotionIsRetainedAfterHitImpulse() {
        Vec3 existing = new Vec3(0.7, 0.2, -0.4);
        Vec3 predicted = SulfurCubeHitModel.velocityAfterHit(existing,
                new Vec3(0, 0, 0), new Vec3(0, 1.6, 0), new Vec3(0, 0, 1),
                new Vec3(0, 0, 2), new Vec3(0, 0.5, 2), 1.0,
                0.0, 0.0, 0.0, 0.0, 0.0);
        assertEquals(existing.x, predicted.x, 1.0e-9);
        assertEquals(existing.y, predicted.y, 1.0e-9);
        assertEquals(existing.z, predicted.z, 1.0e-9);
    }

    @Test
    void aimingAtDifferentVerticalPartsChangesLaunchPath() {
        Vec3 upperAim = hitWithLook(new Vec3(0.0, -0.35, 1.0));
        Vec3 lowerAim = hitWithLook(new Vec3(0.0, -0.55, 1.0));

        assertNotEquals(upperAim.z, lowerAim.z, 1.0e-6);
    }

    @Test
    void matchesSourceSignedVerticalPowerTransferForHorizontalAim() {
        Vec3 velocity = SulfurCubeHitModel.velocityAfterHit(
                new Vec3(0.0, 0.0, 0.0), new Vec3(0.0, 0.0, 0.0),
                new Vec3(0.0, 1.6, 0.0), new Vec3(0.0, 0.0, 1.0),
                new Vec3(0.0, 0.0, 2.0), new Vec3(0.0, 0.5, 2.0), 1.0,
                0.4, 0.1, 4.0, 0.0, 0.0);

        // Source transfer factor clamps to -1: horizontal gets 1.5x and vertical gets 0.5x.
        assertEquals(0.0, velocity.x, 1.0e-9);
        assertEquals(0.08, velocity.y, 1.0e-6);
        assertEquals(0.32, velocity.z, 1.0e-6);
    }

    @Test
    void offAngleHitRotatesAttackDirectionButNotArchetypePowers() {
        Vec3 velocity = SulfurCubeHitModel.velocityAfterHit(
                new Vec3(0.0, 0.0, 0.0), new Vec3(0.0, 0.0, 0.0),
                new Vec3(0.0, 1.6, 0.0), new Vec3(0.0, 0.0, 1.0),
                new Vec3(1.0, 0.0, 2.0), new Vec3(1.0, 0.5, 2.0), 1.0,
                0.4, 0.1, 4.0, 0.0, 0.0);

        // The attack angle rotates the incoming horizontal direction. The vertical
        // impulse uses the unrotated archetype powers, then the game's position-angle
        // normalization caps the horizontal transfer and scales the vertical component.
        assertEquals(0.2988838891, velocity.x, 1.0e-6);
        assertEquals(0.08, velocity.y, 1.0e-6);
        assertEquals(0.114317194, velocity.z, 1.0e-6);
    }

    @Test
    void emptyCubeUsesVanillaDefaultAndExtraKnockbackCalls() {
        Vec3 velocity = SulfurCubeHitModel.vanillaVelocityAfterHit(
                new Vec3(0.0, 0.1, 0.0), new Vec3(0.0, 0.0, 0.0),
                new Vec3(0.0, 0.0, 1.0), new Vec3(0.0, 0.0, 2.0),
                0.4, 0.0, 0.0, true);

        assertEquals(0.0, velocity.x, 1.0e-9);
        assertEquals(0.4, velocity.y, 1.0e-9);
        assertEquals(0.4, velocity.z, 1.0e-9);
    }

    @Test
    void absorbedBlockUsesSulfurArchetypeLaunchInsteadOfVanillaEntityKnockback() {
        Vec3 sulfurCube = SulfurCubeHitModel.velocityAfterHit(
                new Vec3(0.0, 0.0, 0.0), new Vec3(0.0, 0.0, 0.0),
                new Vec3(0.0, 1.6, 0.0), new Vec3(0.0, 0.0, 1.0),
                new Vec3(0.0, 0.0, 2.0), new Vec3(0.0, 0.5, 2.0), 1.0,
                0.4125, 0.09, 4.0, 0.0, 0.0);
        Vec3 emptyCube = SulfurCubeHitModel.vanillaVelocityAfterHit(
                new Vec3(0.0, 0.0, 0.0), new Vec3(0.0, 0.0, 0.0),
                new Vec3(0.0, 0.0, 1.0), new Vec3(0.0, 0.0, 2.0),
                0.4, 0.0, 0.0, true);

        // Regular absorbed blocks use 0.4125/0.09 archetype powers, while an
        // empty cube delegates to vanilla LivingEntity knockback (0.4 strength).
        assertEquals(0.33, sulfurCube.z, 1.0e-6);
        assertEquals(0.072, sulfurCube.y, 1.0e-6);
        assertEquals(0.4, emptyCube.z, 1.0e-9);
        assertEquals(0.4, emptyCube.y, 1.0e-9);
    }

    @Test
    void vanillaKnockbackHalvesExistingMotionAndAppliesResistance() {
        Vec3 velocity = SulfurCubeHitModel.vanillaVelocityAfterHit(
                new Vec3(0.6, 0.2, -0.4), new Vec3(0.0, 0.0, 0.0),
                new Vec3(0.0, 0.0, 1.0), new Vec3(0.0, 0.0, 2.0),
                0.4, 0.0, 0.5, false);

        assertEquals(0.3, velocity.x, 1.0e-9);
        assertEquals(0.1, velocity.y, 1.0e-9);
        assertEquals(0.0, velocity.z, 1.0e-9);
    }

    private Vec3 hitWithDamage(double damage) {
        return hitWithDamageAndKnockback(damage, 0.0);
    }

    private Vec3 hitWithDamageAndKnockback(double damage, double knockback) {
        return SulfurCubeHitModel.velocityAfterHit(
                new Vec3(0.0, 0.0, 0.0), new Vec3(0.0, 0.0, 0.0),
                new Vec3(0.0, 1.6, 0.0), new Vec3(0.0, 0.0, 1.0),
                new Vec3(0.0, 0.0, 2.0), new Vec3(0.0, 0.5, 2.0), 1.0,
                0.4125, 0.09, damage, knockback, 0.0);
    }

    private Vec3 hitWithLook(Vec3 look) {
        return SulfurCubeHitModel.velocityAfterHit(
                new Vec3(0.0, 0.0, 0.0), new Vec3(0.0, 0.0, 0.0),
                new Vec3(0.0, 1.6, 0.0), look,
                new Vec3(0.0, 0.0, 2.0), new Vec3(0.0, 0.5, 2.0), 1.0,
                0.4125, 0.09, 6.0, 0.0, 0.0);
    }
}
