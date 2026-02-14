package ru.nekostul.aicompanion.bugreport;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

final class BugReportCooldownData extends SavedData {
    private static final String DATA_NAME = "aicompanion_bugreport_cooldown";
    private static final String KEY_LAST_SENT = "LastSent";

    private final Map<UUID, Long> lastSent = new HashMap<>();

    private BugReportCooldownData() {
    }

    static BugReportCooldownData get(MinecraftServer server) {
        if (server == null) {
            return null;
        }
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) {
            return null;
        }
        return overworld.getDataStorage().computeIfAbsent(
                BugReportCooldownData::load,
                BugReportCooldownData::new,
                DATA_NAME
        );
    }

    boolean canSend(UUID playerId, long nowMillis, long cooldownMillis) {
        Long last = lastSent.get(playerId);
        return last == null || nowMillis - last >= cooldownMillis;
    }

    long getRemainingSeconds(UUID playerId, long nowMillis, long cooldownMillis) {
        Long last = lastSent.get(playerId);
        if (last == null) {
            return 0L;
        }
        long diff = cooldownMillis - (nowMillis - last);
        return Math.max(0L, diff / 1000L);
    }

    void markSent(UUID playerId, long nowMillis) {
        lastSent.put(playerId, nowMillis);
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        CompoundTag sentTag = new CompoundTag();
        for (Map.Entry<UUID, Long> entry : lastSent.entrySet()) {
            sentTag.putLong(entry.getKey().toString(), entry.getValue());
        }
        tag.put(KEY_LAST_SENT, sentTag);
        return tag;
    }

    private static BugReportCooldownData load(CompoundTag tag) {
        BugReportCooldownData data = new BugReportCooldownData();
        if (!tag.contains(KEY_LAST_SENT, CompoundTag.TAG_COMPOUND)) {
            return data;
        }
        CompoundTag sentTag = tag.getCompound(KEY_LAST_SENT);
        for (String key : sentTag.getAllKeys()) {
            try {
                UUID playerId = UUID.fromString(key);
                data.lastSent.put(playerId, sentTag.getLong(key));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return data;
    }
}
