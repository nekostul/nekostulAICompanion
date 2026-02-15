package ru.nekostul.aicompanion.entity.mining;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.fml.ModList;
import ru.nekostul.aicompanion.CompanionConfig;

import java.lang.reflect.Method;

final class CompanionOreHarvesterIntegration {
    private static final String OREHARVESTER_MODID = "oreharvester";
    private static final String EVENTS_CLASS = "com.natamus.oreharvester_common_forge.events.OreMineEvents";
    private static final String ORE_HARVEST_METHOD = "onOreHarvest";
    private static final String PICKAXE_BLACKLIST_CLASS =
            "com.natamus.oreharvester_common_forge.processing.PickaxeBlacklist";
    private static final String PICKAXE_BLACKLIST_METHOD = "attemptProcessingPickaxeBlacklist";

    private static volatile Method onOreHarvestMethod;
    private static volatile Method pickaxeBlacklistMethod;
    private static volatile boolean lookupFailed;

    private CompanionOreHarvesterIntegration() {
    }

    static boolean tryHarvest(Level level,
                              ServerPlayer player,
                              BlockPos pos,
                              BlockState state,
                              BlockEntity blockEntity) {
        if (level == null || player == null || pos == null || state == null) {
            return false;
        }
        if (!CompanionConfig.isOreHarvesterIntegrationEnabled() || !ModList.get().isLoaded(OREHARVESTER_MODID)) {
            return false;
        }
        ensurePickaxeBlacklist(level);
        Method method = resolveOnOreHarvestMethod();
        if (method == null) {
            return false;
        }
        if (invoke(method, level, player, pos, state, blockEntity, false)) {
            return true;
        }
        return invoke(method, level, player, pos, state, blockEntity, true);
    }

    private static Method resolveOnOreHarvestMethod() {
        Method cached = onOreHarvestMethod;
        if (cached != null) {
            return cached;
        }
        if (lookupFailed) {
            return null;
        }
        try {
            Class<?> eventsClass = Class.forName(EVENTS_CLASS);
            Method method = eventsClass.getMethod(
                    ORE_HARVEST_METHOD,
                    Level.class,
                    Player.class,
                    BlockPos.class,
                    BlockState.class,
                    BlockEntity.class
            );
            onOreHarvestMethod = method;
            return method;
        } catch (ReflectiveOperationException ignored) {
            lookupFailed = true;
            return null;
        }
    }

    private static void ensurePickaxeBlacklist(Level level) {
        if (level == null || lookupFailed) {
            return;
        }
        Method method = pickaxeBlacklistMethod;
        if (method == null) {
            try {
                Class<?> clazz = Class.forName(PICKAXE_BLACKLIST_CLASS);
                method = clazz.getMethod(PICKAXE_BLACKLIST_METHOD, Level.class);
                pickaxeBlacklistMethod = method;
            } catch (ReflectiveOperationException ignored) {
                lookupFailed = true;
                return;
            }
        }
        try {
            method.invoke(null, level);
        } catch (ReflectiveOperationException ignored) {
            lookupFailed = true;
        }
    }

    private static boolean invoke(Method method,
                                  Level level,
                                  ServerPlayer player,
                                  BlockPos pos,
                                  BlockState state,
                                  BlockEntity blockEntity,
                                  boolean sneaking) {
        boolean previousSneak = player.isShiftKeyDown();
        Pose previousPose = player.getPose();
        player.setShiftKeyDown(sneaking);
        if (sneaking && previousPose != Pose.CROUCHING) {
            player.setPose(Pose.CROUCHING);
        }
        try {
            Object result = method.invoke(null, level, player, pos, state, blockEntity);
            return result instanceof Boolean handled && !handled;
        } catch (ReflectiveOperationException ignored) {
            lookupFailed = true;
            return false;
        } finally {
            player.setShiftKeyDown(previousSneak);
            if (player.getPose() != previousPose) {
                player.setPose(previousPose);
            }
        }
    }
}
