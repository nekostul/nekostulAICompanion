package ru.nekostul.aicompanion.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraft.client.gui.screens.MenuScreens;

import ru.nekostul.aicompanion.AiCompanionMod;
import ru.nekostul.aicompanion.client.gui.CompanionEquipmentScreen;
import ru.nekostul.aicompanion.client.render.CompanionRenderer;
import ru.nekostul.aicompanion.registry.ModEntities;
import ru.nekostul.aicompanion.registry.ModMenus;

@Mod.EventBusSubscriber(modid = AiCompanionMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientModEvents {
    private ClientModEvents() {
    }

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.COMPANION.get(), CompanionRenderer::new);
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> MenuScreens.register(ModMenus.COMPANION_EQUIPMENT.get(),
                CompanionEquipmentScreen::new));
    }
}
