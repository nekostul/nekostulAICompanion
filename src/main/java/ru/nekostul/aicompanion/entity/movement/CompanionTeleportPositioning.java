package ru.nekostul.aicompanion.entity.movement;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import ru.nekostul.aicompanion.entity.CompanionEntity;

public final class CompanionTeleportPositioning {
    private CompanionTeleportPositioning() {
    }

    public static Vec3 resolveTeleportTarget(CompanionEntity companion,
                                             Player player,
                                             double behindDistance,
                                             double sideDistance,
                                             int nearbyRadius,
                                             double fovDotThreshold,
                                             int ySearchUp,
                                             int ySearchDown) {
        if (companion == null) {
            return Vec3.ZERO;
        }
        if (player == null) {
            return companion.position();
        }
        Vec3 playerPos = player.position();
        Vec3 forward = player.getLookAngle();
        forward = new Vec3(forward.x, 0.0D, forward.z);
        if (forward.lengthSqr() < 1.0E-4D) {
            float yaw = player.getYRot() * ((float) Math.PI / 180.0F);
            forward = new Vec3(-Mth.sin(yaw), 0.0D, Mth.cos(yaw));
        }
        forward = forward.normalize();
        Vec3 right = new Vec3(-forward.z, 0.0D, forward.x);
        Vec3 behind = forward.scale(-behindDistance);
        Vec3[] offsets = new Vec3[]{
                behind,
                behind.add(right.scale(sideDistance)),
                behind.add(right.scale(-sideDistance)),
                right.scale(sideDistance + 1.0D),
                right.scale(-(sideDistance + 1.0D)),
                behind.scale(1.5D)
        };
        Vec3 spot = findTeleportSpot(companion, playerPos, forward, offsets, true, fovDotThreshold,
                ySearchUp, ySearchDown);
        if (spot != null) {
            return spot;
        }
        spot = findTeleportSpot(companion, playerPos, forward, offsets, false, fovDotThreshold,
                ySearchUp, ySearchDown);
        if (spot != null) {
            return spot;
        }
        spot = findNearbySafeSpot(companion, playerPos, forward, nearbyRadius, true, fovDotThreshold,
                ySearchUp, ySearchDown);
        if (spot != null) {
            return spot;
        }
        spot = findNearbySafeSpot(companion, playerPos, forward, nearbyRadius, false, fovDotThreshold,
                ySearchUp, ySearchDown);
        if (spot != null) {
            return spot;
        }
        return companion.position();
    }

    public static Vec3 resolveInitialDimensionTeleportTarget(ServerPlayer player, double behindDistance) {
        if (player == null) {
            return Vec3.ZERO;
        }
        Vec3 playerPos = player.position();
        Vec3 forward = player.getLookAngle();
        forward = new Vec3(forward.x, 0.0D, forward.z);
        if (forward.lengthSqr() < 1.0E-4D) {
            float yaw = player.getYRot() * ((float) Math.PI / 180.0F);
            forward = new Vec3(-Mth.sin(yaw), 0.0D, Mth.cos(yaw));
        } else {
            forward = forward.normalize();
        }
        return new Vec3(
                playerPos.x - forward.x * behindDistance,
                playerPos.y,
                playerPos.z - forward.z * behindDistance
        );
    }

    public static boolean isOutOfPlayerView(Vec3 playerPos, Vec3 forward, Vec3 targetPos, double fovDotThreshold) {
        Vec3 toTarget = targetPos.subtract(playerPos);
        toTarget = new Vec3(toTarget.x, 0.0D, toTarget.z);
        if (toTarget.lengthSqr() < 1.0E-4D) {
            return false;
        }
        double dot = forward.dot(toTarget.normalize());
        return dot < fovDotThreshold;
    }

    public static Vec3 adjustTeleportY(CompanionEntity companion, Vec3 basePos, int ySearchUp, int ySearchDown) {
        Level level = companion.level();
        if (level == null) {
            return basePos;
        }
        BlockPos base = BlockPos.containing(basePos);
        for (int dy = ySearchUp; dy >= -ySearchDown; dy--) {
            BlockPos feetPos = new BlockPos(base.getX(), base.getY() + dy, base.getZ());
            BlockPos groundPos = feetPos.below();
            if (!isSafeGround(level, groundPos)) {
                continue;
            }
            if (!isClearForTeleport(level, feetPos) || !isClearForTeleport(level, feetPos.above())) {
                continue;
            }
            Vec3 candidate = new Vec3(basePos.x, feetPos.getY(), basePos.z);
            if (isSafeTeleportPosition(companion, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static Vec3 findTeleportSpot(CompanionEntity companion,
                                         Vec3 playerPos,
                                         Vec3 forward,
                                         Vec3[] offsets,
                                         boolean requireOutOfView,
                                         double fovDotThreshold,
                                         int ySearchUp,
                                         int ySearchDown) {
        for (Vec3 offset : offsets) {
            Vec3 candidate = new Vec3(playerPos.x + offset.x, playerPos.y, playerPos.z + offset.z);
            Vec3 adjusted = adjustTeleportY(companion, candidate, ySearchUp, ySearchDown);
            if (adjusted == null) {
                continue;
            }
            if (requireOutOfView && !isOutOfPlayerView(playerPos, forward, adjusted, fovDotThreshold)) {
                continue;
            }
            return adjusted;
        }
        return null;
    }

    private static Vec3 findNearbySafeSpot(CompanionEntity companion,
                                           Vec3 playerPos,
                                           Vec3 forward,
                                           int radius,
                                           boolean requireOutOfView,
                                           double fovDotThreshold,
                                           int ySearchUp,
                                           int ySearchDown) {
        Vec3 best = null;
        double bestDistance = Double.MAX_VALUE;
        int baseX = Mth.floor(playerPos.x);
        int baseZ = Mth.floor(playerPos.z);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                Vec3 candidate = new Vec3(baseX + 0.5D + dx, playerPos.y, baseZ + 0.5D + dz);
                Vec3 adjusted = adjustTeleportY(companion, candidate, ySearchUp, ySearchDown);
                if (adjusted == null) {
                    continue;
                }
                if (requireOutOfView && !isOutOfPlayerView(playerPos, forward, adjusted, fovDotThreshold)) {
                    continue;
                }
                double distance = adjusted.distanceToSqr(playerPos);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = adjusted;
                }
            }
        }
        return best;
    }

    private static boolean isSafeTeleportPosition(CompanionEntity companion, Vec3 pos) {
        Level level = companion.level();
        if (level == null) {
            return true;
        }
        BlockPos blockPos = BlockPos.containing(pos);
        BlockPos groundPos = blockPos.below();
        if (!level.hasChunkAt(blockPos) || !level.hasChunkAt(groundPos)) {
            return false;
        }
        if (!isSafeGround(level, groundPos)) {
            return false;
        }
        if (!isClearForTeleport(level, blockPos) || !isClearForTeleport(level, blockPos.above())) {
            return false;
        }
        AABB box = companion.getBoundingBox().move(pos.x - companion.getX(), pos.y - companion.getY(),
                pos.z - companion.getZ());
        return level.noCollision(companion, box);
    }

    private static boolean isSafeGround(Level level, BlockPos groundPos) {
        if (level == null || groundPos == null) {
            return false;
        }
        if (groundPos.getY() < level.getMinBuildHeight()) {
            return false;
        }
        if (!level.hasChunkAt(groundPos)) {
            return false;
        }
        BlockState state = level.getBlockState(groundPos);
        if (!state.getFluidState().isEmpty()) {
            return false;
        }
        if (!state.isFaceSturdy(level, groundPos, Direction.UP)) {
            return false;
        }
        return !isDamagingBlock(state);
    }

    private static boolean isClearForTeleport(Level level, BlockPos pos) {
        if (level == null || pos == null) {
            return false;
        }
        if (!level.hasChunkAt(pos)) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        if (!state.getFluidState().isEmpty()) {
            return false;
        }
        return state.getCollisionShape(level, pos).isEmpty();
    }

    private static boolean isDamagingBlock(BlockState state) {
        return state.is(Blocks.MAGMA_BLOCK)
                || state.is(Blocks.CAMPFIRE)
                || state.is(Blocks.SOUL_CAMPFIRE)
                || state.is(Blocks.CACTUS)
                || state.is(Blocks.SWEET_BERRY_BUSH)
                || state.is(Blocks.WITHER_ROSE)
                || state.is(Blocks.FIRE)
                || state.is(Blocks.SOUL_FIRE);
    }
}
