package dev.briefestboxer.fabric;

import dev.briefestboxer.core.AimPoint;
import dev.briefestboxer.core.AimPointSelector;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

public final class BriefestBoxerClient implements ClientModInitializer {
    private static final Logger LOGGER = LogManager.getLogger("Briefest Boxer");

    @Override
    public void onInitializeClient() {
        LOGGER.info("Registering 1.7.10 player aim-point guide");
    }

    public static void onClientTick(MinecraftClient client) {
        if (client.world == null) return;

        ClientPlayerEntity viewer = null;
        for (Object entity : client.world.playerEntities) {
            if (entity instanceof ClientPlayerEntity) {
                viewer = (ClientPlayerEntity) entity;
                break;
            }
        }
        if (viewer == null) return;

        Vec3d eye = Vec3d.of(viewer.x, viewer.y + viewer.getEyeHeight(), viewer.z);
        Vec3d look = lookVector(viewer.yaw, viewer.pitch);
        List<AimPoint> points = new ArrayList<AimPoint>();

        for (Object entity : client.world.playerEntities) {
            if (!(entity instanceof PlayerEntity)) continue;
            PlayerEntity target = (PlayerEntity) entity;
            if (target == viewer || target.isInvisible()) continue;
            if (viewer.squaredDistanceTo(target) > 32.0 * 32.0) continue;
            points.addAll(pointsFor(target));
        }

        AimPoint nearest = AimPointSelector.nearestToViewRay(vector(eye), vector(look), points, 32.0);
        if (nearest == null) return;
        Vec3d selectedOffset = Vec3d.of(nearest.position.x - eye.x,
                nearest.position.y - eye.y, nearest.position.z - eye.z);
        if (look.dotProduct(selectedOffset.normalize()) < 0.985) return;

        for (AimPoint point : points) {
            boolean selected = point == nearest;
            client.world.spawnParticle("reddust", point.position.x, point.position.y, point.position.z,
                    selected ? 1.0 : 0.10, selected ? 0.30 : 0.82, selected ? 0.10 : 1.0);
        }
    }

    private static Vec3d lookVector(float yawDegrees, float pitchDegrees) {
        double yaw = Math.toRadians(yawDegrees);
        double pitch = Math.toRadians(pitchDegrees);
        double horizontal = Math.cos(pitch);
        return Vec3d.of(-Math.sin(yaw) * horizontal, -Math.sin(pitch), Math.cos(yaw) * horizontal);
    }

    private static List<AimPoint> pointsFor(PlayerEntity target) {
        Box box = target.getBox();
        double centerX = (box.minX + box.maxX) * 0.5;
        double centerZ = (box.minZ + box.maxZ) * 0.5;
        double width = box.maxX - box.minX;
        double centerY = (box.minY + box.maxY) * 0.5;
        List<AimPoint> points = new ArrayList<AimPoint>(6);
        points.add(new AimPoint("head", vector(Vec3d.of(centerX, box.maxY - 0.12, centerZ))));
        points.add(new AimPoint("torso", vector(Vec3d.of(centerX, centerY + 0.12, centerZ))));
        points.add(new AimPoint("pelvis", vector(Vec3d.of(centerX, box.minY + (box.maxY - box.minY) * 0.43, centerZ))));
        points.add(new AimPoint("left", vector(Vec3d.of(centerX - width * 0.42, centerY + 0.08, centerZ))));
        points.add(new AimPoint("right", vector(Vec3d.of(centerX + width * 0.42, centerY + 0.08, centerZ))));
        points.add(new AimPoint("legs", vector(Vec3d.of(centerX, box.minY + 0.22, centerZ))));
        return points;
    }

    private static dev.briefestboxer.core.Vec3 vector(Vec3d point) {
        return new dev.briefestboxer.core.Vec3(point.x, point.y, point.z);
    }
}
