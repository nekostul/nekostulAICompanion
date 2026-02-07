package ru.nekostul.aicompanion.entity.tool;

import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.core.BlockPos;

import ru.nekostul.aicompanion.entity.CompanionBlockRegistry;
import ru.nekostul.aicompanion.entity.CompanionEntity;
import ru.nekostul.aicompanion.entity.CompanionEquipment;
import ru.nekostul.aicompanion.entity.CompanionInventory;
import ru.nekostul.aicompanion.entity.resource.CompanionResourceType;

public final class CompanionToolHandler {
    enum ToolType {
        AXE,
        SHOVEL,
        PICKAXE
    }

    private final CompanionEntity owner;
    private final CompanionInventory inventory;
    private final CompanionEquipment equipment;
    private final CompanionToolNotifier notifier;

    public CompanionToolHandler(CompanionEntity owner, CompanionInventory inventory, CompanionEquipment equipment) {
        this.owner = owner;
        this.inventory = inventory;
        this.equipment = equipment;
        this.notifier = new CompanionToolNotifier(owner);
    }

    public boolean prepareTool(BlockState state, BlockPos targetPos, Player player, long gameTime) {
        ToolType required = requiredTool(state);
        if (required == null) {
            return true;
        }
        TagKey<Item> tag = toolTag(required);
        if (tag == null) {
            return true;
        }
        if (owner.getMainHandItem().is(tag)) {
            return true;
        }
        if (hasTool(tag)) {
            equipment.equipBestTool(tag);
            return owner.getMainHandItem().is(tag);
        }
        notifier.notifyMissingTool(player, required, targetPos, gameTime);
        return true;
    }

    public boolean ensurePickaxeForRequest(CompanionResourceType type, Player player, long gameTime) {
        if (!requiresPickaxe(type)) {
            return true;
        }
        return ensurePickaxeAvailable(player, owner.blockPosition(), gameTime);
    }

    public boolean ensurePickaxeForBlock(BlockState state, BlockPos targetPos, Player player, long gameTime) {
        if (requiredTool(state) != ToolType.PICKAXE) {
            return true;
        }
        return ensurePickaxeAvailable(player, targetPos, gameTime);
    }

    private ToolType requiredTool(BlockState state) {
        if (CompanionBlockRegistry.isLog(state)) {
            return ToolType.AXE;
        }
        if (CompanionBlockRegistry.isPickaxeMineable(state)
                || CompanionBlockRegistry.isStoneBlock(state)
                || CompanionBlockRegistry.isPickaxeResource(state)) {
            return ToolType.PICKAXE;
        }
        if (CompanionBlockRegistry.isShovelMineable(state)
                || CompanionBlockRegistry.isShovelResource(state)) {
            return ToolType.SHOVEL;
        }
        return null;
    }

    private boolean requiresPickaxe(CompanionResourceType type) {
        if (type == null) {
            return false;
        }
        return switch (type) {
            case STONE, ANDESITE, DIORITE, GRANITE, BASALT,
                 ORE, COAL_ORE, IRON_ORE, COPPER_ORE, GOLD_ORE,
                 REDSTONE_ORE, LAPIS_ORE, DIAMOND_ORE, EMERALD_ORE -> true;
            default -> false;
        };
    }

    private TagKey<Item> toolTag(ToolType tool) {
        return switch (tool) {
            case AXE -> ItemTags.AXES;
            case SHOVEL -> ItemTags.SHOVELS;
            case PICKAXE -> ItemTags.PICKAXES;
        };
    }

    private boolean ensurePickaxeAvailable(Player player, BlockPos targetPos, long gameTime) {
        TagKey<Item> pickaxeTag = ItemTags.PICKAXES;
        if (owner.getMainHandItem().is(pickaxeTag)) {
            return true;
        }
        if (hasTool(pickaxeTag)) {
            equipment.equipBestTool(pickaxeTag);
            return owner.getMainHandItem().is(pickaxeTag);
        }
        notifier.notifyRequiredTool(player, ToolType.PICKAXE, targetPos, gameTime);
        return false;
    }

    private boolean hasTool(TagKey<Item> tag) {
        if (owner.getMainHandItem().is(tag)) {
            return true;
        }
        if (owner.getOffhandItem().is(tag)) {
            return true;
        }
        return inventory.hasItemTag(tag);
    }
}
