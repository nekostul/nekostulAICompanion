package ru.nekostul.aicompanion.entity.home;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;
import ru.nekostul.aicompanion.entity.CompanionEntity;
import ru.nekostul.aicompanion.entity.movement.CompanionMovementSpeed;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class CompanionHomeAssessmentController {
    public enum Result {
        IDLE,
        IN_PROGRESS,
        DONE
    }

    private enum AssessmentState {
        IDLE,
        LOOKING,
        WAITING_ROOM_NAME,
        WAITING_ROOM_CONFIRMATION,
        WAITING_NEXT_ROOM
    }

    private enum RoomDecision {
        NONE,
        MORE_ROOMS
    }

    private static final class LookStep {
        private final float yawOffset;
        private final float pitchOffset;
        private final int durationTicks;

        private LookStep(float yawOffset, float pitchOffset, int durationTicks) {
            this.yawOffset = yawOffset;
            this.pitchOffset = pitchOffset;
            this.durationTicks = durationTicks;
        }
    }

    private static final float LOOK_SIDE_DEGREES = 70.0F;
    private static final float LOOK_UP_DEGREES = 40.0F;
    private static final float LOOK_DOWN_DEGREES = 50.0F;
    private static final float LOOK_SIDE_VARIATION_DEGREES = 18.0F;
    private static final float LOOK_UP_VARIATION_DEGREES = 10.0F;
    private static final float LOOK_DOWN_VARIATION_DEGREES = 12.0F;
    private static final float LOOK_FORWARD_YAW_RANDOM_DEGREES = 12.0F;
    private static final float LOOK_FORWARD_PITCH_RANDOM_DEGREES = 8.0F;
    private static final float LOOK_VERTICAL_YAW_RANDOM_DEGREES = 20.0F;
    private static final int LOOK_STEP_TICKS_MIN = 10;
    private static final int LOOK_STEP_TICKS_MAX = 30;
    private static final int LOOK_EXTRA_STEPS_MIN = 1;
    private static final int LOOK_EXTRA_STEPS_MAX = 3;
    private static final float LIGHT_BRIGHT_THRESHOLD = 8.0F;
    private static final int ASSESS_COOLDOWN_TICKS = 5 * 60 * 20;
    private static final int MAX_SCAN_RADIUS = 20;
    private static final int MAX_SCAN_HEIGHT = 12;
    private static final int MAX_INTERIOR_CELLS = 6000;
    private static final int MAX_COUNTED_BLOCKS = 12000;
    private static final int MIN_INTERIOR_CELLS = 12;
    private static final int MAX_OUTSIDE_LEAK_EDGES = 12;
    private static final float MAX_OUTSIDE_LEAK_RATIO = 0.25F;
    private static final int ROOF_SEARCH_HEIGHT = 20;
    private static final int ROOF_SAMPLE_RADIUS = 1;
    private static final int MIN_ROOF_COVERAGE_SAMPLES = 6;
    private static final int WALL_SEARCH_DISTANCE = 16;
    private static final int REQUIRED_WALL_DIRECTIONS = 6;
    private static final int REQUIRED_CARDINAL_WALL_DIRECTIONS = 3;
    private static final double SAME_ROOM_ORIGIN_DISTANCE_SQR = 16.0D;
    private static final double NEXT_ROOM_FOLLOW_DISTANCE_SQR = 16.0D;
    private static final int NEXT_ROOM_REPATH_TICKS = 10;
    private static final double NEXT_ROOM_FOLLOW_SPEED_BLOCKS_PER_TICK = 0.35D;
    private static final int MAX_ROOM_NAME_LENGTH = 64;

    private static final String START_KEY = "entity.aicompanion.companion.home.assess.start";
    private static final String OUTSIDE_KEY = "entity.aicompanion.companion.home.assess.outside";
    private static final String UPDATED_KEY = "entity.aicompanion.companion.home.assess.updated";
    private static final String COOLDOWN_KEY = "entity.aicompanion.companion.home.assess.cooldown";
    private static final String UNCHANGED_KEY = "entity.aicompanion.companion.home.assess.unchanged";
    private static final String LIGHT_KEY = "entity.aicompanion.companion.home.assess.light";
    private static final String LIGHT_BRIGHT_KEY = "entity.aicompanion.companion.home.assess.light.bright";
    private static final String LIGHT_DARK_KEY = "entity.aicompanion.companion.home.assess.light.dark";
    private static final String FLOOR_KEY = "entity.aicompanion.companion.home.assess.floor";
    private static final String CEILING_KEY = "entity.aicompanion.companion.home.assess.ceiling";
    private static final String MATERIALS_KEY = "entity.aicompanion.companion.home.assess.materials";
    private static final String UNKNOWN_KEY = "entity.aicompanion.companion.home.assess.unknown";
    private static final String TRUNCATED_KEY = "entity.aicompanion.companion.home.assess.truncated";
    private static final String ROOMS_OFFER_KEY = "entity.aicompanion.companion.home.assess.rooms.offer";
    private static final String ROOMS_BUTTON_DONE_KEY = "entity.aicompanion.companion.home.assess.rooms.button.done";
    private static final String ROOMS_BUTTON_MORE_KEY = "entity.aicompanion.companion.home.assess.rooms.button.more";
    private static final String ROOMS_REMOVE_KEY = "entity.aicompanion.companion.home.assess.rooms.remove";
    private static final String ROOMS_NEXT_KEY = "entity.aicompanion.companion.home.assess.rooms.next";
    private static final String ROOMS_ALREADY_KEY = "entity.aicompanion.companion.home.assess.rooms.already";
    private static final String ROOMS_NAME_ASK_KEY = "entity.aicompanion.companion.home.assess.rooms.name.ask";
    private static final String ROOMS_NAME_RETRY_KEY = "entity.aicompanion.companion.home.assess.rooms.name.retry";
    private static final String ROOMS_NAME_DUPLICATE_KEY = "entity.aicompanion.companion.home.assess.rooms.name.duplicate";
    private static final String ROOMS_IN_PROGRESS_NEXT_KEY =
            "entity.aicompanion.companion.home.assess.rooms.in_progress_next";
    private static final String ROOMS_LIST_KEY = "entity.aicompanion.companion.home.assess.rooms.list";
    private static final String ROOMS_NEXT_COMMAND_RU =
            "\u0435\u0449\u0435 \u043a\u043e\u043c\u043d\u0430\u0442\u0430";
    private static final String ROOMS_NEXT_COMMAND_EN = "another room";
    private static final String ROOMS_BUTTON_DONE_TOKEN = "__HOME_ASSESS_DONE__";
    private static final String ROOMS_BUTTON_MORE_TOKEN = "__HOME_ASSESS_MORE__";

    private final CompanionEntity owner;
    private final RandomSource random;

    private AssessmentState assessmentState = AssessmentState.IDLE;
    private UUID activePlayerId;
    private final List<LookStep> lookSteps = new ArrayList<>();
    private final List<HomeScanReport> collectedRoomReports = new ArrayList<>();
    private final List<String> collectedRoomNames = new ArrayList<>();
    private final List<RoomVisit> visitedRooms = new ArrayList<>();
    private HomeScanReport pendingRoomReport;
    private BlockPos pendingRoomOrigin;
    private RoomDecision pendingRoomDecision = RoomDecision.NONE;
    private boolean initialRoomChoicePending;
    private int lookStepIndex = -1;
    private long lookStepStartedTick = -1L;
    private int lookStepDurationTicks = 0;
    private float baseYaw;
    private float basePitch;
    private float lookStepStartYaw;
    private float lookStepStartPitch;
    private float lookStepTargetYaw;
    private float lookStepTargetPitch;
    private long nextAssessmentAllowedTick = -1L;
    private boolean hasLastAssessment;
    private int lastAssessmentSignature;
    private String lastAssessmentPayload = "";
    private BlockPos lastScanOrigin;
    private BlockPos nextRoomFollowLastTarget;
    private long nextRoomFollowLastMoveTick = -1L;

    public CompanionHomeAssessmentController(CompanionEntity owner) {
        this.owner = owner;
        this.random = owner.getRandom();
    }

    public boolean isAssessmentCommand(String message) {
        String normalized = normalize(message);
        if (normalized.isEmpty()) {
            return false;
        }
        return normalized.equals("оцени дом")
                || normalized.equals("оцени мой дом")
                || normalized.contains("оцени дом");
    }

    public String getLastAssessmentPayload() {
        return lastAssessmentPayload;
    }

    public boolean start(ServerPlayer player, long gameTime) {
        return beginRoomAssessment(player, gameTime, true);
    }

    public boolean handleFollowUpMessage(ServerPlayer player, String message, long gameTime) {
        if (player == null || message == null || activePlayerId == null) {
            return false;
        }
        if (!activePlayerId.equals(player.getUUID())) {
            return false;
        }
        String raw = message.trim();
        boolean doneToken = ROOMS_BUTTON_DONE_TOKEN.equalsIgnoreCase(raw);
        boolean moreToken = ROOMS_BUTTON_MORE_TOKEN.equalsIgnoreCase(raw);
        String normalized = normalize(message);
        boolean assessmentCommand = isAssessmentCommand(message);
        if (normalized.isEmpty()) {
            return false;
        }
        if (assessmentState == AssessmentState.WAITING_ROOM_NAME) {
            if (doneToken
                    || moreToken
                    || assessmentCommand
                    || isWholeHouseAnswer(normalized)
                    || isMoreRoomsAnswer(normalized)
                    || isNextRoomCommand(normalized)) {
                owner.sendReply(player, Component.translatable(ROOMS_NAME_RETRY_KEY));
                return true;
            }
            String roomName = sanitizeRoomName(message);
            if (roomName.isEmpty()) {
                owner.sendReply(player, Component.translatable(ROOMS_NAME_RETRY_KEY));
                return true;
            }
            if (isDuplicateRoomName(roomName)) {
                owner.sendReply(player, Component.translatable(ROOMS_NAME_DUPLICATE_KEY));
                return true;
            }
            commitPendingRoom(roomName);
            if (pendingRoomDecision == RoomDecision.MORE_ROOMS) {
                pendingRoomDecision = RoomDecision.NONE;
                owner.sendReply(player, Component.translatable(ROOMS_NEXT_KEY));
                assessmentState = AssessmentState.WAITING_NEXT_ROOM;
                tickNextRoomFollow(player, gameTime);
                return true;
            }
            pendingRoomDecision = RoomDecision.NONE;
            sendRoomsQuestion(player);
            assessmentState = AssessmentState.WAITING_ROOM_CONFIRMATION;
            return true;
        }
        if (assessmentState == AssessmentState.WAITING_ROOM_CONFIRMATION) {
            if (doneToken || isWholeHouseAnswer(normalized)) {
                owner.sendReply(player, Component.translatable(ROOMS_REMOVE_KEY));
                if (initialRoomChoicePending) {
                    initialRoomChoicePending = false;
                    commitPendingRoomWithoutName();
                }
                finalizeAssessment(player, gameTime);
                cancel();
                return true;
            }
            if (moreToken || isMoreRoomsAnswer(normalized)) {
                owner.sendReply(player, Component.translatable(ROOMS_REMOVE_KEY));
                if (initialRoomChoicePending) {
                    initialRoomChoicePending = false;
                    pendingRoomDecision = RoomDecision.MORE_ROOMS;
                    owner.sendReply(player, Component.translatable(ROOMS_NAME_ASK_KEY));
                    assessmentState = AssessmentState.WAITING_ROOM_NAME;
                } else {
                    owner.sendReply(player, Component.translatable(ROOMS_NEXT_KEY));
                    assessmentState = AssessmentState.WAITING_NEXT_ROOM;
                    tickNextRoomFollow(player, gameTime);
                }
                return true;
            }
        }
        if (assessmentState == AssessmentState.WAITING_NEXT_ROOM) {
            if (assessmentCommand) {
                owner.sendReply(player, Component.translatable(ROOMS_IN_PROGRESS_NEXT_KEY));
                return true;
            }
            if (doneToken || isWholeHouseAnswer(normalized)) {
                owner.sendReply(player, Component.translatable(ROOMS_REMOVE_KEY));
                finalizeAssessment(player, gameTime);
                cancel();
                return true;
            }
            if (moreToken || isNextRoomCommand(normalized)) {
                return beginRoomAssessment(player, gameTime, false);
            }
        }
        if (doneToken
                || moreToken
                || assessmentCommand
                || isNextRoomCommand(normalized)) {
            return true;
        }
        return false;
    }

    public boolean isSessionActive() {
        return assessmentState != AssessmentState.IDLE;
    }

    public Result tick(long gameTime) {
        if (assessmentState == AssessmentState.IDLE) {
            return Result.DONE;
        }
        if (!(owner.getPlayerById(activePlayerId) instanceof ServerPlayer player)) {
            cancel();
            return Result.DONE;
        }
        if (assessmentState == AssessmentState.WAITING_ROOM_NAME
                || assessmentState == AssessmentState.WAITING_ROOM_CONFIRMATION) {
            owner.getNavigation().stop();
            resetNextRoomFollowTracking();
            return Result.IN_PROGRESS;
        }
        if (assessmentState == AssessmentState.WAITING_NEXT_ROOM) {
            tickNextRoomFollow(player, gameTime);
            return Result.IN_PROGRESS;
        }
        if (assessmentState != AssessmentState.LOOKING) {
            cancel();
            return Result.DONE;
        }
        owner.getNavigation().stop();
        if (lookStepIndex >= 0) {
            applyCurrentLookStep(gameTime);
            if (gameTime - lookStepStartedTick < lookStepDurationTicks) {
                return Result.IN_PROGRESS;
            }
            if (startNextLookStep(gameTime)) {
                return Result.IN_PROGRESS;
            }
            return Result.IN_PROGRESS;
        }
        HomeScanReport report = scanHome();
        if (!report.insideBuilding) {
            owner.sendReply(player, Component.translatable(OUTSIDE_KEY));
            owner.sendReply(player, Component.translatable(ROOMS_NEXT_KEY));
            assessmentState = AssessmentState.WAITING_NEXT_ROOM;
            tickNextRoomFollow(player, gameTime);
            return Result.IN_PROGRESS;
        }
        queuePendingRoom(report, lastScanOrigin);
        pendingRoomDecision = RoomDecision.NONE;
        if (initialRoomChoicePending) {
            sendRoomsQuestion(player);
            assessmentState = AssessmentState.WAITING_ROOM_CONFIRMATION;
        } else {
            owner.sendReply(player, Component.translatable(ROOMS_NAME_ASK_KEY));
            assessmentState = AssessmentState.WAITING_ROOM_NAME;
        }
        return Result.IN_PROGRESS;
    }

    public void cancel() {
        assessmentState = AssessmentState.IDLE;
        activePlayerId = null;
        collectedRoomReports.clear();
        collectedRoomNames.clear();
        visitedRooms.clear();
        pendingRoomReport = null;
        pendingRoomOrigin = null;
        pendingRoomDecision = RoomDecision.NONE;
        initialRoomChoicePending = false;
        lookSteps.clear();
        lookStepIndex = -1;
        lookStepStartedTick = -1L;
        lookStepDurationTicks = 0;
        lookStepStartYaw = 0.0F;
        lookStepStartPitch = 0.0F;
        lookStepTargetYaw = 0.0F;
        lookStepTargetPitch = 0.0F;
        lastScanOrigin = null;
        resetNextRoomFollowTracking();
    }

    private void applyCurrentLookStep(long gameTime) {
        if (lookStepIndex < 0 || lookStepDurationTicks <= 0) {
            return;
        }
        float t = Mth.clamp((gameTime - lookStepStartedTick + 1.0F) / lookStepDurationTicks, 0.0F, 1.0F);
        float eased = t * t * (3.0F - 2.0F * t);
        float yaw = lerpRotation(lookStepStartYaw, lookStepTargetYaw, eased);
        float pitch = Mth.lerp(eased, lookStepStartPitch, lookStepTargetPitch);
        applyLook(yaw, pitch);
    }

    private boolean startNextLookStep(long gameTime) {
        int nextIndex = lookStepIndex + 1;
        if (nextIndex < 0 || nextIndex >= lookSteps.size()) {
            lookStepIndex = -1;
            return false;
        }
        LookStep step = lookSteps.get(nextIndex);
        lookStepIndex = nextIndex;
        lookStepStartedTick = gameTime;
        lookStepDurationTicks = Math.max(6, step.durationTicks);
        lookStepStartYaw = owner.getYRot();
        lookStepStartPitch = owner.getXRot();
        lookStepTargetYaw = baseYaw + step.yawOffset;
        lookStepTargetPitch = Mth.clamp(basePitch + step.pitchOffset, -85.0F, 85.0F);
        return true;
    }

    private boolean beginRoomAssessment(ServerPlayer player, long gameTime, boolean initialRequest) {
        if (player == null) {
            return false;
        }
        if (initialRequest) {
            if (nextAssessmentAllowedTick >= 0L && gameTime < nextAssessmentAllowedTick) {
                int secondsLeft = secondsLeft(nextAssessmentAllowedTick, gameTime);
                owner.sendReply(player, Component.translatable(COOLDOWN_KEY, secondsLeft / 60, secondsLeft % 60));
                cancel();
                return false;
            }
            HomeScanReport previewReport = scanHome();
            if (!previewReport.insideBuilding) {
                owner.sendReply(player, Component.translatable(OUTSIDE_KEY));
                cancel();
                return false;
            }
            cancel();
            activePlayerId = player.getUUID();
            initialRoomChoicePending = true;
            pendingRoomDecision = RoomDecision.NONE;
        } else {
            if (assessmentState != AssessmentState.WAITING_NEXT_ROOM || activePlayerId == null
                    || !activePlayerId.equals(player.getUUID())) {
                return false;
            }
            HomeScanReport previewReport = scanHome();
            if (!previewReport.insideBuilding) {
                owner.sendReply(player, Component.translatable(OUTSIDE_KEY));
                owner.sendReply(player, Component.translatable(ROOMS_NEXT_KEY));
                return true;
            }
            if (isAlreadyVisitedRoom(previewReport, lastScanOrigin)) {
                owner.sendReply(player, Component.translatable(ROOMS_ALREADY_KEY));
                tickNextRoomFollow(player, gameTime);
                return true;
            }
        }
        assessmentState = AssessmentState.LOOKING;
        resetNextRoomFollowTracking();
        baseYaw = owner.getYRot();
        basePitch = owner.getXRot();
        buildLookSteps();
        if (!startNextLookStep(gameTime)) {
            cancel();
            return false;
        }
        owner.sendReply(player, Component.translatable(START_KEY));
        return true;
    }

    private void buildLookSteps() {
        lookSteps.clear();

        boolean leftFirst = random.nextBoolean();
        float leftYaw = LOOK_SIDE_DEGREES + randomSigned(LOOK_SIDE_VARIATION_DEGREES);
        float rightYaw = -(LOOK_SIDE_DEGREES + randomSigned(LOOK_SIDE_VARIATION_DEGREES));
        float firstSideYaw = leftFirst ? leftYaw : rightYaw;
        float secondSideYaw = leftFirst ? rightYaw : leftYaw;

        addLookStep(
                randomSigned(LOOK_FORWARD_YAW_RANDOM_DEGREES),
                randomSigned(LOOK_FORWARD_PITCH_RANDOM_DEGREES),
                randomRange(12, 20));
        addLookStep(firstSideYaw, randomSigned(10.0F), randomRange(18, 30));
        if (random.nextBoolean()) {
            addLookStep(
                    firstSideYaw + randomSigned(16.0F),
                    randomSigned(10.0F),
                    randomRange(10, 18));
        }
        addLookStep(secondSideYaw, randomSigned(10.0F), randomRange(18, 30));

        if (random.nextBoolean()) {
            addLookStep(
                    randomSigned(LOOK_VERTICAL_YAW_RANDOM_DEGREES),
                    -(LOOK_UP_DEGREES + randomSigned(LOOK_UP_VARIATION_DEGREES)),
                    randomRange(16, 28));
        }
        if (random.nextFloat() < 0.85F) {
            addLookStep(
                    randomSigned(LOOK_VERTICAL_YAW_RANDOM_DEGREES),
                    LOOK_DOWN_DEGREES + randomSigned(LOOK_DOWN_VARIATION_DEGREES),
                    randomRange(16, 28));
        }

        int extraSteps = randomRange(LOOK_EXTRA_STEPS_MIN, LOOK_EXTRA_STEPS_MAX);
        for (int i = 0; i < extraSteps; i++) {
            addLookStep(
                    randomSigned(95.0F),
                    randomSigned(32.0F),
                    randomRange(10, 24));
        }

        addLookStep(randomSigned(18.0F), randomSigned(10.0F), randomRange(12, 20));
        addLookStep(randomSigned(7.0F), randomSigned(6.0F), randomRange(10, 18));
    }

    private void addLookStep(float yawOffset, float pitchOffset, int durationTicks) {
        lookSteps.add(new LookStep(yawOffset, pitchOffset, durationTicks));
    }

    private int randomRange(int min, int max) {
        if (max <= min) {
            return min;
        }
        return min + random.nextInt(max - min + 1);
    }

    private float randomSigned(float magnitude) {
        return (random.nextFloat() * 2.0F - 1.0F) * magnitude;
    }

    private float lerpRotation(float start, float end, float t) {
        return start + Mth.wrapDegrees(end - start) * t;
    }

    private void applyLook(float yaw, float pitch) {
        owner.setYRot(yaw);
        owner.setYBodyRot(yaw);
        owner.setYHeadRot(yaw);
        owner.setXRot(Mth.clamp(pitch, -85.0F, 85.0F));
    }

    private HomeScanReport scanHome() {
        BlockPos origin = pickStartPos();
        lastScanOrigin = origin != null ? origin.immutable() : null;
        if (!isInsideBuilding(origin)) {
            return HomeScanReport.notInside();
        }
        Level level = owner.level();
        Deque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visitedInterior = new HashSet<>();
        Set<BlockPos> countedBlocks = new HashSet<>();
        Map<Block, Integer> materialCounts = new HashMap<>();
        Set<BlockPos> countedFloorBlocks = new HashSet<>();
        Set<BlockPos> countedCeilingBlocks = new HashSet<>();
        Map<Block, Integer> floorCounts = new HashMap<>();
        Map<Block, Integer> ceilingCounts = new HashMap<>();

        queue.add(origin);
        visitedInterior.add(origin);

        int torchCount = 0;
        long lightSum = 0L;
        int lightSamples = 0;
        int outsideLeakEdges = 0;
        boolean truncated = false;

        while (!queue.isEmpty()) {
            if (visitedInterior.size() > MAX_INTERIOR_CELLS) {
                truncated = true;
                break;
            }
            BlockPos current = queue.removeFirst();
            BlockState currentState = level.getBlockState(current);
            if (!isInteriorPassable(currentState, current)) {
                continue;
            }
            lightSum += level.getRawBrightness(current, 0);
            lightSamples++;
            collectSurfaceMaterial(current.below(), level, countedFloorBlocks, floorCounts);
            collectSurfaceMaterial(current.above(), level, countedCeilingBlocks, ceilingCounts);
            if (!currentState.isAir()) {
                torchCount += countBlock(current, currentState, countedBlocks, materialCounts);
            }

            for (Direction direction : Direction.values()) {
                BlockPos next = current.relative(direction);
                if (!isWithinScanBounds(origin, next)) {
                    continue;
                }
                BlockState nextState = level.getBlockState(next);
                if (isInteriorPassable(nextState, next)) {
                    if (isOutsideCell(next)) {
                        outsideLeakEdges++;
                        continue;
                    }
                    if (visitedInterior.add(next)) {
                        queue.addLast(next);
                    }
                    continue;
                }
                if (countedBlocks.size() >= MAX_COUNTED_BLOCKS) {
                    truncated = true;
                    continue;
                }
                if (!nextState.isAir()) {
                    torchCount += countBlock(next, nextState, countedBlocks, materialCounts);
                }
            }
        }
        int interiorCells = visitedInterior.size();
        if (interiorCells < MIN_INTERIOR_CELLS || isOutsideLeakTooHigh(outsideLeakEdges, interiorCells)) {
            return HomeScanReport.notInside();
        }
        float averageLight = lightSamples > 0 ? (float) lightSum / (float) lightSamples : 0.0F;
        return HomeScanReport.of(materialCounts, floorCounts, ceilingCounts,
                torchCount, interiorCells, averageLight, truncated);
    }

    private int countBlock(BlockPos pos,
                           BlockState state,
                           Set<BlockPos> countedBlocks,
                           Map<Block, Integer> materialCounts) {
        if (pos == null || state == null || state.isAir()) {
            return 0;
        }
        BlockPos countPos = normalizedCountPos(pos, state);
        if (countPos == null || countedBlocks.contains(countPos)) {
            return 0;
        }
        countedBlocks.add(countPos);
        materialCounts.merge(state.getBlock(), 1, Integer::sum);
        return isTorch(state) ? 1 : 0;
    }

    private void collectSurfaceMaterial(BlockPos pos,
                                        Level level,
                                        Set<BlockPos> countedSurfaceBlocks,
                                        Map<Block, Integer> surfaceCounts) {
        if (pos == null || level == null) {
            return;
        }
        BlockState state = level.getBlockState(pos);
        if (!isStructureBlock(state, pos)) {
            return;
        }
        BlockPos normalized = normalizedCountPos(pos, state);
        if (normalized == null || !countedSurfaceBlocks.add(normalized)) {
            return;
        }
        surfaceCounts.merge(state.getBlock(), 1, Integer::sum);
    }

    private BlockPos normalizedCountPos(BlockPos pos, BlockState state) {
        if (pos == null || state == null) {
            return null;
        }
        if (state.getBlock() instanceof DoorBlock
                && state.hasProperty(DoorBlock.HALF)
                && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER) {
            return pos.below().immutable();
        }
        return pos.immutable();
    }

    private boolean isTorch(BlockState state) {
        return state.is(Blocks.TORCH)
                || state.is(Blocks.WALL_TORCH)
                || state.is(Blocks.SOUL_TORCH)
                || state.is(Blocks.SOUL_WALL_TORCH)
                || state.is(Blocks.REDSTONE_TORCH)
                || state.is(Blocks.REDSTONE_WALL_TORCH);
    }

    private boolean isWithinScanBounds(BlockPos origin, BlockPos pos) {
        int dx = Math.abs(pos.getX() - origin.getX());
        int dy = Math.abs(pos.getY() - origin.getY());
        int dz = Math.abs(pos.getZ() - origin.getZ());
        return dx <= MAX_SCAN_RADIUS && dz <= MAX_SCAN_RADIUS && dy <= MAX_SCAN_HEIGHT;
    }

    private boolean isInsideBuilding(BlockPos origin) {
        if (origin == null) {
            return false;
        }
        BlockPos head = origin.above();
        if (isOutsideCell(origin) || isOutsideCell(head)) {
            return false;
        }
        if (!hasRoof(head)) {
            return false;
        }
        return hasNearbyWalls(head);
    }

    private boolean hasRoof(BlockPos headPos) {
        Level level = owner.level();
        int roofSamples = 0;
        for (int dx = -ROOF_SAMPLE_RADIUS; dx <= ROOF_SAMPLE_RADIUS; dx++) {
            for (int dz = -ROOF_SAMPLE_RADIUS; dz <= ROOF_SAMPLE_RADIUS; dz++) {
                BlockPos sample = headPos.offset(dx, 0, dz);
                for (int i = 1; i <= ROOF_SEARCH_HEIGHT; i++) {
                    BlockPos probe = sample.above(i);
                    if (isStructureBlock(level.getBlockState(probe), probe)) {
                        roofSamples++;
                        break;
                    }
                }
            }
        }
        return roofSamples >= MIN_ROOF_COVERAGE_SAMPLES;
    }

    private boolean hasNearbyWalls(BlockPos headPos) {
        Level level = owner.level();
        int enclosedDirections = 0;
        int enclosedCardinalDirections = 0;
        if (findWallInDirection(level, headPos, 1, 0, WALL_SEARCH_DISTANCE)) {
            enclosedDirections++;
            enclosedCardinalDirections++;
        }
        if (findWallInDirection(level, headPos, -1, 0, WALL_SEARCH_DISTANCE)) {
            enclosedDirections++;
            enclosedCardinalDirections++;
        }
        if (findWallInDirection(level, headPos, 0, 1, WALL_SEARCH_DISTANCE)) {
            enclosedDirections++;
            enclosedCardinalDirections++;
        }
        if (findWallInDirection(level, headPos, 0, -1, WALL_SEARCH_DISTANCE)) {
            enclosedDirections++;
            enclosedCardinalDirections++;
        }
        if (findWallInDirection(level, headPos, 1, 1, WALL_SEARCH_DISTANCE)) {
            enclosedDirections++;
        }
        if (findWallInDirection(level, headPos, 1, -1, WALL_SEARCH_DISTANCE)) {
            enclosedDirections++;
        }
        if (findWallInDirection(level, headPos, -1, 1, WALL_SEARCH_DISTANCE)) {
            enclosedDirections++;
        }
        if (findWallInDirection(level, headPos, -1, -1, WALL_SEARCH_DISTANCE)) {
            enclosedDirections++;
        }
        return enclosedDirections >= REQUIRED_WALL_DIRECTIONS
                && enclosedCardinalDirections >= REQUIRED_CARDINAL_WALL_DIRECTIONS;
    }

    private boolean findWallInDirection(Level level, BlockPos start, int stepX, int stepZ, int maxDistance) {
        for (int i = 1; i <= maxDistance; i++) {
            BlockPos probe = start.offset(stepX * i, 0, stepZ * i);
            BlockState state = level.getBlockState(probe);
            if (isStructureBlock(state, probe)) {
                return true;
            }
            if (isOutsideCell(probe)) {
                return false;
            }
        }
        return false;
    }

    private boolean isStructureBlock(BlockState state, BlockPos pos) {
        if (state == null || state.isAir()) {
            return false;
        }
        if (!state.getFluidState().isEmpty()) {
            return false;
        }
        return !state.getCollisionShape(owner.level(), pos).isEmpty();
    }

    private boolean isInteriorPassable(BlockState state, BlockPos pos) {
        if (state == null) {
            return false;
        }
        if (state.isAir()) {
            return true;
        }
        if (!state.getFluidState().isEmpty()) {
            return false;
        }
        return state.getCollisionShape(owner.level(), pos).isEmpty();
    }

    private boolean isOutsideLeakTooHigh(int outsideLeakEdges, int interiorCells) {
        if (interiorCells <= 0) {
            return true;
        }
        if (outsideLeakEdges <= MAX_OUTSIDE_LEAK_EDGES) {
            return false;
        }
        float leakRatio = (float) outsideLeakEdges / (float) interiorCells;
        return leakRatio > MAX_OUTSIDE_LEAK_RATIO;
    }

    private boolean isOutsideCell(BlockPos pos) {
        return owner.level().canSeeSky(pos) || owner.level().canSeeSky(pos.above());
    }

    private BlockPos pickStartPos() {
        Level level = owner.level();
        BlockPos eyePos = BlockPos.containing(owner.getEyePosition());
        if (isInteriorPassable(level.getBlockState(eyePos), eyePos)) {
            return eyePos.immutable();
        }
        BlockPos head = owner.blockPosition().above();
        if (isInteriorPassable(level.getBlockState(head), head)) {
            return head.immutable();
        }
        return owner.blockPosition().immutable();
    }

    private void sendRoomsQuestion(ServerPlayer player) {
        if (player == null) {
            return;
        }
        owner.sendReply(player, Component.translatable(ROOMS_REMOVE_KEY));
        MutableComponent question = Component.translatable(ROOMS_OFFER_KEY)
                .append(Component.literal(" "));
        Component wholeHouseButton = Component.translatable(ROOMS_BUTTON_DONE_KEY)
                .withStyle(style -> style.withColor(ChatFormatting.GREEN)
                        .withBold(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                                "/ainpc msg " + ROOMS_BUTTON_DONE_TOKEN)));
        Component moreRoomsButton = Component.translatable(ROOMS_BUTTON_MORE_KEY)
                .withStyle(style -> style.withColor(ChatFormatting.AQUA)
                        .withBold(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                                "/ainpc msg " + ROOMS_BUTTON_MORE_TOKEN)));
        question.append(wholeHouseButton)
                .append(Component.literal(" "))
                .append(moreRoomsButton);
        owner.sendReply(player, question);
    }

    private boolean isWholeHouseAnswer(String normalized) {
        if (normalized == null || normalized.isEmpty()) {
            return false;
        }
        return ROOMS_BUTTON_DONE_TOKEN.toLowerCase(Locale.ROOT).equals(normalized)
                || normalized.contains("\u0432\u0435\u0441\u044c \u0434\u043e\u043c")
                || normalized.contains("\u044d\u0442\u043e \u0432\u0435\u0441\u044c \u0434\u043e\u043c")
                || normalized.contains("whole house")
                || normalized.contains("all house");
    }

    private boolean isMoreRoomsAnswer(String normalized) {
        if (normalized == null || normalized.isEmpty()) {
            return false;
        }
        return ROOMS_BUTTON_MORE_TOKEN.toLowerCase(Locale.ROOT).equals(normalized)
                || normalized.contains("\u0435\u0449\u0435 \u043a\u043e\u043c\u043d\u0430\u0442")
                || normalized.contains("\u0435\u0441\u0442\u044c \u0435\u0449\u0435 \u043a\u043e\u043c\u043d\u0430\u0442")
                || normalized.contains("more room")
                || normalized.contains("more rooms");
    }

    private boolean isNextRoomCommand(String normalized) {
        if (normalized == null || normalized.isEmpty()) {
            return false;
        }
        return normalized.equals(ROOMS_NEXT_COMMAND_RU)
                || normalized.equals(ROOMS_NEXT_COMMAND_EN);
    }

    private void queuePendingRoom(HomeScanReport report, BlockPos origin) {
        pendingRoomReport = report;
        pendingRoomOrigin = origin != null ? origin.immutable() : null;
    }

    private void commitPendingRoom(String roomName) {
        if (pendingRoomReport == null || !pendingRoomReport.insideBuilding) {
            pendingRoomReport = null;
            pendingRoomOrigin = null;
            return;
        }
        collectedRoomReports.add(pendingRoomReport);
        rememberVisitedRoom(pendingRoomReport, pendingRoomOrigin);
        collectedRoomNames.add(roomName);
        pendingRoomReport = null;
        pendingRoomOrigin = null;
    }

    private void commitPendingRoomWithoutName() {
        if (pendingRoomReport == null || !pendingRoomReport.insideBuilding) {
            pendingRoomReport = null;
            pendingRoomOrigin = null;
            return;
        }
        collectedRoomReports.add(pendingRoomReport);
        rememberVisitedRoom(pendingRoomReport, pendingRoomOrigin);
        pendingRoomReport = null;
        pendingRoomOrigin = null;
    }

    private String sanitizeRoomName(String rawMessage) {
        if (rawMessage == null) {
            return "";
        }
        String roomName = rawMessage
                .replaceAll("\\s+", " ")
                .trim();
        if (roomName.isEmpty() || roomName.startsWith("/")) {
            return "";
        }
        if (roomName.length() > MAX_ROOM_NAME_LENGTH) {
            roomName = roomName.substring(0, MAX_ROOM_NAME_LENGTH).trim();
        }
        return roomName;
    }

    private boolean isDuplicateRoomName(String candidateName) {
        String candidateCanonical = canonicalRoomName(candidateName);
        if (candidateCanonical.isEmpty()) {
            return false;
        }
        for (String existingName : collectedRoomNames) {
            String existingCanonical = canonicalRoomName(existingName);
            if (!existingCanonical.isEmpty() && existingCanonical.equals(candidateCanonical)) {
                return true;
            }
        }
        return false;
    }

    private String canonicalRoomName(String roomName) {
        if (roomName == null) {
            return "";
        }
        String normalized = roomName
                .toLowerCase(Locale.ROOT)
                .replace('ё', 'е')
                .replaceAll("[^\\p{L}\\p{N}\\s]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (normalized.isEmpty()) {
            return "";
        }
        StringBuilder canonical = new StringBuilder();
        for (String token : normalized.split(" ")) {
            if (token.isBlank() || token.equals("комната") || token.equals("room")) {
                continue;
            }
            if (canonical.length() > 0) {
                canonical.append(' ');
            }
            canonical.append(token);
        }
        String result = canonical.toString().trim();
        return result.isEmpty() ? normalized : result;
    }

    private void rememberVisitedRoom(HomeScanReport report, BlockPos origin) {
        if (report == null || !report.insideBuilding || origin == null) {
            return;
        }
        for (RoomVisit roomVisit : visitedRooms) {
            if (isSameRoomOrigin(roomVisit.origin, origin)) {
                return;
            }
        }
        visitedRooms.add(new RoomVisit(origin.immutable()));
    }

    private boolean isAlreadyVisitedRoom(HomeScanReport report, BlockPos origin) {
        if (report == null || !report.insideBuilding || origin == null || visitedRooms.isEmpty()) {
            return false;
        }
        for (RoomVisit roomVisit : visitedRooms) {
            if (isSameRoomOrigin(roomVisit.origin, origin)) {
                return true;
            }
        }
        return false;
    }

    private boolean isSameRoomOrigin(BlockPos first, BlockPos second) {
        if (first == null || second == null) {
            return false;
        }
        long dx = (long) first.getX() - second.getX();
        long dy = (long) first.getY() - second.getY();
        long dz = (long) first.getZ() - second.getZ();
        double distanceSqr = (double) (dx * dx + dy * dy + dz * dz);
        return distanceSqr <= SAME_ROOM_ORIGIN_DISTANCE_SQR;
    }

    private void tickNextRoomFollow(ServerPlayer player, long gameTime) {
        if (player == null) {
            return;
        }
        Vec3 targetPos = player.position();
        if (owner.distanceToSqr(targetPos) <= NEXT_ROOM_FOLLOW_DISTANCE_SQR) {
            if (!owner.getNavigation().isDone()) {
                owner.getNavigation().stop();
            }
            resetNextRoomFollowTracking();
            return;
        }
        if (shouldIssueNextRoomFollowMove(targetPos, gameTime)) {
            owner.getNavigation().moveTo(player, nextRoomFollowSpeed(NEXT_ROOM_FOLLOW_SPEED_BLOCKS_PER_TICK));
            rememberNextRoomFollowMove(targetPos, gameTime);
        }
    }

    private boolean shouldIssueNextRoomFollowMove(Vec3 targetPos, long gameTime) {
        if (targetPos == null) {
            return false;
        }
        BlockPos targetBlock = BlockPos.containing(targetPos);
        if (nextRoomFollowLastTarget == null || !nextRoomFollowLastTarget.equals(targetBlock)) {
            return true;
        }
        if (!owner.getNavigation().isDone()) {
            return false;
        }
        return nextRoomFollowLastMoveTick < 0L || gameTime - nextRoomFollowLastMoveTick >= NEXT_ROOM_REPATH_TICKS;
    }

    private void rememberNextRoomFollowMove(Vec3 targetPos, long gameTime) {
        nextRoomFollowLastTarget = targetPos != null ? BlockPos.containing(targetPos) : null;
        nextRoomFollowLastMoveTick = gameTime;
    }

    private void resetNextRoomFollowTracking() {
        nextRoomFollowLastTarget = null;
        nextRoomFollowLastMoveTick = -1L;
    }

    private double nextRoomFollowSpeed(double desiredSpeed) {
        return CompanionMovementSpeed.strictByAttribute(owner, desiredSpeed);
    }

    private void finalizeAssessment(ServerPlayer player, long gameTime) {
        if (player == null) {
            return;
        }
        HomeScanReport report = mergeCollectedReports();
        if (!report.insideBuilding) {
            owner.sendReply(player, Component.translatable(OUTSIDE_KEY));
            return;
        }
        int signature = buildReportSignature(report);
        if (hasLastAssessment && signature == lastAssessmentSignature) {
            owner.sendReply(player, Component.translatable(UNCHANGED_KEY));
            return;
        }
        cacheAssessment(report, gameTime);
        owner.sendReply(player, Component.translatable(UPDATED_KEY));
    }

    private HomeScanReport mergeCollectedReports() {
        if (collectedRoomReports.isEmpty()) {
            return HomeScanReport.notInside();
        }
        Map<Block, Integer> materialCounts = new HashMap<>();
        Map<Block, Integer> floorCounts = new HashMap<>();
        Map<Block, Integer> ceilingCounts = new HashMap<>();
        int torchCount = 0;
        int interiorCells = 0;
        double weightedLight = 0.0D;
        boolean truncated = false;

        for (HomeScanReport roomReport : collectedRoomReports) {
            if (roomReport == null || !roomReport.insideBuilding) {
                continue;
            }
            mergeCounts(materialCounts, roomReport.materialCounts);
            mergeCounts(floorCounts, roomReport.floorCounts);
            mergeCounts(ceilingCounts, roomReport.ceilingCounts);
            torchCount += roomReport.torchCount;
            int roomCells = Math.max(1, roomReport.interiorCells);
            interiorCells += roomCells;
            weightedLight += roomReport.averageLight * roomCells;
            truncated = truncated || roomReport.truncated;
        }
        if (interiorCells <= 0) {
            return HomeScanReport.notInside();
        }
        float averageLight = (float) (weightedLight / interiorCells);
        return HomeScanReport.of(materialCounts, floorCounts, ceilingCounts,
                torchCount, interiorCells, averageLight, truncated);
    }

    private void mergeCounts(Map<Block, Integer> target, Map<Block, Integer> source) {
        if (target == null || source == null || source.isEmpty()) {
            return;
        }
        for (Map.Entry<Block, Integer> entry : source.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue() <= 0) {
                continue;
            }
            target.merge(entry.getKey(), entry.getValue(), Integer::sum);
        }
    }

    private static final class RoomVisit {
        private final BlockPos origin;

        private RoomVisit(BlockPos origin) {
            this.origin = origin;
        }
    }

    private void sendTemporaryReport(ServerPlayer player, HomeScanReport report) {
        if (player == null || report == null || !report.insideBuilding) {
            return;
        }
        int roundedLight = Math.max(0, Math.min(15, Math.round(report.averageLight)));
        Component lightState = Component.translatable(
                report.averageLight >= LIGHT_BRIGHT_THRESHOLD ? LIGHT_BRIGHT_KEY : LIGHT_DARK_KEY);
        owner.sendReply(player, Component.translatable(LIGHT_KEY, lightState, roundedLight));
        owner.sendReply(player, Component.translatable(FLOOR_KEY, summarizeMaterials(report.floorCounts, 6)));
        owner.sendReply(player, Component.translatable(CEILING_KEY, summarizeMaterials(report.ceilingCounts, 6)));
        if (!collectedRoomNames.isEmpty()) {
            owner.sendReply(player, Component.translatable(ROOMS_LIST_KEY, formatCollectedRoomNames()));
        }
        owner.sendReply(player, Component.translatable(MATERIALS_KEY));
        sendMaterialLines(player, report.materialCounts, 10);
        if (report.truncated) {
            owner.sendReply(player, Component.translatable(TRUNCATED_KEY));
        }
    }

    private void sendMaterialLines(ServerPlayer player, Map<Block, Integer> counts, int maxLines) {
        if (player == null) {
            return;
        }
        if (counts == null || counts.isEmpty()) {
            owner.sendReply(player, Component.literal("- ").append(Component.translatable(UNKNOWN_KEY)));
            return;
        }
        List<Map.Entry<Block, Integer>> entries = new ArrayList<>(counts.entrySet());
        entries.sort(Comparator
                .<Map.Entry<Block, Integer>>comparingInt(entry -> entry.getValue()).reversed()
                .thenComparing(entry -> entry.getKey().getDescriptionId()));
        int limit = Math.max(1, maxLines);
        for (int i = 0; i < entries.size() && i < limit; i++) {
            Map.Entry<Block, Integer> entry = entries.get(i);
            owner.sendReply(player, Component.literal("- ")
                    .append(readableBlockName(entry.getKey()))
                    .append(Component.literal(" x" + entry.getValue())));
        }
        if (entries.size() > limit) {
            owner.sendReply(player, Component.literal("- ..."));
        }
    }

    private Component summarizeMaterials(Map<Block, Integer> counts, int maxItems) {
        if (counts == null || counts.isEmpty()) {
            return Component.translatable(UNKNOWN_KEY);
        }
        List<Map.Entry<Block, Integer>> entries = new ArrayList<>(counts.entrySet());
        entries.sort(Comparator
                .<Map.Entry<Block, Integer>>comparingInt(entry -> entry.getValue()).reversed()
                .thenComparing(entry -> entry.getKey().getDescriptionId()));
        int limit = Math.max(1, maxItems);
        MutableComponent line = Component.empty();
        for (int i = 0; i < entries.size() && i < limit; i++) {
            Map.Entry<Block, Integer> entry = entries.get(i);
            if (i > 0) {
                line.append(Component.literal(", "));
            }
            line.append(readableBlockName(entry.getKey()))
                    .append(Component.literal(" x" + entry.getValue()));
        }
        if (entries.size() > limit) {
            line.append(Component.literal(", ..."));
        }
        return line;
    }

    private String formatCollectedRoomNames() {
        if (collectedRoomNames.isEmpty()) {
            return Component.translatable(UNKNOWN_KEY).getString();
        }
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < collectedRoomNames.size(); i++) {
            if (i > 0) {
                result.append(", ");
            }
            result.append(i + 1).append(") ").append(collectedRoomNames.get(i));
        }
        return result.toString();
    }

    private Component readableBlockName(Block block) {
        if (block == null) {
            return Component.translatable(UNKNOWN_KEY);
        }
        String blockId = blockRegistryId(block);
        Component translated = block.getName();
        String translatedText = translated.getString();
        String descriptionId = block.getDescriptionId();
        if (translatedText != null
                && !translatedText.isBlank()
                && !translatedText.equals(descriptionId)
                && !isTechnicalName(translatedText)) {
            return translated;
        }
        Item item = block.asItem();
        if (item != Items.AIR) {
            String itemName = item.getDescription().getString();
            String itemDescriptionId = item.getDescriptionId();
            if (itemName != null
                    && !itemName.isBlank()
                    && !itemName.equals(itemDescriptionId)
                    && !isTechnicalName(itemName)) {
                return Component.literal(itemName);
            }
            String itemId = itemRegistryId(item);
            String humanizedItem = humanizeRegistryId(itemId);
            if (!humanizedItem.isBlank()) {
                return Component.literal(humanizedItem);
            }
        }
        String humanizedBlock = humanizeRegistryId(blockId);
        if (!humanizedBlock.isBlank()) {
            return Component.literal(humanizedBlock);
        }
        return Component.translatable(UNKNOWN_KEY);
    }

    private String blockRegistryId(Block block) {
        if (block == null) {
            return "unknown";
        }
        ResourceLocation key = ForgeRegistries.BLOCKS.getKey(block);
        if (key == null) {
            return block.getDescriptionId();
        }
        return key.toString();
    }

    private String itemRegistryId(Item item) {
        if (item == null) {
            return "";
        }
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(item);
        if (key == null) {
            return item.getDescriptionId();
        }
        return key.toString();
    }

    private boolean isTechnicalName(String text) {
        if (text == null) {
            return true;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        return normalized.startsWith("block.")
                || normalized.startsWith("item.")
                || normalized.contains(":");
    }

    private String humanizeRegistryId(String registryId) {
        if (registryId == null || registryId.isBlank()) {
            return "";
        }
        String path = registryId;
        int colon = registryId.indexOf(':');
        if (colon >= 0 && colon + 1 < registryId.length()) {
            path = registryId.substring(colon + 1);
        }
        path = path.replace('/', ' ').replace('_', ' ').replace('-', ' ').trim();
        if (path.isBlank()) {
            return "";
        }
        StringBuilder result = new StringBuilder(path.length());
        for (String token : path.split("\\s+")) {
            if (token.isBlank()) {
                continue;
            }
            if (result.length() > 0) {
                result.append(' ');
            }
            if (token.length() == 1) {
                result.append(token.toUpperCase(Locale.ROOT));
            } else {
                result.append(Character.toUpperCase(token.charAt(0)))
                        .append(token.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return result.toString();
    }

    private String payloadBlockLabel(Block block) {
        String blockId = blockRegistryId(block);
        String displayName = readableBlockName(block).getString();
        if (displayName == null || displayName.isBlank() || displayName.equals(blockId)) {
            return blockId;
        }
        if (displayName.contains("[" + blockId + "]")) {
            return displayName;
        }
        return displayName + " [" + blockId + "]";
    }

    private void cacheAssessment(HomeScanReport report, long gameTime) {
        if (report == null || !report.insideBuilding) {
            return;
        }
        lastAssessmentSignature = buildReportSignature(report);
        hasLastAssessment = true;
        nextAssessmentAllowedTick = gameTime + ASSESS_COOLDOWN_TICKS;
        lastAssessmentPayload = buildLlmAssessmentPayload(report);
    }

    private int buildReportSignature(HomeScanReport report) {
        if (report == null || !report.insideBuilding) {
            return 0;
        }
        int hash = 1;
        hash = 31 * hash + report.interiorCells;
        hash = 31 * hash + report.torchCount;
        hash = 31 * hash + Math.round(report.averageLight * 10.0F);
        hash = 31 * hash + materialMapSignature(report.materialCounts);
        hash = 31 * hash + materialMapSignature(report.floorCounts);
        hash = 31 * hash + materialMapSignature(report.ceilingCounts);
        hash = 31 * hash + roomNamesSignature();
        return hash;
    }

    private int materialMapSignature(Map<Block, Integer> counts) {
        if (counts == null || counts.isEmpty()) {
            return 0;
        }
        List<Map.Entry<Block, Integer>> entries = new ArrayList<>(counts.entrySet());
        entries.sort(Comparator.comparing(entry -> entry.getKey().getDescriptionId()));
        int hash = 1;
        for (Map.Entry<Block, Integer> entry : entries) {
            hash = 31 * hash + entry.getKey().getDescriptionId().hashCode();
            hash = 31 * hash + entry.getValue();
        }
        return hash;
    }

    private int roomNamesSignature() {
        if (collectedRoomNames.isEmpty()) {
            return 0;
        }
        int hash = 1;
        for (String roomName : collectedRoomNames) {
            if (roomName == null) {
                continue;
            }
            hash = 31 * hash + roomName.toLowerCase(Locale.ROOT).hashCode();
        }
        return hash;
    }

    private String buildLlmAssessmentPayload(HomeScanReport report) {
        int totalBlocks = 0;
        for (int count : report.materialCounts.values()) {
            totalBlocks += count;
        }
        int roundedLight = Math.max(0, Math.min(15, Math.round(report.averageLight)));
        String lightStatus = report.averageLight >= LIGHT_BRIGHT_THRESHOLD ? "bright" : "dark";
        StringBuilder payload = new StringBuilder(512);
        payload.append("home_assessment_report_v1").append('\n');
        payload.append("inside_building=").append(report.insideBuilding).append('\n');
        payload.append("interior_cells=").append(report.interiorCells).append('\n');
        payload.append("total_blocks=").append(totalBlocks).append('\n');
        payload.append("torches=").append(report.torchCount).append('\n');
        payload.append("light_status=").append(lightStatus).append('\n');
        payload.append("average_light=").append(roundedLight).append("/15").append('\n');
        payload.append("rooms_count=").append(collectedRoomNames.size()).append('\n');
        payload.append("rooms_named=").append(formatCollectedRoomNames()).append('\n');
        payload.append("floor_top=").append(summarizeMaterials(report.floorCounts, 6).getString()).append('\n');
        payload.append("ceiling_top=").append(summarizeMaterials(report.ceilingCounts, 6).getString()).append('\n');
        payload.append("materials_overview=Материалы дома (временный отчет): ").append('\n');
        List<Map.Entry<Block, Integer>> entries = new ArrayList<>(report.materialCounts.entrySet());
        entries.sort(Comparator
                .<Map.Entry<Block, Integer>>comparingInt(entry -> entry.getValue()).reversed()
                .thenComparing(entry -> entry.getKey().getDescriptionId()));
        for (Map.Entry<Block, Integer> entry : entries) {
            payload.append("- ").append(payloadBlockLabel(entry.getKey()))
                    .append(": ").append(entry.getValue()).append('\n');
        }
        payload.append("truncated=").append(report.truncated);
        return payload.toString();
    }

    private int secondsLeft(long untilTick, long gameTime) {
        long diff = Math.max(0L, untilTick - gameTime);
        return (int) ((diff + 19L) / 20L);
    }

    private String normalize(String message) {
        if (message == null) {
            return "";
        }
        return message
                .toLowerCase(Locale.ROOT)
                .replace('ё', 'е')
                .replaceAll("[^\\p{L}\\p{N}\\s]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static final class HomeScanReport {
        private final boolean insideBuilding;
        private final Map<Block, Integer> materialCounts;
        private final Map<Block, Integer> floorCounts;
        private final Map<Block, Integer> ceilingCounts;
        private final int torchCount;
        private final int interiorCells;
        private final float averageLight;
        private final boolean truncated;

        private HomeScanReport(boolean insideBuilding,
                               Map<Block, Integer> materialCounts,
                               Map<Block, Integer> floorCounts,
                               Map<Block, Integer> ceilingCounts,
                               int torchCount,
                               int interiorCells,
                               float averageLight,
                               boolean truncated) {
            this.insideBuilding = insideBuilding;
            this.materialCounts = materialCounts;
            this.floorCounts = floorCounts;
            this.ceilingCounts = ceilingCounts;
            this.torchCount = torchCount;
            this.interiorCells = interiorCells;
            this.averageLight = averageLight;
            this.truncated = truncated;
        }

        private static HomeScanReport of(Map<Block, Integer> materialCounts,
                                         Map<Block, Integer> floorCounts,
                                         Map<Block, Integer> ceilingCounts,
                                         int torchCount,
                                         int interiorCells,
                                         float averageLight,
                                         boolean truncated) {
            return new HomeScanReport(true, materialCounts, floorCounts, ceilingCounts,
                    torchCount, interiorCells, averageLight, truncated);
        }

        private static HomeScanReport notInside() {
            return new HomeScanReport(false, Map.of(), Map.of(), Map.of(), 0, 0, 0.0F, false);
        }
    }
}
