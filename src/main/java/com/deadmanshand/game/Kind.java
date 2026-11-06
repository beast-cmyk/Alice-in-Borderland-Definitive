package com.deadmanshand.game;

import com.deadmanshand.city.City;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/** Every game there is: its card, its name, how many play it, and how to set it up. */
public enum Kind {
    // --- the first stage: number cards ---
    LIVE_OR_DIE(new Card(Card.Suit.CLUBS, 3), "LIVE OR DIE", 5, 1, LiveOrDie::new),
    TAG(new Card(Card.Suit.SPADES, 5), "TAG", 6, 1, Tag::new),
    WOLF_AND_LAMBS(new Card(Card.Suit.HEARTS, 7), "WOLF AND LAMBS", 4, 1, WolfAndLambs::new),
    DISTANCE(new Card(Card.Suit.CLUBS, 4), "DISTANCE", 5, 1, Distance::new),
    THREE_SWITCHES(new Card(Card.Suit.DIAMONDS, 4), "THREE SWITCHES", 7, 1, ThreeSwitches::new),
    BINGO(new Card(Card.Suit.CLUBS, 10), "BINGO IN THE DARK", 10, 1, Bingo::new),
    HUMAN_ELEVATOR(new Card(Card.Suit.SPADES, 2), "HUMAN ELEVATOR", 6, 1, HumanElevator::new),
    BORROWING_RACE(new Card(Card.Suit.CLUBS, 2), "BORROWING RACE", 6, 1, BorrowingRace::new),
    BEAST_HUNT(new Card(Card.Suit.SPADES, 6), "BEAST HUNT", 8, 1, BeastHunt::new),
    /** Only ever at the camp, on a night when you're staying there. */
    WITCH_HUNT(new Card(Card.Suit.HEARTS, 10), "WITCH HUNT", 22, 1, WitchHunt::new),
    // --- the second stage: face cards, each run by a citizen of this place ---
    SOLITARY_CONFINEMENT(new Card(Card.Suit.HEARTS, 11), "SOLITARY CONFINEMENT", 12, 2, SolitaryConfinement::new),
    BALANCE_SCALE(new Card(Card.Suit.DIAMONDS, 13), "BALANCE SCALE", 4, 2, BalanceScale::new),
    TARGET(new Card(Card.Suit.CLUBS, 12), "TARGET", 12, 2, Target::new),
    BAGWORM(new Card(Card.Suit.CLUBS, 11), "BAGWORM", 10, 2, Bagworm::new),
    CHAMPION(new Card(Card.Suit.SPADES, 11), "CHAMPION", 8, 2, Champion::new),
    CHECKMATE(new Card(Card.Suit.SPADES, 12), "CHECKMATE", 16, 2, Checkmate::new),
    OSMOSIS(new Card(Card.Suit.CLUBS, 13), "OSMOSIS", 5, 2, Osmosis::new),
    SILENT(new Card(Card.Suit.HEARTS, 13), "SILENT", 10, 2, Silent::new),
    CROQUET(new Card(Card.Suit.HEARTS, 12), "CROQUET", 4, 2, Croquet::new),
    MAHJONG(new Card(Card.Suit.DIAMONDS, 11), "MAHJONG", 3, 2, Mahjong::new),
    SURVIVAL(new Card(Card.Suit.SPADES, 13), "SURVIVAL", 12, 2, Survival::new),
    BOILING_DEATH(new Card(Card.Suit.SPADES, 7), "BOILING DEATH", 14, 1, BoilingDeath::new),
    PARKING(new Card(Card.Suit.DIAMONDS, 12), "PARKING", 10, 2, Parking::new),
    // --- the last stage: no numbers, no faces, only Jokers ---
    OLD_MAID(new Card(Card.Suit.SPADES, Card.JOKER), "OLD MAID", 7, 3, OldMaid::new),
    SACRED_FORTUNES(new Card(Card.Suit.SPADES, Card.JOKER), "SACRED FORTUNES", 10, 3, SacredFortunes::new),
    ZOMBIE_HUNT(new Card(Card.Suit.SPADES, Card.JOKER), "ZOMBIE HUNT", 16, 3, ZombieHunt::new),
    RUNAWAY_TRAIN(new Card(Card.Suit.SPADES, Card.JOKER), "RUNAWAY TRAIN", 10, 3, RunawayTrain::new),
    BINGO_TOWER(new Card(Card.Suit.SPADES, Card.JOKER), "BINGO TOWER", 10, 3, BingoTower::new),
    KICK_THE_CAN(new Card(Card.Suit.SPADES, Card.JOKER), "KICK THE CAN", 14, 3, KickTheCan::new),
    FUTURE_SUGOROKU(new Card(Card.Suit.SPADES, Card.JOKER), "FUTURE SUGOROKU", 10, 3, FutureSugoroku::new),
    /** Only ever for whoever finds the way out of Future Sugoroku. */
    ONE_LAST_GAME(new Card(Card.Suit.SPADES, Card.JOKER), "ONE LAST GAME", 1, 3, OneLastGame::new);

    public final Card card;
    public final String title;
    /** Players in all (you, anyone else, and the strangers who fill the empty places). */
    public final int seats;
    /** 1: number cards; 2: face cards (once you've cleared enough); 3: the last games. */
    public final int stage;
    private final Factory factory;

    interface Factory {
        Game create(Kind kind, ServerWorld world, BlockPos origin);
    }

    Kind(Card card, String title, int seats, int stage, Factory factory) {
        this.card = card;
        this.title = title;
        this.seats = seats;
        this.stage = stage;
        this.factory = factory;
    }

    Game create(ServerWorld world, BlockPos origin) {
        return factory.create(this, world, origin);
    }

    /** Opens like any other venue (rather than only under its own conditions). */
    public boolean scheduled() {
        return this != WITCH_HUNT && this != ONE_LAST_GAME;
    }

    /** Its lot in the city. */
    public City.Lot lot() {
        return City.lot(name().toLowerCase());
    }

    /** Its entrance, at street level. */
    public BlockPos origin() {
        City.Lot lot = lot();
        return new BlockPos(lot.entranceX(), City.STREET, lot.entranceZ());
    }
}
