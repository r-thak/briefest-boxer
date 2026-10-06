package dev.briefestboxer.fabric;

import dev.briefestboxer.core.Trajectory;
import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.SimpleGizmoCollector;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.cubemob.SulfurCube;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/** Checks frame interpolation against vanilla's renderer, with distinct tick histories. */
final class OverlayRenderClientChecks {
    static void run(Minecraft client, SulfurCube cube) {
        cube.setId(1_000_000);
        cube.setPos(8.0, -58.0, 10.0);
        cube.xOld = 4.0;
        cube.yOld = -56.0;
        cube.zOld = 2.0;
        cube.xo = -20.0;
        cube.yo = -20.0;
        cube.zo = -20.0;
        var renderer = client.getEntityRenderDispatcher().getRenderer(cube);
        for (float fraction : new float[]{0.0F, 0.25F, 0.5F, 0.75F, 1.0F}) {
            var state = renderer.createRenderState(cube, fraction);
            var box = BriefestBoxerClient.interpolatedBounds(cube, fraction);
            Vec3 offset = box.getCenter().subtract(cube.getBoundingBox().getCenter());
            Vec3 position = cube.position().add(offset);
            if (position.distanceTo(new Vec3(state.x, state.y, state.z)) > 1.0E-9) {
                throw new AssertionError("Overlay did not match vanilla render interpolation at " + fraction);
            }
        }

        // A default-lifetime gizmo is emitted once before drain removes it.
        // Persistence is unnecessary and would accumulate old poses at high FPS.
        var collector = new SimpleGizmoCollector();
        for (int frame = 0; frame < 10; frame++) {
            try (var ignored = Gizmos.withCollector(collector)) {
                Gizmos.point(Vec3.ZERO, 0xFFFF0000, 1.0F).setAlwaysOnTop();
            }
            if (collector.drainGizmos().size() != 1 || !collector.getGizmos().isEmpty()) {
                throw new AssertionError("Overlay gizmos accumulated across frames");
            }
        }

        SulfurPredictionCache cache = new SulfurPredictionCache();
        ItemStack sword = new ItemStack(Items.IRON_SWORD);
        ItemStack body = new ItemStack(Items.OAK_LOG);
        int[] calculations = {0};
        java.util.function.Supplier<Trajectory> calculate = () -> {
            calculations[0]++;
            return Trajectory.freeFlight(new dev.briefestboxer.core.Vec3(0, 0, 0),
                    new dev.briefestboxer.core.Vec3(0, 0, 0), new dev.briefestboxer.core.Vec3(0, 0, 0), 1, 1);
        };
        var first = cache.get(client, cube, body, sword, 1.0F, cube.getBoundingBox(), calculate);
        cube.xOld -= 1.0; // Render movement must not rerun identical physics.
        var repeated = cache.get(client, cube, body, sword, 1.0F, cube.getBoundingBox(), calculate);
        if (first != repeated || calculations[0] != 1) throw new AssertionError("Forecast was not reused");
        cache.get(client, cube, body, new ItemStack(Items.WOODEN_SWORD), 1.0F, cube.getBoundingBox(), calculate);
        if (calculations[0] != 2) throw new AssertionError("Held item did not invalidate forecast");
        cache.get(client, cube, body, sword, 0.5F, cube.getBoundingBox(), calculate);
        if (calculations[0] != 3) throw new AssertionError("Attack charge did not invalidate forecast");
        cube.setDeltaMovement(new Vec3(0.1, 0.0, 0.0));
        cache.get(client, cube, body, sword, 0.5F, cube.getBoundingBox(), calculate);
        if (calculations[0] != 4) throw new AssertionError("Cube movement did not invalidate forecast");
        cube.getAttribute(Attributes.GRAVITY).setBaseValue(0.05);
        cache.get(client, cube, body, sword, 0.5F, cube.getBoundingBox(), calculate);
        if (calculations[0] != 5) throw new AssertionError("Cube attributes did not invalidate forecast");
        cube.tickCount++;
        cache.get(client, cube, body, sword, 0.5F, cube.getBoundingBox(), calculate);
        if (calculations[0] != 6) throw new AssertionError("New tick did not invalidate forecast");
        System.out.println("[Briefest Boxer GameTest] Overlay matches vanilla at five frame fractions; "
                + "gizmos drain once and forecast cache invalidation passed");
    }
}
