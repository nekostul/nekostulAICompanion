package ru.nekostul.aicompanion;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class CompanionConfig {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.BooleanValue FULL_TREE_CHOP = BUILDER
            .comment("Полная рубка деревьев: ломать весь ствол и листву после нижнего блока.")
            .define("tree.fullChop", true);

    public static final ForgeConfigSpec SPEC = BUILDER.build();

    private CompanionConfig() {
    }

    public static boolean isFullTreeChopEnabled() {
        return FULL_TREE_CHOP.get();
    }

    public static void setFullTreeChopEnabled(boolean enabled) {
        FULL_TREE_CHOP.set(enabled);
        persistValue(enabled);
    }

    private static void persistValue(boolean enabled) {
        Path configPath = FMLPaths.CONFIGDIR.get().resolve("aicompanion-common.toml");
        List<String> lines = new ArrayList<>();
        if (Files.exists(configPath)) {
            try {
                lines.addAll(Files.readAllLines(configPath, StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                return;
            }
        }
        boolean updated = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();
            if (trimmed.startsWith("tree.fullChop")) {
                lines.set(i, "tree.fullChop = " + enabled);
                updated = true;
                break;
            }
        }
        if (!updated) {
            lines.add("tree.fullChop = " + enabled);
        }
        try {
            Files.createDirectories(configPath.getParent());
            Files.write(configPath, lines, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }
}
