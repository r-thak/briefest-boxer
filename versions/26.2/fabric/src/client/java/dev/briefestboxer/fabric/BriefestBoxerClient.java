package dev.briefestboxer.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
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
    // Gizmo instances with the default expiry (0) are dropped during collection.
    // Refresh these short-lived render commands every frame without leaving a trail.
    private static final int GIZMO_REFRESH_LIFETIME_MS = 50;
    private static final Map<Integer, PatchState> PATCHES = new HashMap<>();
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
            selectedEntityId = -1;
            return;
        }
        Entity viewer = client.player;
        Vec3 cameraPosition = client.gameRenderer.mainCamera().position();
        double scanRadius = BriefestBoxerConfig.aimRange();
        double scanRadiusSquared = scanRadius * scanRadius;
        double reach = Math.max(0.0, client.player.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE));
        TargetDistance nearest = null;

        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity == viewer || !entity.isAlive()
                    || entity instanceof net.minecraft.world.entity.player.Player targetPlayer && targetPlayer.isSpectator()
                    || entity.isInvisible()) continue;
            if (entity instanceof net.minecraft.world.entity.player.Player && !BriefestBoxerConfig.showAimPoints) continue;
            if (!(entity instanceof net.minecraft.world.entity.player.Player) && !BriefestBoxerConfig.showEntities) continue;
            AABB bounds = hittableBounds(entity, partialTick);
            EntityHighlightSelector.Bounds geometry = bounds(bounds);
            double distanceSquared = EntityHighlightSelector.distanceSquared(vector(cameraPosition), geometry);
            dev.briefestboxer.core.Vec3 closest = EntityHighlightSelector.closestPoint(vector(cameraPosition), geometry);
            Vec3 nearestSurfacePoint = new Vec3(closest.x, closest.y, closest.z);
            if (distanceSquared > scanRadiusSquared || !isVisible(client, viewer, cameraPosition, bounds, nearestSurfacePoint)) continue;
            TargetDistance candidate = new TargetDistance(entity, bounds, distanceSquared);
            if (nearest == null || candidate.distanceSquared < nearest.distanceSquared) nearest = candidate;
        }

        // Avoid frame-to-frame switching when two targets are almost exactly the same
        // distance from the camera. Keep the current visible target until another is
        // meaningfully closer.
        if (nearest != null && selectedEntityId >= 0 && nearest.entity.getId() != selectedEntityId) {
            for (Entity entity : client.level.entitiesForRendering()) {
                if (entity.getId() != selectedEntityId || !entity.isAlive() || entity.isInvisible()
                        || entity instanceof net.minecraft.world.entity.player.Player player && player.isSpectator()) continue;
                Entity current = entity;
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
        int selectedId = -1;
        if (nearestInReach) {
            selectedId = nearest.entity.getId();
            if (selectedEntityId != selectedId) PATCHES.clear();
            selectedEntityId = selectedId;
            EntityHighlightSelector.Bounds geometry = bounds(nearest.bounds);
            PatchState state = PATCHES.computeIfAbsent(selectedId, ignored -> new PatchState());
            state.entity = nearest.entity;
            // Bounds already use Minecraft's partial-tick entity interpolation.
            // Smoothing them again makes the patch visibly trail behind the model.
            state.bounds = geometry;
        }
        if (!nearestInReach) {
            selectedEntityId = -1;
            PATCHES.clear();
        }
        for (var iterator = PATCHES.entrySet().iterator(); iterator.hasNext();) {
            Map.Entry<Integer, PatchState> entry = iterator.next();
            PatchState state = entry.getValue();
            if (state.entity == null || state.entity.isRemoved() || !state.entity.isAlive()) {
                iterator.remove();
                continue;
            }
            int color = state.entity instanceof net.minecraft.world.entity.player.Player
                    ? BriefestBoxerConfig.selectedColor() : BriefestBoxerConfig.otherColor();
            drawReachableSurface(client, viewer, cameraPosition, state.bounds, reach,
                    color);
        }
    }

    /** Draws only the AABB surface that lies within interaction reach. */
    private static void drawReachableSurface(Minecraft client, Entity viewer, Vec3 camera,
            EntityHighlightSelector.Bounds box, double reach, int rgb) {
        List<Vec3[]> visibleTriangles = new ArrayList<>();
        List<ReachableSurface.Triangle> mesh = ReachableSurface.mesh(box,
                new dev.briefestboxer.core.Vec3(camera.x, camera.y, camera.z), reach);
        for (ReachableSurface.Triangle triangle : mesh) {
            Vec3 a = gameVec(triangle.a), b = gameVec(triangle.b), c = gameVec(triangle.c);
            Vec3 sample = a.add(b).add(c).scale(1.0 / 3.0);
            // Requiring all three vertices keeps the full triangle behind blocks
            // clipped instead of letting its visible center expose hidden edges.
            if (!isPointVisible(client, viewer, camera, sample)
                    || !isPointVisible(client, viewer, camera, a)
                    || !isPointVisible(client, viewer, camera, b)
                    || !isPointVisible(client, viewer, camera, c)) continue;
            visibleTriangles.add(new Vec3[] {a, b, c});
        }
        if (visibleTriangles.isEmpty()) return;
        int color = 0xFF000000 | (rgb & 0xFFFFFF);
        // Draw over entity geometry, but only submit triangles with a clear block ray.
        GizmoStyle style = GizmoStyle.fill(color);
        Gizmos.addGizmo((primitives, progress) -> {
            int fill = style.multipliedFill(progress);
            for (Vec3[] triangle : visibleTriangles) {
                primitives.addTriangleFan(triangle, fill);
            }
        }).setAlwaysOnTop().persistForMillis(GIZMO_REFRESH_LIFETIME_MS);
    }

    private static Vec3 gameVec(dev.briefestboxer.core.Vec3 p) { return new Vec3(p.x, p.y, p.z); }

    private static boolean isPointVisible(Minecraft client, Entity viewer, Vec3 camera, Vec3 point) {
        double pointDistance = camera.distanceToSqr(point);
        if (pointDistance < 1.0E-8) return true;
        HitResult obstruction = client.level.clip(new ClipContext(camera, point,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, viewer));
        return obstruction.getType() != HitResult.Type.BLOCK
                || camera.distanceToSqr(obstruction.getLocation()) >= pointDistance - 0.01;
    }

    private static AABB interpolatedBounds(Entity entity, float partialTick) {
        Vec3 renderOffset = entity.getPosition(partialTick).subtract(entity.position());
        return entity.getBoundingBox().move(renderOffset);
    }

    /** Includes the targeting-margin overspill Minecraft uses when ray picking entities. */
    private static AABB hittableBounds(Entity entity, float partialTick) {
        AABB bounds = interpolatedBounds(entity, partialTick);
        float pickRadius = entity.getPickRadius();
        return pickRadius > 0.0F ? bounds.inflate(pickRadius) : bounds;
    }

    private static final class PatchState {
        private EntityHighlightSelector.Bounds bounds;
        private Entity entity;
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
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, viewer));
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
                && entityHit.getEntity() instanceof SulfurCube targetedCube
                && isAdultSulfurCube(targetedCube)) {
            cube = targetedCube;
            trajectoryTargetGrace.remember(cube, now);
        } else if (client.hitResult == null || client.hitResult.getType() == HitResult.Type.MISS) {
            cube = trajectoryTargetGrace.duringMiss(now, TRAJECTORY_MISS_GRACE_NANOS);
            if (cube != null && (!cube.isAlive() || cube.isBaby() || cube.level() != client.level)) {
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
        int translucent = (0x77 << 24) | BriefestBoxerConfig.trajectoryColor();
        Gizmos.cuboid(predictedBounds, GizmoStyle.strokeAndFill(pathColor, 7.0F, translucent))
                .setAlwaysOnTop().persistForMillis(GIZMO_REFRESH_LIFETIME_MS);
        Gizmos.point(end, pathColor, 64.0F).setAlwaysOnTop().persistForMillis(GIZMO_REFRESH_LIFETIME_MS);
    }

    static boolean isAdultSulfurCube(SulfurCube cube) {
        return cube != null && !cube.isBaby() && cube.getSize() == 2;
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
        int glow = (0x12 << 24) | (rgb & 0xFFFFFF);
        int core = (0x60 << 24) | (rgb & 0xFFFFFF);
        Gizmos.addGizmo((primitives, progress) -> {
            for (Vec3[] segment : segments) {
                Vec3 from = segment[0], to = segment[1];
                Vec3 direction = to.subtract(from);
                double distance = Math.max(0.1, camera.distanceTo(from.add(to).scale(0.5)));
                // Keep the world-space ribbon narrower than the old preview. The
                // perspective scaling still prevents it from disappearing at range.
                double coreHalfWidth = distance * 0.00055;
                double glowHalfWidth = distance * 0.0015;
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
        }).setAlwaysOnTop().persistForMillis(GIZMO_REFRESH_LIFETIME_MS);
    }

    static Trajectory calculateSulfurTrajectory(Minecraft client, SulfurCube cube,
            ItemStack bodyItem, ItemStack weapon, float charge, AABB physicsBounds) {
        final SulfurCube targetCube = cube;
        List<SulfurCubeArchetype> archetypes = cube.matchingArchetypes(bodyItem);
        float baseDamage = (float) effectiveAttribute(client.player, weapon, net.minecraft.world.entity.EquipmentSlot.MAINHAND, Attributes.ATTACK_DAMAGE)
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
        // Empty cubes use LivingEntity.hurtServer, which applies armor after the
        // attack's critical modifier. An absorbed cube overrides hurtServer and
        // directly invokes its knockback path, so armor does not reduce the hit.
        if (bodyItem.isEmpty()) {
            damage = net.minecraft.world.damagesource.CombatRules.getDamageAfterAbsorb(cube,
                    (float) damage, damageSource,
                    (float) effectiveCubeAttribute(cube, bodyItem, archetypes, Attributes.ARMOR),
                    (float) effectiveCubeAttribute(cube, bodyItem, archetypes, Attributes.ARMOR_TOUGHNESS));
        }
        int knockbackLevel = EnchantmentHelper.getItemEnchantmentLevel(
                enchantmentRegistry.getOrThrow(Enchantments.KNOCKBACK), weapon);
        // LivingEntity.getKnockback applies item enchantments then halves the result.
        // Player.attack adds sprint knockback after that; SulfurCube applies 0.25 to this call.
        double extraKnockback = (effectiveAttribute(client.player, weapon, net.minecraft.world.entity.EquipmentSlot.MAINHAND, Attributes.ATTACK_KNOCKBACK)
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
                        0.4, extraKnockback, effectiveCubeAttribute(cube, bodyItem, archetypes, Attributes.KNOCKBACK_RESISTANCE), cube.onGround())
                : SulfurCubeHitModel.velocityAfterHit(vector(cube.getDeltaMovement()),
                        vector(client.player.position()), vector(client.player.getEyePosition()),
                        vector(client.player.getLookAngle()), vector(cube.position()),
                        vector(cube.getBoundingBox().getCenter()), cube.getBbHeight(),
                        modifiers.horizontalPower(), modifiers.verticalPower(), damage, extraKnockback,
                        effectiveCubeAttribute(cube, bodyItem, archetypes, Attributes.KNOCKBACK_RESISTANCE));
        double airDragModifier = effectiveCubeAttribute(cube, bodyItem, archetypes, Attributes.AIR_DRAG_MODIFIER);
        double horizontalAirDrag = clamp(1.0 - (1.0 - 0.91) * airDragModifier, 0.0, 1.0);
        // LivingEntity uses the horizontal drag on Y for omnidirectional movers;
        // absorbed Sulfur Cubes enable that movement mode.
        double verticalBaseDrag = bodyItem.isEmpty() ? 0.98 : 0.91;
        double verticalAirDrag = clamp(1.0 - (1.0 - verticalBaseDrag) * airDragModifier, 0.0, 1.0);
        double gravity = effectiveCubeAttribute(cube, bodyItem, archetypes, Attributes.GRAVITY);
        Trajectory path = Trajectory.withGroundBounces(vector(center), velocity,
                new dev.briefestboxer.core.Vec3(0.0, -gravity, 0.0), horizontalAirDrag, verticalAirDrag,
                effectiveCubeAttribute(cube, bodyItem, archetypes, Attributes.BOUNCINESS), cube.getBbHeight(),
                effectiveCubeAttribute(cube, bodyItem, archetypes, Attributes.FRICTION_MODIFIER), cube.onGround(),
                (x, y, z) -> sampleCubeSurface(client, targetCube, x, y, z),
                (position, movement) -> clipCubeMovement(client, targetCube, startingBounds, center, position, movement),
                liquidPhysics(client, targetCube, bodyItem, archetypes, startingBounds, center),
                BriefestBoxerConfig.trajectorySteps);
        return path;
    }

    private static Trajectory.StepPhysicsSampler liquidPhysics(Minecraft client, SulfurCube cube,
            ItemStack bodyItem, List<SulfurCubeArchetype> archetypes, AABB startingBounds, Vec3 startingCenter) {
        boolean buoyant = !bodyItem.isEmpty() && archetypes.stream().anyMatch(SulfurCubeArchetype::buoyant);
        double gravity = effectiveCubeAttribute(cube, bodyItem, archetypes, Attributes.GRAVITY);
        double fluidJumpThreshold = cube.getFluidJumpThreshold();
        int startingTick = cube.tickCount;
        return new Trajectory.StepPhysicsSampler() {
            @Override
            public dev.briefestboxer.core.Vec3 beforeMovement(dev.briefestboxer.core.Vec3 position,
                    dev.briefestboxer.core.Vec3 velocity, int tickIndex) {
                Vec3 worldPosition = new Vec3(position.x, position.y, position.z);
                FluidSample fluid = sampleWater(client, startingBounds, startingCenter, worldPosition);
                // The first displacement is the hit impulse itself. Entity.tick has
                // already run for the hit frame, so don't apply next-tick fluid flow
                // until subsequent predicted travel steps.
                if (tickIndex == 0 || !fluid.inWater() || fluid.current.lengthSqr() < 1.0E-12) return null;
                Vec3 current = fluid.current;
                if (velocity.x * velocity.x + velocity.z * velocity.z < 9.0E-6
                        && current.horizontalDistance() < 0.0045) {
                    current = new Vec3(current.x, 0.0, current.z).normalize().scale(0.0045);
                }
                return new dev.briefestboxer.core.Vec3(
                        velocity.x + current.x, velocity.y + current.y, velocity.z + current.z);
            }

            @Override
            public dev.briefestboxer.core.Vec3 afterMovement(dev.briefestboxer.core.Vec3 from,
                    dev.briefestboxer.core.Vec3 to, dev.briefestboxer.core.Vec3 collisionVelocity,
                    int tickIndex, boolean wasFalling) {
                // Minecraft consumes the fluid state sampled at the start of travel;
                // the post-move position only refreshes the state for the next tick.
                Vec3 worldFrom = new Vec3(from.x, from.y, from.z);
                FluidSample fluid = sampleWater(client, startingBounds, startingCenter, worldFrom);
                if (!fluid.inWater()) return null;

                Vec3 velocity = new Vec3(collisionVelocity.x, collisionVelocity.y, collisionVelocity.z)
                        .multiply(0.8, 0.8, 0.8);
                double vertical = velocity.y;
                if (gravity != 0.0 && !cube.isSprinting()) {
                    if (wasFalling && Math.abs(vertical - 0.005) > 0.003
                            && Math.abs(vertical - gravity / 16.0) < 0.003) {
                        vertical = -0.003;
                    } else {
                        vertical -= gravity / 16.0;
                    }
                }
                if (buoyant) {
                    double floatAmount = fluid.height - fluidJumpThreshold
                            + 0.2 * Math.sin((startingTick + tickIndex + 1) * 0.4);
                    if (floatAmount > 0.0) vertical += Math.min(1.0, floatAmount) * 0.04;
                }
                return vector(new Vec3(velocity.x, vertical, velocity.z));
            }
        };
    }

    private static FluidSample sampleWater(Minecraft client, AABB startingBounds,
            Vec3 startingCenter, Vec3 predictedCenter) {
        AABB box = startingBounds.move(predictedCenter.subtract(startingCenter));
        int minX = net.minecraft.util.Mth.floor(box.minX);
        int minY = net.minecraft.util.Mth.floor(box.minY);
        int minZ = net.minecraft.util.Mth.floor(box.minZ);
        int maxX = net.minecraft.util.Mth.ceil(box.maxX) - 1;
        int maxY = net.minecraft.util.Mth.ceil(box.maxY) - 1;
        int maxZ = net.minecraft.util.Mth.ceil(box.maxZ) - 1;
        Vec3 current = Vec3.ZERO;
        double fluidHeight = 0.0;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(x, y, z);
                    if (!client.level.hasChunkAt(pos)) continue;
                    var fluid = client.level.getFluidState(pos);
                    if (!fluid.is(net.minecraft.tags.FluidTags.WATER)) continue;
                    double top = y + fluid.getHeight(client.level, pos);
                    if (top < box.minY) continue;
                    // EntityFluidInteraction updates the tracked depth before it
                    // accumulates this block's current. Partial-depth scaling is
                    // therefore based on the depth reached at this point in the
                    // same X/Y/Z scan, rather than the final depth of the entity.
                    fluidHeight = Math.max(fluidHeight, top - box.minY);
                    Vec3 flow = fluid.getFlow(client.level, pos);
                    if (fluidHeight < 0.4) flow = flow.scale(fluidHeight);
                    current = current.add(flow);
                }
            }
        }
        if (fluidHeight <= 0.0) return FluidSample.DRY;
        if (current.lengthSqr() > 1.0E-5) current = current.normalize().scale(0.014);
        else current = Vec3.ZERO;
        return new FluidSample(fluidHeight, current);
    }

    private static final class FluidSample {
        private static final FluidSample DRY = new FluidSample(0.0, Vec3.ZERO);
        private final double height;
        private final Vec3 current;
        private FluidSample(double height, Vec3 current) {
            this.height = height;
            this.current = current;
        }
        private boolean inWater() { return height > 0.0; }
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
        List<VoxelShape> collisions = new java.util.ArrayList<>(
                client.level.getEntityCollisions(cube, movingBounds.expandTowards(requested)));
        Vec3 clipped = Entity.collideBoundingBox(cube, requested, movingBounds, client.level, collisions);
        boolean hitX = requested.x != clipped.x;
        boolean hitY = requested.y != clipped.y;
        boolean hitZ = requested.z != clipped.z;
        boolean hitGround = hitY && requested.y < 0.0;

        // Entity.move tries its step-up path after an ordinary collision. Omitting
        // this made previews stop at low ledges even though Sulfur Cubes can climb
        // them in-game, which was a major source of short/misdirected trajectories.
        float maxUpStep = cube.maxUpStep();
        if (maxUpStep > 0.0F && (hitGround || cube.onGround()) && (hitX || hitZ)) {
            AABB stepBounds = hitGround ? movingBounds.move(0.0, clipped.y, 0.0) : movingBounds;
            AABB stepArea = stepBounds.expandTowards(requested.x, maxUpStep, requested.z);
            if (!hitGround) stepArea = stepArea.expandTowards(0.0, -1.0E-5, 0.0);
            List<VoxelShape> stepCollisions = new java.util.ArrayList<>(
                    client.level.getEntityCollisions(cube, stepArea));
            for (VoxelShape shape : client.level.getBlockCollisions(cube, stepArea)) {
                stepCollisions.add(shape);
            }
            java.util.TreeSet<Float> stepHeights = new java.util.TreeSet<>();
            for (VoxelShape shape : stepCollisions) {
                var yCoordinates = shape.getCoords(net.minecraft.core.Direction.Axis.Y);
                for (int i = 0; i < yCoordinates.size(); i++) {
                    float height = (float) (yCoordinates.getDouble(i) - stepBounds.minY);
                    if (height >= 0.0F && height <= maxUpStep && height != (float) clipped.y) {
                        stepHeights.add(height);
                    }
                }
            }
            for (float stepHeight : stepHeights) {
                Vec3 stepped = Entity.collideBoundingBox(cube,
                        new Vec3(requested.x, stepHeight, requested.z), stepBounds, client.level, stepCollisions);
                if (stepped.horizontalDistanceSqr() > clipped.horizontalDistanceSqr()) {
                    double stepOffsetY = movingBounds.minY - stepBounds.minY;
                    return vector(stepped.subtract(0.0, stepOffsetY, 0.0));
                }
            }
        }
        return vector(clipped);
    }

    /** Includes the absorbed material's matching Sulfur Cube archetype modifiers. */
    private static double effectiveCubeAttribute(SulfurCube cube, ItemStack bodyItem,
            List<SulfurCubeArchetype> archetypes,
            net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute) {
        var current = cube.getAttribute(attribute);
        var effective = new net.minecraft.world.entity.ai.attributes.AttributeInstance(attribute, ignored -> {});
        effective.setBaseValue(current.getBaseValue());
        for (var modifier : current.getModifiers()) effective.addTransientModifier(modifier);
        var itemModifiers = bodyItem.getOrDefault(net.minecraft.core.component.DataComponents.ATTRIBUTE_MODIFIERS,
                net.minecraft.world.item.component.ItemAttributeModifiers.EMPTY);
        itemModifiers.forEach(net.minecraft.world.entity.EquipmentSlot.BODY, (modifiedAttribute, modifier) -> {
            if (modifiedAttribute.equals(attribute) && !effective.hasModifier(modifier.id())) {
                effective.addTransientModifier(modifier);
            }
        });
        for (SulfurCubeArchetype archetype : archetypes) {
            for (var entry : archetype.attributeModifiers()) {
                if (entry.attribute().equals(attribute) && !effective.hasModifier(entry.modifier().id())) {
                    effective.addTransientModifier(entry.modifier());
                }
            }
        }
        return effective.getValue();
    }

    /**
     * Uses the equipped weapon's attribute modifiers even if the server has not yet
     * sent its equipment attribute packet to the client. Existing modifiers are
     * detected by ID so normal synced attributes are never counted twice.
     */
    private static double effectiveAttribute(net.minecraft.world.entity.LivingEntity player, ItemStack weapon,
            net.minecraft.world.entity.EquipmentSlot slot,
            net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute) {
        var current = player.getAttribute(attribute);
        var effective = new net.minecraft.world.entity.ai.attributes.AttributeInstance(attribute, ignored -> {});
        effective.setBaseValue(current.getBaseValue());
        for (var modifier : current.getModifiers()) effective.addTransientModifier(modifier);
        var itemModifiers = weapon.getOrDefault(net.minecraft.core.component.DataComponents.ATTRIBUTE_MODIFIERS,
                net.minecraft.world.item.component.ItemAttributeModifiers.EMPTY);
        itemModifiers.forEach(slot, (modifiedAttribute, modifier) -> {
            if (modifiedAttribute.equals(attribute) && !effective.hasModifier(modifier.id())) {
                effective.addTransientModifier(modifier);
            }
        });
        return effective.getValue();
    }

    private record TargetDistance(Entity entity, AABB bounds, double distanceSquared) {}

    private static dev.briefestboxer.core.Vec3 vector(Vec3 point) {
        return new dev.briefestboxer.core.Vec3(point.x, point.y, point.z);
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

}
