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
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public final class BriefestBoxerClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("Briefest Boxer");
    private static final Map<Integer, PatchState> PATCHES = new HashMap<>();
    private static long lastFrameNanos;

    @Override
    public void onInitializeClient() {
        LOGGER.info("Registering 1.19.2 visible entity surface highlight");
        BriefestBoxerConfig.load(MinecraftClient.getInstance().runDirectory.toPath());
        WorldRenderEvents.AFTER_ENTITIES.register(BriefestBoxerClient::render);
    }

    private static void render(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || context.world() == null || context.consumers() == null) return;
        Vec3d camera = context.camera().getPos();
        double maxDistance = BriefestBoxerConfig.aimRange();
        double reach = 3.0;
        Target nearest = null;
        for (Entity entity : context.world().getEntities()) {
            if (!(entity instanceof LivingEntity target) || target == client.player || !target.isAlive()
                    || target.isSpectator() || target.isInvisible()) continue;
            if (target instanceof AbstractClientPlayerEntity && !BriefestBoxerConfig.showAimPoints) continue;
            if (!(target instanceof AbstractClientPlayerEntity) && !BriefestBoxerConfig.showEntities) continue;
            Box box = target.getBoundingBox();
            Vec3d closest = closest(camera, box);
            double d2 = camera.squaredDistanceTo(closest);
            if (d2 > maxDistance * maxDistance || !visible(context, client.player, camera, closest)) continue;
            if (nearest == null || d2 < nearest.distanceSquared) nearest = new Target(target, box, d2);
        }

        long now = System.nanoTime();
        double dt = lastFrameNanos == 0 ? 1.0 / 60.0 : Math.min(0.1, (now - lastFrameNanos) / 1.0E9);
        lastFrameNanos = now;
        int selectedId = -1;
        if (nearest != null && nearest.distanceSquared <= reach * reach) {
            selectedId = nearest.entity.getId();
            EntityHighlightSelector.Bounds geometry = bounds(nearest.box);
            PatchState state = PATCHES.computeIfAbsent(selectedId, id -> new PatchState(geometry));
            state.bounds = geometry;
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
            drawReachableSurface(context, out, camera, state.bounds, reach,
                    BriefestBoxerConfig.selectedColor(), state.scale);
        }
        context.matrixStack().pop();
    }

    private static void drawReachableSurface(WorldRenderContext context, VertexConsumer out, Vec3d camera,
            EntityHighlightSelector.Bounds bounds, double reach, int rgb, double opacity) {
        for (ReachableSurface.Triangle triangle : ReachableSurface.mesh(bounds, vector(camera), reach)) {
            int alpha = (int) (0xA0 * opacity), r = rgb >>> 16 & 255, g = rgb >>> 8 & 255, b = rgb & 255;
            vertex(out, context, gameVec(triangle.a), r, g, b, alpha);
            vertex(out, context, gameVec(triangle.b), r, g, b, alpha);
            vertex(out, context, gameVec(triangle.c), r, g, b, alpha);
            vertex(out, context, gameVec(triangle.c), r, g, b, alpha);
        }
    }
    private static Vec3d gameVec(Vec3 p) { return new Vec3d(p.x, p.y, p.z); }

    private static void vertex(VertexConsumer out, WorldRenderContext context, Vec3d p,
                               int r, int g, int b, int a) {
        out.vertex(context.matrixStack().peek().getPositionMatrix(), (float) p.x, (float) p.y, (float) p.z)
                .color(r, g, b, a).next();
    }

    private static boolean visible(WorldRenderContext context, Entity viewer, Vec3d camera, Vec3d sample) {
        double distance = camera.squaredDistanceTo(sample);
        if (distance < 1.0E-8) return true;
        var hit = context.world().raycast(new RaycastContext(camera, sample,
                RaycastContext.ShapeType.VISUAL, RaycastContext.FluidHandling.NONE, viewer));
        return hit.getType() != HitResult.Type.BLOCK || camera.squaredDistanceTo(hit.getPos()) >= distance - 0.01;
    }

    private static Vec3d closest(Vec3d p, Box b) {
        return new Vec3d(clamp(p.x, b.minX, b.maxX), clamp(p.y, b.minY, b.maxY), clamp(p.z, b.minZ, b.maxZ));
    }
    private static EntityHighlightSelector.Bounds bounds(Box b) {
        return new EntityHighlightSelector.Bounds(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ);
    }
    private static Vec3 vector(Vec3d p) { return new Vec3(p.x, p.y, p.z); }
    private static double clamp(double x, double lo, double hi) { return Math.max(lo, Math.min(hi, x)); }

    private static final class PatchState {
        private EntityHighlightSelector.Bounds bounds;
        private double scale, targetScale;
        private PatchState(EntityHighlightSelector.Bounds bounds) { this.bounds = bounds; }
    }
    private static final class Target {
        private final LivingEntity entity; private final Box box; private final double distanceSquared;
        private Target(LivingEntity entity, Box box, double d2) { this.entity = entity; this.box = box; distanceSquared = d2; }
    }

    private static final class PatchLayer extends RenderLayer {
        private static final RenderLayer LAYER = new PatchLayer();
        private PatchLayer() {
            super("briefest_boxer_surface_patch", VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.QUADS,
                    256, false, true,
                    () -> {
                        RenderSystem.enableBlend(); RenderSystem.defaultBlendFunc();
                        RenderSystem.setShader(GameRenderer::getPositionColorShader);
                        RenderSystem.enableDepthTest(); RenderSystem.depthFunc(515); RenderSystem.depthMask(false);
                        RenderSystem.disableCull();
                    },
                    () -> {
                        RenderSystem.depthMask(true); RenderSystem.enableCull(); RenderSystem.disableBlend();
                        RenderSystem.depthFunc(515); RenderSystem.setShaderColor(1, 1, 1, 1);
                    });
        }
    }
}
