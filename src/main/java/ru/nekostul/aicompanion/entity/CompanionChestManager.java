package ru.nekostul.aicompanion.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

final class CompanionChestManager {
    private static final String CHEST_REQUEST_KEY = "entity.aicompanion.companion.chest.request";
    private static final String CHEST_FOUND_KEY = "entity.aicompanion.companion.chest.found";
    private static final String CHEST_ASSIGN_MISSING_KEY = "entity.aicompanion.companion.chest.assign.missing";
    private static final String CHEST_ALREADY_KEY = "entity.aicompanion.companion.chest.already";
    private static final String CHEST_LOST_KEY = "entity.aicompanion.companion.chest.lost";
    private static final String CHEST_POS_NBT = "ChestPos";
    private static final String CHEST_POS_SECONDARY_NBT = "ChestPosSecondary";
    private static final String CHEST_DIM_NBT = "ChestDim";
    private static final int CHEST_REQUEST_COOLDOWN_TICKS = 1200;
    private static final double CHEST_ASSIGN_RANGE = 6.0D;

    private final CompanionEntity owner;
    private final CompanionInventory inventory;
    private BlockPos chestPos;
    private BlockPos chestSecondaryPos;
    private boolean awaitingChest;
    private long lastRequestTick = -10000L;
    private boolean chestLostPendingNotice;

    CompanionChestManager(CompanionEntity owner, CompanionInventory inventory) {
        this.owner = owner;
        this.inventory = inventory;
    }

    void requestChest(Player player, long gameTime) {
        if (player == null) {
            return;
        }
        if (chestPos != null) {
            owner.sendReply(player, Component.translatable(CHEST_ALREADY_KEY,
                    chestPos.getX(), chestPos.getY(), chestPos.getZ()));
            return;
        }
        if (gameTime - lastRequestTick < CHEST_REQUEST_COOLDOWN_TICKS) {
            return;
        }
        lastRequestTick = gameTime;
        awaitingChest = true;
        owner.sendReply(player, Component.translatable(CHEST_REQUEST_KEY));
    }

    void tick(Player player) {
        if (chestPos != null) {
            boolean mainLoaded = owner.level().hasChunkAt(chestPos);
            boolean secondaryLoaded = chestSecondaryPos != null && owner.level().hasChunkAt(chestSecondaryPos);
            if (!mainLoaded && !secondaryLoaded) {
                return;
            }
            ChestPair mainPair = mainLoaded ? resolveChestPair(chestPos) : null;
            ChestPair secondaryPair = secondaryLoaded ? resolveChestPair(chestSecondaryPos) : null;
            if (mainPair == null && secondaryPair == null) {
                chestPos = null;
                chestSecondaryPos = null;
                awaitingChest = false;
                chestLostPendingNotice = true;
            } else {
                ChestPair active = mainPair != null ? mainPair : secondaryPair;
                chestPos = active.anchor;
                chestSecondaryPos = active.secondary;
            }
        }
        if (chestLostPendingNotice && player != null) {
            owner.sendReply(player, Component.translatable(CHEST_LOST_KEY));
            chestLostPendingNotice = false;
        }
    }

    boolean handleChestAssignment(Player player, String message) {
        if (player == null || message == null) {
            return false;
        }
        String normalized = message.trim()
                .toLowerCase(Locale.ROOT)
                .replace('\u0451', '\u0435');
        if (normalized.isEmpty()) {
            return false;
        }
        if (!normalized.contains("\u0432\u043e\u0442 \u0442\u0432\u043e\u0439 \u0441\u0443\u043d\u0434\u0443\u043a")
                && !normalized.contains("here is your chest")) {
            return false;
        }
        if (chestPos != null) {
            BlockPos target = findLookedAtChest(player);
            if (target != null && isSameChest(target, chestPos)) {
                owner.sendReply(player, Component.translatable("entity.aicompanion.companion.chest.already.same"));
            } else {
                owner.sendReply(player, Component.translatable(CHEST_ALREADY_KEY,
                        chestPos.getX(), chestPos.getY(), chestPos.getZ()));
            }
            return true;
        }
        BlockPos target = findLookedAtChest(player);
        if (target == null) {
            owner.sendReply(player, Component.translatable(CHEST_ASSIGN_MISSING_KEY));
            return true;
        }
        ChestPair pair = resolveChestPair(target);
        if (pair == null) {
            owner.sendReply(player, Component.translatable(CHEST_ASSIGN_MISSING_KEY));
            return true;
        }
        chestPos = pair.anchor;
        chestSecondaryPos = pair.secondary;
        awaitingChest = false;
        chestLostPendingNotice = false;
        owner.sendReply(player, Component.translatable(CHEST_FOUND_KEY));
        return true;
    }

    boolean hasChest() {
        return chestPos != null && isChestValid(chestPos);
    }

    boolean depositToChest() {
        if (!hasChest()) {
            return false;
        }
        BlockEntity blockEntity = owner.level().getBlockEntity(chestPos);
        if (!(blockEntity instanceof Container container)) {
            return false;
        }
        int moved = inventory.transferToContainer(container);
        return moved > 0;
    }

    void saveToTag(CompoundTag tag) {
        if (chestPos == null) {
            return;
        }
        tag.putLong(CHEST_POS_NBT, chestPos.asLong());
        if (chestSecondaryPos != null) {
            tag.putLong(CHEST_POS_SECONDARY_NBT, chestSecondaryPos.asLong());
        }
        tag.putString(CHEST_DIM_NBT, owner.level().dimension().location().toString());
    }

    void loadFromTag(CompoundTag tag) {
        chestPos = null;
        chestSecondaryPos = null;
        awaitingChest = false;
        chestLostPendingNotice = false;
        if (!tag.contains(CHEST_POS_NBT) || !tag.contains(CHEST_DIM_NBT)) {
            return;
        }
        ResourceLocation dim = ResourceLocation.tryParse(tag.getString(CHEST_DIM_NBT));
        if (dim == null || !owner.level().dimension().location().equals(dim)) {
            chestLostPendingNotice = true;
            return;
        }
        chestPos = BlockPos.of(tag.getLong(CHEST_POS_NBT));
        if (tag.contains(CHEST_POS_SECONDARY_NBT)) {
            chestSecondaryPos = BlockPos.of(tag.getLong(CHEST_POS_SECONDARY_NBT));
        }
    }

    private boolean isChestValid(BlockPos pos) {
        return resolveChestPair(pos) != null;
    }

    private BlockPos findLookedAtChest(Player player) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle().normalize();
        Vec3 end = eye.add(look.scale(CHEST_ASSIGN_RANGE));
        BlockHitResult hit = player.level().clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, player));
        if (hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        BlockPos pos = hit.getBlockPos();
        return isChestBlock(owner.level().getBlockState(pos)) ? pos.immutable() : null;
    }

    private boolean isSameChest(BlockPos a, BlockPos b) {
        if (a == null || b == null) {
            return false;
        }
        BlockPos anchorA = resolveChestPair(a) != null ? resolveChestPair(a).anchor : null;
        BlockPos anchorB = resolveChestPair(b) != null ? resolveChestPair(b).anchor : null;
        return anchorA != null && anchorA.equals(anchorB);
    }

    private ChestPair resolveChestPair(BlockPos pos) {
        if (pos == null) {
            return null;
        }
        BlockState state = owner.level().getBlockState(pos);
        if (!isChestBlock(state)) {
            return null;
        }
        if (!(state.getBlock() instanceof ChestBlock)) {
            return new ChestPair(pos.immutable(), null);
        }
        ChestType type = state.getValue(ChestBlock.TYPE);
        if (type == ChestType.SINGLE) {
            return new ChestPair(pos.immutable(), null);
        }
        BlockPos otherPos = pos.relative(ChestBlock.getConnectedDirection(state));
        BlockState otherState = owner.level().getBlockState(otherPos);
        if (!isChestBlock(otherState)) {
            return new ChestPair(pos.immutable(), null);
        }
        BlockPos anchor = comparePos(pos, otherPos) <= 0 ? pos.immutable() : otherPos.immutable();
        BlockPos secondary = anchor.equals(pos) ? otherPos.immutable() : pos.immutable();
        return new ChestPair(anchor, secondary);
    }

    private boolean isChestBlock(BlockState state) {
        return state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST);
    }

    private int comparePos(BlockPos a, BlockPos b) {
        int cmp = Integer.compare(a.getX(), b.getX());
        if (cmp != 0) {
            return cmp;
        }
        cmp = Integer.compare(a.getY(), b.getY());
        if (cmp != 0) {
            return cmp;
        }
        return Integer.compare(a.getZ(), b.getZ());
    }

    private static final class ChestPair {
        private final BlockPos anchor;
        private final BlockPos secondary;

        private ChestPair(BlockPos anchor, BlockPos secondary) {
            this.anchor = anchor;
            this.secondary = secondary;
        }
    }
}
