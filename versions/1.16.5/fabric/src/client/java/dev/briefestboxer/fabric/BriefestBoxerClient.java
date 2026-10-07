package dev.briefestboxer.fabric;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.briefestboxer.core.BriefestBoxerConfig;
import dev.briefestboxer.core.EntityHighlightSelector;
import dev.briefestboxer.core.ReachableSurface;
import dev.briefestboxer.core.Vec3;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public final class BriefestBoxerClient implements ClientModInitializer {
    private static final Map<Integer, PatchState> PATCHES = new HashMap<>();
    private static long lastFrameNanos;

    @Override
    public void onInitializeClient() {
        System.out.println("[Briefest Boxer] Registering 1.16.5 visible entity surface highlight");
        BriefestBoxerConfig.load(MinecraftClient.getInstance().runDirectory.toPath());
        WorldRenderEvents.AFTER_ENTITIES.register(BriefestBoxerClient::render);
    }

    private static void render(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || context.world() == null || context.consumers() == null) return;
        Vec3d camera = context.camera().getPos();
        double maxDistance = BriefestBoxerConfig.aimRange();
        double reach = client.interactionManager == null ? 3.0
                : client.interactionManager.getReachDistance();
        Target nearest = null;
        for (Entity entity : context.world().getEntities()) {
            Entity target = entity;
            if (!(target instanceof net.minecraft.entity.LivingEntity) || target == client.player || !target.isAlive() || target.isSpectator() || target.isInvisible()) continue;
            if (target instanceof AbstractClientPlayerEntity && !BriefestBoxerConfig.showAimPoints) continue;
            if (!(target instanceof AbstractClientPlayerEntity) && !BriefestBoxerConfig.showEntities) continue;
            Box box = interpolatedHittableBounds(target, context.tickDelta());
            Vec3d closest = closest(camera, box);
            double d2 = camera.squaredDistanceTo(closest);
            if (d2 > Math.min(maxDistance * maxDistance, reach * reach)
                    || (nearest != null && d2 >= nearest.distanceSquared) || !visible(context, client.player, camera, closest)) continue;
            if (nearest == null || d2 < nearest.distanceSquared) nearest = new Target(target, box, d2);
        }

        long now = System.nanoTime();
        double dt = lastFrameNanos == 0 ? 1.0 / 60.0 : Math.min(0.1, (now - lastFrameNanos) / 1.0E9);
        lastFrameNanos = now;
        int selectedId = -1;
        if (nearest != null && nearest.distanceSquared <= reach * reach) {
            selectedId = nearest.entity.getEntityId();
            EntityHighlightSelector.Bounds geometry = bounds(nearest.box);
            PatchState state = PATCHES.computeIfAbsent(selectedId, id -> new PatchState(geometry));
            state.bounds = geometry;
            state.playerTarget = nearest.entity instanceof AbstractClientPlayerEntity;
            state.targetScale = 1.0;
        }
        VertexConsumer out = context.consumers().getBuffer(PatchLayer.LAYER);
        context.matrixStack().push();
        context.matrixStack().translate(-camera.x, -camera.y, -camera.z);
        double ease = 1.0 - Math.exp(-dt / 0.09);
        for (Iterator<Map.Entry<Integer, PatchState>> it = PATCHES.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Integer, PatchState> entry = it.next();
            PatchState state = entry.getValue();
            if (entry.getKey() != selectedId) state.targetScale = 0.0;
            state.scale += (state.targetScale - state.scale) * ease;
            if (state.scale < 0.002) { it.remove(); continue; }
            int color = state.playerTarget ? BriefestBoxerConfig.selectedColor()
                    : BriefestBoxerConfig.otherColor();
            drawReachableSurface(context, out, camera, state.bounds, reach, color, state.scale);
        }
        context.matrixStack().pop();
    }

    private static void drawReachableSurface(WorldRenderContext context, VertexConsumer out, Vec3d camera,
            EntityHighlightSelector.Bounds bounds, double reach, int rgb, double opacity) {
        for (ReachableSurface.Triangle triangle : ReachableSurface.coloredMesh(bounds, vector(camera), reach)) {
            if (BriefestBoxerConfig.multicolorHighlights) rgb = triangle.gradientColor;
            Vec3d a = gameVec(triangle.a), pointB = gameVec(triangle.b), c = gameVec(triangle.c);
            Vec3d center = new Vec3d((a.x + pointB.x + c.x) / 3.0,
                    (a.y + pointB.y + c.y) / 3.0, (a.z + pointB.z + c.z) / 3.0);
            if (!visible(context, MinecraftClient.getInstance().player, camera, center)) continue;
            int alpha = (int) (0xA0 * opacity), r = rgb >>> 16 & 255, g = rgb >>> 8 & 255, blue = rgb & 255;
            vertex(out, context, a, r, g, blue, alpha);
            vertex(out, context, pointB, r, g, blue, alpha);
            vertex(out, context, c, r, g, blue, alpha);
        }
    }
    private static Vec3d gameVec(Vec3 p) { return new Vec3d(p.x, p.y, p.z); }

    private static void vertex(VertexConsumer out, WorldRenderContext context, Vec3d p,
                               int r, int g, int b, int a) {
        out.vertex(context.matrixStack().peek().getModel(), (float) p.x, (float) p.y, (float) p.z)
                .color(r, g, b, a).next();
    }

    private static boolean visible(WorldRenderContext context, Entity viewer, Vec3d camera, Vec3d sample) {
        double distance = camera.squaredDistanceTo(sample);
        if (distance < 1.0E-8) return true;
        BlockHitResult hit = context.world().raycast(new RaycastContext(camera, sample,
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, viewer));
        return hit.getType() != HitResult.Type.BLOCK || camera.squaredDistanceTo(hit.getPos()) >= distance - 0.01;
    }

    private static Vec3d closest(Vec3d p, Box b) {
        return new Vec3d(clamp(p.x, b.minX, b.maxX), clamp(p.y, b.minY, b.maxY), clamp(p.z, b.minZ, b.maxZ));
    }
    private static Box interpolatedHittableBounds(Entity entity, float tickDelta) {
        float margin = entity.getTargetingMargin();
        Box box = entity.getBoundingBox().expand(margin);
        double backstep = 1.0 - tickDelta;
        return box.offset((entity.lastRenderX - entity.getX()) * backstep,
                (entity.lastRenderY - entity.getY()) * backstep,
                (entity.lastRenderZ - entity.getZ()) * backstep);
    }
    private static EntityHighlightSelector.Bounds bounds(Box b) {
        return new EntityHighlightSelector.Bounds(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ);
    }
    private static Vec3 vector(Vec3d p) { return new Vec3(p.x, p.y, p.z); }
    private static double clamp(double x, double lo, double hi) { return Math.max(lo, Math.min(hi, x)); }

    private static final class PatchState {
        private EntityHighlightSelector.Bounds bounds;
        private double scale, targetScale;
        private boolean playerTarget;
        private PatchState(EntityHighlightSelector.Bounds bounds) { this.bounds = bounds; }
    }
    private static final class Target {
        private final Entity entity; private final Box box; private final double distanceSquared;
        private Target(Entity entity, Box box, double d2) { this.entity = entity; this.box = box; distanceSquared = d2; }
    }

    private static final class PatchLayer extends RenderLayer {
        private static final RenderLayer LAYER = new PatchLayer();
        private PatchLayer() {
            super("briefest_boxer_surface_patch", VertexFormats.POSITION_COLOR, 7,
                    256, false, true,
                    () -> {
                        RenderSystem.enableBlend(); RenderSystem.defaultBlendFunc();
                        RenderSystem.enableDepthTest(); RenderSystem.depthFunc(515); RenderSystem.depthMask(false);
                        RenderSystem.disableCull();
                    },
                    () -> {
                        RenderSystem.depthMask(true); RenderSystem.enableCull(); RenderSystem.disableBlend();
                        RenderSystem.depthFunc(515); RenderSystem.clearCurrentColor();
                    });
        }
    }
}
