package ru.nekostul.aicompanion.events;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraft.server.level.ServerLevel;

import ru.nekostul.aicompanion.AiCompanionMod;
import ru.nekostul.aicompanion.entity.inventory.CompanionDropTracker;
import ru.nekostul.aicompanion.entity.CompanionEntity;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

@Mod.EventBusSubscriber(modid = AiCompanionMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CompanionDropEvents {
    private static final int BLOCK_DROP_MARK_DELAY_TICKS = 1;
    private static final int BLOCK_DROP_MARK_LIFETIME_TICKS = 40;
    private static final double BLOCK_DROP_MARK_RADIUS_XZ = 28.0D;
    private static final double BLOCK_DROP_MARK_RADIUS_Y = 12.0D;
    private static final int BLOCK_DROP_MAX_AGE_TICKS = 40;

    private static final List<PendingPlayerBlockDropMark> PENDING_BLOCK_DROP_MARKS = new ArrayList<>();

    private static final class PendingPlayerBlockDropMark {
        private final UUID playerId;
        private final net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension;
        private final net.minecraft.core.BlockPos center;
        private final long readyTick;
        private final long expireTick;

        private PendingPlayerBlockDropMark(UUID playerId,
                                           net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
                                           net.minecraft.core.BlockPos center,
                                           long readyTick,
                                           long expireTick) {
            this.playerId = playerId;
            this.dimension = dimension;
            this.center = center;
            this.readyTick = readyTick;
            this.expireTick = expireTick;
        }
    }

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

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event == null) {
            return;
        }
        Player player = event.getPlayer();
        if (player == null || player.isSpectator() || player instanceof FakePlayer) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        long now = serverLevel.getGameTime();
        PENDING_BLOCK_DROP_MARKS.add(new PendingPlayerBlockDropMark(
                player.getUUID(),
                serverLevel.dimension(),
                event.getPos().immutable(),
                now + BLOCK_DROP_MARK_DELAY_TICKS,
                now + BLOCK_DROP_MARK_DELAY_TICKS + BLOCK_DROP_MARK_LIFETIME_TICKS
        ));
    }

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event == null || event.phase != TickEvent.Phase.END) {
            return;
        }
        if (!(event.level instanceof ServerLevel serverLevel)) {
            return;
        }
        long now = serverLevel.getGameTime();
        Iterator<PendingPlayerBlockDropMark> iterator = PENDING_BLOCK_DROP_MARKS.iterator();
        while (iterator.hasNext()) {
            PendingPlayerBlockDropMark pending = iterator.next();
            if (!pending.dimension.equals(serverLevel.dimension())) {
                continue;
            }
            if (now >= pending.readyTick) {
                AABB range = new AABB(pending.center).inflate(
                        BLOCK_DROP_MARK_RADIUS_XZ,
                        BLOCK_DROP_MARK_RADIUS_Y,
                        BLOCK_DROP_MARK_RADIUS_XZ
                );
                List<ItemEntity> drops = serverLevel.getEntitiesOfClass(ItemEntity.class, range);
                for (ItemEntity item : drops) {
                    if (!item.isAlive() || item.tickCount > BLOCK_DROP_MAX_AGE_TICKS) {
                        continue;
                    }
                    UUID trackedDropper = CompanionDropTracker.getPlayerDropper(item);
                    if (trackedDropper != null && !pending.playerId.equals(trackedDropper)) {
                        continue;
                    }
                    UUID vanillaDropper = CompanionDropTracker.getVanillaDropper(item);
                    if (vanillaDropper != null && !pending.playerId.equals(vanillaDropper)) {
                        continue;
                    }
                    CompanionDropTracker.markPlayerBlockDrop(item, pending.playerId);
                }
            }
            if (now >= pending.expireTick) {
                iterator.remove();
            }
        }
    }
}
