package ru.nekostul.aicompanion;

import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

import ru.nekostul.aicompanion.registry.ModEntities;
import ru.nekostul.aicompanion.registry.ModItems;
import ru.nekostul.aicompanion.registry.ModMenus;

@Mod(AiCompanionMod.MOD_ID)
public class AiCompanionMod {
    public static final String MOD_ID = "aicompanion";

    public AiCompanionMod() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModEntities.ENTITY_TYPES.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        ModMenus.MENUS.register(modEventBus);
        modEventBus.addListener(ModEntities::registerAttributes);
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, CompanionConfig.SPEC);
    }
}
