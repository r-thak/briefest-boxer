package dev.briefestboxer.forge;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.framegraph.FramePass;
import dev.briefestboxer.core.BriefestBoxerConfig;
import dev.briefestboxer.core.EntityHighlightSelector;
import dev.briefestboxer.core.ReachableSurface;
import dev.briefestboxer.core.Vec3;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.state.LevelRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.client.FramePassManager;
import net.minecraftforge.client.event.AddFramePassEvent;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public final class ForgeClientAimGuide {
    private static final Map<Integer, PatchState> PATCHES = new HashMap<>();
    private static long lastFrameNanos;
    private static boolean configLoaded;

    private ForgeClientAimGuide() {}

    public static void addRenderPass(AddFramePassEvent event) {
        event.addPass(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                BriefestBoxerMod.MOD_ID, "aim_guide"), new FramePassManager.PassDefinition() {
            @Override
            public void extracts(LevelTargetBundle bundle, FramePass pass) {
                bundle.main = pass.readsAndWrites(bundle.main);
            }

            @Override
            public void executes(LevelRenderState state) {
                renderHighlights(Minecraft.getInstance(), state);
            }
        });
    }

    private static void renderHighlights(Minecraft client, LevelRenderState renderState) {
        if (client.player == null || client.level == null || renderState.cameraRenderState == null
                || renderState.cameraRenderState.pos == null) { PATCHES.clear(); return; }
        if (!configLoaded) {
            BriefestBoxerConfig.load(client.gameDirectory.toPath());
            configLoaded = true;
        }
        if (!BriefestBoxerConfig.showAimPoints && !BriefestBoxerConfig.showEntities) {
            PATCHES.clear();
            return;
        }
        net.minecraft.world.phys.Vec3 camera = renderState.cameraRenderState.pos;
        double scanRadius = BriefestBoxerConfig.aimRange();
        double reach = Math.max(0.0, client.player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ENTITY_INTERACTION_RANGE));
        Target nearest = null;
        for (EntityRenderState entityState : renderState.entityRenderStates) {
            Entity entity = findLiveEntity(client, entityState);
            if (entity == null) continue;
            if (!(entity instanceof LivingEntity target) || target == client.player || !target.isAlive()
                    || target.isSpectator() || target.isInvisible()) continue;
            if (target instanceof AbstractClientPlayer && !BriefestBoxerConfig.showAimPoints) continue;
            if (!(target instanceof AbstractClientPlayer) && !BriefestBoxerConfig.showEntities) continue;
            AABB box = hittableBounds(target, entityState);
            net.minecraft.world.phys.Vec3 closest = closest(camera, box);
            double d2 = camera.distanceToSqr(closest);
            if (d2 > scanRadius * scanRadius || !visible(client, client.player, camera, closest)) continue;
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
            state.playerTarget = nearest.entity instanceof AbstractClientPlayer;
            state.targetScale = 1.0;
        }

        Matrix4f matrices = new Matrix4f()
                .translate((float) -camera.x, (float) -camera.y, (float) -camera.z);
        VertexConsumer out = client.renderBuffers().bufferSource().getBuffer(RenderType.debugQuads());
        double ease = 1.0 - Math.exp(-dt / 0.09);
        for (Iterator<Map.Entry<Integer, PatchState>> it = PATCHES.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Integer, PatchState> entry = it.next();
            PatchState state = entry.getValue();
            if (entry.getKey() != selectedId) state.targetScale = 0.0;
            state.scale += (state.targetScale - state.scale) * ease;
            if (state.scale < 0.002) { it.remove(); continue; }
            drawReachableSurface(out, matrices, camera, state.bounds, reach,
                    client.player, state.playerTarget ? BriefestBoxerConfig.selectedColor()
                            : BriefestBoxerConfig.otherColor(), state.scale);
        }
        client.renderBuffers().bufferSource().endBatch(RenderType.debugQuads());
    }

    private static Entity findLiveEntity(Minecraft client, EntityRenderState state) {
        Entity nearest = null;
        double nearestDistance = 1.0;
        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity.getType() != state.entityType) continue;
            double dx = entity.getX() - state.x, dy = entity.getY() - state.y, dz = entity.getZ() - state.z;
            double distance = dx * dx + dy * dy + dz * dz;
            if (distance < nearestDistance) { nearest = entity; nearestDistance = distance; }
        }
        return nearest;
    }

    private static void drawReachableSurface(VertexConsumer out, Matrix4f matrices,
            net.minecraft.world.phys.Vec3 camera, EntityHighlightSelector.Bounds bounds, double reach,
            Entity viewer, int rgb, double opacity) {
        int alpha = (int) (160 * opacity), r = rgb >>> 16 & 255, g = rgb >>> 8 & 255, b = rgb & 255;
        for (ReachableSurface.Triangle triangle : ReachableSurface.mesh(bounds, vector(camera), reach)) {
            net.minecraft.world.phys.Vec3 a = gameVec(triangle.a);
            net.minecraft.world.phys.Vec3 pointB = gameVec(triangle.b);
            net.minecraft.world.phys.Vec3 c = gameVec(triangle.c);
            net.minecraft.world.phys.Vec3 sample = new net.minecraft.world.phys.Vec3(
                    (a.x + pointB.x + c.x) / 3.0, (a.y + pointB.y + c.y) / 3.0,
                    (a.z + pointB.z + c.z) / 3.0);
            if (!visible(Minecraft.getInstance(), viewer, camera, sample)) continue;
            vertex(out, matrices, a, r, g, b, alpha);
            vertex(out, matrices, pointB, r, g, b, alpha);
            vertex(out, matrices, c, r, g, b, alpha);
            vertex(out, matrices, c, r, g, b, alpha);
        }
    }

    private static AABB hittableBounds(Entity entity, EntityRenderState state) {
        double margin = entity.getPickRadius();
        return entity.getBoundingBox().inflate(margin).move(
                state.x - entity.getX(), state.y - entity.getY(), state.z - entity.getZ());
    }

    private static net.minecraft.world.phys.Vec3 gameVec(Vec3 p) { return new net.minecraft.world.phys.Vec3(p.x, p.y, p.z); }

    private static void vertex(VertexConsumer out, Matrix4f matrices, net.minecraft.world.phys.Vec3 p,
                               int r, int g, int b, int a) {
        out.addVertex(matrices, (float) p.x, (float) p.y, (float) p.z).setColor(r, g, b, a);
    }

    private static boolean visible(Minecraft client, Entity viewer, net.minecraft.world.phys.Vec3 camera, net.minecraft.world.phys.Vec3 sample) {
        double d2 = camera.distanceToSqr(sample);
        if (d2 < 1.0E-8) return true;
        BlockHitResult hit = client.level.clip(new ClipContext(camera, sample,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, viewer));
        return hit.getType() != HitResult.Type.BLOCK || camera.distanceToSqr(hit.getLocation()) >= d2 - 0.01;
    }

    private static net.minecraft.world.phys.Vec3 closest(net.minecraft.world.phys.Vec3 p, AABB b) {
        return new net.minecraft.world.phys.Vec3(clamp(p.x, b.minX, b.maxX), clamp(p.y, b.minY, b.maxY), clamp(p.z, b.minZ, b.maxZ));
    }
    private static EntityHighlightSelector.Bounds bounds(AABB b) {
        return new EntityHighlightSelector.Bounds(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ);
    }
    private static Vec3 vector(net.minecraft.world.phys.Vec3 p) { return new Vec3(p.x, p.y, p.z); }
    private static double clamp(double x, double lo, double hi) { return Math.max(lo, Math.min(hi, x)); }

    private static final class PatchState {
        private EntityHighlightSelector.Bounds bounds;
        private double scale, targetScale;
        private boolean playerTarget;
        private PatchState(EntityHighlightSelector.Bounds bounds) { this.bounds = bounds; }
    }
    private static final class Target {
        private final LivingEntity entity; private final AABB box; private final double distanceSquared;
        private Target(LivingEntity e, AABB b, double d2) { entity = e; box = b; distanceSquared = d2; }
    }
}
