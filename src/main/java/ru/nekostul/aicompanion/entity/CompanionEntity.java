package ru.nekostul.aicompanion.entity;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
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
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Entity;
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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.PortalInfo;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.ITeleporter;

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
import ru.nekostul.aicompanion.entity.movement.CompanionMovementSpeed;
import ru.nekostul.aicompanion.entity.movement.CompanionTeleportPositioning;
import ru.nekostul.aicompanion.entity.mining.CompanionGatheringController;
import ru.nekostul.aicompanion.entity.tool.CompanionToolHandler;
import ru.nekostul.aicompanion.entity.tool.CompanionToolSlot;
import ru.nekostul.aicompanion.entity.tree.CompanionTreeHarvestController;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

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
    private static final String HOME_POS_NBT = "CompanionHomePos";
    private static final String HOME_DIM_NBT = "CompanionHomeDim";
    private static final String HOME_SET_COOLDOWN_UNTIL_NBT = "CompanionHomeSetCooldownUntil";
    private static final String HOME_SET_ANCHOR_POS_NBT = "CompanionHomeSetAnchorPos";
    private static final String HOME_SET_ANCHOR_DIM_NBT = "CompanionHomeSetAnchorDim";
    private static final String HOME_SET_FREE_RADIUS_ACTIVE_NBT = "CompanionHomeSetFreeRadiusActive";
    private static final String HOME_RESPAWN_WAITING_NBT = "CompanionHomeRespawnWaiting";
    private static final String HOME_DEATH_RECOVERY_NBT = "CompanionHomeDeathRecovery";
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
    private static final String HOME_SET_KEY = "entity.aicompanion.companion.home.set";
    private static final String HOME_ALREADY_KEY = "entity.aicompanion.companion.home.already";
    private static final String HOME_MISSING_KEY = "entity.aicompanion.companion.home.missing";
    private static final String HOME_LOOK_DOWN_KEY = "entity.aicompanion.companion.home.look_down";
    private static final String HOME_DELETED_KEY = "entity.aicompanion.companion.home.deleted";
    private static final String HOME_CONFIRM_KEY = "entity.aicompanion.companion.home.confirm";
    private static final String HOME_DISTANCE_KEY = "entity.aicompanion.companion.home.distance";
    private static final String HOME_CONFIRM_BUTTON_KEY = "entity.aicompanion.companion.home.confirm.button";
    private static final String HOME_AUTO_GO_KEY = "entity.aicompanion.companion.home.auto_go";
    private static final String HOME_INTERACT_KEY = "entity.aicompanion.companion.home.interact";
    private static final String HOME_FOLLOW_KEY = "entity.aicompanion.companion.home.follow";
    private static final String HOME_FOLLOW_BUTTON_KEY = "entity.aicompanion.companion.home.follow.button";
    private static final String HOME_SET_COOLDOWN_KEY = "entity.aicompanion.companion.home.set.cooldown";
    private static final String HOME_DEATH_NO_HOME_KEY = "entity.aicompanion.companion.home.death.no_home";
    private static final String HOME_DEATH_RECOVERY_HP_KEY = "entity.aicompanion.companion.home.death.recovery.hp";
    private static final String HOME_DEATH_RECOVERY_HP_REMOVE_KEY = "entity.aicompanion.companion.home.death.recovery.hp.remove";
    private static final String INVENTORY_FULL_NEED_CHEST_KEY =
            "entity.aicompanion.companion.inventory.full.need_chest";
    private static final String WHERE_STATUS_KEY = "entity.aicompanion.companion.where.status";
    private static final String WHERE_TELEPORT_BUTTON_KEY = "entity.aicompanion.companion.where.button";
    private static final String OWNER_DEATH_COORDS_KEY = "entity.aicompanion.companion.owner.death.coords";
    private static final String TELEPORT_IGNORE_HOME_KEY = "entity.aicompanion.companion.teleport.ignore.home";
    private static final String TELEPORT_IGNORE_WHERE_KEY = "entity.aicompanion.companion.teleport.ignore.where";
    private static final String TELEPORT_IGNORE_FOLLOW_KEY = "entity.aicompanion.companion.teleport.ignore.follow";
    private static final String TELEPORT_IGNORE_BOAT_KEY = "entity.aicompanion.companion.teleport.ignore.boat";
    private static final String TELEPORT_IGNORE_DIMENSION_KEY =
            "entity.aicompanion.companion.teleport.ignore.dimension";
    private static final String BOAT_REQUEST_KEY = "entity.aicompanion.companion.boat.request";
    private static final String BOAT_REQUEST_BUTTON_KEY = "entity.aicompanion.companion.boat.button";
    private static final String DIMENSION_TELEPORT_REQUEST_KEY =
            "entity.aicompanion.companion.dimension.request";
    private static final String DIMENSION_TELEPORT_BUTTON_KEY =
            "entity.aicompanion.companion.dimension.button";
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
    private static final int INVENTORY_FULL_NOTIFY_COOLDOWN_TICKS = 100;
    private static final int HOME_LEAVE_DISTANCE = 96;
    private static final int HOME_AUTO_DISTANCE = 500;
    private static final int HOME_WARN_DISTANCE = 1000;
    private static final int HOME_CONFIRM_TICKS = 15 * 20;
    private static final int HOME_FOLLOW_TICKS = 5 * 20;
    private static final int WHERE_TELEPORT_TICKS = 5 * 20;
    private static final int BOAT_REQUEST_TICKS = 5 * 20;
    private static final int BOAT_REQUEST_COOLDOWN_TICKS = 5 * 20;
    private static final int BOAT_MAX_PASSENGERS = 2;
    private static final int DIMENSION_TELEPORT_TICKS = 10 * 20;
    private static final int WHERE_MIN_DISTANCE = 100;
    private static final int WHERE_COOLDOWN_TICKS = 5 * 60 * 20;
    private static final double BOAT_REQUEST_DISTANCE = 12.0D;
    private static final double BOAT_REQUEST_DISTANCE_SQR = BOAT_REQUEST_DISTANCE * BOAT_REQUEST_DISTANCE;
    private static final int HOME_INTERACT_COOLDOWN_TICKS = 1200;
    private static final int HOME_DEATH_INTERACT_COOLDOWN_TICKS = 5 * 20;
    private static final int HOME_REGEN_TICKS = 20;
    private static final int HOME_DEATH_REGEN_TICKS = 200;
    private static final float HOME_REGEN_AMOUNT = 1.0F;
    private static final double HOME_LEAVE_SPEED = 0.28D;
    private static final double HOME_SET_RANGE = 6.0D;
    private static final int HOME_SET_FREE_RADIUS = 32;
    private static final double HOME_SET_FREE_RADIUS_SQR = (double) HOME_SET_FREE_RADIUS * HOME_SET_FREE_RADIUS;
    private static final int HOME_SET_COOLDOWN_TICKS = 10 * 60 * 20;
    private static final int HOME_MOVE_RETRY_TICKS = 10;
    private static final int HOSTILE_PLAYER_ATTACK_COOLDOWN_TICKS = 20;
    private static final int HOSTILE_PLAYER_REPATH_TICKS = 10;
    private static final double HOSTILE_PLAYER_ATTACK_RANGE_SQR = 4.0D;
    private static final double HOSTILE_PLAYER_CHASE_SPEED = 0.32D;
    private static final int HOSTILE_PLAYER_SECOND_HIT_COUNT = 3;
    private static final int SWING_DURATION_TICKS = 6;

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
    private static final Map<UUID, Long> WHERE_FALLBACK_COOLDOWNS = new ConcurrentHashMap<>();
    private static final Set<UUID> APPROVED_DIMENSION_TELEPORTS = ConcurrentHashMap.newKeySet();

    private static final class PendingTeleportRequest {
        private final UUID companionId;
        private final ResourceKey<Level> levelKey;
        private final BlockPos originPos;
        private ChunkPos chunkPos;
        private final long untilTick;
        private final String messageKey;
        private int lastSeconds;

        private PendingTeleportRequest(UUID companionId, ResourceKey<Level> levelKey, BlockPos originPos,
                                       ChunkPos chunkPos,
                                       long untilTick, String messageKey, int lastSeconds) {
            this.companionId = companionId;
            this.levelKey = levelKey;
            this.originPos = originPos;
            this.chunkPos = chunkPos;
            this.untilTick = untilTick;
            this.messageKey = messageKey;
            this.lastSeconds = lastSeconds;
        }
    }

    private static final class CompanionDirectTeleporter implements ITeleporter {
        private final Vec3 destination;
        private final float yRot;
        private final float xRot;

        private CompanionDirectTeleporter(Vec3 destination, float yRot, float xRot) {
            this.destination = destination;
            this.yRot = yRot;
            this.xRot = xRot;
        }

        @Override
        public Entity placeEntity(Entity entity, ServerLevel currentWorld, ServerLevel destWorld, float yaw,
                                  Function<Boolean, Entity> repositionEntity) {
            return repositionEntity.apply(false);
        }

        @Override
        public PortalInfo getPortalInfo(Entity entity, ServerLevel destWorld,
                                        Function<ServerLevel, PortalInfo> defaultPortalInfo) {
            return new PortalInfo(destination, Vec3.ZERO, yRot, xRot);
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
    private long lastInventoryFullNeedChestTick = -10000L;
    private long lastTeleportCycleTick = -10000L;
    private long lastTeleportOriginalTick = -10000L;
    private long pendingTeleportUntilTick = -1L;
    private UUID pendingTeleportPlayerId;
    private long pendingTeleportReminderTick = -1L;
    private UUID pendingTeleportReminderPlayerId;
    private BlockPos homePos;
    private ResourceLocation homeDimension;
    private boolean returningHome;
    private Vec3 homeLeaveTarget;
    private UUID homeReturnPlayerId;
    private boolean homeDirectReturn;
    private BlockPos lastHomeMoveTarget;
    private long lastHomeMoveAttemptTick = -1L;
    private UUID lastStopPlayerId;
    private boolean stopHomeTriggered;
    private long lastHomeInteractTick = -10000L;
    private UUID pendingHomePlayerId;
    private long pendingHomeUntilTick = -1L;
    private int pendingHomeLastSeconds = -1;
    private int pendingHomeDistance;
    private UUID pendingWherePlayerId;
    private long pendingWhereUntilTick = -1L;
    private int pendingWhereLastSeconds = -1;
    private UUID pendingFollowPlayerId;
    private long pendingFollowUntilTick = -1L;
    private int pendingFollowLastSeconds = -1;
    private UUID pendingBoatPlayerId;
    private long pendingBoatUntilTick = -1L;
    private int pendingBoatLastSeconds = -1;
    private long nextBoatRequestTick = -1L;
    private final Map<UUID, Long> whereCooldowns = new HashMap<>();
    private long lastHomeRegenTick = -1L;
    private long lastHomeDeathInteractTick = -10000L;
    private boolean wasAtHome;
    private long homeSetCooldownUntilTick = -1L;
    private BlockPos homeSetAnchorPos;
    private ResourceLocation homeSetAnchorDimension;
    private boolean homeSetFreeRadiusActive;
    private boolean permanentDeathOnNextDeath;
    private boolean waitingForHomeRespawn;
    private boolean recoveringAfterDeathAtHome;
    private final Map<UUID, Integer> hostilePlayerStrikes = new HashMap<>();
    private UUID hostilePlayerTargetId;
    private int hostilePlayerHitsRemaining;
    private long hostilePlayerNextAttackTick = -1L;
    private BlockPos hostilePlayerLastPathTarget;
    private long hostilePlayerLastPathTick = -1L;
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
                        && !CompanionEntity.this.isAtHome()
                        && !CompanionEntity.this.returningHome
                        && !CompanionEntity.this.combatController.isEngaged(
                        CompanionEntity.this.level().getGameTime())
                        && !CompanionEntity.this.taskCoordinator.isBusy()
                        && super.canUse();
            }

            @Override
            public boolean canContinueToUse() {
                return !CompanionEntity.this.isFollowModeActive()
                        && !CompanionEntity.this.isAtHome()
                        && !CompanionEntity.this.returningHome
                        && !CompanionEntity.this.combatController.isEngaged(
                        CompanionEntity.this.level().getGameTime())
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
        if (recoveringAfterDeathAtHome && mode != CompanionMode.STOPPED) {
            return false;
        }
        if (treeHarvestController.isTreeChopInProgress()) {
            return false;
        }
        this.mode = mode;
        clearFollowRequest(true);
        if (mode != CompanionMode.STOPPED) {
            lastStopPlayerId = null;
            stopHomeTriggered = false;
            if (returningHome) {
                returningHome = false;
                homeLeaveTarget = null;
                homeReturnPlayerId = null;
            }
        }
        if (mode == CompanionMode.STOPPED) {
            this.getNavigation().stop();
        }
        return true;
    }

    public void sendReply(Player player, Component message) {
        sendDirectMessage(player, message);
    }

    public boolean isOwnedBy(Player player) {
        return isOwnerPlayer(player);
    }

    public void onOwnerDeath(ServerPlayer ownerPlayer, BlockPos deathPos) {
        if (this.level().isClientSide || ownerPlayer == null || deathPos == null || !this.isAlive()) {
            return;
        }
        if (!isOwnerPlayer(ownerPlayer)) {
            return;
        }
        if (recoveringAfterDeathAtHome) {
            sendReply(ownerPlayer, Component.translatable(
                    OWNER_DEATH_COORDS_KEY, deathPos.getX(), deathPos.getY(), deathPos.getZ()));
            return;
        }
        if (this.level() != ownerPlayer.level()) {
            return;
        }
        Vec3 targetPos = resolveTeleportTarget(ownerPlayer);
        this.teleportTo(targetPos.x, targetPos.y, targetPos.z);
        this.getNavigation().stop();
        sendReply(ownerPlayer, Component.translatable(
                OWNER_DEATH_COORDS_KEY, deathPos.getX(), deathPos.getY(), deathPos.getZ()));
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
        if (recoveringAfterDeathAtHome) {
            return true;
        }
        if (treeHarvestController.isTreeChopInProgress()) {
            return true;
        }
        return this.taskCoordinator.handlePlayerMessage(player, message);
    }

    public boolean handleBuildPointClick(ServerPlayer player, BlockPos clickedPos) {
        if (player == null || clickedPos == null || recoveringAfterDeathAtHome) {
            return false;
        }
        return this.taskCoordinator.handleBuildPointClick(player, clickedPos, this.level().getGameTime());
    }

    public boolean handleThanks(ServerPlayer player, String message) {
        return this.gratitudeResponder.handle(player, message, this.level().getGameTime());
    }

    public void markStopCommand(ServerPlayer player) {
        if (player == null) {
            return;
        }
        lastStopPlayerId = player.getUUID();
        stopHomeTriggered = false;
    }

    public boolean handleSetHome(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        if (!canPlayerControl(player)) {
            return true;
        }
        long gameTime = this.level().getGameTime();
        tickSetHomeCooldown(gameTime, player);
        if (!homeSetFreeRadiusActive && homeSetCooldownUntilTick >= 0L && gameTime < homeSetCooldownUntilTick) {
            int secondsLeft = secondsLeft(homeSetCooldownUntilTick, gameTime);
            int minutes = secondsLeft / 60;
            int seconds = secondsLeft % 60;
            sendReply(player, Component.translatable(HOME_SET_COOLDOWN_KEY, minutes, seconds));
            return true;
        }
        BlockHitResult hit = findLookedAtBlock(player, HOME_SET_RANGE);
        if (hit == null || hit.getDirection() != Direction.UP) {
            sendReply(player, Component.translatable(HOME_LOOK_DOWN_KEY));
            return true;
        }
        homePos = hit.getBlockPos().immutable();
        homeDimension = this.level().dimension().location();
        if (!homeSetFreeRadiusActive || homeSetAnchorPos == null || homeSetAnchorDimension == null) {
            homeSetAnchorPos = homePos;
            homeSetAnchorDimension = homeDimension;
            homeSetFreeRadiusActive = true;
        }
        if (homeSetCooldownUntilTick < 0L || gameTime >= homeSetCooldownUntilTick) {
            homeSetCooldownUntilTick = gameTime + HOME_SET_COOLDOWN_TICKS;
        }
        sendReply(player, Component.translatable(HOME_SET_KEY, homePos.getX(), homePos.getY(), homePos.getZ()));
        if (waitingForHomeRespawn && !this.isAlive()) {
            reviveAfterDeath();
        }
        return true;
    }

    public boolean handleDeleteHome(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        if (!canPlayerControl(player)) {
            return true;
        }
        if (homePos == null) {
            sendReply(player, Component.translatable(HOME_MISSING_KEY));
            return true;
        }
        homePos = null;
        homeDimension = null;
        returningHome = false;
        homeLeaveTarget = null;
        homeReturnPlayerId = null;
        recoveringAfterDeathAtHome = false;
        waitingForHomeRespawn = false;
        clearSetHomeCooldownState();
        clearHomeRequest();
        sendReply(player, Component.translatable(HOME_DELETED_KEY));
        return true;
    }

    public boolean handleHomeConfirmation(ServerPlayer player, boolean accepted) {
        if (player == null) {
            return false;
        }
        if (pendingHomePlayerId == null || !pendingHomePlayerId.equals(player.getUUID())) {
            return false;
        }
        long gameTime = getServerTick();
        if (gameTime >= pendingHomeUntilTick) {
            sendTimedMessageRemoval(TELEPORT_IGNORE_HOME_KEY, pendingHomePlayerId);
            clearHomeRequest();
            return false;
        }
        sendTimedMessageRemoval(TELEPORT_IGNORE_HOME_KEY, pendingHomePlayerId);
        if (!accepted) {
            clearHomeRequest();
            return true;
        }
        clearHomeRequest();
        startHomeReturn(player);
        return true;
    }

    public boolean handleBoatRideConfirmation(ServerPlayer player, boolean accepted) {
        if (player == null) {
            return false;
        }
        if (pendingBoatPlayerId == null || !pendingBoatPlayerId.equals(player.getUUID())) {
            return false;
        }
        long gameTime = this.level().getGameTime();
        if (gameTime >= pendingBoatUntilTick) {
            clearBoatRideRequest(true);
            nextBoatRequestTick = gameTime + BOAT_REQUEST_COOLDOWN_TICKS;
            return false;
        }
        clearBoatRideRequest(true);
        nextBoatRequestTick = gameTime + BOAT_REQUEST_COOLDOWN_TICKS;
        if (!accepted || player.level() != this.level()) {
            return true;
        }
        if (!(player.getVehicle() instanceof Boat boat)) {
            return true;
        }
        tryBoardBoat(boat);
        return true;
    }

    public void requestDimensionTeleport(ServerPlayer player) {
        if (player == null || player.server == null || !this.isAlive()) {
            return;
        }
        if (!canPlayerControl(player) || recoveringAfterDeathAtHome) {
            return;
        }
        if (player.level().dimension().equals(this.level().dimension())) {
            return;
        }
        PendingTeleportRequest existing = PENDING_TELEPORT_REQUESTS.get(player.getUUID());
        if (existing != null
                && this.getUUID().equals(existing.companionId)
                && DIMENSION_TELEPORT_REQUEST_KEY.equals(existing.messageKey)) {
            return;
        }
        PENDING_TELEPORT_REQUESTS.remove(player.getUUID());
        PENDING_TELEPORTS.remove(player.getUUID());
        long gameTime = getServerTick();
        long untilTick = gameTime + DIMENSION_TELEPORT_TICKS;
        int secondsLeft = secondsLeftStatic(untilTick, gameTime);
        if (!registerPendingTeleportRequest(player, DIMENSION_TELEPORT_REQUEST_KEY, untilTick, secondsLeft)) {
            return;
        }
        sendDimensionTeleportMessage(player, secondsLeft);
    }

    public boolean handleGoHomeCommand(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        if (!canPlayerControl(player)) {
            return true;
        }
        if (treeHarvestController.isTreeChopInProgress()) {
            return true;
        }
        if (!isHomeInCurrentLevel()) {
            sendReply(player, Component.translatable(HOME_MISSING_KEY));
            return true;
        }
        if (isAtHome()) {
            return true;
        }
        if (returningHome) {
            return true;
        }
        int distance = homeDistance();
        if (distance > HOME_WARN_DISTANCE) {
            requestHomeConfirmation(player, distance);
            return true;
        }
        startHomeReturn(player);
        return true;
    }

    public boolean handleWhereCommand(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        if (!canPlayerControl(player)) {
            return true;
        }
        if (this.distanceToSqr(player) <= (double) WHERE_MIN_DISTANCE * WHERE_MIN_DISTANCE) {
            return true;
        }
        long gameTime = getServerTick();
        Long lastTick = whereCooldowns.get(player.getUUID());
        if (lastTick != null && gameTime - lastTick < WHERE_COOLDOWN_TICKS) {
            return true;
        }
        whereCooldowns.put(player.getUUID(), gameTime);
        requestWhereTeleport(player, gameTime);
        return true;
    }

    public static boolean handleWhereCommandFallback(ServerPlayer player) {
        if (player == null || player.server == null) {
            return false;
        }
        CompanionSingleNpcManager.ensureLoaded(player.server);
        UUID companionId = CompanionSingleNpcManager.getActiveId();
        ResourceKey<Level> levelKey = CompanionSingleNpcManager.getActiveDimension();
        BlockPos lastPos = CompanionSingleNpcManager.getLastKnownPos();
        if (companionId == null || levelKey == null || lastPos == null) {
            return false;
        }
        ServerLevel level = player.server.getLevel(levelKey);
        if (level == null) {
            return false;
        }
        double distanceSqr = player.distanceToSqr(
                lastPos.getX() + 0.5D, lastPos.getY() + 0.5D, lastPos.getZ() + 0.5D);
        if (distanceSqr <= (double) WHERE_MIN_DISTANCE * WHERE_MIN_DISTANCE) {
            return true;
        }
        long gameTime = player.server.getTickCount();
        Long lastTick = WHERE_FALLBACK_COOLDOWNS.get(player.getUUID());
        if (lastTick != null && gameTime - lastTick < WHERE_COOLDOWN_TICKS) {
            return true;
        }
        WHERE_FALLBACK_COOLDOWNS.put(player.getUUID(), gameTime);
        long untilTick = gameTime + WHERE_TELEPORT_TICKS;
        int secondsLeft = secondsLeftStatic(untilTick, gameTime);
        PENDING_TELEPORT_REQUESTS.remove(player.getUUID());
        PENDING_TELEPORTS.remove(player.getUUID());
        registerPendingTeleportRequest(player, companionId, levelKey, lastPos, WHERE_STATUS_KEY, untilTick, secondsLeft);
        sendWhereMessageFallback(player, lastPos, secondsLeft);
        return true;
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

    private boolean isOwnerPlayer(Player player) {
        return player != null && ownerId != null && ownerId.equals(player.getUUID());
    }

    private ServerPlayer resolveAttackingPlayer(DamageSource source) {
        if (source == null) {
            return null;
        }
        Entity attacker = source.getEntity();
        if (attacker instanceof ServerPlayer serverPlayer) {
            return serverPlayer;
        }
        Entity direct = source.getDirectEntity();
        if (direct instanceof ServerPlayer serverPlayer) {
            return serverPlayer;
        }
        return null;
    }

    private void registerHostilePlayerHit(ServerPlayer player, long gameTime) {
        if (player == null || player.isSpectator() || !player.isAlive()) {
            return;
        }
        if (recoveringAfterDeathAtHome) {
            return;
        }
        UUID playerId = player.getUUID();
        int strike = hostilePlayerStrikes.getOrDefault(playerId, 0) + 1;
        hostilePlayerStrikes.put(playerId, strike);
        int hitsToDeal;
        if (strike <= 1) {
            hitsToDeal = 1;
        } else if (strike == 2) {
            hitsToDeal = HOSTILE_PLAYER_SECOND_HIT_COUNT;
        } else {
            hitsToDeal = -1;
        }
        startHostilePlayerRetaliation(playerId, hitsToDeal, gameTime);
    }

    private void startHostilePlayerRetaliation(UUID targetId, int hitsToDeal, long gameTime) {
        if (targetId == null) {
            return;
        }
        hostilePlayerTargetId = targetId;
        if (hitsToDeal < 0) {
            hostilePlayerHitsRemaining = -1;
        } else {
            hostilePlayerHitsRemaining = hitsToDeal;
        }
        hostilePlayerNextAttackTick = gameTime;
        resetHostilePlayerMoveTracking();
        if (returningHome) {
            returningHome = false;
            homeLeaveTarget = null;
            homeReturnPlayerId = null;
        }
        if (this.mode == CompanionMode.STOPPED) {
            setMode(CompanionMode.AUTONOMOUS);
        }
    }

    private void clearHostilePlayerRetaliationState() {
        hostilePlayerTargetId = null;
        hostilePlayerHitsRemaining = 0;
        hostilePlayerNextAttackTick = -1L;
        resetHostilePlayerMoveTracking();
        this.setTarget(null);
    }

    private void clearHostilePlayerMemory() {
        hostilePlayerStrikes.clear();
        clearHostilePlayerRetaliationState();
    }

    private boolean tickHostilePlayerRetaliation(long gameTime) {
        if (hostilePlayerTargetId == null || !this.isAlive()) {
            return false;
        }
        if (hostilePlayerHitsRemaining == 0) {
            clearHostilePlayerRetaliationState();
            return false;
        }
        Player targetPlayer = getPlayerById(hostilePlayerTargetId);
        if (!(targetPlayer instanceof ServerPlayer serverPlayer)
                || serverPlayer.isSpectator()
                || !serverPlayer.isAlive()) {
            clearHostilePlayerRetaliationState();
            return false;
        }
        this.equipment.equipBestWeapon();
        this.setTarget(serverPlayer);
        Vec3 targetPos = serverPlayer.position();
        double distanceSqr = this.distanceToSqr(targetPos);
        if (distanceSqr > HOSTILE_PLAYER_ATTACK_RANGE_SQR) {
            if (shouldIssueHostilePlayerMove(targetPos, gameTime)) {
                this.getNavigation().moveTo(serverPlayer, hostilePlayerNavSpeed(HOSTILE_PLAYER_CHASE_SPEED));
                rememberHostilePlayerMove(targetPos, gameTime);
            }
            return true;
        }
        this.getNavigation().stop();
        this.getLookControl().setLookAt(serverPlayer, 30.0F, 30.0F);
        if (gameTime < hostilePlayerNextAttackTick) {
            return true;
        }
        boolean hit = performCriticalRetaliationHit(serverPlayer);
        hostilePlayerNextAttackTick = gameTime + HOSTILE_PLAYER_ATTACK_COOLDOWN_TICKS;
        if (hit && hostilePlayerHitsRemaining > 0) {
            hostilePlayerHitsRemaining--;
            if (hostilePlayerHitsRemaining == 0) {
                clearHostilePlayerRetaliationState();
            }
        }
        return true;
    }

    private boolean shouldIssueHostilePlayerMove(Vec3 targetPos, long gameTime) {
        if (targetPos == null) {
            return false;
        }
        BlockPos blockPos = BlockPos.containing(targetPos);
        if (hostilePlayerLastPathTarget == null || !hostilePlayerLastPathTarget.equals(blockPos)) {
            return true;
        }
        if (!this.getNavigation().isDone()) {
            return false;
        }
        return hostilePlayerLastPathTick < 0L || gameTime - hostilePlayerLastPathTick >= HOSTILE_PLAYER_REPATH_TICKS;
    }

    private void rememberHostilePlayerMove(Vec3 targetPos, long gameTime) {
        hostilePlayerLastPathTarget = targetPos != null ? BlockPos.containing(targetPos) : null;
        hostilePlayerLastPathTick = gameTime;
    }

    private void resetHostilePlayerMoveTracking() {
        hostilePlayerLastPathTarget = null;
        hostilePlayerLastPathTick = -1L;
    }

    private double hostilePlayerNavSpeed(double desiredSpeed) {
        return CompanionMovementSpeed.fallbackDesiredByAttribute(this, desiredSpeed);
    }

    private boolean performCriticalRetaliationHit(Player target) {
        return performCriticalMobHit(target);
    }

    public boolean performCriticalMobHit(LivingEntity target) {
        if (target == null) {
            return false;
        }
        this.swing(InteractionHand.MAIN_HAND, true);
        boolean hit = this.doHurtTarget(target);
        if (hit && this.level() instanceof ServerLevel serverLevel) {
            float baseDamage = (float) this.getAttributeValue(Attributes.ATTACK_DAMAGE);
            float bonusDamage = Math.max(1.0F, baseDamage * 0.5F);
            target.hurt(this.damageSources().mobAttack(this), bonusDamage);
            serverLevel.sendParticles(
                    ParticleTypes.CRIT,
                    target.getX(),
                    target.getY() + target.getBbHeight() * 0.5D,
                    target.getZ(),
                    10,
                    0.25D,
                    0.25D,
                    0.25D,
                    0.05D
            );
        }
        return hit;
    }

    private BlockHitResult findLookedAtBlock(Player player, double range) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle().normalize();
        Vec3 end = eye.add(look.scale(range));
        BlockHitResult hit = player.level().clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, player));
        if (hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        return hit;
    }

    private boolean isHomeInCurrentLevel() {
        return homePos != null && homeDimension != null
                && this.level().dimension().location().equals(homeDimension);
    }

    private boolean isAtHome() {
        return isHomeInCurrentLevel() && this.blockPosition().below().equals(homePos);
    }

    private void requestHomeConfirmation(ServerPlayer player, int distance) {
        long gameTime = this.level().getGameTime();
        pendingHomePlayerId = player.getUUID();
        pendingHomeUntilTick = gameTime + HOME_CONFIRM_TICKS;
        pendingHomeLastSeconds = -1;
        pendingHomeDistance = distance;
        int secondsLeft = secondsLeft(pendingHomeUntilTick, gameTime);
        sendHomeConfirmMessage(player, secondsLeft);
        pendingHomeLastSeconds = secondsLeft;
    }

    private void sendHomeConfirmMessage(ServerPlayer player, int secondsLeft) {
        Component distance = Component.translatable(HOME_DISTANCE_KEY, Math.max(0, pendingHomeDistance));
        MutableComponent base = Component.translatable(HOME_CONFIRM_KEY, distance, secondsLeft);
        Component button = Component.translatable(HOME_CONFIRM_BUTTON_KEY)
                .withStyle(style -> style.withColor(ChatFormatting.GREEN)
                        .withBold(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ainpc home yes")));
        sendReply(player, base.append(Component.literal(" ")).append(button));
    }

    private void requestFollowConfirmation(ServerPlayer player, long gameTime) {
        clearFollowRequest(false);
        pendingFollowPlayerId = player.getUUID();
        pendingFollowUntilTick = gameTime + HOME_FOLLOW_TICKS;
        pendingFollowLastSeconds = -1;
        int secondsLeft = secondsLeft(pendingFollowUntilTick, gameTime);
        sendFollowMessage(player, secondsLeft);
        pendingFollowLastSeconds = secondsLeft;
    }

    private void sendFollowMessage(ServerPlayer player, int secondsLeft) {
        MutableComponent base = Component.translatable(HOME_FOLLOW_KEY, secondsLeft);
        Component button = Component.translatable(HOME_FOLLOW_BUTTON_KEY)
                .withStyle(style -> style.withColor(ChatFormatting.AQUA)
                        .withBold(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ainpc msg \u0421\u041b\u0415\u0414\u0423\u0419")));
        sendReply(player, base.append(Component.literal(" ")).append(button));
    }

    private void requestBoatRide(ServerPlayer player, long gameTime) {
        clearBoatRideRequest(false);
        pendingBoatPlayerId = player.getUUID();
        pendingBoatUntilTick = gameTime + BOAT_REQUEST_TICKS;
        pendingBoatLastSeconds = -1;
        int secondsLeft = secondsLeft(pendingBoatUntilTick, gameTime);
        sendBoatRideMessage(player, secondsLeft);
        pendingBoatLastSeconds = secondsLeft;
    }

    private void sendBoatRideMessage(ServerPlayer player, int secondsLeft) {
        MutableComponent base = Component.translatable(BOAT_REQUEST_KEY, secondsLeft);
        Component button = Component.translatable(BOAT_REQUEST_BUTTON_KEY)
                .withStyle(style -> style.withColor(ChatFormatting.AQUA)
                        .withBold(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ainpc boat yes")));
        sendReply(player, base.append(Component.literal(" ")).append(button));
    }

    private void clearBoatRideRequest(boolean notifyRemove) {
        if (pendingBoatPlayerId != null && notifyRemove) {
            sendTimedMessageRemoval(TELEPORT_IGNORE_BOAT_KEY, pendingBoatPlayerId);
        }
        pendingBoatPlayerId = null;
        pendingBoatUntilTick = -1L;
        pendingBoatLastSeconds = -1;
    }

    private void clearHomeRequest() {
        pendingHomePlayerId = null;
        pendingHomeUntilTick = -1L;
        pendingHomeLastSeconds = -1;
        pendingHomeDistance = 0;
    }

    private void requestWhereTeleport(ServerPlayer player, long gameTime) {
        clearWhereRequest();
        pendingWherePlayerId = player.getUUID();
        pendingWhereUntilTick = gameTime + WHERE_TELEPORT_TICKS;
        pendingWhereLastSeconds = -1;
        removePendingTeleportRequest(player.getUUID(), this.getUUID());
        PENDING_TELEPORTS.remove(player.getUUID());
        int secondsLeft = secondsLeft(pendingWhereUntilTick, gameTime);
        registerPendingTeleportRequest(player, WHERE_STATUS_KEY, pendingWhereUntilTick, secondsLeft);
        sendWhereMessage(player, secondsLeft);
        pendingWhereLastSeconds = secondsLeft;
    }

    private void sendWhereMessage(ServerPlayer player, int secondsLeft) {
        BlockPos pos = this.blockPosition();
        MutableComponent base = Component.translatable(WHERE_STATUS_KEY, pos.getX(), pos.getY(), pos.getZ(), secondsLeft);
        Component button = Component.translatable(WHERE_TELEPORT_BUTTON_KEY)
                .withStyle(style -> style.withColor(ChatFormatting.AQUA)
                        .withBold(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ainpc tp yes")));
        sendReply(player, base.append(Component.literal(" ")).append(button));
    }

    private static void sendWhereMessageFallback(ServerPlayer player, BlockPos pos, int secondsLeft) {
        if (player == null || pos == null) {
            return;
        }
        MutableComponent base = Component.translatable(WHERE_STATUS_KEY, pos.getX(), pos.getY(), pos.getZ(), secondsLeft);
        Component button = Component.translatable(WHERE_TELEPORT_BUTTON_KEY)
                .withStyle(style -> style.withColor(ChatFormatting.AQUA)
                        .withBold(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ainpc tp yes")));
        sendDirectMessageStatic(player, base.append(Component.literal(" ")).append(button));
    }

    private void sendDimensionTeleportMessage(ServerPlayer player, int secondsLeft) {
        sendDimensionTeleportMessageStatic(player, secondsLeft);
    }

    private static void sendDimensionTeleportMessageStatic(ServerPlayer player, int secondsLeft) {
        if (player == null) {
            return;
        }
        MutableComponent base = Component.translatable(DIMENSION_TELEPORT_REQUEST_KEY, secondsLeft);
        Component button = Component.translatable(DIMENSION_TELEPORT_BUTTON_KEY)
                .withStyle(style -> style.withColor(ChatFormatting.AQUA)
                        .withBold(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ainpc tp yes")));
        sendDirectMessageStatic(player, base.append(Component.literal(" ")).append(button));
    }

    private void clearWhereRequest() {
        if (pendingWherePlayerId != null) {
            removePendingTeleportRequest(pendingWherePlayerId, this.getUUID());
            PENDING_TELEPORTS.remove(pendingWherePlayerId);
            sendTimedMessageRemoval(TELEPORT_IGNORE_WHERE_KEY, pendingWherePlayerId);
        }
        pendingWherePlayerId = null;
        pendingWhereUntilTick = -1L;
        pendingWhereLastSeconds = -1;
    }

    private void clearFollowRequest(boolean notifyRemove) {
        if (pendingFollowPlayerId != null && notifyRemove) {
            sendTimedMessageRemoval(TELEPORT_IGNORE_FOLLOW_KEY, pendingFollowPlayerId);
        }
        pendingFollowPlayerId = null;
        pendingFollowUntilTick = -1L;
        pendingFollowLastSeconds = -1;
    }

    private void sendTimedMessageRemoval(String key, UUID playerId) {
        if (key == null || playerId == null) {
            return;
        }
        Player player = getPlayerById(playerId);
        if (player instanceof ServerPlayer serverPlayer) {
            sendReply(serverPlayer, Component.translatable(key));
        }
    }

    private void tickHomeRequests(long gameTime) {
        tickHomeConfirmation(gameTime);
        tickWhereRequest(gameTime);
        tickFollowRequest(gameTime);
        tickStopHomeReturn(gameTime);
    }

    private void tickHomeConfirmation(long gameTime) {
        if (pendingHomePlayerId == null) {
            return;
        }
        Player player = getPlayerById(pendingHomePlayerId);
        if (!(player instanceof ServerPlayer serverPlayer)) {
            clearHomeRequest();
            return;
        }
        if (gameTime >= pendingHomeUntilTick) {
            sendTimedMessageRemoval(TELEPORT_IGNORE_HOME_KEY, pendingHomePlayerId);
            clearHomeRequest();
            return;
        }
        int secondsLeft = secondsLeft(pendingHomeUntilTick, gameTime);
        if (secondsLeft != pendingHomeLastSeconds) {
            pendingHomeLastSeconds = secondsLeft;
            sendHomeConfirmMessage(serverPlayer, secondsLeft);
        }
    }

    private void tickWhereRequest(long gameTime) {
        if (pendingWherePlayerId == null) {
            return;
        }
        Player player = getPlayerById(pendingWherePlayerId);
        if (!(player instanceof ServerPlayer serverPlayer)) {
            clearWhereRequest();
            return;
        }
        long serverTick = getServerTick();
        if (serverTick >= pendingWhereUntilTick) {
            clearWhereRequest();
            return;
        }
        int secondsLeft = secondsLeft(pendingWhereUntilTick, serverTick);
        if (secondsLeft != pendingWhereLastSeconds) {
            pendingWhereLastSeconds = secondsLeft;
            sendWhereMessage(serverPlayer, secondsLeft);
        }
    }

    private void tickFollowRequest(long gameTime) {
        if (pendingFollowPlayerId == null) {
            return;
        }
        Player player = getPlayerById(pendingFollowPlayerId);
        if (!(player instanceof ServerPlayer serverPlayer)) {
            clearFollowRequest(false);
            return;
        }
        if (gameTime >= pendingFollowUntilTick) {
            clearFollowRequest(true);
            return;
        }
        int secondsLeft = secondsLeft(pendingFollowUntilTick, gameTime);
        if (secondsLeft != pendingFollowLastSeconds) {
            pendingFollowLastSeconds = secondsLeft;
            sendFollowMessage(serverPlayer, secondsLeft);
        }
    }

    private void tickBoatRideRequest(long gameTime) {
        if (this.mode == CompanionMode.STOPPED || returningHome || this.taskCoordinator.isBusy()) {
            clearBoatRideRequest(false);
            return;
        }
        ServerPlayer ownerPlayer = resolveBoatOwner();
        if (ownerPlayer == null || ownerPlayer.level() != this.level()) {
            if (this.isPassenger() && this.getVehicle() instanceof Boat) {
                this.stopRiding();
            }
            clearBoatRideRequest(false);
            return;
        }
        if (!(ownerPlayer.getVehicle() instanceof Boat boat)) {
            if (this.isPassenger() && this.getVehicle() instanceof Boat) {
                this.stopRiding();
            }
            clearBoatRideRequest(true);
            return;
        }
        if (this.isPassenger() && this.getVehicle() instanceof Boat currentBoat && currentBoat != boat) {
            this.stopRiding();
        }
        if (!canBoardBoat(boat)) {
            clearBoatRideRequest(true);
            return;
        }
        if (this.isPassenger() && this.getVehicle() == boat) {
            clearBoatRideRequest(false);
            return;
        }
        if (this.distanceToSqr(boat) > BOAT_REQUEST_DISTANCE_SQR) {
            clearBoatRideRequest(false);
            return;
        }
        if (pendingBoatPlayerId != null) {
            tickPendingBoatRideRequest(gameTime);
            return;
        }
        if (nextBoatRequestTick >= 0L && gameTime < nextBoatRequestTick) {
            return;
        }
        requestBoatRide(ownerPlayer, gameTime);
    }

    private void tickPendingBoatRideRequest(long gameTime) {
        if (pendingBoatPlayerId == null) {
            return;
        }
        Player player = getPlayerById(pendingBoatPlayerId);
        if (!(player instanceof ServerPlayer serverPlayer)) {
            clearBoatRideRequest(false);
            return;
        }
        if (!(serverPlayer.getVehicle() instanceof Boat boat)
                || serverPlayer.level() != this.level()
                || !canBoardBoat(boat)) {
            clearBoatRideRequest(true);
            return;
        }
        if (gameTime >= pendingBoatUntilTick) {
            clearBoatRideRequest(true);
            nextBoatRequestTick = gameTime + BOAT_REQUEST_COOLDOWN_TICKS;
            return;
        }
        int secondsLeft = secondsLeft(pendingBoatUntilTick, gameTime);
        if (secondsLeft != pendingBoatLastSeconds) {
            pendingBoatLastSeconds = secondsLeft;
            sendBoatRideMessage(serverPlayer, secondsLeft);
        }
    }

    private ServerPlayer resolveBoatOwner() {
        if (ownerId == null) {
            return null;
        }
        Player ownerPlayer = getPlayerById(ownerId);
        if (!(ownerPlayer instanceof ServerPlayer serverPlayer) || serverPlayer.isSpectator() || !serverPlayer.isAlive()) {
            return null;
        }
        return serverPlayer;
    }

    private boolean canBoardBoat(Boat boat) {
        if (boat == null || !boat.isAlive() || boat.level() != this.level()) {
            return false;
        }
        if (boat.getPassengers().contains(this)) {
            return true;
        }
        return boat.getPassengers().size() < BOAT_MAX_PASSENGERS;
    }

    private boolean tryBoardBoat(Boat boat) {
        if (!canBoardBoat(boat)) {
            return false;
        }
        if (this.isPassenger() && this.getVehicle() == boat) {
            return true;
        }
        if (this.isPassenger()) {
            this.stopRiding();
        }
        this.getNavigation().stop();
        boolean boarded = this.startRiding(boat, true);
        if (!boarded) {
            this.teleportTo(boat.getX(), boat.getY(), boat.getZ());
            boarded = this.startRiding(boat, true);
        }
        if (boarded) {
            this.getNavigation().stop();
        }
        return boarded;
    }

    private void tickStopHomeReturn(long gameTime) {
        if (returningHome || isAtHome()) {
            return;
        }
        if (this.mode != CompanionMode.STOPPED || stopHomeTriggered || lastStopPlayerId == null) {
            return;
        }
        if (!isHomeInCurrentLevel()) {
            return;
        }
        Player player = getPlayerById(lastStopPlayerId);
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        if (this.distanceToSqr(serverPlayer) < (double) HOME_AUTO_DISTANCE * HOME_AUTO_DISTANCE) {
            return;
        }
        stopHomeTriggered = true;
        sendReply(serverPlayer, Component.translatable(HOME_AUTO_GO_KEY));
        startHomeReturn(serverPlayer);
    }

    private void startHomeReturn(ServerPlayer player) {
        if (!isHomeInCurrentLevel()) {
            if (player != null) {
                sendReply(player, Component.translatable(HOME_MISSING_KEY));
            }
            return;
        }
        if (this.mode != CompanionMode.STOPPED && !setMode(CompanionMode.STOPPED)) {
            return;
        }
        returningHome = true;
        homeReturnPlayerId = player != null ? player.getUUID() : null;
        homeDirectReturn = homeDistance() <= HOME_LEAVE_DISTANCE;
        if (homeDirectReturn) {
            homeLeaveTarget = new Vec3(homePos.getX() + 0.5D, homePos.getY() + 1.0D, homePos.getZ() + 0.5D);
        } else {
            homeLeaveTarget = player != null ? resolveHomeLeaveTarget(player) : null;
        }
        resetHomeMoveTracking();
        if (homeLeaveTarget != null && shouldIssueHomeMoveTo(homeLeaveTarget, this.level().getGameTime())) {
            this.getNavigation().moveTo(homeLeaveTarget.x, homeLeaveTarget.y, homeLeaveTarget.z, navSpeed(HOME_LEAVE_SPEED));
            rememberHomeMoveTo(homeLeaveTarget, this.level().getGameTime());
        }
    }

    private void tickHomeReturn(long gameTime) {
        if (!returningHome) {
            return;
        }
        if (!isHomeInCurrentLevel()) {
            returningHome = false;
            homeLeaveTarget = null;
            homeReturnPlayerId = null;
            homeDirectReturn = false;
            return;
        }
        Player player = homeReturnPlayerId != null ? getPlayerById(homeReturnPlayerId) : null;
        if (isAtHome()) {
            this.getNavigation().stop();
            returningHome = false;
            homeLeaveTarget = null;
            homeReturnPlayerId = null;
            homeDirectReturn = false;
            resetHomeMoveTracking();
            return;
        }
        if (homeLeaveTarget == null && player != null && !homeDirectReturn) {
            homeLeaveTarget = resolveHomeLeaveTarget(player);
        }
        if (homeLeaveTarget != null && shouldIssueHomeMoveTo(homeLeaveTarget, gameTime)) {
            this.getNavigation().moveTo(homeLeaveTarget.x, homeLeaveTarget.y, homeLeaveTarget.z, navSpeed(HOME_LEAVE_SPEED));
            rememberHomeMoveTo(homeLeaveTarget, gameTime);
        }
        if (!(player instanceof ServerPlayer serverPlayer) || serverPlayer.isSpectator() || !serverPlayer.isAlive()) {
            teleportHome();
            this.getNavigation().stop();
            returningHome = false;
            homeLeaveTarget = null;
            homeReturnPlayerId = null;
            homeDirectReturn = false;
            resetHomeMoveTracking();
            return;
        }
        Vec3 playerForward = serverPlayer.getLookAngle();
        playerForward = new Vec3(playerForward.x, 0.0D, playerForward.z);
        if (playerForward.lengthSqr() < 1.0E-4D) {
            float yaw = serverPlayer.getYRot() * ((float) Math.PI / 180.0F);
            playerForward = new Vec3(-Mth.sin(yaw), 0.0D, Mth.cos(yaw));
        }
        playerForward = playerForward.normalize();
        if (isOutOfPlayerView(serverPlayer.position(), playerForward, this.position())) {
            Vec3 homeCenter = new Vec3(homePos.getX() + 0.5D, homePos.getY() + 1.0D, homePos.getZ() + 0.5D);
            double distanceToHomeBeforeTeleport = this.position().distanceToSqr(homeCenter);
            teleportHome();
            this.getNavigation().stop();
            returningHome = false;
            homeLeaveTarget = null;
            homeReturnPlayerId = null;
            homeDirectReturn = false;
            resetHomeMoveTracking();
            if (distanceToHomeBeforeTeleport > 4.0D) {
                sendReply(serverPlayer, Component.translatable(HOME_INTERACT_KEY));
            }
        }
    }

    private Vec3 resolveHomeLeaveTarget(Player player) {
        Vec3 playerPos = player.position();
        Vec3 direction = this.position().subtract(playerPos);
        direction = new Vec3(direction.x, 0.0D, direction.z);
        if (direction.lengthSqr() < 1.0E-4D) {
            Vec3 look = player.getLookAngle();
            direction = new Vec3(-look.x, 0.0D, -look.z);
        }
        if (direction.lengthSqr() < 1.0E-4D) {
            direction = new Vec3(1.0D, 0.0D, 0.0D);
        }
        direction = direction.normalize();
        Vec3 target = playerPos.add(direction.scale(HOME_LEAVE_DISTANCE));
        target = new Vec3(target.x, this.getY(), target.z);
        Vec3 adjusted = adjustTeleportY(target);
        return adjusted != null ? adjusted : target;
    }

    private double navSpeed(double desiredSpeed) {
        return CompanionMovementSpeed.fallbackDesiredByAttribute(this, desiredSpeed);
    }

    private boolean shouldIssueHomeMoveTo(Vec3 target, long gameTime) {
        if (target == null) {
            return false;
        }
        BlockPos targetBlock = BlockPos.containing(target);
        if (lastHomeMoveTarget == null || !lastHomeMoveTarget.equals(targetBlock)) {
            return true;
        }
        if (!this.getNavigation().isDone()) {
            return false;
        }
        return lastHomeMoveAttemptTick < 0L || gameTime - lastHomeMoveAttemptTick >= HOME_MOVE_RETRY_TICKS;
    }

    private void rememberHomeMoveTo(Vec3 target, long gameTime) {
        lastHomeMoveTarget = target != null ? BlockPos.containing(target) : null;
        lastHomeMoveAttemptTick = gameTime;
    }

    private void resetHomeMoveTracking() {
        lastHomeMoveTarget = null;
        lastHomeMoveAttemptTick = -1L;
    }

    private void tickSetHomeCooldown(long gameTime, Player referencePlayer) {
        if (homeSetAnchorPos == null || homeSetAnchorDimension == null) {
            return;
        }
        Player trackedPlayer = referencePlayer;
        if (!(trackedPlayer instanceof ServerPlayer) && ownerId != null) {
            trackedPlayer = getPlayerById(ownerId);
        }
        if (!(trackedPlayer instanceof ServerPlayer)) {
            return;
        }
        boolean insideFreeRadius = trackedPlayer.level().dimension().location().equals(homeSetAnchorDimension)
                && trackedPlayer.blockPosition().distSqr(homeSetAnchorPos) <= HOME_SET_FREE_RADIUS_SQR;
        if (insideFreeRadius) {
            homeSetFreeRadiusActive = true;
            return;
        }
        if (homeSetFreeRadiusActive) {
            homeSetFreeRadiusActive = false;
            if (homeSetCooldownUntilTick < 0L) {
                homeSetCooldownUntilTick = gameTime + HOME_SET_COOLDOWN_TICKS;
            }
        }
    }

    private void clearSetHomeCooldownState() {
        homeSetCooldownUntilTick = -1L;
        homeSetAnchorPos = null;
        homeSetAnchorDimension = null;
        homeSetFreeRadiusActive = false;
    }

    private void teleportHome() {
        Vec3 target = new Vec3(homePos.getX() + 0.5D, homePos.getY() + 1.0D, homePos.getZ() + 0.5D);
        this.teleportTo(target.x, target.y, target.z);
        this.getNavigation().stop();
    }

    private int homeDistance() {
        Vec3 target = new Vec3(homePos.getX() + 0.5D, homePos.getY() + 0.5D, homePos.getZ() + 0.5D);
        return (int) Math.round(this.position().distanceTo(target));
    }

    private int secondsLeft(long untilTick, long gameTime) {
        long remaining = Math.max(0L, untilTick - gameTime);
        return (int) Math.ceil(remaining / 20.0D);
    }

    private static int secondsLeftStatic(long untilTick, long gameTime) {
        long remaining = Math.max(0L, untilTick - gameTime);
        return (int) Math.ceil(remaining / 20.0D);
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

    @Override
    public float getAttackAnim(float partialTicks) {
        if (!this.swinging) {
            return 0.0F;
        }
        float swingTicks = this.swingTime + partialTicks;
        float progress = swingTicks / (float) SWING_DURATION_TICKS;
        return Mth.clamp(progress, 0.0F, 1.0F);
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
        if (this.homePos != null && this.homeDimension != null) {
            tag.putLong(HOME_POS_NBT, this.homePos.asLong());
            tag.putString(HOME_DIM_NBT, this.homeDimension.toString());
        }
        if (this.homeSetCooldownUntilTick >= 0L) {
            tag.putLong(HOME_SET_COOLDOWN_UNTIL_NBT, this.homeSetCooldownUntilTick);
        }
        if (this.homeSetAnchorPos != null && this.homeSetAnchorDimension != null) {
            tag.putLong(HOME_SET_ANCHOR_POS_NBT, this.homeSetAnchorPos.asLong());
            tag.putString(HOME_SET_ANCHOR_DIM_NBT, this.homeSetAnchorDimension.toString());
        }
        tag.putBoolean(HOME_SET_FREE_RADIUS_ACTIVE_NBT, this.homeSetFreeRadiusActive);
        tag.putBoolean(HOME_RESPAWN_WAITING_NBT, this.waitingForHomeRespawn);
        tag.putBoolean(HOME_DEATH_RECOVERY_NBT, this.recoveringAfterDeathAtHome);
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
        this.homePos = null;
        this.homeDimension = null;
        if (tag.contains(HOME_POS_NBT) && tag.contains(HOME_DIM_NBT)) {
            ResourceLocation dim = ResourceLocation.tryParse(tag.getString(HOME_DIM_NBT));
            if (dim != null) {
                this.homePos = BlockPos.of(tag.getLong(HOME_POS_NBT));
                this.homeDimension = dim;
            }
        }
        this.homeSetCooldownUntilTick = tag.contains(HOME_SET_COOLDOWN_UNTIL_NBT)
                ? tag.getLong(HOME_SET_COOLDOWN_UNTIL_NBT)
                : -1L;
        this.homeSetAnchorPos = null;
        this.homeSetAnchorDimension = null;
        if (tag.contains(HOME_SET_ANCHOR_POS_NBT) && tag.contains(HOME_SET_ANCHOR_DIM_NBT)) {
            ResourceLocation anchorDim = ResourceLocation.tryParse(tag.getString(HOME_SET_ANCHOR_DIM_NBT));
            if (anchorDim != null) {
                this.homeSetAnchorPos = BlockPos.of(tag.getLong(HOME_SET_ANCHOR_POS_NBT));
                this.homeSetAnchorDimension = anchorDim;
            }
        }
        this.homeSetFreeRadiusActive = tag.contains(HOME_SET_FREE_RADIUS_ACTIVE_NBT)
                && tag.getBoolean(HOME_SET_FREE_RADIUS_ACTIVE_NBT);
        if (this.homeSetAnchorPos == null || this.homeSetAnchorDimension == null) {
            this.homeSetFreeRadiusActive = false;
        }
        this.waitingForHomeRespawn = tag.contains(HOME_RESPAWN_WAITING_NBT)
                && tag.getBoolean(HOME_RESPAWN_WAITING_NBT);
        this.recoveringAfterDeathAtHome = tag.contains(HOME_DEATH_RECOVERY_NBT)
                && tag.getBoolean(HOME_DEATH_RECOVERY_NBT);
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
        if (this.getHealth() > 0.0F && (this.dead || this.deathTime > 0)) {
            this.dead = false;
            this.deathTime = 0;
        }
        if (!this.level().isClientSide) {
            long gameTime = this.level().getGameTime();
            Player nearest = this.level().getNearestPlayer(this, FOLLOW_SEARCH_DISTANCE);
            CompanionSingleNpcManager.updateState(this, this.taskCoordinator.isBusy(),
                    this.lastTeleportCycleTick, this.lastTeleportOriginalTick);
            tickSetHomeCooldown(gameTime, null);
            boolean atHome = isAtHome();
            boolean wasAtHomeBeforeTick = wasAtHome;
            if (atHome && !wasAtHomeBeforeTick) {
                lastHomeInteractTick = -10000L;
                clearFollowRequest(false);
            } else if (!atHome && wasAtHomeBeforeTick) {
                clearFollowRequest(true);
            }
            wasAtHome = atHome;
            if (!recoveringAfterDeathAtHome
                    && this.mode == CompanionMode.STOPPED
                    && !returningHome
                    && isHomeInCurrentLevel()
                    && (atHome || wasAtHomeBeforeTick)) {
                if (!atHome) {
                    teleportHome();
                    atHome = true;
                    wasAtHome = true;
                }
                lockToHomePosition();
            }
            if (recoveringAfterDeathAtHome) {
                if (isHomeInCurrentLevel() && !isAtHome()) {
                    teleportHome();
                }
                this.returningHome = false;
                lockToHomePosition();
                tickHomeRegen(gameTime);
                syncHungerFullFlag();
                tickAmbientChat();
                return;
            }
            tickGreeting();
            tickChestStatus();
            tickItemPickup();
            inventoryExchange.tickToolDropNotice(gameTime);
            tickHomeRequests(gameTime);
            boolean urgentOwnerDefense = tryUrgentOwnerDefense(gameTime);
            if (urgentOwnerDefense) {
                // Urgent owner protection has priority over any current activity.
            } else if (returningHome) {
                tickHomeReturn(gameTime);
            } else {
                tickAutonomousBehavior();
            }
            this.hungerSystem.tick(nearest, gameTime);
            tickHomeRegen(gameTime);
            syncHungerFullFlag();
            tickAmbientChat();
            tickBoatRideRequest(gameTime);
            hideSwordWhileInBoat();
            tickTeleportRequest();
        }
    }

    @Override
    public boolean isPushable() {
        if (isHomePositionLocked()) {
            return false;
        }
        return super.isPushable();
    }

    @Override
    public void push(double x, double y, double z) {
        if (isHomePositionLocked()) {
            return;
        }
        super.push(x, y, z);
    }

    private boolean isHomePositionLocked() {
        if (!isHomeInCurrentLevel()) {
            return false;
        }
        if (recoveringAfterDeathAtHome) {
            return true;
        }
        return this.mode == CompanionMode.STOPPED
                && !returningHome
                && (isAtHome() || wasAtHome);
    }

    private void lockToHomePosition() {
        if (!isHomeInCurrentLevel()) {
            return;
        }
        Vec3 homeCenter = new Vec3(homePos.getX() + 0.5D, homePos.getY() + 1.0D, homePos.getZ() + 0.5D);
        if (this.position().distanceToSqr(homeCenter) > 0.0025D) {
            this.teleportTo(homeCenter.x, homeCenter.y, homeCenter.z);
        }
        this.getNavigation().stop();
        this.setDeltaMovement(0.0D, this.getDeltaMovement().y, 0.0D);
    }

    @Override
    public void swing(InteractionHand hand, boolean fromServerPlayer) {
        boolean wasSwinging = this.swinging;
        super.swing(hand, fromServerPlayer);
        if (!this.level().isClientSide && !wasSwinging) {
            this.level().broadcastEntityEvent(this, (byte) 4);
        }
    }

    @Override
    public void handleEntityEvent(byte id) {
        super.handleEntityEvent(id);
        if (id == 4 && !this.swinging) {
            this.swinging = true;
            this.swingTime = 0;
        }
    }

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
        if (!this.level().isClientSide && isAtHome() && player instanceof ServerPlayer serverPlayer) {
            if (recoveringAfterDeathAtHome) {
                long gameTime = this.level().getGameTime();
                if (gameTime - this.lastHomeDeathInteractTick >= HOME_DEATH_INTERACT_COOLDOWN_TICKS) {
                    this.lastHomeDeathInteractTick = gameTime;
                    int currentHp = Mth.ceil(Math.max(0.0F, this.getHealth()));
                    int maxHp = Mth.ceil(this.getMaxHealth());
                    sendReply(serverPlayer, Component.translatable(HOME_DEATH_RECOVERY_HP_REMOVE_KEY));
                    sendReply(serverPlayer, Component.translatable(HOME_DEATH_RECOVERY_HP_KEY, currentHp, maxHp));
                }
                return InteractionResult.SUCCESS;
            }
            long gameTime = this.level().getGameTime();
            if (gameTime - this.lastHomeInteractTick >= HOME_INTERACT_COOLDOWN_TICKS) {
                this.lastHomeInteractTick = gameTime;
                requestFollowConfirmation(serverPlayer, gameTime);
            }
            return InteractionResult.SUCCESS;
        }
        return super.mobInteract(player, hand);
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (source != null && source.is(DamageTypes.IN_WALL)) {
            return false;
        }
        if (!this.level().isClientSide) {
            ServerPlayer attackingPlayer = resolveAttackingPlayer(source);
            if (attackingPlayer != null && isOwnerPlayer(attackingPlayer)) {
                return false;
            }
            if (attackingPlayer != null) {
                registerHostilePlayerHit(attackingPlayer, this.level().getGameTime());
            }
        }
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
        clearHomeRequest();
        clearWhereRequest();
        clearFollowRequest(false);
        clearBoatRideRequest(true);
        super.die(source);
    }

    @Override
    protected void tickDeath() {
        if (permanentDeathOnNextDeath) {
            super.tickDeath();
            return;
        }
        if (this.deathTime < 20) {
            this.deathTime++;
        }
        if (this.deathTime < 20) {
            return;
        }
        if (this.level().isClientSide) {
            if (this.getHealth() > 0.0F) {
                this.dead = false;
                this.deathTime = 0;
                this.waitingForHomeRespawn = false;
                this.recoveringAfterDeathAtHome = false;
            } else {
                this.dead = true;
                this.deathTime = 20;
            }
            return;
        }
        if (!isHomeInCurrentLevel()) {
            if (!waitingForHomeRespawn) {
                waitingForHomeRespawn = true;
                notifyNoHomeRespawnHint();
            }
            this.dead = true;
            this.deathTime = 20;
            this.setDeltaMovement(Vec3.ZERO);
            this.getNavigation().stop();
            return;
        }
        reviveAfterDeath();
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
            clearHomeRequest();
            clearWhereRequest();
            clearFollowRequest(false);
            clearBoatRideRequest(true);
        }
        CompanionSingleNpcManager.unregister(this);
        super.remove(reason);
    }

    void markPermanentDeathOnNextDeath() {
        this.permanentDeathOnNextDeath = true;
    }

    private void reviveAfterDeath() {
        if (!isHomeInCurrentLevel()) {
            return;
        }
        teleportHome();
        this.dead = false;
        this.deathTime = 0;
        this.setHealth(1.0F);
        this.setDeltaMovement(Vec3.ZERO);
        this.clearFire();
        this.fallDistance = 0.0F;
        this.getNavigation().stop();
        this.invulnerableTime = 20;
        this.permanentDeathOnNextDeath = false;
        this.waitingForHomeRespawn = false;
        this.recoveringAfterDeathAtHome = true;
        clearHostilePlayerMemory();
        this.setMode(CompanionMode.STOPPED);
    }

    private void notifyNoHomeRespawnHint() {
        Component hint = Component.translatable(HOME_DEATH_NO_HOME_KEY);
        if (ownerId != null) {
            Player owner = getPlayerById(ownerId);
            if (owner != null) {
                sendReply(owner, hint);
                return;
            }
        }
        Player nearest = this.level().getNearestPlayer(this, REACTION_RANGE);
        if (nearest != null) {
            sendReply(nearest, hint);
        }
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

    private static void sendDirectMessageStatic(Player player, Component message) {
        if (player == null || message == null) {
            return;
        }
        Component fullMessage = Component.literal(DISPLAY_NAME + ": ").append(message);
        player.sendSystemMessage(fullMessage);
    }

    private long getServerTick() {
        MinecraftServer server = this.level().getServer();
        if (server != null) {
            return server.getTickCount();
        }
        return this.level().getGameTime();
    }

    private void tickItemPickup() {
        long gameTime = this.level().getGameTime();
        if (this.nextItemPickupTick >= 0L && gameTime < this.nextItemPickupTick) {
            return;
        }
        this.nextItemPickupTick = gameTime + ITEM_PICKUP_COOLDOWN_TICKS;
        AABB range = this.getBoundingBox().inflate(ITEM_PICKUP_RADIUS);
        if (inventory.isFull()) {
            notifyInventoryFullNeedChest(gameTime, range);
            return;
        }
        for (net.minecraft.world.entity.item.ItemEntity itemEntity
                : this.level().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, range)) {
            if (!itemEntity.isAlive()) {
                continue;
            }
            if (CompanionDropTracker.isDroppedBy(itemEntity, this.getUUID())) {
                continue;
            }
            UUID playerDropper = CompanionDropTracker.getPlayerDropper(itemEntity);
            if (playerDropper != null) {
                if (CompanionDropTracker.isPlayerBlockDrop(itemEntity)) {
                    continue;
                }
                if (ownerId == null || !ownerId.equals(playerDropper)) {
                    continue;
                }
            } else {
                UUID vanillaDropper = CompanionDropTracker.getVanillaDropper(itemEntity);
                if (vanillaDropper != null && getPlayerById(vanillaDropper) != null) {
                    continue;
                }
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

    private void notifyInventoryFullNeedChest(long gameTime, AABB range) {
        if (ownerId == null || gameTime - lastInventoryFullNeedChestTick < INVENTORY_FULL_NOTIFY_COOLDOWN_TICKS) {
            return;
        }
        if (!hasNearbyOwnerDrop(range)) {
            return;
        }
        Player ownerPlayer = getPlayerById(ownerId);
        if (!(ownerPlayer instanceof ServerPlayer serverPlayer) || serverPlayer.isSpectator() || !serverPlayer.isAlive()) {
            return;
        }
        lastInventoryFullNeedChestTick = gameTime;
        sendReply(serverPlayer, Component.translatable(INVENTORY_FULL_NEED_CHEST_KEY));
    }

    private boolean hasNearbyOwnerDrop(AABB range) {
        if (ownerId == null || range == null) {
            return false;
        }
        for (net.minecraft.world.entity.item.ItemEntity itemEntity
                : this.level().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, range)) {
            if (!itemEntity.isAlive()) {
                continue;
            }
            UUID playerDropper = CompanionDropTracker.getPlayerDropper(itemEntity);
            if (playerDropper == null || !ownerId.equals(playerDropper)) {
                continue;
            }
            if (CompanionDropTracker.isPlayerBlockDrop(itemEntity) || itemEntity.getItem().isEmpty()) {
                continue;
            }
            return true;
        }
        return false;
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
        Player combatPlayer = resolveCombatPlayer(nearest);
        this.toolHandler.resetToolRequest();
        if (tickHostilePlayerRetaliation(gameTime)) {
            return;
        }
        if (treeHarvestController.isTreeChopInProgress()) {
            this.taskCoordinator.tick(this.mode, gameTime);
            if (!this.toolHandler.wasToolRequested()) {
                this.equipment.equipIdleHand();
            }
            return;
        }
        if (combatController.tick(combatPlayer, gameTime)) {
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

    private Player resolveCombatPlayer(Player nearest) {
        if (ownerId != null) {
            Player ownerPlayer = getPlayerById(ownerId);
            if (ownerPlayer != null && ownerPlayer.isAlive() && !ownerPlayer.isSpectator()) {
                return ownerPlayer;
            }
        }
        return nearest;
    }

    private boolean tryUrgentOwnerDefense(long gameTime) {
        if (this.level().isClientSide || ownerId == null || this.getHealth() <= 4.0F) {
            return false;
        }
        Player ownerPlayer = getPlayerById(ownerId);
        if (ownerPlayer == null || ownerPlayer.isSpectator() || !ownerPlayer.isAlive()) {
            return false;
        }
        if (this.mode == CompanionMode.STOPPED) {
            setMode(CompanionMode.AUTONOMOUS);
        }
        return combatController.tickUrgentDefense(ownerPlayer, gameTime);
    }

    private void tickHomeRegen(long gameTime) {
        if (!isAtHome() || !this.isAlive()) {
            return;
        }
        float health = this.getHealth();
        float maxHealth = this.getMaxHealth();
        if (health >= maxHealth) {
            if (recoveringAfterDeathAtHome) {
                recoveringAfterDeathAtHome = false;
            }
            return;
        }
        int regenInterval = recoveringAfterDeathAtHome ? HOME_DEATH_REGEN_TICKS : HOME_REGEN_TICKS;
        if (lastHomeRegenTick >= 0L && gameTime - lastHomeRegenTick < regenInterval) {
            return;
        }
        lastHomeRegenTick = gameTime;
        this.heal(HOME_REGEN_AMOUNT);
        if (recoveringAfterDeathAtHome && this.getHealth() >= this.getMaxHealth()) {
            this.setHealth(this.getMaxHealth());
            recoveringAfterDeathAtHome = false;
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
        if (hostilePlayerTargetId != null) {
            return false;
        }
        if (combatController.isEngaged(this.level().getGameTime())) {
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
        return this.mode == CompanionMode.STOPPED && !returningHome;
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
        return CompanionTeleportPositioning.resolveTeleportTarget(
                this,
                player,
                TELEPORT_BEHIND_DISTANCE,
                TELEPORT_SIDE_DISTANCE,
                TELEPORT_NEARBY_RADIUS,
                TELEPORT_FOV_DOT_THRESHOLD,
                TELEPORT_Y_SEARCH_UP,
                TELEPORT_Y_SEARCH_DOWN
        );
    }

    private CompanionEntity moveToPlayerDimension(ServerPlayer player) {
        if (player == null || !this.isAlive()) {
            return this;
        }
        if (!(player.level() instanceof ServerLevel targetLevel)) {
            return this;
        }
        if (this.isPassenger()) {
            this.stopRiding();
        }
        CompanionEntity movedCompanion = this;
        if (this.level() != targetLevel) {
            Vec3 initialTargetPos = resolveInitialDimensionTeleportTarget(player);
            boolean moved = this.teleportTo(
                    targetLevel,
                    initialTargetPos.x,
                    initialTargetPos.y,
                    initialTargetPos.z,
                    Collections.emptySet(),
                    this.getYRot(),
                    this.getXRot()
            );
            if (moved) {
                Entity movedEntity = targetLevel.getEntity(this.getUUID());
                if (movedEntity instanceof CompanionEntity teleportedCompanion) {
                    movedCompanion = teleportedCompanion;
                }
            }
            if (!moved || movedCompanion.level() != targetLevel) {
                Entity changed = this.changeDimension(targetLevel);
                if (changed instanceof CompanionEntity changedCompanion) {
                    movedCompanion = changedCompanion;
                } else {
                    Entity fromTarget = targetLevel.getEntity(this.getUUID());
                    if (fromTarget instanceof CompanionEntity fallbackCompanion) {
                        movedCompanion = fallbackCompanion;
                    } else {
                        return this;
                    }
                }
            }
        }
        if (movedCompanion.isPassenger()) {
            movedCompanion.stopRiding();
        }
        if (movedCompanion.level() != player.level()) {
            return movedCompanion;
        }
        Vec3 targetPos = movedCompanion.resolveTeleportTarget(player);
        movedCompanion.teleportTo(targetPos.x, targetPos.y, targetPos.z);
        movedCompanion.getNavigation().stop();
        return movedCompanion;
    }

    private static boolean forceTeleportToPlayerDimension(CompanionEntity companion, ServerPlayer player) {
        if (companion == null || player == null || !companion.isAlive()) {
            return false;
        }
        if (!(player.level() instanceof ServerLevel targetLevel)) {
            return false;
        }
        if (companion.isPassenger()) {
            companion.stopRiding();
        }
        CompanionEntity movedCompanion = companion;
        if (companion.level() != player.level()) {
            Vec3 initialTargetPos = resolveInitialDimensionTeleportTarget(player);
            boolean moved = companion.teleportTo(
                    targetLevel,
                    initialTargetPos.x,
                    initialTargetPos.y,
                    initialTargetPos.z,
                    Collections.emptySet(),
                    companion.getYRot(),
                    companion.getXRot()
            );
            if (!moved) {
                Entity changed = companion.changeDimension(targetLevel);
                if (changed instanceof CompanionEntity changedCompanion) {
                    movedCompanion = changedCompanion;
                } else {
                    Entity fromTarget = targetLevel.getEntity(companion.getUUID());
                    if (fromTarget instanceof CompanionEntity fallbackCompanion) {
                        movedCompanion = fallbackCompanion;
                    } else {
                        return false;
                    }
                }
            } else {
                Entity movedEntity = targetLevel.getEntity(companion.getUUID());
                if (movedEntity instanceof CompanionEntity teleportedCompanion) {
                    movedCompanion = teleportedCompanion;
                }
            }
        }
        if (movedCompanion.level() != player.level()) {
            return false;
        }
        if (movedCompanion.isPassenger()) {
            movedCompanion.stopRiding();
        }
        Vec3 targetPos = movedCompanion.resolveTeleportTarget(player);
        movedCompanion.teleportTo(targetPos.x, targetPos.y, targetPos.z);
        movedCompanion.getNavigation().stop();
        return true;
    }

    private static Vec3 resolveInitialDimensionTeleportTarget(ServerPlayer player) {
        return CompanionTeleportPositioning.resolveInitialDimensionTeleportTarget(player, TELEPORT_BEHIND_DISTANCE);
    }

    private boolean isOutOfPlayerView(Vec3 playerPos, Vec3 forward, Vec3 targetPos) {
        return CompanionTeleportPositioning.isOutOfPlayerView(playerPos, forward, targetPos, TELEPORT_FOV_DOT_THRESHOLD);
    }

    private Vec3 adjustTeleportY(Vec3 basePos) {
        return CompanionTeleportPositioning.adjustTeleportY(this, basePos, TELEPORT_Y_SEARCH_UP, TELEPORT_Y_SEARCH_DOWN);
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
                this.blockPosition(),
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
                position,
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
        Entity entity = level.getEntity(request.companionId);
        CompanionEntity companion = entity instanceof CompanionEntity found ? found : null;
        if (companion == null && request.chunkPos != null) {
            level.getChunkSource().getChunk(request.chunkPos.x, request.chunkPos.z, ChunkStatus.FULL, true);
            entity = level.getEntity(request.companionId);
            companion = entity instanceof CompanionEntity foundAfterLoad ? foundAfterLoad : null;
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
        if (!accepted && DIMENSION_TELEPORT_REQUEST_KEY.equals(request.messageKey)) {
            APPROVED_DIMENSION_TELEPORTS.remove(player.getUUID());
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return false;
        }
        ServerLevel level = server.getLevel(request.levelKey);
        long gameTime = level != null ? level.getGameTime() : player.level().getGameTime();
        if (usesServerTickClock(request.messageKey) && player.getServer() != null) {
            gameTime = player.getServer().getTickCount();
        }
        if (gameTime >= request.untilTick) {
            String removeKey = getTimedMessageRemovalKey(request.messageKey);
            if (removeKey != null) {
                sendTeleportIgnore(player, removeKey);
            }
            PENDING_TELEPORT_REQUESTS.remove(player.getUUID(), request);
            PENDING_TELEPORTS.remove(player.getUUID());
            APPROVED_DIMENSION_TELEPORTS.remove(player.getUUID());
            return false;
        }
        CompanionEntity companion = getCompanionFromPendingMap(player, request);
        if (companion == null) {
            companion = resolveCompanionForTeleport(server, request, level);
        }
        if (accepted
                && DIMENSION_TELEPORT_REQUEST_KEY.equals(request.messageKey)
                && (companion == null || !companion.isAlive())) {
            APPROVED_DIMENSION_TELEPORTS.add(player.getUUID());
            sendTeleportIgnore(player, TELEPORT_IGNORE_DIMENSION_KEY);
            return true;
        }
        if (WHERE_STATUS_KEY.equals(request.messageKey)) {
            if (companion != null) {
                companion.clearWhereRequest();
            } else {
                sendTeleportIgnore(player, TELEPORT_IGNORE_WHERE_KEY);
            }
        }
        boolean shouldFollowAfterTeleport = false;
        if (companion != null) {
            shouldFollowAfterTeleport = companion.isAtHome()
                    || companion.getMode() == CompanionMode.STOPPED;
        }
        if (accepted && companion != null && companion.isAlive()) {
            boolean recoveringAtHome = companion.recoveringAfterDeathAtHome && companion.isAtHome();
            if (recoveringAtHome) {
                int currentHp = Mth.ceil(Math.max(0.0F, companion.getHealth()));
                int maxHp = Mth.ceil(companion.getMaxHealth());
                companion.sendReply(player, Component.translatable(HOME_DEATH_RECOVERY_HP_REMOVE_KEY));
                companion.sendReply(player, Component.translatable(HOME_DEATH_RECOVERY_HP_KEY, currentHp, maxHp));
            } else {
                if (DIMENSION_TELEPORT_REQUEST_KEY.equals(request.messageKey)) {
                    try {
                        companion = companion.moveToPlayerDimension(player);
                    } catch (RuntimeException ignored) {
                        if (player.level() instanceof ServerLevel targetLevel) {
                            Vec3 initialTargetPos = resolveInitialDimensionTeleportTarget(player);
                            companion.teleportTo(
                                    targetLevel,
                                    initialTargetPos.x,
                                    initialTargetPos.y,
                                    initialTargetPos.z,
                                    Collections.emptySet(),
                                    companion.getYRot(),
                                    companion.getXRot()
                            );
                            if (companion.level() == player.level()) {
                                Vec3 targetPos = companion.resolveTeleportTarget(player);
                                companion.teleportTo(targetPos.x, targetPos.y, targetPos.z);
                                companion.getNavigation().stop();
                            }
                        }
                    }
                } else {
                    Vec3 targetPos = companion.resolveTeleportTarget(player);
                    companion.teleportTo(targetPos.x, targetPos.y, targetPos.z);
                    companion.getNavigation().stop();
                }
                if (shouldFollowAfterTeleport) {
                    companion.setMode(CompanionMode.AUTONOMOUS);
                }
            }
        }
        PENDING_TELEPORT_REQUESTS.remove(player.getUUID(), request);
        PENDING_TELEPORTS.remove(player.getUUID());
        APPROVED_DIMENSION_TELEPORTS.remove(player.getUUID());
        if (companion != null) {
            companion.clearTeleportRequestState();
            companion.clearTeleportReminder();
        }
        String removeKey = getTimedMessageRemovalKey(request.messageKey);
        if (removeKey != null && !WHERE_STATUS_KEY.equals(request.messageKey)) {
            sendTeleportIgnore(player, removeKey);
        }
        return true;
    }

    public static boolean forceHandlePendingDimensionTeleport(ServerPlayer player) {
        if (player == null || !player.isAlive()) {
            return false;
        }
        PendingTeleportRequest request = PENDING_TELEPORT_REQUESTS.get(player.getUUID());
        if (request == null || !DIMENSION_TELEPORT_REQUEST_KEY.equals(request.messageKey)) {
            return false;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            PENDING_TELEPORT_REQUESTS.remove(player.getUUID(), request);
            PENDING_TELEPORTS.remove(player.getUUID());
            APPROVED_DIMENSION_TELEPORTS.remove(player.getUUID());
            return false;
        }
        ServerLevel requestLevel = server.getLevel(request.levelKey);
        CompanionEntity companion = getCompanionFromPendingMap(player, request);
        if (companion == null) {
            companion = resolveCompanionForTeleport(server, request, requestLevel);
        }
        boolean moved = forceTeleportToPlayerDimension(companion, player);
        PENDING_TELEPORT_REQUESTS.remove(player.getUUID(), request);
        PENDING_TELEPORTS.remove(player.getUUID());
        APPROVED_DIMENSION_TELEPORTS.remove(player.getUUID());
        if (companion != null) {
            companion.clearTeleportRequestState();
            companion.clearTeleportReminder();
        }
        sendTeleportIgnore(player, TELEPORT_IGNORE_DIMENSION_KEY);
        return moved;
    }

    private static CompanionEntity getCompanionFromPendingMap(ServerPlayer player, PendingTeleportRequest request) {
        if (player == null || request == null) {
            return null;
        }
        CompanionEntity pending = PENDING_TELEPORTS.get(player.getUUID());
        if (pending == null) {
            return null;
        }
        if (pending.isRemoved() || !pending.isAlive() || !request.companionId.equals(pending.getUUID())) {
            PENDING_TELEPORTS.remove(player.getUUID(), pending);
            return null;
        }
        return pending;
    }

    private static CompanionEntity resolveCompanionForTeleport(MinecraftServer server,
                                                               PendingTeleportRequest request,
                                                               ServerLevel requestLevel) {
        if (server == null || request == null) {
            return null;
        }
        CompanionEntity companion = findCompanionInLevel(requestLevel, request.companionId, request.chunkPos);
        if (companion != null && companion.isAlive()) {
            return companion;
        }
        CompanionSingleNpcManager.ensureLoaded(server);
        UUID activeId = CompanionSingleNpcManager.getActiveId();
        ResourceKey<Level> activeLevelKey = CompanionSingleNpcManager.getActiveDimension();
        BlockPos lastKnownPos = CompanionSingleNpcManager.getLastKnownPos();
        if (activeId != null && activeId.equals(request.companionId) && activeLevelKey != null) {
            ServerLevel activeLevel = server.getLevel(activeLevelKey);
            ChunkPos activeChunk = lastKnownPos != null ? new ChunkPos(lastKnownPos) : null;
            companion = findCompanionInLevel(activeLevel, activeId, activeChunk);
            if (companion != null && companion.isAlive()) {
                return companion;
            }
        }
        ResourceKey<Level> homeLevelKey = CompanionSingleNpcManager.getLastHomeDimension();
        BlockPos homePos = CompanionSingleNpcManager.getLastHomePos();
        if (homeLevelKey != null && homePos != null) {
            ServerLevel homeLevel = server.getLevel(homeLevelKey);
            companion = findCompanionInLevel(homeLevel, request.companionId, new ChunkPos(homePos));
            if (companion != null && companion.isAlive()) {
                return companion;
            }
        }
        for (ServerLevel anyLevel : server.getAllLevels()) {
            Entity entity = anyLevel.getEntity(request.companionId);
            if (entity instanceof CompanionEntity anyCompanion && anyCompanion.isAlive()) {
                return anyCompanion;
            }
        }
        return null;
    }

    private static CompanionEntity findCompanionInLevel(ServerLevel level, UUID companionId, ChunkPos chunkPos) {
        if (level == null || companionId == null) {
            return null;
        }
        Entity entity = level.getEntity(companionId);
        if (entity instanceof CompanionEntity companion && companion.isAlive()) {
            return companion;
        }
        if (chunkPos != null) {
            level.getChunkSource().getChunk(chunkPos.x, chunkPos.z, ChunkStatus.FULL, true);
            entity = level.getEntity(companionId);
            if (entity instanceof CompanionEntity chunkCompanion && chunkCompanion.isAlive()) {
                return chunkCompanion;
            }
        }
        return null;
    }

    private static void sendTeleportIgnore(ServerPlayer player, String key) {
        if (player == null || key == null) {
            return;
        }
        player.sendSystemMessage(Component.translatable(key));
    }

    private static boolean usesServerTickClock(String messageKey) {
        return WHERE_STATUS_KEY.equals(messageKey) || DIMENSION_TELEPORT_REQUEST_KEY.equals(messageKey);
    }

    private static String getTimedMessageRemovalKey(String messageKey) {
        if (messageKey == null) {
            return null;
        }
        if (WHERE_STATUS_KEY.equals(messageKey)) {
            return TELEPORT_IGNORE_WHERE_KEY;
        }
        if (DIMENSION_TELEPORT_REQUEST_KEY.equals(messageKey)) {
            return TELEPORT_IGNORE_DIMENSION_KEY;
        }
        return null;
    }

    private static void removeExpiredPendingRequest(MinecraftServer server, UUID playerId,
                                                    PendingTeleportRequest request) {
        if (playerId == null || request == null) {
            return;
        }
        PENDING_TELEPORT_REQUESTS.remove(playerId, request);
        PENDING_TELEPORTS.remove(playerId);
        APPROVED_DIMENSION_TELEPORTS.remove(playerId);
        String removeKey = getTimedMessageRemovalKey(request.messageKey);
        if (removeKey == null) {
            return;
        }
        if (server == null) {
            return;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player != null) {
            sendTeleportIgnore(player, removeKey);
        }
    }

    public static void tickPendingTeleports(MinecraftServer server) {
        if (server == null) {
            return;
        }
        if (PENDING_TELEPORT_REQUESTS.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, PendingTeleportRequest> entry : PENDING_TELEPORT_REQUESTS.entrySet()) {
            PendingTeleportRequest request = entry.getValue();
            if (request == null) {
                PENDING_TELEPORT_REQUESTS.remove(entry.getKey());
                PENDING_TELEPORTS.remove(entry.getKey());
                continue;
            }
            ServerLevel level = server.getLevel(request.levelKey);
            if (level == null) {
                removeExpiredPendingRequest(server, entry.getKey(), request);
                continue;
            }
            long gameTime = level.getGameTime();
            if (usesServerTickClock(request.messageKey)) {
                gameTime = server.getTickCount();
            }
            if (gameTime >= request.untilTick) {
                removeExpiredPendingRequest(server, entry.getKey(), request);
                continue;
            }
            if (DIMENSION_TELEPORT_REQUEST_KEY.equals(request.messageKey)) {
                ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
                if (player == null || player.isSpectator() || !player.isAlive()) {
                    continue;
                }
                if (player.level().dimension().equals(request.levelKey)) {
                    removeExpiredPendingRequest(server, entry.getKey(), request);
                    continue;
                }
                if (APPROVED_DIMENSION_TELEPORTS.contains(entry.getKey())) {
                    CompanionEntity companion = getCompanionFromPendingMap(player, request);
                    if (companion == null) {
                        companion = resolveCompanionForTeleport(server, request, level);
                    }
                    if (forceTeleportToPlayerDimension(companion, player)) {
                        PENDING_TELEPORT_REQUESTS.remove(entry.getKey(), request);
                        PENDING_TELEPORTS.remove(entry.getKey());
                        APPROVED_DIMENSION_TELEPORTS.remove(entry.getKey());
                        if (companion != null) {
                            companion.clearTeleportRequestState();
                            companion.clearTeleportReminder();
                        }
                        sendTeleportIgnore(player, TELEPORT_IGNORE_DIMENSION_KEY);
                    }
                    continue;
                }
                int secondsLeft = secondsLeftStatic(request.untilTick, gameTime);
                if (secondsLeft != request.lastSeconds) {
                    request.lastSeconds = secondsLeft;
                    sendDimensionTeleportMessageStatic(player, secondsLeft);
                }
                continue;
            }
            if (WHERE_STATUS_KEY.equals(request.messageKey) && request.originPos != null) {
                if (level.hasChunk(request.chunkPos.x, request.chunkPos.z)) {
                    Entity entity = level.getEntity(request.companionId);
                    if (!(entity instanceof CompanionEntity companion) || !companion.isAlive()) {
                        removeExpiredPendingRequest(server, entry.getKey(), request);
                    }
                    continue;
                }
                int secondsLeft = secondsLeftStatic(request.untilTick, gameTime);
                if (secondsLeft != request.lastSeconds) {
                    request.lastSeconds = secondsLeft;
                    ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
                    if (player != null) {
                        sendWhereMessageFallback(player, request.originPos, secondsLeft);
                    }
                }
            }
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
        Entity first = level.getEntity(companionId);
        CompanionEntity companion = first instanceof CompanionEntity loaded ? loaded : null;
        if (companion == null) {
            level.getChunkSource().getChunk(lastPos.getX() >> 4, lastPos.getZ() >> 4, ChunkStatus.FULL, true);
            Entity loadedAfterChunk = level.getEntity(companionId);
            companion = loadedAfterChunk instanceof CompanionEntity loadedCompanion ? loadedCompanion : null;
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

    BlockPos getHomePos() {
        return homePos;
    }

    ResourceLocation getHomeDimensionId() {
        return homeDimension;
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

    private void hideSwordWhileInBoat() {
        if (!this.isPassenger() || !(this.getVehicle() instanceof Boat)) {
            return;
        }
        ItemStack mainHand = this.getMainHandItem();
        if (mainHand.isEmpty() || CompanionToolSlot.fromStack(mainHand) != CompanionToolSlot.SWORD) {
            return;
        }
        if (getToolSlot(CompanionToolSlot.SWORD).isEmpty()) {
            setToolSlot(CompanionToolSlot.SWORD, mainHand.copy());
            setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            return;
        }
        if (inventory.add(mainHand.copy())) {
            setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        }
    }
}
