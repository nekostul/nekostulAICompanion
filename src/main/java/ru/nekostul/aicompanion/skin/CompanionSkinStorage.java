package ru.nekostul.aicompanion.skin;

import net.minecraftforge.fml.loading.FMLPaths;
import ru.nekostul.aicompanion.AiCompanionMod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

public final class CompanionSkinStorage {
    private CompanionSkinStorage() {
    }

    public static Path ensureSkinDirectoryExists() {
        Path directory = FMLPaths.GAMEDIR.get().resolve(AiCompanionMod.MOD_ID);
        try {
            Files.createDirectories(directory);
        } catch (IOException ignored) {
        }
        return directory;
    }

    public static String getSkinDirectoryName() {
        return AiCompanionMod.MOD_ID;
    }

    public static String normalizeSkinName(String rawSkinName) {
        if (rawSkinName == null) {
            return "";
        }
        String skinName = rawSkinName.trim();
        if (skinName.regionMatches(true, Math.max(0, skinName.length() - 4), ".png", 0, 4)) {
            skinName = skinName.substring(0, skinName.length() - 4).trim();
        }
        if (skinName.isBlank()) {
            return "";
        }
        if (skinName.indexOf('/') >= 0 || skinName.indexOf('\\') >= 0 || skinName.indexOf(':') >= 0) {
            return "";
        }
        try {
            Path normalized = Path.of(skinName).normalize();
            if (normalized.getNameCount() != 1) {
                return "";
            }
        } catch (InvalidPathException ignored) {
            return "";
        }
        return skinName;
    }

    public static Optional<Path> findSkinFile(String rawSkinName) {
        String skinName = normalizeSkinName(rawSkinName);
        if (skinName.isBlank()) {
            return Optional.empty();
        }
        Path directory = ensureSkinDirectoryExists();
        Path exact = directory.resolve(skinName + ".png");
        if (Files.isRegularFile(exact)) {
            return Optional.of(exact);
        }
        String targetFileName = (skinName + ".png").toLowerCase(Locale.ROOT);
        try (Stream<Path> files = Files.list(directory)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).equals(targetFileName))
                    .findFirst();
        } catch (IOException ignored) {
            return Optional.empty();
        }
    }
}
