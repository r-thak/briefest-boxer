package dev.briefestboxer.fabric;

import dev.briefestboxer.core.AimPoint;
import dev.briefestboxer.core.AimPointSelector;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

public final class BriefestBoxerClient implements ClientModInitializer {
    private static final Logger LOGGER = LogManager.getLogger("Briefest Boxer");

    @Override
    public void onInitializeClient() {
        LOGGER.info("Registering 1.11.2 player aim-point guide");
    }

    public static void onClientTick(MinecraftClient client) {
        if (client.player == null || client.world == null) return;

        PlayerEntity viewer = client.player;
        Vec3d eye = viewer.getCameraPosVec(1.0F);
        Vec3d look = viewer.getRotationVec(1.0F);
        List<AimPoint> points = new ArrayList<AimPoint>();

        for (PlayerEntity target : client.world.field_9228) {
            if (target == viewer || target.isSpectator() || target.isInvisible()) continue;
            if (viewer.squaredDistanceTo(target) > 32.0 * 32.0) continue;
            points.addAll(pointsFor(target));
        }

        AimPoint nearest = AimPointSelector.nearestToViewRay(vector(eye), vector(look), points, 32.0);
        if (nearest == null) return;
        Vec3d selectedOffset = vector(nearest.position).subtract(eye);
        if (look.dotProduct(selectedOffset.normalize()) < 0.985) return;

        for (AimPoint point : points) {
            boolean selected = point == nearest;
            double red = selected ? 1.0 : 0.10;
            double green = selected ? 0.30 : 0.82;
            double blue = selected ? 0.10 : 1.0;
            client.particleManager.addParticle(30, point.position.x, point.position.y,
                    point.position.z, red, green, blue);
        }
    }

    private static List<AimPoint> pointsFor(PlayerEntity target) {
        net.minecraft.util.math.Box box = target.getBoundingBox();
        double centerX = (box.minX + box.maxX) * 0.5;
        double centerZ = (box.minZ + box.maxZ) * 0.5;
        double width = box.maxX - box.minX;
        double centerY = (box.minY + box.maxY) * 0.5;
        List<AimPoint> points = new ArrayList<AimPoint>(6);
        points.add(new AimPoint("head", vector(new Vec3d(centerX, box.maxY - 0.12, centerZ))));
        points.add(new AimPoint("torso", vector(new Vec3d(centerX, centerY + 0.12, centerZ))));
        points.add(new AimPoint("pelvis", vector(new Vec3d(centerX, box.minY + (box.maxY - box.minY) * 0.43, centerZ))));
        points.add(new AimPoint("left", vector(new Vec3d(centerX - width * 0.42, centerY + 0.08, centerZ))));
        points.add(new AimPoint("right", vector(new Vec3d(centerX + width * 0.42, centerY + 0.08, centerZ))));
        points.add(new AimPoint("legs", vector(new Vec3d(centerX, box.minY + 0.22, centerZ))));
        return points;
    }

    private static dev.briefestboxer.core.Vec3 vector(Vec3d point) {
        return new dev.briefestboxer.core.Vec3(point.x, point.y, point.z);
    }

    private static Vec3d vector(dev.briefestboxer.core.Vec3 point) {
        return new Vec3d(point.x, point.y, point.z);
    }
}
