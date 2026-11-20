package dev.kizuna.meridian.undead.fabric.mixin;

import dev.kizuna.meridian.healing.fabric.HealingFabric;
import dev.kizuna.meridian.undead.fabric.UndeadFabric;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
abstract class CorpseHealingMixin {
    @Unique private static final int MERIDIAN_SATURATED_HEAL_TICKS = 10;
    @Unique private int meridian$restTicks;

    @Inject(method = "tick", at = @At("TAIL"))
    private void meridian$rest(CallbackInfo callback) {
        if (!((Object) this instanceof Zombie corpse) || !(corpse.level() instanceof ServerLevel world)) {
            return;
        }
        if (!corpse.isAlive() || corpse.getHealth() >= corpse.getMaxHealth() || corpse.hurtTime > 0
                || corpse.getTarget() != null || !UndeadFabric.accepts(corpse)
                || !HealingFabric.naturalRegeneration(world.getGameRules())) {
            meridian$restTicks = 0;
            return;
        }
        if (++meridian$restTicks < MERIDIAN_SATURATED_HEAL_TICKS) {
            return;
        }
        meridian$restTicks = 0;
        double dose = HealingFabric.config().naturalMultiplier() * UndeadFabric.healingMultiplier();
        if (dose > 0) {
            try {
                HealingFabric.inspect(corpse).ifPresent(binding -> HealingFabric.heal(corpse, binding, dose));
            } catch (RuntimeException failure) {
                HealingFabric.report(corpse, failure);
            }
        }
    }
}
