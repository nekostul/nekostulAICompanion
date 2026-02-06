package ru.nekostul.aicompanion.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

final class CompanionBucketHandler {
    enum BucketStatus {
        READY,
        NEED_BUCKETS
    }

    enum FillResult {
        IN_PROGRESS,
        DONE,
        NOT_FOUND
    }

    private static final String BUCKET_NEED_KEY = "entity.aicompanion.companion.bucket.need";
    private static final int BUCKET_REQUEST_COOLDOWN_TICKS = 1200;
    private static final int CHUNK_RADIUS = 6;
    private static final int FLUID_SCAN_RADIUS = CHUNK_RADIUS * 16;
    private static final int FLUID_SCAN_COOLDOWN_TICKS = 80;
    private static final float FLUID_FOV_DOT = -1.0F;
    private static final double FLUID_USE_RANGE_SQR = 6.0D;
    private static final int FILL_COOLDOWN_TICKS = 10;

    private final CompanionEntity owner;
    private final CompanionInventory inventory;
    private long lastBucketRequestTick = -10000L;
    private long nextScanTick = -1L;
    private BlockPos cachedFluidPos;
    private long nextFillTick = -1L;
    private long lastScanTick = -1L;
    private boolean lastScanFound = true;

    CompanionBucketHandler(CompanionEntity owner, CompanionInventory inventory) {
        this.owner = owner;
        this.inventory = inventory;
    }

    BucketStatus ensureBuckets(CompanionResourceRequest request, Player player, long gameTime) {
        if (request == null || !request.getResourceType().isBucketResource()) {
            return BucketStatus.READY;
        }
        int filled = inventory.countMatching(request.getResourceType()::matchesItem);
        int needed = Math.max(0, request.getAmount() - filled);
        int emptyBuckets = inventory.countItem(Items.BUCKET);
        int missing = Math.max(0, needed - emptyBuckets);
        if (missing > 0) {
            if (player != null && gameTime - lastBucketRequestTick >= BUCKET_REQUEST_COOLDOWN_TICKS) {
                lastBucketRequestTick = gameTime;
                owner.sendReply(player, net.minecraft.network.chat.Component.translatable(BUCKET_NEED_KEY, missing));
            }
            return BucketStatus.NEED_BUCKETS;
        }
        return BucketStatus.READY;
    }

    FillResult tickFillBuckets(CompanionResourceRequest request, Player player, long gameTime) {
        if (request == null || !request.getResourceType().isBucketResource()) {
            return FillResult.IN_PROGRESS;
        }
        int filled = inventory.countMatching(request.getResourceType()::matchesItem);
        if (filled >= request.getAmount()) {
            return FillResult.DONE;
        }
        if (inventory.countItem(Items.BUCKET) <= 0) {
            return FillResult.IN_PROGRESS;
        }
        BlockPos target = findFluidSource(request.getResourceType(), gameTime);
        if (target == null) {
            if (gameTime == lastScanTick && !lastScanFound) {
                return FillResult.NOT_FOUND;
            }
            return FillResult.IN_PROGRESS;
        }
        Vec3 center = Vec3.atCenterOf(target);
        double distance = owner.distanceToSqr(center);
        if (distance > FLUID_USE_RANGE_SQR) {
            owner.getNavigation().moveTo(center.x, center.y, center.z, 1.0D);
            return FillResult.IN_PROGRESS;
        }
        owner.getNavigation().stop();
        owner.getLookControl().setLookAt(center.x, center.y, center.z);
        if (gameTime < nextFillTick) {
            return FillResult.IN_PROGRESS;
        }
        nextFillTick = gameTime + FILL_COOLDOWN_TICKS;
        ItemStack filledStack = new ItemStack(request.getResourceType() == CompanionResourceType.WATER
                ? Items.WATER_BUCKET : Items.LAVA_BUCKET);
        if (!inventory.consumeItem(Items.BUCKET, 1)) {
            return FillResult.IN_PROGRESS;
        }
        inventory.add(filledStack);
        owner.swing(InteractionHand.MAIN_HAND, true);
        return inventory.countMatching(request.getResourceType()::matchesItem) >= request.getAmount()
                ? FillResult.DONE : FillResult.IN_PROGRESS;
    }

    private BlockPos findFluidSource(CompanionResourceType type, long gameTime) {
        if (gameTime < nextScanTick && cachedFluidPos != null) {
            BlockState cached = owner.level().getBlockState(cachedFluidPos);
            if (type.matchesBlock(cached)) {
                return cachedFluidPos;
            }
        }
        if (gameTime < nextScanTick) {
            return null;
        }
        lastScanTick = gameTime;
        lastScanFound = false;
        Vec3 eye = owner.getEyePosition();
        Vec3 look = owner.getLookAngle().normalize();
        BlockPos origin = owner.blockPosition();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;

        for (int dx = -FLUID_SCAN_RADIUS; dx <= FLUID_SCAN_RADIUS; dx++) {
            for (int dy = -FLUID_SCAN_RADIUS; dy <= FLUID_SCAN_RADIUS; dy++) {
                for (int dz = -FLUID_SCAN_RADIUS; dz <= FLUID_SCAN_RADIUS; dz++) {
                    pos.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    BlockState state = owner.level().getBlockState(pos);
                    if (!type.matchesBlock(state)) {
                        continue;
                    }
                    Vec3 targetCenter = Vec3.atCenterOf(pos);
                    Vec3 toTarget = targetCenter.subtract(eye);
                    double distanceSqr = owner.distanceToSqr(targetCenter);
                    if (distanceSqr > (double) FLUID_SCAN_RADIUS * FLUID_SCAN_RADIUS) {
                        continue;
                    }
                    if (look.dot(toTarget.normalize()) < FLUID_FOV_DOT) {
                        continue;
                    }
                    if (distanceSqr < bestDistance) {
                        bestDistance = distanceSqr;
                        best = pos.immutable();
                    }
                }
            }
        }
        if (best != null) {
            lastScanFound = true;
        }
        nextScanTick = gameTime + FLUID_SCAN_COOLDOWN_TICKS;
        cachedFluidPos = best;
        return best;
    }

    private boolean hasLineOfSight(Vec3 start, Vec3 end, BlockPos target) {
        BlockHitResult hit = owner.level().clip(new ClipContext(start, end, ClipContext.Block.OUTLINE,
                ClipContext.Fluid.ANY, owner));
        if (hit.getType() == HitResult.Type.MISS) {
            return true;
        }
        BlockPos hitPos = hit.getBlockPos();
        if (hitPos.equals(target)) {
            return true;
        }
        BlockState hitState = owner.level().getBlockState(hitPos);
        if (hitState.is(Blocks.WATER) || hitState.is(Blocks.LAVA)) {
            return true;
        }
        return false;
    }
}
