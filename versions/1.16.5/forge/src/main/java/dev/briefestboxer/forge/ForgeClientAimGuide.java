package dev.briefestboxer.forge;

import com.mojang.blaze3d.matrix.MatrixStack;
import dev.briefestboxer.core.BriefestBoxerConfig;
import dev.briefestboxer.core.EntityHighlightSelector;
import dev.briefestboxer.core.ReachableSurface;
import dev.briefestboxer.core.Vec3;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockRayTraceResult;
import net.minecraft.util.math.RayTraceContext;
import net.minecraft.util.math.vector.Matrix4f;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.api.distmarker.Dist;
import org.lwjgl.opengl.GL11;

import java.util.List;

@Mod.EventBusSubscriber(modid = BriefestBoxerMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ForgeClientAimGuide {
    private static boolean configLoaded;

    private ForgeClientAimGuide() {}

    @SubscribeEvent
    public static void onRenderWorldLast(RenderWorldLastEvent event) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.world == null) return;
        if (!configLoaded) {
            BriefestBoxerConfig.load(client.gameDir.toPath());
            configLoaded = true;
        }
        if (!BriefestBoxerConfig.showAimPoints && !BriefestBoxerConfig.showEntities) return;

        float partialTick = event.getPartialTicks();
        Vector3d camera = client.gameRenderer.getActiveRenderInfo().getProjectedView();
        EntityHighlightSelector.Bounds cameraBounds = bounds(camera);
        Entity nearest = null;
        AxisAlignedBB nearestBox = null;
        double nearestDistance = Double.POSITIVE_INFINITY;
        for (Entity entity : client.world.getAllEntities()) {
            Entity target = entity;
            if (target == client.player || !target.isAlive() || target.isSpectator() || target.isInvisible()) continue;
            if (target instanceof PlayerEntity && !BriefestBoxerConfig.showAimPoints) continue;
            if (!(target instanceof PlayerEntity) && !BriefestBoxerConfig.showEntities) continue;
            AxisAlignedBB box = interpolatedHittableBounds(target, partialTick);
            double distance = EntityHighlightSelector.distanceSquared(vector(camera), bounds(box));
            if (distance > BriefestBoxerConfig.aimRange() * BriefestBoxerConfig.aimRange()
                    || !hasVisibleSample(client, client.player, camera, box)) continue;
            if (distance < nearestDistance) {
                nearest = target;
                nearestBox = box;
                nearestDistance = distance;
            }
        }
        if (nearest == null) return;

        double reach = client.playerController.getBlockReachDistance();
        if (nearestDistance > reach * reach) return;
        List<ReachableSurface.Triangle> mesh = ReachableSurface.mesh(bounds(nearestBox), vector(camera), reach);
        if (mesh.isEmpty()) return;

        int rgb = nearest instanceof PlayerEntity
                ? BriefestBoxerConfig.selectedColor() : BriefestBoxerConfig.otherColor();
        int red = rgb >> 16 & 255, green = rgb >> 8 & 255, blue = rgb & 255;
        MatrixStack matrices = event.getMatrixStack();
        matrices.push();
        matrices.translate(-camera.x, -camera.y, -camera.z);
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT
                | GL11.GL_TEXTURE_BIT | GL11.GL_CURRENT_BIT | GL11.GL_POLYGON_BIT);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(false);
        GL11.glDisable(GL11.GL_CULL_FACE);
        BufferBuilder out = Tessellator.getInstance().getBuffer();
        out.begin(GL11.GL_TRIANGLES, DefaultVertexFormats.POSITION_COLOR);
        Matrix4f matrix = matrices.getLast().getMatrix();
        for (ReachableSurface.Triangle triangle : mesh) {
            Vector3d a = gameVec(triangle.a), b = gameVec(triangle.b), c = gameVec(triangle.c);
            Vector3d sample = new Vector3d((a.x + b.x + c.x) / 3.0,
                    (a.y + b.y + c.y) / 3.0, (a.z + b.z + c.z) / 3.0);
            if (!visible(client, client.player, camera, sample)) continue;
            vertex(out, matrix, a, red, green, blue);
            vertex(out, matrix, b, red, green, blue);
            vertex(out, matrix, c, red, green, blue);
        }
        Tessellator.getInstance().draw();
        GL11.glPopAttrib();
        matrices.pop();
    }

    private static void vertex(BufferBuilder out, Matrix4f matrix, Vector3d p, int r, int g, int b) {
        out.pos(matrix, (float) p.x, (float) p.y, (float) p.z).color(r, g, b, 200).endVertex();
    }

    private static AxisAlignedBB interpolatedHittableBounds(Entity entity, float partialTick) {
        double x = entity.lastTickPosX + (entity.getPosX() - entity.lastTickPosX) * partialTick - entity.getPosX();
        double y = entity.lastTickPosY + (entity.getPosY() - entity.lastTickPosY) * partialTick - entity.getPosY();
        double z = entity.lastTickPosZ + (entity.getPosZ() - entity.lastTickPosZ) * partialTick - entity.getPosZ();
        AxisAlignedBB box = entity.getBoundingBox().offset(x, y, z);
        float overspill = entity.getCollisionBorderSize();
        return overspill > 0.0F ? box.grow(overspill, overspill, overspill) : box;
    }

    private static boolean hasVisibleSample(Minecraft client, Entity viewer, Vector3d camera, AxisAlignedBB box) {
        double centerX = (box.minX + box.maxX) * 0.5;
        double centerY = (box.minY + box.maxY) * 0.5;
        double centerZ = (box.minZ + box.maxZ) * 0.5;
        return visible(client, viewer, camera, new Vector3d(centerX, centerY, centerZ))
                || visible(client, viewer, camera, new Vector3d(centerX, box.maxY - 0.02, centerZ))
                || visible(client, viewer, camera, new Vector3d(box.minX, centerY, centerZ))
                || visible(client, viewer, camera, new Vector3d(box.maxX, centerY, centerZ));
    }

    private static boolean visible(Minecraft client, Entity viewer, Vector3d camera, Vector3d point) {
        double distance = camera.squareDistanceTo(point);
        if (distance < 1.0E-8) return true;
        BlockRayTraceResult hit = client.world.rayTraceBlocks(new RayTraceContext(camera, point,
                RayTraceContext.BlockMode.VISUAL, RayTraceContext.FluidMode.NONE, viewer));
        return hit.getType() != BlockRayTraceResult.Type.BLOCK
                || camera.squareDistanceTo(hit.getHitVec()) >= distance - 0.01;
    }

    private static EntityHighlightSelector.Bounds bounds(AxisAlignedBB box) {
        return new EntityHighlightSelector.Bounds(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
    }

    private static EntityHighlightSelector.Bounds bounds(Vector3d point) {
        return new EntityHighlightSelector.Bounds(point.x, point.y, point.z, point.x, point.y, point.z);
    }

    private static Vec3 vector(Vector3d point) { return new Vec3(point.x, point.y, point.z); }
    private static Vec3 vector(EntityHighlightSelector.Bounds box) { return new Vec3(box.minX, box.minY, box.minZ); }
    private static Vector3d gameVec(Vec3 point) { return new Vector3d(point.x, point.y, point.z); }
}
