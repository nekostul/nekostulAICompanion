package ru.nekostul.aicompanion.events;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import ru.nekostul.aicompanion.AiCompanionMod;
import ru.nekostul.aicompanion.entity.CompanionEntity;
import ru.nekostul.aicompanion.entity.CompanionSingleNpcManager;

@Mod.EventBusSubscriber(modid = AiCompanionMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CompanionOwnerDeathEvents {
    private CompanionOwnerDeathEvents() {
    }

    @SubscribeEvent
    public static void onPlayerDeath(LivingDeathEvent event) {
        if (event == null || !(event.getEntity() instanceof ServerPlayer player) || player.server == null) {
            return;
        }
        CompanionSingleNpcManager.ensureLoaded(player.server);
        CompanionEntity companion = CompanionSingleNpcManager.getActiveIncludingDead(player);
        if (companion == null || !companion.isAlive() || !companion.isOwnedBy(player)) {
            return;
        }
        BlockPos deathPos = player.blockPosition().immutable();
        if (companion.level() != player.level()
                && companion.level() instanceof ServerLevel
                && player.level() instanceof ServerLevel targetLevel) {
            Entity moved = companion.changeDimension(targetLevel);
            if (moved instanceof CompanionEntity movedCompanion) {
                companion = movedCompanion;
            } else {
                return;
            }
        }
        companion.onOwnerDeath(player, deathPos);
    }
}
