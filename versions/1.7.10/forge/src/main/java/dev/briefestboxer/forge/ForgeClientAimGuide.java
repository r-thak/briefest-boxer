package dev.briefestboxer.forge;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import dev.briefestboxer.core.BriefestBoxerConfig;
import dev.briefestboxer.core.EntityHighlightSelector;
import dev.briefestboxer.core.ReachableSurface;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import org.lwjgl.opengl.GL11;

import java.util.List;

@SideOnly(Side.CLIENT)
public final class ForgeClientAimGuide {
    public void loadConfig() {
        BriefestBoxerConfig.load(Minecraft.getMinecraft().mcDataDir.toPath());
    }

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        Minecraft client = Minecraft.getMinecraft();
        if (client.thePlayer == null || client.theWorld == null
                || (!BriefestBoxerConfig.showAimPoints && !BriefestBoxerConfig.showEntities)) return;

        double cameraX = RenderManager.instance.viewerPosX;
        double cameraY = RenderManager.instance.viewerPosY;
        double cameraZ = RenderManager.instance.viewerPosZ;
        EntityHighlightSelector.Bounds camera = new EntityHighlightSelector.Bounds(
                cameraX, cameraY, cameraZ, cameraX, cameraY, cameraZ);
        Entity nearest = null;
        AxisAlignedBB nearestBox = null;
        double nearestDistance = Double.POSITIVE_INFINITY;
        for (Object value : client.theWorld.loadedEntityList) {
            if (!(value instanceof EntityLivingBase)) continue;
            EntityLivingBase target = (EntityLivingBase) value;
            if (target == client.thePlayer || target.isDead || target.isInvisible()
                    || (!(target instanceof EntityPlayer) && !BriefestBoxerConfig.showEntities)
                    || (target instanceof EntityPlayer && !BriefestBoxerConfig.showAimPoints)) continue;
            AxisAlignedBB box = interpolatedHittableBounds(target, event.partialTicks);
            EntityHighlightSelector.Bounds geometry = bounds(box);
            double distance = EntityHighlightSelector.distanceSquared(vector(camera), geometry);
            if (distance > BriefestBoxerConfig.aimRange() * BriefestBoxerConfig.aimRange()
                    || !hasVisibleSample(client, cameraX, cameraY, cameraZ, box)) continue;
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
        Tessellator tessellator = Tessellator.instance;
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT
                | GL11.GL_TEXTURE_BIT | GL11.GL_CURRENT_BIT | GL11.GL_POLYGON_BIT);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(false);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glColor4f(((rgb >> 16) & 255) / 255.0F, ((rgb >> 8) & 255) / 255.0F,
                (rgb & 255) / 255.0F, 0.78F);
        tessellator.startDrawing(GL11.GL_TRIANGLES);
        tessellator.setColorRGBA_I(rgb, 200);
        for (ReachableSurface.Triangle triangle : mesh) {
            if (BriefestBoxerConfig.multicolorHighlights) rgb = triangle.gradientColor;
            tessellator.setColorRGBA_I(rgb, 200);
            Vec3 a = gameVec(triangle.a), b = gameVec(triangle.b), c = gameVec(triangle.c);
            Vec3 sample = Vec3.createVectorHelper((a.xCoord + b.xCoord + c.xCoord) / 3.0,
                    (a.yCoord + b.yCoord + c.yCoord) / 3.0, (a.zCoord + b.zCoord + c.zCoord) / 3.0);
            if (!visible(client, cameraX, cameraY, cameraZ, sample)) continue;
            tessellator.addVertex(a.xCoord - cameraX, a.yCoord - cameraY, a.zCoord - cameraZ);
            tessellator.addVertex(b.xCoord - cameraX, b.yCoord - cameraY, b.zCoord - cameraZ);
            tessellator.addVertex(c.xCoord - cameraX, c.yCoord - cameraY, c.zCoord - cameraZ);
        }
        tessellator.draw();
        GL11.glPopAttrib();
    }

    private static AxisAlignedBB interpolatedHittableBounds(Entity entity, float partialTick) {
        double x = entity.lastTickPosX + (entity.posX - entity.lastTickPosX) * partialTick - entity.posX;
        double y = entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * partialTick - entity.posY;
        double z = entity.lastTickPosZ + (entity.posZ - entity.lastTickPosZ) * partialTick - entity.posZ;
        AxisAlignedBB box = entity.getBoundingBox().offset(x, y, z);
        float overspill = entity.getCollisionBorderSize();
        return overspill > 0.0F ? box.expand(overspill, overspill, overspill) : box;
    }

    private static boolean hasVisibleSample(Minecraft client, double x, double y, double z, AxisAlignedBB box) {
        double centerX = (box.minX + box.maxX) * 0.5;
        double centerY = (box.minY + box.maxY) * 0.5;
        double centerZ = (box.minZ + box.maxZ) * 0.5;
        return visible(client, x, y, z, Vec3.createVectorHelper(centerX, centerY, centerZ))
                || visible(client, x, y, z, Vec3.createVectorHelper(centerX, box.maxY - 0.02, centerZ))
                || visible(client, x, y, z, Vec3.createVectorHelper(box.minX, centerY, centerZ))
                || visible(client, x, y, z, Vec3.createVectorHelper(box.maxX, centerY, centerZ));
    }

    private static boolean visible(Minecraft client, double x, double y, double z, Vec3 point) {
        Vec3 camera = Vec3.createVectorHelper(x, y, z);
        MovingObjectPosition hit = client.theWorld.func_147447_a(camera, point, false, true, false);
        return hit == null || hit.hitVec == null
                || hit.hitVec.squareDistanceTo(camera) >= point.squareDistanceTo(camera) - 0.01;
    }

    private static EntityHighlightSelector.Bounds bounds(AxisAlignedBB box) {
        return new EntityHighlightSelector.Bounds(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
    }

    private static dev.briefestboxer.core.Vec3 vector(EntityHighlightSelector.Bounds box) {
        return new dev.briefestboxer.core.Vec3(box.minX, box.minY, box.minZ);
    }

    private static dev.briefestboxer.core.Vec3 vector(double x, double y, double z) {
        return new dev.briefestboxer.core.Vec3(x, y, z);
    }

    private static Vec3 gameVec(dev.briefestboxer.core.Vec3 point) {
        return Vec3.createVectorHelper(point.x, point.y, point.z);
    }
}
