package dev.briefestboxer.fabric;

import dev.briefestboxer.core.BriefestBoxerConfig;
import dev.briefestboxer.core.EntityHighlightSelector;
import dev.briefestboxer.core.ReachableSurface;
import dev.briefestboxer.core.Vec3;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RayTraceContext;
import org.lwjgl.opengl.GL11;

import java.util.List;

public final class ReachableHighlightRenderer {
    private ReachableHighlightRenderer() {}

    public static void render(float tickDelta, Camera renderCamera) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null
                || (!BriefestBoxerConfig.showAimPoints && !BriefestBoxerConfig.showEntities)) return;
        Vec3d camera = renderCamera.getPos();
        double reach = client.interactionManager == null ? 3.0 : client.interactionManager.getReachDistance();
        double scanRadius = BriefestBoxerConfig.aimRange();
        Box search = new Box(camera.x - scanRadius, camera.y - scanRadius, camera.z - scanRadius,
                camera.x + scanRadius, camera.y + scanRadius, camera.z + scanRadius);
        Entity nearest = null;
        Box nearestBox = null;
        double nearestDistance = Double.POSITIVE_INFINITY;
        for (Entity target : client.world.getEntities((Entity) null, search, entity -> true)) {
            if (target == client.player || !target.isAlive() || target.isSpectator() || target.isInvisible()) continue;
            if (target instanceof PlayerEntity && !BriefestBoxerConfig.showAimPoints) continue;
            if (!(target instanceof PlayerEntity) && !BriefestBoxerConfig.showEntities) continue;
            Box box = hittableBounds(target, tickDelta);
            Vec3d closest = closest(camera, box);
            double distance = camera.squaredDistanceTo(closest);
            if (distance > scanRadius * scanRadius || distance >= nearestDistance
                    || !visible(client, client.player, camera, closest)) continue;
            nearest = target;
            nearestBox = box;
            nearestDistance = distance;
        }
        if (nearest == null || nearestDistance > reach * reach) return;

        int rgb = nearest instanceof PlayerEntity
                ? BriefestBoxerConfig.selectedColor() : BriefestBoxerConfig.otherColor();
        int red = rgb >>> 16 & 255, green = rgb >>> 8 & 255, blue = rgb & 255;
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
        for (ReachableSurface.Triangle triangle : ReachableSurface.mesh(bounds(nearestBox), vector(camera), reach)) {
            Vec3d a = gameVec(triangle.a), b = gameVec(triangle.b), c = gameVec(triangle.c);
            Vec3d sample = new Vec3d((a.x + b.x + c.x) / 3.0,
                    (a.y + b.y + c.y) / 3.0, (a.z + b.z + c.z) / 3.0);
            if (!visible(client, client.player, camera, sample)) continue;
            vertex(out, a, camera, red, green, blue);
            vertex(out, b, camera, red, green, blue);
            vertex(out, c, camera, red, green, blue);
        }
        tessellator.draw();
        GL11.glPopAttrib();
    }

    private static void vertex(BufferBuilder out, Vec3d p, Vec3d camera, int red, int green, int blue) {
        out.vertex(p.x - camera.x, p.y - camera.y, p.z - camera.z)
                .color(red, green, blue, 205).next();
    }

    private static Box hittableBounds(Entity entity, float tickDelta) {
        double backstep = 1.0 - tickDelta;
        double margin = entity.getTargetingMargin();
        return entity.getBoundingBox().expand(margin).offset(
                (entity.prevX - entity.getX()) * backstep,
                (entity.prevY - entity.getY()) * backstep,
                (entity.prevZ - entity.getZ()) * backstep);
    }

    private static Vec3d closest(Vec3d point, Box box) {
        return new Vec3d(clamp(point.x, box.x1, box.x2), clamp(point.y, box.y1, box.y2),
                clamp(point.z, box.z1, box.z2));
    }

    private static boolean visible(MinecraftClient client, Entity viewer, Vec3d camera, Vec3d point) {
        double distance = camera.squaredDistanceTo(point);
        if (distance < 1.0E-8) return true;
        BlockHitResult hit = client.world.rayTrace(new RayTraceContext(camera, point,
                RayTraceContext.ShapeType.OUTLINE, RayTraceContext.FluidHandling.NONE, viewer));
        return hit.getType() != HitResult.Type.BLOCK || camera.squaredDistanceTo(hit.getPos()) >= distance - 0.01;
    }

    private static EntityHighlightSelector.Bounds bounds(Box box) {
        return new EntityHighlightSelector.Bounds(box.x1, box.y1, box.z1, box.x2, box.y2, box.z2);
    }

    private static Vec3 vector(Vec3d point) { return new Vec3(point.x, point.y, point.z); }
    private static Vec3d gameVec(Vec3 point) { return new Vec3d(point.x, point.y, point.z); }
    private static double clamp(double value, double low, double high) { return Math.max(low, Math.min(high, value)); }
}
