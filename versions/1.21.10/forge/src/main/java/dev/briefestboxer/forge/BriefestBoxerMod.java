package dev.briefestboxer.forge;

import net.minecraftforge.fml.common.Mod;

@Mod(BriefestBoxerMod.MOD_ID)
public final class BriefestBoxerMod {
    public static final String MOD_ID = "briefest_boxer";

    public BriefestBoxerMod() {
        net.minecraftforge.client.event.AddFramePassEvent.BUS.addListener(ForgeClientAimGuide::addRenderPass);
    }
}
