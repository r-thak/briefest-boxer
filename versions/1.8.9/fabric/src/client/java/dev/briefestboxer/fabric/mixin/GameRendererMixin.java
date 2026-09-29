package dev.briefestboxer.fabric.mixin;

import dev.briefestboxer.fabric.BriefestBoxerClient;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(method = "renderWorld(FJ)V", at = @At("TAIL"))
    private void briefestBoxer$renderReachableHighlight(float tickDelta, long limitTime, CallbackInfo callbackInfo) {
        BriefestBoxerClient.renderReachableHighlight(tickDelta);
    }
}
