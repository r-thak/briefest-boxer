package dev.briefestboxer.fabric;

import dev.briefestboxer.core.BriefestBoxerConfig;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.MinecraftClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class BriefestBoxerClient implements ClientModInitializer {
    private static final Logger LOGGER = LogManager.getLogger("Briefest Boxer");

    @Override
    public void onInitializeClient() {
        LOGGER.info("Registering 1.15.2 reachable entity highlights");
        BriefestBoxerConfig.load(MinecraftClient.getInstance().runDirectory.toPath());
    }
}
