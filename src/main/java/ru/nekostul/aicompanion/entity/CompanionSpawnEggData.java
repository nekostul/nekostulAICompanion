package ru.nekostul.aicompanion.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

public final class CompanionSpawnEggData extends SavedData {
    private static final String DATA_NAME = "aicompanion_spawn_egg";
    private static final String KEY_EGG_GRANTED = "EggGranted";
    private boolean eggGranted;

    private CompanionSpawnEggData() {
    }

    public static CompanionSpawnEggData get(MinecraftServer server) {
        if (server == null) {
            return null;
        }
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) {
            return null;
        }
        CompanionSpawnEggData data = overworld.getDataStorage().computeIfAbsent(
                CompanionSpawnEggData::load,
                CompanionSpawnEggData::new,
                DATA_NAME
        );
        return data;
    }

    public boolean shouldGrantEgg() {
        return !eggGranted;
    }

    public void markEggGranted() {
        if (eggGranted) {
            return;
        }
        eggGranted = true;
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putBoolean(KEY_EGG_GRANTED, eggGranted);
        return tag;
    }

    private static CompanionSpawnEggData load(CompoundTag tag) {
        CompanionSpawnEggData data = new CompanionSpawnEggData();
        data.eggGranted = tag.contains(KEY_EGG_GRANTED) && tag.getBoolean(KEY_EGG_GRANTED);
        return data;
    }
}
