package ru.nekostul.aicompanion.bugreport;

import net.minecraft.server.level.ServerPlayer;

public final class BugReportCooldown {
    private static final long COOLDOWN_MILLIS = 15L * 60L * 1000L;

    private BugReportCooldown() {
    }

    public static boolean canSend(ServerPlayer player) {
        BugReportCooldownData data = BugReportCooldownData.get(player.getServer());
        if (data == null) {
            return true;
        }
        return data.canSend(player.getUUID(), System.currentTimeMillis(), COOLDOWN_MILLIS);
    }

    public static long getRemainingSeconds(ServerPlayer player) {
        BugReportCooldownData data = BugReportCooldownData.get(player.getServer());
        if (data == null) {
            return 0;
        }
        return data.getRemainingSeconds(player.getUUID(), System.currentTimeMillis(), COOLDOWN_MILLIS);
    }

    public static void markSent(ServerPlayer player) {
        BugReportCooldownData data = BugReportCooldownData.get(player.getServer());
        if (data == null) {
            return;
        }
        data.markSent(player.getUUID(), System.currentTimeMillis());
    }
}
