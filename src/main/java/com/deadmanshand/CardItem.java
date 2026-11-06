package com.deadmanshand;

import com.deadmanshand.game.Card;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/** The card you're given for clearing a game: "Three of Clubs". */
public final class CardItem extends Item {
    public final Card.Suit suit;

    public CardItem(Card.Suit suit, Settings settings) {
        super(settings);
        this.suit = suit;
    }

    @Override
    public Text getName(ItemStack stack) {
        NbtCompound nbt = stack.getNbt();
        int rank = nbt == null ? 0 : nbt.getInt("Rank");
        if (rank == Card.JOKER) {
            return Text.literal("The Joker").formatted(Formatting.LIGHT_PURPLE);
        }
        String name = rank >= 1 && rank <= 13 ? new Card(suit, rank).title() : "Card of " + suit.title;
        return Text.literal(name).formatted(suit.red ? Formatting.RED : Formatting.WHITE);
    }
}
