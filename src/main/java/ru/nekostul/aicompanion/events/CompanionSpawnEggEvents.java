package ru.nekostul.aicompanion.events;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import ru.nekostul.aicompanion.AiCompanionMod;
import ru.nekostul.aicompanion.entity.CompanionSpawnEggData;
import ru.nekostul.aicompanion.entity.CompanionSingleNpcManager;
import ru.nekostul.aicompanion.registry.ModItems;

@Mod.EventBusSubscriber(modid = AiCompanionMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CompanionSpawnEggEvents {
    private static final String SPAWN_EXISTS_KEY = "entity.aicompanion.companion.spawn.exists";

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

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.level().isClientSide) {
            return;
        }
        if (!shouldBlockSpawnEgg(player, event.getItemStack())) {
            return;
        }
        player.sendSystemMessage(Component.translatable(SPAWN_EXISTS_KEY));
        event.setCancellationResult(InteractionResult.FAIL);
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.level().isClientSide) {
            return;
        }
        if (!shouldBlockSpawnEgg(player, event.getItemStack())) {
            return;
        }
        player.sendSystemMessage(Component.translatable(SPAWN_EXISTS_KEY));
        event.setCancellationResult(InteractionResult.FAIL);
        event.setCanceled(true);
    }

    private static boolean shouldBlockSpawnEgg(ServerPlayer player, ItemStack stack) {
        if (stack.isEmpty() || stack.getItem() != ModItems.COMPANION_SPAWN_EGG.get()) {
            return false;
        }
        return CompanionSingleNpcManager.hasOwnedCompanion(player);
    }
}
