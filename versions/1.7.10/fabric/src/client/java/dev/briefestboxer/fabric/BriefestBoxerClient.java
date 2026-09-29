package dev.briefestboxer.fabric;

import dev.briefestboxer.core.BriefestBoxerConfig;
import dev.briefestboxer.core.EntityHighlightSelector;
import dev.briefestboxer.core.ReachableSurface;
import dev.briefestboxer.core.Vec3;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.opengl.GL11;

import java.util.List;

/** Camera-distance reachable-surface overlay for Legacy Fabric 1.7.10. */
public final class BriefestBoxerClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        BriefestBoxerConfig.load(MinecraftClient.getInstance().runDirectory.toPath());
    }

    public static void renderReachableHighlight(float tickDelta) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.world.playerEntities.isEmpty()
                || (!BriefestBoxerConfig.showAimPoints && !BriefestBoxerConfig.showEntities)) return;
        Entity viewer = null;
        for (Object value : client.world.playerEntities) {
            if (value instanceof ClientPlayerEntity) {
                viewer = (ClientPlayerEntity) value;
                break;
            }
        }
        if (viewer == null) return;

        EntityRenderDispatcher cameraRenderer = EntityRenderDispatcher.INSTANCE;
        double cameraX = cameraRenderer.cameraX;
        double cameraY = cameraRenderer.cameraY;
        double cameraZ = cameraRenderer.cameraZ;
        Vec3d camera = Vec3d.of(cameraX, cameraY, cameraZ);
        double scanRange = BriefestBoxerConfig.aimRange();
        Entity nearest = null;
        Box nearestBox = null;
        double nearestDistance = Double.POSITIVE_INFINITY;

        for (Object value : client.world.loadedEntities) {
            if (!(value instanceof LivingEntity)) continue;
            LivingEntity target = (LivingEntity) value;
            if (!target.isAlive() || target == viewer || target.isInvisible()) continue;
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
        if (nearest == null) return;

        double reach = client.interactionManager == null ? 3.0 : client.interactionManager.getReachDistance();
        if (nearestDistance > reach * reach) return;
        int rgb = nearest instanceof PlayerEntity
                ? BriefestBoxerConfig.selectedColor() : BriefestBoxerConfig.otherColor();
        List<ReachableSurface.Triangle> mesh = ReachableSurface.mesh(bounds(nearestBox), vector(camera), reach);
        if (mesh.isEmpty()) return;

        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT
                | GL11.GL_TEXTURE_BIT | GL11.GL_CURRENT_BIT | GL11.GL_POLYGON_BIT);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(false);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glColor4f((rgb >> 16 & 255) / 255.0F, (rgb >> 8 & 255) / 255.0F,
                (rgb & 255) / 255.0F, 0.80F);
        GL11.glBegin(GL11.GL_TRIANGLES);
        for (ReachableSurface.Triangle triangle : mesh) {
            Vec3d a = gameVec(triangle.a);
            Vec3d b = gameVec(triangle.b);
            Vec3d c = gameVec(triangle.c);
            Vec3d sample = Vec3d.of((a.x + b.x + c.x) / 3.0,
                    (a.y + b.y + c.y) / 3.0, (a.z + b.z + c.z) / 3.0);
            if (!visible(client, camera, sample)) continue;
            GL11.glVertex3d(a.x - cameraX, a.y - cameraY, a.z - cameraZ);
            GL11.glVertex3d(b.x - cameraX, b.y - cameraY, b.z - cameraZ);
            GL11.glVertex3d(c.x - cameraX, c.y - cameraY, c.z - cameraZ);
        }
        GL11.glEnd();
        GL11.glPopAttrib();
    }

    private static Box hittableBounds(Entity entity, float tickDelta) {
        double backstep = 1.0 - tickDelta;
        double margin = entity.getTargetingMargin();
        return entity.getBox().expand(margin, margin, margin).offset(
                (entity.prevX - entity.x) * backstep,
                (entity.prevY - entity.y) * backstep,
                (entity.prevZ - entity.z) * backstep);
    }

    private static Vec3d closest(Vec3d point, Box box) {
        return Vec3d.of(clamp(point.x, box.minX, box.maxX), clamp(point.y, box.minY, box.maxY),
                clamp(point.z, box.minZ, box.maxZ));
    }

    private static boolean visible(MinecraftClient client, Vec3d camera, Vec3d point) {
        if (camera.squaredDistanceTo(point) < 1.0E-8) return true;
        return client.world.rayTrace(camera, point, false, true, false) == null;
    }

    private static EntityHighlightSelector.Bounds bounds(Box box) {
        return new EntityHighlightSelector.Bounds(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
    }

    private static Vec3 vector(Vec3d point) { return new Vec3(point.x, point.y, point.z); }
    private static Vec3d gameVec(Vec3 point) { return Vec3d.of(point.x, point.y, point.z); }
    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
