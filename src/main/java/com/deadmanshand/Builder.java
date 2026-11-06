package com.deadmanshand;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.WallSignBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.DyeColor;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayDeque;

/**
 * Builds a venue a few thousand blocks a tick, so it rises over a second or two instead of freezing the game. Blocks
 * are placed without neighbour updates, so nothing falls or flows while it goes up.
 */
public final class Builder {
    private static final int FLAGS = Block.NOTIFY_LISTENERS | Block.FORCE_STATE;
    private final ServerWorld world;
    private final ArrayDeque<Object> ops = new ArrayDeque<>();

    private record Op(long pos, BlockState state) {
    }

    public Builder(ServerWorld world) {
        this.world = world;
    }

    public void set(int x, int y, int z, BlockState state) {
        ops.add(new Op(BlockPos.asLong(x, y, z), state));
    }

    public void set(BlockPos pos, BlockState state) {
        ops.add(new Op(pos.asLong(), state));
    }

    public void fill(int x1, int y1, int z1, int x2, int y2, int z2, BlockState state) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
            for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
                    set(x, y, z, state);
                }
            }
        }
    }

    /** Four walls (no floor or ceiling). */
    public void walls(int x1, int y1, int z1, int x2, int y2, int z2, BlockState state) {
        fill(x1, y1, z1, x2, y2, z1, state);
        fill(x1, y1, z2, x2, y2, z2, state);
        fill(x1, y1, z1, x1, y2, z2, state);
        fill(x2, y1, z1, x2, y2, z2, state);
    }

    /** Runs once everything queued before it has been placed. */
    public void then(Runnable action) {
        ops.add(action);
    }

    /** A two-high door: both halves. */
    public void door(BlockPos pos, Block door, Direction facing) {
        BlockState lower = door.getDefaultState().with(DoorBlock.FACING, facing).with(DoorBlock.HALF, DoubleBlockHalf.LOWER);
        set(pos, lower);
        set(pos.up(), lower.with(DoorBlock.HALF, DoubleBlockHalf.UPPER));
    }

    /** A dark sign with glowing white writing, on the wall behind it ({@code facing}: the way the writing faces). */
    public void sign(BlockPos pos, Direction facing, String... lines) {
        set(pos, Blocks.DARK_OAK_WALL_SIGN.getDefaultState().with(WallSignBlock.FACING, facing));
        then(() -> write(world, pos, lines));
    }

    public static void write(ServerWorld world, BlockPos pos, String... lines) {
        BlockEntity entity = world.getBlockEntity(pos);
        if (entity instanceof SignBlockEntity sign) {
            for (int row = 0; row < 4; row++) {
                sign.setTextOnRow(row, Text.literal(row < lines.length ? lines[row] : ""));
            }
            sign.setTextColor(DyeColor.WHITE);
            sign.setGlowingText(true);
            sign.markDirty();
            BlockState state = world.getBlockState(pos);
            world.updateListeners(pos, state, state, Block.NOTIFY_ALL);
        }
    }

    /** Places up to {@code budget} blocks; true when it's all built. */
    public boolean step(int budget) {
        while (budget-- > 0 && !ops.isEmpty()) {
            Object op = ops.poll();
            if (op instanceof Op block) {
                world.setBlockState(BlockPos.fromLong(block.pos), block.state, FLAGS);
            } else if (op instanceof Runnable action) {
                action.run();
            }
        }
        return ops.isEmpty();
    }

    public boolean done() {
        return ops.isEmpty();
    }
}
