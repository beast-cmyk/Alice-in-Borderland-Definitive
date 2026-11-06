package com.deadmanshand.game;

import com.deadmanshand.Borderland;
import com.deadmanshand.Hud;
import com.deadmanshand.Visa;
import com.deadmanshand.npc.Contestant;
import com.deadmanshand.npc.Voice;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Every game going on, and when new venues light up: at dusk, near anyone over there who isn't near one already
 * (and straight away for someone who's just arrived). Server thread only.
 */
public final class Games {
    static final List<Game> ACTIVE = new ArrayList<>();
    private static final Map<UUID, Long> LAST_OPENED = new HashMap<>();
    private static final Map<UUID, Deque<Kind>> RECENT = new HashMap<>();
    private record Said(UUID player, String message) {
    }

    private static final List<Said> HEARD = new ArrayList<>();
    /** Venues being put up before anyone plays them. */
    private static final Map<Kind, com.deadmanshand.Builder> PREBUILD = new java.util.EnumMap<>(Kind.class);

    private Games() {
    }

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(Games::tick);
        UseBlockCallback.EVENT.register(Games::use);
        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (world.isClient || hand != Hand.MAIN_HAND || !(player instanceof ServerPlayerEntity serverPlayer)) {
                return ActionResult.PASS;
            }
            Game game = gameOf(serverPlayer);
            return game != null && game.touch(serverPlayer, entity) ? ActionResult.SUCCESS : ActionResult.PASS;
        });
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (world.isClient || !(player instanceof ServerPlayerEntity serverPlayer)) {
                return ActionResult.PASS;
            }
            if (entity instanceof Contestant c && c.role.equals("guard") && active(Kind.WITCH_HUNT) instanceof WitchHunt hunt) {
                hunt.hurtGuard(serverPlayer);
            }
            Game game = gameOf(serverPlayer);
            return game != null && game.attack(serverPlayer, entity) ? ActionResult.FAIL : ActionResult.PASS;
        });
        Camp.init();
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            synchronized (HEARD) {
                HEARD.add(new Said(sender.getUuid(), message.getContent().getString()));
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            for (Game game : ACTIVE) {
                game.abandon();
            }
            ACTIVE.clear();
            LAST_OPENED.clear();
            PREBUILD.clear();
        });
    }

    private static void tick(MinecraftServer server) {
        for (Game game : new ArrayList<>(ACTIVE)) {
            try {
                game.tick();
            } catch (RuntimeException e) {
                com.deadmanshand.DeadMansHand.LOG.error("Dead Man's Hand: a game broke ({}); ending it", game.kind, e);
                game.abandon();
                game.closed = true;
            }
            if (game.closed) {
                ACTIVE.remove(game);
            }
        }
        List<Said> said;
        synchronized (HEARD) {
            said = new ArrayList<>(HEARD);
            HEARD.clear();
        }
        for (Said line : said) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(line.player());
            if (player != null) {
                heard(player, line.message());
            }
        }
        ServerWorld borderland = Borderland.world(server);
        if (borderland != null && server.getTicks() % 40 == 0) {
            schedule(borderland);
        }
        if (!PREBUILD.isEmpty()) {
            Map.Entry<Kind, com.deadmanshand.Builder> next = PREBUILD.entrySet().iterator().next();
            if (next.getValue().step(5000)) {
                PREBUILD.remove(next.getKey());
            }
        }
    }

    /** The first time anyone arrives: every venue goes up on its lot, dark, waiting. */
    public static void prebuild(ServerWorld world) {
        for (Kind kind : Kind.values()) {
            if (active(kind) == null && !PREBUILD.containsKey(kind)) {
                PREBUILD.put(kind, kind.create(world, kind.origin()).prebuild());
            }
        }
    }

    @Nullable
    static Game active(Kind kind) {
        for (Game game : ACTIVE) {
            if (game.kind == kind && game.phase != Game.Phase.ENDED) {
                return game;
            }
        }
        return null;
    }

    private static void schedule(ServerWorld world) {
        long timeOfDay = world.getTimeOfDay() % Visa.DAY;
        boolean evening = timeOfDay >= 12500L && timeOfDay < 22000L;
        for (ServerPlayerEntity player : world.getPlayers()) {
            if (player.isSpectator()) {
                continue;
            }
            Visa.Data data = Visa.of(player);
            int stage = Visa.stage(data);
            if (data.inside && stage > data.stageSeen) {
                data.stageSeen = stage;
                stageBegins(player, stage);
            }
            if (!data.inside || Borderland.settling(player) || gameOf(player) != null || nearest(player, 260.0) != null) {
                continue;
            }
            Long last = LAST_OPENED.get(player.getUuid());
            if (!data.firstVenue && (!evening || last != null && world.getTime() - last < 20L * 60L * 4L)) {
                continue;
            }
            if (data.firstVenue && last != null && world.getTime() - last < 20L * 30L) {
                continue;
            }
            Kind kind = pick(player, world.random, data.firstVenue);
            data.firstVenue = false;
            if (kind != null) {
                open(world, player, kind);
            }
        }
    }

    /** A game you haven't just played, that isn't running already; the first night, always the nearest. */
    @Nullable
    private static Kind pick(ServerPlayerEntity player, Random random, boolean first) {
        Deque<Kind> recent = RECENT.computeIfAbsent(player.getUuid(), id -> new ArrayDeque<>());
        int stage = Visa.stage(Visa.of(player));
        List<Kind> choices = new ArrayList<>();
        for (Kind kind : Kind.values()) {
            if (kind.scheduled() && kind.stage <= stage && !recent.contains(kind) && active(kind) == null
                    && !(kind.stage < stage && Visa.of(player).cards.contains(kind.card.code()) && random.nextFloat() < 0.6F)) {
                choices.add(kind);
            }
        }
        if (choices.isEmpty()) {
            recent.clear();
            for (Kind kind : Kind.values()) {
                if (kind.scheduled() && kind.stage <= stage && active(kind) == null) {
                    choices.add(kind);
                }
            }
        }
        if (choices.isEmpty()) {
            return null;
        }
        choices.sort(java.util.Comparator.comparingDouble(kind -> kind.origin().getSquaredDistance(player.getBlockPos())));
        return first ? choices.get(0) : choices.get(random.nextInt(Math.min(3, choices.size())));
    }

    /** A venue for {@code kind} lights up near {@code player}. */
    @Nullable
    public static Game open(ServerWorld world, ServerPlayerEntity player, Kind kind) {
        Game running = active(kind);
        if (running != null) {
            return running;
        }
        PREBUILD.remove(kind);
        Game game = kind.create(world, kind.origin());
        ACTIVE.add(game);
        LAST_OPENED.put(player.getUuid(), world.getTime());
        Deque<Kind> recent = RECENT.computeIfAbsent(player.getUuid(), id -> new ArrayDeque<>());
        recent.addLast(kind);
        while (recent.size() > 3) {
            recent.pollFirst();
        }
        player.sendMessage(Text.literal("Somewhere in the city, a building has lit up. A game is about to begin.").formatted(Formatting.GOLD), false);
        return game;
    }

    @Nullable
    private static Game nearest(Entity entity, double range) {
        return nearest(entity.getBlockPos(), range);
    }

    @Nullable
    private static Game nearest(BlockPos pos, double range) {
        Game best = null;
        double bestDistance = range * range;
        for (Game game : ACTIVE) {
            if (game.phase == Game.Phase.ENDED) {
                continue;
            }
            double distance = game.entrance().getSquaredDistance(pos);
            if (distance < bestDistance) {
                best = game;
                bestDistance = distance;
            }
        }
        return best;
    }

    /** The game {@code entity} is playing (or waiting for), if any. */
    @Nullable
    public static Game gameOf(Entity entity) {
        for (Game game : ACTIVE) {
            if (game.phase != Game.Phase.ENDED && game.isPlaying(entity.getUuid())) {
                return game;
            }
        }
        return null;
    }

    @Nullable
    public static Game byId(int id) {
        for (Game game : ACTIVE) {
            if (game.id == id) {
                return game;
            }
        }
        return null;
    }

    private static void stageBegins(ServerPlayerEntity player, int stage) {
        if (stage == 2) {
            Borderland.title(player, Text.literal("THE SECOND STAGE").formatted(Formatting.GOLD), Text.literal("the face cards").formatted(Formatting.WHITE));
            player.sendMessage(Text.literal("Your phone lights up: from now on the games are run by the people who live here, and each one holds a face "
                    + "card. Somewhere out there, the King of Spades has started hunting.").formatted(Formatting.GOLD), false);
        } else if (stage == 3) {
            Borderland.title(player, Text.literal("THE LAST GAMES").formatted(Formatting.GOLD), Text.literal("nearly the end").formatted(Formatting.WHITE));
            player.sendMessage(Text.literal("Your phone shows a new map. The last games have begun.").formatted(Formatting.GOLD), false);
        }
    }

    /** You used a game item (a match...): the game you're in decides what happens. */
    public static boolean used(ServerPlayerEntity player, ItemStack stack) {
        Game game = gameOf(player);
        return game != null && game.use(player, stack);
    }

    /** /answer: for games where you say or choose something. */
    public static void answer(ServerPlayerEntity player, String answer) {
        Game game = gameOf(player);
        if (game == null) {
            player.sendMessage(Text.literal("You're not in a game.").formatted(Formatting.GRAY), false);
            return;
        }
        game.answer(player, answer.trim());
    }

    /** You're going home (or died): you're out of any game you're in. */
    public static void leave(ServerPlayerEntity player) {
        Game game = gameOf(player);
        if (game != null && game.isPlaying(player.getUuid())) {
            game.seats.put(player.getUuid(), Game.Fate.DEAD);
        }
    }

    private static ActionResult use(PlayerEntity player, World world, Hand hand, BlockHitResult hit) {
        if (world.isClient || !(player instanceof ServerPlayerEntity serverPlayer) || hand != Hand.MAIN_HAND) {
            return ActionResult.PASS;
        }
        BlockPos pos = hit.getBlockPos();
        for (Game game : ACTIVE) {
            if (game.inside(pos) && game.phase != Game.Phase.ENDED && game.phase != Game.Phase.BUILDING) {
                ActionResult result = game.useBlock(serverPlayer, pos, world.getBlockState(pos));
                if (result != ActionResult.PASS) {
                    return result;
                }
            }
        }
        return Camp.useBlock(serverPlayer, pos);
    }

    private static void heard(ServerPlayerEntity player, String message) {
        if (!Borderland.is(player.world)) {
            return;
        }
        Game game = gameOf(player);
        if (game != null) {
            game.hear(player, message);
        }
        Contestant listener = Contestant.listening(player, 14.0);
        if (listener != null) {
            Voice.reply(listener, player, message);
        }
    }

    /** The lines on your screen: your game, or where the nearest venue is. */
    public static void hud(ServerPlayerEntity player, Hud hud) {
        for (Game game : ACTIVE) {
            if (game.has(player.getUuid()) && (game.phase != Game.Phase.ENDED || game.phaseTicks < 100)) {
                game.hud(player, hud);
                return;
            }
        }
        Game near = nearest(player, 400.0);
        if (near != null && near.phase == Game.Phase.OPEN) {
            BlockPos at = near.entrance();
            double dx = at.getX() + 0.5 - player.getX();
            double dz = at.getZ() + 0.5 - player.getZ();
            hud.line("§6VENUE OPEN  §f" + Math.round(Math.sqrt(dx * dx + dz * dz)) + " m " + compass(dx, dz));
        } else if (near != null && near.phase == Game.Phase.BUILDING) {
            hud.line("§7A venue is lighting up...");
        } else if (near != null && (near.phase == Game.Phase.BRIEFING || near.phase == Game.Phase.RUNNING)) {
            hud.line("§7A game is being played nearby.");
        }
    }

    private static String compass(double dx, double dz) {
        String[] names = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};
        double angle = Math.toDegrees(Math.atan2(-dx, dz));
        int index = (int) Math.floorMod(Math.round(angle / 45.0), 8L);
        return names[index];
    }

    public static void phone(ServerPlayerEntity player) {
        for (Game game : ACTIVE) {
            if (game.has(player.getUuid())) {
                game.phone(player);
                return;
            }
        }
        Visa.Data data = Visa.of(player);
        player.sendMessage(Text.literal(data.inside ? "VISA: " + Visa.describe(data.visa) + ".  Cards: " + (data.cards.isEmpty() ? "none" : String.join(", ", data.cards))
                : "No signal.").formatted(Formatting.GRAY), false);
    }
}
