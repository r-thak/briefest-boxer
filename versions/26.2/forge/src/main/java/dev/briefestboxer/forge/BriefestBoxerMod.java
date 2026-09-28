package dev.briefestboxer.forge;

import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(BriefestBoxerMod.MOD_ID)
public final class BriefestBoxerMod {
    public static final String MOD_ID = "briefest_boxer";
    private static final Logger LOGGER = LoggerFactory.getLogger("Briefest Boxer");

    public BriefestBoxerMod() {
        LOGGER.info("Registering 26.2 Forge aim-point and Sulfur Cube guides");
    }
}
