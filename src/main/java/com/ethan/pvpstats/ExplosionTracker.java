package com.ethan.pvpstats;

import net.minecraft.entity.Entity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 爆炸击杀归属追踪。
 * <p>
 * 原版中末影水晶/重生锚/床爆炸产生的伤害源往往不带攻击者，导致无法统计 K/D。
 * 本类通过两条线索还原"谁引爆的"：
 * <ul>
 *   <li>Mixin 在爆炸创建瞬间记录（爆炸中心、伤害源中的攻击者、tick）</li>
 *   <li>对不带攻击者的爆炸（床/重生锚/部分水晶），查找同 tick 附近的
 *       "引爆预备"记录（玩家右键床/重生锚、攻击水晶时登记）</li>
 * </ul>
 */
public final class ExplosionTracker {

    /** 引爆预备记录：玩家点击床/重生锚或攻击水晶 */
    private record Prime(UUID player, Vec3d pos, long tick) {}

    /** 爆炸记录：爆炸中心与归属玩家 */
    private record Blast(UUID attacker, Vec3d pos, long tick) {}

    private static final List<Prime> PRIMES = new ArrayList<>();
    private static final List<Blast> BLASTS = new ArrayList<>();

    /** 引爆点与爆炸中心的最大距离（格） */
    private static final double PRIME_RADIUS_SQ = 9.0 * 9.0;
    /** 受害者与爆炸中心的最大距离（格），爆炸半径约 8，留出余量 */
    private static final double BLAST_RADIUS_SQ = 14.0 * 14.0;

    private ExplosionTracker() {
    }

    /** 登记一次"引爆预备"（右键床/重生锚、攻击水晶时调用） */
    public static void recordPrime(UUID player, Vec3d pos, long tick) {
        PRIMES.add(new Prime(player, pos, tick));
    }

    /** 爆炸创建时调用（ServerWorldMixin HEAD） */
    public static void recordBlast(ServerWorld world, @Nullable Entity exploder,
                                   @Nullable DamageSource source, Vec3d pos) {
        long tick = world.getServer().getTicks();
        UUID attacker = null;

        if (source != null && source.getAttacker() instanceof ServerPlayerEntity player) {
            attacker = player.getUuid();
        }
        if (attacker == null && exploder instanceof ServerPlayerEntity player) {
            attacker = player.getUuid();
        }
        if (attacker == null) {
            // 床/重生锚/水晶：查找同 tick 附近的引爆预备记录
            Prime best = null;
            double bestDist = PRIME_RADIUS_SQ;
            for (Prime prime : PRIMES) {
                if (prime.tick() == tick) {
                    double dist = prime.pos().squaredDistanceTo(pos);
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = prime;
                    }
                }
            }
            if (best != null) {
                attacker = best.player();
            }
        }
        if (attacker != null) {
            BLASTS.add(new Blast(attacker, pos, tick));
        }
    }

    /**
     * 查找某位置当前 tick 附近爆炸的归属玩家。
     * 爆炸伤害与爆炸创建发生在同一 tick，这里容忍 1 tick 误差。
     */
    @Nullable
    public static UUID findAttacker(long tick, Vec3d pos) {
        Blast best = null;
        double bestDist = BLAST_RADIUS_SQ;
        for (Blast blast : BLASTS) {
            if (Math.abs(blast.tick() - tick) <= 1) {
                double dist = blast.pos().squaredDistanceTo(pos);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = blast;
                }
            }
        }
        return best == null ? null : best.attacker();
    }

    /** 每个 server tick 清理过期记录 */
    public static void purge(long now) {
        PRIMES.removeIf(prime -> now - prime.tick() > 5);
        BLASTS.removeIf(blast -> now - blast.tick() > 20);
    }
}
