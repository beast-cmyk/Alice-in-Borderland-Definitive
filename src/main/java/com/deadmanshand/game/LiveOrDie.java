package com.deadmanshand.game;

import com.deadmanshand.Builder;
import com.deadmanshand.Hud;
import com.deadmanshand.Laser;
import com.deadmanshand.npc.Contestant;
import com.deadmanshand.npc.Lines;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.LanternBlock;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3f;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Three of Clubs: a building of five rooms. Each room has two doors, one signed LIVE and one signed DIE, and a clue on
 * the wall that tells you which one leads on; the other door's passage is death. Each room has its own time limit,
 * shorter every time; when it runs out the room fills with gas, and then the gas catches fire. Get out of the
 * building to clear.
 */
final class LiveOrDie extends Game {
    private static final int ROOMS = 5;
    private static final int[] ROOM_SECONDS = {120, 100, 80, 60, 45};

    private enum Riddle {HONEST, LIARS, LAMPS, PRIME, SUNRISE, FLOOR}

    private final class Room {
        final int index;
        /** South (entrance) and north (doors) inside edges, z. */
        final int south;
        final int north;
        Riddle riddle;
        /** Which door's sign says LIVE: 0 left (west), 1 right (east). */
        int liveSign;
        /** The door that leads on: 0 left, 1 right. */
        int right;
        int lamps;
        boolean redFloor;
        int startedAt = -1;
        int expiredAt = -1;
        final boolean[] opened = new boolean[2];
        final boolean[] provenSafe = new boolean[2];
        final boolean[] provenDeadly = new boolean[2];
        String clue = "";

        Room(int index) {
            this.index = index;
            this.south = origin.getZ() - 8 - 14 * index;
            this.north = south - 8;
        }

        int doorX(int door) {
            return origin.getX() + (door == 0 ? -2 : 2);
        }

        boolean contains(Vec3d pos) {
            return pos.x >= origin.getX() - 4 && pos.x < origin.getX() + 5 && pos.z >= north && pos.z < south + 1
                    && pos.y >= origin.getY() - 0.5 && pos.y < origin.getY() + 4;
        }

        /** The passage behind {@code door}: two steps north, then two steps east. */
        boolean inPassage(Vec3d pos, int door) {
            int x = doorX(door);
            int bx = (int) Math.floor(pos.x);
            int bz = (int) Math.floor(pos.z);
            if (pos.y < origin.getY() - 0.5 || pos.y > origin.getY() + 2.5) {
                return false;
            }
            boolean leg = bx == x && (bz == north - 2 || bz == north - 3);
            boolean turn = bz == north - 3 && (bx == x + 1 || bx == x + 2);
            boolean end = bz == north - 4 && bx == x + 2;
            return leg || turn || end;
        }

        int secondsLeft() {
            if (startedAt < 0) {
                return ROOM_SECONDS[index];
            }
            return Math.max(0, ROOM_SECONDS[index] - (ticks - startedAt) / 20);
        }
    }

    private final List<Room> rooms = new ArrayList<>();
    private int ticks;
    /** Strangers: when they'll make up their minds, and what they believe. */
    private final Map<UUID, Integer> decideAt = new HashMap<>();
    private final Map<UUID, Integer> belief = new HashMap<>();
    private final Map<UUID, Integer> roomOf = new HashMap<>();
    /** Strangers who've said out loud which door they're taking, in their current room. */
    private final java.util.Set<UUID> announced = new java.util.HashSet<>();

    LiveOrDie(Kind kind, ServerWorld world, BlockPos origin) {
        super(kind, world, origin);
    }

    private int exitZ() {
        return rooms.get(ROOMS - 1).north - 5;
    }

    @Override
    protected void plan(Builder b) {
        int ox = origin.getX();
        int oy = origin.getY();
        int oz = origin.getZ();
        for (int i = 0; i < ROOMS; i++) {
            rooms.add(new Room(i));
        }
        int far = rooms.get(ROOMS - 1).north - 4;
        bounds = new Box(ox - 6, oy - 2, far - 12, ox + 7, oy + 7, oz + 1);
        entry = new Box(ox - 3, oy, oz - 6, ox + 4, oy + 3, oz - 1);
        entryPos = new BlockPos(ox, oy, oz - 3);
        approach = new BlockPos(ox, oy, oz + 16);

        BlockState wall = Blocks.WHITE_CONCRETE.getDefaultState();
        BlockState air = Blocks.AIR.getDefaultState();
        // The whole block of the building, solid, then the rooms carved out of it
        b.fill(ox - 6, oy - 2, far, ox + 6, oy - 2, oz, Blocks.STONE.getDefaultState());
        b.fill(ox - 5, oy - 1, far, ox + 5, oy + 4, oz, wall);
        b.fill(ox - 6, oy - 1, far - 11, ox + 6, oy + 8, far - 1, air);
        // Lobby, where you register
        b.fill(ox - 4, oy, oz - 6, ox + 4, oy + 3, oz - 1, air);
        b.fill(ox - 4, oy - 1, oz - 6, ox + 4, oy - 1, oz - 1, Blocks.POLISHED_ANDESITE.getDefaultState());
        b.fill(ox - 1, oy, oz, ox + 1, oy + 2, oz, air);
        b.fill(ox - 3, oy + 4, oz - 5, ox + 3, oy + 4, oz - 2, Blocks.SEA_LANTERN.getDefaultState());
        b.sign(new BlockPos(ox, oy + 2, oz - 6), Direction.SOUTH, "GAME VENUE", "", "step inside", "to register");
        // Outside: light by the door, and the yard beyond the last room
        b.set(ox - 2, oy + 2, oz + 1, Blocks.SEA_LANTERN.getDefaultState());
        b.set(ox + 2, oy + 2, oz + 1, Blocks.SEA_LANTERN.getDefaultState());
        b.fill(ox - 6, oy - 1, far - 11, ox + 6, oy - 1, far - 1, Blocks.GRAY_CONCRETE.getDefaultState());
        b.set(ox - 4, oy, far - 6, Blocks.LANTERN.getDefaultState());
        b.set(ox + 4, oy, far - 6, Blocks.LANTERN.getDefaultState());
        b.fill(ox, oy, far - 11, ox, oy + 2, far - 11, Blocks.STONE_BRICKS.getDefaultState());
        b.sign(new BlockPos(ox, oy + 1, far - 10), Direction.SOUTH, "", "EXIT", "", "");

        List<Riddle> pool = new ArrayList<>(List.of(Riddle.values()));
        for (Room room : rooms) {
            Riddle riddle = pool.remove(random.nextInt(pool.size()));
            room.riddle = riddle;
            room.liveSign = random.nextInt(2);
            boolean honest = switch (riddle) {
                case HONEST -> true;
                case LIARS -> false;
                case LAMPS -> {
                    room.lamps = 3 + random.nextInt(4);
                    yield room.lamps % 2 == 0;
                }
                case PRIME -> isPrime(2 * (room.index + 1) + 1);
                case FLOOR -> {
                    room.redFloor = random.nextBoolean();
                    yield !room.redFloor;
                }
                case SUNRISE -> true;
            };
            room.right = riddle == Riddle.SUNRISE ? 1 : honest ? room.liveSign : 1 - room.liveSign;
            room.clue = clue(room);
            build(b, room);
        }
    }

    private static boolean isPrime(int n) {
        for (int d = 2; d * d <= n; d++) {
            if (n % d == 0) {
                return false;
            }
        }
        return n > 1;
    }

    private static String clue(Room room) {
        return switch (room.riddle) {
            case HONEST -> "Every sign in this room tells the truth.";
            case LIARS -> "Every sign in this room is a lie.";
            case LAMPS -> "Count the lanterns. Even: the signs are honest. Odd: they lie.";
            case PRIME -> "Double this room's number and add one. Prime: the signs are honest. Not prime: they lie.";
            case SUNRISE -> "Forget the signs. The way on is the door nearer the sunrise.";
            case FLOOR -> "If the floor is the colour of blood, the signs lie. If not, they are honest.";
        };
    }

    private void build(Builder b, Room room) {
        int ox = origin.getX();
        int oy = origin.getY();
        BlockState air = Blocks.AIR.getDefaultState();
        b.fill(ox - 4, oy, room.north, ox + 4, oy + 3, room.south, air);
        b.fill(ox - 4, oy - 1, room.north, ox + 4, oy - 1, room.south,
                room.redFloor ? Blocks.RED_CONCRETE.getDefaultState() : Blocks.LIGHT_GRAY_CONCRETE.getDefaultState());
        for (int x = ox - 3; x <= ox + 3; x += 3) {
            b.set(x, oy + 4, room.south - 4, Blocks.SEA_LANTERN.getDefaultState());
        }
        // The two doors and their signs, and the passages behind them
        for (int door = 0; door < 2; door++) {
            int x = room.doorX(door);
            b.door(new BlockPos(x, oy, room.north - 1), Blocks.IRON_DOOR, Direction.NORTH);
            b.sign(new BlockPos(x, oy + 2, room.north), Direction.SOUTH, "", door == room.liveSign ? "LIVE" : "DIE", "", "");
            b.fill(x, oy, room.north - 3, x, oy + 1, room.north - 2, air);
            b.fill(x + 1, oy, room.north - 3, x + 2, oy + 1, room.north - 3, air);
            b.fill(x + 2, oy, room.north - 4, x + 2, oy + 1, room.north - 4, air);
            b.set(x + 1, oy + 2, room.north - 3, Blocks.GLOWSTONE.getDefaultState());
            if (door == room.right) {
                b.fill(x + 2, oy, room.north - 5, x + 2, oy + 1, room.north - 5, air);     // on into the next room (or outside)
            }
        }
        // The clue, on the wall you come in by
        List<String> lines = wrap("ROOM " + (room.index + 1) + ". " + room.clue, 15);
        int sign = 0;
        for (int y = oy + 3; y >= oy + 2; y--) {
            for (int x = ox - 3; x <= ox + 3; x += 3) {
                String[] text = new String[4];
                for (int row = 0; row < 4; row++) {
                    int i = sign * 4 + row;
                    text[row] = i < lines.size() ? lines.get(i) : "";
                }
                if (text[0].isEmpty()) {
                    continue;
                }
                b.sign(new BlockPos(x, y, room.south), Direction.NORTH, text);
                sign++;
            }
        }
        if (room.riddle == Riddle.LAMPS) {
            List<BlockPos> spots = new ArrayList<>();
            for (int x = ox - 3; x <= ox + 3; x++) {
                for (int z = room.north + 2; z <= room.south - 2; z++) {
                    spots.add(new BlockPos(x, oy + 3, z));
                }
            }
            for (int i = 0; i < room.lamps && !spots.isEmpty(); i++) {
                b.set(spots.remove(random.nextInt(spots.size())), Blocks.LANTERN.getDefaultState().with(LanternBlock.HANGING, true));
            }
        }
    }

    static List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (line.length() > 0 && line.length() + 1 + word.length() > width) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) {
                line.append(' ');
            }
            line.append(word);
        }
        if (line.length() > 0) {
            lines.add(line.toString());
        }
        return lines;
    }

    @Override
    protected String[] rules() {
        return new String[]{
                "Get out of the building alive.",
                "Every room has two doors. One leads on. Behind the other is death.",
                "Each room has its own time limit. When it runs out, the room fills with gas - and the gas burns.",
                "Read the wall. Choose a door. Touch it to open it.",
                "Overall time limit: eight minutes."};
    }

    @Override
    protected int timeLimit() {
        return 20 * 60 * 8;
    }

    @Override
    protected void arrange() {
        Room first = rooms.get(0);
        int i = 0;
        for (LivingEntity entity : living()) {
            double x = origin.getX() - 3 + (i % 4) * 2 + 0.5;
            double z = first.south - 1 - (i / 4) + 0.5;
            entity.teleport(x, origin.getY(), z);
            if (entity instanceof Contestant contestant) {
                contestant.hold();
            }
            i++;
        }
    }

    @Override
    protected void begin() {
        rooms.get(0).startedAt = ticks;
        for (Contestant contestant : strangers()) {
            contestant.stopAll();
        }
        Contestant first = strangers().isEmpty() ? null : strangers().get(0);
        if (first != null) {
            first.speak(roomMoment(rooms.get(0)) + " The game has just started.",
                    Lines.pick(random, "Two doors. Read the wall, quick.", "LIVE or DIE... it can't be that easy.", "Don't touch anything until we've read it."));
        }
    }

    private String roomMoment(Room room) {
        return "You're in room " + (room.index + 1) + " of 5. The wall says: '" + room.clue + "' The left door's sign says "
                + (room.liveSign == 0 ? "LIVE" : "DIE") + ", the right door's says " + (room.liveSign == 1 ? "LIVE" : "DIE")
                + ". " + room.secondsLeft() + " seconds before this room fills with gas.";
    }

    @Override
    protected void running() {
        ticks++;
        for (UUID id : playing()) {
            LivingEntity entity = entity(id);
            if (entity == null) {
                continue;
            }
            Vec3d pos = entity.getPos();
            if (pos.z < exitZ()) {
                clear(id);
                if (entity instanceof Contestant contestant) {
                    contestant.stopAll();
                    contestant.speak("You just made it out of the building alive.", Lines.pick(random, "We're out. We're actually out.", "Air. Real air."));
                }
                continue;
            }
            for (Room room : rooms) {
                if (room.contains(pos)) {
                    enter(id, entity, room);
                }
                for (int door = 0; door < 2; door++) {
                    if (room.inPassage(pos, door)) {
                        if (door == room.right) {
                            if (!room.provenSafe[door] && pos.z <= room.north - 3.5) {
                                room.provenSafe[door] = true;
                            }
                        } else {
                            room.provenDeadly[door] = true;
                            fail(id, "Wrong door.");
                            for (Contestant witness : strangers()) {
                                witness.remember(nameOf(id) + " chose the wrong door in room " + (room.index + 1) + " and the laser killed them.");
                            }
                            Contestant witness = nearestStranger(entity);
                            if (witness != null) {
                                witness.speak(nameOf(id) + " just opened the wrong door and was killed by a laser in front of you.",
                                        Lines.pick(random, "No. No no no.", "It killed them. Just like that.", "That one's death. The other door. It has to be."));
                            }
                        }
                    }
                }
            }
        }
        for (Room room : rooms) {
            tickGas(room);
        }
        strangersThink();
    }

    private void enter(UUID id, LivingEntity entity, Room room) {
        Integer was = roomOf.put(id, room.index);
        if (was != null && was == room.index) {
            return;
        }
        if (room.startedAt < 0) {
            room.startedAt = ticks;
            announce("Room " + (room.index + 1) + ". " + ROOM_SECONDS[room.index] + " seconds.");
        }
        if (entity instanceof ServerPlayerEntity player) {
            tell(player, "ROOM " + (room.index + 1) + ": " + room.clue, net.minecraft.util.Formatting.YELLOW);
        }
        if (entity instanceof Contestant contestant) {
            contestant.stopAll();
            announced.remove(id);
            decideAt.put(id, ticks + think(contestant));
            belief.put(id, believe(contestant, room));
            if (random.nextFloat() < 0.4F) {
                contestant.speak(roomMoment(room), Lines.pick(random, "Another one. Read it, read it.", "How many seconds have we got?", "Okay. Think."));
            }
        }
    }

    private int think(Contestant contestant) {
        int base = switch (contestant.nature) {
            case RECKLESS -> 40 + random.nextInt(60);
            case CLEVER -> 140 + random.nextInt(220);
            case TIMID -> 260 + random.nextInt(320);
            default -> 140 + random.nextInt(260);
        };
        // fear makes you hang back; grief and anger make you go
        float f = contestant.feelings.fear;
        float push = contestant.feelings.grief + contestant.feelings.anger;
        return (int) (base * (1.0F + f * 0.8F) / (1.0F + push * 0.6F));
    }

    /** Which door this stranger thinks is right: the clever ones work it out; the rest may just trust the LIVE sign. */
    private int believe(Contestant contestant, Room room) {
        if (random.nextFloat() < 0.3F + contestant.judgment() * 0.65F) {
            return room.right;
        }
        return random.nextFloat() < 0.6F ? room.liveSign : random.nextInt(2);
    }

    private void strangersThink() {
        for (Contestant contestant : strangers()) {
            Integer index = roomOf.get(contestant.getUuid());
            if (index == null) {
                continue;
            }
            Room room = rooms.get(index);
            int door = belief.getOrDefault(contestant.getUuid(), room.right);
            // What they've seen beats what they worked out
            for (int d = 0; d < 2; d++) {
                if (room.provenDeadly[d]) {
                    door = 1 - d;
                } else if (room.provenSafe[d]) {
                    door = d;
                }
            }
            boolean urgent = room.secondsLeft() < 12 || room.provenSafe[door];
            if (ticks < decideAt.getOrDefault(contestant.getUuid(), 0) && !urgent) {
                continue;
            }
            belief.put(contestant.getUuid(), door);
            if (announced.add(contestant.getUuid()) && room.contains(contestant.getPos())) {
                // say which door, so everyone knows (and so what they say is what they do)
                String sign = door == room.liveSign ? "LIVE" : "DIE";
                contestant.speak("You've made up your mind in room " + (room.index + 1) + ": the " + (door == 0 ? "left" : "right")
                                + " door, the one whose sign says " + sign + ". You're walking to it now.",
                        Lines.pick(random, (door == 0 ? "Left" : "Right") + " door. The " + sign + " one. I'm going.",
                                "I'm taking the " + (door == 0 ? "left" : "right") + " door.", "This way. The " + sign + " door."));
            }
            int x = room.doorX(door);
            BlockPos doorPos = new BlockPos(x, origin.getY(), room.north - 1);
            if (!room.opened[door] && contestant.squaredDistanceTo(Vec3d.ofBottomCenter(doorPos)) < 4.5) {
                open(room, door);
                contestant.speak("You're opening the " + (door == 0 ? "left" : "right") + " door in room " + (room.index + 1) + ".",
                        Lines.pick(random, "This one. I'm sure. I think I'm sure.", "Here goes.", "Follow me if you trust me."));
            }
            BlockPos target;
            if (room.inPassage(contestant.getPos(), room.right) && contestant.getZ() < room.north - 3.0) {
                int on = room.doorX(room.right) + 2;
                target = room.index == ROOMS - 1 ? new BlockPos(on, origin.getY(), exitZ() - 3) : new BlockPos(on, origin.getY(), room.north - 6);
            } else if (room.opened[door]) {
                target = new BlockPos(x + 2, origin.getY(), room.north - 4);    // through this door's own passage, nowhere else
            } else {
                target = new BlockPos(x, origin.getY(), room.north);
            }
            contestant.go(target, urgent ? 1.3 : 1.0);
        }
    }

    private void open(Room room, int door) {
        if (room.opened[door]) {
            return;
        }
        room.opened[door] = true;
        BlockPos pos = new BlockPos(room.doorX(door), origin.getY(), room.north - 1);
        BlockState state = world.getBlockState(pos);
        if (state.getBlock() instanceof DoorBlock doorBlock) {
            doorBlock.setOpen(null, world, state, pos, true);
        }
        world.playSound(null, pos, SoundEvents.BLOCK_IRON_DOOR_OPEN, SoundCategory.BLOCKS, 1.0F, 0.8F);
    }

    private void tickGas(Room room) {
        if (room.startedAt < 0) {
            return;
        }
        if (room.expiredAt < 0 && room.secondsLeft() <= 0) {
            room.expiredAt = ticks;
            announce("Room " + (room.index + 1) + ": time is up.");
            world.playSound(null, origin.getX(), origin.getY() + 2, room.south - 4, SoundEvents.BLOCK_FIRE_EXTINGUISH, SoundCategory.BLOCKS, 2.0F, 0.5F);
        }
        if (room.expiredAt < 0) {
            return;
        }
        int since = ticks - room.expiredAt;
        Box inside = new Box(origin.getX() - 4, origin.getY(), room.north, origin.getX() + 5, origin.getY() + 4, room.south + 1);
        double cx = origin.getX() + 0.5;
        double cz = (room.north + room.south) / 2.0 + 0.5;
        if (since < 80) {
            if (since % 4 == 0) {
                world.spawnParticles(new DustParticleEffect(new Vec3f(0.55F, 0.75F, 0.2F), 2.0F), cx, origin.getY() + 1.5, cz, 30, 3.5, 1.4, 3.5, 0.0);
            }
            for (LivingEntity entity : world.getEntitiesByClass(LivingEntity.class, inside, LivingEntity::isAlive)) {
                entity.addStatusEffect(new StatusEffectInstance(StatusEffects.NAUSEA, 120, 0));
                entity.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 40, 1));
            }
        } else if (since < 200) {
            if (since == 80) {
                world.playSound(null, cx, origin.getY() + 2, cz, SoundEvents.ITEM_FIRECHARGE_USE, SoundCategory.BLOCKS, 2.0F, 0.6F);
                for (int x = origin.getX() - 4; x <= origin.getX() + 4; x++) {
                    for (int z = room.north; z <= room.south; z++) {
                        if (random.nextFloat() < 0.35F && world.isAir(new BlockPos(x, origin.getY(), z))) {
                            world.setBlockState(new BlockPos(x, origin.getY(), z), Blocks.FIRE.getDefaultState());
                        }
                    }
                }
            }
            if (since % 3 == 0) {
                world.spawnParticles(ParticleTypes.FLAME, cx, origin.getY() + 1.5, cz, 25, 3.5, 1.4, 3.5, 0.02);
            }
            for (LivingEntity entity : world.getEntitiesByClass(LivingEntity.class, inside, LivingEntity::isAlive)) {
                entity.setOnFireFor(6);
                if (since > 140 && isPlaying(entity.getUuid())) {
                    fail(entity.getUuid(), "The room burned.");
                } else if (!isPlaying(entity.getUuid()) && since > 140) {
                    Laser.strike(entity, null);
                }
            }
        }
    }

    private Contestant nearestStranger(LivingEntity near) {
        Contestant best = null;
        double distance = 400.0;
        for (Contestant contestant : strangers()) {
            double d = contestant.squaredDistanceTo(near);
            if (d < distance && contestant != near) {
                best = contestant;
                distance = d;
            }
        }
        return best;
    }

    @Override
    protected ActionResult use(ServerPlayerEntity player, BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof DoorBlock) || !isPlaying(player.getUuid()) || phase != Phase.RUNNING) {
            return state.getBlock() instanceof DoorBlock ? ActionResult.SUCCESS : ActionResult.PASS;
        }
        BlockPos lower = state.get(DoorBlock.HALF) == DoubleBlockHalf.UPPER ? pos.down() : pos;
        for (Room room : rooms) {
            for (int door = 0; door < 2; door++) {
                if (lower.equals(new BlockPos(room.doorX(door), origin.getY(), room.north - 1))) {
                    open(room, door);
                    for (Contestant contestant : strangers()) {
                        if (roomOf.getOrDefault(contestant.getUuid(), -1) == room.index && contestant.trust(player.getUuid()) > 0.2F) {
                            belief.put(contestant.getUuid(), door);     // they'll follow someone they trust
                        }
                    }
                    return ActionResult.SUCCESS;
                }
            }
        }
        return ActionResult.SUCCESS;
    }

    @Override
    protected void more(ServerPlayerEntity player, Hud hud) {
        Integer index = roomOf.get(player.getUuid());
        if (index != null) {
            Room room = rooms.get(index);
            if (room.contains(player.getPos())) {
                hud.line("§fROOM " + (index + 1) + "  §e" + Hud.clock(room.secondsLeft() * 20));
            } else {
                hud.line("§7In the passage...");
            }
        }
    }

    @Override
    public String situation(Contestant who) {
        Integer index = roomOf.get(who.getUuid());
        String where = index == null ? "You haven't reached the first room yet." : roomMoment(rooms.get(index));
        Integer door = belief.get(who.getUuid());
        if (index != null && door != null && rooms.get(index).contains(who.getPos())) {
            Room room = rooms.get(index);
            String which = (door == 0 ? "left" : "right") + " door (the one signed " + (door == room.liveSign ? "LIVE" : "DIE") + ")";
            where += announced.contains(who.getUuid()) ? " You've decided on the " + which + " and you're going through it."
                    : " You're leaning towards the " + which + ", but you haven't committed yet.";
        }
        return "You're playing LIVE OR DIE, the three of clubs: a building of five rooms, each with two doors signed LIVE and DIE. "
                + "One door leads on; behind the other the laser kills you. A clue on the wall tells you which. When a room's time runs "
                + "out it fills with gas, then the gas burns. " + where;
    }

    @Override
    protected void died(UUID id) {
        roomOf.remove(id);
    }

    // --- what a stranger can say they'll do, and then does ---------------------------------------------------

    /** The room {@code who} is standing in and still has to choose a door in, or null. */
    private Room choosing(Contestant who) {
        Integer index = roomOf.get(who.getUuid());
        if (index == null) {
            return null;
        }
        Room room = rooms.get(index);
        return room.contains(who.getPos()) ? room : null;
    }

    @Override
    public Map<String, String> options(Contestant who) {
        Room room = choosing(who);
        if (room == null) {
            return Map.of();
        }
        Map<String, String> options = new java.util.LinkedHashMap<>();
        options.put("LEFT", "go to the left door, the one signed " + (room.liveSign == 0 ? "LIVE" : "DIE") + ", open it and go through");
        options.put("RIGHT", "go to the right door, the one signed " + (room.liveSign == 1 ? "LIVE" : "DIE") + ", open it and go through");
        options.put("WAIT", "stay where you are a little longer and think");
        return options;
    }

    @Override
    protected Map<String, String> optionWords(Contestant who) {
        Room room = choosing(who);
        if (room == null) {
            return Map.of();
        }
        String leftSign = room.liveSign == 0 ? "live" : "die";
        String rightSign = room.liveSign == 1 ? "live" : "die";
        String side = "\\b(the %1$s|%1$s door|%1$s one|%1$s side|go(ing)? %1$s|turn(ing)? %1$s|on the %1$s)\\b";
        String sign = "\\b(%1$s door|%1$s one|%1$s sign|says %1$s|marked %1$s|the %1$s\\b(?! (and|or)))";
        return Map.of("LEFT", "(" + String.format(side, "left") + ")|(" + String.format(sign, leftSign) + ")",
                "RIGHT", "(" + String.format(side, "right") + ")|(" + String.format(sign, rightSign) + ")");
    }

    @Override
    public void chose(Contestant who, String option) {
        Room room = choosing(who);
        if (room == null) {
            return;
        }
        UUID id = who.getUuid();
        switch (option) {
            case "LEFT", "RIGHT" -> {
                belief.put(id, option.equals("LEFT") ? 0 : 1);
                decideAt.put(id, ticks);
                announced.add(id);
                who.stopAll();
            }
            case "WAIT" -> decideAt.put(id, Math.max(decideAt.getOrDefault(id, ticks), ticks + 100));
            default -> {
            }
        }
    }
}
