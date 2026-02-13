package ru.nekostul.aicompanion.entity;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import ru.nekostul.aicompanion.entity.ai.FollowNearestPlayerGoal;
import ru.nekostul.aicompanion.entity.ai.HoldPositionGoal;
import ru.nekostul.aicompanion.entity.command.CompanionCommandParser;
import ru.nekostul.aicompanion.entity.food.CompanionFoodHuntController;
import ru.nekostul.aicompanion.entity.food.CompanionHungerSystem;
import ru.nekostul.aicompanion.entity.gratitude.CompanionGratitudeResponder;
import ru.nekostul.aicompanion.entity.gratitude.CompanionPickupGratitude;
import ru.nekostul.aicompanion.entity.inventory.CompanionDeliveryController;
import ru.nekostul.aicompanion.entity.inventory.CompanionDropTracker;
import ru.nekostul.aicompanion.entity.inventory.CompanionEquipment;
import ru.nekostul.aicompanion.entity.inventory.CompanionInventory;
import ru.nekostul.aicompanion.entity.inventory.CompanionInventoryExchange;
import ru.nekostul.aicompanion.entity.mining.CompanionGatheringController;
import ru.nekostul.aicompanion.entity.tool.CompanionToolHandler;
import ru.nekostul.aicompanion.entity.tool.CompanionToolSlot;
import ru.nekostul.aicompanion.entity.tree.CompanionTreeHarvestController;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CompanionEntity extends PathfinderMob {
    public enum CompanionMode {
        STOPPED,
        FOLLOW,
        AUTONOMOUS
    }

    private enum ChatGroup {
        FOLLOW,
        AUTONOMOUS,
        STOPPED,
        VILLAGE,
        HOSTILE,
        MINING,
        CRAFT
    }

    private static final String DISPLAY_NAME = "nekostulAI";
    private static final String MODE_NBT = "CompanionMode";
    private static final String GREETED_NBT = "CompanionGreeted";
    private static final String INVENTORY_NBT = "CompanionInventory";
    private static final String CHEST_NBT = "CompanionChest";
    private static final String HUNGER_NBT = "CompanionHunger";
    private static final String CHAT_COOLDOWNS_NBT = "CompanionChatCooldowns";
    private static final String TELEPORT_CYCLE_NBT = "CompanionTeleportCycleTick";
    private static final String TELEPORT_ORIGINAL_NBT = "CompanionTeleportOriginalTick";
    private static final String TELEPORT_PENDING_PLAYER_NBT = "CompanionTeleportPendingPlayer";
    private static final String TELEPORT_PENDING_UNTIL_NBT = "CompanionTeleportPendingUntil";
    private static final String TELEPORT_REMINDER_PLAYER_NBT = "CompanionTeleportReminderPlayer";
    private static final String TELEPORT_REMINDER_TICK_NBT = "CompanionTeleportReminderTick";
    private static final String TOOL_SLOTS_NBT = "CompanionToolSlots";
    private static final String OWNER_NBT = "CompanionOwner";
    private static final String PARTY_NBT = "CompanionParty";
    private static final String TOOL_SLOT_PICKAXE_NBT = "Pickaxe";
    private static final String TOOL_SLOT_AXE_NBT = "Axe";
    private static final String TOOL_SLOT_SHOVEL_NBT = "Shovel";
    private static final String TOOL_SLOT_SWORD_NBT = "Sword";
    private static final EquipmentSlot[] ARMOR_SLOTS = new EquipmentSlot[]{
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };
    private static final String GREET_KEY = "entity.aicompanion.companion.spawn.greeting";
    private static final String GREET_ABOUT_KEY = "entity.aicompanion.companion.spawn.about";
    private static final String GREET_COMMANDS_KEY = "entity.aicompanion.companion.spawn.commands";
    private static final String COMMANDS_HEADER_KEY = "entity.aicompanion.companion.commands.header";
    private static final String COMMANDS_STOP_HINT_KEY = "entity.aicompanion.companion.commands.stop_hint";
    private static final String COMMAND_STOP_KEY = "entity.aicompanion.companion.command.stop";
    private static final String COMMAND_FOLLOW_KEY = "entity.aicompanion.companion.command.follow";
    private static final int REACTION_COOLDOWN_TICKS = 60;
    private static final double REACTION_RANGE = 24.0D;
    private static final double FOLLOW_SEARCH_DISTANCE = 48.0D;
    private static final double TELEPORT_REQUEST_DISTANCE = 48.0D;
    private static final double TELEPORT_REQUEST_DISTANCE_SQR = TELEPORT_REQUEST_DISTANCE * TELEPORT_REQUEST_DISTANCE;
    private static final double TELEPORT_SEARCH_DISTANCE = 128.0D;
    private static final double TELEPORT_BEHIND_DISTANCE = 3.0D;
    private static final double TELEPORT_SIDE_DISTANCE = 2.0D;
    private static final double TELEPORT_FOV_DOT_THRESHOLD = 0.2D;
    private static final int TELEPORT_Y_SEARCH_UP = 2;
    private static final int TELEPORT_Y_SEARCH_DOWN = 6;
    private static final int TELEPORT_NEARBY_RADIUS = 5;
    private static final String TELEPORT_REQUEST_KEY = "entity.aicompanion.companion.teleport.request";
    private static final String TELEPORT_REQUEST_ALT_KEY = "entity.aicompanion.companion.teleport.request.alt";
    private static final int TELEPORT_MESSAGE_COOLDOWN_TICKS = 6000;
    private static final int TELEPORT_ORIGINAL_COOLDOWN_TICKS = 12000;
    private static final int TELEPORT_IGNORE_GRACE_TICKS = TELEPORT_MESSAGE_COOLDOWN_TICKS;
    private static final int INVENTORY_SIZE = 27;
    private static final int ITEM_PICKUP_RADIUS = 3;
    private static final int ITEM_PICKUP_COOLDOWN_TICKS = 10;

    private static final EntityDataAccessor<ItemStack> TOOL_PICKAXE =
            SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.ITEM_STACK);
    private static final EntityDataAccessor<ItemStack> TOOL_AXE =
            SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.ITEM_STACK);
    private static final EntityDataAccessor<ItemStack> TOOL_SHOVEL =
            SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.ITEM_STACK);
    private static final EntityDataAccessor<ItemStack> TOOL_SWORD =
            SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.ITEM_STACK);
    private static final EntityDataAccessor<Boolean> HUNGER_FULL =
            SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.BOOLEAN);

    private static final Map<UUID, CompanionEntity> PENDING_TELEPORTS = new ConcurrentHashMap<>();
    private static final Map<UUID, PendingTeleportRequest> PENDING_TELEPORT_REQUESTS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> TELEPORT_IGNORED_TICKS = new ConcurrentHashMap<>();

    private static final class PendingTeleportRequest {
        private final UUID companionId;
        private final ResourceKey<Level> levelKey;
        private ChunkPos chunkPos;
        private final long untilTick;
        private final String messageKey;
        private int lastSeconds;

        private PendingTeleportRequest(UUID companionId, ResourceKey<Level> levelKey, ChunkPos chunkPos,
                                       long untilTick, String messageKey, int lastSeconds) {
            this.companionId = companionId;
            this.levelKey = levelKey;
            this.chunkPos = chunkPos;
            this.untilTick = untilTick;
            this.messageKey = messageKey;
            this.lastSeconds = lastSeconds;
        }
    }

    private static String[] range(String prefix, int from, int to) {
        return java.util.stream.IntStream.rangeClosed(from, to)
                .mapToObj(i -> prefix + i)
                .toArray(String[]::new);
    }

    private static final String[] HIT_KEYS =
            range("entity.aicompanion.companion.hit.", 1, 40);

    private static final String[] DEATH_LAVA_KEYS =
            range("entity.aicompanion.companion.death.lava.", 1, 40);

    private static final String[] DEATH_FALL_KEYS =
            range("entity.aicompanion.companion.death.fall.", 1, 40);

    private static final String[] DEATH_GENERIC_KEYS =
            range("entity.aicompanion.companion.death.generic.", 1, 40);

    private CompanionMode mode = CompanionMode.AUTONOMOUS;
    private boolean hasGreeted;
    private final CompanionInventory inventory;
    private final CompanionEquipment equipment;
    private final CompanionInventoryExchange inventoryExchange;
    private final CompanionToolHandler toolHandler;
    private final CompanionGatheringController gatheringController;
    private final CompanionTreeHarvestController treeHarvestController;
    private final CompanionDeliveryController deliveryController;
    private final CompanionBucketHandler bucketHandler;
    private final CompanionChestManager chestManager;
    private final CompanionCommandParser commandParser;
    private final CompanionHelpSystem helpSystem;
    private final CompanionGratitudeResponder gratitudeResponder;
    private final CompanionPickupGratitude pickupGratitude;
    private final CompanionHungerSystem hungerSystem;
    private final CompanionFoodHuntController foodHuntController;
    private final CompanionCombatController combatController;
    private final CompanionTaskCoordinator taskCoordinator;
    private final CompanionTorchHandler torchHandler;
    private final EnumMap<ChatGroup, Long> chatGroupCooldowns = new EnumMap<>(ChatGroup.class);
    private long lastReactionTick = -1000L;
    private long nextItemPickupTick = -1L;
    private long lastTeleportCycleTick = -10000L;
    private long lastTeleportOriginalTick = -10000L;
    private long pendingTeleportUntilTick = -1L;
    private UUID pendingTeleportPlayerId;
    private long pendingTeleportReminderTick = -1L;
    private UUID pendingTeleportReminderPlayerId;
    private UUID ownerId;
    private final Set<UUID> partyMembers = new HashSet<>();

    public CompanionEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        this.setCustomName(Component.literal(DISPLAY_NAME));
        this.setCustomNameVisible(true);
        this.inventory = new CompanionInventory(this, INVENTORY_SIZE);
        this.equipment = new CompanionEquipment(this, inventory);
        this.inventoryExchange = new CompanionInventoryExchange(this, inventory);
        this.toolHandler = new CompanionToolHandler(this, inventory, equipment);
        this.gatheringController = new CompanionGatheringController(this, inventory, equipment, toolHandler);
        this.treeHarvestController = new CompanionTreeHarvestController(this, inventory, equipment, toolHandler);
        this.deliveryController = new CompanionDeliveryController(this, inventory);
        this.bucketHandler = new CompanionBucketHandler(this, inventory);
        this.chestManager = new CompanionChestManager(this, inventory);
        this.commandParser = new CompanionCommandParser();
        this.helpSystem = new CompanionHelpSystem();
        this.gratitudeResponder = new CompanionGratitudeResponder(this);
        this.pickupGratitude = new CompanionPickupGratitude(this);
        this.hungerSystem = new CompanionHungerSystem(this, inventory);
        this.foodHuntController = new CompanionFoodHuntController(this, inventory, equipment, hungerSystem);
        this.combatController = new CompanionCombatController(this, equipment);
        this.torchHandler = new CompanionTorchHandler(this, inventory);
        this.taskCoordinator = new CompanionTaskCoordinator(this, inventory, equipment, gatheringController,
                treeHarvestController, deliveryController, bucketHandler, chestManager, commandParser, helpSystem,
                inventoryExchange, torchHandler);
        if (this.getNavigation() instanceof GroundPathNavigation navigation) {
            navigation.setCanOpenDoors(true);
            navigation.setCanPassDoors(true);
        }
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(TOOL_PICKAXE, ItemStack.EMPTY);
        this.entityData.define(TOOL_AXE, ItemStack.EMPTY);
        this.entityData.define(TOOL_SHOVEL, ItemStack.EMPTY);
        this.entityData.define(TOOL_SWORD, ItemStack.EMPTY);
        this.entityData.define(HUNGER_FULL, true);
    }

    @Override
    public void onAddedToWorld() {
        super.onAddedToWorld();
        if (!this.level().isClientSide) {
            ensureOwnerFromNearby();
        }
        CompanionSingleNpcManager.register(this);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new HoldPositionGoal(this, this::isStopMode));
        this.goalSelector.addGoal(2, new FollowNearestPlayerGoal(this, 2.4D, (float) FOLLOW_SEARCH_DISTANCE, 3.0F,
                this::isFollowModeActive));
        this.goalSelector.addGoal(3, new OpenDoorGoal(this, true));
        this.goalSelector.addGoal(4, new WaterAvoidingRandomStrollGoal(this, 0.8D) {
            @Override
            public boolean canUse() {
                return !CompanionEntity.this.isFollowModeActive()
                        && !CompanionEntity.this.taskCoordinator.isBusy()
                        && super.canUse();
            }

            @Override
            public boolean canContinueToUse() {
                return !CompanionEntity.this.isFollowModeActive()
                        && !CompanionEntity.this.taskCoordinator.isBusy()
                        && super.canContinueToUse();
            }
        });
        this.goalSelector.addGoal(5, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(6, new RandomLookAroundGoal(this));
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0D)
                .add(Attributes.ATTACK_DAMAGE, 3.0D)
                .add(Attributes.MOVEMENT_SPEED, 2.3D)
                .add(Attributes.FOLLOW_RANGE, 32.0D);
    }

    public CompanionMode getMode() {
        return this.mode;
    }

    public boolean setMode(CompanionMode mode) {
        if (mode == null || this.mode == mode) {
            return false;
        }
        if (treeHarvestController.isTreeChopInProgress()) {
            return false;
        }
        this.mode = mode;
        if (mode == CompanionMode.STOPPED) {
            this.getNavigation().stop();
        }
        return true;
    }

    public void sendReply(Player player, Component message) {
        sendDirectMessage(player, message);
    }

    public boolean canPlayerControl(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        if (ownerId == null) {
            ownerId = player.getUUID();
            return true;
        }
        if (ownerId.equals(player.getUUID())) {
            return true;
        }
        return partyMembers.contains(player.getUUID());
    }

    public boolean canManageParty(ServerPlayer player) {
        return ensureOwner(player);
    }

    public boolean addPartyMember(ServerPlayer owner, ServerPlayer member) {
        if (!canManageParty(owner) || member == null) {
            return false;
        }
        if (ownerId != null && ownerId.equals(member.getUUID())) {
            return false;
        }
        return partyMembers.add(member.getUUID());
    }

    public boolean removePartyMember(ServerPlayer owner, ServerPlayer member) {
        if (!canManageParty(owner) || member == null) {
            return false;
        }
        return partyMembers.remove(member.getUUID());
    }

    public boolean handlePlayerCommand(ServerPlayer player, String message) {
        if (!canPlayerControl(player)) {
            return false;
        }
        if (treeHarvestController.isTreeChopInProgress()) {
            return true;
        }
        return this.taskCoordinator.handlePlayerMessage(player, message);
    }

    public boolean handleThanks(ServerPlayer player, String message) {
        return this.gratitudeResponder.handle(player, message, this.level().getGameTime());
    }

    private boolean ensureOwner(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        if (ownerId == null) {
            ownerId = player.getUUID();
            return true;
        }
        return ownerId.equals(player.getUUID());
    }

    private void ensureOwnerFromNearby() {
        if (ownerId != null) {
            return;
        }
        Player nearest = this.level().getNearestPlayer(this, 8.0D);
        if (nearest instanceof ServerPlayer serverPlayer && !serverPlayer.isSpectator()) {
            ownerId = serverPlayer.getUUID();
        }
    }

    public void onInventoryUpdated() {
        this.taskCoordinator.onInventoryUpdated();
    }

    public ItemStack getToolSlot(CompanionToolSlot slot) {
        if (slot == null) {
            return ItemStack.EMPTY;
        }
        return this.entityData.get(toolAccessor(slot));
    }

    public void setToolSlot(CompanionToolSlot slot, ItemStack stack) {
        if (slot == null) {
            return;
        }
        ItemStack toStore = stack == null ? ItemStack.EMPTY : stack.copy();
        this.entityData.set(toolAccessor(slot), toStore);
    }

    public ItemStack takeToolSlot(CompanionToolSlot slot) {
        ItemStack stored = getToolSlot(slot);
        if (!stored.isEmpty()) {
            setToolSlot(slot, ItemStack.EMPTY);
        }
        return stored;
    }

    private EntityDataAccessor<ItemStack> toolAccessor(CompanionToolSlot slot) {
        return switch (slot) {
            case PICKAXE -> TOOL_PICKAXE;
            case AXE -> TOOL_AXE;
            case SHOVEL -> TOOL_SHOVEL;
            case SWORD -> TOOL_SWORD;
        };
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putString(MODE_NBT, this.mode.name());
        tag.putBoolean(GREETED_NBT, this.hasGreeted);
        CompoundTag inventoryTag = new CompoundTag();
        this.inventory.saveToTag(inventoryTag);
        tag.put(INVENTORY_NBT, inventoryTag);
        CompoundTag hungerTag = new CompoundTag();
        this.hungerSystem.saveToTag(hungerTag);
        tag.put(HUNGER_NBT, hungerTag);
        CompoundTag chestTag = new CompoundTag();
        this.chestManager.saveToTag(chestTag);
        if (!chestTag.isEmpty()) {
            tag.put(CHEST_NBT, chestTag);
        }
        CompoundTag toolSlotsTag = new CompoundTag();
        saveToolSlot(toolSlotsTag, TOOL_SLOT_PICKAXE_NBT, getToolSlot(CompanionToolSlot.PICKAXE));
        saveToolSlot(toolSlotsTag, TOOL_SLOT_AXE_NBT, getToolSlot(CompanionToolSlot.AXE));
        saveToolSlot(toolSlotsTag, TOOL_SLOT_SHOVEL_NBT, getToolSlot(CompanionToolSlot.SHOVEL));
        saveToolSlot(toolSlotsTag, TOOL_SLOT_SWORD_NBT, getToolSlot(CompanionToolSlot.SWORD));
        if (!toolSlotsTag.isEmpty()) {
            tag.put(TOOL_SLOTS_NBT, toolSlotsTag);
        }
        CompoundTag chatCooldownsTag = new CompoundTag();
        for (ChatGroup group : ChatGroup.values()) {
            Long lastTick = this.chatGroupCooldowns.get(group);
            if (lastTick != null) {
                chatCooldownsTag.putLong(group.name(), lastTick);
            }
        }
        tag.put(CHAT_COOLDOWNS_NBT, chatCooldownsTag);
        tag.putLong(TELEPORT_CYCLE_NBT, this.lastTeleportCycleTick);
        tag.putLong(TELEPORT_ORIGINAL_NBT, this.lastTeleportOriginalTick);
        if (this.pendingTeleportPlayerId != null) {
            tag.putUUID(TELEPORT_PENDING_PLAYER_NBT, this.pendingTeleportPlayerId);
            tag.putLong(TELEPORT_PENDING_UNTIL_NBT, this.pendingTeleportUntilTick);
        }
        if (this.pendingTeleportReminderPlayerId != null) {
            tag.putUUID(TELEPORT_REMINDER_PLAYER_NBT, this.pendingTeleportReminderPlayerId);
            tag.putLong(TELEPORT_REMINDER_TICK_NBT, this.pendingTeleportReminderTick);
        }
        if (this.ownerId != null) {
            tag.putUUID(OWNER_NBT, this.ownerId);
        }
        if (!this.partyMembers.isEmpty()) {
            ListTag partyTag = new ListTag();
            for (UUID memberId : this.partyMembers) {
                partyTag.add(StringTag.valueOf(memberId.toString()));
            }
            tag.put(PARTY_NBT, partyTag);
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains(MODE_NBT)) {
            try {
                this.mode = CompanionMode.valueOf(tag.getString(MODE_NBT));
            } catch (IllegalArgumentException ignored) {
                this.mode = CompanionMode.AUTONOMOUS;
            }
        }
        this.hasGreeted = tag.getBoolean(GREETED_NBT);
        if (tag.contains(INVENTORY_NBT)) {
            CompoundTag inventoryTag = tag.getCompound(INVENTORY_NBT);
            this.inventory.loadFromTag(inventoryTag);
        }
        if (tag.contains(HUNGER_NBT)) {
            CompoundTag hungerTag = tag.getCompound(HUNGER_NBT);
            this.hungerSystem.loadFromTag(hungerTag);
        }
        if (!this.level().isClientSide) {
            syncHungerFullFlag();
        }
        if (tag.contains(CHEST_NBT)) {
            CompoundTag chestTag = tag.getCompound(CHEST_NBT);
            this.chestManager.loadFromTag(chestTag);
        }
        if (tag.contains(TOOL_SLOTS_NBT)) {
            CompoundTag toolSlotsTag = tag.getCompound(TOOL_SLOTS_NBT);
            setToolSlot(CompanionToolSlot.PICKAXE, loadToolSlot(toolSlotsTag, TOOL_SLOT_PICKAXE_NBT));
            setToolSlot(CompanionToolSlot.AXE, loadToolSlot(toolSlotsTag, TOOL_SLOT_AXE_NBT));
            setToolSlot(CompanionToolSlot.SHOVEL, loadToolSlot(toolSlotsTag, TOOL_SLOT_SHOVEL_NBT));
            setToolSlot(CompanionToolSlot.SWORD, loadToolSlot(toolSlotsTag, TOOL_SLOT_SWORD_NBT));
        }
        if (tag.contains(CHAT_COOLDOWNS_NBT)) {
            CompoundTag chatCooldownsTag = tag.getCompound(CHAT_COOLDOWNS_NBT);
            for (ChatGroup group : ChatGroup.values()) {
                if (chatCooldownsTag.contains(group.name())) {
                    this.chatGroupCooldowns.put(group, chatCooldownsTag.getLong(group.name()));
                }
            }
        }
        if (tag.contains(TELEPORT_CYCLE_NBT)) {
            this.lastTeleportCycleTick = tag.getLong(TELEPORT_CYCLE_NBT);
        }
        if (tag.contains(TELEPORT_ORIGINAL_NBT)) {
            this.lastTeleportOriginalTick = tag.getLong(TELEPORT_ORIGINAL_NBT);
        }
        if (tag.hasUUID(TELEPORT_PENDING_PLAYER_NBT) || tag.hasUUID(TELEPORT_REMINDER_PLAYER_NBT)) {
            clearTeleportRequestState();
            clearTeleportReminder();
        }
        this.ownerId = tag.hasUUID(OWNER_NBT) ? tag.getUUID(OWNER_NBT) : null;
        this.partyMembers.clear();
        ListTag partyTag = tag.getList(PARTY_NBT, Tag.TAG_STRING);
        for (int i = 0; i < partyTag.size(); i++) {
            String rawId = partyTag.getString(i);
            try {
                this.partyMembers.add(UUID.fromString(rawId));
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (!this.level().isClientSide) {
            restoreToolSlotsFromHand();
        }
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (!this.level().isClientSide) {
            long gameTime = this.level().getGameTime();
            Player nearest = this.level().getNearestPlayer(this, FOLLOW_SEARCH_DISTANCE);
            CompanionSingleNpcManager.updateState(this, this.taskCoordinator.isBusy(),
                    this.lastTeleportCycleTick, this.lastTeleportOriginalTick);
            tickGreeting();
            tickChestStatus();
            tickItemPickup();
            tickAutonomousBehavior();
            this.hungerSystem.tick(nearest, gameTime);
            syncHungerFullFlag();
            tickAmbientChat();
            tickTeleportRequest();
        }
    }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!stack.isEmpty() && stack.isEdible() && !isNegativeFood(stack)) {
            if (this.level().isClientSide) {
                return isHungerFullSynced() ? InteractionResult.PASS : InteractionResult.SUCCESS;
            }
            long gameTime = this.level().getGameTime();
            if (!hungerSystem.feedFromPlayer(stack, gameTime)) {
                syncHungerFullFlag();
                return InteractionResult.PASS;
            }
            if (!player.getAbilities().instabuild) {
                stack.shrink(1);
            }
            syncHungerFullFlag();
            return InteractionResult.CONSUME;
        }
        return super.mobInteract(player, hand);
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        int[] armorBefore = snapshotArmorDamage();
        boolean result = super.hurt(source, amount);
        if (result && !this.level().isClientSide) {
            ensureArmorDurability(source, amount, armorBefore);
            if (this.isAlive()
                    && source.getEntity() instanceof Player
                    && !isIgnoredHitReaction(source)) {
                long gameTime = this.level().getGameTime();
                if (gameTime - this.lastReactionTick >= REACTION_COOLDOWN_TICKS) {
                    this.lastReactionTick = gameTime;
                    sendReaction(Component.translatable(pickRandomKey(HIT_KEYS)));
                }
            }
        }
        return result;
    }

    @Override
    public void die(DamageSource source) {
        if (!this.level().isClientSide) {
            sendGlobalReaction(Component.translatable(pickDeathKey(source)));
        }
        clearTeleportRequest();
        clearTeleportReminder();
        super.die(source);
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    @Override
    public boolean isCustomNameVisible() {
        return true;
    }

    @Override
    public void remove(RemovalReason reason) {
        if (!this.isAlive()) {
            clearTeleportRequest();
            clearTeleportReminder();
        }
        CompanionSingleNpcManager.unregister(this);
        super.remove(reason);
    }

    private void sendReaction(Component message) {
        AABB range = this.getBoundingBox().inflate(REACTION_RANGE);
        Component fullMessage = Component.literal(DISPLAY_NAME + ": ").append(message);
        for (Player player : this.level().getEntitiesOfClass(Player.class, range)) {
            player.sendSystemMessage(fullMessage);
        }
    }

    private void sendGlobalReaction(Component message) {
        Component fullMessage = Component.literal(DISPLAY_NAME + ": ").append(message);
        if (this.level() instanceof ServerLevel serverLevel) {
            for (ServerPlayer player : serverLevel.getServer().getPlayerList().getPlayers()) {
                player.sendSystemMessage(fullMessage);
            }
            return;
        }
        for (Player player : this.level().players()) {
            player.sendSystemMessage(fullMessage);
        }
    }

    private void sendDirectMessage(Player player, Component message) {
        Component fullMessage = Component.literal(DISPLAY_NAME + ": ").append(message);
        player.sendSystemMessage(fullMessage);
    }

    private void tickItemPickup() {
        long gameTime = this.level().getGameTime();
        if (this.nextItemPickupTick >= 0L && gameTime < this.nextItemPickupTick) {
            return;
        }
        this.nextItemPickupTick = gameTime + ITEM_PICKUP_COOLDOWN_TICKS;
        if (inventory.isFull()) {
            return;
        }
        AABB range = this.getBoundingBox().inflate(ITEM_PICKUP_RADIUS);
        for (net.minecraft.world.entity.item.ItemEntity itemEntity
                : this.level().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, range)) {
            if (!itemEntity.isAlive()) {
                continue;
            }
            if (CompanionDropTracker.isDroppedBy(itemEntity, this.getUUID())) {
                continue;
            }
            if (CompanionDropTracker.isMobDrop(itemEntity)
                    && !CompanionDropTracker.isMobDropFrom(itemEntity, this.getUUID())) {
                continue;
            }
            ItemStack stack = itemEntity.getItem();
            if (stack.isEmpty()) {
                continue;
            }
            ItemStack equippedStack = tryAutoEquipDroppedTool(itemEntity, stack);
            if (!equippedStack.isEmpty()) {
                inventoryExchange.recordPickup(itemEntity, equippedStack);
                pickupGratitude.onPickup(itemEntity, equippedStack, gameTime);
            }
            if (stack.isEmpty()) {
                itemEntity.discard();
                continue;
            }
            int before = stack.getCount();
            ItemStack pickedCopy = stack.copy();
            inventory.add(stack);
            int pickedCount = before - stack.getCount();
            if (pickedCount > 0) {
                pickedCopy.setCount(pickedCount);
                inventoryExchange.recordPickup(itemEntity, pickedCopy);
                pickupGratitude.onPickup(itemEntity, pickedCopy, gameTime);
            }
            if (stack.isEmpty()) {
                itemEntity.discard();
            } else {
                itemEntity.setItem(stack);
            }
            if (inventory.isFull()) {
                break;
            }
        }
    }

    public void sendCommandList(Player player) {
        Component follow = buildCommandButton(COMMAND_FOLLOW_KEY, "СЛЕДОВАНИЕ");
        Component message = Component.translatable(COMMANDS_HEADER_KEY)
                .append(Component.literal(" "))
                .append(Component.literal("[")).append(follow).append(Component.literal("]"));
        sendDirectMessage(player, message);
        sendDirectMessage(player, Component.translatable(COMMANDS_STOP_HINT_KEY,
                Component.translatable(COMMAND_STOP_KEY)));
    }

    private Component buildCommandButton(String translationKey, String commandText) {
        return Component.translatable(translationKey)
                .withStyle(style -> style
                        .withColor(ChatFormatting.AQUA)
                        .withBold(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                                "/ainpc msg " + commandText)));
    }

    private void tickGreeting() {
        if (this.hasGreeted) {
            return;
        }
        Player nearest = this.level().getNearestPlayer(this, 24.0D);
        if (nearest == null || nearest.isSpectator()) {
            return;
        }
        sendDirectMessage(nearest, Component.translatable(GREET_KEY));
        sendDirectMessage(nearest, Component.translatable(GREET_ABOUT_KEY));
        sendDirectMessage(nearest, Component.translatable(GREET_COMMANDS_KEY));
        this.hasGreeted = true;
    }

    private void tickChestStatus() {
        Player nearest = this.level().getNearestPlayer(this, FOLLOW_SEARCH_DISTANCE);
        this.chestManager.tick(nearest);
    }

    private void tickAutonomousBehavior() {
        long gameTime = this.level().getGameTime();
        Player nearest = this.level().getNearestPlayer(this, FOLLOW_SEARCH_DISTANCE);
        this.toolHandler.resetToolRequest();
        if (treeHarvestController.isTreeChopInProgress()) {
            this.taskCoordinator.tick(this.mode, gameTime);
            if (!this.toolHandler.wasToolRequested()) {
                this.equipment.equipIdleHand();
            }
            return;
        }
        if (combatController.tick(nearest, gameTime)) {
            return;
        }
        if (foodHuntController.tick(nearest, gameTime, taskCoordinator.isBusy())) {
            return;
        }
        this.taskCoordinator.tick(this.mode, gameTime);
        if (!this.toolHandler.wasToolRequested()) {
            this.equipment.equipIdleHand();
        }
    }

    private ItemStack tryAutoEquipDroppedTool(net.minecraft.world.entity.item.ItemEntity itemEntity, ItemStack stack) {
        if (itemEntity == null || stack == null || stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        if (CompanionDropTracker.getPlayerDropper(itemEntity) == null) {
            return ItemStack.EMPTY;
        }
        CompanionToolSlot slot = CompanionToolSlot.fromStack(stack);
        if (slot == null) {
            return ItemStack.EMPTY;
        }
        if (!getToolSlot(slot).isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack toStore = stack.copy();
        toStore.setCount(1);
        setToolSlot(slot, toStore);
        stack.shrink(1);
        return toStore;
    }

    private boolean isNegativeFood(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        return stack.is(Items.ROTTEN_FLESH)
                || stack.is(Items.SPIDER_EYE)
                || stack.is(Items.POISONOUS_POTATO)
                || stack.is(Items.PUFFERFISH);
    }

    private void tickAmbientChat() {
        return;
    }

    private boolean isHungerFullSynced() {
        return this.level().isClientSide
                ? this.entityData.get(HUNGER_FULL)
                : hungerSystem.isHungerFull();
    }

    private void syncHungerFullFlag() {
        if (this.level().isClientSide) {
            return;
        }
        this.entityData.set(HUNGER_FULL, hungerSystem.isHungerFull());
    }

    private boolean isFollowModeActive() {
        if (treeHarvestController.isTreeChopInProgress()) {
            return false;
        }
        if (this.mode == CompanionMode.FOLLOW) {
            return !this.taskCoordinator.isBusy();
        }
        if (this.mode == CompanionMode.AUTONOMOUS) {
            return !this.taskCoordinator.isBusy();
        }
        return false;
    }

    private boolean isStopMode() {
        return this.mode == CompanionMode.STOPPED;
    }

    private void tickTeleportRequest() {
        if (pendingTeleportPlayerId != null || pendingTeleportReminderPlayerId != null) {
            clearTeleportRequest();
            clearTeleportReminder();
        }
        if (this.mode == CompanionMode.STOPPED) {
            return;
        }
        if (this.taskCoordinator.isBusy()) {
            return;
        }
        Player nearest = this.level().getNearestPlayer(this, TELEPORT_SEARCH_DISTANCE);
        if (nearest == null || nearest.isSpectator() || !nearest.isAlive()) {
            return;
        }
        if (this.distanceToSqr(nearest) <= TELEPORT_REQUEST_DISTANCE_SQR) {
            return;
        }
        Vec3 targetPos = resolveTeleportTarget(nearest);
        this.teleportTo(targetPos.x, targetPos.y, targetPos.z);
        this.getNavigation().stop();
    }

    private Vec3 resolveTeleportTarget(Player player) {
        if (player == null) {
            return this.position();
        }
        Vec3 playerPos = player.position();
        Vec3 forward = player.getLookAngle();
        forward = new Vec3(forward.x, 0.0D, forward.z);
        if (forward.lengthSqr() < 1.0E-4D) {
            float yaw = player.getYRot() * ((float) Math.PI / 180.0F);
            forward = new Vec3(-Mth.sin(yaw), 0.0D, Mth.cos(yaw));
        }
        forward = forward.normalize();
        Vec3 right = new Vec3(-forward.z, 0.0D, forward.x);
        Vec3 behind = forward.scale(-TELEPORT_BEHIND_DISTANCE);
        Vec3[] offsets = new Vec3[]{
                behind,
                behind.add(right.scale(TELEPORT_SIDE_DISTANCE)),
                behind.add(right.scale(-TELEPORT_SIDE_DISTANCE)),
                right.scale(TELEPORT_SIDE_DISTANCE + 1.0D),
                right.scale(-(TELEPORT_SIDE_DISTANCE + 1.0D)),
                behind.scale(1.5D)
        };
        Vec3 spot = findTeleportSpot(playerPos, forward, offsets, true);
        if (spot != null) {
            return spot;
        }
        spot = findTeleportSpot(playerPos, forward, offsets, false);
        if (spot != null) {
            return spot;
        }
        spot = findNearbySafeSpot(playerPos, forward, TELEPORT_NEARBY_RADIUS, true);
        if (spot != null) {
            return spot;
        }
        spot = findNearbySafeSpot(playerPos, forward, TELEPORT_NEARBY_RADIUS, false);
        if (spot != null) {
            return spot;
        }
        return this.position();
    }

    private Vec3 findTeleportSpot(Vec3 playerPos, Vec3 forward, Vec3[] offsets, boolean requireOutOfView) {
        for (Vec3 offset : offsets) {
            Vec3 candidate = new Vec3(playerPos.x + offset.x, playerPos.y, playerPos.z + offset.z);
            Vec3 adjusted = adjustTeleportY(candidate);
            if (adjusted == null) {
                continue;
            }
            if (requireOutOfView && !isOutOfPlayerView(playerPos, forward, adjusted)) {
                continue;
            }
            return adjusted;
        }
        return null;
    }

    private Vec3 findNearbySafeSpot(Vec3 playerPos, Vec3 forward, int radius, boolean requireOutOfView) {
        Vec3 best = null;
        double bestDistance = Double.MAX_VALUE;
        int baseX = Mth.floor(playerPos.x);
        int baseZ = Mth.floor(playerPos.z);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                Vec3 candidate = new Vec3(baseX + 0.5D + dx, playerPos.y, baseZ + 0.5D + dz);
                Vec3 adjusted = adjustTeleportY(candidate);
                if (adjusted == null) {
                    continue;
                }
                if (requireOutOfView && !isOutOfPlayerView(playerPos, forward, adjusted)) {
                    continue;
                }
                double distance = adjusted.distanceToSqr(playerPos);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = adjusted;
                }
            }
        }
        return best;
    }

    private boolean isOutOfPlayerView(Vec3 playerPos, Vec3 forward, Vec3 targetPos) {
        Vec3 toTarget = targetPos.subtract(playerPos);
        toTarget = new Vec3(toTarget.x, 0.0D, toTarget.z);
        if (toTarget.lengthSqr() < 1.0E-4D) {
            return false;
        }
        double dot = forward.dot(toTarget.normalize());
        return dot < TELEPORT_FOV_DOT_THRESHOLD;
    }

    private Vec3 adjustTeleportY(Vec3 basePos) {
        Level level = this.level();
        if (level == null) {
            return basePos;
        }
        BlockPos base = BlockPos.containing(basePos);
        for (int dy = TELEPORT_Y_SEARCH_UP; dy >= -TELEPORT_Y_SEARCH_DOWN; dy--) {
            BlockPos feetPos = new BlockPos(base.getX(), base.getY() + dy, base.getZ());
            BlockPos groundPos = feetPos.below();
            if (!isSafeGround(groundPos)) {
                continue;
            }
            if (!isClearForTeleport(feetPos) || !isClearForTeleport(feetPos.above())) {
                continue;
            }
            Vec3 candidate = new Vec3(basePos.x, feetPos.getY(), basePos.z);
            if (isSafeTeleportPosition(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private boolean isSafeTeleportPosition(Vec3 pos) {
        Level level = this.level();
        if (level == null) {
            return true;
        }
        BlockPos blockPos = BlockPos.containing(pos);
        BlockPos groundPos = blockPos.below();
        if (!level.hasChunkAt(blockPos) || !level.hasChunkAt(groundPos)) {
            return false;
        }
        if (!isSafeGround(groundPos)) {
            return false;
        }
        if (!isClearForTeleport(blockPos) || !isClearForTeleport(blockPos.above())) {
            return false;
        }
        AABB box = this.getBoundingBox().move(pos.x - this.getX(), pos.y - this.getY(), pos.z - this.getZ());
        return level.noCollision(this, box);
    }

    private boolean isSafeGround(BlockPos groundPos) {
        Level level = this.level();
        if (level == null || groundPos == null) {
            return false;
        }
        if (groundPos.getY() < level.getMinBuildHeight()) {
            return false;
        }
        if (!level.hasChunkAt(groundPos)) {
            return false;
        }
        BlockState state = level.getBlockState(groundPos);
        if (!state.getFluidState().isEmpty()) {
            return false;
        }
        if (!state.isFaceSturdy(level, groundPos, Direction.UP)) {
            return false;
        }
        return !isDamagingBlock(state);
    }

    private boolean isClearForTeleport(BlockPos pos) {
        Level level = this.level();
        if (level == null || pos == null) {
            return false;
        }
        if (!level.hasChunkAt(pos)) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        if (!state.getFluidState().isEmpty()) {
            return false;
        }
        return state.getCollisionShape(level, pos).isEmpty();
    }

    private boolean isDamagingBlock(BlockState state) {
        return state.is(Blocks.MAGMA_BLOCK)
                || state.is(Blocks.CAMPFIRE)
                || state.is(Blocks.SOUL_CAMPFIRE)
                || state.is(Blocks.CACTUS)
                || state.is(Blocks.SWEET_BERRY_BUSH)
                || state.is(Blocks.WITHER_ROSE)
                || state.is(Blocks.FIRE)
                || state.is(Blocks.SOUL_FIRE);
    }

    private String pickTeleportRequestKey(long gameTime) {
        if (gameTime - lastTeleportOriginalTick >= TELEPORT_ORIGINAL_COOLDOWN_TICKS) {
            return TELEPORT_REQUEST_KEY;
        }
        return TELEPORT_REQUEST_ALT_KEY;
    }

    private static String pickTeleportRequestKey(long gameTime, long lastOriginalTick) {
        if (gameTime - lastOriginalTick >= TELEPORT_ORIGINAL_COOLDOWN_TICKS) {
            return TELEPORT_REQUEST_KEY;
        }
        return TELEPORT_REQUEST_ALT_KEY;
    }

    private static ServerPlayer findNearestPlayer(ServerLevel level, BlockPos origin, double maxDistance) {
        if (level == null || origin == null) {
            return null;
        }
        double maxDistanceSqr = maxDistance * maxDistance;
        ServerPlayer nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        double originX = origin.getX() + 0.5D;
        double originY = origin.getY() + 0.5D;
        double originZ = origin.getZ() + 0.5D;
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator() || !player.isAlive()) {
                continue;
            }
            double distance = player.distanceToSqr(originX, originY, originZ);
            if (distance > maxDistanceSqr || distance >= nearestDistance) {
                continue;
            }
            nearestDistance = distance;
            nearest = player;
        }
        return nearest;
    }

    private boolean registerPendingTeleportRequest(ServerPlayer player, String messageKey, long untilTick,
                                                   int secondsLeft) {
        if (player == null || messageKey == null) {
            return false;
        }
        PendingTeleportRequest request = new PendingTeleportRequest(
                this.getUUID(),
                this.level().dimension(),
                new ChunkPos(this.blockPosition()),
                untilTick,
                messageKey,
                secondsLeft
        );
        if (PENDING_TELEPORT_REQUESTS.putIfAbsent(player.getUUID(), request) != null) {
            return false;
        }
        PENDING_TELEPORTS.put(player.getUUID(), this);
        return true;
    }

    private static boolean registerPendingTeleportRequest(ServerPlayer player, UUID companionId,
                                                          ResourceKey<Level> levelKey, BlockPos position,
                                                          String messageKey, long untilTick, int secondsLeft) {
        if (player == null || companionId == null || levelKey == null || position == null || messageKey == null) {
            return false;
        }
        PendingTeleportRequest request = new PendingTeleportRequest(
                companionId,
                levelKey,
                new ChunkPos(position),
                untilTick,
                messageKey,
                secondsLeft
        );
        if (PENDING_TELEPORT_REQUESTS.putIfAbsent(player.getUUID(), request) != null) {
            return false;
        }
        return true;
    }

    private void clearTeleportRequest() {
        if (pendingTeleportPlayerId != null) {
            removePendingTeleportRequest(pendingTeleportPlayerId, this.getUUID());
            PENDING_TELEPORTS.remove(pendingTeleportPlayerId, this);
            clearTeleportRequestState();
        }
    }

    private void clearTeleportRequestState() {
        pendingTeleportPlayerId = null;
        pendingTeleportUntilTick = -1L;
    }

    private static void removePendingTeleportRequest(UUID playerId, UUID companionId) {
        if (playerId == null || companionId == null) {
            return;
        }
        PendingTeleportRequest request = PENDING_TELEPORT_REQUESTS.get(playerId);
        if (request != null && companionId.equals(request.companionId)) {
            PENDING_TELEPORT_REQUESTS.remove(playerId, request);
        }
    }

    private void clearTeleportReminder() {
        pendingTeleportReminderPlayerId = null;
        pendingTeleportReminderTick = -1L;
    }

    public static CompanionEntity getPendingTeleportFor(Player player) {
        if (player == null) {
            return null;
        }
        CompanionEntity pending = PENDING_TELEPORTS.get(player.getUUID());
        if (pending != null) {
            if (pending.isRemoved() || !pending.isAlive() || !pending.isPendingTeleportFor(player)) {
                PENDING_TELEPORTS.remove(player.getUUID(), pending);
            } else {
                return pending;
            }
        }
        if (player instanceof ServerPlayer serverPlayer) {
            CompanionEntity active = CompanionSingleNpcManager.getActive(serverPlayer);
            if (active != null && active.isPendingTeleportFor(player)) {
                PENDING_TELEPORTS.put(player.getUUID(), active);
                return active;
            }
            PendingTeleportRequest request = PENDING_TELEPORT_REQUESTS.get(player.getUUID());
            if (request != null) {
                CompanionEntity resolved = resolvePendingTeleportCompanion(serverPlayer, request);
                if (resolved != null) {
                    PENDING_TELEPORTS.put(player.getUUID(), resolved);
                    return resolved;
                }
                removePendingTeleportRequest(player.getUUID(), request.companionId);
                PENDING_TELEPORTS.remove(player.getUUID());
            }
        }
        return null;
    }

    private static CompanionEntity resolvePendingTeleportCompanion(ServerPlayer player, PendingTeleportRequest request) {
        if (player == null || request == null) {
            return null;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return null;
        }
        ServerLevel level = server.getLevel(request.levelKey);
        if (level == null) {
            return null;
        }
        CompanionEntity companion = (CompanionEntity) level.getEntity(request.companionId);
        if (companion == null) {
            level.getChunkSource().getChunk(request.chunkPos.x, request.chunkPos.z, ChunkStatus.FULL, true);
            companion = (CompanionEntity) level.getEntity(request.companionId);
        }
        if (companion == null || companion.isRemoved()) {
            return null;
        }
        if (!companion.isPendingTeleportFor(player)) {
            return null;
        }
        return companion;
    }

    public static boolean shouldIgnoreExpiredTeleport(Player player, long gameTime) {
        if (player == null) {
            return false;
        }
        Long expiredAt = TELEPORT_IGNORED_TICKS.get(player.getUUID());
        if (expiredAt == null) {
            return false;
        }
        if (gameTime - expiredAt <= TELEPORT_IGNORE_GRACE_TICKS) {
            return true;
        }
        TELEPORT_IGNORED_TICKS.remove(player.getUUID(), expiredAt);
        return false;
    }

    public static boolean handleTeleportResponse(ServerPlayer player, boolean accepted) {
        if (player == null) {
            return false;
        }
        PendingTeleportRequest request = PENDING_TELEPORT_REQUESTS.get(player.getUUID());
        if (request == null) {
            return false;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return false;
        }
        ServerLevel level = server.getLevel(request.levelKey);
        long gameTime = level != null ? level.getGameTime() : player.level().getGameTime();
        if (gameTime >= request.untilTick) {
            PENDING_TELEPORT_REQUESTS.remove(player.getUUID(), request);
            PENDING_TELEPORTS.remove(player.getUUID());
            return false;
        }
        CompanionEntity companion = null;
        if (level != null) {
            companion = (CompanionEntity) level.getEntity(request.companionId);
            if (companion == null) {
                level.getChunkSource().getChunk(request.chunkPos.x, request.chunkPos.z, ChunkStatus.FULL, true);
                companion = (CompanionEntity) level.getEntity(request.companionId);
            }
        }
        if (accepted && companion != null && companion.isAlive()) {
            Vec3 targetPos = companion.resolveTeleportTarget(player);
            companion.teleportTo(targetPos.x, targetPos.y, targetPos.z);
            companion.getNavigation().stop();
        }
        PENDING_TELEPORT_REQUESTS.remove(player.getUUID(), request);
        PENDING_TELEPORTS.remove(player.getUUID());
        if (companion != null) {
            companion.clearTeleportRequestState();
            companion.clearTeleportReminder();
        }
        return true;
    }

    public static void tickPendingTeleports(MinecraftServer server) {
        if (server == null) {
            return;
        }
        if (!PENDING_TELEPORT_REQUESTS.isEmpty()) {
            PENDING_TELEPORT_REQUESTS.clear();
            PENDING_TELEPORTS.clear();
        }
    }

    public static void tickTeleportRequestFallback(MinecraftServer server) {
        if (server == null) {
            return;
        }
        UUID companionId = CompanionSingleNpcManager.getActiveId();
        ResourceKey<Level> levelKey = CompanionSingleNpcManager.getActiveDimension();
        BlockPos lastPos = CompanionSingleNpcManager.getLastKnownPos();
        if (companionId == null || levelKey == null || lastPos == null) {
            return;
        }
        if (CompanionSingleNpcManager.getLastMode() == CompanionMode.STOPPED
                || CompanionSingleNpcManager.isLastBusy()) {
            return;
        }
        ServerLevel level = server.getLevel(levelKey);
        if (level == null) {
            return;
        }
        ServerPlayer nearest = findNearestPlayer(level, lastPos, TELEPORT_SEARCH_DISTANCE);
        if (nearest == null) {
            return;
        }
        if (nearest.distanceToSqr(lastPos.getX() + 0.5D, lastPos.getY() + 0.5D, lastPos.getZ() + 0.5D)
                <= TELEPORT_REQUEST_DISTANCE_SQR) {
            return;
        }
        CompanionEntity companion = (CompanionEntity) level.getEntity(companionId);
        if (companion == null) {
            level.getChunkSource().getChunk(lastPos.getX() >> 4, lastPos.getZ() >> 4, ChunkStatus.FULL, true);
            companion = (CompanionEntity) level.getEntity(companionId);
        }
        if (companion == null || companion.isRemoved() || !companion.isAlive()) {
            return;
        }
        Vec3 targetPos = companion.resolveTeleportTarget(nearest);
        companion.teleportTo(targetPos.x, targetPos.y, targetPos.z);
        companion.getNavigation().stop();
    }

    public void handleTeleportResponse(Player player, boolean accepted) {
        if (pendingTeleportPlayerId == null || !pendingTeleportPlayerId.equals(player.getUUID())) {
            return;
        }
        if (this.level().getGameTime() >= pendingTeleportUntilTick) {
            clearTeleportRequest();
            clearTeleportReminder();
            return;
        }
        if (accepted && player.level() == this.level() && this.isAlive()) {
            Vec3 targetPos = resolveTeleportTarget(player);
            this.teleportTo(targetPos.x, targetPos.y, targetPos.z);
            this.getNavigation().stop();
        }
        clearTeleportRequest();
        clearTeleportReminder();
    }

    private boolean isPendingTeleportFor(Player player) {
        return player != null && pendingTeleportPlayerId != null
                && pendingTeleportPlayerId.equals(player.getUUID());
    }

    private String pickDeathKey(DamageSource source) {
        String msgId = source.getMsgId();
        if ("lava".equals(msgId)) {
            return pickRandomKey(DEATH_LAVA_KEYS);
        }
        if ("fall".equals(msgId) || "fallingBlock".equals(msgId) || "fallingStalactite".equals(msgId)) {
            return pickRandomKey(DEATH_FALL_KEYS);
        }
        return pickRandomKey(DEATH_GENERIC_KEYS);
    }

    private String pickRandomKey(String[] keys) {
        return keys[this.random.nextInt(keys.length)];
    }

    public Player getPlayerById(UUID playerId) {
        return findPlayerById(playerId);
    }

    private Player findPlayerById(UUID playerId) {
        for (Player player : this.level().players()) {
            if (player.getUUID().equals(playerId)) {
                return player;
            }
        }
        return null;
    }

    private boolean isIgnoredHitReaction(DamageSource source) {
        String msgId = source.getMsgId();
        return "lava".equals(msgId)
                || "inFire".equals(msgId)
                || "onFire".equals(msgId)
                || "hotFloor".equals(msgId);
    }

    private int[] snapshotArmorDamage() {
        int[] damage = new int[ARMOR_SLOTS.length];
        for (int i = 0; i < ARMOR_SLOTS.length; i++) {
            ItemStack stack = getItemBySlot(ARMOR_SLOTS[i]);
            damage[i] = stack.isEmpty() ? -1 : stack.getDamageValue();
        }
        return damage;
    }

    private void ensureArmorDurability(DamageSource source, float amount, int[] before) {
        if (source == null || amount <= 0.0F || before == null) {
            return;
        }
        if (!hasArmorEquipped()) {
            return;
        }
        if (armorDamageChanged(before)) {
            return;
        }
        this.hurtArmor(source, amount);
    }

    private boolean hasArmorEquipped() {
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            if (!getItemBySlot(slot).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private boolean armorDamageChanged(int[] before) {
        for (int i = 0; i < ARMOR_SLOTS.length; i++) {
            ItemStack stack = getItemBySlot(ARMOR_SLOTS[i]);
            int previous = before[i];
            if (stack.isEmpty()) {
                if (previous != -1) {
                    return true;
                }
                continue;
            }
            if (previous == -1 || stack.getDamageValue() != previous) {
                return true;
            }
        }
        return false;
    }

    private static void saveToolSlot(CompoundTag tag, String key, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        tag.put(key, stack.save(new CompoundTag()));
    }

    private static ItemStack loadToolSlot(CompoundTag tag, String key) {
        if (tag == null || !tag.contains(key)) {
            return ItemStack.EMPTY;
        }
        return ItemStack.of(tag.getCompound(key));
    }

    private void restoreToolSlotsFromHand() {
        ItemStack mainHand = getMainHandItem();
        CompanionToolSlot slot = CompanionToolSlot.fromStack(mainHand);
        if (slot == null || !getToolSlot(slot).isEmpty()) {
            return;
        }
        setToolSlot(slot, mainHand);
        setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
    }
}
