package ru.nekostul.aicompanion.entity;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CompanionEntity extends PathfinderMob {
    public enum CompanionMode {
        STOPPED,
        FOLLOW,
        AUTONOMOUS
    }

    private enum ChatContext {
        NONE,
        VILLAGE,
        HOSTILE,
        MINING,
        CRAFT
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
    private static final String TOOL_SLOT_PICKAXE_NBT = "Pickaxe";
    private static final String TOOL_SLOT_AXE_NBT = "Axe";
    private static final String TOOL_SLOT_SHOVEL_NBT = "Shovel";
    private static final String TOOL_SLOT_SWORD_NBT = "Sword";
    private static final String GREET_KEY = "entity.aicompanion.companion.spawn.greeting";
    private static final String GREET_ABOUT_KEY = "entity.aicompanion.companion.spawn.about";
    private static final String GREET_COMMANDS_KEY = "entity.aicompanion.companion.spawn.commands";
    private static final String COMMANDS_HEADER_KEY = "entity.aicompanion.companion.commands.header";
    private static final String COMMANDS_STOP_HINT_KEY = "entity.aicompanion.companion.commands.stop_hint";
    private static final String COMMAND_STOP_KEY = "entity.aicompanion.companion.command.stop";
    private static final String COMMAND_FOLLOW_KEY = "entity.aicompanion.companion.command.follow";
    private static final String COMMAND_FREE_KEY = "entity.aicompanion.companion.command.free";
    private static final int REACTION_COOLDOWN_TICKS = 60;
    private static final double REACTION_RANGE = 24.0D;
    private static final double FOLLOW_MAX_DISTANCE = 48.0D;
    private static final double FOLLOW_MAX_DISTANCE_SQR = FOLLOW_MAX_DISTANCE * FOLLOW_MAX_DISTANCE;
    private static final double FOLLOW_SEARCH_DISTANCE = 48.0D;
    private static final double TELEPORT_REQUEST_DISTANCE = 48.0D;
    private static final double TELEPORT_REQUEST_DISTANCE_SQR = TELEPORT_REQUEST_DISTANCE * TELEPORT_REQUEST_DISTANCE;
    private static final double TELEPORT_SEARCH_DISTANCE = 128.0D;
    private static final String TELEPORT_REQUEST_KEY = "entity.aicompanion.companion.teleport.request";
    private static final String TELEPORT_REQUEST_ALT_KEY = "entity.aicompanion.companion.teleport.request.alt";
    private static final String TELEPORT_REQUEST_REPEAT_KEY = "entity.aicompanion.companion.teleport.request.repeat";
    private static final String TELEPORT_ACCEPT_KEY = "entity.aicompanion.companion.teleport.accept";
    private static final String TELEPORT_DENY_KEY = "entity.aicompanion.companion.teleport.deny";
    private static final String TELEPORT_YES_KEY = "entity.aicompanion.companion.teleport.button.yes";
    private static final String TELEPORT_NO_KEY = "entity.aicompanion.companion.teleport.button.no";
    private static final int TELEPORT_MESSAGE_COOLDOWN_TICKS = 6000;
    private static final int TELEPORT_REPEAT_DELAY_TICKS = 800;
    private static final int TELEPORT_ORIGINAL_COOLDOWN_TICKS = 12000;
    private static final int TELEPORT_RESPONSE_TIMEOUT_TICKS = 600;
    private static final int INVENTORY_SIZE = 27;
    private static final int DEFENSE_RADIUS = 12;
    private static final double DEFENSE_RADIUS_SQR = DEFENSE_RADIUS * DEFENSE_RADIUS;
    private static final int ITEM_PICKUP_RADIUS = 3;
    private static final int ITEM_PICKUP_COOLDOWN_TICKS = 10;
    private static final int AMBIENT_CHAT_MIN_TICKS = 1000;
    private static final int AMBIENT_CHAT_MAX_TICKS = 1800;
    private static final int CONTEXT_CHAT_SOON_MIN_TICKS = 200;
    private static final int CONTEXT_CHAT_SOON_MAX_TICKS = 400;
    private static final int CHAT_GROUP_COOLDOWN_TICKS = 6000;
    private static final int LOG_FROM_LEAVES_RADIUS = 2;
    private static final int LOG_FROM_LEAVES_MAX_DEPTH = 6;

    private static final EntityDataAccessor<ItemStack> TOOL_PICKAXE =
            SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.ITEM_STACK);
    private static final EntityDataAccessor<ItemStack> TOOL_AXE =
            SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.ITEM_STACK);
    private static final EntityDataAccessor<ItemStack> TOOL_SHOVEL =
            SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.ITEM_STACK);
    private static final EntityDataAccessor<ItemStack> TOOL_SWORD =
            SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.ITEM_STACK);

    private static final Map<UUID, CompanionEntity> PENDING_TELEPORTS = new ConcurrentHashMap<>();

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

    private static final String[] CHAT_FOLLOW_KEYS =
            range("entity.aicompanion.companion.chat.follow.", 1, 20);

    private static final String[] CHAT_AUTONOMOUS_KEYS =
            range("entity.aicompanion.companion.chat.autonomous.", 1, 20);

    private static final String[] CHAT_STOP_KEYS =
            range("entity.aicompanion.companion.chat.stop.", 1, 12);

    private static final String[] CHAT_GENERIC_KEYS =
            range("entity.aicompanion.companion.chat.generic.", 1, 12);

    private static final String[] CHAT_CONTEXT_VILLAGE_KEYS =
            range("entity.aicompanion.companion.chat.context.village.", 1, 8);

    private static final String[] CHAT_CONTEXT_HOSTILE_KEYS =
            range("entity.aicompanion.companion.chat.context.hostile.", 1, 8);

    private static final String[] CHAT_CONTEXT_MINING_KEYS =
            range("entity.aicompanion.companion.chat.context.mining.", 1, 8);

    private static final String[] CHAT_CONTEXT_CRAFT_KEYS =
            range("entity.aicompanion.companion.chat.context.craft.", 1, 8);

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
    private String lastAmbientKey;
    private long nextAmbientChatTick = -1L;
    private long lastAmbientChatTick = -10000L;
    private ChatContext pendingChatContext = ChatContext.NONE;
    private long pendingChatContextUntilTick = -1L;
    private long lastReactionTick = -1000L;
    private long nextItemPickupTick = -1L;
    private long lastTeleportCycleTick = -10000L;
    private long lastTeleportOriginalTick = -10000L;
    private long pendingTeleportUntilTick = -1L;
    private UUID pendingTeleportPlayerId;
    private long pendingTeleportReminderTick = -1L;
    private UUID pendingTeleportReminderPlayerId;

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
    }

    @Override
    public void onAddedToWorld() {
        super.onAddedToWorld();
        CompanionSingleNpcManager.register(this);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new HoldPositionGoal(this, this::isStopMode));
        this.goalSelector.addGoal(2, new FollowNearestPlayerGoal(this, 1.35D, (float) FOLLOW_SEARCH_DISTANCE, 3.0F,
                this::isFollowModeActive));
        this.goalSelector.addGoal(3, new OpenDoorGoal(this, true));
        this.goalSelector.addGoal(4, new WaterAvoidingRandomStrollGoal(this, 0.8D));
        this.goalSelector.addGoal(5, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(6, new RandomLookAroundGoal(this));
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0D)
                .add(Attributes.ATTACK_DAMAGE, 3.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.3D)
                .add(Attributes.FOLLOW_RANGE, 32.0D);
    }

    public CompanionMode getMode() {
        return this.mode;
    }

    public boolean setMode(CompanionMode mode) {
        if (mode == null || this.mode == mode) {
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

    public boolean handlePlayerCommand(ServerPlayer player, String message) {
        return this.taskCoordinator.handlePlayerMessage(player, message);
    }

    public boolean handleThanks(ServerPlayer player, String message) {
        return this.gratitudeResponder.handle(player, message, this.level().getGameTime());
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
        if (tag.hasUUID(TELEPORT_PENDING_PLAYER_NBT)) {
            this.pendingTeleportPlayerId = tag.getUUID(TELEPORT_PENDING_PLAYER_NBT);
            this.pendingTeleportUntilTick = tag.getLong(TELEPORT_PENDING_UNTIL_NBT);
            if (PENDING_TELEPORTS.putIfAbsent(this.pendingTeleportPlayerId, this) != null) {
                this.pendingTeleportPlayerId = null;
                this.pendingTeleportUntilTick = -1L;
            }
        }
        if (tag.hasUUID(TELEPORT_REMINDER_PLAYER_NBT)) {
            this.pendingTeleportReminderPlayerId = tag.getUUID(TELEPORT_REMINDER_PLAYER_NBT);
            this.pendingTeleportReminderTick = tag.getLong(TELEPORT_REMINDER_TICK_NBT);
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
            tickGreeting();
            tickChestStatus();
            tickItemPickup();
            tickAutonomousBehavior();
            this.hungerSystem.tick(nearest, gameTime);
            tickAmbientChat();
            tickTeleportRequest();
        }
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        boolean result = super.hurt(source, amount);
        if (result && !this.level().isClientSide && this.isAlive()
                && source.getEntity() instanceof Player
                && !isIgnoredHitReaction(source)) {
            long gameTime = this.level().getGameTime();
            if (gameTime - this.lastReactionTick >= REACTION_COOLDOWN_TICKS) {
                this.lastReactionTick = gameTime;
                sendReaction(Component.translatable(pickRandomKey(HIT_KEYS)));
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
        clearTeleportRequest();
        clearTeleportReminder();
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

    private void tickAmbientChat() {
        long gameTime = this.level().getGameTime();
        if (this.nextAmbientChatTick < 0L) {
            this.nextAmbientChatTick = gameTime + randomBetween(AMBIENT_CHAT_MIN_TICKS, AMBIENT_CHAT_MAX_TICKS);
            return;
        }
        if (gameTime < this.nextAmbientChatTick) {
            return;
        }
        Player nearest = this.level().getNearestPlayer(this, FOLLOW_SEARCH_DISTANCE);
        if (nearest == null || nearest.isSpectator()) {
            this.nextAmbientChatTick = gameTime + randomBetween(AMBIENT_CHAT_MIN_TICKS, AMBIENT_CHAT_MAX_TICKS);
            return;
        }
        ChatContext context = resolveChatContext(gameTime);
        ChatGroup group = resolveChatGroup(context);
        long nextAllowedTick = getChatGroupNextAllowedTick(group);
        if (nextAllowedTick > gameTime) {
            this.nextAmbientChatTick = Math.max(this.nextAmbientChatTick, nextAllowedTick);
            return;
        }
        String key = pickAmbientChatKey(context);
        this.lastAmbientKey = key;
        sendDirectMessage(nearest, Component.translatable(key));
        this.lastAmbientChatTick = gameTime;
        markChatGroupUsed(group, gameTime);
        if (context == this.pendingChatContext) {
            this.pendingChatContext = ChatContext.NONE;
            this.pendingChatContextUntilTick = -1L;
        }
        this.nextAmbientChatTick = gameTime + randomBetween(AMBIENT_CHAT_MIN_TICKS, AMBIENT_CHAT_MAX_TICKS);
    }

    private ChatGroup resolveChatGroup(ChatContext context) {
        return switch (context) {
            case VILLAGE -> ChatGroup.VILLAGE;
            case HOSTILE -> ChatGroup.HOSTILE;
            case MINING -> ChatGroup.MINING;
            case CRAFT -> ChatGroup.CRAFT;
            case NONE -> switch (this.mode) {
                case STOPPED -> ChatGroup.STOPPED;
                case FOLLOW -> ChatGroup.FOLLOW;
                case AUTONOMOUS -> ChatGroup.AUTONOMOUS;
            };
        };
    }

    private long getChatGroupNextAllowedTick(ChatGroup group) {
        Long last = this.chatGroupCooldowns.get(group);
        if (last == null) {
            return 0L;
        }
        return last + CHAT_GROUP_COOLDOWN_TICKS;
    }

    private void markChatGroupUsed(ChatGroup group, long gameTime) {
        this.chatGroupCooldowns.put(group, gameTime);
    }

    private String pickAmbientChatKey(ChatContext context) {
        String[] pool = switch (context) {
            case VILLAGE -> CHAT_CONTEXT_VILLAGE_KEYS;
            case HOSTILE -> CHAT_CONTEXT_HOSTILE_KEYS;
            case MINING -> CHAT_CONTEXT_MINING_KEYS;
            case CRAFT -> CHAT_CONTEXT_CRAFT_KEYS;
            case NONE -> switch (this.mode) {
                case STOPPED -> CHAT_STOP_KEYS;
                case AUTONOMOUS -> CHAT_AUTONOMOUS_KEYS;
                case FOLLOW -> CHAT_FOLLOW_KEYS;
            };
        };
        return pickRandomKeyAvoiding(pool, this.lastAmbientKey);
    }

    private ChatContext resolveChatContext(long gameTime) {
        if (this.pendingChatContext != ChatContext.NONE) {
            if (gameTime <= this.pendingChatContextUntilTick) {
                return this.pendingChatContext;
            }
            this.pendingChatContext = ChatContext.NONE;
            this.pendingChatContextUntilTick = -1L;
        }
        if (this.taskCoordinator.isBusy()) {
            return ChatContext.MINING;
        }
        if (findNearestHostile(DEFENSE_RADIUS) != null) {
            return ChatContext.HOSTILE;
        }
        if (isVillageNearby()) {
            return ChatContext.VILLAGE;
        }
        return ChatContext.NONE;
    }

    private boolean isVillageNearby() {
        AABB range = this.getBoundingBox().inflate(16.0D);
        return !this.level().getEntitiesOfClass(Villager.class, range).isEmpty();
    }

    private void queueContextChat(ChatContext context, long gameTime) {
        if (context == null || context == ChatContext.NONE) {
            return;
        }
        this.pendingChatContext = context;
        this.pendingChatContextUntilTick = gameTime + CONTEXT_CHAT_SOON_MAX_TICKS;
        if (gameTime - this.lastAmbientChatTick < CONTEXT_CHAT_SOON_MIN_TICKS) {
            return;
        }
        if (this.nextAmbientChatTick < 0L
                || this.nextAmbientChatTick - gameTime > CONTEXT_CHAT_SOON_MAX_TICKS) {
            this.nextAmbientChatTick = gameTime + randomBetween(CONTEXT_CHAT_SOON_MIN_TICKS,
                    CONTEXT_CHAT_SOON_MAX_TICKS);
        }
    }

    private boolean isFollowModeActive() {
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

    private void sendTeleportRequest(Player player, String messageKey) {
        Component yesButton = Component.translatable(TELEPORT_YES_KEY)
                .withStyle(style -> style
                        .withColor(ChatFormatting.GREEN)
                        .withBold(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ainpc tp yes")));
        Component noButton = Component.translatable(TELEPORT_NO_KEY)
                .withStyle(style -> style
                        .withColor(ChatFormatting.RED)
                        .withBold(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ainpc tp no")));
        Component message = Component.translatable(messageKey)
                .append(Component.literal(" "))
                .append(Component.literal("["))
                .append(yesButton)
                .append(Component.literal("] "))
                .append(Component.literal("["))
                .append(noButton)
                .append(Component.literal("]"));
        sendDirectMessage(player, message);
    }

    private void tickTeleportRequest() {
        if (this.mode == CompanionMode.STOPPED) {
            return;
        }
        if (this.taskCoordinator.isBusy()) {
            return;
        }
        long gameTime = this.level().getGameTime();
        if (pendingTeleportPlayerId != null) {
            Player player = findPlayerById(pendingTeleportPlayerId);
            if (player == null || player.isSpectator() || !player.isAlive()) {
                clearTeleportRequest();
                clearTeleportReminder();
                return;
            }
            if (this.distanceToSqr(player) <= TELEPORT_REQUEST_DISTANCE_SQR) {
                clearTeleportRequest();
                clearTeleportReminder();
                return;
            }
            if (gameTime >= pendingTeleportUntilTick) {
                clearTeleportRequest();
            }
        }

        if (pendingTeleportPlayerId != null) {
            return;
        }

        if (pendingTeleportReminderPlayerId != null && gameTime >= pendingTeleportReminderTick) {
            Player player = findPlayerById(pendingTeleportReminderPlayerId);
            if (player == null || player.isSpectator() || !player.isAlive()) {
                clearTeleportReminder();
                return;
            }
            if (this.distanceToSqr(player) <= TELEPORT_REQUEST_DISTANCE_SQR) {
                clearTeleportReminder();
                return;
            }
            if (PENDING_TELEPORTS.putIfAbsent(player.getUUID(), this) != null) {
                clearTeleportReminder();
                return;
            }
            pendingTeleportPlayerId = player.getUUID();
            pendingTeleportUntilTick = gameTime + TELEPORT_RESPONSE_TIMEOUT_TICKS;
            sendTeleportRequest(player, TELEPORT_REQUEST_REPEAT_KEY);
            lastTeleportCycleTick = gameTime;
            clearTeleportReminder();
            return;
        }

        if (gameTime - lastTeleportCycleTick < TELEPORT_MESSAGE_COOLDOWN_TICKS) {
            return;
        }

        Player nearest = this.level().getNearestPlayer(this, TELEPORT_SEARCH_DISTANCE);
        if (nearest == null || nearest.isSpectator()) {
            return;
        }
        if (this.distanceToSqr(nearest) <= TELEPORT_REQUEST_DISTANCE_SQR) {
            return;
        }
        if (PENDING_TELEPORTS.putIfAbsent(nearest.getUUID(), this) != null) {
            return;
        }

        String messageKey = pickTeleportRequestKey(gameTime);
        if (TELEPORT_REQUEST_KEY.equals(messageKey)) {
            lastTeleportOriginalTick = gameTime;
        }
        pendingTeleportPlayerId = nearest.getUUID();
        pendingTeleportUntilTick = gameTime + TELEPORT_RESPONSE_TIMEOUT_TICKS;
        pendingTeleportReminderPlayerId = nearest.getUUID();
        pendingTeleportReminderTick = gameTime + TELEPORT_REPEAT_DELAY_TICKS;
        lastTeleportCycleTick = gameTime;
        sendTeleportRequest(nearest, messageKey);
    }

    private String pickTeleportRequestKey(long gameTime) {
        if (gameTime - lastTeleportOriginalTick >= TELEPORT_ORIGINAL_COOLDOWN_TICKS) {
            return TELEPORT_REQUEST_KEY;
        }
        return TELEPORT_REQUEST_ALT_KEY;
    }

    private void clearTeleportRequest() {
        if (pendingTeleportPlayerId != null) {
            PENDING_TELEPORTS.remove(pendingTeleportPlayerId, this);
            pendingTeleportPlayerId = null;
            pendingTeleportUntilTick = -1L;
        }
    }

    private void clearTeleportReminder() {
        pendingTeleportReminderPlayerId = null;
        pendingTeleportReminderTick = -1L;
    }

    public static CompanionEntity getPendingTeleportFor(Player player) {
        return PENDING_TELEPORTS.get(player.getUUID());
    }

    public void handleTeleportResponse(Player player, boolean accepted) {
        if (pendingTeleportPlayerId == null || !pendingTeleportPlayerId.equals(player.getUUID())) {
            return;
        }
        if (accepted) {
            if (player.level() == this.level() && this.isAlive()) {
                this.teleportTo(player.getX(), player.getY(), player.getZ());
                this.getNavigation().stop();
                sendDirectMessage(player, Component.translatable(TELEPORT_ACCEPT_KEY));
            } else {
                sendDirectMessage(player, Component.translatable(TELEPORT_DENY_KEY));
            }
        } else {
            sendDirectMessage(player, Component.translatable(TELEPORT_DENY_KEY));
        }
        clearTeleportRequest();
        clearTeleportReminder();
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

    private Animal findNearestAnimal(double radius) {
        AABB range = this.getBoundingBox().inflate(radius);
        Animal nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (Animal animal : this.level().getEntitiesOfClass(Animal.class, range)) {
            if (!animal.isAlive()) {
                continue;
            }
            double distance = this.distanceToSqr(animal);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = animal;
            }
        }
        return nearest;
    }

    private Monster findNearestHostile(double radius) {
        AABB range = this.getBoundingBox().inflate(radius);
        Monster nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (Monster monster : this.level().getEntitiesOfClass(Monster.class, range)) {
            if (!monster.isAlive()) {
                continue;
            }
            double distance = this.distanceToSqr(monster);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = monster;
            }
        }
        return nearest;
    }

    private LivingEntity findLivingEntityById(UUID entityId) {
        if (entityId == null) {
            return null;
        }
        AABB range = this.getBoundingBox().inflate(32.0D);
        for (LivingEntity entity : this.level().getEntitiesOfClass(LivingEntity.class, range)) {
            if (entity.getUUID().equals(entityId)) {
                return entity;
            }
        }
        return null;
    }

    private BlockPos findNearestLog(int radius) {
        BlockPos origin = this.blockPosition();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    pos.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (!this.level().getBlockState(pos).is(BlockTags.LOGS)) {
                        continue;
                    }
                    double distance = origin.distSqr(pos);
                    if (distance < nearestDistance) {
                        nearestDistance = distance;
                        nearest = pos.immutable();
                    }
                }
            }
        }
        return nearest;
    }

    private int randomBetween(int minTicks, int maxTicks) {
        if (maxTicks <= minTicks) {
            return minTicks;
        }
        return minTicks + this.random.nextInt(maxTicks - minTicks + 1);
    }

    private String pickRandomKeyAvoiding(String[] keys, String lastKey) {
        if (keys.length == 0) {
            return "entity.aicompanion.companion.chat.generic.1";
        }
        if (keys.length == 1) {
            return keys[0];
        }
        String key;
        do {
            key = keys[this.random.nextInt(keys.length)];
        } while (key.equals(lastKey));
        return key;
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

