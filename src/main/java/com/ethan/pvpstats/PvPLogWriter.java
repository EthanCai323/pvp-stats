package com.ethan.pvpstats;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

/**
 * 组别结束时把战报写入 服务器根目录/pvplogs/<开始时间>.txt
 */
public class PvPLogWriter {

    public static Path write(PvPGroup group, String reason) throws IOException {
        Path dir = FabricLoader.getInstance().getGameDir().resolve("pvplogs");
        Files.createDirectories(dir);

        SimpleDateFormat fileNameFormat = new SimpleDateFormat("yyyyMMddHHmmss");
        SimpleDateFormat humanFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

        String baseName = fileNameFormat.format(new Date(group.getStartMillis()));
        Path file = dir.resolve(baseName + ".txt");
        int suffix = 2;
        while (Files.exists(file)) {
            file = dir.resolve(baseName + "_" + suffix++ + ".txt");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("PVP 组别战报\n");
        sb.append("组别名称: ").append(group.getName()).append('\n');
        sb.append("开始时间: ").append(humanFormat.format(new Date(group.getStartMillis()))).append('\n');
        sb.append("结束时间: ").append(humanFormat.format(new Date())).append('\n');
        sb.append("结束原因: ").append(reason).append('\n');

        sb.append("\n[战绩统计] (击杀/死亡)\n");
        for (UUID uuid : group.allParticipants()) {
            PvPGroup.Stats s = group.getStats(uuid);
            sb.append(group.nameOf(uuid)).append(": ").append(s.kills).append('/').append(s.deaths).append('\n');
        }

        sb.append("\n[击杀明细]\n");
        boolean anyKill = false;
        for (Map.Entry<UUID, Map<UUID, Integer>> killerEntry : group.getKillMatrix().entrySet()) {
            for (Map.Entry<UUID, Integer> victimEntry : killerEntry.getValue().entrySet()) {
                sb.append(group.nameOf(killerEntry.getKey()))
                        .append(" 击杀 ").append(group.nameOf(victimEntry.getKey()))
                        .append(": ").append(victimEntry.getValue()).append(" 次\n");
                anyKill = true;
            }
        }
        if (!anyKill) {
            sb.append("（无）\n");
        }

        sb.append("\n[死亡记录]\n");
        if (group.getDeathLog().isEmpty()) {
            sb.append("（无）\n");
        } else {
            for (String line : group.getDeathLog()) {
                sb.append(line).append('\n');
            }
        }

        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        return file;
    }
}
