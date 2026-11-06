package com.deadmanshand;

import com.deadmanshand.city.City;
import com.deadmanshand.game.Games;
import com.deadmanshand.npc.Contestant;
import com.deadmanshand.npc.Hunter;
import com.deadmanshand.npc.Motorcycle;
import com.deadmanshand.npc.Panther;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.projectile.FireworkRocketEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.registry.Registry;
import net.minecraft.util.registry.RegistryKey;
import net.minecraft.world.GameMode;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The other side: a great city with nobody left in it. No animals, no monsters, no people, except the other players.
 * You get there when the fireworks come, waking in the middle of the crossing; you leave when you die. Server thread
 * only.
 */
public final class Borderland {
    public static final RegistryKey<World> KEY = RegistryKey.of(Registry.WORLD_KEY, DeadMansHand.id("borderland"));
    /** How long the fireworks last before you're taken, in ticks. */
    private static final int SHOW = 560;
    private static final int[] COLORS = {0xFF3B3B, 0xFFD23B, 0x3BFF6E, 0x3BC8FF, 0xC43BFF, 0xFFFFFF, 0xFF8A1E};

    /** Players under the fireworks right now: ticks so far. */
    private static final Map<UUID, Integer> CROSSING = new HashMap<>();
    /** Lines said to newly arrived players, after a pause: player -> ticks since arrival. */
    private static final Map<UUID, Integer> ARRIVED = new HashMap<>();

    private Borderland() {
    }

    static void init() {
        ServerTickEvents.END_SERVER_TICK.register(Borderland::tick);
        ServerPlayerEvents.AFTER_RESPAWN.register(Borderland::respawned);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> joined(handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> CROSSING.remove(handler.player.getUuid()));
    }

    public static ServerWorld world(MinecraftServer server) {
        return server.getWorld(KEY);
    }

    public static boolean is(World world) {
        return world.getRegistryKey().equals(KEY);
    }

    /** Just woke up over there and still taking it in: no venue lights up for them yet. */
    public static boolean settling(ServerPlayerEntity player) {
        return ARRIVED.containsKey(player.getUuid());
    }

    private static void tick(MinecraftServer server) {
        if (server.getTicks() % 20 == 0) {
            for (ServerPlayerEntity player : server.getOverworld().getPlayers()) {
                maybeCross(player);
            }
        }
        for (Iterator<Map.Entry<UUID, Integer>> it = CROSSING.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Integer> entry = it.next();
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(entry.getKey());
            if (player == null || is(player.world)) {
                it.remove();
                continue;
            }
            int t = entry.getValue() + 1;
            entry.setValue(t);
            if (fireworks(player, t)) {
                it.remove();
            }
        }
        for (Iterator<Map.Entry<UUID, Integer>> it = ARRIVED.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Integer> entry = it.next();
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(entry.getKey());
            if (player == null) {
                it.remove();
                continue;
            }
            int t = entry.getValue() + 1;
            entry.setValue(t);
            if (t == 100) {
                say(player, "No people. No animals. Not even the wind sounds right.");
            } else if (t == 260) {
                say(player, "Your phone shows a number you've never seen before: your VISA. It's counting down.");
            } else if (t >= 420) {
                // now the first venue lights up (Games waits for this)
                it.remove();
            }
        }
        ServerWorld borderland = world(server);
        if (borderland != null && server.getTicks() % 40 == 7) {
            empty(borderland);
        }
        if (borderland != null && server.getTicks() % 200 == 50) {
            populate(borderland);
        }
    }

    /** Other people wander the city: never many, and never where you're looking. */
    private static void populate(ServerWorld world) {
        int loaded = world.getEntitiesByType(DeadMansHand.CONTESTANT, entity -> true).size();
        for (ServerPlayerEntity player : world.getPlayers()) {
            if (player.isSpectator() || loaded >= 48) {
                continue;
            }
            int near = world.getEntitiesByClass(Contestant.class, player.getBoundingBox().expand(96.0), c -> c.gameId < 0).size();
            for (int tries = 0; near < 8 && tries < 6; tries++) {
                double angle = world.random.nextDouble() * Math.PI * 2.0;
                double distance = 40.0 + world.random.nextDouble() * 50.0;
                int[] spot = City.pavement((int) (player.getX() + Math.cos(angle) * distance), (int) (player.getZ() + Math.sin(angle) * distance),
                        world.random.nextLong());
                if (spot == null) {
                    continue;
                }
                BlockPos pos = new BlockPos(spot[0], City.STREET, spot[1]);
                if (!world.isAir(pos) || !world.isAir(pos.up()) || player.squaredDistanceTo(pos.getX(), pos.getY(), pos.getZ()) < 30.0 * 30.0) {
                    continue;
                }
                if (Contestant.spawn(world, pos, world.random) != null) {
                    near++;
                    loaded++;
                }
            }
        }
    }

    private static void say(ServerPlayerEntity player, String text) {
        player.sendMessage(Text.literal(text).formatted(Formatting.GRAY, Formatting.ITALIC), false);
    }

    private static void maybeCross(ServerPlayerEntity player) {
        if (!"auto".equals(Config.crossing) || CROSSING.containsKey(player.getUuid()) || player.isSpectator()) {
            return;
        }
        Visa.Data data = Visa.of(player);
        long now = player.world.getTime();
        if (data.nextCrossing < 0L) {
            data.nextCrossing = now + Config.crossingAfterDays * (long) Visa.DAY - player.world.getTimeOfDay() % Visa.DAY;
            return;
        }
        long timeOfDay = player.world.getTimeOfDay() % Visa.DAY;
        if (now >= data.nextCrossing && timeOfDay >= 17000L && timeOfDay <= 20000L) {
            startCrossing(player);
        }
    }

    /** The fireworks begin. Half a minute later, everyone's gone. */
    public static void startCrossing(ServerPlayerEntity player) {
        if (is(player.world) || CROSSING.containsKey(player.getUuid())) {
            return;
        }
        CROSSING.put(player.getUuid(), 0);
        say(player, "Fireworks? At this hour?");
    }

    private static boolean fireworks(ServerPlayerEntity player, int t) {
        if (t < SHOW - 40 && t % 6 == 0) {
            launch(player, t > 300 ? 3 : t > 150 ? 2 : 1);
        }
        if (t == 240) {
            say(player, "They're getting closer. They're right on top of you.");
        }
        if (t == SHOW - 80) {
            Net.flash(player, 140);
            player.world.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ENTITY_FIREWORK_ROCKET_LARGE_BLAST_FAR,
                    SoundCategory.AMBIENT, 4.0F, 0.5F);
        }
        if (t >= SHOW) {
            arrive(player);
            return true;
        }
        return false;
    }

    private static void launch(ServerPlayerEntity player, int count) {
        if (!(player.world instanceof ServerWorld world)) {
            return;
        }
        Random random = world.random;
        for (int i = 0; i < count; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double distance = 14.0 + random.nextDouble() * 46.0;
            double x = player.getX() + Math.cos(angle) * distance;
            double z = player.getZ() + Math.sin(angle) * distance;
            double y = Math.max(player.getY(), world.getTopY(Heightmap.Type.MOTION_BLOCKING, (int) Math.floor(x), (int) Math.floor(z))) + 1.0;
            ItemStack stack = new ItemStack(Items.FIREWORK_ROCKET);
            NbtCompound fireworks = stack.getOrCreateSubNbt("Fireworks");
            fireworks.putByte("Flight", (byte) (1 + random.nextInt(3)));
            NbtList explosions = new NbtList();
            NbtCompound explosion = new NbtCompound();
            explosion.putByte("Type", (byte) random.nextInt(5));
            explosion.putIntArray("Colors", new int[]{COLORS[random.nextInt(COLORS.length)], COLORS[random.nextInt(COLORS.length)]});
            explosion.putIntArray("FadeColors", new int[]{COLORS[random.nextInt(COLORS.length)]});
            explosion.putBoolean("Flicker", random.nextBoolean());
            explosion.putBoolean("Trail", true);
            explosions.add(explosion);
            fireworks.put("Explosions", explosions);
            world.spawnEntity(new FireworkRocketEntity(world, x, y, z, stack));
        }
    }

    /** You wake up on the other side, standing where you were. */
    public static void arrive(ServerPlayerEntity player) {
        ServerWorld borderland = world(player.server);
        if (borderland == null) {
            DeadMansHand.LOG.error("Dead Man's Hand: the borderland dimension is missing (datapack not loaded?)");
            return;
        }
        int[] spot = City.arrival(borderland.random.nextLong());
        borderland.getChunk(spot[0] >> 4, spot[1] >> 4);
        player.teleport(borderland, spot[0] + 0.5, City.STREET + 0.1, spot[1] + 0.5, player.getYaw(), player.getPitch());
        Games.prebuild(borderland);
        Visa.Data data = Visa.of(player);
        data.inside = true;
        data.visa = Config.arrivalVisaDays * Visa.DAY;
        data.firstVenue = true;
        title(player, Text.literal("Silence").formatted(Formatting.WHITE), Text.literal("Everyone is gone.").formatted(Formatting.GRAY));
        ARRIVED.put(player.getUuid(), 0);
    }

    public static BlockPos surface(ServerWorld world, int x, int z) {
        world.getChunk(x >> 4, z >> 4);
        return new BlockPos(x, world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z), z);
    }

    /** Back to your own world, alive (the command). */
    public static void sendHome(ServerPlayerEntity player) {
        Games.leave(player);
        ServerWorld home = player.server.getOverworld();
        BlockPos spawn = home.getSpawnPos();
        BlockPos landing = surface(home, spawn.getX(), spawn.getZ());
        player.teleport(home, landing.getX() + 0.5, landing.getY() + 0.1, landing.getZ() + 0.5, player.getYaw(), player.getPitch());
        Visa.Data data = Visa.of(player);
        data.inside = false;
        data.nextCrossing = home.getTime() + Math.max(1, Config.returnAfterDays) * (long) Visa.DAY;
    }

    private static void respawned(ServerPlayerEntity oldPlayer, ServerPlayerEntity player, boolean alive) {
        if (alive) {
            return;
        }
        Visa.Data data = Visa.of(player);
        if (!data.inside) {
            return;
        }
        data.deaths++;
        Games.leave(player);
        if (Config.permadeath) {
            ServerWorld borderland = world(player.server);
            if (borderland != null) {
                player.changeGameMode(GameMode.SPECTATOR);
                BlockPos at = oldPlayer.getBlockPos();
                player.teleport(borderland, at.getX() + 0.5, Math.max(at.getY(), borderland.getBottomY() + 1) + 1.0, at.getZ() + 0.5,
                        player.getYaw(), player.getPitch());
                title(player, Text.literal("GAME OVER").formatted(Formatting.DARK_RED), Text.literal("You stay behind, watching.").formatted(Formatting.GRAY));
                return;
            }
        }
        data.inside = false;
        data.visa = 0;
        ServerWorld home = player.server.getOverworld();
        if (is(player.world)) {
            BlockPos spawn = home.getSpawnPos();
            BlockPos landing = surface(home, spawn.getX(), spawn.getZ());
            player.teleport(home, landing.getX() + 0.5, landing.getY() + 0.1, landing.getZ() + 0.5, player.getYaw(), player.getPitch());
        }
        data.nextCrossing = home.getTime() + Math.max(1, Config.returnAfterDays) * (long) Visa.DAY;
        title(player, Text.literal("You woke up").formatted(Formatting.WHITE), Text.literal("Was any of it real?").formatted(Formatting.GRAY));
    }

    private static void joined(ServerPlayerEntity player) {
        Visa.Data data = Visa.of(player);
        if (!data.restore.isEmpty() && Games.gameOf(player) == null) {
            GameMode mode = GameMode.byName(data.restore, GameMode.SURVIVAL);
            data.restore = "";
            player.changeGameMode(mode);
        }
        if (data.inside && !is(player.world)) {
            data.inside = false;
        }
    }

    /** Nobody is left over there: anything that wanders in (or was built into a village) is gone again. */
    private static void empty(ServerWorld world) {
        List<Entity> gone = new ArrayList<>();
        for (Entity entity : world.iterateEntities()) {
            if (entity instanceof MobEntity && !(entity instanceof Contestant) && !(entity instanceof Hunter) && !(entity instanceof Panther)
                    && !(entity instanceof Motorcycle) && !entity.getScoreboardTags().contains(DeadMansHand.ID)) {
                gone.add(entity);
            }
        }
        gone.forEach(Entity::discard);
    }

    public static void title(ServerPlayerEntity player, Text title, Text subtitle) {
        player.networkHandler.sendPacket(new TitleFadeS2CPacket(10, 60, 20));
        player.networkHandler.sendPacket(new SubtitleS2CPacket(subtitle));
        player.networkHandler.sendPacket(new TitleS2CPacket(title));
    }
}
