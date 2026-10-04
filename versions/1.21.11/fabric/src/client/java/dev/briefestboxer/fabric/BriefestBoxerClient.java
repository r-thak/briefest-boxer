package dev.briefestboxer.fabric;

import dev.briefestboxer.core.BriefestBoxerConfig;
import dev.briefestboxer.core.EntityHighlightSelector;
import dev.briefestboxer.core.ReachableSurface;
import dev.briefestboxer.core.Vec3;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public final class BriefestBoxerClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("Briefest Boxer");
    private static final Map<Integer, PatchState> PATCHES = new HashMap<>();
    private static long lastFrameNanos;

    @Override
    public void onInitializeClient() {
        LOGGER.info("Registering 1.21.11 visible entity highlights");
        BriefestBoxerConfig.load(MinecraftClient.getInstance().runDirectory.toPath());
        WorldRenderEvents.AFTER_ENTITIES.register(BriefestBoxerClient::renderHighlights);
    }

    private static void renderHighlights(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null || context.consumers() == null) return;
        float tickDelta = client.getRenderTickCounter().getTickProgress(false);
        Vec3d camera = client.getCameraEntity().getClientCameraPosVec(tickDelta);
        double maxDistance = BriefestBoxerConfig.aimRange();
        double maxDistanceSquared = maxDistance * maxDistance;
        double reach = client.player.getAttributeValue(EntityAttributes.ENTITY_INTERACTION_RANGE);
        Target nearest = null;

        for (Entity entity : client.world.getEntities()) {
            Entity target = entity;
            if (target == client.player || !target.isAlive() || target.isSpectator() || target.isInvisible()) continue;
            if (target instanceof AbstractClientPlayerEntity && !BriefestBoxerConfig.showAimPoints) continue;
            if (!(target instanceof AbstractClientPlayerEntity) && !BriefestBoxerConfig.showEntities) continue;
            Box box = hittableBounds(target, tickDelta);
            EntityHighlightSelector.Bounds geometry = bounds(box);
            Vec3 coreCamera = vector(camera);
            double distanceSquared = EntityHighlightSelector.distanceSquared(coreCamera, geometry);
            Vec3d closest = closestPoint(camera, box);
            if (distanceSquared > maxDistanceSquared || !visible(client, client.player, camera, closest)) continue;
            Target candidate = new Target(target, box, distanceSquared);
            if (nearest == null || candidate.distanceSquared < nearest.distanceSquared) nearest = candidate;
        }

        boolean selectedInReach = nearest != null && nearest.distanceSquared <= reach * reach;
        long now = System.nanoTime();
        double dt = lastFrameNanos == 0 ? 1.0 / 60.0 : Math.min(0.1, (now - lastFrameNanos) / 1.0E9);
        lastFrameNanos = now;
        int selectedId = -1;
        if (selectedInReach) {
            selectedId = nearest.entity.getId();
            EntityHighlightSelector.Bounds geometry = bounds(nearest.box);
            PatchState state = PATCHES.computeIfAbsent(selectedId, id -> new PatchState(geometry));
            state.bounds = geometry;
            state.playerTarget = nearest.entity instanceof AbstractClientPlayerEntity;
            state.targetScale = 1.0;
        }
        VertexConsumer quads = context.consumers().getBuffer(RenderLayers.debugQuads());
        context.matrices().push();
        double ease = 1.0 - Math.exp(-dt / 0.09);
        for (Iterator<Map.Entry<Integer, PatchState>> it = PATCHES.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Integer, PatchState> entry = it.next();
            PatchState state = entry.getValue();
            if (entry.getKey() != selectedId) state.targetScale = 0.0;
            state.scale += (state.targetScale - state.scale) * ease;
            if (state.scale < 0.002) { it.remove(); continue; }
            int color = state.playerTarget ? BriefestBoxerConfig.selectedColor()
                    : BriefestBoxerConfig.otherColor();
            drawReachableSurface(client, context, quads, camera, state.bounds, reach, color, state.scale);
        }
        context.matrices().pop();
    }

    private static boolean visible(MinecraftClient client, Entity viewer, Vec3d camera, Vec3d sample) {
        double distanceSquared = camera.squaredDistanceTo(sample);
        if (distanceSquared < 1.0E-8) return true;
        var hit = client.world.raycast(new RaycastContext(camera, sample,
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, viewer));
        return hit.getType() != HitResult.Type.BLOCK || camera.squaredDistanceTo(hit.getPos()) >= distanceSquared - 0.01;
    }

    private static EntityHighlightSelector.Bounds bounds(Box box) {
        return new EntityHighlightSelector.Bounds(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
    }

    private static Vec3 vector(Vec3d point) { return new Vec3(point.x, point.y, point.z); }

    private static Vec3d closestPoint(Vec3d p, Box b) {
        return new Vec3d(Math.max(b.minX, Math.min(p.x, b.maxX)), Math.max(b.minY, Math.min(p.y, b.maxY)),
                Math.max(b.minZ, Math.min(p.z, b.maxZ)));
    }
    private static Box hittableBounds(Entity entity, float tickDelta) {
        Vec3d interpolated = entity.getLerpedPos(tickDelta);
        double margin = entity.getTargetingMargin();
        return entity.getBoundingBox().expand(margin).offset(
                interpolated.x - entity.getX(), interpolated.y - entity.getY(), interpolated.z - entity.getZ());
    }
    private static void drawReachableSurface(MinecraftClient client, WorldRenderContext context, VertexConsumer out, Vec3d camera,
            EntityHighlightSelector.Bounds bounds, double reach, int rgb, double opacity) {
        for (ReachableSurface.Triangle triangle : ReachableSurface.mesh(bounds, vector(camera), reach)) {
            Vec3d a = gameVec(triangle.a), pointB = gameVec(triangle.b), c = gameVec(triangle.c);
            Vec3d sample = new Vec3d((a.x + pointB.x + c.x) / 3.0,
                    (a.y + pointB.y + c.y) / 3.0, (a.z + pointB.z + c.z) / 3.0);
            if (!visible(client, client.player, camera, sample)) continue;
            int alpha = (int) (0xA0 * opacity), r = (rgb >>> 16) & 255, g = (rgb >>> 8) & 255, blue = rgb & 255;
            vertex(out, context, camera, a, r, g, blue, alpha);
            vertex(out, context, camera, pointB, r, g, blue, alpha);
            vertex(out, context, camera, c, r, g, blue, alpha);
        }
    }
    private static Vec3d gameVec(Vec3 p) { return new Vec3d(p.x, p.y, p.z); }
    private static void vertex(VertexConsumer out, WorldRenderContext context, Vec3d camera, Vec3d p,
            int r, int g, int b, int a) {
        out.vertex(context.matrices().peek().getPositionMatrix(), (float) (p.x - camera.x),
                        (float) (p.y - camera.y), (float) (p.z - camera.z))
                .color(r, g, b, a);
    }
    private static final class PatchState {
        private EntityHighlightSelector.Bounds bounds;
        private double scale, targetScale;
        private boolean playerTarget;
        private PatchState(EntityHighlightSelector.Bounds bounds) { this.bounds = bounds; }
    }

    private static final class Target {
        private final Entity entity;
        private final Box box;
        private final double distanceSquared;
        private Target(Entity entity, Box box, double distanceSquared) {
            this.entity = entity;
            this.box = box;
            this.distanceSquared = distanceSquared;
        }
    }
}
