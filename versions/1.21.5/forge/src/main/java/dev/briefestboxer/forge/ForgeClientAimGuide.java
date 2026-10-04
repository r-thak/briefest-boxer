package dev.briefestboxer.forge;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.briefestboxer.core.BriefestBoxerConfig;
import dev.briefestboxer.core.EntityHighlightSelector;
import dev.briefestboxer.core.ReachableSurface;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

@Mod.EventBusSubscriber(modid = BriefestBoxerMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ForgeClientAimGuide {
    private static final Map<Integer, PatchState> PATCHES = new HashMap<>();
    private static long lastFrameNanos;
    private static boolean configLoaded;

    private ForgeClientAimGuide() {}

    @SubscribeEvent
    public static void onRenderLivingPost(RenderLivingEvent.Post<?, ?, ?> event) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) { PATCHES.clear(); return; }
        if (!configLoaded) {
            BriefestBoxerConfig.load(client.gameDirectory.toPath());
            configLoaded = true;
        }
        renderEntityHighlight(client, event);
    }

    private static void renderEntityHighlight(Minecraft client, RenderLivingEvent.Post<?, ?, ?> event) {
        if (!BriefestBoxerConfig.showAimPoints && !BriefestBoxerConfig.showEntities) {
            PATCHES.clear();
            return;
        }
        EntityRenderState renderState = event.getState();
        if (renderState.isInvisible) return;
        Entity current = findLiveEntity(client, renderState);
        if (!(current instanceof LivingEntity rendered) || rendered == client.player
                || !rendered.isAlive() || rendered.isInvisible() || rendered.isSpectator()) return;

        Vec3 camera = client.gameRenderer.getMainCamera().getPosition();
        double scanRadius = BriefestBoxerConfig.aimRange();
        double reach = Math.max(0.0, client.player.getAttributeValue(
                net.minecraft.world.entity.ai.attributes.Attributes.ENTITY_INTERACTION_RANGE));
        Target nearest = nearestTarget(client, camera, scanRadius);

        long now = System.nanoTime();
        double dt = lastFrameNanos == 0 ? 1.0 / 60.0 : Math.min(0.1, (now - lastFrameNanos) / 1.0E9);
        lastFrameNanos = now;
        double ease = 1.0 - Math.exp(-dt / 0.09);
        int selectedId = nearest != null && nearest.distanceSquared <= reach * reach ? nearest.entity.getId() : -1;
        for (Iterator<Map.Entry<Integer, PatchState>> iterator = PATCHES.entrySet().iterator(); iterator.hasNext();) {
            Map.Entry<Integer, PatchState> entry = iterator.next();
            PatchState patch = entry.getValue();
            patch.targetScale = entry.getKey() == selectedId ? 1.0 : 0.0;
            patch.scale += (patch.targetScale - patch.scale) * ease;
            if (patch.scale < 0.002) iterator.remove();
        }
        if (selectedId < 0 || nearest.entity != rendered) return;

        PatchState patch = PATCHES.computeIfAbsent(selectedId, ignored -> new PatchState());
        patch.playerTarget = rendered instanceof AbstractClientPlayer;
        AABB renderBounds = nearest.bounds.move(renderState.x - rendered.getX(),
                renderState.y - rendered.getY(), renderState.z - rendered.getZ());
        patch.bounds = bounds(renderBounds);
        drawReachableSurface(event.getPoseStack(), event.getMultiBufferSource().getBuffer(RenderType.debugQuads()),
                camera, renderState, patch.bounds, reach, client.player,
                patch.playerTarget ? BriefestBoxerConfig.selectedColor() : BriefestBoxerConfig.otherColor(),
                Math.max(patch.scale, 0.08));
    }

    private static Target nearestTarget(Minecraft client, Vec3 camera, double scanRadius) {
        double scanRadiusSquared = scanRadius * scanRadius;
        Target nearest = null;
        for (Entity entity : client.level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity target) || target == client.player || !target.isAlive()
                    || target.isSpectator() || target.isInvisible()) continue;
            if (target instanceof AbstractClientPlayer && !BriefestBoxerConfig.showAimPoints) continue;
            if (!(target instanceof AbstractClientPlayer) && !BriefestBoxerConfig.showEntities) continue;
            AABB box = hittableBounds(target);
            Vec3 closest = closest(camera, box);
            double d2 = camera.distanceToSqr(closest);
            if (d2 > scanRadiusSquared || !visible(client, client.player, camera, closest)) continue;
            if (nearest == null || d2 < nearest.distanceSquared) nearest = new Target(target, box, d2);
        }
        return nearest;
    }

    private static Entity findLiveEntity(Minecraft client, EntityRenderState state) {
        Entity nearest = null;
        double nearestDistanceSquared = 1.0;
        for (Entity entity : client.level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity)) continue;
            double dx = state.x - entity.getX();
            double dy = state.y - entity.getY();
            double dz = state.z - entity.getZ();
            double distanceSquared = dx * dx + dy * dy + dz * dz;
            if (distanceSquared < nearestDistanceSquared) {
                nearest = entity;
                nearestDistanceSquared = distanceSquared;
            }
        }
        return nearest;
    }

    private static void drawReachableSurface(PoseStack matrices, VertexConsumer out, Vec3 camera,
            EntityRenderState renderState, EntityHighlightSelector.Bounds box, double reach, Entity viewer,
            int rgb, double opacity) {
        int alpha = (int) (160 * opacity), r = rgb >>> 16 & 255, g = rgb >>> 8 & 255, b = rgb & 255;
        for (ReachableSurface.Triangle triangle : ReachableSurface.mesh(box, vector(camera), reach)) {
            Vec3 a = gameVec(triangle.a), pointB = gameVec(triangle.b), c = gameVec(triangle.c);
            Vec3 sample = new Vec3((a.x + pointB.x + c.x) / 3.0,
                    (a.y + pointB.y + c.y) / 3.0, (a.z + pointB.z + c.z) / 3.0);
            if (!visible(Minecraft.getInstance(), viewer, camera, sample)) continue;
            vertex(out, matrices, a, renderState, r, g, b, alpha);
            vertex(out, matrices, pointB, renderState, r, g, b, alpha);
            vertex(out, matrices, c, renderState, r, g, b, alpha);
            vertex(out, matrices, c, renderState, r, g, b, alpha);
        }
    }

    private static void vertex(VertexConsumer out, PoseStack matrices, Vec3 point,
            EntityRenderState renderState, int r, int g, int b, int a) {
        out.addVertex(matrices.last(), (float) (point.x - renderState.x),
                (float) (point.y - renderState.y), (float) (point.z - renderState.z)).setColor(r, g, b, a);
    }

    private static AABB hittableBounds(Entity entity) {
        double margin = entity.getPickRadius();
        return margin > 0.0 ? entity.getBoundingBox().inflate(margin) : entity.getBoundingBox();
    }

    private static Vec3 gameVec(dev.briefestboxer.core.Vec3 p) { return new Vec3(p.x, p.y, p.z); }

    private static boolean visible(Minecraft client, Entity viewer, Vec3 camera, Vec3 sample) {
        double d2 = camera.distanceToSqr(sample);
        if (d2 < 1.0E-8) return true;
        BlockHitResult hit = client.level.clip(new ClipContext(camera, sample,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, viewer));
        return hit.getType() != HitResult.Type.BLOCK || camera.distanceToSqr(hit.getLocation()) >= d2 - 0.01;
    }

    private static Vec3 closest(Vec3 point, AABB box) {
        return new Vec3(clamp(point.x, box.minX, box.maxX), clamp(point.y, box.minY, box.maxY),
                clamp(point.z, box.minZ, box.maxZ));
    }

    private static EntityHighlightSelector.Bounds bounds(AABB box) {
        return new EntityHighlightSelector.Bounds(box.minX, box.minY, box.minZ,
                box.maxX, box.maxY, box.maxZ);
    }

    private static dev.briefestboxer.core.Vec3 vector(Vec3 point) {
        return new dev.briefestboxer.core.Vec3(point.x, point.y, point.z);
    }
    private static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }

    private static final class PatchState {
        private EntityHighlightSelector.Bounds bounds;
        private double scale, targetScale;
        private boolean playerTarget;
    }

    private static final class Target {
        private final LivingEntity entity;
        private final AABB bounds;
        private final double distanceSquared;

        private Target(LivingEntity entity, AABB bounds, double distanceSquared) {
            this.entity = entity;
            this.bounds = bounds;
            this.distanceSquared = distanceSquared;
        }
    }
}
