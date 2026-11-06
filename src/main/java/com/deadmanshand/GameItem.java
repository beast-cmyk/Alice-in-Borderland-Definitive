package com.deadmanshand;

import com.deadmanshand.game.Games;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

/** Something a game hands you (a match, a slip...): using it is up to the game you're in. */
public final class GameItem extends Item {
    public GameItem(Settings settings) {
        super(settings);
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        if (!world.isClient && user instanceof ServerPlayerEntity player && Games.used(player, stack)) {
            return TypedActionResult.success(stack, false);
        }
        return TypedActionResult.pass(stack);
    }
}
