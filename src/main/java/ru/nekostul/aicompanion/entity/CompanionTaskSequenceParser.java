package ru.nekostul.aicompanion.entity;

import ru.nekostul.aicompanion.entity.command.CompanionCommandParser;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

final class CompanionTaskSequenceParser {
    static final class SequenceTask {
        private final CompanionCommandParser.CommandRequest request;
        private final String originalText;
        private final int order;

        SequenceTask(CompanionCommandParser.CommandRequest request, String originalText, int order) {
            this.request = request;
            this.originalText = originalText;
            this.order = order;
        }

        CompanionCommandParser.CommandRequest request() {
            return request;
        }

        String originalText() {
            return originalText;
        }

        int order() {
            return order;
        }
    }

    static final class SequenceParseResult {
        private final boolean sequenceCommand;
        private final List<SequenceTask> tasks;
        private final String failedSegment;
        private final CompanionCommandParser.ParseError parseError;

        private SequenceParseResult(boolean sequenceCommand,
                                    List<SequenceTask> tasks,
                                    String failedSegment,
                                    CompanionCommandParser.ParseError parseError) {
            this.sequenceCommand = sequenceCommand;
            this.tasks = tasks;
            this.failedSegment = failedSegment;
            this.parseError = parseError;
        }

        static SequenceParseResult notSequence() {
            return new SequenceParseResult(false, List.of(), "", CompanionCommandParser.ParseError.NONE);
        }

        static SequenceParseResult failed(String failedSegment, CompanionCommandParser.ParseError parseError) {
            return new SequenceParseResult(true, List.of(), failedSegment, parseError);
        }

        static SequenceParseResult success(List<SequenceTask> tasks) {
            return new SequenceParseResult(true, tasks, "", CompanionCommandParser.ParseError.NONE);
        }

        boolean isSequenceCommand() {
            return sequenceCommand;
        }

        boolean isValid() {
            return !tasks.isEmpty();
        }

        List<SequenceTask> tasks() {
            return tasks;
        }

        String failedSegment() {
            return failedSegment;
        }

        CompanionCommandParser.ParseError parseError() {
            return parseError;
        }
    }

    private static final Pattern SEQUENCE_MARKER_PATTERN = Pattern.compile(
            "(?iu)(?:^|\\s)(?:\\u043f\\u043e\\u0442\\u043e\\u043c|\\u0437\\u0430\\u0442\\u0435\\u043c|then)(?:\\s|$)");
    private static final Pattern SEQUENCE_DELIMITER_PATTERN = Pattern.compile(
            "(?iu)\\s+(?:\\u043f\\u043e\\u0442\\u043e\\u043c|\\u0437\\u0430\\u0442\\u0435\\u043c|then)\\s+");
    private static final Pattern SEQUENCE_AND_PATTERN = Pattern.compile(
            "(?iu)(?:^|\\s)(?:\\u0438|and)(?:\\s|$)");
    private static final Pattern SEQUENCE_AND_DELIMITER_PATTERN = Pattern.compile(
            "(?iu)\\s+(?:\\u0438|and)\\s+");
    private static final Pattern SEQUENCE_FIRST_PREFIX_PATTERN = Pattern.compile(
            "(?iu)^(?:\\u0441\\u043d\\u0430\\u0447\\u0430\\u043b\\u0430|first|firstly)\\s+");
    private static final Pattern SEQUENCE_NEXT_PREFIX_PATTERN = Pattern.compile(
            "(?iu)^(?:\\u043f\\u043e\\u0442\\u043e\\u043c|\\u0437\\u0430\\u0442\\u0435\\u043c|then|\\u0438|and)\\s+");

    private final CompanionCommandParser commandParser;

    CompanionTaskSequenceParser(CompanionCommandParser commandParser) {
        this.commandParser = commandParser;
    }

    SequenceParseResult parse(String message) {
        if (message == null || message.isBlank()) {
            return SequenceParseResult.notSequence();
        }
        boolean hasThenMarker = SEQUENCE_MARKER_PATTERN.matcher(message).find();
        boolean hasAndMarker = SEQUENCE_AND_PATTERN.matcher(message).find();
        if (!hasThenMarker && !hasAndMarker) {
            return SequenceParseResult.notSequence();
        }
        String prepared = message;
        if (hasThenMarker) {
            prepared = SEQUENCE_DELIMITER_PATTERN.matcher(prepared).replaceAll(",");
        }
        if (hasAndMarker) {
            prepared = SEQUENCE_AND_DELIMITER_PATTERN.matcher(prepared).replaceAll(",");
        }
        String[] rawParts = prepared.split("[,;]");
        List<SequenceTask> tasks = new ArrayList<>();
        CompanionCommandParser.TaskAction fallbackAction = null;
        int order = 1;
        for (String rawPart : rawParts) {
            String part = normalizeTaskText(rawPart);
            if (part.isEmpty()) {
                continue;
            }
            CompanionCommandParser.ParseResult parsed = commandParser.parseDetailed(part, fallbackAction);
            if (parsed.getRequest() == null) {
                return SequenceParseResult.failed(part, parsed.getError());
            }
            fallbackAction = parsed.getRequest().getTaskAction();
            tasks.add(new SequenceTask(parsed.getRequest(), part, order));
            order++;
        }
        if (tasks.size() < 2) {
            return SequenceParseResult.notSequence();
        }
        return SequenceParseResult.success(tasks);
    }

    static String normalizeTaskText(String rawPart) {
        if (rawPart == null) {
            return "";
        }
        String trimmed = rawPart.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        String withoutFirst = SEQUENCE_FIRST_PREFIX_PATTERN.matcher(trimmed).replaceFirst("");
        String withoutNext = SEQUENCE_NEXT_PREFIX_PATTERN.matcher(withoutFirst).replaceFirst("");
        return withoutNext.trim();
    }
}
