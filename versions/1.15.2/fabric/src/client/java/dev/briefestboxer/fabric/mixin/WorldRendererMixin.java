package dev.briefestboxer.fabric.mixin;

import dev.briefestboxer.fabric.ReachableHighlightRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.util.math.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldRenderer.class)
public abstract class WorldRendererMixin {
    @Inject(method = "render", at = @At("TAIL"))
    private void briefestBoxer$renderReachableHighlights(MatrixStack matrices, float tickDelta, long limitTime,
            boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer,
            LightmapTextureManager lightmap, Matrix4f projectionMatrix, CallbackInfo ci) {
        ReachableHighlightRenderer.render(tickDelta, camera);
    }
}
