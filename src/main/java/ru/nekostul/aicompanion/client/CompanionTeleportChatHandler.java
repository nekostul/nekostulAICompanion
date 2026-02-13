package ru.nekostul.aicompanion.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;

import ru.nekostul.aicompanion.AiCompanionMod;

import java.lang.reflect.Field;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

@Mod.EventBusSubscriber(modid = AiCompanionMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CompanionTeleportChatHandler {
    private static final Set<String> TELEPORT_REQUEST_KEYS = Set.of(
            "entity.aicompanion.companion.teleport.request",
            "entity.aicompanion.companion.teleport.request.alt",
            "entity.aicompanion.companion.teleport.request.repeat",
            "entity.aicompanion.companion.home.confirm",
            "entity.aicompanion.companion.where.status",
            "entity.aicompanion.companion.home.follow"
    );
    private static final String TELEPORT_IGNORE_PREFIX = "entity.aicompanion.companion.teleport.ignore.";

    private static final Field CHAT_MESSAGES_FIELD =
            ObfuscationReflectionHelper.findField(ChatComponent.class, "f_93760_");

    private CompanionTeleportChatHandler() {
    }

    @SubscribeEvent
    public static void onSystemChat(ClientChatReceivedEvent.System event) {
        if (event.isOverlay()) {
            return;
        }
        Component message = event.getMessage();
        if (containsTeleportRequest(message) || containsTeleportIgnore(message)) {
            event.setCanceled(true);
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft == null || minecraft.gui == null) {
                return;
            }
            ChatComponent chat = minecraft.gui.getChat();
            replaceTeleportMessage(chat, message, containsTeleportIgnore(message));
        }
    }

    private static boolean containsTeleportRequest(Component message) {
        if (message == null) {
            return false;
        }
        if (message.getContents() instanceof TranslatableContents contents
                && TELEPORT_REQUEST_KEYS.contains(contents.getKey())) {
            return true;
        }
        for (Component sibling : message.getSiblings()) {
            if (containsTeleportRequest(sibling)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsTeleportIgnore(Component message) {
        if (message == null) {
            return false;
        }
        if (message.getContents() instanceof TranslatableContents contents
                && contents.getKey().startsWith(TELEPORT_IGNORE_PREFIX)) {
            return true;
        }
        for (Component sibling : message.getSiblings()) {
            if (containsTeleportIgnore(sibling)) {
                return true;
            }
        }
        return false;
    }

    private static void replaceTeleportMessage(ChatComponent chat, Component message, boolean ignore) {
        List<GuiMessage> messages = getChatMessages(chat);
        if (messages != null) {
            boolean removed = false;
            for (Iterator<GuiMessage> iterator = messages.iterator(); iterator.hasNext(); ) {
                GuiMessage guiMessage = iterator.next();
                if (containsTeleportRequest(guiMessage.content())) {
                    iterator.remove();
                    removed = true;
                }
            }
            if (removed) {
                chat.rescaleChat();
            }
        }
        if (!ignore) {
            chat.addMessage(message);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<GuiMessage> getChatMessages(ChatComponent chat) {
        try {
            return (List<GuiMessage>) CHAT_MESSAGES_FIELD.get(chat);
        } catch (IllegalAccessException ignored) {
            return null;
        }
    }
}
