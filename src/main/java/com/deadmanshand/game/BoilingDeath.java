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
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Seven of Spades, in a stadium: everyone starts on the pitch. Then something goes off under it, and the bowl begins to
 * fill with boiling water. Climb the stands and get out through a gate before it reaches you; most of the gates are
 * barred, and the stands are coming apart.
 */
final class BoilingDeath extends Game {
    private static final int GATES = 8;
    private static final int OPEN = 3;
    private static final int TIERS = 10;
    private static final double FIELD = 0.3;
    private static final int RISE = 70;
    private static final double A = 22.0;
    private static final double B = 19.0;

    /** Surface height (first air block) of every column in the bowl, keyed by x then z. */
    private final Map<Long, Integer> surface = new HashMap<>();
    private final List<long[]> columns = new ArrayList<>();
    private final boolean[] open = new boolean[GATES];
    private final Map<UUID, Integer> gateTried = new HashMap<>();
    private int cx;
    private int cz;
    private int oy;
    private int level;
    private int next;
    private int ticks;

    BoilingDeath(Kind kind, ServerWorld world, BlockPos origin) {
        super(kind, world, origin);
    }

    private double q(double x, double z) {
        double dx = (x - cx - 0.5) / A;
        double dz = (z - cz - 0.5) / B;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static int tier(double q) {
        return 1 + (int) Math.floor((q - FIELD) / (0.92 - FIELD) * TIERS);
    }

    private Vec3d gate(int k, double at) {
        double a = Math.PI * 2 * k / GATES + Math.PI / GATES;
        return new Vec3d(cx + 0.5 + Math.cos(a) * A * at, oy + TIERS + 1, cz + 0.5 + Math.sin(a) * B * at);
    }

    @Override
    protected void plan(Builder b) {
        int ox = origin.getX();
        oy = origin.getY();
        int oz = origin.getZ();
        cx = ox;
        cz = oz - 25;
        bounds = new Box(ox - 24, oy - 2, oz - 47, ox + 26, oy + 22, oz + 3);
        entry = new Box(ox - 3, oy, oz - 4, ox + 4, oy + 3, oz + 1);
        entryPos = new BlockPos(ox, oy, oz - 2);
        approach = new BlockPos(ox, oy, oz + 6);
        for (int k = 0; k < GATES; k++) {
            open[k] = false;
        }
        for (int n = 0; n < OPEN; ) {
            int k = random.nextInt(GATES);
            if (!open[k]) {
                open[k] = true;
                n++;
            }
        }
        BlockState air = Blocks.AIR.getDefaultState();
        BlockState[] seat = {Blocks.BLUE_CONCRETE.getDefaultState(), Blocks.WHITE_CONCRETE.getDefaultState(), Blocks.LIGHT_BLUE_CONCRETE.getDefaultState()};
        BlockState shell = Blocks.LIGHT_GRAY_CONCRETE.getDefaultState();
        surface.clear();
        columns.clear();
        b.fill(ox - 24, oy, oz - 47, ox + 25, oy + 20, oz + 2, air);
        b.fill(ox - 24, oy - 1, oz - 47, ox + 25, oy - 1, oz + 2, Blocks.SMOOTH_STONE.getDefaultState());
        for (int x = ox - 24; x <= ox + 25; x++) {
            for (int z = oz - 47; z <= oz + 2; z++) {
                double q = q(x, z);
                if (q < FIELD) {
                    boolean line = Math.abs(z - cz) == 0 || Math.abs(q - 0.15) < 0.025;
                    b.set(x, oy - 1, z, line ? Blocks.WHITE_CONCRETE.getDefaultState() : (Math.floorMod(x, 4) < 2
                            ? Blocks.GRASS_BLOCK.getDefaultState() : Blocks.MOSS_BLOCK.getDefaultState()));
                    keep(x, z, oy);
                } else if (q < 0.92) {
                    int t = tier(q);
                    b.fill(x, oy - 1, z, x, oy + t - 2, z, shell);
                    b.set(x, oy + t - 1, z, seat[Math.floorMod(t, 3)]);
                    keep(x, z, oy + t);
                } else if (q < 1.0) {
                    b.fill(x, oy - 1, z, x, oy + TIERS, z, shell);
                    keep(x, z, oy + TIERS + 1);
                } else if (q < 1.07) {
                    b.fill(x, oy - 1, z, x, oy + TIERS + 4, z, Blocks.WHITE_CONCRETE.getDefaultState());
                    if (Math.floorMod(x + z, 3) == 0) {
                        b.set(x, oy + TIERS + 4, z, Blocks.SEA_LANTERN.getDefaultState());
                    }
                }
            }
        }
        // The gates in the top of the outer wall: a few open, the rest barred
        for (int k = 0; k < GATES; k++) {
            Vec3d g = gate(k, 1.035);
            for (int x = (int) g.x - 2; x <= (int) g.x + 2; x++) {
                for (int z = (int) g.z - 2; z <= (int) g.z + 2; z++) {
                    double q = q(x, z);
                    double d = Math.hypot(x + 0.5 - g.x, z + 0.5 - g.z);
                    if (q >= 0.99 && q < 1.08 && d < 1.6) {
                        b.fill(x, oy + TIERS + 1, z, x, oy + TIERS + 2, z, open[k] ? air : Blocks.IRON_BARS.getDefaultState());
                    }
                }
            }
            Vec3d sign = gate(k, 0.97);
            b.set((int) Math.floor(sign.x), oy + TIERS + 3, (int) Math.floor(sign.z), Blocks.REDSTONE_LAMP.getDefaultState());
        }
        // Floodlight masts
        for (int k = 0; k < 4; k++) {
            Vec3d m = gate(k * 2, 1.02);
            b.fill((int) m.x, oy + TIERS + 4, (int) m.z, (int) m.x, oy + TIERS + 8, (int) m.z, Blocks.IRON_BARS.getDefaultState());
            b.set((int) m.x, oy + TIERS + 9, (int) m.z, Blocks.SEA_LANTERN.getDefaultState());
        }
        b.sign(new BlockPos(ox + 3, oy + 2, oz + 2), Direction.SOUTH, "GAME VENUE", "", "step inside", "to register");
        b.fill(ox + 3, oy, oz + 1, ox + 3, oy + 2, oz + 1, Blocks.IRON_BLOCK.getDefaultState());
    }

    private void keep(int x, int z, int top) {
        surface.put(key(x, z), top);
        columns.add(new long[]{x, z, top});
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    @Override
    protected String[] rules() {
        return new String[]{
                "Get out of the stadium.",
                "You have until the time runs out."};
    }

    @Override
    protected int timeLimit() {
        return 20 * (6 + (TIERS + 3) * RISE / 20);
    }

    @Override
    protected void arrange() {
        int i = 0;
        for (UUID id : playing()) {
            LivingEntity e = entity(id);
            if (e == null) {
                continue;
            }
            double a = i * 2.4;
            double r = 1.5 + (i % 4);
            e.teleport(cx + 0.5 + Math.cos(a) * r, oy, cz + 0.5 + Math.sin(a) * r);
            if (e instanceof Contestant c) {
                c.hold();
            }
            i++;
        }
    }

    @Override
    protected void begin() {
        level = oy;
        next = 5 * 20;
        world.playSound(null, cx, oy, cz, SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.BLOCKS, 6.0F, 0.5F);
        world.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, cx + 0.5, oy + 1, cz + 0.5, 3, 4.0, 1.0, 4.0, 0.0);
        world.spawnParticles(ParticleTypes.CLOUD, cx + 0.5, oy + 1, cz + 0.5, 200, 6.0, 1.0, 6.0, 0.15);
        for (Contestant c : strangers()) {
            c.feelings.scared(c, 0.4F);
            c.stopAll();
            if (random.nextFloat() < 0.4F) {
                c.say(Lines.pick(random, "What was that?!", "Water! Get up the stands!", "Run!", "It's boiling!"));
            }
        }
    }

    @Override
    protected void running() {
        ticks++;
        if (--next <= 0 && level <= oy + TIERS + 3) {
            next = RISE;
            rise();
        }
        if (ticks % 6 == 0) {
            steam();
        }
        if (ticks % 90 == 45) {
            collapse();
        }
        for (LivingEntity e : living()) {
            double q = q(e.getX() - 0.5, e.getZ() - 0.5);
            if (q > 1.0 && e.getY() >= oy + TIERS) {
                clear(e.getUuid());
                tell(e, "You're out.", Formatting.GREEN);
                e.teleport(approach.getX() + 0.5, approach.getY(), approach.getZ() + 0.5);
                continue;
            }
            if (q < 1.0 && e.getY() < level - 0.05) {
                e.addStatusEffect(new StatusEffectInstance(StatusEffects.NAUSEA, 60, 0, false, false));
                if (ticks % 10 == 0) {
                    e.damage(Laser.BOILING, 3.0F);
                    world.spawnParticles(ParticleTypes.CLOUD, e.getX(), e.getY() + 1.2, e.getZ(), 6, 0.3, 0.3, 0.3, 0.05);
                    if (!e.isAlive()) {
                        seats.put(e.getUuid(), Fate.DEAD);
                        died(e.getUuid());
                    }
                }
            }
        }
        for (Contestant c : strangers()) {
            flee(c);
        }
    }

    /** The water comes up another block. */
    private void rise() {
        BlockState water = Blocks.WATER.getDefaultState();
        for (long[] col : columns) {
            if (col[2] <= level) {
                BlockPos p = new BlockPos((int) col[0], level, (int) col[1]);
                if (world.getBlockState(p).isAir()) {
                    world.setBlockState(p, water);
                }
            }
        }
        level++;
        world.playSound(null, cx, level, cz, SoundEvents.BLOCK_WATER_AMBIENT, SoundCategory.BLOCKS, 4.0F, 0.6F);
        world.playSound(null, cx, level, cz, SoundEvents.BLOCK_FIRE_EXTINGUISH, SoundCategory.BLOCKS, 3.0F, 0.5F);
        if (level == oy + 4) {
            announce("Something's giving way under the stands.");
        }
    }

    private void steam() {
        for (int k = 0; k < 14; k++) {
            long[] col = columns.get(random.nextInt(columns.size()));
            if (col[2] < level) {
                world.spawnParticles(ParticleTypes.CLOUD, col[0] + 0.5, level + 0.1, col[1] + 0.5, 1, 0.2, 0.1, 0.2, 0.02);
                world.spawnParticles(ParticleTypes.SPLASH, col[0] + 0.5, level, col[1] + 0.5, 3, 0.3, 0.0, 0.3, 0.0);
            }
        }
    }

    /** A piece of the stands breaks away. */
    private void collapse() {
        long[] col = columns.get(random.nextInt(columns.size()));
        int x = (int) col[0];
        int z = (int) col[1];
        double q = q(x, z);
        if (q < FIELD + 0.08 || q > 0.9) {
            return;
        }
        world.playSound(null, x, col[2], z, SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.BLOCKS, 3.0F, 0.7F);
        world.spawnParticles(ParticleTypes.EXPLOSION, x + 0.5, col[2], z + 0.5, 4, 1.0, 0.5, 1.0, 0.0);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Integer top = surface.get(key(x + dx, z + dz));
                if (top != null && top > oy + 2 && q(x + dx, z + dz) < 0.92) {
                    for (int y = top - 2; y < top; y++) {
                        world.setBlockState(new BlockPos(x + dx, y, z + dz), Blocks.AIR.getDefaultState());
                    }
                }
            }
        }
    }

    private void flee(Contestant c) {
        UUID id = c.getUuid();
        int tried = gateTried.getOrDefault(id, -1);
        if (tried < 0) {
            // the clever ones read the barred gates from the pitch; everyone else picks the nearest
            int best = 0;
            double d = Double.MAX_VALUE;
            for (int k = 0; k < GATES; k++) {
                if (c.nature == Contestant.Nature.CLEVER && !open[k]) {
                    continue;
                }
                double dd = c.squaredDistanceTo(gate(k, 0.96));
                if (dd < d) {
                    d = dd;
                    best = k;
                }
            }
            tried = best;
            gateTried.put(id, tried);
        }
        Vec3d inside = gate(tried, 0.96);
        if (c.squaredDistanceTo(inside) < 2.5 * 2.5) {
            if (open[tried]) {
                c.go(gate(tried, 1.12), 1.3);
                return;
            }
            // barred: on to the next one round
            int step = Math.floorMod(id.hashCode(), 2) == 0 ? 1 : GATES - 1;
            gateTried.put(id, (tried + step) % GATES);
            if (random.nextFloat() < 0.3F) {
                c.say(Lines.pick(random, "It's locked!", "This one's barred!", "Keep going!"));
            }
            return;
        }
        if (c.nature == Contestant.Nature.TIMID && ticks < 60) {
            c.hold();
            return;
        }
        c.go(inside, 1.35);
    }

    @Override
    protected void timeUp() {
        announce("The stadium is full.");
        for (UUID id : playing()) {
            LivingEntity e = entity(id);
            seats.put(id, Fate.DEAD);
            if (e != null && e.isAlive()) {
                e.damage(Laser.BOILING, 1000.0F);
            }
            died(id);
        }
    }

    @Override
    protected void ended() {
        // drain it
        for (long[] col : columns) {
            for (int y = (int) col[2]; y <= level; y++) {
                BlockPos p = new BlockPos((int) col[0], y, (int) col[1]);
                if (!world.getBlockState(p).isAir()) {
                    world.setBlockState(p, Blocks.AIR.getDefaultState());
                }
            }
        }
    }

    @Override
    protected void more(ServerPlayerEntity player, Hud hud) {
        int below = (int) Math.floor(player.getY()) - level;
        hud.line(below < 0 ? "§4§lIN THE WATER" : "§fWATER §c" + below + " below you");
    }

    // --- what a stranger can say they'll do, and then does ---------------------------------------------------

    @Override
    protected boolean followable() {
        return true;
    }

    @Override
    public Map<String, String> options(Contestant who) {
        if (!isPlaying(who.getUuid())) {
            return Map.of();
        }
        return Map.of("GATE_N", "head for gate N (1 to " + GATES + ", round the top of the stadium) and try it: for example [GATE 3]");
    }

    @Override
    protected Map<String, String> optionWords(Contestant who) {
        return Map.of("GATE_N", "\\bgate\\s+(\\d)\\b");
    }

    @Override
    public void chose(Contestant who, String option) {
        if (option.startsWith("GATE_")) {
            try {
                int n = Integer.parseInt(option.substring(5)) - 1;
                if (n >= 0 && n < GATES) {
                    gateTried.put(who.getUuid(), n);
                }
            } catch (NumberFormatException ignored) {
            }
        }
    }

    @Override
    public String situation(Contestant who) {
        return "You're in a stadium that's flooding with boiling water after an explosion under the pitch. Climb the stands and get out "
                + "through one of the gates in the top of the outer wall; some are barred.";
    }
}
