package ru.nekostul.aicompanion.events;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.NetworkHooks;

import ru.nekostul.aicompanion.AiCompanionMod;
import ru.nekostul.aicompanion.CompanionConfig;
import ru.nekostul.aicompanion.bugreport.BugReportService;
import ru.nekostul.aicompanion.client.gui.CompanionEquipmentMenu;
import ru.nekostul.aicompanion.entity.CompanionEntity;
import ru.nekostul.aicompanion.entity.CompanionSingleNpcManager;
import ru.nekostul.aicompanion.registry.ModEntities;

@Mod.EventBusSubscriber(modid = AiCompanionMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CompanionCommands {
    private static final String TREECHOP_ENABLED_KEY = "entity.aicompanion.companion.treechop.enabled";
    private static final String TREECHOP_DISABLED_KEY = "entity.aicompanion.companion.treechop.disabled";

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
                        .then(Commands.literal("bug")
                                .then(Commands.argument("text", StringArgumentType.greedyString())
                                        .executes(CompanionCommands::handleBugReport)))
                        .then(Commands.literal("sethome")
                                .executes(CompanionCommands::handleSetHome))
                        .then(Commands.literal("home")
                                .then(Commands.literal("yes")
                                        .executes(context -> handleHomeConfirm(context, true)))
                                .then(Commands.literal("no")
                                        .executes(context -> handleHomeConfirm(context, false))))
                        .then(Commands.literal("boat")
                                .then(Commands.literal("yes")
                                        .executes(context -> handleBoatConfirm(context, true)))
                                .then(Commands.literal("no")
                                        .executes(context -> handleBoatConfirm(context, false))))
                        .then(Commands.literal("treechop")
                                .then(Commands.literal("on")
                                        .executes(context -> handleTreeChop(context, true)))
                                .then(Commands.literal("off")
                                        .executes(context -> handleTreeChop(context, false))))
                        .then(Commands.literal("spawn")
                                .requires(source -> source.hasPermission(2))
                                .executes(CompanionCommands::handleSpawn))
        );
    }

    private static int handleTeleport(CommandContext<CommandSourceStack> context, boolean accepted)
            throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        try {
            if (CompanionEntity.handleTeleportResponse(player, accepted)) {
                return 1;
            }
        } catch (RuntimeException ignored) {
            if (accepted && CompanionEntity.forceHandlePendingDimensionTeleport(player)) {
                return 1;
            }
            return 0;
        }
        if (accepted && CompanionEntity.forceHandlePendingDimensionTeleport(player)) {
            return 1;
        }
        return 0;
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
        if (companion == null || !companion.canPlayerControl(player)) {
            return 0;
        }
        NetworkHooks.openScreen(player, new SimpleMenuProvider(
                (containerId, inventory, p) -> new CompanionEquipmentMenu(containerId, inventory, companion, true),
                Component.translatable("container.crafting")), buffer -> {
            buffer.writeInt(companion.getId());
            buffer.writeBoolean(true);
        });
        return 1;
    }

    private static int handleBugReport(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        String message = StringArgumentType.getString(context, "text");
        BugReportService.sendAsync(player, message);
        return 1;
    }

    private static int handleSetHome(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        CompanionEntity companion = CompanionSingleNpcManager.getActiveIncludingDead(player);
        if (companion == null || !companion.canPlayerControl(player)) {
            return 0;
        }
        return companion.handleSetHome(player) ? 1 : 0;
    }

    private static int handleHomeConfirm(CommandContext<CommandSourceStack> context, boolean accepted)
            throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        CompanionEntity companion = CompanionSingleNpcManager.getActive(player);
        if (companion == null || !companion.canPlayerControl(player)) {
            return 0;
        }
        return companion.handleHomeConfirmation(player, accepted) ? 1 : 0;
    }

    private static int handleTreeChop(CommandContext<CommandSourceStack> context, boolean enabled)
            throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        CompanionEntity companion = CompanionSingleNpcManager.getActive(player);
        if (companion != null && !companion.canPlayerControl(player)) {
            return 0;
        }
        CompanionConfig.setFullTreeChopEnabled(enabled);
        Component message = Component.translatable(enabled ? TREECHOP_ENABLED_KEY : TREECHOP_DISABLED_KEY);
        if (companion != null) {
            companion.sendReply(player, message);
        } else {
            context.getSource().sendSuccess(() -> message, false);
        }
        return 1;
    }

    private static int handleBoatConfirm(CommandContext<CommandSourceStack> context, boolean accepted)
            throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        CompanionEntity companion = CompanionSingleNpcManager.getActive(player);
        if (companion == null || !companion.canPlayerControl(player)) {
            return 0;
        }
        return companion.handleBoatRideConfirmation(player, accepted) ? 1 : 0;
    }

    private static int handleSpawn(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        CompanionEntity companion = spawnCompanionNear(player);
        return companion != null ? 1 : 0;
    }

    private static CompanionEntity spawnCompanionNear(ServerPlayer player) {
        if (player == null) {
            return null;
        }
        BlockPos base = player.blockPosition();
        Direction facing = player.getDirection();
        BlockPos[] candidates = new BlockPos[] {
                base.relative(facing.getOpposite()),
                base.relative(facing.getClockWise()),
                base.relative(facing.getCounterClockWise()),
                base.above(),
                base
        };
        for (BlockPos candidate : candidates) {
            CompanionEntity spawned = ModEntities.COMPANION.get().spawn(
                    player.serverLevel(),
                    (ItemStack) null,
                    player,
                    candidate,
                    MobSpawnType.COMMAND,
                    true,
                    false
            );
            if (spawned != null) {
                spawned.setYRot(player.getYRot());
                spawned.setXRot(player.getXRot());
                return spawned;
            }
        }
        return null;
    }
}
