package dev.briefestboxer.fabric.mixin;

import dev.briefestboxer.fabric.ReachableHighlightRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.VisibleRegion;
import net.minecraft.client.render.WorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldRenderer.class)
public abstract class WorldRendererMixin {
    @Inject(method = "renderEntities", at = @At("TAIL"))
    private void briefestBoxer$renderReachableHighlights(Camera camera, VisibleRegion visibleRegion,
            float tickDelta, CallbackInfo ci) {
        ReachableHighlightRenderer.render(tickDelta, camera);
    }
}
