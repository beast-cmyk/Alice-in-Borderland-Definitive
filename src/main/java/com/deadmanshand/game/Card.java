package com.deadmanshand.game;

import com.deadmanshand.DeadMansHand;
import net.minecraft.item.ItemStack;

/** A playing card: the suit says what kind of game it is, the number how hard (and how many days it's worth). */
public record Card(Suit suit, int rank) {
    public enum Suit {
        CLUBS("clubs", "Clubs", '♣', false, "a teamwork game"),
        DIAMONDS("diamonds", "Diamonds", '♦', true, "a game of the mind"),
        HEARTS("hearts", "Hearts", '♥', true, "a game of the heart: of betrayal"),
        SPADES("spades", "Spades", '♠', false, "a game of the body");

        public final String id;
        public final String title;
        public final char symbol;
        public final boolean red;
        public final String kind;

        Suit(String id, String title, char symbol, boolean red, String kind) {
            this.id = id;
            this.title = title;
            this.symbol = symbol;
            this.red = red;
            this.kind = kind;
        }

        /** Section-sign colour code for HUD lines. */
        public String color() {
            return red ? "§c" : "§f";
        }
    }

    private static final String[] RANKS = {"", "Ace", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten",
            "Jack", "Queen", "King"};

    /** Rank 14: the one card that isn't a number or a face. */
    public static final int JOKER = 14;

    /** "Three of Clubs". */
    public String title() {
        return rank == JOKER ? "the Joker" : RANKS[rank] + " of " + suit.title;
    }

    /** "♣3" style, for the HUD. */
    public String shortName() {
        if (rank == JOKER) {
            return "§dJK";
        }
        String r = switch (rank) {
            case 1 -> "A";
            case 11 -> "J";
            case 12 -> "Q";
            case 13 -> "K";
            default -> String.valueOf(rank);
        };
        return suit.color() + suit.symbol + r;
    }

    /** "3C": how it's saved. */
    public String code() {
        return rank + suit.title.substring(0, 1);
    }

    /** Days on your visa for clearing it. */
    public int days() {
        return rank;
    }

    public ItemStack stack() {
        ItemStack stack = new ItemStack(DeadMansHand.CARDS[suit.ordinal()]);
        stack.getOrCreateNbt().putInt("Rank", rank);
        return stack;
    }
}
