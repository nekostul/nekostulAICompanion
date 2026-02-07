package ru.nekostul.aicompanion.entity.resource;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import ru.nekostul.aicompanion.entity.resource.CompanionResourceType;

import java.util.EnumMap;
import java.util.List;
import java.util.function.Predicate;

public final class CompanionBlockRegistry {
    private static final TagKey<Block> ORES_TAG =
            TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("minecraft", "ores"));
    private static final TagKey<Item> ORES_ITEM_TAG =
            TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("minecraft", "ores"));
    private static final TagKey<Item> FORGE_ORES_ITEM_TAG =
            TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("forge", "ores"));
    private static final TagKey<Item> FORGE_RAW_MATERIALS_TAG =
            TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("forge", "raw_materials"));

    private static final List<ResourceEntry> ENTRIES = List.of(
            new ResourceEntry(
                    CompanionResourceType.WATER,
                    true,
                    state -> state.getFluidState().isSource() && state.getFluidState().is(Fluids.WATER),
                    stack -> stack.is(Items.WATER_BUCKET),
                    new String[]{"вод", "water"}
            ),
            new ResourceEntry(
                    CompanionResourceType.LAVA,
                    true,
                    state -> state.getFluidState().isSource() && state.getFluidState().is(Fluids.LAVA),
                    stack -> stack.is(Items.LAVA_BUCKET),
                    new String[]{"лав", "lava"}
            ),
            new ResourceEntry(
                    CompanionResourceType.LOG,
                    false,
                    state -> state.is(BlockTags.LOGS),
                    stack -> stack.is(ItemTags.LOGS),
                    new String[]{"дерев", "брев", "log", "wood"}
            ),
            new ResourceEntry(
                    CompanionResourceType.COAL_ORE,
                    false,
                    state -> state.is(Blocks.COAL_ORE) || state.is(Blocks.DEEPSLATE_COAL_ORE),
                    stack -> stack.is(Items.COAL),
                    new String[]{"уголь", "coal"}
            ),
            new ResourceEntry(
                    CompanionResourceType.IRON_ORE,
                    false,
                    state -> state.is(Blocks.IRON_ORE) || state.is(Blocks.DEEPSLATE_IRON_ORE),
                    stack -> stack.is(Items.RAW_IRON),
                    new String[]{"желез", "iron"}
            ),
            new ResourceEntry(
                    CompanionResourceType.COPPER_ORE,
                    false,
                    state -> state.is(Blocks.COPPER_ORE) || state.is(Blocks.DEEPSLATE_COPPER_ORE),
                    stack -> stack.is(Items.RAW_COPPER),
                    new String[]{"медь", "медн", "copper"}
            ),
            new ResourceEntry(
                    CompanionResourceType.GOLD_ORE,
                    false,
                    state -> state.is(Blocks.GOLD_ORE) || state.is(Blocks.DEEPSLATE_GOLD_ORE),
                    stack -> stack.is(Items.RAW_GOLD),
                    new String[]{"золот", "gold"}
            ),
            new ResourceEntry(
                    CompanionResourceType.REDSTONE_ORE,
                    false,
                    state -> state.is(Blocks.REDSTONE_ORE) || state.is(Blocks.DEEPSLATE_REDSTONE_ORE),
                    stack -> stack.is(Items.REDSTONE),
                    new String[]{"редстоун", "redstone"}
            ),
            new ResourceEntry(
                    CompanionResourceType.LAPIS_ORE,
                    false,
                    state -> state.is(Blocks.LAPIS_ORE) || state.is(Blocks.DEEPSLATE_LAPIS_ORE),
                    stack -> stack.is(Items.LAPIS_LAZULI),
                    new String[]{"лазурит", "lapis"}
            ),
            new ResourceEntry(
                    CompanionResourceType.DIAMOND_ORE,
                    false,
                    state -> state.is(Blocks.DIAMOND_ORE) || state.is(Blocks.DEEPSLATE_DIAMOND_ORE),
                    stack -> stack.is(Items.DIAMOND),
                    new String[]{"алмаз", "diamond"}
            ),
            new ResourceEntry(
                    CompanionResourceType.EMERALD_ORE,
                    false,
                    state -> state.is(Blocks.EMERALD_ORE) || state.is(Blocks.DEEPSLATE_EMERALD_ORE),
                    stack -> stack.is(Items.EMERALD),
                    new String[]{"изумруд", "emerald"}
            ),
            new ResourceEntry(
                    CompanionResourceType.ORE,
                    false,
                    state -> state.is(ORES_TAG),
                    stack -> stack.is(ORES_ITEM_TAG) || stack.is(FORGE_ORES_ITEM_TAG)
                            || stack.is(FORGE_RAW_MATERIALS_TAG)
                            || stack.is(Items.COAL) || stack.is(Items.RAW_IRON)
                            || stack.is(Items.RAW_COPPER) || stack.is(Items.RAW_GOLD)
                            || stack.is(Items.REDSTONE) || stack.is(Items.LAPIS_LAZULI)
                            || stack.is(Items.DIAMOND) || stack.is(Items.EMERALD),
                    new String[]{"руда", "ore"}
            ),
            new ResourceEntry(
                    CompanionResourceType.TORCH,
                    false,
                    state -> state.is(Blocks.TORCH) || state.is(Blocks.WALL_TORCH),
                    stack -> stack.is(Items.TORCH),
                    new String[]{"факел", "torch"}
            ),
            new ResourceEntry(
                    CompanionResourceType.DIRT,
                    false,
                    state -> state.is(Blocks.DIRT) || state.is(Blocks.GRASS_BLOCK)
                            || state.is(Blocks.COARSE_DIRT) || state.is(Blocks.ROOTED_DIRT)
                            || state.is(Blocks.PODZOL) || state.is(Blocks.MYCELIUM)
                            || state.is(Blocks.MUD),
                    stack -> stack.is(Items.DIRT) || stack.is(Items.GRASS_BLOCK)
                            || stack.is(Items.COARSE_DIRT) || stack.is(Items.ROOTED_DIRT)
                            || stack.is(Items.PODZOL) || stack.is(Items.MYCELIUM)
                            || stack.is(Items.MUD),
                    new String[]{"земл", "гряз", "почв", "грунт", "dirt"}
            ),
            new ResourceEntry(
                    CompanionResourceType.CLAY,
                    false,
                    state -> state.is(Blocks.CLAY),
                    stack -> stack.is(Items.CLAY_BALL) || stack.is(Items.CLAY),
                    new String[]{"глин", "clay"}
            ),
            new ResourceEntry(
                    CompanionResourceType.SAND,
                    false,
                    state -> state.is(Blocks.SAND) || state.is(Blocks.RED_SAND),
                    stack -> stack.is(Items.SAND) || stack.is(Items.RED_SAND),
                    new String[]{"песок", "песоч", "sand"}
            ),
            new ResourceEntry(
                    CompanionResourceType.GRAVEL,
                    false,
                    state -> state.is(Blocks.GRAVEL),
                    stack -> stack.is(Items.GRAVEL),
                    new String[]{"грав", "щеб", "gravel"}
            ),
            new ResourceEntry(
                    CompanionResourceType.STONE,
                    false,
                    state -> state.is(Blocks.STONE),
                    stack -> stack.is(Items.COBBLESTONE) || stack.is(Items.STONE),
                    new String[]{"камн", "камен", "булыж", "stone", "cobble"}
            ),
            new ResourceEntry(
                    CompanionResourceType.ANDESITE,
                    false,
                    state -> state.is(Blocks.ANDESITE) || state.is(Blocks.POLISHED_ANDESITE),
                    stack -> stack.is(Items.ANDESITE) || stack.is(Items.POLISHED_ANDESITE),
                    new String[]{"андез", "andesite"}
            ),
            new ResourceEntry(
                    CompanionResourceType.DIORITE,
                    false,
                    state -> state.is(Blocks.DIORITE) || state.is(Blocks.POLISHED_DIORITE),
                    stack -> stack.is(Items.DIORITE) || stack.is(Items.POLISHED_DIORITE),
                    new String[]{"диор", "diorite"}
            ),
            new ResourceEntry(
                    CompanionResourceType.GRANITE,
                    false,
                    state -> state.is(Blocks.GRANITE) || state.is(Blocks.POLISHED_GRANITE),
                    stack -> stack.is(Items.GRANITE) || stack.is(Items.POLISHED_GRANITE),
                    new String[]{"гранит", "granite"}
            ),
            new ResourceEntry(
                    CompanionResourceType.BASALT,
                    false,
                    state -> state.is(Blocks.BASALT) || state.is(Blocks.POLISHED_BASALT) || state.is(Blocks.SMOOTH_BASALT),
                    stack -> stack.is(Items.BASALT) || stack.is(Items.POLISHED_BASALT) || stack.is(Items.SMOOTH_BASALT),
                    new String[]{"базальт", "basalt"}
            )
    );

    private static final EnumMap<CompanionResourceType, ResourceEntry> BY_TYPE =
            new EnumMap<>(CompanionResourceType.class);

    static {
        for (ResourceEntry entry : ENTRIES) {
            BY_TYPE.put(entry.type, entry);
        }
    }

    private CompanionBlockRegistry() {
    }

    public static CompanionResourceType findTypeByMessage(String normalized) {
        if (normalized == null || normalized.isEmpty()) {
            return null;
        }
        for (ResourceEntry entry : ENTRIES) {
            if (entry.matchesKeyword(normalized)) {
                return entry.type;
            }
        }
        return null;
    }

    public static boolean matchesBlock(CompanionResourceType type, BlockState state) {
        ResourceEntry entry = BY_TYPE.get(type);
        return entry != null && entry.blockPredicate.test(state);
    }

    public static boolean matchesItem(CompanionResourceType type, ItemStack stack) {
        ResourceEntry entry = BY_TYPE.get(type);
        return entry != null && entry.itemPredicate.test(stack);
    }

    public static boolean isBucketResource(CompanionResourceType type) {
        ResourceEntry entry = BY_TYPE.get(type);
        return entry != null && entry.bucketResource;
    }

    public static boolean isLog(BlockState state) {
        return matchesBlock(CompanionResourceType.LOG, state);
    }

    public static boolean isLeaves(BlockState state) {
        return state.is(BlockTags.LEAVES);
    }

    public static boolean isBaseStone(BlockState state) {
        return state.is(BlockTags.BASE_STONE_OVERWORLD);
    }

    public static boolean isStoneBlock(BlockState state) {
        return state.is(Blocks.STONE);
    }

    public static boolean isShovelMineable(BlockState state) {
        return state.is(BlockTags.MINEABLE_WITH_SHOVEL);
    }

    public static boolean isPickaxeMineable(BlockState state) {
        return state.is(BlockTags.MINEABLE_WITH_PICKAXE);
    }

    public static boolean isShovelResource(BlockState state) {
        return matchesBlock(CompanionResourceType.DIRT, state)
                || matchesBlock(CompanionResourceType.SAND, state)
                || matchesBlock(CompanionResourceType.GRAVEL, state)
                || matchesBlock(CompanionResourceType.CLAY, state);
    }

    public static boolean isPickaxeResource(BlockState state) {
        return matchesBlock(CompanionResourceType.STONE, state)
                || matchesBlock(CompanionResourceType.ANDESITE, state)
                || matchesBlock(CompanionResourceType.DIORITE, state)
                || matchesBlock(CompanionResourceType.GRANITE, state)
                || matchesBlock(CompanionResourceType.BASALT, state)
                || matchesBlock(CompanionResourceType.ORE, state)
                || matchesBlock(CompanionResourceType.COAL_ORE, state)
                || matchesBlock(CompanionResourceType.IRON_ORE, state)
                || matchesBlock(CompanionResourceType.COPPER_ORE, state)
                || matchesBlock(CompanionResourceType.GOLD_ORE, state)
                || matchesBlock(CompanionResourceType.REDSTONE_ORE, state)
                || matchesBlock(CompanionResourceType.LAPIS_ORE, state)
                || matchesBlock(CompanionResourceType.DIAMOND_ORE, state)
                || matchesBlock(CompanionResourceType.EMERALD_ORE, state);
    }

    private static final class ResourceEntry {
        private final CompanionResourceType type;
        private final boolean bucketResource;
        private final Predicate<BlockState> blockPredicate;
        private final Predicate<ItemStack> itemPredicate;
        private final String[] keywords;

        private ResourceEntry(CompanionResourceType type,
                              boolean bucketResource,
                              Predicate<BlockState> blockPredicate,
                              Predicate<ItemStack> itemPredicate,
                              String[] keywords) {
            this.type = type;
            this.bucketResource = bucketResource;
            this.blockPredicate = blockPredicate;
            this.itemPredicate = itemPredicate;
            this.keywords = keywords;
        }

        private boolean matchesKeyword(String normalized) {
            for (String keyword : keywords) {
                if (normalized.contains(keyword)) {
                    return true;
                }
            }
            return false;
        }
    }
}


