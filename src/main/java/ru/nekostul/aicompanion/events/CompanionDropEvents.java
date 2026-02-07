package ru.nekostul.aicompanion.events;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import ru.nekostul.aicompanion.AiCompanionMod;
import ru.nekostul.aicompanion.entity.inventory.CompanionDropTracker;
import ru.nekostul.aicompanion.entity.CompanionEntity;

import java.util.UUID;

@Mod.EventBusSubscriber(modid = AiCompanionMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CompanionDropEvents {
    private CompanionDropEvents() {
    }

    @SubscribeEvent
    public static void onLivingDrops(LivingDropsEvent event) {
        if (event == null) {
            return;
        }
        LivingEntity entity = event.getEntity();
        if (entity instanceof Player || entity instanceof CompanionEntity) {
            return;
        }
        Entity source = event.getSource().getEntity();
        UUID dropperId = source != null ? source.getUUID() : null;
        for (ItemEntity item : event.getDrops()) {
            CompanionDropTracker.markMobDrop(item, dropperId);
        }
    }

    @SubscribeEvent
    public static void onItemToss(ItemTossEvent event) {
        if (event == null) {
            return;
        }
        Player player = event.getPlayer();
        if (player == null || player.isSpectator()) {
            return;
        }
        ItemEntity entity = event.getEntity();
        if (entity == null) {
            return;
        }
        CompanionDropTracker.markPlayerDrop(entity, player.getUUID());
    }
}
