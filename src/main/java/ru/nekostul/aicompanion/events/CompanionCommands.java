package ru.nekostul.aicompanion.events;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import ru.nekostul.aicompanion.AiCompanionMod;
import ru.nekostul.aicompanion.entity.CompanionEntity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Mod.EventBusSubscriber(modid = AiCompanionMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CompanionCommands {
    private static final String TELEPORT_NONE_KEY = "entity.aicompanion.companion.teleport.none";
    private static final int NO_REQUEST_COOLDOWN_TICKS = 1200;
    private static final Map<UUID, Long> NO_REQUEST_MESSAGE_TICKS = new ConcurrentHashMap<>();

    private CompanionCommands() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(
                Commands.literal("aicompanion")
                        .then(Commands.literal("tp")
                                .then(Commands.literal("yes")
                                        .executes(context -> handleTeleport(context, true)))
                                .then(Commands.literal("no")
                                        .executes(context -> handleTeleport(context, false))))
                        .then(Commands.literal("msg")
                                .then(Commands.argument("text", StringArgumentType.greedyString())
                                        .executes(CompanionCommands::handleMessage)))
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
}
