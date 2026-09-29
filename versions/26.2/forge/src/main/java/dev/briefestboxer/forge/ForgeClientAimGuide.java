package dev.briefestboxer.forge;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.event.TickEvent;
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

import dev.briefestboxer.core.BriefestBoxerConfig;
import dev.briefestboxer.core.EntityHighlightSelector;
import dev.briefestboxer.core.EntityTargetGrace;
import dev.briefestboxer.core.ReachableSurface;
import dev.briefestboxer.core.SulfurCubeHitModel;
import dev.briefestboxer.core.Trajectory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Mod.EventBusSubscriber(modid = BriefestBoxerMod.MOD_ID, value = Dist.CLIENT)
public final class ForgeClientAimGuide {
    // Gizmo instances with the default expiry (0) are dropped during collection.
    // Refresh these short-lived render commands every frame without leaving a trail.
    private static final int GIZMO_REFRESH_LIFETIME_MS = 50;
    private static boolean configLoaded;
    private static final Map<Integer, PatchState> PATCHES = new HashMap<>();
    private static int selectedEntityId = -1;
    private static final long TRAJECTORY_MISS_GRACE_NANOS = 100_000_000L;
    private static final EntityTargetGrace<SulfurCube> trajectoryTargetGrace = new EntityTargetGrace<>();

    private ForgeClientAimGuide() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent.Post event) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return;
        if (!configLoaded) {
            BriefestBoxerConfig.load(client.gameDirectory.toPath());
            configLoaded = true;
        }
    }

    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent.Post event) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return;
        try (Gizmos.TemporaryCollection ignored = client.levelRenderer.collectPerFrameRenderThreadGizmos()) {
            float partialTick = event.timer().getGameTimeDeltaPartialTick(false);
            renderEntityHighlights(client, partialTick);
            renderSulfurPrediction(client, partialTick);
        }
    }

    private static void renderEntityHighlights(Minecraft client, float partialTick) {
        if (!BriefestBoxerConfig.showAimPoints && !BriefestBoxerConfig.showEntities) {
            PATCHES.clear();
            selectedEntityId = -1;
            return;
        }
        Entity viewer = client.player;
        Vec3 camera = client.gameRenderer.mainCamera().position();
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
            Vec3 closest = closestPoint(bounds, camera);
            double distanceSquared = EntityHighlightSelector.distanceSquared(vector(camera), bounds(bounds));
            if (distanceSquared > scanRadiusSquared || !isVisible(client, viewer, camera, bounds, closest)) continue;
            TargetDistance candidate = new TargetDistance(target, bounds, distanceSquared);
            if (nearest == null || distanceSquared < nearest.distanceSquared) nearest = candidate;
        }

        // Stabilize selection when adjacent targets have nearly equal camera distance.
        if (nearest != null && selectedEntityId >= 0 && nearest.entity.getId() != selectedEntityId) {
            for (Entity entity : client.level.entitiesForRendering()) {
                if (entity.getId() != selectedEntityId || !(entity instanceof LivingEntity current)
                        || !current.isAlive() || current.isInvisible() || current.isSpectator()) continue;
                if (current instanceof net.minecraft.world.entity.player.Player && !BriefestBoxerConfig.showAimPoints) break;
                if (!(current instanceof net.minecraft.world.entity.player.Player) && !BriefestBoxerConfig.showEntities) break;
                AABB currentBounds = hittableBounds(current, partialTick);
                EntityHighlightSelector.Bounds currentGeometry = bounds(currentBounds);
                double currentDistance = EntityHighlightSelector.distanceSquared(vector(camera), currentGeometry);
                Vec3 currentClosest = closestPoint(currentBounds, camera);
                if (currentDistance <= scanRadiusSquared && currentDistance <= reach * reach
                        && currentDistance <= nearest.distanceSquared + 0.12 * 0.12
                        && isVisible(client, viewer, camera, currentBounds, currentClosest)) {
                    nearest = new TargetDistance(current, currentBounds, currentDistance);
                }
                break;
            }
        }

        boolean inReach = nearest != null && reach > 0 && nearest.distanceSquared <= reach * reach;
        int selectedId = inReach ? nearest.entity.getId() : -1;
        if (inReach) {
            if (selectedEntityId != selectedId) PATCHES.clear();
            EntityHighlightSelector.Bounds geometry = bounds(nearest.bounds);
            selectedEntityId = selectedId;
            PatchState state = PATCHES.computeIfAbsent(selectedId, id -> new PatchState());
            state.entity = nearest.entity;
            state.bounds = geometry;
        }
        if (!inReach) {
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
            int color = BriefestBoxerConfig.selectedColor();
            drawReachableSurface(client, viewer, camera, state.bounds, reach,
                    color);
        }
    }

    private static void drawReachableSurface(Minecraft client, Entity viewer, Vec3 camera,
            EntityHighlightSelector.Bounds box, double reach, int rgb) {
        List<Vec3[]> visibleTriangles = new ArrayList<>();
        for (ReachableSurface.Triangle triangle : ReachableSurface.mesh(box, vector(camera), reach)) {
            Vec3 a = gameVec(triangle.a), b = gameVec(triangle.b), c = gameVec(triangle.c);
            Vec3 sample = a.add(b).add(c).scale(1.0 / 3.0);
            if (!isPointVisible(client, viewer, camera, sample)) continue;
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

    /** Includes the same pick-radius overspill Minecraft uses when ray picking entities. */
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

    private static Vec3 closestPoint(AABB box, Vec3 point) {
        return new Vec3(clamp(point.x, box.minX, box.maxX), clamp(point.y, box.minY, box.maxY),
                clamp(point.z, box.minZ, box.maxZ));
    }

    private static EntityHighlightSelector.Bounds bounds(AABB box) {
        return new EntityHighlightSelector.Bounds(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
    }

    private static boolean isVisible(Minecraft client, Entity viewer, Vec3 camera, AABB box, Vec3 closest) {
        Vec3 center = box.getCenter();
        for (Vec3 sample : List.of(closest, center, new Vec3(center.x, box.maxY - 0.05, center.z),
                new Vec3(center.x, box.minY + 0.05, center.z), new Vec3(box.minX + 0.02, center.y, center.z),
                new Vec3(box.maxX - 0.02, center.y, center.z))) {
            double distanceSquared = camera.distanceToSqr(sample);
            if (distanceSquared < 1.0E-8) return true;
            HitResult obstruction = client.level.clip(new ClipContext(camera, sample,
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, viewer));
            if (obstruction.getType() != HitResult.Type.BLOCK
                    || camera.distanceToSqr(obstruction.getLocation()) >= distanceSquared - 0.01) return true;
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
        if (cube == null) return;
        final SulfurCube targetCube = cube;

        ItemStack bodyItem = cube.getItemBySlot(EquipmentSlot.BODY);
        List<SulfurCubeArchetype> archetypes = cube.matchingArchetypes(bodyItem);

        // Player.attack samples its cooldown at a fixed half tick in 26.2.
        float charge = client.player.getAttackStrengthScale(0.5F);
        ItemStack weapon = client.player.getWeaponItem();
        float baseDamage = (float) effectiveAttribute(client.player, weapon,
                net.minecraft.world.entity.EquipmentSlot.MAINHAND, Attributes.ATTACK_DAMAGE)
                * (0.2F + charge * charge * 0.8F);
        var damageSource = weapon.getDamageSource(client.player);
        boolean fullStrength = charge > 0.9F;
        var enchantmentRegistry = client.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        double weaponBonus = weapon.getItem().getAttackDamageBonus(cube, baseDamage, damageSource);
        double damage = Math.max(0.0, baseDamage + weaponBonus);
        // LivingEntity applies the Sulfur Cube's absorbed-material armor before
        // forwarding damage into its hurt/knockback path. Model that reduced value.
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
        AABB renderBounds = interpolatedBounds(cube, partialTick);
        Vec3 renderCenter = renderBounds.getCenter();
        AABB startingBounds = cube.getBoundingBox();
        Vec3 center = startingBounds.getCenter();
        Vec3 renderOffset = renderCenter.subtract(center);
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
                BriefestBoxerConfig.trajectorySteps);

        int pathColor = 0xFF000000 | BriefestBoxerConfig.trajectoryColor();
        renderTrajectoryRibbon(client, path, renderOffset, BriefestBoxerConfig.trajectoryColor());
        dev.briefestboxer.core.Vec3 endPosition = path.getPositions().get(path.getPositions().size() - 1);
        Vec3 end = new Vec3(endPosition.x, endPosition.y, endPosition.z).add(renderOffset);
        AABB predictedBounds = renderBounds.move(end.subtract(renderCenter));
        int translucent = (0xCC << 24) | BriefestBoxerConfig.trajectoryColor();
        Gizmos.cuboid(predictedBounds, GizmoStyle.strokeAndFill(pathColor, 7.0F, translucent))
                .setAlwaysOnTop().persistForMillis(GIZMO_REFRESH_LIFETIME_MS);
        Gizmos.point(end, pathColor, 64.0F).setAlwaysOnTop().persistForMillis(GIZMO_REFRESH_LIFETIME_MS);
    }

    static boolean isAdultSulfurCube(SulfurCube cube) {
        return cube != null && !cube.isBaby() && cube.getSize() == 2;
    }

    /** Filled ribbon stays visibly thick when the platform clamps GL line widths. */
    private static void renderTrajectoryRibbon(Minecraft client, Trajectory path, Vec3 renderOffset, int rgb) {
        var rotation = client.gameRenderer.mainCamera().rotation();
        org.joml.Vector3f cameraRight = new org.joml.Vector3f(1.0F, 0.0F, 0.0F).rotate(rotation);
        Vec3 fallbackSide = new Vec3(cameraRight.x, cameraRight.y, cameraRight.z);
        Vec3 camera = client.gameRenderer.mainCamera().position();
        List<Vec3[]> segments = new ArrayList<>();
        for (int i = 1; i < path.getPositions().size(); i++) {
            dev.briefestboxer.core.Vec3 previous = path.getPositions().get(i - 1);
            dev.briefestboxer.core.Vec3 point = path.getPositions().get(i);
            segments.add(new Vec3[] {
                    new Vec3(previous.x, previous.y, previous.z).add(renderOffset),
                    new Vec3(point.x, point.y, point.z).add(renderOffset)});
        }
        int glow = (0x55 << 24) | (rgb & 0xFFFFFF);
        int core = (0xEE << 24) | (rgb & 0xFFFFFF);
        Gizmos.addGizmo((primitives, progress) -> {
            double glowHalfWidth = 0.09;
            double coreHalfWidth = 0.044;
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
        }).setAlwaysOnTop().persistForMillis(GIZMO_REFRESH_LIFETIME_MS);
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
        List<VoxelShape> collisions = new ArrayList<>(
                client.level.getEntityCollisions(cube, movingBounds.expandTowards(requested)));
        Vec3 clipped = Entity.collideBoundingBox(cube, requested, movingBounds, client.level, collisions);
        boolean hitX = requested.x != clipped.x;
        boolean hitY = requested.y != clipped.y;
        boolean hitZ = requested.z != clipped.z;
        boolean hitGround = hitY && requested.y < 0.0;

        // Match Entity.move's step-up path so a predicted cube doesn't stop at
        // ledges it can climb during its real knockback flight.
        float maxUpStep = cube.maxUpStep();
        if (maxUpStep > 0.0F && (hitGround || cube.onGround()) && (hitX || hitZ)) {
            AABB stepBounds = hitGround ? movingBounds.move(0.0, clipped.y, 0.0) : movingBounds;
            AABB stepArea = stepBounds.expandTowards(requested.x, maxUpStep, requested.z);
            if (!hitGround) stepArea = stepArea.expandTowards(0.0, -1.0E-5, 0.0);
            List<VoxelShape> stepCollisions = new ArrayList<>(
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

    /** Uses held weapon modifiers if the server's equipment attribute update is pending. */
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

    private record TargetDistance(LivingEntity entity, AABB bounds, double distanceSquared) {}

    private static dev.briefestboxer.core.Vec3 vector(Vec3 point) {
        return new dev.briefestboxer.core.Vec3(point.x, point.y, point.z);
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
