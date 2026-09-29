package dev.briefestboxer.fabric;

import dev.briefestboxer.core.BriefestBoxerConfig;
import dev.briefestboxer.core.EntityHighlightSelector;
import dev.briefestboxer.core.ReachableSurface;
import dev.briefestboxer.core.Vec3;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.hit.HitResult;
import org.lwjgl.opengl.GL11;

import java.util.List;

/** Camera-distance reachable-surface overlay for Legacy Fabric 1.11.2. */
public final class BriefestBoxerClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        MinecraftClient client = MinecraftClient.getInstance();
        BriefestBoxerConfig.load(client.runDirectory.toPath());
    }

    public static void renderReachableHighlight(float tickDelta) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null
                || (!BriefestBoxerConfig.showAimPoints && !BriefestBoxerConfig.showEntities)) return;

        // The legacy dispatcher exposes the interpolated render camera position;
        // using player eye position here caused the overlay to swim while moving.
        net.minecraft.client.render.entity.EntityRenderDispatcher dispatcher = client.getEntityRenderDispatcher();
        double cameraX = dispatcher.field_4695;
        double cameraY = dispatcher.field_4694;
        double cameraZ = dispatcher.field_4693;
        Vec3d camera = new Vec3d(cameraX, cameraY, cameraZ);
        double scanRange = BriefestBoxerConfig.aimRange();
        double reach = client.interactionManager == null ? 3.0 : client.interactionManager.getReachDistance();
        Entity nearest = null;
        Box nearestBox = null;
        double nearestDistance = Double.POSITIVE_INFINITY;

        for (Entity target : client.world.field_0_260) {
            if (target == client.player || !target.isAlive() || target.isInvisible()) continue;
            if (target instanceof PlayerEntity && ((PlayerEntity) target).isSpectator()) continue;
            if (target instanceof PlayerEntity && !BriefestBoxerConfig.showAimPoints) continue;
            if (!(target instanceof PlayerEntity) && !BriefestBoxerConfig.showEntities) continue;
            Box box = hittableBounds(target, tickDelta);
            Vec3d closest = closest(camera, box);
            double distance = camera.squaredDistanceTo(closest);
            if (distance > scanRange * scanRange || distance >= nearestDistance
                    || !visible(client, camera, closest)) continue;
            nearest = target;
            nearestBox = box;
            nearestDistance = distance;
        }
        if (nearest == null || nearestDistance > reach * reach) return;

        int rgb = nearest instanceof PlayerEntity
                ? BriefestBoxerConfig.selectedColor() : BriefestBoxerConfig.otherColor();
        List<ReachableSurface.Triangle> mesh = ReachableSurface.mesh(bounds(nearestBox), vector(camera), reach);
        if (mesh.isEmpty()) return;

        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder out = tessellator.getBuffer();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT
                | GL11.GL_TEXTURE_BIT | GL11.GL_CURRENT_BIT | GL11.GL_POLYGON_BIT);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(false);
        GL11.glDisable(GL11.GL_CULL_FACE);
        out.begin(GL11.GL_TRIANGLES, VertexFormats.POSITION_COLOR);
        for (ReachableSurface.Triangle triangle : mesh) {
            Vec3d a = gameVec(triangle.a);
            Vec3d b = gameVec(triangle.b);
            Vec3d c = gameVec(triangle.c);
            Vec3d sample = new Vec3d((a.x + b.x + c.x) / 3.0,
                    (a.y + b.y + c.y) / 3.0, (a.z + b.z + c.z) / 3.0);
            if (!visible(client, camera, sample)) continue;
            vertex(out, a, cameraX, cameraY, cameraZ, rgb);
            vertex(out, b, cameraX, cameraY, cameraZ, rgb);
            vertex(out, c, cameraX, cameraY, cameraZ, rgb);
        }
        tessellator.draw();
        GL11.glPopAttrib();
    }

    private static Box hittableBounds(Entity entity, float tickDelta) {
        double backstep = 1.0 - tickDelta;
        double margin = entity.getTargetingMargin();
        return entity.getBoundingBox().expand(margin, margin, margin).offset(
                (entity.prevX - entity.x) * backstep,
                (entity.prevY - entity.y) * backstep,
                (entity.prevZ - entity.z) * backstep);
    }

    private static Vec3d closest(Vec3d point, Box box) {
        return new Vec3d(clamp(point.x, box.minX, box.maxX), clamp(point.y, box.minY, box.maxY),
                clamp(point.z, box.minZ, box.maxZ));
    }

    private static boolean visible(MinecraftClient client, Vec3d camera, Vec3d point) {
        double distance = camera.squaredDistanceTo(point);
        if (distance < 1.0E-8) return true;
        HitResult hit = client.world.raycast(camera, point, false, true, false);
        return hit == null || hit.field_1330 != HitResult.Type.BLOCK;
    }

    private static void vertex(BufferBuilder out, Vec3d point, double cameraX, double cameraY,
            double cameraZ, int rgb) {
        out.vertex(point.x - cameraX, point.y - cameraY, point.z - cameraZ)
                .color(rgb >> 16 & 255, rgb >> 8 & 255, rgb & 255, 205).next();
    }

    private static EntityHighlightSelector.Bounds bounds(Box box) {
        return new EntityHighlightSelector.Bounds(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
    }

    private static Vec3 vector(Vec3d point) { return new Vec3(point.x, point.y, point.z); }
    private static Vec3d gameVec(Vec3 point) { return new Vec3d(point.x, point.y, point.z); }
    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
