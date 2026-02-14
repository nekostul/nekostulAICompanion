package ru.nekostul.aicompanion.events;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import ru.nekostul.aicompanion.AiCompanionMod;
import ru.nekostul.aicompanion.entity.CompanionSpawnEggData;
import ru.nekostul.aicompanion.registry.ModItems;

@Mod.EventBusSubscriber(modid = AiCompanionMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CompanionSpawnEggEvents {
    private CompanionSpawnEggEvents() {
    }

    @SubscribeEvent
    public static void onLevelLoad(LevelEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        if (serverLevel.dimension() != Level.OVERWORLD) {
            return;
        }
        CompanionSpawnEggData.get(serverLevel.getServer());
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        CompanionSpawnEggData data = CompanionSpawnEggData.get(player.getServer());
        if (data == null || !data.shouldGrantEgg()) {
            return;
        }
        ItemStack egg = new ItemStack(ModItems.COMPANION_SPAWN_EGG.get());
        boolean added = player.getInventory().add(egg);
        if (added) {
            data.markEggGranted();
        }
    }
}
