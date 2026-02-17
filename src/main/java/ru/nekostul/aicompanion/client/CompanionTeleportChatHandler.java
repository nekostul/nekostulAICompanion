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
    private static final String HOME_DEATH_RECOVERY_HP_KEY =
            "entity.aicompanion.companion.home.death.recovery.hp";
    private static final String HOME_DEATH_RECOVERY_HP_REMOVE_KEY =
            "entity.aicompanion.companion.home.death.recovery.hp.remove";
    private static final String TREE_RETRY_OFFER_KEY =
            "entity.aicompanion.companion.tree.retry.offer";
    private static final String TREE_RETRY_REMOVE_KEY =
            "entity.aicompanion.companion.tree.retry.remove";
    private static final String HOME_ASSESS_ROOMS_OFFER_KEY =
            "entity.aicompanion.companion.home.assess.rooms.offer";
    private static final String HOME_ASSESS_ROOMS_REMOVE_KEY =
            "entity.aicompanion.companion.home.assess.rooms.remove";
    private static final String BUILD_POINT_OFFER_KEY =
            "entity.aicompanion.companion.build.point.ask";
    private static final String BUILD_POINT_REMOVE_KEY =
            "entity.aicompanion.companion.build.point.remove";
    private static final String BUILD_RESOURCES_OFFER_KEY =
            "entity.aicompanion.companion.build.resources.offer";
    private static final String BUILD_RESOURCES_REMOVE_KEY =
            "entity.aicompanion.companion.build.resources.remove";
    private static final String INVENTORY_DROP_CONFIRM_OFFER_KEY =
            "entity.aicompanion.companion.inventory.drop.confirm";
    private static final String INVENTORY_DROP_CONFIRM_REMOVE_KEY =
            "entity.aicompanion.companion.inventory.drop.confirm.remove";
    private static final Set<String> TELEPORT_REQUEST_KEYS = Set.of(
            "entity.aicompanion.companion.teleport.request",
            "entity.aicompanion.companion.teleport.request.alt",
            "entity.aicompanion.companion.teleport.request.repeat",
            "entity.aicompanion.companion.boat.request",
            "entity.aicompanion.companion.dimension.request",
            "entity.aicompanion.companion.home.confirm",
            "entity.aicompanion.companion.where.status",
            "entity.aicompanion.companion.home.follow",
            "entity.aicompanion.companion.inventory.drop.tools.notice"
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
        boolean teleportRequest = containsTeleportRequest(message);
        boolean teleportIgnore = containsTeleportIgnore(message);
        boolean homeRecoveryHp = containsHomeRecoveryHp(message);
        boolean homeRecoveryHpRemove = containsHomeRecoveryHpRemove(message);
        boolean treeRetryOffer = containsTreeRetryOffer(message);
        boolean treeRetryRemove = containsTreeRetryRemove(message);
        boolean homeAssessRoomsOffer = containsHomeAssessRoomsOffer(message);
        boolean homeAssessRoomsRemove = containsHomeAssessRoomsRemove(message);
        boolean buildPointOffer = containsBuildPointOffer(message);
        boolean buildPointRemove = containsBuildPointRemove(message);
        boolean buildResourcesOffer = containsBuildResourcesOffer(message);
        boolean buildResourcesRemove = containsBuildResourcesRemove(message);
        boolean inventoryDropConfirmOffer = containsInventoryDropConfirmOffer(message);
        boolean inventoryDropConfirmRemove = containsInventoryDropConfirmRemove(message);
        if (teleportRequest || teleportIgnore
                || homeRecoveryHp || homeRecoveryHpRemove
                || treeRetryOffer || treeRetryRemove
                || homeAssessRoomsOffer || homeAssessRoomsRemove
                || buildPointOffer || buildPointRemove
                || buildResourcesOffer || buildResourcesRemove
                || inventoryDropConfirmOffer || inventoryDropConfirmRemove) {
            event.setCanceled(true);
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft == null || minecraft.gui == null) {
                return;
            }
            ChatComponent chat = minecraft.gui.getChat();
            if (teleportRequest || teleportIgnore) {
                replaceTeleportMessage(chat, message, teleportIgnore);
            } else if (homeRecoveryHp || homeRecoveryHpRemove) {
                replaceHomeRecoveryHpMessage(chat, message, homeRecoveryHpRemove);
            } else if (homeAssessRoomsOffer || homeAssessRoomsRemove) {
                replaceHomeAssessRoomsMessage(chat, message, homeAssessRoomsRemove);
            } else if (buildPointOffer || buildPointRemove) {
                replaceBuildPointMessage(chat, message, buildPointRemove);
            } else if (buildResourcesOffer || buildResourcesRemove) {
                replaceBuildResourcesMessage(chat, message, buildResourcesRemove);
            } else if (inventoryDropConfirmOffer || inventoryDropConfirmRemove) {
                replaceInventoryDropConfirmMessage(chat, message, inventoryDropConfirmRemove);
            } else {
                replaceTreeRetryMessage(chat, message, treeRetryRemove);
            }
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

    private static boolean containsHomeRecoveryHp(Component message) {
        if (message == null) {
            return false;
        }
        if (message.getContents() instanceof TranslatableContents contents
                && HOME_DEATH_RECOVERY_HP_KEY.equals(contents.getKey())) {
            return true;
        }
        for (Component sibling : message.getSiblings()) {
            if (containsHomeRecoveryHp(sibling)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsHomeRecoveryHpRemove(Component message) {
        if (message == null) {
            return false;
        }
        if (message.getContents() instanceof TranslatableContents contents
                && HOME_DEATH_RECOVERY_HP_REMOVE_KEY.equals(contents.getKey())) {
            return true;
        }
        for (Component sibling : message.getSiblings()) {
            if (containsHomeRecoveryHpRemove(sibling)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsTreeRetryOffer(Component message) {
        if (message == null) {
            return false;
        }
        if (message.getContents() instanceof TranslatableContents contents
                && TREE_RETRY_OFFER_KEY.equals(contents.getKey())) {
            return true;
        }
        for (Component sibling : message.getSiblings()) {
            if (containsTreeRetryOffer(sibling)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsTreeRetryRemove(Component message) {
        if (message == null) {
            return false;
        }
        if (message.getContents() instanceof TranslatableContents contents
                && TREE_RETRY_REMOVE_KEY.equals(contents.getKey())) {
            return true;
        }
        for (Component sibling : message.getSiblings()) {
            if (containsTreeRetryRemove(sibling)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsHomeAssessRoomsOffer(Component message) {
        if (message == null) {
            return false;
        }
        if (message.getContents() instanceof TranslatableContents contents
                && HOME_ASSESS_ROOMS_OFFER_KEY.equals(contents.getKey())) {
            return true;
        }
        for (Component sibling : message.getSiblings()) {
            if (containsHomeAssessRoomsOffer(sibling)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsHomeAssessRoomsRemove(Component message) {
        if (message == null) {
            return false;
        }
        if (message.getContents() instanceof TranslatableContents contents
                && HOME_ASSESS_ROOMS_REMOVE_KEY.equals(contents.getKey())) {
            return true;
        }
        for (Component sibling : message.getSiblings()) {
            if (containsHomeAssessRoomsRemove(sibling)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsBuildResourcesOffer(Component message) {
        if (message == null) {
            return false;
        }
        if (message.getContents() instanceof TranslatableContents contents
                && BUILD_RESOURCES_OFFER_KEY.equals(contents.getKey())) {
            return true;
        }
        for (Component sibling : message.getSiblings()) {
            if (containsBuildResourcesOffer(sibling)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsBuildPointOffer(Component message) {
        if (message == null) {
            return false;
        }
        if (message.getContents() instanceof TranslatableContents contents
                && BUILD_POINT_OFFER_KEY.equals(contents.getKey())) {
            return true;
        }
        for (Component sibling : message.getSiblings()) {
            if (containsBuildPointOffer(sibling)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsBuildPointRemove(Component message) {
        if (message == null) {
            return false;
        }
        if (message.getContents() instanceof TranslatableContents contents
                && BUILD_POINT_REMOVE_KEY.equals(contents.getKey())) {
            return true;
        }
        for (Component sibling : message.getSiblings()) {
            if (containsBuildPointRemove(sibling)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsBuildResourcesRemove(Component message) {
        if (message == null) {
            return false;
        }
        if (message.getContents() instanceof TranslatableContents contents
                && BUILD_RESOURCES_REMOVE_KEY.equals(contents.getKey())) {
            return true;
        }
        for (Component sibling : message.getSiblings()) {
            if (containsBuildResourcesRemove(sibling)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsInventoryDropConfirmOffer(Component message) {
        if (message == null) {
            return false;
        }
        if (message.getContents() instanceof TranslatableContents contents
                && INVENTORY_DROP_CONFIRM_OFFER_KEY.equals(contents.getKey())) {
            return true;
        }
        for (Component sibling : message.getSiblings()) {
            if (containsInventoryDropConfirmOffer(sibling)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsInventoryDropConfirmRemove(Component message) {
        if (message == null) {
            return false;
        }
        if (message.getContents() instanceof TranslatableContents contents
                && INVENTORY_DROP_CONFIRM_REMOVE_KEY.equals(contents.getKey())) {
            return true;
        }
        for (Component sibling : message.getSiblings()) {
            if (containsInventoryDropConfirmRemove(sibling)) {
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

    private static void replaceHomeRecoveryHpMessage(ChatComponent chat, Component message, boolean removeOnly) {
        List<GuiMessage> messages = getChatMessages(chat);
        if (messages != null) {
            boolean removed = false;
            for (Iterator<GuiMessage> iterator = messages.iterator(); iterator.hasNext(); ) {
                GuiMessage guiMessage = iterator.next();
                if (containsHomeRecoveryHp(guiMessage.content())) {
                    iterator.remove();
                    removed = true;
                }
            }
            if (removed) {
                chat.rescaleChat();
            }
        }
        if (!removeOnly) {
            chat.addMessage(message);
        }
    }

    private static void replaceTreeRetryMessage(ChatComponent chat, Component message, boolean removeOnly) {
        List<GuiMessage> messages = getChatMessages(chat);
        if (messages != null) {
            boolean removed = false;
            for (Iterator<GuiMessage> iterator = messages.iterator(); iterator.hasNext(); ) {
                GuiMessage guiMessage = iterator.next();
                if (containsTreeRetryOffer(guiMessage.content())) {
                    iterator.remove();
                    removed = true;
                }
            }
            if (removed) {
                chat.rescaleChat();
            }
        }
        if (!removeOnly) {
            chat.addMessage(message);
        }
    }

    private static void replaceHomeAssessRoomsMessage(ChatComponent chat, Component message, boolean removeOnly) {
        List<GuiMessage> messages = getChatMessages(chat);
        if (messages != null) {
            boolean removed = false;
            for (Iterator<GuiMessage> iterator = messages.iterator(); iterator.hasNext(); ) {
                GuiMessage guiMessage = iterator.next();
                if (containsHomeAssessRoomsOffer(guiMessage.content())) {
                    iterator.remove();
                    removed = true;
                }
            }
            if (removed) {
                chat.rescaleChat();
            }
        }
        if (!removeOnly) {
            chat.addMessage(message);
        }
    }

    private static void replaceBuildPointMessage(ChatComponent chat, Component message, boolean removeOnly) {
        List<GuiMessage> messages = getChatMessages(chat);
        if (messages != null) {
            boolean removed = false;
            for (Iterator<GuiMessage> iterator = messages.iterator(); iterator.hasNext(); ) {
                GuiMessage guiMessage = iterator.next();
                if (containsBuildPointOffer(guiMessage.content())) {
                    iterator.remove();
                    removed = true;
                }
            }
            if (removed) {
                chat.rescaleChat();
            }
        }
        if (!removeOnly) {
            chat.addMessage(message);
        }
    }

    private static void replaceBuildResourcesMessage(ChatComponent chat, Component message, boolean removeOnly) {
        List<GuiMessage> messages = getChatMessages(chat);
        if (messages != null) {
            boolean removed = false;
            for (Iterator<GuiMessage> iterator = messages.iterator(); iterator.hasNext(); ) {
                GuiMessage guiMessage = iterator.next();
                if (containsBuildResourcesOffer(guiMessage.content())) {
                    iterator.remove();
                    removed = true;
                }
            }
            if (removed) {
                chat.rescaleChat();
            }
        }
        if (!removeOnly) {
            chat.addMessage(message);
        }
    }

    private static void replaceInventoryDropConfirmMessage(ChatComponent chat, Component message, boolean removeOnly) {
        List<GuiMessage> messages = getChatMessages(chat);
        if (messages != null) {
            boolean removed = false;
            for (Iterator<GuiMessage> iterator = messages.iterator(); iterator.hasNext(); ) {
                GuiMessage guiMessage = iterator.next();
                if (containsInventoryDropConfirmOffer(guiMessage.content())) {
                    iterator.remove();
                    removed = true;
                }
            }
            if (removed) {
                chat.rescaleChat();
            }
        }
        if (!removeOnly) {
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
