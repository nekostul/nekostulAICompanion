package ru.nekostul.aicompanion.events;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import ru.nekostul.aicompanion.AiCompanionMod;
import ru.nekostul.aicompanion.entity.CompanionSingleNpcManager;

@Mod.EventBusSubscriber(modid = AiCompanionMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CompanionHomeProtectionEvents {
    private CompanionHomeProtectionEvents() {
    }

    @SubscribeEvent
    public static void onBreakHomeBlock(BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        if (player.getAbilities().instabuild) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        CompanionSingleNpcManager.ensureLoaded(server);
        BlockPos homePos = CompanionSingleNpcManager.getLastHomePos();
        ResourceKey<Level> homeDimension = CompanionSingleNpcManager.getLastHomeDimension();
        if (homePos == null || homeDimension == null) {
            return;
        }
        if (!player.serverLevel().dimension().equals(homeDimension)) {
            return;
        }
        if (!event.getPos().equals(homePos)) {
            return;
        }
        event.setCanceled(true);
    }
}
