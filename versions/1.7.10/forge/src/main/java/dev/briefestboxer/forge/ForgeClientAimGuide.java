package dev.briefestboxer.forge;

import dev.briefestboxer.core.AimPoint;
import dev.briefestboxer.core.AimPointSelector;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.Vec3;

import java.util.ArrayList;
import java.util.List;

@SideOnly(Side.CLIENT)
public final class ForgeClientAimGuide {
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft client = Minecraft.getMinecraft();
        if (client.thePlayer == null || client.theWorld == null) return;

        EntityPlayer viewer = client.thePlayer;
        Vec3 eye = Vec3.createVectorHelper(viewer.posX, viewer.posY + viewer.getEyeHeight(), viewer.posZ);
        Vec3 look = viewer.getLook(1.0F);
        List<AimPoint> points = new ArrayList<AimPoint>();
        for (Object entity : client.theWorld.playerEntities) {
            if (!(entity instanceof EntityPlayer)) continue;
            EntityPlayer target = (EntityPlayer) entity;
            if (target == viewer || target.isInvisible()) continue;
            if (viewer.getDistanceSqToEntity(target) > 32.0 * 32.0) continue;
            points.addAll(pointsFor(target));
        }

        AimPoint nearest = AimPointSelector.nearestToViewRay(vector(eye), vector(look), points, 32.0);
        if (nearest == null) return;
        Vec3 offset = Vec3.createVectorHelper(nearest.position.x - eye.xCoord,
                nearest.position.y - eye.yCoord, nearest.position.z - eye.zCoord);
        if (look.dotProduct(offset.normalize()) < 0.985) return;

        for (AimPoint point : points) {
            boolean selected = point == nearest;
            client.theWorld.spawnParticle("reddust", point.position.x, point.position.y,
                    point.position.z, selected ? 1.0 : 0.10, selected ? 0.30 : 0.82, selected ? 0.10 : 1.0);
        }
    }

    private static List<AimPoint> pointsFor(EntityPlayer target) {
        AxisAlignedBB box = target.getBoundingBox();
        double centerX = (box.minX + box.maxX) * 0.5;
        double centerZ = (box.minZ + box.maxZ) * 0.5;
        double width = box.maxX - box.minX;
        double centerY = (box.minY + box.maxY) * 0.5;
        List<AimPoint> points = new ArrayList<AimPoint>(6);
        points.add(new AimPoint("head", point(centerX, box.maxY - 0.12, centerZ)));
        points.add(new AimPoint("torso", point(centerX, centerY + 0.12, centerZ)));
        points.add(new AimPoint("pelvis", point(centerX, box.minY + (box.maxY - box.minY) * 0.43, centerZ)));
        points.add(new AimPoint("left", point(centerX - width * 0.42, centerY + 0.08, centerZ)));
        points.add(new AimPoint("right", point(centerX + width * 0.42, centerY + 0.08, centerZ)));
        points.add(new AimPoint("legs", point(centerX, box.minY + 0.22, centerZ)));
        return points;
    }

    private static dev.briefestboxer.core.Vec3 point(double x, double y, double z) {
        return new dev.briefestboxer.core.Vec3(x, y, z);
    }

    private static dev.briefestboxer.core.Vec3 vector(Vec3 v) {
        return new dev.briefestboxer.core.Vec3(v.xCoord, v.yCoord, v.zCoord);
    }
}
