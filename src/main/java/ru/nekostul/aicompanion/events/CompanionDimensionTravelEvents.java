package ru.nekostul.aicompanion.events;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import ru.nekostul.aicompanion.AiCompanionMod;
import ru.nekostul.aicompanion.entity.CompanionEntity;
import ru.nekostul.aicompanion.entity.CompanionSingleNpcManager;

import java.util.UUID;

@Mod.EventBusSubscriber(modid = AiCompanionMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CompanionDimensionTravelEvents {
    private CompanionDimensionTravelEvents() {
    }

    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event == null || !(event.getEntity() instanceof ServerPlayer player) || player.server == null) {
            return;
        }
        CompanionSingleNpcManager.ensureLoaded(player.server);
        CompanionEntity companion = CompanionSingleNpcManager.getActiveIncludingDead(player);
        if (companion == null) {
            UUID companionId = CompanionSingleNpcManager.getActiveId();
            ResourceKey<Level> levelKey = CompanionSingleNpcManager.getActiveDimension();
            BlockPos lastPos = CompanionSingleNpcManager.getLastKnownPos();
            if (companionId != null && levelKey != null && lastPos != null) {
                ServerLevel companionLevel = player.server.getLevel(levelKey);
                if (companionLevel != null) {
                    companionLevel.getChunkSource().getChunk(lastPos.getX() >> 4, lastPos.getZ() >> 4,
                            ChunkStatus.FULL, true);
                    Entity entity = companionLevel.getEntity(companionId);
                    if (entity instanceof CompanionEntity loadedCompanion) {
                        companion = loadedCompanion;
                    }
                }
            }
        }
        if (companion == null || !companion.isAlive()) {
            return;
        }
        companion.requestDimensionTeleport(player);
    }
}
