package com.deadmanshand.city;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.StairsBlock;
import net.minecraft.structure.StructureSet;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.registry.Registry;
import net.minecraft.world.HeightLimitView;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.Blender;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.chunk.FlatChunkGenerator;
import net.minecraft.world.gen.chunk.FlatChunkGeneratorConfig;
import net.minecraft.world.gen.noise.NoiseConfig;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Builds the empty city, chunk by chunk, straight from {@link City}'s plan. Nothing else grows there. */
public final class CityGenerator extends FlatChunkGenerator {
    public static final Codec<CityGenerator> CODEC = RecordCodecBuilder.create(instance -> createStructureSetRegistryGetter(instance)
            .and(FlatChunkGeneratorConfig.CODEC.fieldOf("settings").forGetter(FlatChunkGenerator::getConfig))
            .apply(instance, instance.stable(CityGenerator::new)));

    private static BlockState[] states;

    public CityGenerator(Registry<StructureSet> structureSets, FlatChunkGeneratorConfig config) {
        super(structureSets, config);
    }

    @Override
    protected Codec<? extends ChunkGenerator> getCodec() {
        return CODEC;
    }

    @Override
    public CompletableFuture<Chunk> populateNoise(Executor executor, Blender blender, NoiseConfig noiseConfig, StructureAccessor structureAccessor,
                                                  Chunk chunk) {
        BlockState[] palette = palette();
        ChunkPos pos = chunk.getPos();
        BlockPos.Mutable mutable = new BlockPos.Mutable();
        Heightmap ocean = chunk.getHeightmap(Heightmap.Type.OCEAN_FLOOR_WG);
        Heightmap surface = chunk.getHeightmap(Heightmap.Type.WORLD_SURFACE_WG);
        int bottom = chunk.getBottomY();
        int top = chunk.getTopY();
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int cx = lx;
                int cz = lz;
                City.column(pos.getStartX() + lx, pos.getStartZ() + lz, (y, mat) -> {
                    if (y < bottom || y >= top || mat == Mat.AIR) {
                        return;
                    }
                    BlockState state = palette[mat.ordinal()];
                    chunk.setBlockState(mutable.set(cx, y, cz), state, false);
                    ocean.trackUpdate(cx, y, cz, state);
                    surface.trackUpdate(cx, y, cz, state);
                });
            }
        }
        return CompletableFuture.completedFuture(chunk);
    }

    @Override
    public int getHeight(int x, int z, Heightmap.Type heightmap, HeightLimitView world, NoiseConfig noiseConfig) {
        return City.STREET;
    }

    private static BlockState[] palette() {
        if (states == null) {
            BlockState[] map = new BlockState[Mat.values().length];
            for (Mat mat : Mat.values()) {
                map[mat.ordinal()] = block(mat);
            }
            states = map;
        }
        return states;
    }

    private static BlockState block(Mat mat) {
        return switch (mat) {
            case AIR -> Blocks.AIR.getDefaultState();
            case BEDROCK -> Blocks.BEDROCK.getDefaultState();
            case STONE -> Blocks.STONE.getDefaultState();
            case DIRT -> Blocks.DIRT.getDefaultState();
            case GRASS -> Blocks.GRASS_BLOCK.getDefaultState();
            case GRAVEL -> Blocks.GRAVEL.getDefaultState();
            case SAND -> Blocks.SAND.getDefaultState();
            case WATER -> Blocks.WATER.getDefaultState();
            case ASPHALT -> Blocks.GRAY_CONCRETE.getDefaultState();
            case MARK -> Blocks.WHITE_CONCRETE.getDefaultState();
            case MARK_YELLOW -> Blocks.YELLOW_CONCRETE.getDefaultState();
            case SIDEWALK -> Blocks.SMOOTH_STONE.getDefaultState();
            case CURB -> Blocks.POLISHED_ANDESITE.getDefaultState();
            case PLAZA -> Blocks.STONE_BRICKS.getDefaultState();
            case TILE -> Blocks.POLISHED_ANDESITE.getDefaultState();
            case WALL_WHITE -> Blocks.WHITE_CONCRETE.getDefaultState();
            case WALL_LIGHT -> Blocks.LIGHT_GRAY_CONCRETE.getDefaultState();
            case WALL_GRAY -> Blocks.GRAY_CONCRETE.getDefaultState();
            case WALL_DARK -> Blocks.POLISHED_BLACKSTONE.getDefaultState();
            case WALL_QUARTZ -> Blocks.SMOOTH_QUARTZ.getDefaultState();
            case WALL_BRICK -> Blocks.BRICKS.getDefaultState();
            case WALL_SAND -> Blocks.SMOOTH_SANDSTONE.getDefaultState();
            case WALL_TERRA -> Blocks.WHITE_TERRACOTTA.getDefaultState();
            case WALL_CYAN -> Blocks.CYAN_TERRACOTTA.getDefaultState();
            case WALL_BROWN -> Blocks.BROWN_TERRACOTTA.getDefaultState();
            case WALL_STONE -> Blocks.STONE_BRICKS.getDefaultState();
            case GLASS -> Blocks.GLASS.getDefaultState();
            case GLASS_DARK -> Blocks.GRAY_STAINED_GLASS.getDefaultState();
            case GLASS_BLACK -> Blocks.BLACK_STAINED_GLASS.getDefaultState();
            case GLASS_BLUE -> Blocks.LIGHT_BLUE_STAINED_GLASS.getDefaultState();
            case GLASS_CYAN -> Blocks.CYAN_STAINED_GLASS.getDefaultState();
            case LIT_WARM -> Blocks.OCHRE_FROGLIGHT.getDefaultState();
            case LIT_WHITE -> Blocks.SEA_LANTERN.getDefaultState();
            case LIT_PINK -> Blocks.PEARLESCENT_FROGLIGHT.getDefaultState();
            case LIT_GREEN -> Blocks.VERDANT_FROGLIGHT.getDefaultState();
            case LIT_ORANGE -> Blocks.SHROOMLIGHT.getDefaultState();
            case LIT_YELLOW -> Blocks.GLOWSTONE.getDefaultState();
            case LIT_PURPLE -> Blocks.CRYING_OBSIDIAN.getDefaultState();
            case FLOOR -> Blocks.SMOOTH_STONE.getDefaultState();
            case ROOF -> Blocks.GRAY_CONCRETE_POWDER.getDefaultState();
            case METAL -> Blocks.IRON_BLOCK.getDefaultState();
            case POLE -> Blocks.ANDESITE_WALL.getDefaultState();
            case RAIL -> Blocks.RAIL.getDefaultState();
            case DECK -> Blocks.SMOOTH_STONE.getDefaultState();
            case BARS -> Blocks.IRON_BARS.getDefaultState();
            case C_WHITE -> Blocks.WHITE_CONCRETE.getDefaultState();
            case C_BLACK -> Blocks.BLACK_CONCRETE.getDefaultState();
            case C_RED -> Blocks.RED_CONCRETE.getDefaultState();
            case C_ORANGE -> Blocks.ORANGE_CONCRETE.getDefaultState();
            case C_YELLOW -> Blocks.YELLOW_CONCRETE.getDefaultState();
            case C_LIME -> Blocks.LIME_CONCRETE.getDefaultState();
            case C_GREEN -> Blocks.GREEN_CONCRETE.getDefaultState();
            case C_CYAN -> Blocks.CYAN_CONCRETE.getDefaultState();
            case C_LIGHT_BLUE -> Blocks.LIGHT_BLUE_CONCRETE.getDefaultState();
            case C_BLUE -> Blocks.BLUE_CONCRETE.getDefaultState();
            case C_PURPLE -> Blocks.PURPLE_CONCRETE.getDefaultState();
            case C_MAGENTA -> Blocks.MAGENTA_CONCRETE.getDefaultState();
            case C_PINK -> Blocks.PINK_CONCRETE.getDefaultState();
            case C_GRAY -> Blocks.LIGHT_GRAY_CONCRETE.getDefaultState();
            case TIRE -> Blocks.BLACK_CONCRETE.getDefaultState();
            case CAR_GLASS -> Blocks.BLACK_STAINED_GLASS.getDefaultState();
            case LOG -> Blocks.OAK_LOG.getDefaultState();
            case LEAVES -> Blocks.OAK_LEAVES.getDefaultState().with(net.minecraft.block.LeavesBlock.PERSISTENT, true);
            case FLOWER -> Blocks.POPPY.getDefaultState();
            case TALL_GRASS -> Blocks.GRASS.getDefaultState();
            case BENCH -> Blocks.OAK_STAIRS.getDefaultState().with(StairsBlock.FACING, Direction.NORTH);
            case HEDGE -> Blocks.AZALEA_LEAVES.getDefaultState().with(net.minecraft.block.LeavesBlock.PERSISTENT, true);
            case MOSS -> Blocks.MOSS_BLOCK.getDefaultState();
        };
    }
}
