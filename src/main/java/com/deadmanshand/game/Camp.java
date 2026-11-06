package com.deadmanshand.game;

import com.deadmanshand.Borderland;
import com.deadmanshand.Builder;
import com.deadmanshand.CardItem;
import com.deadmanshand.DeadMansHand;
import com.deadmanshand.Visa;
import com.deadmanshand.npc.Contestant;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LadderBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.enums.BedPart;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Lagoon: a resort hotel the longer-lasting players have taken over. A pool, a garden with a bonfire that never goes
 * out, a party every night, a guard with guns, and a wall in the lobby where every card is handed in, because when the
 * wall is full, they say, everyone goes home. One night the ten of hearts is played here.
 */
public final class Camp {
    public static final int RESIDENTS = 18;
    private static final String[] WELCOME = {
            "Welcome to the Lagoon. Nobody here plays alone.",
            "Rule one: every card belongs to the camp. Hand yours in at the wall in the lobby.",
            "Rule two: guns are for the guard. Nobody else carries one.",
            "Rule three: this is a party. Dress for the pool and have a good time.",
            "Rule four: traitors die.",
            "When the wall is full, we all go home. Find yourself a room."};
    private static final Map<UUID, Integer> GREETING = new HashMap<>();

    private Camp() {
    }

    static void init() {
        ServerTickEvents.END_SERVER_TICK.register(Camp::tick);
    }

    // --- where everything is -----------------------------------------------------------------------------------

    static BlockPos origin() {
        return Kind.WITCH_HUNT.origin();
    }

    /** The whole camp, from the gate to the back of the hotel. */
    public static Box area() {
        BlockPos o = origin();
        return new Box(o.getX() - 25, o.getY() - 4, o.getZ() - 48, o.getX() + 26, o.getY() + 28, o.getZ() + 3);
    }

    public static boolean inside(Vec3d pos) {
        return area().contains(pos);
    }

    /** In front of the front desk, where she's found. */
    static BlockPos reception() {
        BlockPos o = origin();
        return new BlockPos(o.getX(), o.getY(), o.getZ() - 37);
    }

    /** The middle of the bonfire. */
    static BlockPos fire() {
        BlockPos o = origin();
        return new BlockPos(o.getX() + 13, o.getY(), o.getZ() - 19);
    }

    static BlockPos wallSign() {
        BlockPos o = origin();
        return new BlockPos(o.getX(), o.getY() + 2, o.getZ() - 43);
    }

    static BlockPos roof() {
        BlockPos o = origin();
        return new BlockPos(o.getX() + 2, o.getY() + 20, o.getZ() - 36);
    }

    static BlockPos diary() {
        BlockPos o = origin();
        return new BlockPos(o.getX() + 12, o.getY() + 4, o.getZ() - 40);
    }

    private static BlockPos desk() {
        BlockPos o = origin();
        return new BlockPos(o.getX(), o.getY(), o.getZ() - 39);
    }

    /** Somewhere people hang about: the pool deck, the garden, the lobby. */
    static BlockPos hangout(Random random) {
        BlockPos o = origin();
        return switch (random.nextInt(4)) {
            case 0 -> new BlockPos(o.getX() - 21 + random.nextInt(3), o.getY(), o.getZ() - 28 + random.nextInt(14));
            case 1 -> new BlockPos(o.getX() - 18 + random.nextInt(14), o.getY(), o.getZ() - 12 + random.nextInt(3));
            case 2 -> new BlockPos(o.getX() + 6 + random.nextInt(14), o.getY(), o.getZ() - 12 - random.nextInt(4));
            default -> new BlockPos(o.getX() - 12 + random.nextInt(24), o.getY(), o.getZ() - 37 + random.nextInt(2));
        };
    }

    static boolean built(ServerWorld world) {
        BlockPos desk = desk();
        return world.isChunkLoaded(desk) && world.getBlockState(desk).isOf(Blocks.POLISHED_DIORITE);
    }

    // --- the building -----------------------------------------------------------------------------------------

    static void build(Builder b, ServerWorld world, BlockPos o) {
        int ox = o.getX();
        int oy = o.getY();
        int oz = o.getZ();
        BlockState air = Blocks.AIR.getDefaultState();
        BlockState grass = Blocks.GRASS_BLOCK.getDefaultState();
        BlockState path = Blocks.SMOOTH_SANDSTONE.getDefaultState();
        BlockState deck = Blocks.SMOOTH_QUARTZ.getDefaultState();
        BlockState hedge = Blocks.AZALEA_LEAVES.getDefaultState().with(net.minecraft.block.LeavesBlock.PERSISTENT, true);
        BlockState wall = Blocks.WHITE_CONCRETE.getDefaultState();
        BlockState glass = Blocks.LIGHT_BLUE_STAINED_GLASS.getDefaultState();
        BlockState slab = Blocks.SMOOTH_STONE.getDefaultState();
        // Ground: lawn everywhere, the lot cleared above it
        b.fill(ox - 24, oy, oz - 47, ox + 25, oy + 26, oz + 1, air);
        b.fill(ox - 24, oy - 1, oz - 47, ox + 25, oy - 1, oz + 1, grass);
        // The hedge round the edge, with the gate in the south side
        b.walls(ox - 24, oy, oz - 47, ox + 25, oy + 1, oz + 1, hedge);
        b.fill(ox - 2, oy, oz + 1, ox + 2, oy + 1, oz + 1, air);
        b.set(ox - 3, oy + 2, oz + 1, Blocks.LANTERN.getDefaultState());
        b.set(ox + 3, oy + 2, oz + 1, Blocks.LANTERN.getDefaultState());
        b.fill(ox - 3, oy, oz + 1, ox - 3, oy + 1, oz + 1, Blocks.STONE_BRICK_WALL.getDefaultState());
        b.fill(ox + 3, oy, oz + 1, ox + 3, oy + 1, oz + 1, Blocks.STONE_BRICK_WALL.getDefaultState());
        // Paths: gate to lobby, and across to the pool and the garden
        b.fill(ox - 1, oy - 1, oz - 33, ox + 1, oy - 1, oz + 1, path);
        b.fill(ox - 20, oy - 1, oz - 12, ox + 21, oy - 1, oz - 10, path);
        for (int z = oz - 31; z <= oz - 2; z += 5) {
            b.set(ox - 2, oy, z, Blocks.OAK_FENCE.getDefaultState());
            b.set(ox - 2, oy + 1, z, Blocks.LANTERN.getDefaultState());
            b.set(ox + 2, oy, z, Blocks.OAK_FENCE.getDefaultState());
            b.set(ox + 2, oy + 1, z, Blocks.LANTERN.getDefaultState());
        }

        // --- the hotel: five floors along the north side ---
        int x0 = ox - 21;
        int x1 = ox + 21;
        int z0 = oz - 45;
        int z1 = oz - 33;
        int top = oy + 20;
        b.fill(x0, oy - 1, z0, x1, oy - 1, z1, Blocks.POLISHED_ANDESITE.getDefaultState());
        b.walls(x0, oy, z0, x1, top, z1, wall);
        for (int f = 0; f < 5; f++) {
            int y = oy + 4 * f;
            if (f > 0) {
                b.fill(x0 + 1, y - 1, z0 + 1, x1 - 1, y - 1, z1 - 1, slab);
            }
            // windows all round, two high, between the pillars
            for (int x = x0 + 2; x <= x1 - 2; x++) {
                if (Math.floorMod(x - x0, 4) != 0) {
                    b.fill(x, y + 1, z1, x, y + 2, z1, glass);
                    b.fill(x, y + 1, z0, x, y + 2, z0, glass);
                }
            }
            if (f > 0) {
                // the rooms: a corridor at the back, rooms facing the pool, a balcony each
                b.fill(x0 + 1, y, z0 + 4, x1 - 1, y + 2, z0 + 4, wall);
                for (int x = x0 + 6; x < x1; x += 6) {
                    b.fill(x, y, z0 + 5, x, y + 2, z1 - 1, wall);
                    b.fill(x - 3, y, z0 + 4, x - 2, y + 1, z0 + 4, air);
                }
                b.fill(x1 - 4, y, z0 + 4, x1 - 3, y + 1, z0 + 4, air);
                b.fill(x0 + 1, y - 1, z1 + 1, x1 - 1, y - 1, z1 + 1, slab);
                b.fill(x0 + 1, y, z1 + 1, x1 - 1, y, z1 + 1, Blocks.IRON_BARS.getDefaultState());
                for (int x = x0 + 3; x < x1; x += 6) {
                    b.fill(x, y, z1, x + 1, y + 1, z1, air);
                }
                b.set(x0 + 8, y + 2, z0 + 2, Blocks.SEA_LANTERN.getDefaultState());
                b.set(x1 - 8, y + 2, z0 + 2, Blocks.SEA_LANTERN.getDefaultState());
            }
        }
        b.fill(x0, top, z0, x1, top, z1, slab);
        b.walls(x0, top + 1, z0, x1, top + 1, z1, Blocks.SMOOTH_QUARTZ_SLAB.getDefaultState());
        // Ladders up the west end, floor to roof
        for (int y = oy; y < top + 1; y++) {
            b.set(x0 + 1, y, z0 + 2, Blocks.LADDER.getDefaultState().with(LadderBlock.FACING, Direction.EAST));
        }
        // The lobby: doors to the garden, a long front desk, the card wall behind it
        b.fill(ox - 1, oy, z1, ox + 1, oy + 2, z1, air);
        b.fill(ox - 6, oy, desk().getZ(), ox + 6, oy, desk().getZ(), Blocks.POLISHED_DIORITE.getDefaultState());
        b.fill(ox - 6, oy + 1, desk().getZ(), ox + 6, oy + 1, desk().getZ(), Blocks.SMOOTH_QUARTZ_SLAB.getDefaultState());
        b.fill(ox - 5, oy + 1, z0 + 1, ox + 5, oy + 3, z0 + 1, Blocks.BLACK_CONCRETE.getDefaultState());
        for (int x = ox - 5; x <= ox + 5; x++) {
            b.set(x, oy + 4 - 1, z0 + 2, Blocks.SEA_LANTERN.getDefaultState());
        }
        int cards = world.getServer() == null ? 0 : Visa.get(world.getServer()).campCards;
        b.sign(wallSign(), Direction.SOUTH, "THE WALL", "every card", "takes us home", cards + " handed in");
        for (int x = ox - 16; x <= ox + 16; x += 8) {
            b.set(x, oy + 3, oz - 39, Blocks.SEA_LANTERN.getDefaultState());
        }
        b.set(ox - 14, oy, oz - 36, Blocks.WHITE_WOOL.getDefaultState());
        b.set(ox - 13, oy, oz - 36, Blocks.WHITE_WOOL.getDefaultState());
        b.set(ox + 13, oy, oz - 36, Blocks.WHITE_WOOL.getDefaultState());
        b.set(ox + 14, oy, oz - 36, Blocks.WHITE_WOOL.getDefaultState());
        // Her room, first floor up: a bed, a chest, and in the chest her diary
        BlockPos chest = diary();
        b.set(chest, Blocks.CHEST.getDefaultState());
        b.set(chest.add(2, 0, 4), Blocks.WHITE_BED.getDefaultState().with(BedBlock.FACING, Direction.NORTH).with(BedBlock.PART, BedPart.FOOT));
        b.set(chest.add(2, 0, 3), Blocks.WHITE_BED.getDefaultState().with(BedBlock.FACING, Direction.NORTH).with(BedBlock.PART, BedPart.HEAD));
        b.then(() -> fillDiary(world));
        // The name on the roof, lit, facing the pool
        b.fill(ox - 13, top + 1, z1 - 1, ox + 12, top + 7, z1 - 1, Blocks.BLACK_CONCRETE.getDefaultState());
        letters(b, "LAGOON", ox - 12, top + 2, z1, Blocks.PEARLESCENT_FROGLIGHT.getDefaultState());

        // --- the pool ---
        int px0 = ox - 20;
        int px1 = ox - 4;
        int pz0 = oz - 29;
        int pz1 = oz - 14;
        b.fill(px0, oy - 1, pz0, px1, oy - 1, pz1, deck);
        b.fill(px0 + 1, oy - 4, pz0 + 1, px1 - 1, oy - 4, pz1 - 1, Blocks.CYAN_TERRACOTTA.getDefaultState());
        b.walls(px0 + 1, oy - 3, pz0 + 1, px1 - 1, oy - 2, pz1 - 1, Blocks.CYAN_TERRACOTTA.getDefaultState());
        b.fill(px0 + 2, oy - 3, pz0 + 2, px1 - 2, oy - 1, pz1 - 2, Blocks.WATER.getDefaultState());
        b.fill(px0 + 1, oy - 1, pz0 + 1, px1 - 1, oy - 1, pz0 + 1, Blocks.SEA_LANTERN.getDefaultState());
        for (int z = pz0 + 2; z <= pz1 - 2; z += 3) {
            b.set(px0, oy, z, Blocks.WHITE_CARPET.getDefaultState());
            b.set(px1, oy, z, Blocks.WHITE_CARPET.getDefaultState());
        }
        umbrella(b, px0 - 2, oy, pz0 + 4, Blocks.RED_WOOL.getDefaultState());
        umbrella(b, px0 - 2, oy, pz1 - 3, Blocks.YELLOW_WOOL.getDefaultState());
        umbrella(b, px1 + 2, oy, pz0 + 3, Blocks.LIGHT_BLUE_WOOL.getDefaultState());

        // --- the garden and the bonfire ---
        BlockPos fire = fire();
        int fx = fire.getX();
        int fz = fire.getZ();
        b.fill(fx - 3, oy - 1, fz - 3, fx + 3, oy - 1, fz + 3, Blocks.STONE_BRICKS.getDefaultState());
        b.fill(fx - 1, oy - 1, fz - 1, fx + 1, oy - 1, fz + 1, Blocks.NETHERRACK.getDefaultState());
        b.fill(fx - 1, oy, fz - 1, fx + 1, oy, fz + 1, Blocks.FIRE.getDefaultState());
        for (int i = -2; i <= 2; i += 4) {
            b.set(fx + i, oy, fz, Blocks.CAMPFIRE.getDefaultState());
            b.set(fx, oy, fz + i, Blocks.CAMPFIRE.getDefaultState());
        }
        for (int d = -1; d <= 1; d++) {
            b.set(fx - 6, oy, fz + d, Blocks.OAK_STAIRS.getDefaultState().with(StairsBlock.FACING, Direction.WEST));
            b.set(fx + 6, oy, fz + d, Blocks.OAK_STAIRS.getDefaultState().with(StairsBlock.FACING, Direction.EAST));
            b.set(fx + d, oy, fz + 6, Blocks.OAK_STAIRS.getDefaultState().with(StairsBlock.FACING, Direction.SOUTH));
        }
        for (int x = ox + 5; x <= ox + 23; x += 6) {
            b.fill(x, oy, oz - 31, x + 1, oy + 1, oz - 31, hedge);
            b.fill(x, oy, oz - 7, x + 1, oy, oz - 7, hedge);
        }
        for (int x = ox + 6; x <= ox + 22; x += 4) {
            b.set(x, oy, oz - 9, Blocks.POPPY.getDefaultState());
            b.set(x + 1, oy, oz - 9, Blocks.CORNFLOWER.getDefaultState());
        }
    }

    private static void umbrella(Builder b, int x, int y, int z, BlockState cloth) {
        b.fill(x, y, z, x, y + 2, z, Blocks.OAK_FENCE.getDefaultState());
        b.fill(x - 1, y + 3, z - 1, x + 1, y + 3, z + 1, cloth);
    }

    /** Block letters three wide and five high, facing south, starting at (x, y) and going east. */
    private static void letters(Builder b, String word, int x, int y, int z, BlockState state) {
        for (char c : word.toCharArray()) {
            String[] glyph = Glyphs.of(c);
            for (int row = 0; row < 5; row++) {
                for (int col = 0; col < 3; col++) {
                    if (glyph[row].charAt(col) == '#') {
                        b.set(x + col, y + 4 - row, z, state);
                    }
                }
            }
            x += 4;
        }
    }

    private static void fillDiary(ServerWorld world) {
        if (!(world.getBlockEntity(diary()) instanceof ChestBlockEntity chest)) {
            return;
        }
        ItemStack book = new ItemStack(Items.WRITABLE_BOOK);
        NbtList pages = new NbtList();
        pages.add(NbtString.of("They don't know what I am. I deal the games. I set up the rooms and read out the rules, and every time "
                + "someone dies over there, I get their days."));
        pages.add(NbtString.of("Tonight they'll find me at the front desk. Let them look for whoever did it. Nobody did it but me. "
                + "If the game that comes for this place needs a witch, it can have me."));
        book.getOrCreateNbt().put("pages", pages);
        book.setCustomName(Text.literal("Diary").formatted(Formatting.GRAY));
        chest.setStack(13, book);
    }

    // --- the people -------------------------------------------------------------------------------------------

    private static void tick(MinecraftServer server) {
        if (server.getTicks() % 20 != 3) {
            return;
        }
        try {
            tickCamp(server);
        } catch (RuntimeException e) {
            DeadMansHand.LOG.error("Dead Man's Hand: the camp tick failed", e);
        }
    }

    private static void tickCamp(MinecraftServer server) {
        ServerWorld world = Borderland.world(server);
        if (world == null) {
            return;
        }
        Visa visa = Visa.get(server);
        BlockPos o = origin();
        boolean near = false;
        for (ServerPlayerEntity player : world.getPlayers()) {
            if (player.isSpectator()) {
                continue;
            }
            if (player.squaredDistanceTo(o.getX(), o.getY(), o.getZ() - 20) < 200.0 * 200.0) {
                near = true;
            }
            if (inside(player.getPos())) {
                welcome(world, player);
            }
        }
        Game hunt = Games.active(Kind.WITCH_HUNT);
        if (near && !visa.campFallen && hunt == null && built(world)) {
            populate(world);
        }
        if (hunt == null && !visa.campFallen && server.getTicks() % 40 == 3) {
            maybeHunt(world);
        }
    }

    private static List<Contestant> people(ServerWorld world) {
        return world.getEntitiesByClass(Contestant.class, area().expand(48.0), c -> c.isAlive() && !c.role.isEmpty() && !c.isBody());
    }

    /** Keeps the camp lived in: a leader, the guard, two quiet girls who deal the games, and everyone else. */
    private static void populate(ServerWorld world) {
        List<Contestant> people = people(world);
        if (people.size() >= RESIDENTS) {
            return;
        }
        int leaders = 0;
        int guards = 0;
        int dealers = 0;
        for (Contestant c : people) {
            switch (c.role) {
                case "leader" -> leaders++;
                case "guard" -> guards++;
                case "dealer" -> dealers++;
                default -> {
                }
            }
        }
        String role = leaders == 0 ? "leader" : guards < 5 ? "guard" : dealers < 2 ? "dealer" : "resident";
        Random random = world.random;
        BlockPos at = hangout(random);
        Contestant c = Contestant.spawn(world, at, random, role, new BlockPos(origin().getX(), origin().getY(), origin().getZ() - 22));
        if (c != null && role.equals("guard")) {
            c.equipStack(EquipmentSlot.MAINHAND, new ItemStack(DeadMansHand.GUN));
        }
    }

    /** The first time you walk in, the leader tells you how things work here, a line every few seconds. */
    private static void welcome(ServerWorld world, ServerPlayerEntity player) {
        Visa.Data data = Visa.of(player);
        UUID id = player.getUuid();
        if (!data.camp) {
            data.camp = true;
            data.campSince = world.getTime();
            GREETING.put(id, 0);
        }
        Integer step = GREETING.get(id);
        if (step == null) {
            return;
        }
        GREETING.put(id, step + 1);
        if (step % 3 != 0) {
            return;
        }
        int line = step / 3;
        if (line >= WELCOME.length) {
            GREETING.remove(id);
            return;
        }
        Contestant leader = leader(world);
        if (leader != null) {
            leader.look(player);
            player.sendMessage(Text.literal("<" + leader.name() + "> ").formatted(Formatting.WHITE).append(Text.literal(WELCOME[line])), false);
        } else {
            player.sendMessage(Text.literal(WELCOME[line]).formatted(Formatting.GRAY, Formatting.ITALIC), false);
        }
    }

    @org.jetbrains.annotations.Nullable
    static Contestant leader(ServerWorld world) {
        for (Contestant c : people(world)) {
            if (c.role.equals("leader")) {
                return c;
            }
        }
        return null;
    }

    /** Hand a card in at the wall. */
    static ActionResult useBlock(ServerPlayerEntity player, BlockPos pos) {
        if (!Borderland.is(player.world)) {
            return ActionResult.PASS;
        }
        BlockPos sign = wallSign();
        if (Math.abs(pos.getX() - sign.getX()) > 5 || pos.getY() < sign.getY() - 2 || pos.getY() > sign.getY() + 1
                || Math.abs(pos.getZ() - sign.getZ()) > 1) {
            return ActionResult.PASS;
        }
        ItemStack stack = player.getMainHandStack();
        if (!(stack.getItem() instanceof CardItem)) {
            player.sendMessage(Text.literal("The wall is covered in cards, each one crossed out. Hold a card and use the wall to hand it in.")
                    .formatted(Formatting.GRAY), false);
            return ActionResult.SUCCESS;
        }
        Text name = stack.getName();
        stack.decrement(1);
        Visa visa = Visa.get(player.server);
        visa.campCards++;
        visa.markDirty();
        Visa.Data data = Visa.of(player);
        data.handedIn++;
        player.sendMessage(Text.literal("You hand in the ").formatted(Formatting.GRAY).append(name).append(Text.literal(
                ". The camp holds " + visa.campCards + " cards now.").formatted(Formatting.GRAY)), false);
        if (player.world instanceof ServerWorld world) {
            Builder.write(world, sign, "THE WALL", "every card", "takes us home", visa.campCards + " handed in");
        }
        return ActionResult.SUCCESS;
    }

    /**
     * The ten of hearts comes for the camp on a night when you're staying there: once you've been here a while and
     * cleared a few games of your own.
     */
    private static void maybeHunt(ServerWorld world) {
        long timeOfDay = world.getTimeOfDay() % Visa.DAY;
        if (timeOfDay < 13500L || timeOfDay > 21000L) {
            return;
        }
        for (ServerPlayerEntity player : world.getPlayers()) {
            Visa.Data data = Visa.of(player);
            if (player.isSpectator() || !data.inside || !data.camp || !inside(player.getPos()) || Games.gameOf(player) != null
                    || data.cleared < 3 || data.cards.contains(Kind.WITCH_HUNT.card.code())
                    || world.getTime() - data.campSince < Visa.DAY / 2) {
                continue;
            }
            Games.open(world, player, Kind.WITCH_HUNT);
            return;
        }
    }
}
