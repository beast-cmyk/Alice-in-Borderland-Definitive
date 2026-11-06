package com.deadmanshand;

import com.deadmanshand.game.Games;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.PersistentState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Everyone's visa: how long they have left over there (in ticks; a day is 24000), the cards they've won, and when the
 * fireworks will come for them. Saved with the world. Server thread only.
 */
public final class Visa extends PersistentState {
    public static final int DAY = 24000;

    public static final class Data {
        /** Over there right now. */
        public boolean inside;
        public int visa;
        /** World time when the fireworks may come (-1: not decided yet). */
        public long nextCrossing = -1L;
        public final List<String> cards = new ArrayList<>();
        public int cleared;
        public int deaths;
        /** A game mode to give back (a game put you in adventure mode and you left before it ended). */
        public String restore = "";
        /** Just arrived: open your first venue straight away, whatever the time. */
        public boolean firstVenue;
        /** Has been to the camp (and heard its rules), since when, and how many cards handed in there. */
        public boolean camp;
        public long campSince;
        public int handedIn;
        /** The highest stage you've been told about. */
        public int stageSeen = 1;

        NbtCompound write() {
            NbtCompound nbt = new NbtCompound();
            nbt.putBoolean("Inside", inside);
            nbt.putInt("Visa", visa);
            nbt.putLong("NextCrossing", nextCrossing);
            NbtList list = new NbtList();
            for (String card : cards) {
                list.add(NbtString.of(card));
            }
            nbt.put("Cards", list);
            nbt.putInt("Cleared", cleared);
            nbt.putInt("Deaths", deaths);
            nbt.putString("Restore", restore);
            nbt.putBoolean("FirstVenue", firstVenue);
            nbt.putBoolean("Camp", camp);
            nbt.putLong("CampSince", campSince);
            nbt.putInt("HandedIn", handedIn);
            nbt.putInt("StageSeen", stageSeen);
            return nbt;
        }

        static Data read(NbtCompound nbt) {
            Data data = new Data();
            data.inside = nbt.getBoolean("Inside");
            data.visa = nbt.getInt("Visa");
            data.nextCrossing = nbt.contains("NextCrossing") ? nbt.getLong("NextCrossing") : -1L;
            NbtList list = nbt.getList("Cards", NbtElement.STRING_TYPE);
            for (int i = 0; i < list.size(); i++) {
                data.cards.add(list.getString(i));
            }
            data.cleared = nbt.getInt("Cleared");
            data.deaths = nbt.getInt("Deaths");
            data.restore = nbt.getString("Restore");
            data.firstVenue = nbt.getBoolean("FirstVenue");
            data.camp = nbt.getBoolean("Camp");
            data.campSince = nbt.getLong("CampSince");
            data.handedIn = nbt.getInt("HandedIn");
            data.stageSeen = Math.max(1, nbt.getInt("StageSeen"));
            return data;
        }
    }

    private final Map<UUID, Data> players = new HashMap<>();
    /** The camp has had its witch hunt: whoever's left has scattered, and nobody new moves in. */
    public boolean campFallen;
    /** Cards handed in at the camp, by everyone. */
    public int campCards;
    /** Players whose screen is showing the visa (so it can be cleared when they leave). */
    private static final Set<UUID> SHOWING = new HashSet<>();

    public static Visa get(MinecraftServer server) {
        return server.getOverworld().getPersistentStateManager().getOrCreate(Visa::read, Visa::new, "deadmanshand");
    }

    public static Data of(ServerPlayerEntity player) {
        Visa visa = get(player.server);
        visa.markDirty();
        return visa.players.computeIfAbsent(player.getUuid(), id -> new Data());
    }

    private static Visa read(NbtCompound nbt) {
        Visa visa = new Visa();
        visa.campFallen = nbt.getBoolean("CampFallen");
        visa.campCards = nbt.getInt("CampCards");
        NbtCompound all = nbt.getCompound("Players");
        for (String key : all.getKeys()) {
            try {
                visa.players.put(UUID.fromString(key), Data.read(all.getCompound(key)));
            } catch (IllegalArgumentException ignored) {
                // not a player id
            }
        }
        return visa;
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt) {
        NbtCompound all = new NbtCompound();
        for (Map.Entry<UUID, Data> entry : players.entrySet()) {
            all.put(entry.getKey().toString(), entry.getValue().write());
        }
        nbt.put("Players", all);
        nbt.putBoolean("CampFallen", campFallen);
        nbt.putInt("CampCards", campCards);
        return nbt;
    }

    static void init() {
        ServerTickEvents.END_SERVER_TICK.register(Visa::tick);
    }

    private static void tick(MinecraftServer server) {
        ServerWorld borderland = Borderland.world(server);
        boolean refresh = server.getTicks() % 10 == 0;
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            boolean there = borderland != null && player.world == borderland;
            Data data = there || refresh ? of(player) : null;
            if (there && data.inside && !player.isSpectator()) {
                data.visa--;
                if (data.visa <= 0) {
                    data.visa = 0;
                    // In the middle of a game the sky waits: you either win days or the game kills you
                    if (player.isAlive() && !player.isCreative() && Games.gameOf(player) == null) {
                        Laser.strike(player, "Your visa ran out.");
                    }
                }
            }
            if (refresh) {
                if (there && data.inside) {
                    Hud hud = new Hud();
                    Games.hud(player, hud);
                    Net.hud(player, data.visa, hud);
                    SHOWING.add(player.getUuid());
                } else if (SHOWING.remove(player.getUuid())) {
                    Net.hud(player, -1, new Hud());
                }
            }
        }
    }

    /** Which stage you're in: how many games, and how many face cards, you've cleared. */
    public static int stage(Data data) {
        if (data.cleared < Config.faceCardsAfter) {
            return 1;
        }
        int faces = 0;
        for (String card : data.cards) {
            try {
                if (Integer.parseInt(card.substring(0, card.length() - 1)) >= 11) {
                    faces++;
                }
            } catch (NumberFormatException ignored) {
                // not a card code
            }
        }
        return faces < Config.finalAfter ? 2 : 3;
    }

    /** "2 days 06:30": days, then hours and minutes of the day (a Minecraft day is 20 minutes). */
    public static String describe(int ticks) {
        int days = ticks / DAY;
        int rest = ticks % DAY;
        int hours = rest / 1000;
        int minutes = rest % 1000 * 60 / 1000;
        return (days > 0 ? days + (days == 1 ? " day " : " days ") : "") + (hours < 10 ? "0" : "") + hours + ":" + (minutes < 10 ? "0" : "") + minutes;
    }
}
