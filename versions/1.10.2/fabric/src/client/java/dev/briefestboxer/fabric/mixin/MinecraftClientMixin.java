package dev.briefestboxer.fabric.mixin;

import dev.briefestboxer.fabric.BriefestBoxerClient;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void briefestBoxer$tick(CallbackInfo callbackInfo) {
        BriefestBoxerClient.onClientTick((MinecraftClient) (Object) this);
    }
}
