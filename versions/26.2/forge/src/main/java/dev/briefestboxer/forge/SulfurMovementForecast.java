package dev.briefestboxer.forge;

import dev.briefestboxer.core.Trajectory;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.cubemob.SulfurCube;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumMap;

/** Runs native knockback and travel on an unattached, silent prediction entity. */
final class SulfurMovementForecast extends SulfurCube {
    private final SulfurCube source;

    private SulfurMovementForecast(SulfurCube source, ItemStack body, AABB bounds) {
        super(EntityTypes.SULFUR_CUBE, source.level());
        this.source = source;
        setSize(source.getSize(), false);
        setPos(bounds.getCenter().x, bounds.minY, bounds.getCenter().z);
        setBoundingBox(bounds);
        setSilent(true);
        setNoGravity(source.isNoGravity());
        setSprinting(source.isSprinting());
        for (var effect : source.getActiveEffects()) addEffect(new net.minecraft.world.effect.MobEffectInstance(effect));
        getAttributes().assignAllValues(source.getAttributes());
        // Body equipment and attribute packets can arrive separately. Remove
        // previously synced archetype modifiers before applying the actual body.
        source.level().registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.SULFUR_CUBE_ARCHETYPE)
                .stream().forEach(archetype -> {
                    for (var entry : archetype.attributeModifiers()) {
                        var attribute = getAttribute(entry.attribute());
                        if (attribute != null) attribute.removeModifier(entry.modifier().id());
                    }
                });
        setItemSlot(EquipmentSlot.BODY, body.copy());
        var previous = new EnumMap<EquipmentSlot, ItemStack>(EquipmentSlot.class);
        for (EquipmentSlot slot : EquipmentSlot.VALUES) previous.put(slot, ItemStack.EMPTY);
        // Initializes the game's private archetype power and buoyancy fields,
        // and applies the same material attributes as normal equipment updates.
        collectEquipmentChanges(previous);
        setOnGround(source.onGround());
        mainSupportingBlockPos = source.mainSupportingBlockPos;
        tickCount = source.tickCount;
        setDeltaMovement(source.getDeltaMovement());
    }

    static Trajectory calculate(SulfurCube source, ItemStack body, AABB bounds,
            Player attacker, DamageSource damageSource, float damage, double bonusKnockback, int steps) {
        var forecast = new SulfurMovementForecast(source, body, bounds);
        forecast.knockback(0.4F, attacker.getX() - forecast.getX(), attacker.getZ() - forecast.getZ(),
                damageSource, damage, false);
        if (bonusKnockback > 0.0) {
            double yaw = attacker.getYRot() * ((float) Math.PI / 180.0F);
            forecast.knockback(bonusKnockback, net.minecraft.util.Mth.sin(yaw), -net.minecraft.util.Mth.cos(yaw),
                    damageSource, damage, true);
        }
        var positions = new ArrayList<dev.briefestboxer.core.Vec3>(steps + 1);
        forecast.addPosition(positions);
        int restingTicks = 0;
        for (int tick = 0; tick < steps; tick++) {
            Vec3 previousCenter = forecast.getBoundingBox().getCenter();
            forecast.tickCount++;
            // Only fluid sampling and travel are invoked. No entity tick, AI,
            // damage, block effects, spawning or world registration occurs.
            forecast.updateFluidInteraction();
            Vec3 velocity = forecast.getDeltaMovement();
            forecast.setDeltaMovement(Math.abs(velocity.x) < 0.003 ? 0.0 : velocity.x,
                    Math.abs(velocity.y) < 0.003 ? 0.0 : velocity.y,
                    Math.abs(velocity.z) < 0.003 ? 0.0 : velocity.z);
            forecast.travel(Vec3.ZERO);
            forecast.addPosition(positions);
            Vec3 remaining = forecast.getDeltaMovement();
            boolean atRest = !forecast.isInWater() && !forecast.isInLava()
                    && forecast.onGround() && remaining.y <= 0.0
                    && remaining.horizontalDistanceSqr() < 1.0E-12
                    && previousCenter.distanceToSqr(forecast.getBoundingBox().getCenter()) < 1.0E-12;
            restingTicks = atRest ? restingTicks + 1 : 0;
            if (restingTicks >= 3) {
                // Keep the requested sample count without simulating thousands
                // of identical grounded ticks. Floating cubes keep simulating.
                var endpoint = positions.getLast();
                while (positions.size() < steps + 1) positions.add(endpoint);
                break;
            }
        }
        return Trajectory.fromPositions(positions);
    }

    private void addPosition(ArrayList<dev.briefestboxer.core.Vec3> positions) {
        Vec3 center = getBoundingBox().getCenter();
        positions.add(new dev.briefestboxer.core.Vec3(center.x, center.y, center.z));
    }

    @Override protected boolean isLocalClientAuthoritative() { return true; }
    @Override public boolean canSimulateMovement() { return true; }
    @Override public boolean isEffectiveAi() { return true; }
    @Override public boolean canCollideWith(Entity other) { return other != source && super.canCollideWith(other); }
    @Override protected MovementEmission getMovementEmission() { return MovementEmission.NONE; }
    @Override public void playSound(SoundEvent event, float volume, float pitch) {}
    @Override public void gameEvent(Holder<GameEvent> event) {}
    @Override public void gameEvent(Holder<GameEvent> event, Entity entity) {}
    @Override protected void doWaterSplashEffect() {}
    @Override protected void checkFallDamage(double movementY, boolean onGround, BlockState state, BlockPos pos) {}
}
