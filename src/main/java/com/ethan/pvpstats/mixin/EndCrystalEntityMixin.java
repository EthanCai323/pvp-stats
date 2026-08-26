package com.ethan.pvpstats.mixin;

import com.ethan.pvpstats.ExplosionTracker;
import net.minecraft.entity.Entity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 玩家攻击末影水晶时登记"引爆预备"，使水晶爆炸（包括箭射爆）能归属到攻击者。
 */
@Mixin(EndCrystalEntity.class)
public abstract class EndCrystalEntityMixin {

    @Inject(method = "damage", at = @At("HEAD"))
    private void pvpstats$recordPrime(ServerWorld world, DamageSource source, float amount,
                                      CallbackInfoReturnable<Boolean> cir) {
        Entity attacker = source.getAttacker();
        if (attacker instanceof ServerPlayerEntity player) {
            EndCrystalEntity crystal = (EndCrystalEntity) (Object) this;
            ExplosionTracker.recordPrime(player.getUuid(), crystal.getPos(), world.getServer().getTicks());
        }
    }
}
