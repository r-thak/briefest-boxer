package dev.briefestboxer.fabric;

import dev.briefestboxer.core.Trajectory;
import dev.briefestboxer.core.BriefestBoxerConfig;
import com.terraformersmc.modmenu.ModMenu;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.cubemob.SulfurCube;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/** Compares the client preview's launch velocity with a real server-side hit. */
@SuppressWarnings("UnstableApiUsage")
public final class SulfurTrajectoryClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            var connection = singleplayer.getConnection();
            connection.waitForChunksRender();

            // Foliage has collision geometry in some blocks, but must not hide
            // the visible target surface. Solid blocks must still occlude it.
            BlockPos leafPos = new BlockPos(2, -56, 1);
            BlockPos wallPos = new BlockPos(2, -56, 2);
            singleplayer.getServer().computeOnServer(server -> {
                var level = singleplayer.getConnection().getServerLevel();
                level.setBlockAndUpdate(leafPos, Blocks.OAK_LEAVES.defaultBlockState());
                level.setBlockAndUpdate(wallPos, Blocks.AIR.defaultBlockState());
                return null;
            });
            context.waitTicks(1);
            connection.waitForClientboundPackets();
            boolean visibleThroughLeaves = context.computeOnClient(client ->
                    BriefestBoxerClient.isPointVisible(client, client.player,
                            new Vec3(2.5, -55.5, 0.5), new Vec3(2.5, -55.5, 3.5)));
            if (!visibleThroughLeaves) throw new AssertionError("Tree leaves should not cull the target highlight");
            singleplayer.getServer().computeOnServer(server -> {
                singleplayer.getConnection().getServerLevel().setBlockAndUpdate(
                        wallPos, Blocks.STONE.defaultBlockState());
                return null;
            });
            context.waitTicks(1);
            connection.waitForClientboundPackets();
            boolean visibleThroughWall = context.computeOnClient(client ->
                    BriefestBoxerClient.isPointVisible(client, client.player,
                            new Vec3(2.5, -55.5, 0.5), new Vec3(2.5, -55.5, 3.5)));
            if (visibleThroughWall) throw new AssertionError("Opaque blocks must continue to cull target highlights");
            singleplayer.getServer().computeOnServer(server -> {
                var level = singleplayer.getConnection().getServerLevel();
                level.setBlockAndUpdate(leafPos, Blocks.AIR.defaultBlockState());
                level.setBlockAndUpdate(wallPos, Blocks.AIR.defaultBlockState());
                return null;
            });

            context.computeOnClient(client -> {
                SulfurCube cube = (SulfurCube) BuiltInRegistries.ENTITY_TYPE.getValue(
                        Identifier.withDefaultNamespace("sulfur_cube")).create(client.level, EntitySpawnReason.COMMAND);
                if (cube == null) throw new AssertionError("Could not create a Sulfur Cube for age filtering");
                // EntityType.create() alone produces an adult default instance;
                // explicitly construct the child state before testing the filter.
                cube.setBaby(true);
                cube.setSize(1, true);
                if (BriefestBoxerClient.isAdultSulfurCube(cube)) {
                    throw new AssertionError("A baby Sulfur Cube must not get the adult trajectory preview");
                }
                makeAdult(cube);
                if (!BriefestBoxerClient.isAdultSulfurCube(cube)) {
                    throw new AssertionError("The size-2 adult Sulfur Cube must get the adult trajectory preview");
                }
                cube.discard();
                return null;
            });

            int cubeId = singleplayer.getServer().computeOnServer(server -> {
                var level = singleplayer.getConnection().getServerLevel();
                var player = singleplayer.getConnection().getServerPlayer();
                int floorY = -58;
                for (int x = -3; x <= 3; x++) {
                    // Keep the complete configured forecast over a known floor.
                    // Some absorbed materials carry the cube more than 80 blocks
                    // from launch in 256 ticks, so a short platform made the server
                    // simulation leave the collision geometry seen by the client.
                    for (int z = -100; z <= 100; z++) {
                        level.setBlockAndUpdate(new BlockPos(x, floorY - 1, z), Blocks.STONE.defaultBlockState());
                        for (int y = floorY; y <= floorY + 12; y++) {
                            level.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
                        }
                    }
                }
                for (int x = -1; x <= 1; x++) {
                    for (int y = floorY; y <= floorY + 8; y++) {
                        level.setBlockAndUpdate(new BlockPos(x, y, 8), Blocks.STONE.defaultBlockState());
                    }
                }
                // Keep the whole adult cube and its first flight ticks in
                // frame. At the old two-block camera distance the red surface
                // filled the screenshot, so the cube appeared stationary even
                // though the server position assertions passed.
                // Keep the adult cube inside the client's normal interaction
                // reach too. The previous four-block camera-to-hitbox distance
                // exceeded the client's vanilla reach (even though the server
                // test raised its own reach attribute), so it exercised the
                // trajectory guide without exercising the reachable-surface
                // highlight on the rendered client.
                player.setPos(0.5, floorY, 0.5);
                player.setDeltaMovement(Vec3.ZERO);
                player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ENTITY_INTERACTION_RANGE)
                        .setBaseValue(6.0);
                player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
                        new ItemStack(Items.IRON_SWORD));

                EntityType<?> sulfurCubeType = BuiltInRegistries.ENTITY_TYPE.getValue(
                        Identifier.withDefaultNamespace("sulfur_cube"));
                SulfurCube cube = (SulfurCube) sulfurCubeType.create(level, EntitySpawnReason.COMMAND);
                if (cube == null) throw new AssertionError("Could not create a Sulfur Cube");
                makeAdult(cube);
                if (!cube.equipItem(new ItemStack(Blocks.OAK_LOG))) throw new AssertionError("Log absorption was rejected");
                // Keep the cube's normal AI goals active: the ballistic preview
                // is compared with the live cube to quantify post-hit steering.
                cube.setPos(0.5, floorY, 2.5);
                cube.setDeltaMovement(Vec3.ZERO);
                if (!level.addFreshEntity(cube)) throw new AssertionError("Sulfur Cube spawn was rejected");
                return cube.getId();
            });

            context.waitTicks(40);
            connection.waitForClientboundPackets();

            String[] clientState = new String[1];
            Trajectory prediction = context.computeOnClient(client -> {
                SulfurCube cube = findCube(client, cubeId);
                // Match launch velocity on both sides before the simulated hit.
                cube.setDeltaMovement(Vec3.ZERO);
                // The GameTest teleports the server player during world setup.
                // Resync the client camera entity before taking the visual
                // snapshot so visibility rays originate from the actual player.
                client.player.setPos(0.5, cube.getY(), 0.5);
                client.player.setDeltaMovement(Vec3.ZERO);
                client.gameRenderer.mainCamera().update(client.getDeltaTracker());
                aimAt(client.player, cube);
                client.gameRenderer.mainCamera().update(client.getDeltaTracker());
                clientState[0] = describe(client.player, cube);
                client.hitResult = new EntityHitResult(cube, cube.getBoundingBox().getCenter());
                ItemStack bodyItem = cube.getItemBySlot(EquipmentSlot.BODY);
                ItemStack weapon = client.player.getWeaponItem();
                float charge = client.player.getAttackStrengthScale(0.5F);
                Trajectory path = BriefestBoxerClient.calculateSulfurTrajectory(
                        client, cube, bodyItem, weapon, charge, cube.getBoundingBox());
                return path;
            });
            // Let the normal render hook consume the targeted client hit result
            // once before screenshotting the pre-hit trajectory preview.
            context.waitTicks(1);
            connection.waitForClientboundPackets();
            String targetRay = context.computeOnClient(client -> {
                SulfurCube cube = findCube(client, cubeId);
                if (!(client.hitResult instanceof EntityHitResult hit) || hit.getEntity() != cube) {
                    return "miss=" + client.hitResult + ", player=" + describe(client.player, cube);
                }
                return "hit=" + hit.getEntity().getType() + " at " + hit.getLocation();
            });
            if (!targetRay.startsWith("hit=")) {
                throw new AssertionError("The pre-hit trajectory test must use the actual aimed-at adult cube: "
                        + targetRay);
            }
            // Keep a clearly labeled baseline for comparison with the in-flight
            // capture below. The cube is stationary in this image by design;
            // movement is only considered tested after the hit and client sync.
            context.takeScreenshot("sulfur-trajectory-before-hit-static");
            var first = prediction.getPositions().get(0);
            var second = prediction.getPositions().get(1);
            Vec3 predictedLaunch = new Vec3(second.x - first.x, second.y - first.y, second.z - first.z);
            long[] flightStartTick = new long[1];
            AttackResult attack = singleplayer.getServer().computeOnServer(server -> {
                var level = singleplayer.getConnection().getServerLevel();
                var cube = (SulfurCube) level.getEntity(cubeId);
                Player player = singleplayer.getConnection().getServerPlayer();
                cube.setDeltaMovement(Vec3.ZERO);
                aimAt(player, cube);
                double reach = player.getAttributeValue(
                        net.minecraft.world.entity.ai.attributes.Attributes.ENTITY_INTERACTION_RANGE);
                double hitDistance = player.getEyePosition().distanceTo(cube.getBoundingBox().getCenter());
                if (hitDistance > reach || !player.hasLineOfSight(cube)) {
                    throw new AssertionError("Adult Sulfur Cube test hit must be visible and in reach: distance="
                            + hitDistance + ", reach=" + reach + ", " + describe(player, cube));
                }
                String state = describe(player, cube);
                player.attack(cube);
                flightStartTick[0] = level.getGameTime();
                return new AttackResult(cube.getDeltaMovement(), state);
            });

            // A zero-velocity prediction could match a zero-velocity server hit
            // and still pass the comparison below. Require a real forward hit
            // impulse so the rest of this test actually validates a moving cube.
            if (attack.velocity().length() < 0.25 || attack.velocity().z < 0.15) {
                throw new AssertionError("Hitting the adult Sulfur Cube must launch it forward: velocity="
                        + attack.velocity() + "; server=" + attack.state());
            }
            System.out.println("[Briefest Boxer GameTest] Adult Sulfur Cube hit impulse confirmed: "
                    + attack.velocity() + " blocks/tick");

            double error = predictedLaunch.distanceTo(attack.velocity());
            if (error > 0.01) {
                throw new AssertionError("Sulfur Cube launch mismatch: predicted " + predictedLaunch
                        + ", actual " + attack.velocity() + ", error " + error + "; client=" + clientState[0]
                        + "; server=" + attack.state());
            }

            // Advance the actual singleplayer world so the hit cube visibly moves
            // and collision callbacks run normally. Calling cube.tick() directly
            // only advances the entity in place from the test's perspective: it
            // skips the world's tick/network/render cadence and produced a static
            // looking fixture even though the math loop had advanced.
            var actualPath = new java.util.ArrayList<MotionSample>();
            StringBuilder pathTrace = new StringBuilder();
            boolean capturedInFlight = false;
            for (int sample = 1; sample < prediction.getPositions().size(); sample++) {
                context.waitTicks(1);
                // Minecraft synchronizes entity positions periodically. Wait for
                // the first real clientbound update, but place the test wall far
                // enough away that this wait captures the cube while still moving.
                if (!capturedInFlight) connection.waitForClientboundEntityUpdates(
                        BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.withDefaultNamespace("sulfur_cube")));
                MotionSample actual = singleplayer.getServer().computeOnServer(server -> {
                    var level = singleplayer.getConnection().getServerLevel();
                    var cube = (SulfurCube) level.getEntity(cubeId);
                    if (cube == null) throw new AssertionError("Adult Sulfur Cube disappeared during flight");
                    return new MotionSample(cube.getBoundingBox().getCenter(), cube.getDeltaMovement(),
                            cube.onGround(), cube.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.GRAVITY),
                            cube.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.BOUNCINESS),
                            cube.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.AIR_DRAG_MODIFIER),
                            level.getGameTime());
                });
                actualPath.add(actual);
                int elapsedTicks = (int) (actual.worldTick() - flightStartTick[0]);
                if (elapsedTicks <= 0) continue;
                if (elapsedTicks >= prediction.getPositions().size()) break;
                if (!capturedInFlight && elapsedTicks > 0) {
                    // Capture a live client-tracked pose before the cube reaches
                    // the wall that is used for the collision check.
                    ClientCubeSample clientCube = context.computeOnClient(client -> {
                        SulfurCube cube = findCube(client, cubeId);
                        // Keep the camera fixed at the launch view. Following the
                        // cube here makes it stay centered in the screenshot and
                        // falsely suggests that the entity never moved.
                        client.hitResult = new EntityHitResult(cube, cube.getBoundingBox().getCenter());
                        return new ClientCubeSample(cube.getBoundingBox().getCenter(),
                                cube.isAlive(), cube.isInvisible());
                    });
                    Vec3 launchCenter = new Vec3(first.x, first.y, first.z);
                    double clientDisplacement = clientCube.center().distanceTo(launchCenter);
                    if (!clientCube.alive() || clientCube.invisible() || clientDisplacement < 0.75) {
                        throw new AssertionError("Adult Sulfur Cube did not visibly advance on the client: "
                                + "launch=" + launchCenter + ", server=" + actual.center()
                                + ", client=" + clientCube + ", clientDisplacement=" + clientDisplacement);
                    }
                    System.out.println("[Briefest Boxer GameTest] Adult Sulfur Cube client motion confirmed: launch="
                            + launchCenter + ", client=" + clientCube.center()
                            + ", displacement=" + clientDisplacement + " blocks");
                    context.computeOnClient(client -> {
                        SulfurCube cube = findCube(client, cubeId);
                        // Turn the fixed camera perpendicular to the launch
                        // direction. Looking straight down the flight path made
                        // forward motion look like a static target in screenshots.
                        client.player.setYRot(client.player.getYRot() + 35.0F);
                        client.hitResult = new EntityHitResult(cube, cube.getBoundingBox().getCenter());
                        return null;
                    });
                    // Capture at this synchronized, still-airborne sample. Waiting
                    // another game tick would put the cube into the nearby wall.
                    context.takeScreenshot("sulfur-trajectory-preview-in-flight");
                    capturedInFlight = true;
                }
                Vec3 actualCenter = actual.center();
                var expectedSample = prediction.getPositions().get(elapsedTicks);
                Vec3 expectedCenter = new Vec3(expectedSample.x, expectedSample.y, expectedSample.z);
                var previousSample = prediction.getPositions().get(elapsedTicks - 1);
                Vec3 expectedMotion = expectedCenter.subtract(
                        new Vec3(previousSample.x, previousSample.y, previousSample.z));
                double positionError = actualCenter.distanceTo(expectedCenter);
                pathTrace.append(elapsedTicks).append(": expected=").append(expectedCenter)
                        .append(" actual=").append(actualCenter).append(" velocity=")
                        .append(actual.velocity()).append("; ");
                if (positionError > 0.10) {
                    throw new AssertionError("Sulfur Cube path diverged at tick " + elapsedTicks + ": predicted "
                            + expectedCenter + " (step " + expectedMotion + "), actual " + actual
                            + ", error " + positionError + "; client=" + clientState[0] + "; trace=" + pathTrace);
                }
                // Stop checking after the planned wall collision. Later in this
                // fixture the cube can return far enough to collide with the test
                // player, which is outside the path model being validated here.
                if (actualPath.size() > 1) {
                    MotionSample previous = actualPath.get(actualPath.size() - 2);
                    if (actual.center().z < previous.center().z - 1.0E-4) break;
                }
            }
            double previousZ = prediction.getPositions().get(0).z;
            boolean hitWall = false;
            for (MotionSample sample : actualPath) {
                if (sample.center().z < previousZ - 1.0E-4) hitWall = true;
                previousZ = sample.center().z;
            }
            if (!hitWall) throw new AssertionError("The Sulfur Cube did not collide with the test wall");

            MotionSample inFlight = actualPath.get(Math.min(4, actualPath.size() - 1));
            double visibleDisplacement = inFlight.center().distanceTo(new Vec3(first.x, first.y, first.z));
            if (visibleDisplacement < 0.75) {
                throw new AssertionError("Adult Sulfur Cube did not visibly move during the real world ticks: "
                        + "displacement=" + visibleDisplacement + ", trace=" + pathTrace);
            }

            int waterCubeId = singleplayer.getServer().computeOnServer(server -> {
                var level = singleplayer.getConnection().getServerLevel();
                var player = singleplayer.getConnection().getServerPlayer();
                for (int x = -4; x <= 4; x++) {
                    for (int z = 19; z <= 54; z++) {
                        level.setBlockAndUpdate(new BlockPos(x, -59, z), Blocks.STONE.defaultBlockState());
                        for (int y = -58; y <= -52; y++) {
                            boolean wall = x == -4 || x == 4 || z == 19 || z == 54;
                            level.setBlockAndUpdate(new BlockPos(x, y, z),
                                    wall ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
                        }
                    }
                }
                for (int x = -3; x <= 3; x++) {
                    level.setBlockAndUpdate(new BlockPos(x, -58, 29), Blocks.STONE.defaultBlockState());
                    // Keep a long, one-way stream so the prediction exercises
                    // water entry, sustained current, buoyancy, and fluid drag.
                    for (int z = 30; z <= 50; z++) {
                        level.setBlockAndUpdate(new BlockPos(x, -58, z), Blocks.WATER.defaultBlockState());
                    }
                }
                player.setPos(0.5, -58.0, 21.5);
                player.setDeltaMovement(Vec3.ZERO);
                player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
                        new ItemStack(Items.IRON_SWORD));
                SulfurCube cube = (SulfurCube) BuiltInRegistries.ENTITY_TYPE.getValue(
                        Identifier.withDefaultNamespace("sulfur_cube")).create(level, EntitySpawnReason.COMMAND);
                if (cube == null) throw new AssertionError("Could not create a water-physics Sulfur Cube");
                makeAdult(cube);
                if (!cube.equipItem(new ItemStack(Blocks.OAK_LOG))) {
                    throw new AssertionError("Water-physics Sulfur Cube rejected its buoyant oak-log body item");
                }
                cube.getGoalSelector().removeAllGoals(goal -> true);
                cube.setPos(0.5, -58.0, 26.5);
                cube.setDeltaMovement(Vec3.ZERO);
                if (!level.addFreshEntity(cube)) throw new AssertionError("Water-physics Sulfur Cube spawn was rejected");
                return cube.getId();
            });
            context.waitTicks(40);
            connection.waitForClientboundPackets();

            Vec3[] waterServerStart = new Vec3[1];
            singleplayer.getServer().computeOnServer(server -> {
                SulfurCube cube = (SulfurCube) singleplayer.getConnection().getServerLevel().getEntity(waterCubeId);
                waterServerStart[0] = cube.getBoundingBox().getCenter();
                return null;
            });

            Trajectory waterPrediction = context.computeOnClient(client -> {
                SulfurCube cube = findCube(client, waterCubeId);
                // Client entity interpolation can lag the authoritative cube by a
                // fraction of a block. Align the test predictor with the exact server
                // origin so this assertion measures physics, not packet interpolation.
                cube.setPos(waterServerStart[0].x,
                        waterServerStart[0].y - cube.getBbHeight() * 0.5,
                        waterServerStart[0].z);
                cube.setDeltaMovement(Vec3.ZERO);
                client.player.resetAttackStrengthTicker();
                aimAt(client.player, cube);
                client.hitResult = new EntityHitResult(cube, cube.getBoundingBox().getCenter());
                return BriefestBoxerClient.calculateSulfurTrajectory(client, cube,
                        cube.getItemBySlot(EquipmentSlot.BODY), client.player.getWeaponItem(),
                        client.player.getAttackStrengthScale(0.5F), cube.getBoundingBox());
            });
            AttackResult waterAttack = singleplayer.getServer().computeOnServer(server -> {
                var level = singleplayer.getConnection().getServerLevel();
                SulfurCube cube = (SulfurCube) level.getEntity(waterCubeId);
                Player player = singleplayer.getConnection().getServerPlayer();
                cube.setDeltaMovement(Vec3.ZERO);
                player.resetAttackStrengthTicker();
                aimAt(player, cube);
                if (cube.isInWater()) throw new AssertionError("Water-entry test cube must start dry");
                player.attack(cube);
                return new AttackResult(cube.getDeltaMovement(), describe(player, cube));
            });
            var waterStart = waterPrediction.getPositions().get(0);
            var waterNext = waterPrediction.getPositions().get(1);
            Vec3 waterPredictedLaunch = new Vec3(waterNext.x - waterStart.x,
                    waterNext.y - waterStart.y, waterNext.z - waterStart.z);
            double waterLaunchError = waterPredictedLaunch.distanceTo(waterAttack.velocity());
            // The forecast samples water flow from the client world while the server
            // attack vector is measured before the next fluid-current update.
            if (waterLaunchError > 0.03) {
                throw new AssertionError("Submerged adult Sulfur Cube launch mismatch: predicted "
                        + waterPredictedLaunch + ", actual " + waterAttack.velocity()
                        + ", error=" + waterLaunchError + "; server=" + waterAttack.state());
            }
            StringBuilder waterTrace = new StringBuilder();
            boolean[] enteredWater = {false};
            double maxWaterError = 0.0;
            for (int tick = 1; tick <= 48; tick++) {
                context.waitTicks(1);
                int elapsedTick = tick;
                MotionSample actual = singleplayer.getServer().computeOnServer(server -> {
                    var level = singleplayer.getConnection().getServerLevel();
                    SulfurCube cube = (SulfurCube) level.getEntity(waterCubeId);
                    if (cube == null) throw new AssertionError("Submerged Sulfur Cube disappeared");
                    enteredWater[0] |= cube.isInWater();
                    return new MotionSample(cube.getBoundingBox().getCenter(), cube.getDeltaMovement(),
                            cube.onGround(), cube.getAttributeValue(
                                    net.minecraft.world.entity.ai.attributes.Attributes.GRAVITY),
                            cube.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.BOUNCINESS),
                            cube.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.AIR_DRAG_MODIFIER),
                            level.getGameTime());
                });
                var expectedPosition = waterPrediction.getPositions().get(elapsedTick);
                Vec3 predictionOrigin = new Vec3(waterStart.x, waterStart.y, waterStart.z);
                Vec3 serverOrigin = waterServerStart[0];
                Vec3 expected = new Vec3(expectedPosition.x, expectedPosition.y, expectedPosition.z)
                        .add(serverOrigin.subtract(predictionOrigin));
                double waterError = actual.center().distanceTo(expected);
                maxWaterError = Math.max(maxWaterError, waterError);
                waterTrace.append(tick).append(": predicted=").append(expected)
                        .append(" actual=").append(actual.center()).append(" velocity=")
                        .append(actual.velocity()).append(" onGround=").append(actual.onGround())
                        .append(" error=").append(waterError).append("; ");
                if (waterError > 0.20) {
                    throw new AssertionError("Water Sulfur Cube path diverged at tick " + tick
                            + ": predicted=" + expected + ", actual=" + actual.center()
                            + ", error=" + waterError + "; server=" + waterAttack.state()
                            + "; trace=" + waterTrace);
                }
            }
            if (!enteredWater[0]) throw new AssertionError("Adult Sulfur Cube did not enter water during the measured path");
            System.out.println("[Briefest Boxer GameTest] Adult Sulfur Cube dry-to-water trajectory matched 48 real world ticks; max error="
                    + maxWaterError + " blocks");

            int vanillaCubeId = singleplayer.getServer().computeOnServer(server -> {
                var level = singleplayer.getConnection().getServerLevel();
                var player = singleplayer.getConnection().getServerPlayer();
                player.resetAttackStrengthTicker();
                player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
                        new ItemStack(Items.IRON_SWORD));
                SulfurCube cube = (SulfurCube) BuiltInRegistries.ENTITY_TYPE.getValue(
                        Identifier.withDefaultNamespace("sulfur_cube")).create(level, EntitySpawnReason.COMMAND);
                if (cube == null) throw new AssertionError("Could not create an empty Sulfur Cube");
                makeAdult(cube);
                cube.getGoalSelector().removeAllGoals(goal -> true);
                cube.setNoGravity(true);
                cube.setPos(-1.5, -58.0, 4.5);
                cube.setOnGround(true);
                cube.setDeltaMovement(Vec3.ZERO);
                if (!level.addFreshEntity(cube)) throw new AssertionError("Empty Sulfur Cube spawn was rejected");
                return cube.getId();
            });
            context.waitTicks(40);
            connection.waitForClientboundPackets();

            Trajectory vanillaPrediction = context.computeOnClient(client -> {
                SulfurCube cube = findCube(client, vanillaCubeId);
                cube.setDeltaMovement(Vec3.ZERO);
                cube.setOnGround(true);
                client.player.resetAttackStrengthTicker();
                aimAt(client.player, cube);
                client.hitResult = new EntityHitResult(cube, cube.getBoundingBox().getCenter());
                return BriefestBoxerClient.calculateSulfurTrajectory(client, cube,
                        cube.getItemBySlot(EquipmentSlot.BODY), client.player.getWeaponItem(),
                        client.player.getAttackStrengthScale(0.5F), cube.getBoundingBox());
            });
            AttackResult vanillaAttack = singleplayer.getServer().computeOnServer(server -> {
                SulfurCube cube = (SulfurCube) singleplayer.getConnection().getServerLevel().getEntity(vanillaCubeId);
                Player player = singleplayer.getConnection().getServerPlayer();
                cube.setDeltaMovement(Vec3.ZERO);
                cube.setOnGround(true);
                player.resetAttackStrengthTicker();
                aimAt(player, cube);
                player.attack(cube);
                return new AttackResult(cube.getDeltaMovement(), describe(player, cube));
            });
            var vanillaStart = vanillaPrediction.getPositions().get(0);
            var vanillaNext = vanillaPrediction.getPositions().get(1);
            Vec3 vanillaPredictedLaunch = new Vec3(vanillaNext.x - vanillaStart.x,
                    vanillaNext.y - vanillaStart.y, vanillaNext.z - vanillaStart.z);
            double vanillaError = vanillaPredictedLaunch.distanceTo(vanillaAttack.velocity());
            if (vanillaError > 0.01) {
                throw new AssertionError("Empty Sulfur Cube launch mismatch: predicted "
                        + vanillaPredictedLaunch + ", actual " + vanillaAttack.velocity()
                        + ", error " + vanillaError + "; server=" + vanillaAttack.state());
            }

            // Exercise materially different archetype powers and pitch angles.
            // These cases target the knockback formula directly, so no hit-ray
            // or rendering behavior can hide an incorrect launch prediction.
            LaunchCase[] launchCases = {
                    new LaunchCase(Blocks.OAK_LOG, Items.IRON_SWORD, -0.35),
                    new LaunchCase(Blocks.OAK_LOG, Items.IRON_SWORD, 0.35),
                    new LaunchCase(Blocks.BLUE_ICE, Items.IRON_SWORD, 0.35),
                    new LaunchCase(Blocks.WOOL.white(), Items.DIAMOND_SWORD, -0.20),
                    new LaunchCase(Blocks.IRON_BLOCK, Items.DIAMOND_SWORD, 0.20),
                    new LaunchCase(Blocks.HONEYCOMB_BLOCK, Items.MACE, -0.35),
                    new LaunchCase(Blocks.MAGMA_BLOCK, Items.MACE, 0.35),
                    new LaunchCase(Blocks.SPONGE, Items.IRON_AXE, -0.25),
                    new LaunchCase(Blocks.DIRT, Items.DIAMOND_AXE, 0.25),
                    new LaunchCase(Blocks.MYCELIUM, Items.NETHERITE_SWORD, -0.15),
                    new LaunchCase(Blocks.SOUL_SAND, Items.STONE_SWORD, 0.15),
                    new LaunchCase(Blocks.TNT, Items.STICK, 0.30),
                    new LaunchCase(Blocks.STONE, Items.IRON_SWORD, 0.0),
                    // Absorbed cubes bypass LivingEntity's armor damage path.
                    // Give this one extreme armor so an incorrect prediction-side
                    // armor reduction is obvious in the launch-velocity comparison.
                    new LaunchCase(Blocks.OAK_LOG, Items.IRON_SWORD, 0.0, 30.0)
            };
            Vec3 lowAimOakLogLaunch = null;
            Vec3 highAimOakLogLaunch = null;
            for (LaunchCase launchCase : launchCases) {
                int caseCubeId = singleplayer.getServer().computeOnServer(server -> {
                var level = singleplayer.getConnection().getServerLevel();
                var player = singleplayer.getConnection().getServerPlayer();
                player.setPos(0.5, -58.0, 0.5);
                player.setDeltaMovement(Vec3.ZERO);
                player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
                        new ItemStack(launchCase.weapon()));
                    SulfurCube cube = (SulfurCube) BuiltInRegistries.ENTITY_TYPE.getValue(
                            Identifier.withDefaultNamespace("sulfur_cube")).create(level, EntitySpawnReason.COMMAND);
                    if (cube == null) throw new AssertionError("Could not create archetype test Sulfur Cube");
                    makeAdult(cube);
                    if (!cube.equipItem(new ItemStack(launchCase.block()))) {
                        throw new AssertionError("Sulfur Cube rejected archetype item " + launchCase.block());
                    }
                    cube.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR)
                            .setBaseValue(launchCase.armorBase());
                    // Isolate absorbed-material physics in the per-archetype cases;
                    // the live-goal adult case above covers normal AI behavior.
                    cube.getGoalSelector().removeAllGoals(goal -> true);
                    cube.setPos(0.5, -58.0, 4.5);
                    cube.setOnGround(true);
                    cube.setDeltaMovement(Vec3.ZERO);
                    if (!level.addFreshEntity(cube)) throw new AssertionError("Archetype Sulfur Cube spawn was rejected");
                return cube.getId();
            });
            connection.waitForClientboundPackets();
            // Use fully charged, representative hits for the cross-archetype flight checks.
            context.waitTicks(40);
            connection.waitForClientboundPackets();

            Trajectory casePrediction = context.computeOnClient(client -> {
                SulfurCube cube = findCube(client, caseCubeId);
                cube.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR)
                        .setBaseValue(launchCase.armorBase());
                cube.setOnGround(true);
                cube.setDeltaMovement(Vec3.ZERO);
                aimAt(client.player, cube, launchCase.aimOffset());
                    client.hitResult = new EntityHitResult(cube, cube.getBoundingBox().getCenter());
                    return BriefestBoxerClient.calculateSulfurTrajectory(client, cube,
                            cube.getItemBySlot(EquipmentSlot.BODY), client.player.getWeaponItem(),
                            client.player.getAttackStrengthScale(0.5F), cube.getBoundingBox());
                });
                if (casePrediction.getPositions().size() != BriefestBoxerConfig.trajectorySteps + 1) {
                    throw new AssertionError("Trajectory forecast did not contain the configured number of steps: "
                            + casePrediction.getPositions().size() + " for " + BriefestBoxerConfig.trajectorySteps);
                }
                AttackResult caseAttack = singleplayer.getServer().computeOnServer(server -> {
                    var level = singleplayer.getConnection().getServerLevel();
                    SulfurCube cube = (SulfurCube) level.getEntity(caseCubeId);
                    Player player = singleplayer.getConnection().getServerPlayer();
                    cube.setOnGround(true);
                    cube.setDeltaMovement(Vec3.ZERO);
                    aimAt(player, cube, launchCase.aimOffset());
                    player.attack(cube);
                    Vec3 launchVelocity = cube.getDeltaMovement();
                    String state = describe(player, cube);
                    // Keep the real post-hit flight clear of player-contact pushes;
                    // the client preview models the cube's ballistic response.
                    player.setPos(20.5, -58.0, 20.5);
                    player.setDeltaMovement(Vec3.ZERO);
                    java.util.ArrayList<MotionSample> samples = new java.util.ArrayList<>();
                    int verifiedTicks = launchCase.armorBase() > 0.0
                            ? Math.min(128, casePrediction.getPositions().size() - 1)
                            : casePrediction.getPositions().size() - 1;
                    for (int tick = 1; tick <= verifiedTicks; tick++) {
                        cube.tick();
                        samples.add(new MotionSample(cube.getBoundingBox().getCenter(), cube.getDeltaMovement(),
                                cube.onGround(), cube.getAttributeValue(
                                        net.minecraft.world.entity.ai.attributes.Attributes.GRAVITY),
                                cube.getAttributeValue(
                                        net.minecraft.world.entity.ai.attributes.Attributes.BOUNCINESS),
                                cube.getAttributeValue(
                                        net.minecraft.world.entity.ai.attributes.Attributes.AIR_DRAG_MODIFIER),
                                level.getGameTime()));
                    }
                    AttackResult result = new AttackResult(launchVelocity, state, samples);
                    cube.discard();
                    return result;
                });
                var caseStart = casePrediction.getPositions().get(0);
                var caseNext = casePrediction.getPositions().get(1);
                Vec3 casePredictedLaunch = new Vec3(caseNext.x - caseStart.x,
                        caseNext.y - caseStart.y, caseNext.z - caseStart.z);
                double caseError = casePredictedLaunch.distanceTo(caseAttack.velocity());
                if (caseError > 0.01) {
                    throw new AssertionError("Sulfur Cube launch mismatch for " + launchCase + ": predicted "
                            + casePredictedLaunch + ", actual " + caseAttack.velocity()
                            + ", error " + caseError + "; server=" + caseAttack.state());
                }
                if (launchCase.block() == Blocks.OAK_LOG && launchCase.weapon() == Items.IRON_SWORD) {
                    if (launchCase.aimOffset() < 0.0) lowAimOakLogLaunch = caseAttack.velocity();
                    if (launchCase.aimOffset() > 0.0) highAimOakLogLaunch = caseAttack.velocity();
                }

                StringBuilder caseTrace = new StringBuilder();
                int verifiedTicks = launchCase.armorBase() > 0.0
                        ? Math.min(128, casePrediction.getPositions().size() - 1)
                        : casePrediction.getPositions().size() - 1;
                for (int tick = 1; tick <= verifiedTicks; tick++) {
                    MotionSample actual = caseAttack.path().get(tick - 1);
                    var expected = casePrediction.getPositions().get(tick);
                    var expectedPrevious = casePrediction.getPositions().get(tick - 1);
                    Vec3 expectedCenter = new Vec3(expected.x, expected.y, expected.z);
                    Vec3 expectedPreviousCenter = new Vec3(expectedPrevious.x, expectedPrevious.y, expectedPrevious.z);
                    Vec3 actualPreviousCenter = tick == 1 ? caseAttack.path().getFirst().center()
                            .subtract(caseAttack.path().getFirst().velocity())
                            : caseAttack.path().get(tick - 2).center();
                    double positionError = actual.center().distanceTo(expectedCenter);
                    caseTrace.append(tick).append(": expected=").append(expectedCenter)
                            .append(" actual=").append(actual.center()).append(" velocity=")
                            .append(actual.velocity()).append(" onGround=").append(actual.onGround())
                            .append("; ");
                    // Collision timing and tiny retained impulses accumulate at long
                    // range. Keep every forecast sample within 30 cm of the live
                    // cube across the full 12.8-second prediction.
                    if (positionError > 0.30) {
                        throw new AssertionError("Sulfur Cube path mismatch for " + launchCase + " at tick "
                                + tick + ": predicted " + expectedCenter + " from " + expectedPreviousCenter
                                + ", actual " + actual.center() + " from " + actualPreviousCenter
                                + " with velocity " + actual.velocity()
                                + ", error " + positionError + "; server=" + caseAttack.state()
                                + "; trace=" + caseTrace);
                    }
                }
            }
            if (lowAimOakLogLaunch == null || highAimOakLogLaunch == null
                    || Math.abs(lowAimOakLogLaunch.y - highAimOakLogLaunch.y) < 0.10) {
                throw new AssertionError("Aiming at different vertical parts of the same adult Sulfur Cube "
                        + "did not produce distinct launch heights: lower=" + lowAimOakLogLaunch
                        + ", upper=" + highAimOakLogLaunch);
            }

            context.computeOnClient(client -> {
                if (!ModMenu.hasConfigScreen("briefest_boxer")) {
                    throw new AssertionError("Mod Menu did not register Briefest Boxer's configuration screen");
                }
                var screen = ModMenu.getConfigScreen("briefest_boxer", null);
                if (screen == null) throw new AssertionError("Mod Menu returned no Briefest Boxer config screen");
                client.setScreenAndShow(screen);
                if (screen.children().size() != 9) {
                    throw new AssertionError("Briefest Boxer config screen should expose 9 controls, got "
                            + screen.children().size());
                }
                return null;
            });
            context.takeScreenshot("briefest-boxer-modmenu-config");
        }
    }

    private static String describe(Player player, SulfurCube cube) {
        return "player=" + player.position() + ", eye=" + player.getEyePosition()
                + ", look=" + player.getLookAngle() + ", cooldown=" + player.getAttackStrengthScale(0.5F)
                + ", attack=" + player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE)
                + ", sprint=" + player.isSprinting() + ", onGround=" + player.onGround()
                + ", cube=" + cube.position() + ", center=" + cube.getBoundingBox().getCenter()
                + ", motion=" + cube.getDeltaMovement() + ", height=" + cube.getBbHeight()
                + ", gravity=" + cube.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.GRAVITY)
                + ", bounce=" + cube.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.BOUNCINESS)
                + ", armor=" + cube.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR)
                + ", toughness=" + cube.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR_TOUGHNESS)
                + ", knockRes=" + cube.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.KNOCKBACK_RESISTANCE)
                + ", airDrag=" + cube.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.AIR_DRAG_MODIFIER)
                + ", frictionModifier=" + cube.getAttributeValue(
                        net.minecraft.world.entity.ai.attributes.Attributes.FRICTION_MODIFIER)
                + ", groundBlockFriction=" + cube.getBlockStateOn().getBlock().getFriction()
                + ", archetypes=" + cube.matchingArchetypes(cube.getItemBySlot(EquipmentSlot.BODY)).stream()
                        .map(a -> a.knockbackModifiers().toString()).toList();
    }

    private record AttackResult(Vec3 velocity, String state, java.util.List<MotionSample> path) {
        private AttackResult(Vec3 velocity, String state) {
            this(velocity, state, java.util.List.of());
        }
    }
    private record LaunchCase(net.minecraft.world.level.block.Block block,
                              net.minecraft.world.item.Item weapon, double aimOffset, double armorBase) {
        private LaunchCase(net.minecraft.world.level.block.Block block,
                           net.minecraft.world.item.Item weapon, double aimOffset) {
            this(block, weapon, aimOffset, 0.0);
        }
    }
    private record MotionSample(Vec3 center, Vec3 velocity, boolean onGround,
                                double gravity, double bounciness, double airDrag, long worldTick) {}
    private record ClientCubeSample(Vec3 center, boolean alive, boolean invisible) {}

    private static SulfurCube findCube(Minecraft client, int id) {
        var entity = client.level.getEntity(id);
        if (!(entity instanceof SulfurCube cube)) {
            throw new AssertionError("Sulfur Cube " + id + " is not present on the client");
        }
        return cube;
    }

    private static void makeAdult(SulfurCube cube) {
        // Adult Sulfur Cubes are size 2 and are not in the ageable baby state.
        cube.setBaby(false);
        cube.setSize(2, true);
        if (cube.getSize() != 2 || cube.isBaby()) {
            throw new AssertionError("Trajectory fixture must be an adult size-2 Sulfur Cube");
        }
    }

    private static void aimAt(Player player, SulfurCube cube) {
        aimAt(player, cube, 0.0);
    }

    private static void aimAt(Player player, SulfurCube cube, double yOffset) {
        player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,
                cube.getBoundingBox().getCenter().add(0.0, yOffset, 0.0));
    }
}
