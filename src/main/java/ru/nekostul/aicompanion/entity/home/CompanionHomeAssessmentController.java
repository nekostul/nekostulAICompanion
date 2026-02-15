package ru.nekostul.aicompanion.entity.home;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import ru.nekostul.aicompanion.entity.CompanionEntity;

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
    private static final int ROOF_SEARCH_HEIGHT = 20;
    private static final int WALL_SEARCH_DISTANCE = 16;
    private static final int REQUIRED_WALL_DIRECTIONS = 2;

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

    private final CompanionEntity owner;
    private final RandomSource random;

    private UUID activePlayerId;
    private final List<LookStep> lookSteps = new ArrayList<>();
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
        if (player == null) {
            return false;
        }
        HomeScanReport previewReport = scanHome();
        if (!previewReport.insideBuilding) {
            owner.sendReply(player, Component.translatable(OUTSIDE_KEY));
            cancel();
            return false;
        }
        int previewSignature = buildReportSignature(previewReport);
        if (hasLastAssessment && previewSignature == lastAssessmentSignature) {
            owner.sendReply(player, Component.translatable(UNCHANGED_KEY));
            cancel();
            return false;
        }
        if (nextAssessmentAllowedTick >= 0L && gameTime < nextAssessmentAllowedTick) {
            int secondsLeft = secondsLeft(nextAssessmentAllowedTick, gameTime);
            owner.sendReply(player, Component.translatable(COOLDOWN_KEY, secondsLeft / 60, secondsLeft % 60));
            cancel();
            return false;
        }
        activePlayerId = player.getUUID();
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

    public Result tick(long gameTime) {
        if (activePlayerId == null) {
            return Result.IDLE;
        }
        if (!(owner.getPlayerById(activePlayerId) instanceof ServerPlayer player)) {
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
        finalizeAssessment(player, report, gameTime);
        cancel();
        return Result.DONE;
    }

    public void cancel() {
        activePlayerId = null;
        lookSteps.clear();
        lookStepIndex = -1;
        lookStepStartedTick = -1L;
        lookStepDurationTicks = 0;
        lookStepStartYaw = 0.0F;
        lookStepStartPitch = 0.0F;
        lookStepTargetYaw = 0.0F;
        lookStepTargetPitch = 0.0F;
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
                if (isInteriorPassable(nextState, next) && !isOutsideCell(next)) {
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
        float averageLight = lightSamples > 0 ? (float) lightSum / (float) lightSamples : 0.0F;
        return HomeScanReport.of(materialCounts, floorCounts, ceilingCounts,
                torchCount, visitedInterior.size(), averageLight, truncated);
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
        if (!hasRoof(head)) {
            return false;
        }
        return hasNearbyWalls(head);
    }

    private boolean hasRoof(BlockPos headPos) {
        Level level = owner.level();
        for (int i = 1; i <= ROOF_SEARCH_HEIGHT; i++) {
            BlockPos probe = headPos.above(i);
            if (isStructureBlock(level.getBlockState(probe), probe)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasNearbyWalls(BlockPos headPos) {
        Level level = owner.level();
        int wallDirections = 0;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (findWallInDirection(level, headPos, direction, WALL_SEARCH_DISTANCE)) {
                wallDirections++;
            }
        }
        return wallDirections >= REQUIRED_WALL_DIRECTIONS;
    }

    private boolean findWallInDirection(Level level, BlockPos start, Direction direction, int maxDistance) {
        for (int i = 1; i <= maxDistance; i++) {
            BlockPos probe = start.relative(direction, i);
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

    private void finalizeAssessment(ServerPlayer player, HomeScanReport report, long gameTime) {
        if (player == null) {
            return;
        }
        if (!report.insideBuilding) {
            owner.sendReply(player, Component.translatable(OUTSIDE_KEY));
            return;
        }
        cacheAssessment(report, gameTime);
        owner.sendReply(player, Component.translatable(UPDATED_KEY));
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
            line.append(entry.getKey().getName())
                    .append(Component.literal(" x" + entry.getValue()));
        }
        if (entries.size() > limit) {
            line.append(Component.literal(", ..."));
        }
        return line;
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
        payload.append("floor_top=").append(summarizeMaterials(report.floorCounts, 6).getString()).append('\n');
        payload.append("ceiling_top=").append(summarizeMaterials(report.ceilingCounts, 6).getString()).append('\n');
        payload.append("materials_overview=Материалы дома (временный отчет): ").append('\n');
        List<Map.Entry<Block, Integer>> entries = new ArrayList<>(report.materialCounts.entrySet());
        entries.sort(Comparator
                .<Map.Entry<Block, Integer>>comparingInt(entry -> entry.getValue()).reversed()
                .thenComparing(entry -> entry.getKey().getDescriptionId()));
        for (Map.Entry<Block, Integer> entry : entries) {
            payload.append("- ").append(entry.getKey().getDescriptionId())
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
