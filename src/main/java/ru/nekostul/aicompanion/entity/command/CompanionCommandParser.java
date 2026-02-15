package ru.nekostul.aicompanion.entity.command;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ThreadLocalRandom;

import ru.nekostul.aicompanion.entity.resource.CompanionBlockRegistry;
import ru.nekostul.aicompanion.entity.resource.CompanionResourceType;
import ru.nekostul.aicompanion.entity.tree.CompanionTreeRequestMode;

public final class CompanionCommandParser {
    public enum TaskAction {
        GATHER,
        BRING
    }

    public enum ParseError {
        NONE,
        EMPTY_MESSAGE,
        MISSING_ACTION,
        MISSING_RESOURCE,
        INVALID_AMOUNT
    }

    public static final class ParseResult {
        private final CommandRequest request;
        private final ParseError error;

        private ParseResult(CommandRequest request, ParseError error) {
            this.request = request;
            this.error = error;
        }

        public CommandRequest getRequest() {
            return request;
        }

        public ParseError getError() {
            return error;
        }
    }

    public static final class CommandRequest {
        private final TaskAction taskAction;
        private final CompanionResourceType resourceType;
        private final int amount;
        private final CompanionTreeRequestMode treeMode;

        CommandRequest(TaskAction taskAction, CompanionResourceType resourceType, int amount,
                       CompanionTreeRequestMode treeMode) {
            this.taskAction = taskAction;
            this.resourceType = resourceType;
            this.amount = amount;
            this.treeMode = treeMode;
        }

        public TaskAction getTaskAction() {
            return taskAction;
        }

        public CompanionResourceType getResourceType() {
            return resourceType;
        }

        public int getAmount() {
            return amount;
        }

        public CompanionTreeRequestMode getTreeMode() {
            return treeMode;
        }
    }

    private static final Pattern NUMBER_PATTERN = Pattern.compile("(\\d+)");
    private static final int DEFAULT_BUCKET_AMOUNT = 1;
    private static final int DEFAULT_ORE_MIN_AMOUNT = 1;
    private static final int DEFAULT_ORE_MAX_AMOUNT = 5;
    private static final int DEFAULT_BLOCK_MIN_AMOUNT = 1;
    private static final int DEFAULT_BLOCK_MAX_AMOUNT = 16;

    public CommandRequest parse(String message) {
        return parseDetailed(message).getRequest();
    }

    public ParseResult parseDetailed(String message) {
        return parseDetailed(message, null);
    }

    public ParseResult parseDetailed(String message, TaskAction fallbackAction) {
        String normalized = normalize(message);
        if (normalized.isEmpty()) {
            return new ParseResult(null, ParseError.EMPTY_MESSAGE);
        }
        TaskAction taskAction = parseTaskAction(normalized, fallbackAction);
        if (taskAction == null) {
            return new ParseResult(null, ParseError.MISSING_ACTION);
        }
        CompanionResourceType resourceType = parseResourceType(normalized);
        if (resourceType == null) {
            return new ParseResult(null, ParseError.MISSING_RESOURCE);
        }
        CompanionTreeRequestMode treeMode = parseTreeMode(normalized, resourceType);
        int amount = parseAmount(normalized, resourceType, treeMode);
        if (amount <= 0) {
            return new ParseResult(null, ParseError.INVALID_AMOUNT);
        }
        return new ParseResult(new CommandRequest(taskAction, resourceType, amount, treeMode), ParseError.NONE);
    }

    private TaskAction parseTaskAction(String normalized, TaskAction fallbackAction) {
        if (containsBringKeyword(normalized)) {
            return TaskAction.BRING;
        }
        if (containsGatherKeyword(normalized)) {
            return TaskAction.GATHER;
        }
        return fallbackAction;
    }

    private boolean containsGatherKeyword(String normalized) {
        return normalized.contains("\u0434\u043e\u0431\u0443\u0434")
                || normalized.contains("\u0434\u043e\u0431\u044b")
                || normalized.contains("\u0441\u0434\u0435\u043b")
                || normalized.contains("\u0441\u043e\u0431\u0435\u0440")
                || normalized.contains("mine")
                || normalized.contains("gather");
    }

    private boolean containsBringKeyword(String normalized) {
        return normalized.contains("\u043f\u0440\u0438\u043d\u0435\u0441")
                || normalized.contains("\u0434\u0430\u0439")
                || normalized.contains("\u0434\u043e\u0441\u0442\u0430\u043d")
                || normalized.contains("\u0434\u043e\u0441\u0442\u0430\u0442")
                || normalized.contains("bring")
                || normalized.contains("get");
    }

    private CompanionResourceType parseResourceType(String normalized) {
        return CompanionBlockRegistry.findTypeByMessage(normalized);
    }

    private int parseAmount(String normalized, CompanionResourceType resourceType, CompanionTreeRequestMode treeMode) {
        Matcher matcher = NUMBER_PATTERN.matcher(normalized);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
                return defaultAmount(normalized, resourceType, treeMode);
            }
        }
        return defaultAmount(normalized, resourceType, treeMode);
    }

    private int defaultAmount(String normalized, CompanionResourceType resourceType, CompanionTreeRequestMode treeMode) {
        if (treeMode != CompanionTreeRequestMode.NONE) {
            return 1;
        }
        if (resourceType.isBucketResource()) {
            return DEFAULT_BUCKET_AMOUNT;
        }
        if (isOreResource(resourceType)) {
            return randomInclusive(DEFAULT_ORE_MIN_AMOUNT, DEFAULT_ORE_MAX_AMOUNT);
        }
        return randomInclusive(DEFAULT_BLOCK_MIN_AMOUNT, DEFAULT_BLOCK_MAX_AMOUNT);
    }

    private int randomInclusive(int min, int max) {
        if (max <= min) {
            return min;
        }
        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    private boolean isOreResource(CompanionResourceType type) {
        if (type == null) {
            return false;
        }
        return switch (type) {
            case ORE,
                 COAL_ORE,
                 IRON_ORE,
                 COPPER_ORE,
                 GOLD_ORE,
                 REDSTONE_ORE,
                 LAPIS_ORE,
                 DIAMOND_ORE,
                 EMERALD_ORE -> true;
            default -> false;
        };
    }

    private CompanionTreeRequestMode parseTreeMode(String normalized, CompanionResourceType resourceType) {
        if (resourceType != CompanionResourceType.LOG) {
            return CompanionTreeRequestMode.NONE;
        }
        if (!normalized.contains("\u0434\u0435\u0440\u0435\u0432")) {
            return CompanionTreeRequestMode.NONE;
        }
        if (normalized.contains("\u0431\u043b\u043e\u043a")) {
            return CompanionTreeRequestMode.LOG_BLOCKS;
        }
        return CompanionTreeRequestMode.TREE_COUNT;
    }

    private String normalize(String message) {
        return CompanionRussianNormalizer.normalize(message);
    }
}
