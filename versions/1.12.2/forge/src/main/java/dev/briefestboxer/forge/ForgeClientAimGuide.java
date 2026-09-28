package dev.briefestboxer.forge;

import dev.briefestboxer.core.AimPoint;
import dev.briefestboxer.core.AimPointSelector;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

@SideOnly(Side.CLIENT)
public final class ForgeClientAimGuide {
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft client = Minecraft.getMinecraft();
        if (client.player == null || client.world == null) return;

        EntityPlayer viewer = client.player;
        Vec3d eye = viewer.getPositionEyes(1.0F);
        Vec3d look = viewer.getLook(1.0F);
        List<AimPoint> points = new ArrayList<AimPoint>();
        for (Object entity : client.world.playerEntities) {
            if (!(entity instanceof EntityPlayer)) continue;
            EntityPlayer target = (EntityPlayer) entity;
            if (target == viewer || target.isInvisible()) continue;
            if (viewer.getDistanceSq(target) > 32.0 * 32.0) continue;
            points.addAll(pointsFor(target));
        }

        AimPoint nearest = AimPointSelector.nearestToViewRay(vector(eye), vector(look), points, 32.0);
        if (nearest == null) return;
        Vec3d offset = new Vec3d(nearest.position.x - eye.x,
                nearest.position.y - eye.y, nearest.position.z - eye.z);
        if (look.dotProduct(offset.normalize()) < 0.985) return;

        for (AimPoint point : points) {
            boolean selected = point == nearest;
            client.world.spawnParticle(EnumParticleTypes.REDSTONE, point.position.x, point.position.y, point.position.z,
                    selected ? 1.0 : 0.10, selected ? 0.30 : 0.82, selected ? 0.10 : 1.0);
        }
    }

    private static List<AimPoint> pointsFor(EntityPlayer target) {
        AxisAlignedBB box = target.getEntityBoundingBox();
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

    private static dev.briefestboxer.core.Vec3 vector(Vec3d value) {
        return new dev.briefestboxer.core.Vec3(value.x, value.y, value.z);
    }
}
