package dev.briefestboxer.forge;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.client.event.RenderAvatarEvent;
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
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import dev.briefestboxer.core.BriefestBoxerConfig;
import dev.briefestboxer.core.EntityHighlightSelector;
import dev.briefestboxer.core.EntityTargetGrace;
import dev.briefestboxer.core.ReachableSurface;
import dev.briefestboxer.core.Trajectory;

import java.util.ArrayList;
import java.util.List;

@Mod.EventBusSubscriber(modid = BriefestBoxerMod.MOD_ID, value = Dist.CLIENT)
public final class ForgeClientAimGuide {
    private static boolean configLoaded;
    private static boolean submittedThisFrame;
    private static int selectedEntityId = -1;
    private static final SulfurPredictionCache predictionCache = new SulfurPredictionCache();
    private static final long TRAJECTORY_MISS_GRACE_NANOS = 100_000_000L;
    private static final EntityTargetGrace<SulfurCube> trajectoryTargetGrace = new EntityTargetGrace<>();

    private ForgeClientAimGuide() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent.Post event) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            selectedEntityId = -1;
            trajectoryTargetGrace.clear();
            predictionCache.clear();
            return;
        }
        if (!configLoaded) {
            BriefestBoxerConfig.load(client.gameDirectory.toPath());
            configLoaded = true;
        }
    }

    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent.Pre event) {
        submittedThisFrame = false;
    }

    @SubscribeEvent
    public static void onLivingRender(RenderLivingEvent.Pre event) {
        submitOverlay();
    }

    @SubscribeEvent
    public static void onAvatarRender(RenderAvatarEvent.Pre event) {
        submitOverlay();
    }

    private static void submitOverlay() {
        if (submittedThisFrame) return;
        submittedThisFrame = true;
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            selectedEntityId = -1;
            trajectoryTargetGrace.clear();
            predictionCache.clear();
            return;
        }
        // RenderTick.Post queued the previous pose for the next frame. Entity
        // submission has the current camera and runs before gizmo finalization.
        try (Gizmos.TemporaryCollection ignored = client.levelRenderer.collectPerFrameRenderThreadGizmos()) {
            renderEntityHighlights(client);
            renderSulfurPrediction(client);
        }
    }

    private static void renderEntityHighlights(Minecraft client) {
        if (BriefestBoxerConfig.hitboxAlpha() == 0
                || (!BriefestBoxerConfig.showAimPoints && !BriefestBoxerConfig.showEntities)) {
            selectedEntityId = -1;
            return;
        }
        Entity viewer = client.player;
        Vec3 camera = client.gameRenderer.mainCamera().position();
        dev.briefestboxer.core.Vec3 coreCamera = vector(camera);
        double reach = Math.max(0.0, client.player.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE));
        double radius = Math.min(BriefestBoxerConfig.aimRange(), reach);
        if (radius <= 0.0) { selectedEntityId = -1; return; }
        double radiusSquared = radius * radius;
        List<TargetDistance> candidates = new ArrayList<>();
        for (Entity entity : client.level.entitiesForRendering()) {
            // Dropped items (and other non-living entities) are not attack targets.
            if (!(entity instanceof net.minecraft.world.entity.LivingEntity)
                    || entity == viewer || !entity.isAlive() || entity.isSpectator() || entity.isInvisible()) continue;
            boolean player = entity instanceof net.minecraft.world.entity.player.Player;
            if (player ? !BriefestBoxerConfig.showAimPoints : !BriefestBoxerConfig.showEntities) continue;
            AABB box = hittableBounds(entity, renderPartialTick(client, entity));
            double distance = EntityHighlightSelector.distanceSquared(coreCamera, bounds(box));
            if (distance <= radiusSquared) candidates.add(new TargetDistance(entity, box, distance));
        }
        candidates.sort(java.util.Comparator.comparingDouble(candidate -> candidate.distanceSquared
                - (candidate.entity.getId() == selectedEntityId ? 0.12 * 0.12 : 0.0)));
        int shown = 0;
        int firstId = -1;
        for (TargetDistance candidate : candidates) {

            int color = candidate.entity instanceof net.minecraft.world.entity.player.Player
                    ? BriefestBoxerConfig.selectedColor() : BriefestBoxerConfig.otherColor();
            if (!drawReachableSurface(client, viewer, camera, bounds(candidate.bounds), reach, color)) continue;
            if (shown == 0) firstId = candidate.entity.getId();
            if (++shown >= BriefestBoxerConfig.highlightLimit()) break;
        }
        selectedEntityId = firstId;
    }

    private static float renderPartialTick(Minecraft client, Entity entity) {
        return client.getDeltaTracker().getGameTimeDeltaPartialTick(!client.level.tickRateManager().isEntityFrozen(entity));
    }

    /** Draws only the AABB surface that lies within interaction reach. */
    private static boolean drawReachableSurface(Minecraft client, Entity viewer, Vec3 camera,
            EntityHighlightSelector.Bounds box, double reach, int rgb) {
        List<ReachableSurface.Triangle> mesh = ReachableSurface.mesh(box, vector(camera), reach);
        if (mesh.isEmpty()) return false;
        List<EntityHighlightSelector.Bounds> blockers = new ArrayList<>();
        var context = net.minecraft.world.phys.shapes.CollisionContext.of(viewer);
        // Collect actual targeting shapes once; subtract their projected shadows
        // from each face instead of inferring holes from sparse ray samples.
        for (int x = (int) Math.floor(Math.min(camera.x, box.minX)) - 1;
                x <= Math.floor(Math.max(camera.x, box.maxX)) + 1; x++) {
            for (int y = (int) Math.floor(Math.min(camera.y, box.minY)) - 1;
                    y <= Math.floor(Math.max(camera.y, box.maxY)) + 1; y++) {
                for (int z = (int) Math.floor(Math.min(camera.z, box.minZ)) - 1;
                        z <= Math.floor(Math.max(camera.z, box.maxZ)) + 1; z++) {
                    var pos = new net.minecraft.core.BlockPos(x, y, z);
                    var state = client.level.getBlockState(pos);
                    if (state.isAir() || state.is(BlockTags.LEAVES)) continue;
                    for (AABB part : state.getShape(client.level, pos, context).toAabbs()) {
                        blockers.add(bounds(part.move(pos)));
                    }
                }
            }
        }
        List<dev.briefestboxer.core.Vec3[]> visibleQuads =
                dev.briefestboxer.core.SurfaceOcclusion.quads(mesh, vector(camera), blockers);
        if (visibleQuads.isEmpty()) return false;
        int color = (BriefestBoxerConfig.hitboxAlpha() << 24) | (rgb & 0xFFFFFF);
        // These are actual clipped face quads, not their rectangular bounding
        // slabs. Submit one command for this frame so old poses cannot overlap.
        Gizmos.addGizmo((primitives, progress) -> {
            for (dev.briefestboxer.core.Vec3[] quad : visibleQuads) {
                primitives.addQuad(gameVec(quad[0]), gameVec(quad[1]), gameVec(quad[2]), gameVec(quad[3]), color);
            }
        }).setAlwaysOnTop();
        return true;
    }

    private static Vec3 gameVec(dev.briefestboxer.core.Vec3 p) { return new Vec3(p.x, p.y, p.z); }

    static boolean isPointVisible(Minecraft client, Entity viewer, Vec3 camera, Vec3 point) {
        double pointDistance = camera.distanceToSqr(point);
        if (pointDistance < 1.0E-8) return true;
        return !hasOpaqueBlockBetween(client, viewer, camera, point, pointDistance);
    }

    static AABB interpolatedBounds(Entity entity, float partialTick) {
        // EntityRenderer interpolates xOld/yOld/zOld. getPosition() instead
        // interpolates xo/yo/zo, which can differ after network movement.
        double backstep = 1.0 - partialTick;
        return entity.getBoundingBox().move((entity.xOld - entity.getX()) * backstep,
                (entity.yOld - entity.getY()) * backstep, (entity.zOld - entity.getZ()) * backstep);
    }

    /** Includes the targeting-margin overspill Minecraft uses when ray picking entities. */
    private static AABB hittableBounds(Entity entity, float partialTick) {
        AABB bounds = interpolatedBounds(entity, partialTick);
        float pickRadius = entity.getPickRadius();
        return pickRadius > 0.0F ? bounds.inflate(pickRadius) : bounds;
    }

    private static EntityHighlightSelector.Bounds bounds(AABB box) {
        return new EntityHighlightSelector.Bounds(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
    }

    /** Leaves can have collider shapes even though their sparse foliage should not hide a target. */
    private static boolean hasOpaqueBlockBetween(Minecraft client, Entity viewer, Vec3 camera,
            Vec3 point, double pointDistance) {
        Vec3 ray = point.subtract(camera);
        double rayLength = ray.length();
        if (rayLength < 1.0E-4) return false;
        Vec3 direction = ray.scale(1.0 / rayLength);
        Vec3 start = camera;
        // Skip leaf blocks one at a time, but keep ordinary solid blocks as occluders.
        // This avoids treating the first leaf in a dense shrub canopy as a wall.
        for (int skippedLeaves = 0; skippedLeaves < 64; skippedLeaves++) {
            HitResult obstruction = client.level.clip(new ClipContext(start, point,
                    ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, viewer));
            if (obstruction.getType() != HitResult.Type.BLOCK
                    || camera.distanceToSqr(obstruction.getLocation()) >= pointDistance - 0.01) return false;
            if (!client.level.getBlockState(((net.minecraft.world.phys.BlockHitResult) obstruction).getBlockPos())
                    .is(BlockTags.LEAVES)) return true;
            var leafPos = ((net.minecraft.world.phys.BlockHitResult) obstruction).getBlockPos();
            double passed = nextBlockExitDistance(camera, direction, leafPos);
            if (passed >= rayLength) return false;
            start = camera.add(direction.scale(passed));
        }
        // A long run of foliage should not hide a hittable part. The configured
        // scan range caps this loop's ray length, and non-leaf solids still stop it.
        return false;
    }

    private static double nextBlockExitDistance(Vec3 origin, Vec3 direction,
            net.minecraft.core.BlockPos block) {
        double distance = Double.POSITIVE_INFINITY;
        if (direction.x > 1.0E-9) distance = Math.min(distance, (block.getX() + 1.0 - origin.x) / direction.x);
        else if (direction.x < -1.0E-9) distance = Math.min(distance, (block.getX() - origin.x) / direction.x);
        if (direction.y > 1.0E-9) distance = Math.min(distance, (block.getY() + 1.0 - origin.y) / direction.y);
        else if (direction.y < -1.0E-9) distance = Math.min(distance, (block.getY() - origin.y) / direction.y);
        if (direction.z > 1.0E-9) distance = Math.min(distance, (block.getZ() + 1.0 - origin.z) / direction.z);
        else if (direction.z < -1.0E-9) distance = Math.min(distance, (block.getZ() - origin.z) / direction.z);
        return Double.isFinite(distance) ? distance + 0.001 : 0.001;
    }

    private static void renderSulfurPrediction(Minecraft client) {
        if (!BriefestBoxerConfig.showSulfurTrajectory) {
            trajectoryTargetGrace.clear();
            predictionCache.clear();
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
        if (cube == null) { predictionCache.clear(); return; }
        ItemStack bodyItem = cube.getItemBySlot(EquipmentSlot.BODY);
        ItemStack weapon = client.player.getWeaponItem();
        float charge = client.player.getAttackStrengthScale(0.5F);
        AABB renderBounds = interpolatedBounds(cube, renderPartialTick(client, cube));
        Vec3 renderCenter = renderBounds.getCenter();
        AABB startingBounds = cube.getBoundingBox();
        Vec3 center = startingBounds.getCenter();
        Vec3 renderOffset = renderCenter.subtract(center);
        SulfurCube targetCube = cube;
        Trajectory path = predictionCache.get(client, cube, bodyItem, weapon, charge, startingBounds,
                () -> calculateSulfurTrajectory(client, targetCube, bodyItem, weapon, charge, startingBounds));

        int pathColor = 0xFF000000 | BriefestBoxerConfig.trajectoryColor();
        renderTrajectoryRibbon(client, path, renderOffset, BriefestBoxerConfig.trajectoryColor());
        dev.briefestboxer.core.Vec3 endPosition = path.getPositions().get(path.getPositions().size() - 1);
        Vec3 end = new Vec3(endPosition.x, endPosition.y, endPosition.z);
        AABB predictedBounds = cube.getBoundingBox().move(end.subtract(cube.getBoundingBox().getCenter()));
        int translucent = (0x44 << 24) | BriefestBoxerConfig.trajectoryColor();
        Gizmos.cuboid(predictedBounds, GizmoStyle.strokeAndFill(pathColor, 2.0F, translucent))
                .setAlwaysOnTop();
        Gizmos.point(end, pathColor, 12.0F).setAlwaysOnTop();
    }

    static boolean isAdultSulfurCube(SulfurCube cube) {
        return cube != null && !cube.isBaby() && cube.getSize() == 2;
    }

    /** Use the game's screen-width line primitive so the path remains visible. */
    private static void renderTrajectoryRibbon(Minecraft client, Trajectory path, Vec3 renderOffset, int rgb) {
        int color = (0x99 << 24) | (rgb & 0xFFFFFF);
        Gizmos.addGizmo((primitives, progress) -> {
            var positions = path.getPositions();
            // Smooth the starting point with the visible cube, while keeping
            // future collision/landing coordinates anchored to the world.
            Vec3 from = gameVec(positions.getFirst()).add(renderOffset);
            for (int i = 1; i < positions.size(); i++) {
                Vec3 to = gameVec(positions.get(i));
                if (from.distanceToSqr(to) > 1.0E-12) primitives.addLine(from, to, color, 2.0F);
                from = to;
            }
        }).setAlwaysOnTop();
    }

    private static Trajectory calculateSulfurTrajectory(Minecraft client, SulfurCube cube,
            ItemStack bodyItem, ItemStack weapon, float charge, AABB physicsBounds) {
        final SulfurCube targetCube = cube;
        List<SulfurCubeArchetype> archetypes = cube.matchingArchetypes(bodyItem);
        float unscaledDamage = (float) effectiveAttribute(client.player, weapon, net.minecraft.world.entity.EquipmentSlot.MAINHAND, Attributes.ATTACK_DAMAGE);
        float enchantmentDamage = (enchantmentValue(weapon,
                net.minecraft.world.item.enchantment.EnchantmentEffectComponents.DAMAGE, unscaledDamage)
                - unscaledDamage) * charge;
        float baseDamage = unscaledDamage * (0.2F + charge * charge * 0.8F);
        var damageSource = weapon.getDamageSource(client.player);
        boolean fullStrength = charge > 0.9F;
        double weaponBonus = weapon.getItem().getAttackDamageBonus(cube, baseDamage, damageSource);
        double damage = Math.max(0.0, baseDamage + weaponBonus);
        boolean critical = fullStrength && client.player.fallDistance > 0.0 && !client.player.onGround()
                && !client.player.onClimbable() && !client.player.isInWater()
                && !client.player.isMobilityRestricted() && !client.player.isPassenger() && !client.player.isSprinting();
        if (weapon.is(net.minecraft.world.item.Items.MACE)
                && net.minecraft.world.item.MaceItem.canSmashAttack(client.player)) {
            // MaceItem only adds Density on ServerLevel; reproduce the synced
            // fall-distance effect here for the client forecast.
            weaponBonus += enchantmentValue(weapon,
                    net.minecraft.world.item.enchantment.EnchantmentEffectComponents.SMASH_DAMAGE_PER_FALLEN_BLOCK,
                    0.0F) * client.player.fallDistance;
            damage = Math.max(0.0, baseDamage + weaponBonus);
        }
        if (critical) damage *= 1.5;
        damage = Math.max(0.0, damage + enchantmentDamage);
        // Empty cubes use LivingEntity.hurtServer, which applies armor after the
        // attack's critical modifier. An absorbed cube overrides hurtServer and
        // directly invokes its knockback path, so armor does not reduce the hit.
        if (bodyItem.isEmpty()) {
            damage = net.minecraft.world.damagesource.CombatRules.getDamageAfterAbsorb(cube,
                    (float) damage, damageSource,
                    (float) effectiveCubeAttribute(cube, bodyItem, archetypes, Attributes.ARMOR),
                    (float) effectiveCubeAttribute(cube, bodyItem, archetypes, Attributes.ARMOR_TOUGHNESS));
        }
        // Apply the synced enchantment value effects in the same order as
        // LivingEntity.getKnockback, then add Player.attack's sprint bonus.
        double extraKnockback = enchantmentValue(weapon,
                net.minecraft.world.item.enchantment.EnchantmentEffectComponents.KNOCKBACK,
                (float) effectiveAttribute(client.player, weapon, net.minecraft.world.entity.EquipmentSlot.MAINHAND, Attributes.ATTACK_KNOCKBACK))
                * 0.5 + (client.player.isSprinting() && fullStrength ? 0.5 : 0.0);
        return SulfurMovementForecast.calculate(cube, bodyItem, physicsBounds, client.player,
                damageSource, (float) damage, extraKnockback, BriefestBoxerConfig.trajectorySteps);
    }

    /** Client-safe value effects from the server's synchronized enchantment registry. */
    private static float enchantmentValue(ItemStack weapon,
            net.minecraft.core.component.DataComponentType<java.util.List<net.minecraft.world.item.enchantment.ConditionalEffect<net.minecraft.world.item.enchantment.effects.EnchantmentValueEffect>>> component,
            float value) {
        var enchantments = weapon.getOrDefault(net.minecraft.core.component.DataComponents.ENCHANTMENTS,
                net.minecraft.world.item.enchantment.ItemEnchantments.EMPTY);
        var random = net.minecraft.util.RandomSource.create(0L);
        for (var entry : enchantments.entrySet()) {
            for (var effect : entry.getKey().value().getEffects(component)) {
                // Vanilla Sharpness, Knockback and Density are unconditional.
                // Target-filtered effects such as Smite do not apply to sulfur
                // cubes. Server-only loot predicates cannot be evaluated here.
                if (effect.requirements().isEmpty()) {
                    value = effect.effect().process(entry.getIntValue(), random, value);
                }
            }
        }
        return value;
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
            if (modifiedAttribute.equals(attribute)) {
                effective.addOrUpdateTransientModifier(modifier);
            }
        });
        for (SulfurCubeArchetype archetype : archetypes) {
            for (var entry : archetype.attributeModifiers()) {
                if (entry.attribute().equals(attribute)) {
                    effective.addOrUpdateTransientModifier(entry.modifier());
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
        if (slot == net.minecraft.world.entity.EquipmentSlot.MAINHAND) {
            effective.removeModifier(net.minecraft.world.item.Item.BASE_ATTACK_DAMAGE_ID);
            effective.removeModifier(net.minecraft.world.item.Item.BASE_ATTACK_SPEED_ID);
        }
        var itemModifiers = weapon.getOrDefault(net.minecraft.core.component.DataComponents.ATTRIBUTE_MODIFIERS,
                net.minecraft.world.item.component.ItemAttributeModifiers.EMPTY);
        itemModifiers.forEach(slot, (modifiedAttribute, modifier) -> {
            if (modifiedAttribute.equals(attribute)) {
                effective.addOrUpdateTransientModifier(modifier);
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
