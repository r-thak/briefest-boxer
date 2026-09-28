package dev.briefestboxer.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.SulfurCubeArchetype;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.cubemob.SulfurCube;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.briefestboxer.core.BriefestBoxerConfig;
import dev.briefestboxer.core.EntityHighlightSelector;
import dev.briefestboxer.core.EntityTargetGrace;
import dev.briefestboxer.core.ReachableSurface;
import dev.briefestboxer.core.SulfurCubeHitModel;
import dev.briefestboxer.core.Trajectory;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

public final class BriefestBoxerClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("Briefest Boxer");
    private static final Map<Integer, PatchState> PATCHES = new HashMap<>();
    private static long lastRenderNanos;
    private static int selectedEntityId = -1;
    private static final long TRAJECTORY_MISS_GRACE_NANOS = 100_000_000L;
    private static final EntityTargetGrace<SulfurCube> trajectoryTargetGrace = new EntityTargetGrace<>();

    @Override
    public void onInitializeClient() {
        LOGGER.info("Registering 26.2 entity highlights and Sulfur Cube trajectory guide");
        BriefestBoxerConfig.load(Minecraft.getInstance().gameDirectory.toPath());
        LevelRenderEvents.BEFORE_GIZMOS.register(BriefestBoxerClient::onBeforeGizmos);
    }

    private static void onBeforeGizmos(LevelRenderContext context) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return;

        float partialTick = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        renderEntityHighlights(client, partialTick);
        renderSulfurPrediction(client, partialTick);
    }

    private static void renderEntityHighlights(Minecraft client, float partialTick) {
        if (!BriefestBoxerConfig.showAimPoints && !BriefestBoxerConfig.showEntities) {
            PATCHES.clear();
            return;
        }
        Entity viewer = client.player;
        Vec3 cameraPosition = client.gameRenderer.mainCamera().position();
        double scanRadius = BriefestBoxerConfig.aimRange();
        double scanRadiusSquared = scanRadius * scanRadius;
        double reach = Math.max(0.0, client.player.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE));
        TargetDistance nearest = null;

        for (Entity entity : client.level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity target) || target == viewer || !target.isAlive()
                    || target.isSpectator() || target.isInvisible()) continue;
            if (target instanceof net.minecraft.world.entity.player.Player && !BriefestBoxerConfig.showAimPoints) continue;
            if (!(target instanceof net.minecraft.world.entity.player.Player) && !BriefestBoxerConfig.showEntities) continue;
            AABB bounds = hittableBounds(target, partialTick);
            EntityHighlightSelector.Bounds geometry = bounds(bounds);
            double distanceSquared = EntityHighlightSelector.distanceSquared(vector(cameraPosition), geometry);
            dev.briefestboxer.core.Vec3 closest = EntityHighlightSelector.closestPoint(vector(cameraPosition), geometry);
            Vec3 nearestSurfacePoint = new Vec3(closest.x, closest.y, closest.z);
            if (distanceSquared > scanRadiusSquared || !isVisible(client, viewer, cameraPosition, bounds, nearestSurfacePoint)) continue;
            TargetDistance candidate = new TargetDistance(target, bounds, distanceSquared);
            if (nearest == null || candidate.distanceSquared < nearest.distanceSquared) nearest = candidate;
        }

        // Avoid frame-to-frame switching when two targets are almost exactly the same
        // distance from the camera. Keep the current visible target until another is
        // meaningfully closer.
        if (nearest != null && selectedEntityId >= 0 && nearest.entity.getId() != selectedEntityId) {
            for (Entity entity : client.level.entitiesForRendering()) {
                if (entity.getId() != selectedEntityId || !(entity instanceof LivingEntity current)
                        || !current.isAlive() || current.isInvisible() || current.isSpectator()) continue;
                if (current instanceof net.minecraft.world.entity.player.Player && !BriefestBoxerConfig.showAimPoints) break;
                if (!(current instanceof net.minecraft.world.entity.player.Player) && !BriefestBoxerConfig.showEntities) break;
                AABB currentBounds = hittableBounds(current, partialTick);
                EntityHighlightSelector.Bounds currentGeometry = bounds(currentBounds);
                double currentDistance = EntityHighlightSelector.distanceSquared(vector(cameraPosition), currentGeometry);
                dev.briefestboxer.core.Vec3 currentClosest = EntityHighlightSelector.closestPoint(vector(cameraPosition), currentGeometry);
                if (currentDistance <= scanRadiusSquared && currentDistance <= reach * reach
                        && currentDistance <= nearest.distanceSquared + 0.12 * 0.12
                        && isVisible(client, viewer, cameraPosition, currentBounds,
                                new Vec3(currentClosest.x, currentClosest.y, currentClosest.z))) {
                    nearest = new TargetDistance(current, currentBounds, currentDistance);
                }
                break;
            }
        }

        boolean nearestInReach = nearest != null && nearest.distanceSquared <= reach * reach;
        long now = System.nanoTime();
        double dt = lastRenderNanos == 0L ? 1.0 / 60.0 : Math.min(0.1, (now - lastRenderNanos) / 1.0E9);
        lastRenderNanos = now;
        int selectedId = -1;
        if (nearestInReach) {
            selectedId = nearest.entity.getId();
            if (selectedEntityId != selectedId) PATCHES.clear();
            selectedEntityId = selectedId;
            EntityHighlightSelector.Bounds geometry = bounds(nearest.bounds);
            PatchState state = PATCHES.computeIfAbsent(selectedId, ignored -> new PatchState());
            state.bounds = geometry;
        }
        if (!nearestInReach) {
            selectedEntityId = -1;
            PATCHES.clear();
        }
        double easing = 1.0 - Math.exp(-dt / 0.18);
        if (nearestInReach) {
            PatchState state = PATCHES.get(selectedId);
            state.opacity += (1.0 - state.opacity) * easing;
            drawReachableSurface(client, viewer, cameraPosition, state.bounds, reach,
                    BriefestBoxerConfig.selectedColor(), state.opacity);
        }
    }

    /** Draws only the AABB surface that lies within interaction reach. */
    private static void drawReachableSurface(Minecraft client, Entity viewer, Vec3 camera,
            EntityHighlightSelector.Bounds box, double reach, int rgb, double opacity) {
        List<Vec3[]> visibleTriangles = new ArrayList<>();
        List<ReachableSurface.Triangle> mesh = ReachableSurface.mesh(box,
                new dev.briefestboxer.core.Vec3(camera.x, camera.y, camera.z), reach);
        for (ReachableSurface.Triangle triangle : mesh) {
            Vec3 a = gameVec(triangle.a), b = gameVec(triangle.b), c = gameVec(triangle.c);
            Vec3 sample = a.add(b).add(c).scale(1.0 / 3.0);
            if (!isPointVisible(client, viewer, camera, sample)) continue;
            visibleTriangles.add(new Vec3[] {a, b, c});
        }
        if (visibleTriangles.isEmpty()) return;
        int alpha = (int) (0xFF * opacity);
        int color = (alpha << 24) | (rgb & 0xFFFFFF);
        // Draw over entity geometry, but only submit triangles with a clear block ray.
        GizmoStyle style = GizmoStyle.fill(color);
        Gizmos.addGizmo((primitives, progress) -> {
            int fill = style.multipliedFill(progress);
            for (Vec3[] triangle : visibleTriangles) {
                primitives.addTriangleFan(triangle, fill);
            }
        }).setAlwaysOnTop();
    }

    private static Vec3 gameVec(dev.briefestboxer.core.Vec3 p) { return new Vec3(p.x, p.y, p.z); }

    private static boolean isPointVisible(Minecraft client, Entity viewer, Vec3 camera, Vec3 point) {
        double pointDistance = camera.distanceToSqr(point);
        if (pointDistance < 1.0E-8) return true;
        HitResult obstruction = client.level.clip(new ClipContext(camera, point,
                ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, viewer));
        return obstruction.getType() != HitResult.Type.BLOCK
                || camera.distanceToSqr(obstruction.getLocation()) >= pointDistance - 0.01;
    }

    private static AABB interpolatedBounds(Entity entity, float partialTick) {
        Vec3 renderOffset = entity.getPosition(partialTick).subtract(entity.position());
        return entity.getBoundingBox().move(renderOffset);
    }

    /** Includes the same pick-radius overspill Minecraft uses when ray picking entities. */
    private static AABB hittableBounds(Entity entity, float partialTick) {
        AABB bounds = interpolatedBounds(entity, partialTick);
        float pickRadius = entity.getPickRadius();
        return pickRadius > 0.0F ? bounds.inflate(pickRadius) : bounds;
    }

    private static final class PatchState {
        private EntityHighlightSelector.Bounds bounds;
        private double opacity;
        private PatchState() {}
    }

    private static EntityHighlightSelector.Bounds bounds(AABB box) {
        return new EntityHighlightSelector.Bounds(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
    }

    private static boolean isVisible(Minecraft client, Entity viewer, Vec3 camera, AABB box, Vec3 closest) {
        Vec3 center = box.getCenter();
        Vec3 upper = new Vec3(center.x, box.maxY - 0.05, center.z);
        Vec3 lower = new Vec3(center.x, box.minY + 0.05, center.z);
        Vec3 sideA = new Vec3(box.minX + 0.02, center.y, center.z);
        Vec3 sideB = new Vec3(box.maxX - 0.02, center.y, center.z);
        for (Vec3 sample : List.of(closest, center, upper, lower, sideA, sideB)) {
            double sampleDistance = camera.distanceToSqr(sample);
            if (sampleDistance < 1.0E-8) return true;
            HitResult obstruction = client.level.clip(new ClipContext(camera, sample,
                    ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, viewer));
            if (obstruction.getType() != HitResult.Type.BLOCK
                    || camera.distanceToSqr(obstruction.getLocation()) >= sampleDistance - 0.01) return true;
        }
        return false;
    }

    private static void renderSulfurPrediction(Minecraft client, float partialTick) {
        if (!BriefestBoxerConfig.showSulfurTrajectory) {
            trajectoryTargetGrace.clear();
            return;
        }
        long now = System.nanoTime();
        SulfurCube cube = null;
        if (client.hitResult instanceof EntityHitResult entityHit
                && entityHit.getEntity() instanceof SulfurCube targetedCube) {
            cube = targetedCube;
            trajectoryTargetGrace.remember(cube, now);
        } else if (client.hitResult == null || client.hitResult.getType() == HitResult.Type.MISS) {
            cube = trajectoryTargetGrace.duringMiss(now, TRAJECTORY_MISS_GRACE_NANOS);
            if (cube != null && (!cube.isAlive() || cube.level() != client.level)) {
                trajectoryTargetGrace.clear();
                cube = null;
            }
        } else {
            trajectoryTargetGrace.clear();
        }
        if (cube == null) {
            return;
        }

        ItemStack bodyItem = cube.getItemBySlot(EquipmentSlot.BODY);
        ItemStack weapon = client.player.getWeaponItem();
        // Player.attack samples its cooldown at a fixed half tick in 26.2.
        float charge = client.player.getAttackStrengthScale(0.5F);
        AABB renderBounds = interpolatedBounds(cube, partialTick);
        Vec3 renderCenter = renderBounds.getCenter();
        AABB physicsBounds = cube.getBoundingBox();
        Vec3 physicsCenter = physicsBounds.getCenter();
        Vec3 renderOffset = renderCenter.subtract(physicsCenter);
        Trajectory path = calculateSulfurTrajectory(client, cube, bodyItem, weapon, charge, physicsBounds);
        int pathColor = 0xFF000000 | BriefestBoxerConfig.trajectoryColor();
        renderTrajectoryRibbon(client, path, renderOffset, BriefestBoxerConfig.trajectoryColor());
        dev.briefestboxer.core.Vec3 endPosition = path.getPositions().get(path.getPositions().size() - 1);
        Vec3 end = new Vec3(endPosition.x, endPosition.y, endPosition.z).add(renderOffset);
        AABB predictedBounds = renderBounds.move(end.subtract(renderCenter));
        int translucent = (0xCC << 24) | BriefestBoxerConfig.trajectoryColor();
        Gizmos.cuboid(predictedBounds, GizmoStyle.strokeAndFill(pathColor, 7.0F, translucent));
        Gizmos.point(end, pathColor, 64.0F);
    }

    /** Pixel-width-independent filled ribbon; GL line widths are capped at 1px on many drivers. */
    private static void renderTrajectoryRibbon(Minecraft client, Trajectory path, Vec3 renderOffset, int rgb) {
        var rotation = client.gameRenderer.mainCamera().rotation();
        org.joml.Vector3f cameraRight = new org.joml.Vector3f(1.0F, 0.0F, 0.0F).rotate(rotation);
        Vec3 fallbackSide = new Vec3(cameraRight.x, cameraRight.y, cameraRight.z);
        Vec3 camera = client.gameRenderer.mainCamera().position();
        List<Vec3[]> segments = new ArrayList<>();
        for (int i = 1; i < path.getPositions().size(); i++) {
            dev.briefestboxer.core.Vec3 previous = path.getPositions().get(i - 1);
            dev.briefestboxer.core.Vec3 point = path.getPositions().get(i);
            Vec3 from = new Vec3(previous.x, previous.y, previous.z).add(renderOffset);
            Vec3 to = new Vec3(point.x, point.y, point.z).add(renderOffset);
            segments.add(new Vec3[] {from, to});
        }
        int glow = (0x55 << 24) | (rgb & 0xFFFFFF);
        int core = (0xEE << 24) | (rgb & 0xFFFFFF);
        Gizmos.addGizmo((primitives, progress) -> {
            double glowHalfWidth = 0.045;
            double coreHalfWidth = 0.022;
            for (Vec3[] segment : segments) {
                Vec3 from = segment[0], to = segment[1];
                Vec3 direction = to.subtract(from);
                Vec3 view = camera.subtract(from.add(to).scale(0.5));
                Vec3 side = direction.cross(view);
                if (side.lengthSqr() < 1.0E-8) side = fallbackSide;
                else side = side.normalize();
                Vec3 glowSide = side.scale(glowHalfWidth);
                Vec3 coreSide = side.scale(coreHalfWidth);
                primitives.addQuad(from.subtract(glowSide), from.add(glowSide),
                        to.add(glowSide), to.subtract(glowSide), glow);
                primitives.addQuad(from.subtract(coreSide), from.add(coreSide),
                        to.add(coreSide), to.subtract(coreSide), core);
            }
        }).setAlwaysOnTop();
    }

    private static Trajectory calculateSulfurTrajectory(Minecraft client, SulfurCube cube,
            ItemStack bodyItem, ItemStack weapon, float charge, AABB physicsBounds) {
        final SulfurCube targetCube = cube;
        List<SulfurCubeArchetype> archetypes = cube.matchingArchetypes(bodyItem);
        float baseDamage = (float) client.player.getAttributeValue(Attributes.ATTACK_DAMAGE)
                * (0.2F + charge * charge * 0.8F);
        var damageSource = weapon.getDamageSource(client.player);
        boolean fullStrength = charge > 0.9F;
        var enchantmentRegistry = client.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        double weaponBonus = weapon.getItem().getAttackDamageBonus(cube, baseDamage, damageSource);
        double damage = Math.max(0.0, baseDamage + weaponBonus);
        boolean critical = fullStrength && client.player.fallDistance > 0.0 && !client.player.onGround()
                && !client.player.onClimbable() && !client.player.isInWater()
                && !client.player.isMobilityRestricted() && !client.player.isPassenger() && !client.player.isSprinting();
        if (critical) damage *= 1.5;
        int knockbackLevel = EnchantmentHelper.getItemEnchantmentLevel(
                enchantmentRegistry.getOrThrow(Enchantments.KNOCKBACK), weapon);
        // LivingEntity.getKnockback applies item enchantments then halves the result.
        // Player.attack adds sprint knockback after that; SulfurCube applies 0.25 to this call.
        double extraKnockback = (client.player.getAttributeValue(Attributes.ATTACK_KNOCKBACK)
                + knockbackLevel) * 0.5 + (client.player.isSprinting() && fullStrength ? 0.5 : 0.0);
        // The game applies every matching archetype in registry order, so the final match supplies knockback.
        SulfurCubeArchetype.KnockbackModifiers modifiers = archetypes.isEmpty()
                ? SulfurCubeArchetype.DEFAULT_KNOCKBACK_MODIFIERS
                : archetypes.getLast().knockbackModifiers();
        AABB startingBounds = physicsBounds;
        Vec3 center = startingBounds.getCenter();
        dev.briefestboxer.core.Vec3 velocity = bodyItem.isEmpty()
                ? SulfurCubeHitModel.vanillaVelocityAfterHit(vector(cube.getDeltaMovement()),
                        vector(client.player.position()), vector(client.player.getLookAngle()), vector(cube.position()),
                        0.4, extraKnockback, cube.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE), cube.onGround())
                : SulfurCubeHitModel.velocityAfterHit(vector(cube.getDeltaMovement()),
                        vector(client.player.position()), vector(client.player.getEyePosition()),
                        vector(client.player.getLookAngle()), vector(cube.position()),
                        vector(cube.getBoundingBox().getCenter()), cube.getBbHeight(),
                        modifiers.horizontalPower(), modifiers.verticalPower(), damage, extraKnockback,
                        cube.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE));
        double airDragModifier = cube.getAttributeValue(Attributes.AIR_DRAG_MODIFIER);
        double horizontalAirDrag = clamp(1.0 - (1.0 - 0.91) * airDragModifier, 0.0, 1.0);
        double verticalAirDrag = clamp(1.0 - (1.0 - 0.98) * airDragModifier, 0.0, 1.0);
        double gravity = cube.getAttributeValue(Attributes.GRAVITY);
        Trajectory path = Trajectory.withGroundBounces(vector(center), velocity,
                new dev.briefestboxer.core.Vec3(0.0, -gravity, 0.0), horizontalAirDrag, verticalAirDrag,
                cube.getAttributeValue(Attributes.BOUNCINESS), cube.getBbHeight(),
                cube.getAttributeValue(Attributes.FRICTION_MODIFIER), cube.onGround(),
                (x, y, z) -> sampleCubeSurface(client, targetCube, x, y, z),
                (position, movement) -> clipCubeMovement(client, targetCube, startingBounds, center, position, movement),
                BriefestBoxerConfig.trajectorySteps);
        return path;
    }

    private static Trajectory.Surface sampleCubeSurface(Minecraft client, SulfurCube cube,
            double centerX, double centerY, double centerZ) {
        double radius = cube.getBbWidth() * 0.425;
        double highestSurface = Double.NEGATIVE_INFINITY;
        net.minecraft.core.BlockPos highestBlock = null;
        for (int xStep = -1; xStep <= 1; xStep++) {
            for (int zStep = -1; zStep <= 1; zStep++) {
                double x = centerX + xStep * radius;
                double z = centerZ + zStep * radius;
                Vec3 from = new Vec3(x, centerY, z);
                Vec3 to = new Vec3(x, Math.max(client.level.getMinY(), centerY - 8.0), z);
                var hit = client.level.clip(new ClipContext(from, to,
                        ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, cube));
                if (hit.getType() == HitResult.Type.BLOCK && hit.getLocation().y > highestSurface) {
                    highestSurface = hit.getLocation().y;
                    highestBlock = hit.getBlockPos();
                }
            }
        }
        if (highestBlock == null) return null;
        var state = client.level.getBlockState(highestBlock);
        boolean suppressesBounce = state.is(BlockTags.SUPPRESSES_BOUNCE);
        double blockBounce = suppressesBounce ? 0.0 : state.getBlock().getBounceRestitution();
        return new Trajectory.Surface(highestSurface, blockBounce, suppressesBounce,
                state.getBlock().getFriction());
    }

    private static dev.briefestboxer.core.Vec3 clipCubeMovement(Minecraft client, SulfurCube cube,
            AABB startingBounds, Vec3 currentCenter, dev.briefestboxer.core.Vec3 position,
            dev.briefestboxer.core.Vec3 movement) {
        Vec3 offset = new Vec3(position.x - currentCenter.x, position.y - currentCenter.y, position.z - currentCenter.z);
        AABB movingBounds = startingBounds.move(offset);
        Vec3 requested = new Vec3(movement.x, movement.y, movement.z);
        List<VoxelShape> collisions = new java.util.ArrayList<>();
        for (VoxelShape shape : client.level.getBlockCollisions(cube, movingBounds.expandTowards(requested))) {
            collisions.add(shape);
        }
        Vec3 clipped = Entity.collideBoundingBox(cube, requested, movingBounds, client.level, collisions);
        return vector(clipped);
    }

    private record TargetDistance(LivingEntity entity, AABB bounds, double distanceSquared) {}

    private static dev.briefestboxer.core.Vec3 vector(Vec3 point) {
        return new dev.briefestboxer.core.Vec3(point.x, point.y, point.z);
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

}
