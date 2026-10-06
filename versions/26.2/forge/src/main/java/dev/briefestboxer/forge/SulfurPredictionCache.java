package dev.briefestboxer.forge;

import dev.briefestboxer.core.BriefestBoxerConfig;
import dev.briefestboxer.core.Trajectory;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.monster.cubemob.SulfurCube;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.function.Supplier;

/** Reuses a forecast between renders of the same client tick, never its draw pose. */
final class SulfurPredictionCache {
    private Inputs inputs;
    private ItemStack body = ItemStack.EMPTY;
    private ItemStack weapon = ItemStack.EMPTY;
    private Trajectory path;

    Trajectory get(Minecraft client, SulfurCube cube, ItemStack bodyItem, ItemStack heldItem,
            float charge, AABB bounds, Supplier<Trajectory> calculate) {
        var player = client.player;
        Inputs current = new Inputs(cube, player, client.level.getGameTime(), cube.tickCount, player.tickCount,
                cube.position(), cube.getDeltaMovement(), bounds, cube.onGround(),
                player.position(), player.getEyePosition(), player.getLookAngle(), charge,
                player.fallDistance, player.onGround(), player.isSprinting(), player.onClimbable(),
                player.isInWater(), player.isMobilityRestricted(), player.isPassenger(),
                BriefestBoxerConfig.trajectorySteps,
                new PhysicsAttributes(player.getAttributeValue(Attributes.ATTACK_DAMAGE),
                        player.getAttributeValue(Attributes.ATTACK_KNOCKBACK),
                        cube.getAttributeValue(Attributes.GRAVITY), cube.getAttributeValue(Attributes.BOUNCINESS),
                        cube.getAttributeValue(Attributes.AIR_DRAG_MODIFIER), cube.getAttributeValue(Attributes.FRICTION_MODIFIER),
                        cube.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE), cube.getAttributeValue(Attributes.ARMOR),
                        cube.getAttributeValue(Attributes.ARMOR_TOUGHNESS),
                        cube.getAttributeValue(Attributes.WATER_MOVEMENT_EFFICIENCY)));
        if (path != null && current.equals(inputs)
                && ItemStack.isSameItemSameComponents(body, bodyItem)
                && ItemStack.isSameItemSameComponents(weapon, heldItem)) return path;
        path = calculate.get();
        inputs = current;
        body = bodyItem.copy();
        weapon = heldItem.copy();
        return path;
    }

    void clear() {
        inputs = null;
        path = null;
        body = ItemStack.EMPTY;
        weapon = ItemStack.EMPTY;
    }

    private record Inputs(Object cube, Object player, long worldTick, int cubeTick, int playerTick,
            Vec3 cubePosition, Vec3 velocity, AABB bounds, boolean grounded,
            Vec3 playerPosition, Vec3 eye, Vec3 look, float charge, double fallDistance,
            boolean playerGrounded, boolean sprinting, boolean climbing, boolean water,
            boolean restricted, boolean passenger, int steps, PhysicsAttributes attributes) {}

    private record PhysicsAttributes(double damage, double knockback, double gravity, double bounce,
            double drag, double friction, double resistance, double armor, double toughness, double water) {}
}
