package com.ethan.pvpstats.mixin;

import com.ethan.pvpstats.ExplosionTracker;
import net.minecraft.entity.Entity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.explosion.ExplosionBehavior;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在爆炸创建瞬间记录归属信息，供水晶/重生锚/床爆炸的 K/D 统计与伤害限制使用。
 */
@Mixin(ServerWorld.class)
public abstract class ServerWorldMixin {

    @Inject(method = "createExplosion(Lnet/minecraft/entity/Entity;Lnet/minecraft/entity/damage/DamageSource;Lnet/minecraft/world/explosion/ExplosionBehavior;DDDFZLnet/minecraft/world/World$ExplosionSourceType;Lnet/minecraft/particle/ParticleEffect;Lnet/minecraft/particle/ParticleEffect;Lnet/minecraft/registry/entry/RegistryEntry;)V",
            at = @At("HEAD"))
    private void pvpstats$recordExplosion(Entity entity, DamageSource damageSource, ExplosionBehavior behavior,
                                          double x, double y, double z, float power, boolean createFire,
                                          World.ExplosionSourceType explosionSourceType,
                                          ParticleEffect smallParticle, ParticleEffect largeParticle,
                                          RegistryEntry<SoundEvent> soundEvent, CallbackInfo ci) {
        ExplosionTracker.recordBlast((ServerWorld) (Object) this, entity, damageSource, new Vec3d(x, y, z));
    }
}
