package ru.nekostul.aicompanion.client.gui;

import com.mojang.datafixers.util.Pair;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
<<<<<<< HEAD
import net.minecraft.world.InteractionHand;
=======
>>>>>>> c2d33cbe0c980ab5a9c3c4b21831b9294ece5fe9
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.StackedContents;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.RecipeBookMenu;
import net.minecraft.world.inventory.RecipeBookType;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.Equipable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.Level;

import java.util.Optional;

import ru.nekostul.aicompanion.entity.CompanionEntity;
import ru.nekostul.aicompanion.entity.tool.CompanionToolSlot;
import ru.nekostul.aicompanion.registry.ModMenus;

public final class CompanionEquipmentMenu extends RecipeBookMenu<CraftingContainer> {
    public static final int RESULT_SLOT = 0;
    public static final int CRAFT_SLOT_START = 1;
    public static final int CRAFT_SLOT_END = 5;
    public static final int ARMOR_SLOT_START = 5;
    public static final int ARMOR_SLOT_END = 9;
    public static final int INV_SLOT_START = 9;
    public static final int INV_SLOT_END = 36;
    public static final int USE_ROW_SLOT_START = 36;
    public static final int USE_ROW_SLOT_END = 45;
    public static final int SHIELD_SLOT = 45;

<<<<<<< HEAD
    public static final int NPC_PANEL_WIDTH = 101;
    public static final int NPC_PANEL_HEIGHT = 87;
=======
    public static final int NPC_PANEL_WIDTH = 104;
    public static final int NPC_PANEL_HEIGHT = 166;
>>>>>>> c2d33cbe0c980ab5a9c3c4b21831b9294ece5fe9

    private static final EquipmentSlot[] PLAYER_ARMOR_SLOTS = new EquipmentSlot[]{
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };
    private static final int NPC_ARMOR_COUNT = 4;
    private static final int NPC_TOOL_COUNT = 4;
    private static final int NPC_SLOT_Y = 8;
    private static final int NPC_SLOT_SPACING = 18;
    private static final int NPC_ARMOR_X = 8;
<<<<<<< HEAD
    private static final int NPC_TOOL_X = 77;
=======
    private static final int NPC_TOOL_X = 78;
>>>>>>> c2d33cbe0c980ab5a9c3c4b21831b9294ece5fe9
    private static final ResourceLocation[] PLAYER_ARMOR_EMPTY = new ResourceLocation[]{
            InventoryMenu.EMPTY_ARMOR_SLOT_BOOTS,
            InventoryMenu.EMPTY_ARMOR_SLOT_LEGGINGS,
            InventoryMenu.EMPTY_ARMOR_SLOT_CHESTPLATE,
            InventoryMenu.EMPTY_ARMOR_SLOT_HELMET
    };
<<<<<<< HEAD
=======
    private static final ResourceLocation[] NPC_ARMOR_EMPTY = new ResourceLocation[]{
            InventoryMenu.EMPTY_ARMOR_SLOT_HELMET,
            InventoryMenu.EMPTY_ARMOR_SLOT_CHESTPLATE,
            InventoryMenu.EMPTY_ARMOR_SLOT_LEGGINGS,
            InventoryMenu.EMPTY_ARMOR_SLOT_BOOTS
    };
>>>>>>> c2d33cbe0c980ab5a9c3c4b21831b9294ece5fe9

    private final CraftingContainer craftSlots = new TransientCraftingContainer(this, 2, 2);
    private final ResultContainer resultSlots = new ResultContainer();
    public final boolean active;
    private final Player owner;
    private final CompanionEntity companion;
    private final Container npcContainer;
    private final Slot[] npcArmorSlots = new Slot[NPC_ARMOR_COUNT];
    private final Slot[] npcToolSlots = new Slot[NPC_TOOL_COUNT];
    private final boolean npcPanelOpenByDefault;
    private boolean npcPanelVisible;
    private int npcArmorStart;
    private int npcToolStart;
    private int npcSlotEnd;

    public CompanionEquipmentMenu(int containerId, Inventory playerInventory, CompanionEntity companion,
                                  boolean npcPanelOpenByDefault) {
        this(containerId, playerInventory, !playerInventory.player.level().isClientSide, playerInventory.player,
                companion, npcPanelOpenByDefault);
    }

    public CompanionEquipmentMenu(int containerId, Inventory playerInventory, FriendlyByteBuf buffer) {
        this(containerId, playerInventory, readCompanion(playerInventory, buffer), readNpcPanelOpen(buffer));
    }

    private CompanionEquipmentMenu(int containerId, Inventory playerInventory, boolean active, Player owner,
                                   CompanionEntity companion, boolean npcPanelOpenByDefault) {
        super(ModMenus.COMPANION_EQUIPMENT.get(), containerId);
        this.active = active;
        this.owner = owner;
        this.companion = companion;
        this.npcPanelOpenByDefault = npcPanelOpenByDefault;
        this.npcPanelVisible = npcPanelOpenByDefault;
        this.npcContainer = new CompanionEquipmentContainer(companion);

        this.addSlot(new ResultSlot(playerInventory.player, this.craftSlots, this.resultSlots, 0, 154, 28));

        for (int i = 0; i < 2; ++i) {
            for (int j = 0; j < 2; ++j) {
                this.addSlot(new Slot(this.craftSlots, j + i * 2, 98 + j * 18, 18 + i * 18));
            }
        }

        for (int k = 0; k < 4; ++k) {
            final EquipmentSlot equipmentSlot = PLAYER_ARMOR_SLOTS[k];
            this.addSlot(new Slot(playerInventory, 39 - k, 8, 8 + k * 18) {
                @Override
                public void setByPlayer(ItemStack stack) {
                    CompanionEquipmentMenu.onEquipItem(owner, equipmentSlot, stack, this.getItem());
                    super.setByPlayer(stack);
                }

                @Override
                public int getMaxStackSize() {
                    return 1;
                }

                @Override
                public boolean mayPlace(ItemStack stack) {
                    return stack.canEquip(equipmentSlot, owner);
                }

                @Override
                public boolean mayPickup(Player player) {
                    ItemStack itemStack = this.getItem();
                    return !itemStack.isEmpty() && !player.isCreative()
                            && EnchantmentHelper.hasBindingCurse(itemStack)
                            ? false : super.mayPickup(player);
                }

                @Override
                public Pair<ResourceLocation, ResourceLocation> getNoItemIcon() {
                    return Pair.of(InventoryMenu.BLOCK_ATLAS, PLAYER_ARMOR_EMPTY[equipmentSlot.getIndex()]);
                }
            });
        }

        for (int l = 0; l < 3; ++l) {
            for (int j1 = 0; j1 < 9; ++j1) {
                this.addSlot(new Slot(playerInventory, j1 + (l + 1) * 9, 8 + j1 * 18, 84 + l * 18));
            }
        }

        for (int i1 = 0; i1 < 9; ++i1) {
            this.addSlot(new Slot(playerInventory, i1, 8 + i1 * 18, 142));
        }

        this.addSlot(new Slot(playerInventory, 40, 77, 62) {
            @Override
            public void setByPlayer(ItemStack stack) {
                CompanionEquipmentMenu.onEquipItem(owner, EquipmentSlot.OFFHAND, stack, this.getItem());
                super.setByPlayer(stack);
            }

            @Override
            public Pair<ResourceLocation, ResourceLocation> getNoItemIcon() {
                return Pair.of(InventoryMenu.BLOCK_ATLAS, InventoryMenu.EMPTY_ARMOR_SLOT_SHIELD);
            }
        });

        this.npcArmorStart = this.slots.size();
        addNpcArmorSlots();
        this.npcToolStart = this.slots.size();
        addNpcToolSlots();
        this.npcSlotEnd = this.slots.size();
    }

    static void onEquipItem(Player player, EquipmentSlot slot, ItemStack stack, ItemStack previous) {
        Equipable equipable = Equipable.get(stack);
        if (equipable != null) {
            player.onEquipItem(slot, previous, stack);
        }
    }

    public CompanionEntity getCompanion() {
        return companion;
    }

    public boolean isNpcPanelOpenByDefault() {
        return npcPanelOpenByDefault;
    }

    public void setNpcPanelVisible(boolean visible) {
        this.npcPanelVisible = visible;
    }

    public boolean isNpcPanelVisible() {
        return npcPanelVisible;
    }

    public Slot getNpcToolSlot(int index) {
        if (index < 0 || index >= npcToolSlots.length) {
            return null;
        }
        return npcToolSlots[index];
    }

<<<<<<< HEAD
    public boolean isNpcSlot(Slot slot) {
        if (slot == null) {
            return false;
        }
        for (Slot armorSlot : npcArmorSlots) {
            if (armorSlot == slot) {
                return true;
            }
        }
        for (Slot toolSlot : npcToolSlots) {
            if (toolSlot == slot) {
                return true;
            }
        }
        return false;
    }

=======
>>>>>>> c2d33cbe0c980ab5a9c3c4b21831b9294ece5fe9
    @Override
    public void fillCraftSlotsStackedContents(StackedContents contents) {
        this.craftSlots.fillStackedContents(contents);
    }

    @Override
    public void clearCraftingContent() {
        this.resultSlots.clearContent();
        this.craftSlots.clearContent();
    }

    @Override
    public boolean recipeMatches(Recipe<? super CraftingContainer> recipe) {
        return recipe.matches(this.craftSlots, this.owner.level());
    }

    @Override
    public void slotsChanged(Container container) {
        slotChangedCraftingGrid(this, this.owner.level(), this.owner, this.craftSlots, this.resultSlots);
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        this.resultSlots.clearContent();
        if (!player.level().isClientSide) {
            this.clearContainer(player, this.craftSlots);
        }
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack itemStack = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot.hasItem()) {
            ItemStack slotStack = slot.getItem();
            itemStack = slotStack.copy();
            EquipmentSlot equipmentSlot = Mob.getEquipmentSlotForItem(itemStack);
            if (index == RESULT_SLOT) {
                if (!this.moveItemStackTo(slotStack, INV_SLOT_START, USE_ROW_SLOT_END, true)) {
                    return ItemStack.EMPTY;
                }
                slot.onQuickCraft(slotStack, itemStack);
            } else if (index >= CRAFT_SLOT_START && index < CRAFT_SLOT_END) {
                if (!this.moveItemStackTo(slotStack, INV_SLOT_START, USE_ROW_SLOT_END, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (index >= ARMOR_SLOT_START && index < ARMOR_SLOT_END) {
                if (!this.moveItemStackTo(slotStack, INV_SLOT_START, USE_ROW_SLOT_END, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (index >= npcArmorStart && index < npcSlotEnd) {
                if (!this.moveItemStackTo(slotStack, INV_SLOT_START, USE_ROW_SLOT_END, true)) {
                    return ItemStack.EMPTY;
                }
            } else if (equipmentSlot.getType() == EquipmentSlot.Type.ARMOR
                    && !this.slots.get(8 - equipmentSlot.getIndex()).hasItem()) {
                int i = 8 - equipmentSlot.getIndex();
                if (!this.moveItemStackTo(slotStack, i, i + 1, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (equipmentSlot == EquipmentSlot.OFFHAND && !this.slots.get(SHIELD_SLOT).hasItem()) {
                if (!this.moveItemStackTo(slotStack, SHIELD_SLOT, SHIELD_SLOT + 1, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (index >= INV_SLOT_START && index < INV_SLOT_END) {
                if (!tryMoveToNpcSlots(slotStack)
                        && !this.moveItemStackTo(slotStack, USE_ROW_SLOT_START, USE_ROW_SLOT_END, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (index >= USE_ROW_SLOT_START && index < USE_ROW_SLOT_END) {
                if (!tryMoveToNpcSlots(slotStack)
                        && !this.moveItemStackTo(slotStack, INV_SLOT_START, INV_SLOT_END, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (!this.moveItemStackTo(slotStack, INV_SLOT_START, USE_ROW_SLOT_END, false)) {
                return ItemStack.EMPTY;
            }

            if (slotStack.isEmpty()) {
                slot.setByPlayer(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }

            if (slotStack.getCount() == itemStack.getCount()) {
                return ItemStack.EMPTY;
            }

            slot.onTake(player, slotStack);
            if (index == RESULT_SLOT) {
                player.drop(slotStack, false);
            }
        }

        return itemStack;
    }

    @Override
    public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
        return slot.container != this.resultSlots && super.canTakeItemForPickAll(stack, slot);
    }

    @Override
    public int getResultSlotIndex() {
        return RESULT_SLOT;
    }

    @Override
    public int getGridWidth() {
        return this.craftSlots.getWidth();
    }

    @Override
    public int getGridHeight() {
        return this.craftSlots.getHeight();
    }

    @Override
    public int getSize() {
        return CRAFT_SLOT_END;
    }

    public CraftingContainer getCraftSlots() {
        return this.craftSlots;
    }

    @Override
    public RecipeBookType getRecipeBookType() {
        return RecipeBookType.CRAFTING;
    }

    @Override
    public boolean shouldMoveToInventory(int slotIndex) {
        return slotIndex != this.getResultSlotIndex();
    }

    private void addNpcArmorSlots() {
        int armorX = -NPC_PANEL_WIDTH + NPC_ARMOR_X;
        for (int i = 0; i < npcArmorSlots.length; i++) {
            EquipmentSlot slot = PLAYER_ARMOR_SLOTS[i];
            CompanionArmorSlot armorSlot = new CompanionArmorSlot(npcContainer, i,
                    armorX, NPC_SLOT_Y + i * NPC_SLOT_SPACING, slot, companion);
<<<<<<< HEAD
=======
            armorSlot.setBackground(InventoryMenu.BLOCK_ATLAS, NPC_ARMOR_EMPTY[i]);
>>>>>>> c2d33cbe0c980ab5a9c3c4b21831b9294ece5fe9
            npcArmorSlots[i] = this.addSlot(armorSlot);
        }
    }

    private void addNpcToolSlots() {
        int toolX = -NPC_PANEL_WIDTH + NPC_TOOL_X;
        npcToolSlots[0] = this.addSlot(new CompanionToolSlotSlot(npcContainer, NPC_ARMOR_COUNT + 0,
                toolX, NPC_SLOT_Y, CompanionToolSlot.PICKAXE));
<<<<<<< HEAD
        npcToolSlots[1] = this.addSlot(new CompanionToolSlotSlot(npcContainer, NPC_ARMOR_COUNT + 2,
                toolX, NPC_SLOT_Y + NPC_SLOT_SPACING, CompanionToolSlot.SHOVEL));
        npcToolSlots[2] = this.addSlot(new CompanionToolSlotSlot(npcContainer, NPC_ARMOR_COUNT + 1,
                toolX, NPC_SLOT_Y + NPC_SLOT_SPACING * 2, CompanionToolSlot.AXE));
=======
        npcToolSlots[1] = this.addSlot(new CompanionToolSlotSlot(npcContainer, NPC_ARMOR_COUNT + 1,
                toolX, NPC_SLOT_Y + NPC_SLOT_SPACING, CompanionToolSlot.AXE));
        npcToolSlots[2] = this.addSlot(new CompanionToolSlotSlot(npcContainer, NPC_ARMOR_COUNT + 2,
                toolX, NPC_SLOT_Y + NPC_SLOT_SPACING * 2, CompanionToolSlot.SHOVEL));
>>>>>>> c2d33cbe0c980ab5a9c3c4b21831b9294ece5fe9
        npcToolSlots[3] = this.addSlot(new CompanionToolSlotSlot(npcContainer, NPC_ARMOR_COUNT + 3,
                toolX, NPC_SLOT_Y + NPC_SLOT_SPACING * 3, CompanionToolSlot.SWORD));
    }

    private boolean tryMoveToNpcSlots(ItemStack stack) {
        if (companion == null || stack.isEmpty()) {
            return false;
        }
        EquipmentSlot armorSlot = getNpcArmorSlot(stack);
        if (armorSlot != null) {
            int target = npcArmorStart + armorSlotIndex(armorSlot);
            if (target >= npcArmorStart && target < npcToolStart && !this.slots.get(target).hasItem()) {
                return this.moveItemStackTo(stack, target, target + 1, false);
            }
            return false;
        }
        CompanionToolSlot toolSlot = CompanionToolSlot.fromStack(stack);
        if (toolSlot != null) {
            int target = npcToolStart + toolSlotIndex(toolSlot);
            if (target >= npcToolStart && target < npcSlotEnd && !this.slots.get(target).hasItem()) {
                return this.moveItemStackTo(stack, target, target + 1, false);
            }
        }
        return false;
    }

    private static EquipmentSlot getNpcArmorSlot(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        if (stack.getItem() instanceof Equipable equipable) {
            EquipmentSlot slot = equipable.getEquipmentSlot();
            if (slot.getType() == EquipmentSlot.Type.ARMOR) {
                return slot;
            }
        }
        return null;
    }

    private static int armorSlotIndex(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> 0;
            case CHEST -> 1;
            case LEGS -> 2;
            case FEET -> 3;
            default -> -1;
        };
    }

    private static int toolSlotIndex(CompanionToolSlot slot) {
        return switch (slot) {
            case PICKAXE -> 0;
<<<<<<< HEAD
            case SHOVEL -> 1;
            case AXE -> 2;
=======
            case AXE -> 1;
            case SHOVEL -> 2;
>>>>>>> c2d33cbe0c980ab5a9c3c4b21831b9294ece5fe9
            case SWORD -> 3;
        };
    }

    private static CompanionEntity readCompanion(Inventory playerInventory, FriendlyByteBuf buffer) {
        if (buffer == null || playerInventory.player.level() == null) {
            return null;
        }
        int entityId = buffer.readInt();
        Entity entity = playerInventory.player.level().getEntity(entityId);
        if (entity instanceof CompanionEntity companion) {
            return companion;
        }
        return null;
    }

    private static boolean readNpcPanelOpen(FriendlyByteBuf buffer) {
        if (buffer == null || buffer.readableBytes() < 1) {
            return false;
        }
        return buffer.readBoolean();
    }

    private static void slotChangedCraftingGrid(CompanionEquipmentMenu menu, Level level, Player player,
                                                CraftingContainer craftSlots, ResultContainer resultSlots) {
        if (level.isClientSide) {
            return;
        }
        ServerPlayer serverPlayer = (ServerPlayer) player;
        ItemStack result = ItemStack.EMPTY;
        Optional<CraftingRecipe> optional = level.getServer().getRecipeManager()
                .getRecipeFor(RecipeType.CRAFTING, craftSlots, level);
        if (optional.isPresent()) {
            CraftingRecipe recipe = optional.get();
            if (resultSlots.setRecipeUsed(level, serverPlayer, recipe)) {
                ItemStack crafted = recipe.assemble(craftSlots, level.registryAccess());
                if (crafted.isItemEnabled(level.enabledFeatures())) {
                    result = crafted;
                }
            }
        }
        resultSlots.setItem(0, result);
        menu.setRemoteSlot(RESULT_SLOT, result);
        serverPlayer.connection.send(new ClientboundContainerSetSlotPacket(menu.containerId,
                menu.incrementStateId(), RESULT_SLOT, result));
    }

    private static final class CompanionEquipmentContainer implements Container {
        private final CompanionEntity companion;

        private CompanionEquipmentContainer(CompanionEntity companion) {
            this.companion = companion;
        }

        @Override
        public int getContainerSize() {
            return NPC_ARMOR_COUNT + NPC_TOOL_COUNT;
        }

        @Override
        public boolean isEmpty() {
            for (int i = 0; i < getContainerSize(); i++) {
                if (!getItem(i).isEmpty()) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public ItemStack getItem(int index) {
            if (companion == null) {
                return ItemStack.EMPTY;
            }
<<<<<<< HEAD
            CompanionToolSlot toolSlot = getToolSlotByIndex(index);
            if (toolSlot != null) {
                return getToolSlotItem(toolSlot);
            }
=======
>>>>>>> c2d33cbe0c980ab5a9c3c4b21831b9294ece5fe9
            return switch (index) {
                case 0 -> companion.getItemBySlot(EquipmentSlot.HEAD);
                case 1 -> companion.getItemBySlot(EquipmentSlot.CHEST);
                case 2 -> companion.getItemBySlot(EquipmentSlot.LEGS);
                case 3 -> companion.getItemBySlot(EquipmentSlot.FEET);
<<<<<<< HEAD
=======
                case 4 -> companion.getToolSlot(CompanionToolSlot.PICKAXE);
                case 5 -> companion.getToolSlot(CompanionToolSlot.AXE);
                case 6 -> companion.getToolSlot(CompanionToolSlot.SHOVEL);
                case 7 -> companion.getToolSlot(CompanionToolSlot.SWORD);
>>>>>>> c2d33cbe0c980ab5a9c3c4b21831b9294ece5fe9
                default -> ItemStack.EMPTY;
            };
        }

        @Override
        public ItemStack removeItem(int index, int count) {
<<<<<<< HEAD
            CompanionToolSlot toolSlot = getToolSlotByIndex(index);
            if (toolSlot != null) {
                return removeToolSlotItem(toolSlot, count);
            }
=======
>>>>>>> c2d33cbe0c980ab5a9c3c4b21831b9294ece5fe9
            ItemStack current = getItem(index);
            if (current.isEmpty()) {
                return ItemStack.EMPTY;
            }
            ItemStack result = current.split(count);
            setItem(index, current);
            return result;
        }

        @Override
        public ItemStack removeItemNoUpdate(int index) {
<<<<<<< HEAD
            CompanionToolSlot toolSlot = getToolSlotByIndex(index);
            if (toolSlot != null) {
                return removeToolSlotItemNoUpdate(toolSlot);
            }
=======
>>>>>>> c2d33cbe0c980ab5a9c3c4b21831b9294ece5fe9
            ItemStack current = getItem(index);
            setItem(index, ItemStack.EMPTY);
            return current;
        }

        @Override
        public void setItem(int index, ItemStack stack) {
            if (companion == null) {
                return;
            }
            ItemStack toStore = stack == null ? ItemStack.EMPTY : stack;
<<<<<<< HEAD
            CompanionToolSlot toolSlot = getToolSlotByIndex(index);
            if (toolSlot != null) {
                setToolSlotItem(toolSlot, toStore);
                return;
            }
=======
>>>>>>> c2d33cbe0c980ab5a9c3c4b21831b9294ece5fe9
            switch (index) {
                case 0 -> companion.setItemSlot(EquipmentSlot.HEAD, toStore);
                case 1 -> companion.setItemSlot(EquipmentSlot.CHEST, toStore);
                case 2 -> companion.setItemSlot(EquipmentSlot.LEGS, toStore);
                case 3 -> companion.setItemSlot(EquipmentSlot.FEET, toStore);
<<<<<<< HEAD
=======
                case 4 -> companion.setToolSlot(CompanionToolSlot.PICKAXE, toStore);
                case 5 -> companion.setToolSlot(CompanionToolSlot.AXE, toStore);
                case 6 -> companion.setToolSlot(CompanionToolSlot.SHOVEL, toStore);
                case 7 -> companion.setToolSlot(CompanionToolSlot.SWORD, toStore);
>>>>>>> c2d33cbe0c980ab5a9c3c4b21831b9294ece5fe9
                default -> {
                }
            }
        }

        @Override
        public void setChanged() {
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }

        @Override
        public void clearContent() {
            for (int i = 0; i < getContainerSize(); i++) {
                setItem(i, ItemStack.EMPTY);
            }
        }
<<<<<<< HEAD

        private CompanionToolSlot getToolSlotByIndex(int index) {
            return switch (index) {
                case 4 -> CompanionToolSlot.PICKAXE;
                case 5 -> CompanionToolSlot.AXE;
                case 6 -> CompanionToolSlot.SHOVEL;
                case 7 -> CompanionToolSlot.SWORD;
                default -> null;
            };
        }

        private ItemStack getToolSlotItem(CompanionToolSlot slot) {
            ItemStack stored = companion.getToolSlot(slot);
            if (!stored.isEmpty()) {
                return stored;
            }
            ItemStack mainHand = companion.getMainHandItem();
            CompanionToolSlot mainHandSlot = CompanionToolSlot.fromStack(mainHand);
            return mainHandSlot == slot ? mainHand : ItemStack.EMPTY;
        }

        private ItemStack removeToolSlotItem(CompanionToolSlot slot, int count) {
            ItemStack stored = companion.getToolSlot(slot);
            if (!stored.isEmpty()) {
                ItemStack result = stored.split(count);
                companion.setToolSlot(slot, stored);
                return result;
            }
            ItemStack mainHand = companion.getMainHandItem();
            CompanionToolSlot mainHandSlot = CompanionToolSlot.fromStack(mainHand);
            if (mainHandSlot != slot) {
                return ItemStack.EMPTY;
            }
            ItemStack result = mainHand.split(count);
            companion.setItemInHand(InteractionHand.MAIN_HAND, mainHand.isEmpty() ? ItemStack.EMPTY : mainHand);
            return result;
        }

        private ItemStack removeToolSlotItemNoUpdate(CompanionToolSlot slot) {
            ItemStack stored = companion.getToolSlot(slot);
            if (!stored.isEmpty()) {
                companion.setToolSlot(slot, ItemStack.EMPTY);
                return stored;
            }
            ItemStack mainHand = companion.getMainHandItem();
            CompanionToolSlot mainHandSlot = CompanionToolSlot.fromStack(mainHand);
            if (mainHandSlot != slot) {
                return ItemStack.EMPTY;
            }
            companion.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            return mainHand;
        }

        private void setToolSlotItem(CompanionToolSlot slot, ItemStack stack) {
            if (!stack.isEmpty()) {
                companion.setToolSlot(slot, stack);
                return;
            }
            if (!companion.getToolSlot(slot).isEmpty()) {
                companion.setToolSlot(slot, ItemStack.EMPTY);
                return;
            }
            ItemStack mainHand = companion.getMainHandItem();
            CompanionToolSlot mainHandSlot = CompanionToolSlot.fromStack(mainHand);
            if (mainHandSlot == slot) {
                companion.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            }
        }
=======
>>>>>>> c2d33cbe0c980ab5a9c3c4b21831b9294ece5fe9
    }

    private class CompanionArmorSlot extends Slot {
        private final EquipmentSlot equipmentSlot;
        private final CompanionEntity companion;

        private CompanionArmorSlot(Container container, int index, int x, int y, EquipmentSlot slot,
                                   CompanionEntity companion) {
            super(container, index, x, y);
            this.equipmentSlot = slot;
            this.companion = companion;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            if (companion == null || stack.isEmpty()) {
                return stack.isEmpty();
            }
            return stack.canEquip(equipmentSlot, companion);
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public boolean mayPickup(Player player) {
            ItemStack itemStack = this.getItem();
            return !itemStack.isEmpty() && !player.isCreative()
                    && EnchantmentHelper.hasBindingCurse(itemStack)
                    ? false : super.mayPickup(player);
        }

        @Override
<<<<<<< HEAD
        public Pair<ResourceLocation, ResourceLocation> getNoItemIcon() {
            return Pair.of(InventoryMenu.BLOCK_ATLAS, PLAYER_ARMOR_EMPTY[equipmentSlot.getIndex()]);
        }

        @Override
=======
>>>>>>> c2d33cbe0c980ab5a9c3c4b21831b9294ece5fe9
        public boolean isActive() {
            return CompanionEquipmentMenu.this.npcPanelVisible;
        }
    }

    private class CompanionToolSlotSlot extends Slot {
        private final CompanionToolSlot toolSlot;

        private CompanionToolSlotSlot(Container container, int index, int x, int y, CompanionToolSlot toolSlot) {
            super(container, index, x, y);
            this.toolSlot = toolSlot;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return stack.isEmpty() || toolSlot.matches(stack);
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public boolean isActive() {
            return CompanionEquipmentMenu.this.npcPanelVisible;
        }
    }
}
