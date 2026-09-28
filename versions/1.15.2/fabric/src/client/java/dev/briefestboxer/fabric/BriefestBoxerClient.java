package dev.briefestboxer.fabric;

import dev.briefestboxer.core.AimPoint;
import dev.briefestboxer.core.AimPointSelector;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.particle.DustParticleEffect;
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
        LOGGER.info("Registering 1.15.2 player aim-point guide");
        ClientTickEvents.END_CLIENT_TICK.register(BriefestBoxerClient::onClientTick);
    }

    private static void onClientTick(MinecraftClient client) {
        if (client.player == null || client.world == null) return;

        AbstractClientPlayerEntity viewer = client.player;
        Vec3d eye = viewer.getCameraPosVec(1.0F);
        Vec3d look = viewer.getRotationVec(1.0F);
        List<AimPoint> points = new ArrayList<AimPoint>();

        for (AbstractClientPlayerEntity target : client.world.getPlayers()) {
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
            DustParticleEffect color = selected
                    ? new DustParticleEffect(1.0F, 0.30F, 0.10F, 1.25F)
                    : new DustParticleEffect(0.10F, 0.82F, 1.0F, 0.8F);
            client.world.addParticle(color,
                    point.position.x, point.position.y, point.position.z, 0.0, 0.0, 0.0);
        }
    }

    private static List<AimPoint> pointsFor(AbstractClientPlayerEntity target) {
        Box box = target.getBoundingBox();
        double centerX = (box.x1 + box.x2) * 0.5;
        double centerZ = (box.z1 + box.z2) * 0.5;
        double width = box.x2 - box.x1;
        double centerY = (box.y1 + box.y2) * 0.5;
        List<AimPoint> points = new ArrayList<AimPoint>(6);
        points.add(new AimPoint("head", vector(new Vec3d(centerX, box.y2 - 0.12, centerZ))));
        points.add(new AimPoint("torso", vector(new Vec3d(centerX, centerY + 0.12, centerZ))));
        points.add(new AimPoint("pelvis", vector(new Vec3d(centerX, box.y1 + (box.y2 - box.y1) * 0.43, centerZ))));
        points.add(new AimPoint("left", vector(new Vec3d(centerX - width * 0.42, centerY + 0.08, centerZ))));
        points.add(new AimPoint("right", vector(new Vec3d(centerX + width * 0.42, centerY + 0.08, centerZ))));
        points.add(new AimPoint("legs", vector(new Vec3d(centerX, box.y1 + 0.22, centerZ))));
        return points;
    }

    private static dev.briefestboxer.core.Vec3 vector(Vec3d point) {
        return new dev.briefestboxer.core.Vec3(point.x, point.y, point.z);
    }

    private static Vec3d vector(dev.briefestboxer.core.Vec3 point) {
        return new Vec3d(point.x, point.y, point.z);
    }
}
