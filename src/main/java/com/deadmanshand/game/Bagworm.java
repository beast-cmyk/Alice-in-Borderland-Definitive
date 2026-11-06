package com.deadmanshand.game;

import com.deadmanshand.Builder;
import com.deadmanshand.Hud;
import com.deadmanshand.npc.Contestant;
import com.deadmanshand.npc.Lines;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.StairsBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
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
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Jack of Clubs, in an old auditorium: ropes of every length hanging from the flies over the seats. Everyone starts on a
 * rope. Swing, jump from rope to rope, kick the others off. Touch the floor and the laser finds you. When the Jack of
 * Clubs is down, everyone still hanging clears it. Hold SNEAK to hug your rope.
 */
final class Bagworm extends Game {
    private static final int COLS = 5;
    private static final int ROWS = 3;
    private static final int GAP = 3;
    private static final int TOP = 21;

    private final List<int[]> ropes = new ArrayList<>();
    /** The strangers hanging: which rope, and how high. */
    private final Map<UUID, int[]> hang = new HashMap<>();
    private final Map<UUID, Float> grip = new HashMap<>();
    private final Map<UUID, Integer> moveIn = new HashMap<>();
    private final Map<UUID, Vec3d> start = new HashMap<>();
    private UUID jack;
    private int oy;
    private int ticks;

    Bagworm(Kind kind, ServerWorld world, BlockPos origin) {
        super(kind, world, origin);
    }

    @Override
    protected void plan(Builder b) {
        int ox = origin.getX();
        oy = origin.getY();
        int oz = origin.getZ();
        bounds = new Box(ox - 17, oy - 2, oz - 38, ox + 18, oy + TOP + 3, oz + 3);
        entry = new Box(ox - 3, oy, oz - 5, ox + 4, oy + 3, oz + 1);
        entryPos = new BlockPos(ox, oy, oz - 3);
        approach = new BlockPos(ox, oy, oz + 6);
        BlockState wall = Blocks.DARK_OAK_PLANKS.getDefaultState();
        BlockState air = Blocks.AIR.getDefaultState();
        b.fill(ox - 16, oy - 1, oz - 37, ox + 16, oy + TOP + 1, oz - 6, wall);
        b.fill(ox - 15, oy, oz - 36, ox + 15, oy + TOP, oz - 7, air);
        b.fill(ox - 15, oy - 1, oz - 36, ox + 15, oy - 1, oz - 7, Blocks.RED_CARPET.getDefaultState());
        b.fill(ox - 15, oy - 2, oz - 36, ox + 15, oy - 2, oz - 7, Blocks.OAK_PLANKS.getDefaultState());
        // Rows of seats, a stage at the front, red curtains
        for (int z = oz - 26; z <= oz - 9; z += 2) {
            for (int x = ox - 13; x <= ox + 13; x++) {
                if (Math.abs(x - ox) > 1) {
                    b.set(x, oy, z, Blocks.RED_WOOL.getDefaultState());
                    b.set(x, oy + 1, z + 1, Blocks.DARK_OAK_STAIRS.getDefaultState().with(StairsBlock.FACING, Direction.SOUTH));
                }
            }
        }
        b.fill(ox - 14, oy, oz - 36, ox + 14, oy + 1, oz - 31, Blocks.DARK_OAK_PLANKS.getDefaultState());
        b.fill(ox - 15, oy + 2, oz - 36, ox - 12, oy + 14, oz - 36, Blocks.RED_WOOL.getDefaultState());
        b.fill(ox + 12, oy + 2, oz - 36, ox + 15, oy + 14, oz - 36, Blocks.RED_WOOL.getDefaultState());
        // Lamps on the walls, and the ropes
        for (int z = oz - 33; z <= oz - 9; z += 6) {
            b.set(ox - 16, oy + TOP - 2, z, Blocks.SEA_LANTERN.getDefaultState());
            b.set(ox + 16, oy + TOP - 2, z, Blocks.SEA_LANTERN.getDefaultState());
            b.set(ox, oy + TOP, z, Blocks.SEA_LANTERN.getDefaultState());
        }
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                int x = ox + (c - COLS / 2) * GAP;
                int z = oz - 24 + (r - ROWS / 2) * GAP;
                int bottom = oy + 6 + Math.floorMod(r * 7 + c * 5, 7);
                for (int y = bottom + 1; y < oy + TOP; y++) {
                    b.set(x, y, z, Blocks.WEEPING_VINES_PLANT.getDefaultState());
                }
                b.set(x, bottom, z, Blocks.WEEPING_VINES.getDefaultState().with(net.minecraft.block.AbstractPlantStemBlock.AGE, 25));
                ropes.add(new int[]{x, z, bottom});
            }
        }
        // The lobby on the street
        b.fill(ox - 4, oy - 1, oz - 6, ox + 4, oy + 4, oz + 1, wall);
        b.fill(ox - 3, oy, oz - 5, ox + 3, oy + 3, oz, air);
        b.fill(ox - 1, oy, oz + 1, ox + 1, oy + 2, oz + 1, air);
        b.set(ox, oy + 3, oz - 3, Blocks.LANTERN.getDefaultState());
        b.sign(new BlockPos(ox, oy + 3, oz + 2), Direction.SOUTH, "GAME VENUE", "", "step inside", "to register");
    }

    @Override
    protected String[] rules() {
        return new String[]{
                "Everyone starts on a rope. Hold on (SNEAK) and don't touch the floor.",
                "You may swing and jump from rope to rope, and kick others off theirs.",
                "Touch the floor and it's game over.",
                "When the Jack of Clubs is eliminated, everyone still on a rope clears the game.",
                "Time limit: fifteen minutes."};
    }

    @Override
    protected int timeLimit() {
        return 20 * 60 * 15;
    }

    @Override
    protected void arrange() {
        Contestant j = citizen("Taiga", entryPos);
        if (j != null) {
            jack = j.getUuid();
        }
        List<int[]> free = new ArrayList<>(ropes);
        java.util.Collections.shuffle(free, new java.util.Random(random.nextLong()));
        // the Jack takes the longest drop
        int[] high = ropes.get(0);
        for (int[] rope : ropes) {
            if (rope[2] > high[2]) {
                high = rope;
            }
        }
        free.remove(high);
        for (UUID id : playing()) {
            LivingEntity entity = entity(id);
            if (entity == null) {
                continue;
            }
            int[] rope = id.equals(jack) ? high : free.isEmpty() ? ropes.get(random.nextInt(ropes.size())) : free.remove(0);
            int y = rope[2] + 3 + random.nextInt(Math.max(1, oy + TOP - rope[2] - 6));
            if (entity instanceof Contestant c) {
                c.hold();
                hang.put(id, new int[]{rope[0], rope[1], y});
                grip.put(id, id.equals(jack) ? 400.0F : 120.0F + c.judgment() * 80.0F);
                moveIn.put(id, 20 * (3 + random.nextInt(6)));
                c.setNoGravity(true);
            }
            entity.teleport(rope[0] + 0.5, y, rope[1] + 0.5 - 0.35);
            start.put(id, new Vec3d(rope[0] + 0.5, y, rope[1] + 0.5 - 0.35));
        }
    }

    /** While the rules are read, nobody slides off before it starts. */
    @Override
    protected void briefingTick(int t) {
        for (UUID id : players) {
            ServerPlayerEntity player = player(id);
            Vec3d at = start.get(id);
            if (player != null && at != null && player.getPos().squaredDistanceTo(at) > 0.36) {
                player.networkHandler.requestTeleport(at.x, at.y, at.z, player.getYaw(), player.getPitch());
            }
        }
    }

    @Override
    protected void begin() {
        titleAll(net.minecraft.text.Text.literal("HOLD ON").formatted(Formatting.RED),
                net.minecraft.text.Text.literal("SNEAK to hug the rope").formatted(Formatting.WHITE));
    }

    @Override
    protected void running() {
        ticks++;
        for (UUID id : playing()) {
            LivingEntity entity = entity(id);
            if (entity == null) {
                continue;
            }
            int[] h = hang.get(id);
            if (h != null) {
                hold((Contestant) entity, h);
            } else if (entity.isOnGround() && entity.getY() < oy + 2.5 && ticks > 30) {
                fail(id, "You touched the floor.");
                if (id.equals(jack)) {
                    jackDown();
                    return;
                }
            }
            if (h == null && entity instanceof Contestant c && c.isOnGround() && ticks > 30) {
                fail(id, null);
            }
        }
        if (jack != null && !isPlaying(jack) && phase == Phase.RUNNING) {
            jackDown();
        }
    }

    private void jackDown() {
        announce("The Jack of Clubs is down.");
        for (UUID id : playing()) {
            clear(id);
        }
        finish();
    }

    /** A stranger on a rope: holding on, moving between ropes, kicking whoever's close. */
    private void hold(Contestant c, int[] h) {
        UUID id = c.getUuid();
        float g = grip.merge(id, -0.05F, Float::sum);
        if (g <= 0) {
            drop(c, "can't hold on any more");
            return;
        }
        Vec3d at = new Vec3d(h[0] + 0.5, h[2], h[1] + 0.5 - 0.35);
        c.setNoGravity(true);
        c.setVelocity(Vec3d.ZERO);
        c.fallDistance = 0;
        if (c.getPos().squaredDistanceTo(at) > 0.04) {
            c.refreshPositionAndAngles(at.x, at.y, at.z, c.getYaw(), c.getPitch());
        }
        int wait = moveIn.merge(id, -1, Integer::sum);
        if (wait > 0) {
            return;
        }
        moveIn.put(id, 20 * (2 + random.nextInt(5)));
        // somebody close? kick
        LivingEntity near = null;
        double best = 2.2 * 2.2;
        for (LivingEntity other : living()) {
            if (other != c && other.squaredDistanceTo(c) < best) {
                best = other.squaredDistanceTo(c);
                near = other;
            }
        }
        String said = told.get(id);
        boolean spare = "SPARE".equals(said) && !id.equals(jack);
        boolean wanted = said != null && near != null && said.equals("KICK_" + tagOf(nameOf(near.getUuid())));
        if (near != null && !spare && (wanted || c.nerve() > 0.3F || id.equals(jack))) {
            c.look(near);
            c.swingHand(net.minecraft.util.Hand.MAIN_HAND);
            kick(c, near);
            return;
        }
        // otherwise swing to a rope next to someone
        List<int[]> next = new ArrayList<>();
        for (int[] rope : ropes) {
            int d = Math.abs(rope[0] - h[0]) + Math.abs(rope[1] - h[1]);
            if (d == GAP && h[2] > rope[2] + 1 && taken(rope) == null) {
                next.add(rope);
            }
        }
        if (!next.isEmpty() && random.nextFloat() < 0.5F) {
            int[] rope = next.get(random.nextInt(next.size()));
            world.spawnParticles(ParticleTypes.CLOUD, c.getX(), c.getY() + 1, c.getZ(), 4, 0.2, 0.2, 0.2, 0.01);
            hang.put(id, new int[]{rope[0], rope[1], Math.max(rope[2] + 1, h[2] - random.nextInt(2))});
            grip.merge(id, -6.0F, Float::sum);
        }
    }

    @Nullable
    private UUID taken(int[] rope) {
        for (Map.Entry<UUID, int[]> e : hang.entrySet()) {
            if (e.getValue()[0] == rope[0] && e.getValue()[1] == rope[1]) {
                return e.getKey();
            }
        }
        return null;
    }

    private void kick(LivingEntity by, LivingEntity target) {
        world.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.ENTITY_PLAYER_ATTACK_KNOCKBACK, SoundCategory.PLAYERS, 1.0F, 1.0F);
        if (target instanceof Contestant c && hang.containsKey(c.getUuid())) {
            float strength = c.getUuid().equals(jack) ? 0.25F : 0.6F;
            if (random.nextFloat() < strength + (by instanceof ServerPlayerEntity ? 0.25F : 0.0F)) {
                drop(c, "was kicked off");
            } else {
                grip.merge(c.getUuid(), -25.0F, Float::sum);
            }
        } else {
            Vec3d push = target.getPos().subtract(by.getPos()).multiply(1, 0, 1).normalize().multiply(0.9);
            target.addVelocity(push.x, 0.2, push.z);
            target.velocityModified = true;
        }
    }

    private void drop(Contestant c, String what) {
        hang.remove(c.getUuid());
        c.setNoGravity(false);
        c.stopAll();
        if (random.nextFloat() < 0.6F) {
            c.say(Lines.pick(random, "No—!", "I can't—", "Aaah!"));
        }
    }

    @Override
    protected boolean attacked(ServerPlayerEntity player, Entity target) {
        if (target instanceof LivingEntity living && player.squaredDistanceTo(target) < 3.5 * 3.5) {
            kick(player, living);
        }
        return false;
    }

    @Override
    protected void more(ServerPlayerEntity player, Hud hud) {
        hud.line("§fSTILL HANGING §e" + playing().size());
        hud.line(jack != null && isPlaying(jack) ? "§7The Jack of Clubs is on the highest rope" : "§aThe Jack is down");
        hud.line("§7SNEAK to hold on. Hit to kick.");
    }

    // --- what a stranger can say they'll do, and then does ---------------------------------------------------

    @Override
    public Map<String, String> options(Contestant who) {
        UUID id = who.getUuid();
        if (!isPlaying(id) || !hang.containsKey(id)) {
            return Map.of();
        }
        Map<String, String> options = new LinkedHashMap<>();
        for (LivingEntity other : living()) {
            if (other != who) {
                options.put("KICK_" + tagOf(nameOf(other.getUuid())), "kick " + nameOf(other.getUuid()) + " off their rope when they're in reach");
            }
        }
        options.put("SPARE", "don't kick anyone; just hang on");
        return options;
    }

    @Override
    protected Map<String, String> optionWords(Contestant who) {
        Map<String, String> words = new LinkedHashMap<>();
        for (LivingEntity other : living()) {
            words.put("KICK_" + tagOf(nameOf(other.getUuid())), "\\b(kick|knock) " + nameOf(other.getUuid()).toLowerCase() + "\\b");
        }
        words.put("SPARE", "\\b(won'?t kick|not kicking|leave (you|them) alone|just hang on)\\b");
        return words;
    }

    @Override
    public String situation(Contestant who) {
        return "You're playing BAGWORM, the jack of clubs, hanging from a rope over the seats of a dark auditorium. Everyone is on a rope; "
                + "you can swing to other ropes and kick people off. Touch the floor and you die. When the Jack of Clubs falls, everyone "
                + "left clears it." + (who.getUuid().equals(jack) ? " You ARE the Jack of Clubs, a citizen here." : "");
    }

    @Override
    protected void ended() {
        for (UUID id : seats.keySet()) {
            LivingEntity entity = entity(id);
            if (entity != null) {
                unfloat(entity);
            }
        }
        hang.clear();
    }
}
