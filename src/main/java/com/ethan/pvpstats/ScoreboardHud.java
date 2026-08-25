package com.ethan.pvpstats;

import net.minecraft.network.packet.s2c.play.ScoreboardDisplayS2CPacket;
import net.minecraft.network.packet.s2c.play.ScoreboardObjectiveUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ScoreboardScoreUpdateS2CPacket;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardCriterion;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.number.BlankNumberFormat;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 通过虚拟计分板数据包为每个成员单独渲染侧边栏：
 * 标题为 "PVP:组名"，每行显示 "玩家名 击杀/死亡"。
 * 这些计分板只存在于客户端，不占用服务端真实计分板。
 */
public class ScoreboardHud {

    /** 玩家 -> 当前正在显示的虚拟计分板名称（用于下线清理） */
    private static final Map<UUID, String> SHOWN = new HashMap<>();

    /** 刷新组内所有在线成员的计分板 */
    public static void refresh(MinecraftServer server, PvPGroup group) {
        for (UUID uuid : group.getMembers()) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
            if (player != null) {
                showFor(server, group, player);
            }
        }
    }

    public static void showFor(MinecraftServer server, PvPGroup group, ServerPlayerEntity player) {
        String objName = group.getObjectiveName();
        Text title = Text.literal("PVP:" + group.getName()).formatted(Formatting.GOLD, Formatting.BOLD);

        // 先删后建，保证客户端上的旧分数被清掉
        ScoreboardObjective obj = createObjective(objName, title);
        player.networkHandler.sendPacket(new ScoreboardObjectiveUpdateS2CPacket(obj, ScoreboardObjectiveUpdateS2CPacket.REMOVE_MODE));
        player.networkHandler.sendPacket(new ScoreboardObjectiveUpdateS2CPacket(obj, ScoreboardObjectiveUpdateS2CPacket.ADD_MODE));
        player.networkHandler.sendPacket(new ScoreboardDisplayS2CPacket(ScoreboardDisplaySlot.SIDEBAR, obj));

        List<UUID> order = group.sortedMembers();
        int score = order.size();
        for (UUID uuid : order) {
            PvPGroup.Stats stats = group.getStats(uuid);
            String name = group.nameOf(uuid);
            boolean online = server.getPlayerManager().getPlayer(uuid) != null;
            Text line = Text.literal(name + " ")
                    .formatted(online ? Formatting.GREEN : Formatting.GRAY)
                    .append(Text.literal(stats.kills + "/" + stats.deaths).formatted(Formatting.YELLOW));
            player.networkHandler.sendPacket(new ScoreboardScoreUpdateS2CPacket(
                    name, objName, score--, Optional.of(line), Optional.of(BlankNumberFormat.INSTANCE)));
        }
        SHOWN.put(player.getUuid(), objName);
    }

    /** 清除某个玩家的 PVP 计分板（退出组别 / 组别结束时调用） */
    public static void clear(ServerPlayerEntity player) {
        String objName = SHOWN.remove(player.getUuid());
        if (objName == null) {
            return;
        }
        ScoreboardObjective obj = createObjective(objName, Text.empty());
        player.networkHandler.sendPacket(new ScoreboardDisplayS2CPacket(ScoreboardDisplaySlot.SIDEBAR, null));
        player.networkHandler.sendPacket(new ScoreboardObjectiveUpdateS2CPacket(obj, ScoreboardObjectiveUpdateS2CPacket.REMOVE_MODE));
    }

    /** 玩家重新登录时客户端计分板已重置，只需忘掉旧状态，不必发包 */
    public static void forget(UUID uuid) {
        SHOWN.remove(uuid);
    }

    private static ScoreboardObjective createObjective(String name, Text displayName) {
        Scoreboard scoreboard = new Scoreboard();
        return scoreboard.addObjective(name, ScoreboardCriterion.DUMMY, displayName,
                ScoreboardCriterion.RenderType.INTEGER, false, BlankNumberFormat.INSTANCE);
    }
}
