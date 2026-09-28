package dev.briefestboxer.forge;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.relauncher.Side;

@Mod(modid = BriefestBoxerMod.MOD_ID, name = "Briefest Boxer", version = "0.1.0")
public final class BriefestBoxerMod {
    public static final String MOD_ID = "briefest_boxer";

    @Mod.EventHandler
    public void onInitialize(FMLInitializationEvent event) {
        if (FMLCommonHandler.instance().getSide() == Side.CLIENT) {
            FMLCommonHandler.instance().bus().register(new ForgeClientAimGuide());
        }
    }
}
