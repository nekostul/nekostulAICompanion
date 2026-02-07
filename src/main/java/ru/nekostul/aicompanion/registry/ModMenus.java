package ru.nekostul.aicompanion.registry;

import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import ru.nekostul.aicompanion.AiCompanionMod;
import ru.nekostul.aicompanion.client.gui.CompanionEquipmentMenu;

public final class ModMenus {
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, AiCompanionMod.MOD_ID);

    public static final RegistryObject<MenuType<CompanionEquipmentMenu>> COMPANION_EQUIPMENT =
            MENUS.register("companion_equipment", () -> IForgeMenuType.create(CompanionEquipmentMenu::new));

    private ModMenus() {
    }
}
