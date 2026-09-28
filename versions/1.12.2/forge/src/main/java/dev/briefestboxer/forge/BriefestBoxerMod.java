package dev.briefestboxer.forge;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.relauncher.Side;

@Mod(modid = BriefestBoxerMod.MOD_ID, name = "Briefest Boxer", version = "0.1.0", acceptableRemoteVersions = "*")
public final class BriefestBoxerMod {
    public static final String MOD_ID = "briefest_boxer";

    @Mod.EventHandler
    public void initialize(FMLInitializationEvent event) {
        if (FMLCommonHandler.instance().getEffectiveSide() == Side.CLIENT) {
            FMLCommonHandler.instance().bus().register(new ForgeClientAimGuide());
        }
    }
}
