package com.deadmanshand.mixin;

import com.deadmanshand.Borderland;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.SpawnHelper;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Nothing is born over there: no animals, no monsters, ever. */
@Mixin(SpawnHelper.class)
public abstract class SpawnHelperMixin {
    @Inject(method = "spawn", at = @At("HEAD"), cancellable = true)
    private static void deadmanshand$nobody(ServerWorld world, WorldChunk chunk, SpawnHelper.Info info, boolean spawnAnimals,
                                            boolean spawnMonsters, boolean rareSpawn, CallbackInfo ci) {
        if (Borderland.is(world)) {
            ci.cancel();
        }
    }
}
