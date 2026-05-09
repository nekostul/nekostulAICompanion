package ru.nekostul.aicompanion.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

import ru.nekostul.aicompanion.AiCompanionMod;
import ru.nekostul.aicompanion.entity.CompanionEntity;

public class CompanionRenderer extends MobRenderer<CompanionEntity, PlayerModel<CompanionEntity>> {
    private static final ResourceLocation TEXTURE =
            new ResourceLocation(AiCompanionMod.MOD_ID, "textures/entity/companion.png");

    public CompanionRenderer(EntityRendererProvider.Context context) {
        super(context, new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER), false), 0.5F);
        this.addLayer(new HumanoidArmorLayer<>(this,
                new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
                new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)),
                context.getModelManager()));
        this.addLayer(new ItemInHandLayer<>(this, context.getItemInHandRenderer()));
    }

    @Override
    public ResourceLocation getTextureLocation(CompanionEntity entity) {
        return CompanionSkinManager.getTexture(entity, TEXTURE);
    }

    @Override
    protected void setupRotations(CompanionEntity entity, PoseStack poseStack, float ageInTicks, float rotationYaw,
                                  float partialTicks) {
        super.setupRotations(entity, poseStack, ageInTicks, rotationYaw, partialTicks);
        float swimAmount = entity.getSwimAmount(partialTicks);
        if (swimAmount <= 0.0F) {
            return;
        }
        float baseRotation = entity.isInWaterOrBubble() ? -90.0F - entity.getXRot() : -90.0F;
        float swimRotation = Mth.lerp(swimAmount, 0.0F, baseRotation);
        poseStack.mulPose(Axis.XP.rotationDegrees(swimRotation));
        if (entity.isVisuallySwimming()) {
            poseStack.translate(0.0F, -1.0F, 0.3F);
        }
    }
}
