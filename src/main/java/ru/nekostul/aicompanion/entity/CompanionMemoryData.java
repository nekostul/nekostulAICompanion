package ru.nekostul.aicompanion.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.UUID;

public final class CompanionMemoryData extends SavedData {
    private static final String DATA_NAME = "aicompanion_companion";
    private static final String KEY_ACTIVE_ID = "ActiveId";
    private static final String KEY_ACTIVE_DIM = "ActiveDim";
    private static final String KEY_LAST_POS = "LastPos";
    private static final String KEY_HOME_POS = "HomePos";
    private static final String KEY_HOME_DIM = "HomeDim";

    private UUID activeId;
    private ResourceLocation activeDimensionId;
    private BlockPos lastKnownPos;
    private BlockPos homePos;
    private ResourceLocation homeDimensionId;

    private CompanionMemoryData() {
    }

    public static CompanionMemoryData get(MinecraftServer server) {
        if (server == null) {
            return null;
        }
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) {
            return null;
        }
        return overworld.getDataStorage().computeIfAbsent(
                CompanionMemoryData::load,
                CompanionMemoryData::new,
                DATA_NAME
        );
    }

    public UUID getActiveId() {
        return activeId;
    }

    public ResourceKey<Level> getActiveDimension() {
        if (activeDimensionId == null) {
            return null;
        }
        return ResourceKey.create(Registries.DIMENSION, activeDimensionId);
    }

    public BlockPos getLastKnownPos() {
        return lastKnownPos;
    }

    public BlockPos getHomePos() {
        return homePos;
    }

    public ResourceKey<Level> getHomeDimension() {
        if (homeDimensionId == null) {
            return null;
        }
        return ResourceKey.create(Registries.DIMENSION, homeDimensionId);
    }

    public void setActive(UUID id, ResourceKey<Level> dimension, BlockPos pos) {
        activeId = id;
        activeDimensionId = dimension != null ? dimension.location() : null;
        lastKnownPos = pos;
    }

    public void setHome(BlockPos pos, ResourceKey<Level> dimension) {
        homePos = pos;
        homeDimensionId = dimension != null ? dimension.location() : null;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        if (activeId != null) {
            tag.putUUID(KEY_ACTIVE_ID, activeId);
        }
        if (activeDimensionId != null) {
            tag.putString(KEY_ACTIVE_DIM, activeDimensionId.toString());
        }
        if (lastKnownPos != null) {
            tag.putLong(KEY_LAST_POS, lastKnownPos.asLong());
        }
        if (homePos != null) {
            tag.putLong(KEY_HOME_POS, homePos.asLong());
        }
        if (homeDimensionId != null) {
            tag.putString(KEY_HOME_DIM, homeDimensionId.toString());
        }
        return tag;
    }

    private static CompanionMemoryData load(CompoundTag tag) {
        CompanionMemoryData data = new CompanionMemoryData();
        if (tag.hasUUID(KEY_ACTIVE_ID)) {
            data.activeId = tag.getUUID(KEY_ACTIVE_ID);
        }
        if (tag.contains(KEY_ACTIVE_DIM)) {
            data.activeDimensionId = new ResourceLocation(tag.getString(KEY_ACTIVE_DIM));
        }
        if (tag.contains(KEY_LAST_POS)) {
            data.lastKnownPos = BlockPos.of(tag.getLong(KEY_LAST_POS));
        }
        if (tag.contains(KEY_HOME_POS)) {
            data.homePos = BlockPos.of(tag.getLong(KEY_HOME_POS));
        }
        if (tag.contains(KEY_HOME_DIM)) {
            data.homeDimensionId = new ResourceLocation(tag.getString(KEY_HOME_DIM));
        }
        return data;
    }
}
