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
            .comment("Full tree chop: break the whole trunk and leaves after the bottom log.")
            .define("tree.fullChop", true);

    public static final ForgeConfigSpec.BooleanValue NPC_PANEL_BUTTON = BUILDER
            .comment("Show the NPC panel button in the inventory.",
                    "If disabled, the panel can be opened with /ainpc gui.")
            .define("gui.npcPanelButton", true);

    public static final ForgeConfigSpec.IntValue MAX_BLOCKS_PER_TASK = BUILDER
            .comment("Maximum number of regular blocks per single task.",
                    "If player asks for more, amount is automatically clamped to this limit.")
            .defineInRange("limits.maxBlocksPerTask", 64, 1, 4096);

    public static final ForgeConfigSpec.IntValue MAX_TREES_PER_TASK = BUILDER
            .comment("Maximum number of full trees (treechop) per single task.",
                    "If player asks for more, amount is automatically clamped to this limit.")
            .defineInRange("limits.maxTreesPerTask", 5, 1, 256);

    public static final ForgeConfigSpec.BooleanValue OREHARVESTER_INTEGRATION = BUILDER
            .comment("Enable integration with oreharvester mod (modid: oreharvester).",
                    "If enabled and oreharvester is installed, NPC uses oreharvester for full ore mining.",
                    "If disabled or oreharvester is missing, NPC uses default mining logic.")
            .define("oreharvester_integration", true);

    public static final ForgeConfigSpec SPEC = BUILDER.build();

    private CompanionConfig() {
    }

    public static boolean isFullTreeChopEnabled() {
        return FULL_TREE_CHOP.get();
    }

    public static boolean isNpcPanelButtonEnabled() {
        return NPC_PANEL_BUTTON.get();
    }

    public static int getMaxBlocksPerTask() {
        return MAX_BLOCKS_PER_TASK.get();
    }

    public static int getMaxTreesPerTask() {
        return MAX_TREES_PER_TASK.get();
    }

    public static boolean isOreHarvesterIntegrationEnabled() {
        return OREHARVESTER_INTEGRATION.get();
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



