package com.deadmanshand.game;

import com.deadmanshand.Builder;
import com.deadmanshand.Hud;
import com.deadmanshand.npc.Contestant;
import com.deadmanshand.npc.Lines;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.LivingEntity;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntPredicate;

/**
 * Queen of Diamonds, in a multi-storey car park. (Nobody who played it ever said what it was; this is what it is here.)
 * Every level has a row of eight cars and a board of clues. Exactly one car fits every clue: start it and the barrier to
 * the next ramp lifts. Start the wrong one and it's the last thing you do. Reach the roof before time runs out.
 */
final class Parking extends Game {
    private static final int LEVELS = 4;
    private static final int CARS = 8;
    private static final String[] COLOURS = {"white", "black", "red", "blue", "yellow", "silver"};

    private final int[][] colour = new int[LEVELS][CARS];
    private final int[][] plate = new int[LEVELS][CARS];
    private final int[] target = new int[LEVELS];
    private final List<List<String>> clues = new ArrayList<>();
    private final List<List<IntPredicate>> tests = new ArrayList<>();
    private final boolean[] solved = new boolean[LEVELS];
    private final Set<Integer> burnt = new HashSet<>();
    private final Map<UUID, Integer> thinkAt = new HashMap<>();
    private final Map<UUID, Integer> levelOf = new HashMap<>();
    private final Map<UUID, Integer> going = new HashMap<>();
    private int oy;
    private int ticks;

    Parking(Kind kind, ServerWorld world, BlockPos origin) {
        super(kind, world, origin);
    }

    private int floor(int level) {
        return oy + 5 * level;
    }

    private int carX(int k) {
        return origin.getX() - 16 + 4 * k;
    }

    private int rampX(int level) {
        return level % 2 == 0 ? origin.getX() + 16 : origin.getX() - 18;
    }

    // --- the puzzles ---------------------------------------------------------------------------------------------

    private static int digits(int n) {
        return n / 100 + n / 10 % 10 + n % 10;
    }

    private void puzzle(int level) {
        int[] col = colour[level];
        int[] pl = plate[level];
        for (int k = 0; k < CARS; k++) {
            col[k] = random.nextInt(COLOURS.length);
            pl[k] = 100 + random.nextInt(900);
        }
        int t = random.nextInt(CARS);
        target[level] = t;
        List<String> text = new ArrayList<>();
        List<IntPredicate> test = new ArrayList<>();
        // everything that's true of the right car
        Set<Integer> present = new HashSet<>();
        for (int c : col) {
            present.add(c);
        }
        for (int c : present) {
            if (c != col[t]) {
                text.add("It isn't " + COLOURS[c] + ".");
                test.add(i -> col[i] != c);
            }
        }
        text.add("It's " + COLOURS[col[t]] + ".");
        test.add(i -> col[i] == col[t]);
        boolean even = pl[t] % 2 == 0;
        text.add("Its plate number is " + (even ? "even." : "odd."));
        test.add(i -> pl[i] % 2 == (even ? 0 : 1));
        int s = digits(pl[t]);
        int more = s - 1 - random.nextInt(5);
        int less = s + 1 + random.nextInt(5);
        text.add("Its plate's digits add up to more than " + more + ".");
        test.add(i -> digits(pl[i]) > more);
        text.add("Its plate's digits add up to less than " + less + ".");
        test.add(i -> digits(pl[i]) < less);
        Set<Integer> beside = new HashSet<>();
        if (t > 0) {
            beside.add(col[t - 1]);
        }
        if (t < CARS - 1) {
            beside.add(col[t + 1]);
        }
        for (int c : beside) {
            text.add("It's parked next to a " + COLOURS[c] + " car.");
            test.add(i -> i > 0 && col[i - 1] == c || i < CARS - 1 && col[i + 1] == c);
        }
        for (int c : present) {
            if (!beside.contains(c)) {
                text.add("It isn't next to a " + COLOURS[c] + " car.");
                test.add(i -> !(i > 0 && col[i - 1] == c || i < CARS - 1 && col[i + 1] == c));
            }
        }
        if (t > 0) {
            int n = 1 + random.nextInt(t);
            text.add("It's in a space numbered higher than " + n + ".");
            test.add(i -> i + 1 > n);
        }
        if (t < CARS - 1) {
            int n = t + 2 + random.nextInt(CARS - 1 - t);
            text.add("It's in a space numbered lower than " + n + ".");
            test.add(i -> i + 1 < n);
        }
        if (t > 0 && t < CARS - 1) {
            text.add("It isn't at either end of the row.");
            test.add(i -> i > 0 && i < CARS - 1);
        }
        // as few of them as it takes to leave exactly one car
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < text.size(); i++) {
            order.add(i);
        }
        Collections.shuffle(order, new java.util.Random(random.nextLong()));
        Set<Integer> left = new HashSet<>();
        for (int k = 0; k < CARS; k++) {
            left.add(k);
        }
        List<String> chosenText = new ArrayList<>();
        List<IntPredicate> chosenTest = new ArrayList<>();
        for (int i : order) {
            if (left.size() == 1 || chosenText.size() >= 6) {
                break;
            }
            Set<Integer> after = new HashSet<>();
            for (int k : left) {
                if (test.get(i).test(k)) {
                    after.add(k);
                }
            }
            if (after.size() < left.size()) {
                left = after;
                chosenText.add(text.get(i));
                chosenTest.add(test.get(i));
            }
        }
        if (left.size() > 1) {
            chosenText.add("Its plate is " + pl[t] + ".");
            chosenTest.add(i -> pl[i] == pl[t]);
        }
        clues.add(chosenText);
        tests.add(chosenTest);
    }

    /** Up to four lines of fifteen letters. */
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
        while (lines.size() > 4) {
            lines.remove(lines.size() - 1);
        }
        return lines.toArray(new String[0]);
    }

    // --- the building ---------------------------------------------------------------------------------------------

    @Override
    protected void plan(Builder b) {
        int ox = origin.getX();
        oy = origin.getY();
        int oz = origin.getZ();
        clues.clear();
        tests.clear();
        for (int level = 0; level < LEVELS; level++) {
            puzzle(level);
            solved[level] = false;
        }
        bounds = new Box(ox - 24, oy - 2, oz - 47, ox + 26, floor(LEVELS) + 6, oz + 3);
        entry = new Box(ox - 3, oy, oz - 4, ox + 4, oy + 3, oz + 1);
        entryPos = new BlockPos(ox, oy, oz - 2);
        approach = new BlockPos(ox, oy, oz + 6);
        BlockState air = Blocks.AIR.getDefaultState();
        BlockState concrete = Blocks.LIGHT_GRAY_CONCRETE.getDefaultState();
        BlockState tarmac = Blocks.GRAY_CONCRETE.getDefaultState();
        BlockState[] paint = {Blocks.WHITE_CONCRETE.getDefaultState(), Blocks.BLACK_CONCRETE.getDefaultState(), Blocks.RED_CONCRETE.getDefaultState(),
                Blocks.BLUE_CONCRETE.getDefaultState(), Blocks.YELLOW_CONCRETE.getDefaultState(), Blocks.LIGHT_GRAY_CONCRETE.getDefaultState()};
        int x0 = ox - 20;
        int x1 = ox + 20;
        int z0 = oz - 43;
        int z1 = oz - 5;
        b.fill(ox - 24, oy, oz - 47, ox + 25, floor(LEVELS) + 4, oz + 2, air);
        b.fill(ox - 24, oy - 1, oz - 47, ox + 25, oy - 1, oz + 2, tarmac);
        for (int level = 0; level <= LEVELS; level++) {
            int y = floor(level);
            // the deck, and the walls round it with barred openings
            b.fill(x0, y - 1, z0, x1, y - 1, z1, level == 0 ? tarmac : concrete);
            if (level == LEVELS) {
                b.walls(x0, y, z0, x1, y + 1, z1, concrete);
                b.walls(x0, y + 1, z0, x1, y + 1, z1, Blocks.IRON_BARS.getDefaultState());
                b.fill(ox - 3, y - 1, oz - 26, ox + 3, y - 1, oz - 20, Blocks.YELLOW_CONCRETE.getDefaultState());
                b.sign(new BlockPos(ox, y, oz - 27), Direction.SOUTH, "", "ROOF", "", "");
                b.set(ox, y, oz - 28, concrete);
                continue;
            }
            b.walls(x0, y, z0, x1, y + 3, z1, concrete);
            b.walls(x0, y + 1, z0, x1, y + 2, z1, Blocks.IRON_BARS.getDefaultState());
            for (int x = x0 + 4; x < x1; x += 8) {
                for (int z = z0 + 12; z < z1 - 14; z += 12) {
                    b.fill(x, y, z, x, y + 3, z, concrete);
                }
            }
            for (int x = x0 + 6; x < x1; x += 10) {
                b.set(x, y + 3, oz - 25, Blocks.SEA_LANTERN.getDefaultState());
            }
            // the cars
            for (int k = 0; k < CARS; k++) {
                int cx = carX(k);
                b.fill(cx - 1, y - 1, z0 + 2, cx - 1, y - 1, z0 + 8, Blocks.WHITE_CONCRETE.getDefaultState());
                if (level >= LEVELS) {
                    continue;
                }
                BlockState body = paint[colour[level][k]];
                b.fill(cx, y, z0 + 3, cx + 2, y, z0 + 6, body);
                b.fill(cx, y + 1, z0 + 4, cx + 2, y + 1, z0 + 5, Blocks.GLASS.getDefaultState());
                b.fill(cx, y + 1, z0 + 3, cx + 2, y + 1, z0 + 3, body);
                b.set(cx, y, z0 + 6, Blocks.SEA_LANTERN.getDefaultState());
                b.set(cx + 2, y, z0 + 6, Blocks.SEA_LANTERN.getDefaultState());
                b.sign(new BlockPos(cx + 1, y, z0 + 7), Direction.SOUTH, "SPACE " + (k + 1), "", "plate " + plate[level][k], "(start: use it)");
            }
            // the ramp up, its walls, and its barrier
            int rx = rampX(level);
            for (int i = 0; i <= 4; i++) {
                b.fill(rx, y, oz - 10 - i, rx + 2, y + i, oz - 10 - i, concrete);
            }
            b.fill(rx - 1, y, oz - 15, rx - 1, y + 3, oz - 9, concrete);
            b.fill(rx + 3, y, oz - 15, rx + 3, y + 3, oz - 9, concrete);
            b.fill(rx, y, oz - 9, rx + 2, y + 2, oz - 9, Blocks.IRON_BARS.getDefaultState());
            b.set(rx + 1, y + 3, oz - 9, Blocks.REDSTONE_LAMP.getDefaultState());
            // the board of clues on the south wall
            List<String> c = clues.get(level);
            int[] slots = {-14, -11, -8, -5, 5, 8, 11};
            b.sign(new BlockPos(ox - 17, y + 1, z1 - 1), Direction.NORTH, "LEVEL " + (level + 1), "Start the car", "that fits every", "clue.");
            for (int i = 0; i < c.size() && i < slots.length; i++) {
                b.sign(new BlockPos(ox + slots[i], y + 1, z1 - 1), Direction.NORTH, wrap(c.get(i)));
            }
        }
        // the stairwell holes, once every deck is down
        for (int level = 0; level < LEVELS; level++) {
            int rx = rampX(level);
            b.fill(rx, floor(level) + 4, oz - 13, rx + 2, floor(level) + 4, oz - 10, air);
        }
        // in at the front
        b.fill(ox - 2, oy, z1, ox + 2, oy + 2, z1, air);
        b.sign(new BlockPos(ox + 3, oy + 2, oz + 2), Direction.SOUTH, "GAME VENUE", "", "step inside", "to register");
        b.fill(ox + 3, oy, oz + 1, ox + 3, oy + 2, oz + 1, Blocks.IRON_BLOCK.getDefaultState());
    }

    @Override
    protected String[] rules() {
        return new String[]{
                "Reach the roof before time runs out.",
                "On every level, exactly one car fits all the clues on the board. Start it and the barrier to the next ramp lifts.",
                "Start the wrong car and it's game over."};
    }

    @Override
    protected int timeLimit() {
        return 15 * 60 * 20;
    }

    @Override
    protected void arrange() {
        for (Contestant c : strangers()) {
            c.hold();
        }
    }

    @Override
    protected void begin() {
        int ox = origin.getX();
        int oz = origin.getZ();
        int i = 0;
        for (LivingEntity e : living()) {
            e.teleport(ox - 4.5 + (i % 9), oy, oz - 20.5 - (i / 9));
            i++;
        }
        for (int x = ox - 2; x <= ox + 2; x++) {
            for (int y = 0; y < 3; y++) {
                world.setBlockState(new BlockPos(x, oy + y, oz - 5), Blocks.IRON_BARS.getDefaultState());
            }
        }
        for (Contestant c : strangers()) {
            c.stopAll();
        }
    }

    @Override
    protected void ended() {
        int ox = origin.getX();
        int oz = origin.getZ();
        for (int x = ox - 2; x <= ox + 2; x++) {
            for (int y = 0; y < 3; y++) {
                world.setBlockState(new BlockPos(x, oy + y, oz - 5), Blocks.AIR.getDefaultState());
            }
        }
    }

    // --- play ---------------------------------------------------------------------------------------------

    private int levelAt(double y) {
        return (int) Math.floor((y - oy + 0.5) / 5.0);
    }

    @Override
    protected ActionResult use(ServerPlayerEntity player, BlockPos pos, BlockState state) {
        int level = levelAt(pos.getY());
        int dz = pos.getZ() - (origin.getZ() - 43);
        int dx = pos.getX() - carX(0);
        if (level < 0 || level >= LEVELS || pos.getY() > floor(level) + 1 || dz < 3 || dz > 7 || dx < 0 || dx % 4 > 2 || dx / 4 >= CARS) {
            return ActionResult.PASS;
        }
        start(player, level, dx / 4);
        return ActionResult.SUCCESS;
    }

    private void start(LivingEntity who, int level, int k) {
        if (solved[level] || !isPlaying(who.getUuid())) {
            return;
        }
        int cx = carX(k) + 1;
        int y = floor(level);
        int cz = origin.getZ() - 43 + 5;
        announce(nameOf(who.getUuid()) + " starts the car in space " + (k + 1) + " on level " + (level + 1) + ".");
        if (k == target[level]) {
            solved[level] = true;
            world.playSound(null, cx, y, cz, SoundEvents.ENTITY_MINECART_RIDING, SoundCategory.BLOCKS, 1.5F, 0.6F);
            world.playSound(null, cx, y, cz, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.BLOCKS, 1.0F, 1.0F);
            int rx = rampX(level);
            for (int x = rx; x <= rx + 2; x++) {
                for (int dy = 0; dy < 3; dy++) {
                    world.setBlockState(new BlockPos(x, y + dy, origin.getZ() - 9), Blocks.AIR.getDefaultState());
                }
            }
            world.setBlockState(new BlockPos(rx + 1, y + 3, origin.getZ() - 9), Blocks.GLOWSTONE.getDefaultState());
            announce("The engine turns over. The barrier to level " + (level + 2 > LEVELS ? "the roof" : String.valueOf(level + 2)) + " lifts.");
        } else {
            burnt.add(level * CARS + k);
            world.playSound(null, cx, y, cz, SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.BLOCKS, 2.0F, 0.9F);
            world.spawnParticles(ParticleTypes.EXPLOSION, cx + 0.5, y + 1, cz + 0.5, 3, 1.0, 0.5, 1.0, 0.0);
            world.spawnParticles(ParticleTypes.LARGE_SMOKE, cx + 0.5, y + 1.5, cz + 0.5, 40, 1.0, 0.8, 1.0, 0.02);
            fail(who.getUuid(), "Wrong car.");
            for (Contestant c : strangers()) {
                c.remember(nameOf(who.getUuid()) + " started the wrong car, the one in space " + (k + 1) + " on level " + (level + 1) + ", and died.");
            }
        }
    }

    @Override
    protected void running() {
        ticks++;
        for (LivingEntity e : living()) {
            int level = levelAt(e.getY());
            if (level >= LEVELS) {
                clear(e.getUuid());
                tell(e, "You're on the roof.", Formatting.GREEN);
                e.teleport(approach.getX() + 0.5, approach.getY(), approach.getZ() + 0.5);
                continue;
            }
            Integer was = levelOf.put(e.getUuid(), level);
            if (e instanceof Contestant c && (was == null || was != level)) {
                thinkAt.put(c.getUuid(), ticks + think(c));
                going.remove(c.getUuid());
            }
        }
        for (Contestant c : strangers()) {
            npc(c);
        }
    }

    private int think(Contestant c) {
        return switch (c.nature) {
            case CLEVER -> 20 * (15 + random.nextInt(15));
            case RECKLESS -> 20 * (10 + random.nextInt(20));
            case TIMID -> c.nerve() > 0.35F ? 20 * (40 + random.nextInt(40)) : Integer.MAX_VALUE / 2;
            default -> 20 * (25 + random.nextInt(35));
        };
    }

    /** Which car {@code c} thinks it is: the right one if they've worked it out, otherwise one that fits some of the clues. */
    private int guess(Contestant c, int level) {
        float sure = c.judgment() * (c.nature == Contestant.Nature.CLEVER ? 0.95F : 0.65F);
        if (random.nextFloat() < sure) {
            return target[level];
        }
        List<IntPredicate> test = tests.get(level);
        List<Integer> fits = new ArrayList<>();
        for (int k = 0; k < CARS; k++) {
            if (burnt.contains(level * CARS + k)) {
                continue;
            }
            int ok = 0;
            for (IntPredicate p : test) {
                if (p.test(k)) {
                    ok++;
                }
            }
            if (ok >= test.size() - 1) {
                fits.add(k);
            }
        }
        return fits.isEmpty() ? target[level] : fits.get(random.nextInt(fits.size()));
    }

    private void npc(Contestant c) {
        UUID id = c.getUuid();
        Integer level = levelOf.get(id);
        if (level == null || level >= LEVELS) {
            return;
        }
        int y = floor(level);
        int rx = rampX(level);
        int oz = origin.getZ();
        if (solved[level]) {
            // up the ramp
            Vec3d foot = new Vec3d(rx + 1.5, y, oz - 8.5);
            Vec3d top = new Vec3d(rx + 1.5, y + 5, oz - 16.5);
            c.go(c.getZ() < oz - 9.5 || c.squaredDistanceTo(foot) < 1.5 ? top : foot, 1.15);
            return;
        }
        Integer car = going.get(id);
        if (car != null) {
            Vec3d front = new Vec3d(carX(car) + 1.5, y, oz - 43 + 8.5);
            if (c.squaredDistanceTo(front) < 1.2 * 1.2) {
                c.look(null);
                c.swingHand(net.minecraft.util.Hand.MAIN_HAND);
                going.remove(id);
                start(c, level, car);
            } else {
                c.go(front, 1.0);
            }
            return;
        }
        if (ticks < thinkAt.getOrDefault(id, Integer.MAX_VALUE)) {
            // read the board
            if (c.arrived(1.0)) {
                c.go(new Vec3d(origin.getX() - 3 + Math.floorMod(id.hashCode(), 8) + 0.5, y, oz - 8.5), 0.8);
            }
            return;
        }
        decide(c, level, guess(c, level), true);
    }

    /** {@code c} goes to start the car in space {@code k + 1}, and says so. */
    private void decide(Contestant c, int level, int k, boolean say) {
        going.put(c.getUuid(), k);
        thinkAt.put(c.getUuid(), Integer.MAX_VALUE / 2);
        if (say) {
            c.speak("You've worked out it's the " + COLOURS[colour[level][k]] + " car in space " + (k + 1) + " on level " + (level + 1)
                            + ", and you're walking over to start it now.",
                    Lines.pick(random, "It's space " + (k + 1) + ". The " + COLOURS[colour[level][k]] + " one. I'm starting it.",
                            "Space " + (k + 1) + ". It has to be.", "I'm sure. Space " + (k + 1) + "."));
        }
    }

    // --- what a stranger can say they'll do, and then does ---------------------------------------------------

    @Override
    public Map<String, String> options(Contestant who) {
        Integer level = levelOf.get(who.getUuid());
        if (level == null || level >= LEVELS || solved[level]) {
            return Map.of();
        }
        Map<String, String> options = new LinkedHashMap<>();
        options.put("START_N", "walk to the car in space N (1 to 8) on this level and start it, for example [START 3]. If it's the wrong car, you die");
        options.put("WAIT", "keep studying the clues for now");
        return options;
    }

    @Override
    protected Map<String, String> optionWords(Contestant who) {
        return Map.of("START_N", "\\b(space|car|number|bay)\\s+(\\d)\\b");
    }

    @Override
    public void chose(Contestant who, String option) {
        Integer level = levelOf.get(who.getUuid());
        if (level == null || level >= LEVELS || solved[level]) {
            return;
        }
        if (option.equals("WAIT")) {
            thinkAt.put(who.getUuid(), Math.max(thinkAt.getOrDefault(who.getUuid(), ticks), ticks + 200));
            going.remove(who.getUuid());
        } else if (option.startsWith("START_")) {
            try {
                int k = Integer.parseInt(option.substring(6)) - 1;
                if (k >= 0 && k < CARS) {
                    decide(who, level, k, false);
                }
            } catch (NumberFormatException ignored) {
            }
        }
    }

    // --- show ---------------------------------------------------------------------------------------------

    @Override
    protected void more(ServerPlayerEntity player, Hud hud) {
        int level = Math.max(0, Math.min(LEVELS - 1, levelAt(player.getY())));
        hud.line("§fLEVEL §e" + (level + 1) + "/" + LEVELS + (solved[level] ? "  §aRAMP OPEN" : "  §cBARRIER DOWN"));
        hud.line("§7Read the board, then use the car that fits");
    }

    // --- what a stranger can say they'll do, and then does ---------------------------------------------------

    @Override
    protected boolean followable() {
        return true;
    }

    @Override
    public String situation(Contestant who) {
        Integer level = levelOf.get(who.getUuid());
        StringBuilder s = new StringBuilder("You're playing the queen of diamonds in a multi-storey car park: on each level exactly one of eight "
                + "cars fits every clue on the board, and starting it lifts the barrier to the next ramp; starting a wrong one kills you. "
                + "Reach the roof. ");
        if (level != null && level < LEVELS) {
            s.append("You're on level ").append(level + 1).append(". ");
            if (solved[level]) {
                s.append("This level's barrier is up: get up the ramp.");
            } else {
                s.append("The cars, by space: ");
                for (int k = 0; k < CARS; k++) {
                    s.append(k + 1).append(' ').append(COLOURS[colour[level][k]]).append(" (plate ").append(plate[level][k]).append(")")
                            .append(burnt.contains(level * CARS + k) ? " [wrong, burnt out]" : "").append(k < CARS - 1 ? ", " : ". ");
                }
                s.append("The clues: ").append(String.join(" ", clues.get(level)));
            }
        }
        return s.toString();
    }
}
