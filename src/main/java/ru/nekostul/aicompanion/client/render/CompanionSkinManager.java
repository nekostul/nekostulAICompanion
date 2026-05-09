package ru.nekostul.aicompanion.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import ru.nekostul.aicompanion.AiCompanionMod;
import ru.nekostul.aicompanion.entity.CompanionEntity;
import ru.nekostul.aicompanion.skin.CompanionSkinStorage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class CompanionSkinManager {
    private static final Map<String, ResourceLocation> SKIN_CACHE = new ConcurrentHashMap<>();

    private CompanionSkinManager() {
    }

    public static ResourceLocation getTexture(CompanionEntity entity, ResourceLocation defaultTexture) {
        if (entity == null) {
            return defaultTexture;
        }
        String skinName = entity.getCustomSkinName();
        if (skinName == null || skinName.isBlank()) {
            return defaultTexture;
        }
        ResourceLocation cached = SKIN_CACHE.get(skinName);
        if (cached != null) {
            return cached;
        }
        Optional<Path> skinFile = CompanionSkinStorage.findSkinFile(skinName);
        if (skinFile.isEmpty()) {
            return defaultTexture;
        }
        try (InputStream inputStream = Files.newInputStream(skinFile.get())) {
            NativeImage image = NativeImage.read(inputStream);
            ResourceLocation location = new ResourceLocation(
                    AiCompanionMod.MOD_ID,
                    "dynamic/skin/" + UUID.nameUUIDFromBytes(skinName.getBytes(StandardCharsets.UTF_8))
                            .toString()
                            .replace("-", "")
            );
            Minecraft.getInstance().getTextureManager().register(location, new DynamicTexture(image));
            SKIN_CACHE.put(skinName, location);
            return location;
        } catch (IOException | RuntimeException ignored) {
            return defaultTexture;
        }
    }
}
