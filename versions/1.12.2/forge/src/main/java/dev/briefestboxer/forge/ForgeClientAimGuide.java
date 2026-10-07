package dev.briefestboxer.forge;

import dev.briefestboxer.core.BriefestBoxerConfig;
import dev.briefestboxer.core.EntityHighlightSelector;
import dev.briefestboxer.core.ReachableSurface;
import dev.briefestboxer.core.Vec3;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.RayTraceResult.Type;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;

import java.util.List;

@SideOnly(Side.CLIENT)
public final class ForgeClientAimGuide {
    public void loadConfig() {
        BriefestBoxerConfig.load(Minecraft.getMinecraft().gameDir.toPath());
    }

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        Minecraft client = Minecraft.getMinecraft();
        if (client.player == null || client.world == null
                || (!BriefestBoxerConfig.showAimPoints && !BriefestBoxerConfig.showEntities)) return;

        RenderManager renderManager = client.getRenderManager();
        double cameraX = renderManager.viewerPosX;
        double cameraY = renderManager.viewerPosY;
        double cameraZ = renderManager.viewerPosZ;
        Vec3d camera = new Vec3d(cameraX, cameraY, cameraZ);
        EntityLivingBase nearest = null;
        AxisAlignedBB nearestBox = null;
        double nearestDistance = Double.POSITIVE_INFINITY;
        for (Entity entity : client.world.loadedEntityList) {
            if (!(entity instanceof EntityLivingBase)) continue;
            EntityLivingBase target = (EntityLivingBase) entity;
            if (target == client.player || target.isDead || target.isInvisible()) continue;
            if (target instanceof EntityPlayer && !BriefestBoxerConfig.showAimPoints) continue;
            if (!(target instanceof EntityPlayer) && !BriefestBoxerConfig.showEntities) continue;
            AxisAlignedBB box = interpolatedHittableBounds(target, event.getPartialTicks());
            double distance = EntityHighlightSelector.distanceSquared(vector(camera), bounds(box));
            if (distance > BriefestBoxerConfig.aimRange() * BriefestBoxerConfig.aimRange()
                    || !hasVisibleSample(client, camera, box)) continue;
            if (distance < nearestDistance) {
                nearest = target;
                nearestBox = box;
                nearestDistance = distance;
            }
        }
        if (nearest == null) return;

        double reach = client.playerController.getBlockReachDistance();
        if (nearestDistance > reach * reach) return;
        List<ReachableSurface.Triangle> mesh = ReachableSurface.coloredMesh(bounds(nearestBox), vector(camera), reach);
        if (mesh.isEmpty()) return;
        int rgb = nearest instanceof EntityPlayer
                ? BriefestBoxerConfig.selectedColor() : BriefestBoxerConfig.otherColor();
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder out = tessellator.getBuffer();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT
                | GL11.GL_TEXTURE_BIT | GL11.GL_CURRENT_BIT | GL11.GL_POLYGON_BIT);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(false);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glColor4f((rgb >> 16 & 255) / 255.0F, (rgb >> 8 & 255) / 255.0F,
                (rgb & 255) / 255.0F, 0.78F);
        out.begin(GL11.GL_TRIANGLES, DefaultVertexFormats.POSITION_COLOR);
        for (ReachableSurface.Triangle triangle : mesh) {
            if (BriefestBoxerConfig.multicolorHighlights) rgb = triangle.gradientColor;
            Vec3d a = gameVec(triangle.a), b = gameVec(triangle.b), c = gameVec(triangle.c);
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

    private static void vertex(BufferBuilder out, Vec3d p, double cameraX, double cameraY,
            double cameraZ, int rgb) {
        out.pos(p.x - cameraX, p.y - cameraY, p.z - cameraZ)
                .color(rgb >> 16 & 255, rgb >> 8 & 255, rgb & 255, 200).endVertex();
    }

    private static AxisAlignedBB interpolatedHittableBounds(Entity entity, float partialTick) {
        double x = entity.lastTickPosX + (entity.posX - entity.lastTickPosX) * partialTick - entity.posX;
        double y = entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * partialTick - entity.posY;
        double z = entity.lastTickPosZ + (entity.posZ - entity.lastTickPosZ) * partialTick - entity.posZ;
        AxisAlignedBB box = entity.getEntityBoundingBox().offset(x, y, z);
        float overspill = entity.getCollisionBorderSize();
        return overspill > 0.0F ? box.grow(overspill, overspill, overspill) : box;
    }

    private static boolean hasVisibleSample(Minecraft client, Vec3d camera, AxisAlignedBB box) {
        double centerX = (box.minX + box.maxX) * 0.5;
        double centerY = (box.minY + box.maxY) * 0.5;
        double centerZ = (box.minZ + box.maxZ) * 0.5;
        return visible(client, camera, new Vec3d(centerX, centerY, centerZ))
                || visible(client, camera, new Vec3d(centerX, box.maxY - 0.02, centerZ))
                || visible(client, camera, new Vec3d(box.minX, centerY, centerZ))
                || visible(client, camera, new Vec3d(box.maxX, centerY, centerZ));
    }

    private static boolean visible(Minecraft client, Vec3d camera, Vec3d point) {
        double distance = camera.squareDistanceTo(point);
        if (distance < 1.0E-8) return true;
        RayTraceResult hit = client.world.rayTraceBlocks(camera, point, false, true, false);
        return hit == null || hit.typeOfHit != Type.BLOCK || hit.hitVec.squareDistanceTo(camera) >= distance - 0.01;
    }

    private static EntityHighlightSelector.Bounds bounds(AxisAlignedBB box) {
        return new EntityHighlightSelector.Bounds(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
    }

    private static dev.briefestboxer.core.Vec3 vector(EntityHighlightSelector.Bounds box) {
        return new Vec3(box.minX, box.minY, box.minZ);
    }

    private static Vec3d gameVec(Vec3 point) { return new Vec3d(point.x, point.y, point.z); }
    private static dev.briefestboxer.core.Vec3 vector(Vec3d point) {
        return new dev.briefestboxer.core.Vec3(point.x, point.y, point.z);
    }
}
