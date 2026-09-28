package dev.briefestboxer.forge;

import dev.briefestboxer.core.AimPoint;
import dev.briefestboxer.core.AimPointSelector;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.player.AbstractClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particles.RedstoneParticleData;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;

@Mod.EventBusSubscriber(modid = BriefestBoxerMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ForgeClientAimGuide {
    private ForgeClientAimGuide() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.world == null) return;

        AbstractClientPlayerEntity viewer = client.player;
        Vector3d eye = viewer.getEyePosition(1.0F);
        Vector3d look = viewer.getLook(1.0F);
        List<AimPoint> points = new ArrayList<>();
        for (PlayerEntity target : client.world.getPlayers()) {
            if (target == viewer || target.isSpectator() || target.isInvisible()) continue;
            if (viewer.getDistanceSq(target) > 32.0 * 32.0) continue;
            points.addAll(pointsFor(target));
        }

        AimPoint nearest = AimPointSelector.nearestToViewRay(vector(eye), vector(look), points, 32.0);
        if (nearest == null) return;
        Vector3d selectedPoint = new Vector3d(nearest.position.x, nearest.position.y, nearest.position.z);
        if (look.dotProduct(selectedPoint.subtract(eye).normalize()) < 0.985) return;

        for (AimPoint point : points) {
            boolean selected = point == nearest;
            float red = selected ? 1.0F : 0.10F;
            float green = selected ? 0.30F : 0.80F;
            float blue = selected ? 0.10F : 1.0F;
            client.world.addParticle(new RedstoneParticleData(red, green, blue, selected ? 1.25F : 0.8F),
                    point.position.x, point.position.y, point.position.z, 0.0, 0.0, 0.0);
        }
    }

    private static List<AimPoint> pointsFor(PlayerEntity target) {
        AxisAlignedBB box = target.getBoundingBox();
        double centerX = (box.minX + box.maxX) * 0.5;
        double centerZ = (box.minZ + box.maxZ) * 0.5;
        double width = box.maxX - box.minX;
        double centerY = (box.minY + box.maxY) * 0.5;
        List<AimPoint> points = new ArrayList<>(6);
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

    private static dev.briefestboxer.core.Vec3 vector(Vector3d value) {
        return new dev.briefestboxer.core.Vec3(value.x, value.y, value.z);
    }
}
