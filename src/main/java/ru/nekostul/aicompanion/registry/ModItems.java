package ru.nekostul.aicompanion.registry;

import net.minecraft.world.item.Item;
import net.minecraftforge.common.ForgeSpawnEggItem;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import ru.nekostul.aicompanion.AiCompanionMod;

public final class ModItems {
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, AiCompanionMod.MOD_ID);

    public static final RegistryObject<Item> COMPANION_SPAWN_EGG = ITEMS.register(
            "companion_spawn_egg",
            () -> new ForgeSpawnEggItem(
                    ModEntities.COMPANION,
                    0x8C8C8C,
                    0x2F2F2F,
                    new Item.Properties()
            )
    );

    private ModItems() {
    }
}
