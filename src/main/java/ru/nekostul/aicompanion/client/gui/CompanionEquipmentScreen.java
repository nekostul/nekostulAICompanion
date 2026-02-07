package ru.nekostul.aicompanion.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ImageButton;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.EffectRenderingInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeUpdateListener;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import ru.nekostul.aicompanion.AiCompanionMod;
import ru.nekostul.aicompanion.entity.CompanionEntity;

@OnlyIn(Dist.CLIENT)
public final class CompanionEquipmentScreen extends EffectRenderingInventoryScreen<CompanionEquipmentMenu>
        implements RecipeUpdateListener {
    private static final ResourceLocation INVENTORY_LOCATION =
            new ResourceLocation("textures/gui/container/inventory.png");
    private static final ResourceLocation RECIPE_BUTTON_LOCATION =
            new ResourceLocation("textures/gui/recipe_button.png");
    private static final ResourceLocation NPC_BUTTON_LOCATION =
            new ResourceLocation(AiCompanionMod.MOD_ID, "textures/gui/npc_button.png");
    private static final ResourceLocation PICKAXE_ICON =
            new ResourceLocation(AiCompanionMod.MOD_ID, "textures/gui/pickaxe.png");
    private static final ResourceLocation AXE_ICON =
            new ResourceLocation(AiCompanionMod.MOD_ID, "textures/gui/axe.png");
    private static final ResourceLocation SHOVEL_ICON =
            new ResourceLocation(AiCompanionMod.MOD_ID, "textures/gui/shovel.png");
    private static final ResourceLocation SWORD_ICON =
            new ResourceLocation(AiCompanionMod.MOD_ID, "textures/gui/sword.png");

    private float xMouse;
    private float yMouse;
    private final RecipeBookComponent recipeBookComponent = new RecipeBookComponent();
    private boolean widthTooNarrow;
    private boolean buttonClicked;
    private ImageButton recipeButton;
    private ImageButton npcButton;
    private boolean npcPanelOpen;
    private float npcPanelProgress;
    private int npcPanelOffsetX = -CompanionEquipmentMenu.NPC_PANEL_WIDTH;

    public CompanionEquipmentScreen(CompanionEquipmentMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.titleLabelX = 97;
    }

    @Override
    public void containerTick() {
        if (this.minecraft.gameMode.hasInfiniteItems()) {
            this.minecraft.setScreen(new CreativeModeInventoryScreen(this.minecraft.player,
                    this.minecraft.player.connection.enabledFeatures(),
                    this.minecraft.options.operatorItemsTab().get()));
        } else {
            this.recipeBookComponent.tick();
            float target = this.npcPanelOpen ? 1.0F : 0.0F;
            if (Math.abs(this.npcPanelProgress - target) > 0.001F) {
                this.npcPanelProgress = Mth.approach(this.npcPanelProgress, target, 0.2F);
                updateLayout();
            }
        }
    }

    @Override
    protected void init() {
        if (this.minecraft.gameMode.hasInfiniteItems()) {
            this.minecraft.setScreen(new CreativeModeInventoryScreen(this.minecraft.player,
                    this.minecraft.player.connection.enabledFeatures(),
                    this.minecraft.options.operatorItemsTab().get()));
        } else {
            super.init();
            this.widthTooNarrow = this.width < 379;
            this.recipeBookComponent.init(this.width, this.height, this.minecraft, this.widthTooNarrow, this.menu);
            this.npcPanelOpen = this.menu.isNpcPanelOpenByDefault();
            this.npcPanelProgress = this.npcPanelOpen ? 1.0F : 0.0F;
            updateLayout();

            this.recipeButton = this.addRenderableWidget(new ImageButton(
                    this.leftPos + 104, this.height / 2 - 22, 20, 18, 0, 0, 19, RECIPE_BUTTON_LOCATION,
                    (button) -> {
                        this.recipeBookComponent.toggleVisibility();
                        this.buttonClicked = true;
                        updateLayout();
                    }));
            this.npcButton = this.addRenderableWidget(new ImageButton(
                    this.leftPos + 126, this.height / 2 - 22, 20, 18, 0, 0, 19, NPC_BUTTON_LOCATION,
                    (button) -> {
                        this.npcPanelOpen = !this.npcPanelOpen;
                        this.buttonClicked = true;
                    }));

            this.addWidget(this.recipeBookComponent);
            this.setInitialFocus(this.recipeBookComponent);
            updateButtonPositions();
        }
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        guiGraphics.drawString(this.font, this.title, this.titleLabelX, this.titleLabelY, 4210752, false);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics);
        if (this.recipeBookComponent.isVisible() && this.widthTooNarrow) {
            this.renderBg(guiGraphics, partialTick, mouseX, mouseY);
            this.recipeBookComponent.render(guiGraphics, mouseX, mouseY, partialTick);
        } else {
            this.recipeBookComponent.render(guiGraphics, mouseX, mouseY, partialTick);
            super.render(guiGraphics, mouseX, mouseY, partialTick);
            this.recipeBookComponent.renderGhostRecipe(guiGraphics, this.leftPos, this.topPos, false, partialTick);
        }
        this.renderTooltip(guiGraphics, mouseX, mouseY);
        this.recipeBookComponent.renderTooltip(guiGraphics, this.leftPos, this.topPos, mouseX, mouseY);
        this.xMouse = (float) mouseX;
        this.yMouse = (float) mouseY;
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        int panelX = this.leftPos + this.npcPanelOffsetX;
        int panelY = this.topPos;
        if (this.npcPanelProgress > 0.01F) {
            guiGraphics.blit(INVENTORY_LOCATION, panelX, panelY, 0, 0,
                    CompanionEquipmentMenu.NPC_PANEL_WIDTH, CompanionEquipmentMenu.NPC_PANEL_HEIGHT);
        }
        guiGraphics.blit(INVENTORY_LOCATION, this.leftPos, this.topPos, 0, 0, this.imageWidth, this.imageHeight);
        renderPlayerModel(guiGraphics);
        if (this.npcPanelProgress > 0.01F) {
            renderNpcModel(guiGraphics, panelX, panelY);
            renderNpcToolIcons(guiGraphics);
        }
    }

    private void renderPlayerModel(GuiGraphics guiGraphics) {
        int i = this.leftPos;
        int j = this.topPos;
        InventoryScreen.renderEntityInInventoryFollowsMouse(guiGraphics, i + 51, j + 75, 30,
                (float) (i + 51) - this.xMouse, (float) (j + 75 - 50) - this.yMouse, this.minecraft.player);
    }

    private void renderNpcModel(GuiGraphics guiGraphics, int panelX, int panelY) {
        CompanionEntity companion = this.menu.getCompanion();
        if (companion == null) {
            return;
        }
        InventoryScreen.renderEntityInInventoryFollowsMouse(guiGraphics, panelX + 52, panelY + 75, 30,
                (float) (panelX + 52) - this.xMouse, (float) (panelY + 75 - 50) - this.yMouse, companion);
    }

    private void renderNpcToolIcons(GuiGraphics guiGraphics) {
        renderToolIcon(guiGraphics, 0, PICKAXE_ICON);
        renderToolIcon(guiGraphics, 1, AXE_ICON);
        renderToolIcon(guiGraphics, 2, SHOVEL_ICON);
        renderToolIcon(guiGraphics, 3, SWORD_ICON);
    }

    private void renderToolIcon(GuiGraphics guiGraphics, int index, ResourceLocation icon) {
        Slot slot = this.menu.getNpcToolSlot(index);
        if (slot == null || slot.hasItem()) {
            return;
        }
        int x = this.leftPos + slot.x + 1;
        int y = this.topPos + slot.y + 1;
        guiGraphics.blit(icon, x, y, 0, 0, 16, 16, 16, 16);
    }

    @Override
    protected boolean isHovering(int x, int y, int width, int height, double mouseX, double mouseY) {
        return (!this.widthTooNarrow || !this.recipeBookComponent.isVisible())
                && super.isHovering(x, y, width, height, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.recipeBookComponent.mouseClicked(mouseX, mouseY, button)) {
            this.setFocused(this.recipeBookComponent);
            return true;
        }
        return this.widthTooNarrow && this.recipeBookComponent.isVisible()
                ? false : super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (this.buttonClicked) {
            this.buttonClicked = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    protected boolean hasClickedOutside(double mouseX, double mouseY, int leftPos, int topPos, int button) {
        boolean outsideMain = mouseX < (double) leftPos || mouseY < (double) topPos
                || mouseX >= (double) (leftPos + this.imageWidth)
                || mouseY >= (double) (topPos + this.imageHeight);
        int panelX = this.leftPos + this.npcPanelOffsetX;
        boolean outsidePanel = true;
        if (this.menu.isNpcPanelVisible()) {
            outsidePanel = mouseX < (double) panelX || mouseY < (double) topPos
                    || mouseX >= (double) (panelX + CompanionEquipmentMenu.NPC_PANEL_WIDTH)
                    || mouseY >= (double) (topPos + CompanionEquipmentMenu.NPC_PANEL_HEIGHT);
        }
        boolean flag = outsideMain && outsidePanel;
        return this.recipeBookComponent.hasClickedOutside(mouseX, mouseY, this.leftPos, this.topPos, this.imageWidth,
                this.imageHeight, button) && flag;
    }

    @Override
    protected void slotClicked(Slot slot, int slotId, int mouseButton, ClickType clickType) {
        super.slotClicked(slot, slotId, mouseButton, clickType);
        this.recipeBookComponent.slotClicked(slot);
    }

    @Override
    public void recipesUpdated() {
        this.recipeBookComponent.recipesUpdated();
    }

    @Override
    public RecipeBookComponent getRecipeBookComponent() {
        return this.recipeBookComponent;
    }

    private void updateLayout() {
        int baseLeft = this.recipeBookComponent.updateScreenPosition(this.width, this.imageWidth);
        this.leftPos = baseLeft + Math.round(CompanionEquipmentMenu.NPC_PANEL_WIDTH * this.npcPanelProgress);
        this.menu.setNpcPanelVisible(this.npcPanelProgress > 0.01F);
        updateButtonPositions();
    }

    private void updateButtonPositions() {
        if (this.recipeButton != null) {
            this.recipeButton.setPosition(this.leftPos + 104, this.height / 2 - 22);
        }
        if (this.npcButton != null) {
            this.npcButton.setPosition(this.leftPos + 126, this.height / 2 - 22);
        }
    }
}
