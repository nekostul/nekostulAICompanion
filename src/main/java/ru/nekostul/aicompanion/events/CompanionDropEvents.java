package ru.nekostul.aicompanion.events;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.chat.Component;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Mod.EventBusSubscriber(modid = AiCompanionMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CompanionDropEvents {
    private static final int BLOCK_DROP_MARK_DELAY_TICKS = 1;
    private static final int BLOCK_DROP_MARK_LIFETIME_TICKS = 40;
    private static final double BLOCK_DROP_MARK_RADIUS_XZ = 28.0D;
    private static final double BLOCK_DROP_MARK_RADIUS_Y = 12.0D;
    private static final int BLOCK_DROP_MAX_AGE_TICKS = 40;
    private static final double OWNER_TOSS_DIRECT_ACCEPT_RADIUS = 48.0D;
    private static final double OWNER_TOSS_FALLBACK_ACCEPT_RADIUS = 128.0D;
    private static final int OWNER_TOSS_RETRY_INTERVAL_TICKS = 2;
    private static final int OWNER_TOSS_RETRY_MAX_AGE_TICKS = 400;
    private static final double OWNER_TOSS_RETRY_NEAR_PLAYER_RADIUS_SQR = 400.0D;
    private static final boolean OWNER_TOSS_GROUND_PICKUP_ONLY = true;
    private static final boolean DEBUG_OWNER_BLOCK_TOSS_3S = false;
    private static final long OWNER_BLOCK_TOSS_DEBUG_WINDOW_TICKS = 60L;
    private static final long OWNER_BLOCK_TOSS_DEBUG_STALE_TICKS = 200L;

    private static final List<PendingPlayerBlockDropMark> PENDING_BLOCK_DROP_MARKS = new ArrayList<>();
    private static final Map<UUID, OwnerBlockTossDebugWindow> OWNER_BLOCK_TOSS_DEBUG = new HashMap<>();

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

    private static final class OwnerBlockTossDebugWindow {
        private final net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension;
        private final UUID companionId;
        private final long windowStartTick;
        private long lastUpdateTick;
        private int blockStacks;
        private int blockItems;
        private int acceptedBlockStacks;
        private int acceptedBlockItems;

        private OwnerBlockTossDebugWindow(
                net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
                UUID companionId,
                long windowStartTick) {
            this.dimension = dimension;
            this.companionId = companionId;
            this.windowStartTick = windowStartTick;
            this.lastUpdateTick = windowStartTick;
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
        if (!(player.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        ItemStack tossedStack = entity.getItem();
        int tossedItems = tossedStack.isEmpty() ? 0 : tossedStack.getCount();
        boolean tossedBlockStack = !tossedStack.isEmpty() && tossedStack.getItem() instanceof BlockItem;
        int acceptedItems = 0;
        CompanionEntity acceptedBy = null;
        if (!OWNER_TOSS_GROUND_PICKUP_ONLY) {
            acceptedBy = tryDirectAcceptOwnerToss(serverLevel, player, entity);
            if (tossedItems > 0) {
                int remainingItems = entity.isAlive() ? entity.getItem().getCount() : 0;
                acceptedItems = Math.max(0, tossedItems - remainingItems);
            }
        }
        if (DEBUG_OWNER_BLOCK_TOSS_3S && tossedBlockStack && tossedItems > 0) {
            recordOwnerBlockTossDebug(serverLevel, player, acceptedBy, tossedItems, acceptedItems);
        }
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
                    if (CompanionDropTracker.isPlayerTossDrop(item)) {
                        continue;
                    }
                    CompanionDropTracker.markPlayerBlockDrop(item, pending.playerId);
                }
            }
            if (now >= pending.expireTick) {
                iterator.remove();
            }
        }
        if (!OWNER_TOSS_GROUND_PICKUP_ONLY && now % OWNER_TOSS_RETRY_INTERVAL_TICKS == 0L) {
            tickOwnerTossRetry(serverLevel);
        }
        tickOwnerBlockTossDebug(serverLevel, now);
    }

    private static void recordOwnerBlockTossDebug(ServerLevel serverLevel, Player player, CompanionEntity companion,
                                                  int tossedItems, int acceptedItems) {
        if (serverLevel == null || player == null || tossedItems <= 0) {
            return;
        }
        UUID playerId = player.getUUID();
        UUID companionId = companion != null ? companion.getUUID() : null;
        long now = serverLevel.getGameTime();
        OwnerBlockTossDebugWindow window = OWNER_BLOCK_TOSS_DEBUG.get(playerId);
        if (window == null
                || !window.dimension.equals(serverLevel.dimension())
                || !Objects.equals(window.companionId, companionId)) {
            window = new OwnerBlockTossDebugWindow(serverLevel.dimension(), companionId, now);
            OWNER_BLOCK_TOSS_DEBUG.put(playerId, window);
        }
        window.blockStacks++;
        window.blockItems += tossedItems;
        if (acceptedItems > 0) {
            window.acceptedBlockStacks++;
            window.acceptedBlockItems += Math.min(acceptedItems, tossedItems);
        }
        window.lastUpdateTick = now;
    }

    private static void tickOwnerBlockTossDebug(ServerLevel serverLevel, long now) {
        if (!DEBUG_OWNER_BLOCK_TOSS_3S || OWNER_BLOCK_TOSS_DEBUG.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, OwnerBlockTossDebugWindow>> iterator = OWNER_BLOCK_TOSS_DEBUG.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, OwnerBlockTossDebugWindow> entry = iterator.next();
            OwnerBlockTossDebugWindow window = entry.getValue();
            if (window == null) {
                iterator.remove();
                continue;
            }
            if (!window.dimension.equals(serverLevel.dimension())) {
                continue;
            }
            if (window.blockStacks > 0 && now - window.windowStartTick >= OWNER_BLOCK_TOSS_DEBUG_WINDOW_TICKS) {
                emitOwnerBlockTossDebug(serverLevel, entry.getKey(), window);
                iterator.remove();
                continue;
            }
            if (now - window.lastUpdateTick >= OWNER_BLOCK_TOSS_DEBUG_STALE_TICKS) {
                iterator.remove();
            }
        }
    }

    private static void emitOwnerBlockTossDebug(ServerLevel serverLevel, UUID playerId, OwnerBlockTossDebugWindow window) {
        if (serverLevel == null || playerId == null || window == null || window.blockStacks <= 0) {
            return;
        }
        Player player = serverLevel.getPlayerByUUID(playerId);
        if (player == null || player.isSpectator()) {
            return;
        }
        int rejectedStacks = Math.max(0, window.blockStacks - window.acceptedBlockStacks);
        int rejectedItems = Math.max(0, window.blockItems - window.acceptedBlockItems);
        Component message = Component.literal("[DEBUG toss3s] block tossed: "
                + window.blockStacks + " st / " + window.blockItems + " it"
                + ", npc accepted: " + window.acceptedBlockStacks + " st / " + window.acceptedBlockItems + " it"
                + ", not accepted: " + rejectedStacks + " st / " + rejectedItems + " it");
        CompanionEntity companion = null;
        if (window.companionId != null) {
            Entity entity = serverLevel.getEntity(window.companionId);
            if (entity instanceof CompanionEntity liveCompanion
                    && liveCompanion.isAlive()
                    && liveCompanion.isOwnedBy(player)) {
                companion = liveCompanion;
            }
        }
        if (companion != null) {
            companion.sendReply(player, message);
        } else {
            player.sendSystemMessage(Component.literal("nekostulAI: ").append(message));
        }
    }

    private static CompanionEntity tryDirectAcceptOwnerToss(ServerLevel serverLevel, Player player, ItemEntity entity) {
        if (serverLevel == null || player == null || entity == null || !entity.isAlive()) {
            return null;
        }
        List<CompanionEntity> close = collectOwnedCompanions(serverLevel, player, entity, OWNER_TOSS_DIRECT_ACCEPT_RADIUS);
        CompanionEntity acceptedBy = tryAcceptWithCompanions(close, entity, player);
        if (acceptedBy != null || !entity.isAlive() || entity.getItem().isEmpty()) {
            return acceptedBy;
        }
        if (OWNER_TOSS_FALLBACK_ACCEPT_RADIUS <= OWNER_TOSS_DIRECT_ACCEPT_RADIUS) {
            return acceptedBy;
        }
        List<CompanionEntity> fallback = collectOwnedCompanions(serverLevel, player, entity,
                OWNER_TOSS_FALLBACK_ACCEPT_RADIUS);
        if (!close.isEmpty() && !fallback.isEmpty()) {
            Set<UUID> seen = new HashSet<>();
            for (CompanionEntity companion : close) {
                if (companion != null) {
                    seen.add(companion.getUUID());
                }
            }
            fallback.removeIf(companion -> companion == null || seen.contains(companion.getUUID()));
        }
        CompanionEntity fallbackAcceptedBy = tryAcceptWithCompanions(fallback, entity, player);
        return fallbackAcceptedBy != null ? fallbackAcceptedBy : acceptedBy;
    }

    private static List<CompanionEntity> collectOwnedCompanions(ServerLevel serverLevel, Player player,
                                                                ItemEntity itemEntity, double radius) {
        if (serverLevel == null || player == null || itemEntity == null || radius <= 0.0D) {
            return List.of();
        }
        AABB range = itemEntity.getBoundingBox().inflate(radius);
        List<CompanionEntity> companions = serverLevel.getEntitiesOfClass(CompanionEntity.class, range,
                companion -> companion != null && companion.isAlive() && companion.isOwnedBy(player));
        companions.sort(Comparator.comparingDouble(companion -> companion.distanceToSqr(itemEntity)));
        return companions;
    }

    private static CompanionEntity tryAcceptWithCompanions(List<CompanionEntity> companions,
                                                           ItemEntity entity,
                                                           Player player) {
        if (companions == null || companions.isEmpty() || entity == null || player == null) {
            return null;
        }
        CompanionEntity acceptedBy = null;
        for (CompanionEntity companion : companions) {
            if (companion == null || !companion.isAlive()) {
                continue;
            }
            boolean accepted = companion.tryAcceptOwnerToss(entity, player);
            if (accepted && acceptedBy == null) {
                acceptedBy = companion;
            }
            if (!entity.isAlive() || entity.getItem().isEmpty()) {
                break;
            }
        }
        return acceptedBy;
    }

    private static void tickOwnerTossRetry(ServerLevel serverLevel) {
        if (serverLevel == null) {
            return;
        }
        List<? extends Player> players = serverLevel.players();
        if (players == null || players.isEmpty()) {
            return;
        }
        for (Player player : players) {
            if (player == null || player.isSpectator() || !player.isAlive()) {
                continue;
            }
            AABB range = player.getBoundingBox().inflate(OWNER_TOSS_FALLBACK_ACCEPT_RADIUS);
            List<ItemEntity> nearbyItems = serverLevel.getEntitiesOfClass(ItemEntity.class, range,
                    item -> item != null
                            && item.isAlive()
                            && item.tickCount <= OWNER_TOSS_RETRY_MAX_AGE_TICKS
                            && !item.getItem().isEmpty()
                            && !CompanionDropTracker.isMobDrop(item)
                            && !CompanionDropTracker.isPlayerBlockDrop(item));
            if (nearbyItems.isEmpty()) {
                continue;
            }
            UUID playerId = player.getUUID();
            for (ItemEntity itemEntity : nearbyItems) {
                if (itemEntity == null || !itemEntity.isAlive()) {
                    continue;
                }
                UUID trackedDropper = CompanionDropTracker.getPlayerDropper(itemEntity);
                if (trackedDropper != null) {
                    if (!playerId.equals(trackedDropper)) {
                        continue;
                    }
                } else {
                    UUID vanillaDropper = CompanionDropTracker.getVanillaDropper(itemEntity);
                    if (vanillaDropper != null) {
                        if (!playerId.equals(vanillaDropper)) {
                            continue;
                        }
                    } else if (itemEntity.distanceToSqr(player) > OWNER_TOSS_RETRY_NEAR_PLAYER_RADIUS_SQR) {
                        continue;
                    }
                }
                tryDirectAcceptOwnerToss(serverLevel, player, itemEntity);
            }
        }
    }
}
