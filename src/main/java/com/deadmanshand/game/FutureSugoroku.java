package com.deadmanshand.game;

import com.deadmanshand.Builder;
import com.deadmanshand.Hud;
import com.deadmanshand.Laser;
import com.deadmanshand.npc.Contestant;
import com.deadmanshand.npc.Lines;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Future Sugoroku: twenty-five rooms in a square, five by five, and somewhere a way out. Everyone starts with fifteen
 * points on their wristband. Fifteen rounds. Each round the dice are rolled for every door: the number says how many may
 * go through it. Going through a door costs a point; staying put costs a point, and locks your wristband, so you can't
 * move next round unless two others in the room free you at the panel. Every room has its own value, given or taken when
 * you walk in, and going back into a room that's been used rolls fewer dice. On every wall, a future. Reach the exit and
 * you've cleared it. At zero points you're out. After fifteen rounds, the water comes.
 */
final class FutureSugoroku extends Game {
    private static final int N = 5;
    private static final int ROUNDS = 15;
    private static final int MOVE = 30 * 20;
    private static final int REST = 5 * 20;
    private static final String[] FUTURES = {
            "A FUTURE: you walk out into the sun, and someone is waiting for you.",
            "A FUTURE: you get out alone, and you never stop looking back.",
            "A FUTURE: you're the last one in this room.",
            "A FUTURE: someone you trust takes your place at the door.",
            "A FUTURE: you hold a door for a stranger, and it closes on you.",
            "A FUTURE: you wake up in a hospital bed, and none of this happened.",
            "A FUTURE: you find the way out, and choose not to take it.",
            "A FUTURE: the water is warm, and it doesn't hurt.",
            "A FUTURE: you remember every one of their names.",
            "A FUTURE: you were never meant to leave."};

    private final int[][] value = new int[N][N];
    private final boolean[][] used = new boolean[N][N];
    /** Doors: [r][c][0] east of (r, c), [r][c][1] south of (r, c). How many may go through this round (0 = shut). */
    private final int[][][] cap = new int[N][N][2];
    private final int[][][] passed = new int[N][N][2];
    private final Map<UUID, Integer> points = new HashMap<>();
    private final Map<UUID, Integer> roomAt = new HashMap<>();
    private final Map<UUID, Integer> startedIn = new HashMap<>();
    private final Map<UUID, Set<Integer>> been = new HashMap<>();
    private final Set<UUID> locked = new HashSet<>();
    private final Map<Integer, List<Object[]>> panel = new HashMap<>();
    private final Map<UUID, Integer> plan = new HashMap<>();
    private int round;
    private int timer;
    private boolean moving;
    private int gx0;
    private int gz0;
    private int oy;
    private int exitRoom;

    FutureSugoroku(Kind kind, ServerWorld world, BlockPos origin) {
        super(kind, world, origin);
    }

    private static String name(int room) {
        return "ABCDE".charAt(room / N) + String.valueOf(room % N + 1);
    }

    private int roomOf(LivingEntity e) {
        int c = (int) Math.floor((e.getX() - gx0) / 8.0);
        int r = (int) Math.floor((e.getZ() - gz0) / 8.0);
        if (c < 0 || c >= N || r < 0 || r >= N || e.getY() < oy - 1 || e.getY() > oy + 4) {
            return -1;
        }
        return r * N + c;
    }

    private Vec3d centre(int room) {
        return new Vec3d(gx0 + 8 * (room % N) + 4.5, oy, gz0 + 8 * (room / N) + 4.5);
    }

    /** The door between two neighbouring rooms: {r, c, dir} of the west/north one, or null. */
    private int[] door(int a, int b) {
        int ra = a / N;
        int ca = a % N;
        int rb = b / N;
        int cb = b % N;
        if (ra == rb && Math.abs(ca - cb) == 1) {
            return new int[]{ra, Math.min(ca, cb), 0};
        }
        if (ca == cb && Math.abs(ra - rb) == 1) {
            return new int[]{Math.min(ra, rb), ca, 1};
        }
        return null;
    }

    private BlockPos doorPos(int r, int c, int dir) {
        return dir == 0 ? new BlockPos(gx0 + 8 * (c + 1), oy, gz0 + 8 * r + 4) : new BlockPos(gx0 + 8 * c + 4, oy, gz0 + 8 * (r + 1));
    }

    private List<Integer> neighbours(int room) {
        List<Integer> list = new ArrayList<>();
        int r = room / N;
        int c = room % N;
        if (c > 0) {
            list.add(room - 1);
        }
        if (c < N - 1) {
            list.add(room + 1);
        }
        if (r > 0) {
            list.add(room - N);
        }
        if (r < N - 1) {
            list.add(room + N);
        }
        return list;
    }

    @Override
    protected void plan(Builder b) {
        int ox = origin.getX();
        oy = origin.getY();
        int oz = origin.getZ();
        gx0 = ox - 20;
        gz0 = oz - 45;
        exitRoom = 4;   // A5
        bounds = new Box(ox - 24, oy - 2, oz - 47, ox + 26, oy + 10, oz + 3);
        entry = new Box(ox - 3, oy, oz - 4, ox + 4, oy + 3, oz + 1);
        entryPos = new BlockPos(ox, oy, oz - 2);
        approach = new BlockPos(ox, oy, oz + 6);
        for (int r = 0; r < N; r++) {
            for (int c = 0; c < N; c++) {
                int[] values = {-2, -1, -1, 0, 0, 0, 1, 2};
                value[r][c] = values[random.nextInt(values.length)];
                used[r][c] = false;
            }
        }
        value[4][0] = 0;
        BlockState air = Blocks.AIR.getDefaultState();
        BlockState wall = Blocks.QUARTZ_BLOCK.getDefaultState();
        int x1 = gx0 + 8 * N;
        int z1 = gz0 + 8 * N;
        b.fill(ox - 24, oy, oz - 47, ox + 25, oy + 8, oz + 2, air);
        b.fill(ox - 24, oy - 1, oz - 47, ox + 25, oy - 1, oz + 2, Blocks.GRAY_CONCRETE.getDefaultState());
        b.fill(gx0, oy - 1, gz0, x1, oy + 4, z1, wall);
        int f = 0;
        for (int r = 0; r < N; r++) {
            for (int c = 0; c < N; c++) {
                int x = gx0 + 8 * c;
                int z = gz0 + 8 * r;
                b.fill(x + 1, oy, z + 1, x + 7, oy + 3, z + 7, air);
                b.fill(x + 1, oy - 1, z + 1, x + 7, oy - 1, z + 7, (r + c) % 2 == 0 ? Blocks.WHITE_CONCRETE.getDefaultState()
                        : Blocks.LIGHT_GRAY_CONCRETE.getDefaultState());
                b.set(x + 4, oy + 3, z + 4, Blocks.SEA_LANTERN.getDefaultState());
                // the panel in the middle, where two wristbands lift a lock
                b.set(x + 4, oy, z + 4, Blocks.REDSTONE_LAMP.getDefaultState());
                b.sign(new BlockPos(x + 4, oy + 1, z + 1), Direction.SOUTH, "ROOM " + name(r * N + c), "", "", "");
                b.sign(new BlockPos(x + 1, oy + 2, z + 4), Direction.EAST, wrap(FUTURES[f++ % FUTURES.length]));
                // the doors, shut until the dice say otherwise
                if (c < N - 1) {
                    b.fill(x + 8, oy, z + 4, x + 8, oy + 1, z + 4, Blocks.IRON_BARS.getDefaultState());
                }
                if (r < N - 1) {
                    b.fill(x + 4, oy, z + 8, x + 4, oy + 1, z + 8, Blocks.IRON_BARS.getDefaultState());
                }
            }
        }
        // the way out, in the far corner, and the way in
        b.fill(gx0 + 8 * 4 + 4, oy, gz0, gx0 + 8 * 4 + 4, oy + 1, gz0, air);
        b.fill(gx0 + 8 * 4 + 3, oy, gz0 - 2, gx0 + 8 * 4 + 5, oy + 2, gz0 - 1, air);
        b.fill(gx0 + 8 * 4 + 3, oy - 1, gz0 - 2, gx0 + 8 * 4 + 5, oy - 1, gz0 - 1, Blocks.GRASS_BLOCK.getDefaultState());
        b.sign(new BlockPos(ox + 3, oy + 2, oz + 2), Direction.SOUTH, "GAME VENUE", "", "step inside", "to register");
        b.fill(ox + 3, oy, oz + 1, ox + 3, oy + 2, oz + 1, Blocks.IRON_BLOCK.getDefaultState());
    }

    private static String[] wrap(String text) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (line.length() + word.length() + 1 > 15 && line.length() > 0) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) {
                line.append(' ');
            }
            line.append(word);
        }
        lines.add(line.toString());
        return lines.subList(0, Math.min(4, lines.size())).toArray(new String[0]);
    }

    @Override
    protected String[] rules() {
        return new String[]{
                "Future Sugoroku. Twenty-five rooms. Find the exit within fifteen rounds. You start with fifteen points.",
                "Each round, dice are rolled for every door: the number says how many may go through it. One door per round.",
                "Going through a door costs 1 point. Staying costs 1 point and locks you for the next round, unless two others free you at the panel.",
                "Every room gives or takes points when you enter. Rooms already used roll fewer dice.",
                "At zero points you're out."};
    }

    @Override
    protected int timeLimit() {
        return ROUNDS * (MOVE + REST) + 20 * 30;
    }

    @Override
    protected void arrange() {
        int start = 4 * N;
        int i = 0;
        for (UUID id : playing()) {
            points.put(id, 15);
            been.put(id, new HashSet<>(List.of(start)));
            roomAt.put(id, start);
            LivingEntity e = entity(id);
            if (e != null) {
                Vec3d c = centre(start);
                e.teleport(c.x - 2 + (i % 4) * 1.3, oy, c.z - 1.5 + (i / 4) * 1.3);
                if (e instanceof Contestant k) {
                    k.hold();
                }
            }
            i++;
        }
        used[4][0] = true;
    }

    @Override
    protected void begin() {
        nextRound();
    }

    // --- rounds ---------------------------------------------------------------------------------------------

    private void nextRound() {
        round++;
        moving = true;
        timer = MOVE;
        startedIn.clear();
        plan.clear();
        for (UUID id : playing()) {
            startedIn.put(id, roomAt.getOrDefault(id, -1));
        }
        // roll the dice for every door, fewer in rooms already used
        for (int r = 0; r < N; r++) {
            for (int c = 0; c < N; c++) {
                for (int dir = 0; dir < 2; dir++) {
                    passed[r][c][dir] = 0;
                    boolean exists = dir == 0 ? c < N - 1 : r < N - 1;
                    int roll = 0;
                    if (exists) {
                        boolean worn = used[r][c] || (dir == 0 ? used[r][c + 1] : used[r + 1][c]);
                        roll = random.nextFloat() < (worn ? 0.45F : 0.15F) ? 0 : 1 + random.nextInt(worn ? 3 : 6);
                    }
                    cap[r][c][dir] = roll;
                    if (exists) {
                        setDoor(r, c, dir, roll > 0);
                    }
                }
            }
        }
        world.playSound(null, origin.getX(), oy + 2, origin.getZ() - 25, SoundEvents.BLOCK_NOTE_BLOCK_BASEDRUM, SoundCategory.BLOCKS, 3.0F, 0.6F);
        announce("Round " + round + " of " + ROUNDS + ". The dice are rolled. Thirty seconds.");
        for (UUID id : playing()) {
            LivingEntity e = entity(id);
            if (e != null && locked.contains(id)) {
                tell(e, "Your wristband is locked: you can't move this round, unless two others free you at the panel.", Formatting.RED);
            }
        }
    }

    private void setDoor(int r, int c, int dir, boolean open) {
        BlockPos p = doorPos(r, c, dir);
        BlockState s = open ? Blocks.AIR.getDefaultState() : Blocks.IRON_BARS.getDefaultState();
        world.setBlockState(p, s);
        world.setBlockState(p.up(), s);
        // the number over the door, on both sides
        int n = cap[r][c][dir] - passed[r][c][dir];
        String[] text = {"", open ? "DOOR: " + n : "SHUT", "", ""};
        BlockPos above = p.up(2);
        for (Direction d : dir == 0 ? new Direction[]{Direction.WEST, Direction.EAST} : new Direction[]{Direction.NORTH, Direction.SOUTH}) {
            BlockPos at = above.offset(d);
            if (!world.getBlockState(at).isOf(Blocks.DARK_OAK_WALL_SIGN)) {
                world.setBlockState(at, Blocks.DARK_OAK_WALL_SIGN.getDefaultState().with(net.minecraft.block.WallSignBlock.FACING, d));
            }
            Builder.write(world, at, text);
        }
    }

    private void endMoves() {
        moving = false;
        timer = REST;
        for (int r = 0; r < N; r++) {
            for (int c = 0; c < N; c++) {
                for (int dir = 0; dir < 2; dir++) {
                    if (dir == 0 ? c < N - 1 : r < N - 1) {
                        setDoor(r, c, dir, false);
                    }
                }
            }
        }
        locked.clear();
        for (UUID id : playing()) {
            int now = roomAt.getOrDefault(id, -1);
            boolean moved = now != startedIn.getOrDefault(id, -1);
            change(id, -1, moved ? "the door" : "staying");
            if (!moved) {
                locked.add(id);     // staying put locks your wristband for the next round
            }
        }
        for (UUID id : playing()) {
            if (points.getOrDefault(id, 0) <= 0) {
                announce(nameOf(id) + " has no points left.");
                kill(id, Laser.SHOCK, "Your wristband reached zero.");
            }
        }
    }

    private void change(UUID id, int by, String why) {
        points.merge(id, by, Integer::sum);
        LivingEntity e = entity(id);
        if (e != null && by != 0) {
            tell(e, (by > 0 ? "+" : "") + by + " (" + why + "). Points: " + points.get(id) + ".", by > 0 ? Formatting.GREEN : Formatting.RED);
        }
    }

    @Override
    protected void running() {
        if (moving) {
            watchDoors();
            for (Contestant c : strangers()) {
                think(c);
            }
            if (--timer <= 0) {
                endMoves();
            }
        } else if (--timer <= 0) {
            if (round >= ROUNDS) {
                flood();
            } else {
                nextRound();
            }
        }
        // locked wristbands hold you still
        for (UUID id : locked) {
            LivingEntity e = entity(id);
            if (e != null && moving) {
                e.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 10, 6, false, false));
            }
        }
        // the panels: two wristbands within five seconds lift every lock in the room
        long now = world.getTime();
        for (Map.Entry<Integer, List<Object[]>> e : panel.entrySet()) {
            e.getValue().removeIf(o -> now - (Long) o[1] > 100);
        }
    }

    /** Who went through which door: count them, shut the door when it's full, and send back anyone it shouldn't let through. */
    private void watchDoors() {
        for (LivingEntity e : living()) {
            UUID id = e.getUuid();
            // out through the exit
            if (roomAt.getOrDefault(id, -1) == exitRoom && e.getZ() < gz0 - 0.2) {
                announce(nameOf(id) + " has found the way out.");
                clear(id);
                Game last = e instanceof ServerPlayerEntity player ? Games.open(world, player, Kind.ONE_LAST_GAME) : null;
                if (last != null && last.entryPos != null) {
                    // through the door, and the world goes white
                    tell(e, "You step through the door, and everything goes white.", Formatting.WHITE);
                    e.teleport(last.entryPos.getX() + 0.5, last.entryPos.getY(), last.entryPos.getZ() + 0.5);
                } else {
                    e.teleport(approach.getX() + 0.5, approach.getY(), approach.getZ() + 0.5);
                }
                continue;
            }
            int now = roomOf(e);
            int was = roomAt.getOrDefault(id, -1);
            if (now < 0 || now == was) {
                continue;
            }
            int start = startedIn.getOrDefault(id, -1);
            int[] d = door(was, now);
            boolean allowed = d != null && !locked.contains(id) && was == start && passed[d[0]][d[1]][d[2]] < cap[d[0]][d[1]][d[2]];
            if (!allowed) {
                Vec3d back = centre(was);
                e.teleport(back.x, oy, back.z);
                tell(e, locked.contains(id) ? "Your wristband is locked." : was != start ? "One door a round." : "That door's full.", Formatting.RED);
                continue;
            }
            passed[d[0]][d[1]][d[2]]++;
            roomAt.put(id, now);
            int left = cap[d[0]][d[1]][d[2]] - passed[d[0]][d[1]][d[2]];
            setDoor(d[0], d[1], d[2], left > 0);
            world.playSound(null, e.getX(), e.getY(), e.getZ(), SoundEvents.BLOCK_NOTE_BLOCK_CHIME, SoundCategory.PLAYERS, 0.8F, 1.2F);
            int r = now / N;
            int c = now % N;
            if (been.get(id).contains(now)) {
                tell(e, "Back in " + name(now) + ". You'll wait here.", Formatting.GRAY);
            } else {
                been.get(id).add(now);
                change(id, value[r][c], "room " + name(now));
            }
            used[r][c] = true;
            if (now == exitRoom) {
                tell(e, "This room has a door in its far wall.", Formatting.GOLD);
            }
        }
    }

    @Override
    protected ActionResult use(ServerPlayerEntity player, BlockPos pos, BlockState state) {
        int room = roomOf(player);
        if (room >= 0 && pos.equals(new BlockPos(centre(room)))) {
            scan(player, room);
            return ActionResult.SUCCESS;
        }
        return ActionResult.PASS;
    }

    private void scan(LivingEntity who, int room) {
        List<Object[]> list = panel.computeIfAbsent(room, k -> new ArrayList<>());
        for (Object[] o : list) {
            if (o[0].equals(who.getUuid())) {
                return;
            }
        }
        list.add(new Object[]{who.getUuid(), world.getTime()});
        world.playSound(null, who.getX(), who.getY(), who.getZ(), SoundEvents.BLOCK_NOTE_BLOCK_PLING, SoundCategory.PLAYERS, 0.8F, 1.5F);
        tell(who, "Your wristband is scanned. It takes two.", Formatting.YELLOW);
        if (list.size() >= 2) {
            list.clear();
            for (UUID id : new ArrayList<>(locked)) {
                LivingEntity e = entity(id);
                if (e != null && roomOf(e) == room) {
                    locked.remove(id);
                    tell(e, "Your lock is lifted.", Formatting.GREEN);
                }
            }
        }
    }

    private void flood() {
        announce("Fifteen rounds. The water comes.");
        world.playSound(null, origin.getX(), oy + 2, origin.getZ() - 25, SoundEvents.BLOCK_WATER_AMBIENT, SoundCategory.BLOCKS, 4.0F, 0.4F);
        for (UUID id : playing()) {
            LivingEntity e = entity(id);
            if (e != null) {
                world.spawnParticles(ParticleTypes.SPLASH, e.getX(), e.getY() + 1, e.getZ(), 60, 0.6, 0.6, 0.6, 0.1);
            }
            kill(id, Laser.FLOOD, "The water came.");
        }
        finish();
    }

    // --- the strangers ---------------------------------------------------------------------------------------------

    private void think(Contestant c) {
        UUID id = c.getUuid();
        int room = roomAt.getOrDefault(id, -1);
        if (room < 0) {
            return;
        }
        if (locked.contains(id)) {
            c.go(centre(room).add(1.2, 0, 0), 0.8);
            return;
        }
        // free someone locked in here, if you're kind (or clever about it)
        for (UUID other : locked) {
            LivingEntity e = entity(other);
            if (e != null && roomOf(e) == room && (c.nature == Contestant.Nature.KIND || c.nature == Contestant.Nature.CLEVER)) {
                if (c.squaredDistanceTo(centre(room)) < 2.2 * 2.2) {
                    scan(c, room);
                } else {
                    c.go(centre(room).add(0, 0, 1.2), 1.0);
                }
                return;
            }
        }
        if (room == exitRoom) {
            c.go(new Vec3d(gx0 + 8 * 4 + 4.5, oy, gz0 - 1.5), 1.2);
            return;
        }
        if (startedIn.getOrDefault(id, -1) != room) {
            c.go(centre(room), 0.8);
            return;
        }
        Integer target = plan.get(id);
        if (target == null) {
            target = choose(c, room);
            if (target == null) {
                return;
            }
            plan.put(id, target);
            if (random.nextFloat() < 0.3F) {
                c.say(Lines.pick(random, "I'm taking " + name(target) + ".", "This way. " + name(target) + ".", "Go, before it fills up!"));
            }
        }
        int[] d = door(room, target);
        if (d == null || passed[d[0]][d[1]][d[2]] >= cap[d[0]][d[1]][d[2]]) {
            plan.remove(id);
            return;
        }
        Vec3d through = centre(target);
        c.go(through, 1.25);
    }

    /** Which room to try: open doors only; away from where they started, and the clever ones away from where they've been. */
    private Integer choose(Contestant c, int room) {
        Integer best = null;
        double score = -1e9;
        Set<Integer> mine = been.get(c.getUuid());
        for (int n : neighbours(room)) {
            int[] d = door(room, n);
            int left = cap[d[0]][d[1]][d[2]] - passed[d[0]][d[1]][d[2]];
            if (left <= 0) {
                continue;
            }
            double s = random.nextDouble() * 2;
            int r = n / N;
            int col = n % N;
            s += (4 - r) * 0.6 + col * 0.6;      // the far corner feels like where an exit would be
            if (mine.contains(n)) {
                s -= c.judgment() > 0.5F ? 5 : 1;
            }
            if (s > score) {
                score = s;
                best = n;
            }
        }
        // cautious ones sometimes wait it out, and pay for it
        if (best != null && c.nature == Contestant.Nature.TIMID && random.nextFloat() < 0.3F) {
            return null;
        }
        return best;
    }

    // --- what a stranger can say they'll do, and then does ---------------------------------------------------

    @Override
    public Map<String, String> options(Contestant who) {
        int room = roomAt.getOrDefault(who.getUuid(), -1);
        if (!moving || room < 0 || locked.contains(who.getUuid()) || startedIn.getOrDefault(who.getUuid(), -2) != room) {
            return Map.of();
        }
        Map<String, String> options = new LinkedHashMap<>();
        for (int n : neighbours(room)) {
            int[] d = door(room, n);
            int left = cap[d[0]][d[1]][d[2]] - passed[d[0]][d[1]][d[2]];
            if (left > 0) {
                options.put("GO_" + name(n), "go through the door into room " + name(n) + " (" + left + " more may pass)");
            }
        }
        options.put("STAY", "stay in this room this round (it costs a point and locks your wristband)");
        return options;
    }

    @Override
    protected Map<String, String> optionWords(Contestant who) {
        Map<String, String> words = new LinkedHashMap<>();
        int room = roomAt.getOrDefault(who.getUuid(), -1);
        if (room >= 0) {
            for (int n : neighbours(room)) {
                words.put("GO_" + name(n), "\\b" + name(n).toLowerCase() + "\\b");
            }
        }
        return words;
    }

    @Override
    public void chose(Contestant who, String option) {
        UUID id = who.getUuid();
        if (option.equals("STAY")) {
            plan.remove(id);
            return;
        }
        int room = roomAt.getOrDefault(id, -1);
        for (int n : neighbours(room)) {
            if (option.equals("GO_" + name(n))) {
                plan.put(id, n);
            }
        }
    }

    // --- show ---------------------------------------------------------------------------------------------

    @Override
    protected void more(ServerPlayerEntity player, Hud hud) {
        UUID id = player.getUuid();
        int room = roomAt.getOrDefault(id, -1);
        hud.line("§fROUND §e" + round + "/" + ROUNDS + "  §fPOINTS §e" + points.getOrDefault(id, 0));
        hud.line("§fROOM §e" + (room < 0 ? "?" : name(room)) + (locked.contains(id) ? "  §cLOCKED" : "") + (moving ? "  §a" + Hud.clock(timer) : "  §7resting"));
    }

    @Override
    public String situation(Contestant who) {
        UUID id = who.getUuid();
        int room = roomAt.getOrDefault(id, -1);
        return "You're playing FUTURE SUGOROKU: twenty-five rooms, five by five, and an exit somewhere. Each round dice decide how many may go "
                + "through each door. Moving costs a point, staying costs a point and locks you; two others can unlock you at the panel. "
                + "Rooms give or take points. Zero points and you're dead; after fifteen rounds, a flood. Round " + round + ", you're in "
                + (room < 0 ? "a corridor" : "room " + name(room)) + " with " + points.getOrDefault(id, 0) + " points" + (locked.contains(id) ? ", locked." : ".");
    }
}
