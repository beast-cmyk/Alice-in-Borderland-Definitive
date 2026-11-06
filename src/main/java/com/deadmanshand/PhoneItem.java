package com.deadmanshand;

import com.deadmanshand.game.Games;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

/** The phone you take when you register: use it to read the rules again. */
public final class PhoneItem extends Item {
    public PhoneItem(Settings settings) {
        super(settings);
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        if (!world.isClient && user instanceof ServerPlayerEntity player) {
            Games.phone(player);
        }
        return TypedActionResult.success(user.getStackInHand(hand), world.isClient);
    }
}
