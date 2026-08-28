package com.ethan.pvpstats;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.block.BedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.RespawnAnchorBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;

import java.io.IOException;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PVP 组别核心逻辑：创建/邀请/退出/结束、伤害限制、击杀统计、自动解散。
 */
public class PvPManager {

    private static final long OFFLINE_GRACE_TICKS = 20L * 60;   // 离线 1 分钟内重连保留组别
    private static final long INVITE_TTL_TICKS = 20L * 120;     // 邀请 2 分钟有效
    private static final long SOLO_TIMEOUT_TICKS = 20L * 30;    // 仅剩 1 人持续 30 秒自动结束

    private static final Map<String, PvPGroup> GROUPS = new LinkedHashMap<>();
    private static final Map<UUID, String> MEMBERSHIP = new HashMap<>();
    private static final AtomicInteger OBJECTIVE_ID = new AtomicInteger();

    public static void register() {
        PvPCommands.register();
        ServerTickEvents.END_SERVER_TICK.register(PvPManager::tick);
        ServerLivingEntityEvents.ALLOW_DAMAGE.register(PvPManager::allowDamage);
        ServerLivingEntityEvents.AFTER_DEATH.register(PvPManager::afterDeath);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> onJoin(handler.player, server));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> onDisconnect(handler.player, server));
        // 玩家右键床/重生锚时登记"引爆预备"，供爆炸归属追踪
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (player instanceof ServerPlayerEntity serverPlayer && world instanceof ServerWorld serverWorld) {
                BlockState state = serverWorld.getBlockState(hitResult.getBlockPos());
                if (state.getBlock() instanceof BedBlock || state.getBlock() instanceof RespawnAnchorBlock) {
                    ExplosionTracker.recordPrime(serverPlayer.getUuid(),
                            hitResult.getBlockPos().toCenterPos(), serverWorld.getServer().getTicks());
                }
            }
            return ActionResult.PASS;
        });
    }

    public static Collection<String> groupNames() {
        return new ArrayList<>(GROUPS.keySet());
    }

    public static String groupOf(UUID uuid) {
        return MEMBERSHIP.get(uuid);
    }

    // ---------------------------------------------------------------- 命令入口

    /** /pvp start <name> */
    public static int cmdStart(ServerCommandSource source, String name) {
        ServerPlayerEntity player = getPlayer(source);
        if (player == null) return 0;
        if (GROUPS.containsKey(name)) {
            source.sendError(Text.literal("[PVP] 组别 " + name + " 已存在，请换一个名称"));
            return 0;
        }
        String current = MEMBERSHIP.get(player.getUuid());
        if (current != null) {
            source.sendError(Text.literal("[PVP] 你已在组别 " + current + " 中，请先输入 /pvp quit " + current + " 退出"));
            return 0;
        }
        PvPGroup group = new PvPGroup(name, "pvps" + OBJECTIVE_ID.incrementAndGet());
        GROUPS.put(name, group);
        joinGroup(source.getServer(), group, player);
        source.sendFeedback(() -> Text.literal("[PVP] 已创建组别 " + name + "，使用 /pvp add " + name + " <玩家> 邀请他人"), false);
        return 1;
    }

    /** /pvp add <group> <player> */
    public static int cmdAdd(ServerCommandSource source, String groupName, ServerPlayerEntity target) {
        ServerPlayerEntity player = getPlayer(source);
        if (player == null) return 0;
        PvPGroup group = GROUPS.get(groupName);
        if (group == null) {
            source.sendError(Text.literal("[PVP] 组别 " + groupName + " 不存在"));
            return 0;
        }
        if (!group.getMembers().contains(player.getUuid())) {
            source.sendError(Text.literal("[PVP] 你不在组别 " + groupName + " 中，无法邀请他人"));
            return 0;
        }
        if (target.getUuid().equals(player.getUuid())) {
            source.sendError(Text.literal("[PVP] 你不能邀请自己"));
            return 0;
        }
        if (MEMBERSHIP.containsKey(target.getUuid())) {
            source.sendError(Text.literal("[PVP] 玩家 " + target.getName().getString() + " 已在组别 " + MEMBERSHIP.get(target.getUuid()) + " 中"));
            return 0;
        }
        MinecraftServer server = source.getServer();
        group.rememberName(target.getUuid(), target.getName().getString());
        group.getInvites().put(target.getUuid(), (long) server.getTicks() + INVITE_TTL_TICKS);

        Text invite = Text.literal("[PVP] 玩家 ").formatted(Formatting.YELLOW)
                .append(Text.literal(player.getName().getString()).formatted(Formatting.AQUA))
                .append(Text.literal(" 邀请你加入 PVP 组别 "))
                .append(Text.literal(groupName).formatted(Formatting.GOLD))
                .append(Text.literal("  "))
                .append(clickable("[接受]", Formatting.GREEN, "/pvp accept " + groupName, "点击接受邀请"))
                .append(Text.literal(" "))
                .append(clickable("[拒绝]", Formatting.RED, "/pvp decline " + groupName, "点击拒绝邀请"));
        target.sendMessage(invite, false);

        ServerPlayerEntity inviter = player;
        source.sendFeedback(() -> Text.literal("[PVP] 已向 " + inviterName(target) + " 发送邀请（2 分钟内有效）"), false);
        return 1;
    }

    /** /pvp accept <group>（由点击聊天消息触发，也可手动输入） */
    public static int cmdAccept(ServerCommandSource source, String groupName) {
        ServerPlayerEntity player = getPlayer(source);
        if (player == null) return 0;
        PvPGroup group = GROUPS.get(groupName);
        if (group == null) {
            source.sendError(Text.literal("[PVP] 组别 " + groupName + " 不存在或已解散"));
            return 0;
        }
        Long expire = group.getInvites().remove(player.getUuid());
        if (expire == null) {
            source.sendError(Text.literal("[PVP] 你没有来自组别 " + groupName + " 的邀请"));
            return 0;
        }
        String current = MEMBERSHIP.get(player.getUuid());
        if (current != null) {
            source.sendError(Text.literal("[PVP] 你已在组别 " + current + " 中，无法加入其他组别"));
            return 0;
        }
        boolean hadStats = group.hasStats(player.getUuid());
        joinGroup(source.getServer(), group, player);
        player.sendMessage(Text.literal("[PVP] 你已加入组别 " + groupName).formatted(Formatting.GREEN), false);
        if (hadStats) {
            PvPGroup.Stats s = group.getStats(player.getUuid());
            player.sendMessage(Text.literal("[PVP] 已恢复你在本组的历史战绩：" + s.kills + "/" + s.deaths).formatted(Formatting.GRAY), false);
        }
        broadcastToGroup(source.getServer(), group,
                Text.literal("[PVP] " + player.getName().getString() + " 加入了组别 " + groupName).formatted(Formatting.GREEN));
        return 1;
    }

    /** /pvp decline <group> */
    public static int cmdDecline(ServerCommandSource source, String groupName) {
        ServerPlayerEntity player = getPlayer(source);
        if (player == null) return 0;
        PvPGroup group = GROUPS.get(groupName);
        if (group == null || group.getInvites().remove(player.getUuid()) == null) {
            source.sendError(Text.literal("[PVP] 你没有来自组别 " + groupName + " 的邀请"));
            return 0;
        }
        player.sendMessage(Text.literal("[PVP] 你已拒绝组别 " + groupName + " 的邀请").formatted(Formatting.GRAY), false);
        broadcastToGroup(source.getServer(), group,
                Text.literal("[PVP] " + player.getName().getString() + " 拒绝了组别 " + groupName + " 的邀请").formatted(Formatting.GRAY));
        return 1;
    }

    /** /pvp quit <group> */
    public static int cmdQuit(ServerCommandSource source, String groupName) {
        ServerPlayerEntity player = getPlayer(source);
        if (player == null) return 0;
        PvPGroup group = GROUPS.get(groupName);
        if (group == null || !group.getMembers().contains(player.getUuid())) {
            source.sendError(Text.literal("[PVP] 你不在组别 " + groupName + " 中"));
            return 0;
        }
        removeMember(source.getServer(), group, player.getUuid(), null);
        player.sendMessage(Text.literal("[PVP] 你已退出组别 " + groupName + "，战绩将在组别存续期间保留").formatted(Formatting.YELLOW), false);
        return 1;
    }

    /** /pvp end <group>：发起结束请求，需要所有成员点击同意 */
    public static int cmdEnd(ServerCommandSource source, String groupName) {
        ServerPlayerEntity player = getPlayer(source);
        if (player == null) return 0;
        PvPGroup group = GROUPS.get(groupName);
        if (group == null || !group.getMembers().contains(player.getUuid())) {
            source.sendError(Text.literal("[PVP] 你不在组别 " + groupName + " 中"));
            return 0;
        }
        if (group.getEndRequester() != null) {
            source.sendError(Text.literal("[PVP] 已有结束请求等待所有成员决定，暂时无法发起新的结束请求"));
            return 0;
        }
        group.setEndRequester(player.getUuid());
        group.getEndAgreed().clear();

        Text message = Text.literal("[PVP] ").formatted(Formatting.YELLOW)
                .append(Text.literal(player.getName().getString()).formatted(Formatting.AQUA))
                .append(Text.literal(" 请求结束组别 "))
                .append(Text.literal(groupName).formatted(Formatting.GOLD))
                .append(Text.literal("，需要所有成员同意  "))
                .append(clickable("[同意]", Formatting.GREEN, "/pvp agree " + groupName, "点击同意结束组别"));
        broadcastToGroup(source.getServer(), group, message);
        return 1;
    }

    /** /pvp agree <group>：同意结束请求（由点击聊天消息触发，也可手动输入） */
    public static int cmdAgree(ServerCommandSource source, String groupName) {
        ServerPlayerEntity player = getPlayer(source);
        if (player == null) return 0;
        PvPGroup group = GROUPS.get(groupName);
        if (group == null || !group.getMembers().contains(player.getUuid())) {
            source.sendError(Text.literal("[PVP] 你不在组别 " + groupName + " 中"));
            return 0;
        }
        if (group.getEndRequester() == null) {
            source.sendError(Text.literal("[PVP] 组别 " + groupName + " 当前没有进行中的结束请求"));
            return 0;
        }
        if (group.getEndAgreed().add(player.getUuid())) {
            broadcastToGroup(source.getServer(), group,
                    Text.literal("[PVP] " + player.getName().getString() + " 已同意结束（"
                            + group.getEndAgreed().size() + "/" + group.getMembers().size() + "）").formatted(Formatting.GRAY));
        }
        checkEndVoteComplete(source.getServer(), group);
        return 1;
    }

    // ---------------------------------------------------------------- 事件处理

    /** 伤害限制：组内成员只能攻击同组成员，组外玩家无法攻击组内成员（含水晶/重生锚/床爆炸） */
    private static boolean allowDamage(LivingEntity entity, DamageSource source, float amount) {
        if (!(entity instanceof ServerPlayerEntity victim)) {
            return true;
        }
        MinecraftServer server = serverOf(victim);
        if (server == null) {
            return true;
        }
        UUID attackerUuid = resolveAttacker(server, victim, source);
        if (attackerUuid == null || attackerUuid.equals(victim.getUuid())) {
            return true;
        }
        ServerPlayerEntity attackerPlayer = server.getPlayerManager().getPlayer(attackerUuid);
        String victimGroup = MEMBERSHIP.get(victim.getUuid());
        String attackerGroup = MEMBERSHIP.get(attackerUuid);
        if (victimGroup == null && attackerGroup == null) {
            return true; // 双方都不在任何 PVP 组别，保持原版行为
        }
        boolean allowed = victimGroup != null && victimGroup.equals(attackerGroup);
        if (!allowed && attackerPlayer != null) {
            attackerPlayer.sendMessage(Text.literal("[PVP] 你只能攻击与你在同一 PVP 组别的玩家").formatted(Formatting.RED), true);
        }
        return allowed;
    }

    /**
     * 死亡统计：
     * 组内玩家的任何死亡都计入自己的死亡数；
     * 击杀方为同组玩家时（含水晶/重生锚/床爆炸归属）计入击杀数与两两击杀明细。
     */
    private static void afterDeath(LivingEntity entity, DamageSource source) {
        if (!(entity instanceof ServerPlayerEntity victim)) {
            return;
        }
        String groupName = MEMBERSHIP.get(victim.getUuid());
        if (groupName == null) {
            return;
        }
        PvPGroup group = GROUPS.get(groupName);
        MinecraftServer server = serverOf(victim);
        if (group == null || server == null) {
            return;
        }
        String time = new SimpleDateFormat("HH:mm:ss").format(new Date());
        String victimName = victim.getName().getString();

        UUID attackerUuid = resolveAttacker(server, victim, source);
        // “在与 xxx 的战斗中死亡”（火焰/摔落等持续伤害）：伤害源无攻击者时，归属最近攻击者
        if (attackerUuid == null && victim.getPrimeAdversary() instanceof ServerPlayerEntity prime) {
            attackerUuid = prime.getUuid();
        }
        boolean explosionKill = attackerUuid != null
                && !(source.getAttacker() instanceof ServerPlayerEntity)
                && isExplosionLike(source);
        boolean combatKill = attackerUuid != null
                && !(source.getAttacker() instanceof ServerPlayerEntity)
                && !explosionKill;

        // 任何原因的死亡都计入死亡数
        group.statsOf(victim.getUuid(), victimName).deaths++;

        if (attackerUuid != null && !attackerUuid.equals(victim.getUuid())
                && groupName.equals(MEMBERSHIP.get(attackerUuid))) {
            group.getStats(attackerUuid).kills++;
            group.addKill(attackerUuid, victim.getUuid());
            String suffix = explosionKill ? "（爆炸）" : combatKill ? "（战斗中）" : "";
            group.logDeath(time, victimName + " 被 " + group.nameOf(attackerUuid) + " 击杀" + suffix
                    + (combatKill ? "：" + source.getDeathMessage(victim).getString() : ""));
        } else {
            group.logDeath(time, victimName + " 死亡：" + source.getDeathMessage(victim).getString());
        }
        ScoreboardHud.refresh(server, group);
    }

    /** 解析伤害来源的攻击玩家：直接攻击者，或爆炸类伤害的归属玩家 */
    private static UUID resolveAttacker(MinecraftServer server, LivingEntity entity, DamageSource source) {
        if (source.getAttacker() instanceof ServerPlayerEntity player) {
            return player.getUuid();
        }
        if (isExplosionLike(source)) {
            return ExplosionTracker.findAttacker(server.getTicks(), entity.getEntityPos());
        }
        return null;
    }

    /** 1.21.11 中 Entity 不再有 getServer()，从所在世界获取 */
    private static MinecraftServer serverOf(LivingEntity entity) {
        return entity.getEntityWorld() instanceof ServerWorld serverWorld ? serverWorld.getServer() : null;
    }

    private static boolean isExplosionLike(DamageSource source) {
        return source.isOf(DamageTypes.EXPLOSION)
                || source.isOf(DamageTypes.PLAYER_EXPLOSION)
                || source.isOf(DamageTypes.BAD_RESPAWN_POINT);
    }

    /** 重新登录：1 分钟内回到组别（只要组别还没解散） */
    private static void onJoin(ServerPlayerEntity player, MinecraftServer server) {
        ScoreboardHud.forget(player.getUuid());
        String groupName = MEMBERSHIP.get(player.getUuid());
        if (groupName == null) {
            return;
        }
        PvPGroup group = GROUPS.get(groupName);
        if (group == null) {
            MEMBERSHIP.remove(player.getUuid());
            return;
        }
        group.rememberName(player.getUuid(), player.getName().getString());
        if (group.getOfflineSince().remove(player.getUuid()) != null) {
            player.sendMessage(Text.literal("[PVP] 欢迎回来，你仍在组别 " + groupName + " 中").formatted(Formatting.GREEN), false);
            broadcastToGroup(server, group,
                    Text.literal("[PVP] " + player.getName().getString() + " 重新上线，保留在组别中").formatted(Formatting.GRAY));
        }
        ScoreboardHud.refresh(server, group);
    }

    /** 掉线：保留在组内 1 分钟 */
    private static void onDisconnect(ServerPlayerEntity player, MinecraftServer server) {
        String groupName = MEMBERSHIP.get(player.getUuid());
        if (groupName == null) {
            return;
        }
        PvPGroup group = GROUPS.get(groupName);
        if (group == null) {
            return;
        }
        group.getOfflineSince().put(player.getUuid(), (long) server.getTicks());
        broadcastToGroup(server, group,
                Text.literal("[PVP] " + player.getName().getString() + " 已离线，1 分钟内重连将保留在组别中").formatted(Formatting.GRAY));
        ScoreboardHud.refresh(server, group);
    }

    // ---------------------------------------------------------------- 定时检查

    private static void tick(MinecraftServer server) {
        long now = server.getTicks();
        ExplosionTracker.purge(now);
        for (PvPGroup group : new ArrayList<>(GROUPS.values())) {
            // 离线超时：超过 1 分钟未重连视为退出（战绩保留）
            for (Map.Entry<UUID, Long> entry : new HashMap<>(group.getOfflineSince()).entrySet()) {
                if (now - entry.getValue() >= OFFLINE_GRACE_TICKS) {
                    removeMember(server, group, entry.getKey(), "离线超过 1 分钟，自动退出组别");
                }
            }
            // 邀请过期
            group.getInvites().entrySet().removeIf(invite -> {
                if (now >= invite.getValue()) {
                    ServerPlayerEntity target = server.getPlayerManager().getPlayer(invite.getKey());
                    if (target != null) {
                        target.sendMessage(Text.literal("[PVP] 组别 " + group.getName() + " 的邀请已过期").formatted(Formatting.GRAY), false);
                    }
                    return true;
                }
                return false;
            });
            if (GROUPS.get(group.getName()) != group) {
                continue; // 组别已在本次 tick 中解散
            }
            // 自动结束条件
            if (group.getMembers().isEmpty()) {
                endGroup(server, group, "组内无人");
            } else if (group.getMembers().size() == 1) {
                if (group.getSingleSinceTick() < 0) {
                    group.setSingleSinceTick(now);
                } else if (now - group.getSingleSinceTick() >= SOLO_TIMEOUT_TICKS) {
                    endGroup(server, group, "仅剩 1 名成员超过 30 秒");
                }
            } else {
                group.setSingleSinceTick(-1);
            }
        }
    }

    // ---------------------------------------------------------------- 内部工具

    private static void joinGroup(MinecraftServer server, PvPGroup group, ServerPlayerEntity player) {
        group.getMembers().add(player.getUuid());
        group.statsOf(player.getUuid(), player.getName().getString()); // 若之前有战绩则自动沿用
        group.getOfflineSince().remove(player.getUuid());
        MEMBERSHIP.put(player.getUuid(), group.getName());
        ScoreboardHud.refresh(server, group);
    }

    private static void removeMember(MinecraftServer server, PvPGroup group, UUID uuid, String reason) {
        group.getMembers().remove(uuid);
        group.getOfflineSince().remove(uuid);
        MEMBERSHIP.remove(uuid);

        ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
        if (player != null) {
            ScoreboardHud.clear(player);
        }

        String name = group.nameOf(uuid);
        broadcastToGroup(server, group, Text.literal(reason == null
                ? "[PVP] " + name + " 退出了组别 " + group.getName()
                : "[PVP] " + name + " " + reason).formatted(Formatting.YELLOW));

        // 结束请求善后
        if (group.getEndRequester() != null) {
            group.getEndAgreed().remove(uuid);
            if (group.getEndRequester().equals(uuid)) {
                group.setEndRequester(null);
                group.getEndAgreed().clear();
                broadcastToGroup(server, group, Text.literal("[PVP] 结束请求发起者已退出，结束请求已取消").formatted(Formatting.GRAY));
            } else {
                checkEndVoteComplete(server, group);
            }
        }
        ScoreboardHud.refresh(server, group);
    }

    /** 所有人都同意后立即结束 */
    private static void checkEndVoteComplete(MinecraftServer server, PvPGroup group) {
        if (group.getEndRequester() == null || group.getMembers().isEmpty()) {
            return;
        }
        if (group.getEndAgreed().containsAll(group.getMembers())) {
            endGroup(server, group, "所有成员同意结束");
        }
    }

    private static void endGroup(MinecraftServer server, PvPGroup group, String reason) {
        // 聊天框公布战绩
        server.getPlayerManager().broadcast(
                Text.literal("[PVP] 组别 " + group.getName() + " 已结束（" + reason + "）").formatted(Formatting.GOLD, Formatting.BOLD), false);
        for (UUID uuid : group.allParticipants()) {
            PvPGroup.Stats s = group.getStats(uuid);
            server.getPlayerManager().broadcast(Text.literal("  ")
                    .append(Text.literal(group.nameOf(uuid)).formatted(Formatting.AQUA))
                    .append(Text.literal(" 击杀 ").formatted(Formatting.GRAY))
                    .append(Text.literal(String.valueOf(s.kills)).formatted(Formatting.GREEN))
                    .append(Text.literal(" / 死亡 ").formatted(Formatting.GRAY))
                    .append(Text.literal(String.valueOf(s.deaths)).formatted(Formatting.RED)), false);
        }

        // 清理计分板与成员关系
        for (UUID uuid : new ArrayList<>(group.getMembers())) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
            if (player != null) {
                ScoreboardHud.clear(player);
            }
            MEMBERSHIP.remove(uuid);
        }
        GROUPS.remove(group.getName());

        // 写战报日志
        try {
            Path file = PvPLogWriter.write(group, reason);
            PvPStatsMod.LOGGER.info("[PvPStats] 组别 {} 战报已保存: {}", group.getName(), file);
        } catch (IOException e) {
            PvPStatsMod.LOGGER.error("[PvPStats] 保存组别 {} 战报失败", group.getName(), e);
        }
    }

    private static void broadcastToGroup(MinecraftServer server, PvPGroup group, Text message) {
        for (UUID uuid : group.getMembers()) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
            if (player != null) {
                player.sendMessage(message, false);
            }
        }
    }

    private static Text clickable(String label, Formatting color, String command, String hover) {
        return Text.literal(label).setStyle(Style.EMPTY
                .withColor(color)
                .withBold(true)
                .withClickEvent(new ClickEvent.RunCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(Text.literal(hover))));
    }

    private static ServerPlayerEntity getPlayer(ServerCommandSource source) {
        try {
            return source.getPlayerOrThrow();
        } catch (CommandSyntaxException e) {
            source.sendError(Text.literal("[PVP] 该命令只能由玩家使用"));
            return null;
        }
    }

    private static String inviterName(ServerPlayerEntity target) {
        return target.getName().getString();
    }
}
