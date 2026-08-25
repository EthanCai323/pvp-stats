package com.ethan.pvpstats;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 一个 PVP 组别的全部数据。
 * <p>
 * members 只保存当前在组的玩家；stats / names / killMatrix 保存组别存续期间
 * 所有参与过战斗的玩家数据，因此退出后再加入可以恢复历史 K/D。
 */
public class PvPGroup {

    public static class Stats {
        public int kills;
        public int deaths;
    }

    private final String name;
    private final String objectiveName;
    private final long startMillis;

    /** 当前在组成员（含暂时离线的成员） */
    private final Set<UUID> members = new LinkedHashSet<>();
    /** 所有参与过本组的玩家的战绩（退出后仍保留） */
    private final Map<UUID, Stats> stats = new HashMap<>();
    /** killer -> victim -> 击杀次数 */
    private final Map<UUID, Map<UUID, Integer>> killMatrix = new HashMap<>();
    private final Map<UUID, String> names = new HashMap<>();
    private final List<String> deathLog = new ArrayList<>();
    /** 离线成员 -> 离线时刻（server tick） */
    private final Map<UUID, Long> offlineSince = new HashMap<>();
    /** 被邀请人 -> 邀请过期 tick */
    private final Map<UUID, Long> invites = new HashMap<>();

    /** 只剩 1 名成员的起始 tick，-1 表示当前不止 1 人 */
    private long singleSinceTick = -1;
    /** 结束请求发起者，null 表示当前没有进行中的结束请求 */
    private UUID endRequester;
    private final Set<UUID> endAgreed = new HashSet<>();

    public PvPGroup(String name, String objectiveName) {
        this.name = name;
        this.objectiveName = objectiveName;
        this.startMillis = System.currentTimeMillis();
    }

    public String getName() {
        return name;
    }

    public String getObjectiveName() {
        return objectiveName;
    }

    public long getStartMillis() {
        return startMillis;
    }

    public Set<UUID> getMembers() {
        return members;
    }

    public Map<UUID, Long> getOfflineSince() {
        return offlineSince;
    }

    public Map<UUID, Long> getInvites() {
        return invites;
    }

    public Map<UUID, Map<UUID, Integer>> getKillMatrix() {
        return killMatrix;
    }

    public List<String> getDeathLog() {
        return deathLog;
    }

    public long getSingleSinceTick() {
        return singleSinceTick;
    }

    public void setSingleSinceTick(long tick) {
        this.singleSinceTick = tick;
    }

    public UUID getEndRequester() {
        return endRequester;
    }

    public void setEndRequester(UUID endRequester) {
        this.endRequester = endRequester;
    }

    public Set<UUID> getEndAgreed() {
        return endAgreed;
    }

    public void rememberName(UUID uuid, String playerName) {
        names.put(uuid, playerName);
    }

    public String nameOf(UUID uuid) {
        return names.getOrDefault(uuid, uuid.toString().substring(0, 8));
    }

    public Stats statsOf(UUID uuid, String playerName) {
        rememberName(uuid, playerName);
        return stats.computeIfAbsent(uuid, k -> new Stats());
    }

    public Stats getStats(UUID uuid) {
        return stats.computeIfAbsent(uuid, k -> new Stats());
    }

    public boolean hasStats(UUID uuid) {
        return stats.containsKey(uuid);
    }

    public Set<UUID> allParticipants() {
        return stats.keySet();
    }

    public void addKill(UUID killer, UUID victim) {
        killMatrix.computeIfAbsent(killer, k -> new HashMap<>()).merge(victim, 1, Integer::sum);
    }

    public void logDeath(String time, String message) {
        deathLog.add("[" + time + "] " + message);
    }

    /** 计分板行排序：击杀数降序 -> 死亡数升序 -> 名字 */
    public List<UUID> sortedMembers() {
        List<UUID> list = new ArrayList<>(members);
        list.sort((a, b) -> {
            Stats sa = getStats(a);
            Stats sb = getStats(b);
            if (sa.kills != sb.kills) return Integer.compare(sb.kills, sa.kills);
            if (sa.deaths != sb.deaths) return Integer.compare(sa.deaths, sb.deaths);
            return nameOf(a).compareToIgnoreCase(nameOf(b));
        });
        return list;
    }
}
