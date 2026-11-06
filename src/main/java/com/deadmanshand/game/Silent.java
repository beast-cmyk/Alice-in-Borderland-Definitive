package com.deadmanshand.game;

import com.deadmanshand.Builder;
import com.deadmanshand.Hud;
import com.deadmanshand.Laser;
import com.deadmanshand.npc.Contestant;
import com.deadmanshand.npc.Lines;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LanternBlock;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * King of Hearts, in an abandoned building: a dark, narrow concrete labyrinth with mirrors at its turns, a few lights,
 * and corridors that loop back on themselves. Nobody explains the rules. Something in there roars, and nobody sees it.
 * Whoever it catches is dead; whoever finds the way out has cleared it.
 */
final class Silent extends Game {
    private static final int W = 15;
    private static final int H = 12;

    /** Open walls: east of (i, j), and south of (i, j). */
    private final boolean[][] east = new boolean[W][H];
    private final boolean[][] south = new boolean[W][H];
    private final Map<UUID, Set<Integer>> seen = new HashMap<>();
    private final Map<UUID, Integer> heading = new HashMap<>();
    private int mx0;
    private int mz0;
    private int oy;
    private int exitCol;
    private int startCol;

    /** The thing in the dark: where it is, where it's going, and how long until it moves again. */
    private Vec3d beast;
    private int beastGoal = -1;
    private int beastNext = -1;
    private boolean hunting;
    private int feeding;
    private int roar = 200;
    private int ticks;

    Silent(Kind kind, ServerWorld world, BlockPos origin) {
        super(kind, world, origin);
    }

    // --- the maze ---------------------------------------------------------------------------------------------

    private void carve() {
        for (boolean[] row : east) {
            Arrays.fill(row, false);
        }
        for (boolean[] row : south) {
            Arrays.fill(row, false);
        }
        boolean[][] visited = new boolean[W][H];
        Deque<int[]> stack = new ArrayDeque<>();
        stack.push(new int[]{startCol, H - 1});
        visited[startCol][H - 1] = true;
        int[][] steps = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!stack.isEmpty()) {
            int[] at = stack.peek();
            List<int[]> options = new ArrayList<>();
            for (int[] s : steps) {
                int i = at[0] + s[0];
                int j = at[1] + s[1];
                if (i >= 0 && i < W && j >= 0 && j < H && !visited[i][j]) {
                    options.add(new int[]{i, j});
                }
            }
            if (options.isEmpty()) {
                stack.pop();
                continue;
            }
            int[] next = options.get(random.nextInt(options.size()));
            open(at[0], at[1], next[0], next[1]);
            visited[next[0]][next[1]] = true;
            stack.push(next);
        }
        // Knock through some more walls, so corridors loop back on themselves
        for (int k = 0; k < W * H / 7; k++) {
            int i = random.nextInt(W - 1);
            int j = random.nextInt(H - 1);
            if (random.nextBoolean()) {
                east[i][j] = true;
            } else {
                south[i][j] = true;
            }
        }
    }

    private void open(int i, int j, int i2, int j2) {
        if (i2 == i + 1) {
            east[i][j] = true;
        } else if (i2 == i - 1) {
            east[i2][j] = true;
        } else if (j2 == j + 1) {
            south[i][j] = true;
        } else {
            south[i][j2] = true;
        }
    }

    private List<Integer> neighbours(int cell) {
        int i = cell % W;
        int j = cell / W;
        List<Integer> list = new ArrayList<>(4);
        if (i + 1 < W && east[i][j]) {
            list.add(cell + 1);
        }
        if (i > 0 && east[i - 1][j]) {
            list.add(cell - 1);
        }
        if (j + 1 < H && south[i][j]) {
            list.add(cell + W);
        }
        if (j > 0 && south[i][j - 1]) {
            list.add(cell - W);
        }
        return list;
    }

    private int cellOf(Vec3d pos) {
        int i = Math.max(0, Math.min(W - 1, (int) Math.floor((pos.x - mx0) / 3.0)));
        int j = Math.max(0, Math.min(H - 1, (int) Math.floor((pos.z - mz0) / 3.0)));
        return j * W + i;
    }

    private Vec3d centre(int cell) {
        return new Vec3d(mx0 + 3 * (cell % W) + 2.0, oy, mz0 + 3 * (cell / W) + 2.0);
    }

    /** Distances along the corridors from {@code from} to every cell. */
    private int[] distances(int from) {
        int[] d = new int[W * H];
        Arrays.fill(d, Integer.MAX_VALUE);
        d[from] = 0;
        Deque<Integer> queue = new ArrayDeque<>();
        queue.add(from);
        while (!queue.isEmpty()) {
            int c = queue.poll();
            for (int n : neighbours(c)) {
                if (d[n] == Integer.MAX_VALUE) {
                    d[n] = d[c] + 1;
                    queue.add(n);
                }
            }
        }
        return d;
    }

    /** The first step on the way from {@code from} to {@code to}. */
    private int stepToward(int from, int to) {
        if (from == to) {
            return to;
        }
        int[] d = distances(to);
        int best = from;
        for (int n : neighbours(from)) {
            if (d[n] < d[best]) {
                best = n;
            }
        }
        return best;
    }

    @Override
    protected void plan(Builder b) {
        int ox = origin.getX();
        oy = origin.getY();
        int oz = origin.getZ();
        int z0 = oz - 47;
        mx0 = ox - 22;
        mz0 = z0 + 4;
        startCol = (ox - mx0) / 3;
        exitCol = random.nextInt(W);
        carve();
        int mx1 = mx0 + 3 * W;
        int mz1 = mz0 + 3 * H;
        bounds = new Box(ox - 24, oy - 2, z0, ox + 26, oy + 10, oz + 3);
        entry = new Box(ox - 2, oy, oz - 5, ox + 4, oy + 3, oz + 1);
        entryPos = new BlockPos(ox, oy, oz - 3);
        approach = new BlockPos(ox, oy, oz + 6);
        BlockState air = Blocks.AIR.getDefaultState();
        BlockState wall = Blocks.GRAY_CONCRETE.getDefaultState();
        BlockState shell = Blocks.CRACKED_STONE_BRICKS.getDefaultState();
        // The building, solid to begin with
        b.fill(mx0 - 1, oy - 1, mz0 - 1, mx1 + 1, oy + 5, mz1 + 1, shell);
        b.fill(mx0, oy, mz0, mx1, oy + 3, mz1, wall);
        b.fill(mx0, oy - 1, mz0, mx1, oy - 1, mz1, Blocks.POLISHED_ANDESITE.getDefaultState());
        b.fill(mx0, oy + 4, mz0, mx1, oy + 4, mz1, Blocks.GRAY_CONCRETE.getDefaultState());
        // Then the corridors
        for (int j = 0; j < H; j++) {
            for (int i = 0; i < W; i++) {
                int x = mx0 + 3 * i + 1;
                int z = mz0 + 3 * j + 1;
                b.fill(x, oy, z, x + 1, oy + 3, z + 1, air);
                if (i + 1 < W && east[i][j]) {
                    b.fill(x + 2, oy, z, x + 2, oy + 3, z + 1, air);
                }
                if (j + 1 < H && south[i][j]) {
                    b.fill(x, oy, z + 2, x + 1, oy + 3, z + 2, air);
                }
                int r = Math.floorMod(i * 31 + j * 17 + ox, 23);
                if (r == 0) {
                    b.set(x, oy + 3, z, Blocks.SOUL_LANTERN.getDefaultState().with(LanternBlock.HANGING, true));
                } else if (r == 5 || r == 11) {
                    b.set(x + 1, oy - 1, z, Blocks.GRAVEL.getDefaultState());
                }
                // Mirrors: a pane in the wall at a turn shows you the next corridor, which looks just like this one
                if (r == 7 && i + 1 < W && !east[i][j]) {
                    b.fill(x + 2, oy, z, x + 2, oy + 2, z + 1, Blocks.GLASS.getDefaultState());
                } else if (r == 13 && j + 1 < H && !south[i][j]) {
                    b.fill(x, oy, z + 2, x + 1, oy + 2, z + 2, Blocks.GLASS.getDefaultState());
                }
            }
        }
        // The way in, from a little lobby, shut until the game begins
        int sx = mx0 + 3 * startCol + 1;
        b.fill(ox - 3, oy - 1, mz1 + 1, ox + 4, oy + 4, oz + 1, shell);
        b.fill(ox - 2, oy, mz1 + 1, ox + 3, oy + 3, oz, air);
        b.fill(ox - 2, oy - 1, mz1 + 1, ox + 3, oy - 1, oz, Blocks.POLISHED_ANDESITE.getDefaultState());
        b.fill(ox - 1, oy, oz + 1, ox + 1, oy + 2, oz + 1, air);
        b.fill(sx, oy, mz1, sx + 1, oy + 2, mz1 + 1, Blocks.IRON_BARS.getDefaultState());
        b.set(ox, oy + 3, oz - 3, Blocks.SOUL_LANTERN.getDefaultState().with(LanternBlock.HANGING, true));
        b.sign(new BlockPos(ox + 2, oy + 2, oz + 2), Direction.SOUTH, "GAME VENUE", "", "step inside", "to register");
        // The way out: a gap in the far wall, as dark as the rest
        int ex = mx0 + 3 * exitCol + 1;
        b.fill(ex, oy, mz0 - 1, ex + 1, oy + 2, mz0, air);
        b.fill(ex - 2, oy - 1, z0, ex + 3, oy - 1, mz0 - 2, Blocks.GRASS_BLOCK.getDefaultState());
        b.fill(ex - 2, oy, z0, ex + 3, oy + 3, mz0 - 2, air);
    }

    private int exitCell() {
        return exitCol;
    }

    private boolean escaped(LivingEntity e) {
        return e.getZ() < mz0 - 0.3 && e.getY() > oy - 1.5;
    }

    @Override
    protected String[] rules() {
        return new String[]{
                "The rules of this game will not be explained.",
                "Good luck."};
    }

    @Override
    protected int timeLimit() {
        return 20 * 60 * 20;
    }

    @Override
    protected void arrange() {
        int far = random.nextInt(W) + W * random.nextInt(H / 2);
        beast = centre(far);
        for (Contestant c : strangers()) {
            c.hold();
        }
    }

    @Override
    protected void begin() {
        int sx = mx0 + 3 * startCol + 1;
        int mz1 = mz0 + 3 * H;
        world.setBlockState(new BlockPos(sx, oy, mz1), Blocks.AIR.getDefaultState());
        world.setBlockState(new BlockPos(sx + 1, oy, mz1), Blocks.AIR.getDefaultState());
        for (int y = 0; y < 3; y++) {
            for (int dz = 0; dz < 2; dz++) {
                world.setBlockState(new BlockPos(sx, oy + y, mz1 + dz), Blocks.AIR.getDefaultState());
                world.setBlockState(new BlockPos(sx + 1, oy + y, mz1 + dz), Blocks.AIR.getDefaultState());
            }
        }
        int ox = origin.getX();
        int oz = origin.getZ();
        for (int x = ox - 1; x <= ox + 1; x++) {
            for (int y = 0; y < 3; y++) {
                world.setBlockState(new BlockPos(x, oy + y, oz + 1), Blocks.CRACKED_STONE_BRICKS.getDefaultState());
            }
        }
        world.playSound(null, ox, oy, oz, SoundEvents.BLOCK_IRON_DOOR_OPEN, SoundCategory.BLOCKS, 1.0F, 0.6F);
        for (Contestant c : strangers()) {
            c.stopAll();
        }
    }

    @Override
    protected void ended() {
        int ox = origin.getX();
        int oz = origin.getZ();
        for (int x = ox - 1; x <= ox + 1; x++) {
            for (int y = 0; y < 3; y++) {
                world.setBlockState(new BlockPos(x, oy + y, oz + 1), Blocks.AIR.getDefaultState());
            }
        }
        for (UUID id : seats.keySet()) {
            LivingEntity e = entity(id);
            if (e != null) {
                e.removeStatusEffect(StatusEffects.DARKNESS);
            }
        }
    }

    // --- the game ---------------------------------------------------------------------------------------------

    @Override
    protected void running() {
        ticks++;
        for (LivingEntity e : living()) {
            if (escaped(e)) {
                clear(e.getUuid());
                tell(e, "You found the way out.", Formatting.GREEN);
            }
        }
        moveBeast();
        for (Contestant c : strangers()) {
            wander(c);
        }
    }

    /** How loud {@code e} is being. */
    private static double noise(LivingEntity e) {
        double n = 1.0;
        if (e.isSprinting()) {
            n += 3.0;
        }
        if (e.isSneaking()) {
            n -= 0.7;
        }
        if (e instanceof Contestant c && c.getVelocity().horizontalLengthSquared() > 0.04) {
            n += 1.5;
        }
        return n;
    }

    private void moveBeast() {
        if (beast == null) {
            return;
        }
        int here = cellOf(beast);
        if (feeding > 0) {
            feeding--;
            return;
        }
        // Listen
        if (ticks % 10 == 0) {
            int[] d = distances(here);
            LivingEntity prey = null;
            double best = Double.MAX_VALUE;
            for (LivingEntity e : living()) {
                int cell = cellOf(e.getPos());
                double n = noise(e);
                double heard = d[cell] - n * 2.0;
                if (d[cell] <= 4 + n * 3 && heard < best) {
                    best = heard;
                    prey = e;
                }
            }
            boolean was = hunting;
            hunting = prey != null;
            if (hunting) {
                beastGoal = cellOf(prey.getPos());
                if (!was) {
                    world.playSound(null, beast.x, beast.y, beast.z, SoundEvents.ENTITY_RAVAGER_ROAR, SoundCategory.HOSTILE, 3.0F, 0.5F);
                }
            } else if (beastGoal < 0 || beastGoal == here) {
                beastGoal = random.nextInt(W * H);
            }
            beastNext = stepToward(here, beastGoal);
        }
        if (beastNext < 0) {
            return;
        }
        Vec3d to = here == beastGoal && hunting ? closestPrey() : centre(beastNext);
        if (to == null) {
            to = centre(beastNext);
        }
        Vec3d step = to.subtract(beast);
        double len = step.length();
        double speed = hunting ? 0.215 : 0.11;
        if (len > 0.01) {
            beast = len <= speed ? to : beast.add(step.multiply(speed / len));
        }
        if (len < 0.2 && beastNext != here) {
            beastNext = stepToward(cellOf(beast), beastGoal);
        }
        // What it sounds like
        if (ticks % (hunting ? 9 : 16) == 0) {
            world.playSound(null, beast.x, beast.y, beast.z, SoundEvents.ENTITY_RAVAGER_STEP, SoundCategory.HOSTILE, hunting ? 1.4F : 0.8F, 0.6F);
        }
        if (--roar <= 0) {
            roar = 300 + random.nextInt(300);
            world.playSound(null, beast.x, beast.y, beast.z, SoundEvents.ENTITY_RAVAGER_ROAR, SoundCategory.HOSTILE, 4.0F, 0.45F + random.nextFloat() * 0.1F);
            for (Contestant c : strangers()) {
                if (c.getPos().squaredDistanceTo(beast) < 24 * 24) {
                    c.feelings.scared(c, 0.15F);
                }
            }
            world.spawnParticles(ParticleTypes.ASH, beast.x, beast.y + 1.5, beast.z, 20, 0.6, 0.8, 0.6, 0.01);
        }
        for (LivingEntity e : living()) {
            double d = e.getPos().squaredDistanceTo(beast);
            if (d < 14 * 14 && ticks % 20 == 0) {
                if (e instanceof ServerPlayerEntity player) {
                    player.playSound(SoundEvents.ENTITY_WARDEN_HEARTBEAT, SoundCategory.HOSTILE, (float) (1.2 - Math.sqrt(d) / 14.0), 1.0F);
                }
                e.addStatusEffect(new StatusEffectInstance(StatusEffects.DARKNESS, 60, 0, false, false));
            }
            if (d < 1.3 * 1.3) {
                caught(e);
                break;
            }
        }
    }

    private Vec3d closestPrey() {
        Vec3d best = null;
        double d = Double.MAX_VALUE;
        for (LivingEntity e : living()) {
            double dd = e.getPos().squaredDistanceTo(beast);
            if (dd < d && cellOf(e.getPos()) == cellOf(beast)) {
                d = dd;
                best = e.getPos();
            }
        }
        return best;
    }

    private void caught(LivingEntity e) {
        UUID id = e.getUuid();
        seats.put(id, Fate.DEAD);
        world.playSound(null, e.getX(), e.getY(), e.getZ(), SoundEvents.ENTITY_WARDEN_ATTACK_IMPACT, SoundCategory.HOSTILE, 2.0F, 0.6F);
        world.playSound(null, e.getX(), e.getY(), e.getZ(), SoundEvents.ENTITY_RAVAGER_ROAR, SoundCategory.HOSTILE, 4.0F, 0.4F);
        world.spawnParticles(ParticleTypes.LARGE_SMOKE, e.getX(), e.getY() + 1.0, e.getZ(), 25, 0.4, 0.6, 0.4, 0.02);
        world.spawnParticles(ParticleTypes.CRIT, e.getX(), e.getY() + 1.0, e.getZ(), 30, 0.4, 0.6, 0.4, 0.3);
        if (e.isAlive()) {
            e.damage(Laser.BEAST, 1000.0F);
        }
        died(id);
        feeding = 140;
        hunting = false;
    }

    @Override
    protected void timeUp() {
        announce("Time.");
        for (UUID id : playing()) {
            LivingEntity e = entity(id);
            if (e != null) {
                caught(e);
            } else {
                fail(id, null);
            }
        }
    }

    // --- the strangers ---------------------------------------------------------------------------------------------

    private void wander(Contestant c) {
        UUID id = c.getUuid();
        int here = cellOf(c.getPos());
        Set<Integer> mine = seen.computeIfAbsent(id, k -> new HashSet<>());
        mine.add(here);
        double fear = beast == null ? 99 : Math.sqrt(c.getPos().squaredDistanceTo(beast));
        if (here == exitCell()) {
            c.go(new Vec3d(mx0 + 3 * exitCol + 2.0, oy, mz0 - 2.5), 1.2);
            return;
        }
        if (fear < 9 && (c.nature == Contestant.Nature.TIMID || c.feelings.fear > 0.7F) && random.nextFloat() < 0.6F) {
            // frozen with fear, which is quiet, at least
            c.hold();
            if (random.nextFloat() < 0.01F) {
                c.say(Lines.pick(random, "No, no, no...", "What is that?", "Please..."));
            }
            return;
        }
        Integer goal = heading.get(id);
        boolean arrived = goal == null || c.getPos().squaredDistanceTo(centre(goal)) < 0.6 * 0.6 || !neighbours(here).contains(goal) && goal != here;
        if (arrived) {
            List<Integer> next = neighbours(here);
            if (next.isEmpty()) {
                return;
            }
            Integer pick = null;
            if (fear < 12 && beast != null) {
                // away from it
                int b = cellOf(beast);
                int[] d = distances(b);
                int far = -1;
                for (int n : next) {
                    if (pick == null || d[n] > far) {
                        far = d[n];
                        pick = n;
                    }
                }
                if (random.nextFloat() < 0.15F) {
                    c.say(Lines.pick(random, "Run!", "It's coming!", "Did you hear that?"));
                }
            } else {
                List<Integer> fresh = new ArrayList<>();
                for (int n : next) {
                    if (!mine.contains(n)) {
                        fresh.add(n);
                    }
                }
                List<Integer> from = fresh.isEmpty() ? next : fresh;
                // the clever ones keep heading away from where they came in
                if (c.nature == Contestant.Nature.CLEVER) {
                    from.sort((a, b2) -> Integer.compare(a / W, b2 / W));
                    pick = from.get(0);
                } else {
                    pick = from.get(random.nextInt(from.size()));
                }
            }
            heading.put(id, pick);
            goal = pick;
        }
        c.go(centre(goal), fear < 12 ? 1.35 : 0.9);
    }

    // --- show ---------------------------------------------------------------------------------------------

    @Override
    protected void more(ServerPlayerEntity player, Hud hud) {
        double d = beast == null ? 99 : Math.sqrt(player.getPos().squaredDistanceTo(beast));
        hud.line(d < 8 ? "§4§l. . ." : d < 16 ? "§c. . ." : "§8. . .");
        int out = 0;
        for (Map.Entry<UUID, Fate> e : seats.entrySet()) {
            if (e.getValue() == Fate.CLEARED) {
                out++;
            }
        }
        hud.line("§7OUT §f" + out + "  §7LEFT §f" + playing().size());
    }

    // --- what a stranger can say they'll do, and then does ---------------------------------------------------

    @Override
    protected boolean followable() {
        return true;
    }

    @Override
    public String situation(Contestant who) {
        double d = beast == null ? 99 : Math.sqrt(who.getPos().squaredDistanceTo(beast));
        return "You're playing the king of hearts, in a pitch-dark concrete labyrinth in an abandoned building. Nobody explained the "
                + "rules. Something big is in here with you, roaring, and you've never seen it. Find the way out. "
                + (d < 10 ? "It's very close; you can hear it breathing." : d < 20 ? "You can hear it somewhere near." : "It's quiet, for now.");
    }
}
