package ru.nekostul.aicompanion.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ImageButton;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;
import java.util.WeakHashMap;

import ru.nekostul.aicompanion.AiCompanionMod;
import ru.nekostul.aicompanion.CompanionConfig;
import ru.nekostul.aicompanion.entity.CompanionEntity;

@Mod.EventBusSubscriber(modid = AiCompanionMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CompanionInventoryButtonHandler {
    private static final ResourceLocation NPC_BUTTON_LOCATION =
            new ResourceLocation(AiCompanionMod.MOD_ID, "textures/gui/buttonnpc.png");
    private static final String OPEN_GUI_COMMAND = "ainpc gui";
    private static final Map<InventoryScreen, ImageButton> NPC_BUTTONS = new WeakHashMap<>();

    private CompanionInventoryButtonHandler() {
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof InventoryScreen screen)) {
            return;
        }
        AbstractContainerScreen<?> containerScreen = (AbstractContainerScreen<?>) screen;
        int leftPos = getLeftPos(containerScreen);
        int buttonX = leftPos + 126;
        int buttonY = screen.height / 2 - 22;
        ImageButton button = new ImageButton(buttonX, buttonY, 20, 18, 0, 0, 19, NPC_BUTTON_LOCATION, 20, 37,
                (btn) -> sendOpenGuiCommand());
        event.addListener(button);
        NPC_BUTTONS.put(screen, button);
        updateButtonVisibility(button);
    }

    @SubscribeEvent
    public static void onScreenRender(ScreenEvent.Render.Pre event) {
        if (!(event.getScreen() instanceof InventoryScreen screen)) {
            return;
        }
        ImageButton button = NPC_BUTTONS.get(screen);
        if (button == null) {
            return;
        }
        AbstractContainerScreen<?> containerScreen = (AbstractContainerScreen<?>) screen;
        int leftPos = getLeftPos(containerScreen);
        int buttonX = leftPos + 126;
        int buttonY = screen.height / 2 - 22;
        button.setPosition(buttonX, buttonY);
        updateButtonVisibility(button);
    }

    @SubscribeEvent
    public static void onScreenClose(ScreenEvent.Closing event) {
        if (event.getScreen() instanceof InventoryScreen screen) {
            NPC_BUTTONS.remove(screen);
        }
    }

    private static int getLeftPos(AbstractContainerScreen<?> screen) {
        try {
            java.lang.reflect.Field field = AbstractContainerScreen.class.getDeclaredField("leftPos");
            field.setAccessible(true);
            return field.getInt(screen);
        } catch (ReflectiveOperationException ignored) {
            int width = Minecraft.getInstance().getWindow().getGuiScaledWidth();
            return (width - 176) / 2;
        }
    }

    private static void sendOpenGuiCommand() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        if (minecraft.player.connection != null) {
            minecraft.player.connection.sendCommand(OPEN_GUI_COMMAND);
        }
    }

    private static void updateButtonVisibility(ImageButton button) {
        boolean enabled = CompanionConfig.isNpcPanelButtonEnabled();
        boolean hasNpc = enabled && hasCompanion();
        button.visible = hasNpc;
        button.active = hasNpc;
    }

    private static boolean hasCompanion() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return false;
        }
        AABB area = minecraft.player.getBoundingBox().inflate(512.0D);
        return !minecraft.level.getEntitiesOfClass(CompanionEntity.class, area).isEmpty();
    }
}
