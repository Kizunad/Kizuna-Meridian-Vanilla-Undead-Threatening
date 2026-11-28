package dev.kizuna.meridian.undead.fabric.mixin;

import dev.kizuna.meridian.undead.fabric.UndeadLeapFabric;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.monster.zombie.Zombie;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Zombie.class)
abstract class ZombieLeapMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void meridian$decideLeap(CallbackInfo callback) {
        Zombie corpse = (Zombie) (Object) this;
        if (corpse.level() instanceof ServerLevel level) {
            UndeadLeapFabric.tickZombie(corpse, level);
        }
    }
}
