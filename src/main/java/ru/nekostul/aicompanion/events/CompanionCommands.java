package ru.nekostul.aicompanion.events;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.NetworkHooks;

import ru.nekostul.aicompanion.AiCompanionMod;
import ru.nekostul.aicompanion.CompanionConfig;
import ru.nekostul.aicompanion.client.gui.CompanionEquipmentMenu;
import ru.nekostul.aicompanion.entity.CompanionEntity;
import ru.nekostul.aicompanion.entity.CompanionSingleNpcManager;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Mod.EventBusSubscriber(modid = AiCompanionMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CompanionCommands {
    private static final String TELEPORT_NONE_KEY = "entity.aicompanion.companion.teleport.none";
    private static final String TREECHOP_ENABLED_KEY = "entity.aicompanion.companion.treechop.enabled";
    private static final String TREECHOP_DISABLED_KEY = "entity.aicompanion.companion.treechop.disabled";
    private static final int NO_REQUEST_COOLDOWN_TICKS = 1200;
    private static final Map<UUID, Long> NO_REQUEST_MESSAGE_TICKS = new ConcurrentHashMap<>();

    private CompanionCommands() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(
                Commands.literal("ainpc")
                        .then(Commands.literal("tp")
                                .then(Commands.literal("yes")
                                        .executes(context -> handleTeleport(context, true)))
                                .then(Commands.literal("no")
                                        .executes(context -> handleTeleport(context, false))))
                        .then(Commands.literal("msg")
                                .then(Commands.argument("text", StringArgumentType.greedyString())
                                        .executes(CompanionCommands::handleMessage)))
                        .then(Commands.literal("gui")
                                .executes(CompanionCommands::handleGui))
                        .then(Commands.literal("treechop")
                                .then(Commands.literal("on")
                                        .executes(context -> handleTreeChop(context, true)))
                                .then(Commands.literal("off")
                                        .executes(context -> handleTreeChop(context, false))))
        );
    }

    private static int handleTeleport(CommandContext<CommandSourceStack> context, boolean accepted)
            throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        CompanionEntity companion = CompanionEntity.getPendingTeleportFor(player);
        if (companion == null) {
            long now = player.level().getGameTime();
            long lastMessageTick = NO_REQUEST_MESSAGE_TICKS.getOrDefault(player.getUUID(), Long.MIN_VALUE);
            if (now - lastMessageTick >= NO_REQUEST_COOLDOWN_TICKS) {
                NO_REQUEST_MESSAGE_TICKS.put(player.getUUID(), now);
                context.getSource().sendFailure(Component.translatable(TELEPORT_NONE_KEY));
            }
            return 0;
        }
        companion.handleTeleportResponse(player, accepted);
        return 1;
    }

    private static int handleMessage(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        String message = StringArgumentType.getString(context, "text");
        boolean handled = CompanionChatEvents.handlePlayerMessage(player, message);
        return handled ? 1 : 0;
    }

    private static int handleGui(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        CompanionEntity companion = CompanionSingleNpcManager.getActive(player);
        if (companion == null) {
            return 0;
        }
        NetworkHooks.openScreen(player, new SimpleMenuProvider(
                (containerId, inventory, p) -> new CompanionEquipmentMenu(containerId, inventory, companion, true),
                companion.getDisplayName()), buffer -> {
            buffer.writeInt(companion.getId());
            buffer.writeBoolean(true);
        });
        return 1;
    }

    private static int handleTreeChop(CommandContext<CommandSourceStack> context, boolean enabled)
            throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        CompanionConfig.setFullTreeChopEnabled(enabled);
        Component message = Component.translatable(enabled ? TREECHOP_ENABLED_KEY : TREECHOP_DISABLED_KEY);
        CompanionEntity companion = CompanionSingleNpcManager.getActive(player);
        if (companion != null) {
            companion.sendReply(player, message);
        } else {
            context.getSource().sendSuccess(() -> message, false);
        }
        return 1;
    }
}
