package com.deadmanshand.game;

import com.deadmanshand.Builder;
import com.deadmanshand.Hud;
import com.deadmanshand.npc.Contestant;
import com.deadmanshand.npc.Lines;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.projectile.thrown.SnowballEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Queen of Clubs, in a water pump plant: seven narrow concrete ribs over deep, empty shafts, one rib to each pair. Throw
 * the glowing balls and knock the others off. Fall and you're out. The last pair, or the last one, standing clears it.
 */
final class Target extends Game {
    private static final int RIBS = 7;
    private static final int SPACING = 6;
    private static final int LENGTH = 30;
    private static final int DEPTH = 36;

    private final Map<UUID, Integer> pairOf = new HashMap<>();
    private final Map<UUID, Integer> ribOf = new HashMap<>();
    private final Map<UUID, Integer> throwIn = new HashMap<>();
    private final Map<UUID, Object[]> balls = new HashMap<>();
    private final Set<UUID> queens = new HashSet<>();
    private int oy;
    private int ticks;

    Target(Kind kind, ServerWorld world, BlockPos origin) {
        super(kind, world, origin);
    }

    private int ribX(int rib) {
        return origin.getX() + SPACING * (rib - RIBS / 2);
    }

    private int z0() {
        return origin.getZ() - 8 - LENGTH;
    }

    @Override
    protected void plan(Builder b) {
        int ox = origin.getX();
        oy = origin.getY();
        int oz = origin.getZ();
        int z0 = z0();
        int z1 = oz - 8;
        bounds = new Box(ox - 23, oy - DEPTH - 3, z0 - 4, ox + 24, oy + 14, oz + 2);
        entry = new Box(ox - 3, oy, oz - 5, ox + 4, oy + 3, oz + 1);
        entryPos = new BlockPos(ox, oy, oz - 3);
        approach = new BlockPos(ox, oy, oz + 6);
        BlockState concrete = Blocks.GRAY_CONCRETE.getDefaultState();
        BlockState wall = Blocks.LIGHT_GRAY_CONCRETE.getDefaultState();
        BlockState air = Blocks.AIR.getDefaultState();
        // The hall over the shafts
        b.fill(ox - 22, oy - DEPTH - 2, z0 - 3, ox + 22, oy + 12, z1 + 1, wall);
        b.fill(ox - 21, oy - DEPTH - 1, z0 - 2, ox + 21, oy + 11, z1, air);
        b.fill(ox - 21, oy - DEPTH - 2, z0 - 2, ox + 21, oy - DEPTH - 2, z1, Blocks.MUD.getDefaultState());
        // The ribs, nothing joining them: whoever falls goes all the way down
        for (int rib = 0; rib < RIBS; rib++) {
            int x = ribX(rib);
            b.fill(x, oy - 3, z0, x + 1, oy - 1, z1 - 2, concrete);
            b.fill(x, oy - DEPTH - 1, z0, x + 1, oy - 4, z0, Blocks.STONE_BRICKS.getDefaultState());
        }
        // Light only from the roof
        for (int x = ox - 20; x <= ox + 20; x += 4) {
            b.fill(x, oy + 12, z0, x + 1, oy + 12, z1 - 2, Blocks.GLASS.getDefaultState());
        }
        b.set(ox - 21, oy + 2, z1 - 2, Blocks.REDSTONE_LAMP.getDefaultState());
        // The way in from the street
        b.fill(ox - 4, oy - 1, z1 + 1, ox + 4, oy + 4, oz + 1, concrete);
        b.fill(ox - 3, oy, z1 + 1, ox + 3, oy + 3, oz, air);
        b.fill(ox - 1, oy, oz + 1, ox + 1, oy + 2, oz + 1, air);
        b.set(ox, oy + 3, oz - 3, Blocks.SEA_LANTERN.getDefaultState());
        b.sign(new BlockPos(ox, oy + 3, oz + 2), Direction.SOUTH, "GAME VENUE", "", "step inside", "to register");
    }

    @Override
    protected String[] rules() {
        return new String[]{
                "You play in pairs. Each pair stands on one concrete rib.",
                "Throw the glowing balls to knock the others off their ribs.",
                "Fall from your rib and it's game over.",
                "The last pair standing, or the last one, clears the game."};
    }

    @Override
    protected int timeLimit() {
        return 20 * 60 * 12;
    }

    @Override
    protected void arrange() {
        Contestant q = citizen("Rio", entryPos);
        Contestant partner = citizen("Mina", entryPos);
        if (q != null) {
            queens.add(q.getUuid());
        }
        if (partner != null) {
            queens.add(partner.getUuid());
        }
        List<UUID> others = new ArrayList<>();
        for (UUID id : playing()) {
            if (!queens.contains(id)) {
                others.add(id);
            }
        }
        java.util.Collections.shuffle(others, new java.util.Random(random.nextLong()));
        // The queen's pair takes the middle rib; everyone else in twos on the others
        List<Integer> ribs = new ArrayList<>();
        for (int r = 0; r < RIBS; r++) {
            if (r != RIBS / 2) {
                ribs.add(r);
            }
        }
        int pair = 1;
        for (int i = 0; i < others.size(); i++) {
            int slot = i / 2;
            if (slot >= ribs.size()) {
                fail(others.get(i), "There's no rib left for you.");
                continue;
            }
            pairOf.put(others.get(i), pair + slot);
            ribOf.put(others.get(i), ribs.get(slot));
        }
        for (UUID id : queens) {
            pairOf.put(id, 0);
            ribOf.put(id, RIBS / 2);
        }
        Map<Integer, Integer> filled = new HashMap<>();
        for (UUID id : playing()) {
            LivingEntity entity = entity(id);
            Integer rib = ribOf.get(id);
            if (entity == null || rib == null) {
                continue;
            }
            int k = filled.merge(rib, 1, Integer::sum);
            entity.teleport(ribX(rib) + (k == 1 ? 0.5 : 1.5), oy, z0() + LENGTH / 2.0 + (k == 1 ? -2 : 2));
            if (entity instanceof ServerPlayerEntity player) {
                player.getInventory().offerOrDrop(balls(16));
                UUID mate = mateOf(id);
                tell(player, "Your partner: " + (mate == null ? "nobody, you're alone" : nameOf(mate)) + ".", Formatting.YELLOW);
            } else if (entity instanceof Contestant c) {
                c.hold();
            }
        }
    }

    private static ItemStack balls(int count) {
        ItemStack stack = new ItemStack(Items.SNOWBALL, count);
        stack.setCustomName(Text.literal("Glowing Ball").formatted(Formatting.GREEN));
        return stack;
    }

    private UUID mateOf(UUID id) {
        Integer pair = pairOf.get(id);
        for (Map.Entry<UUID, Integer> e : pairOf.entrySet()) {
            if (!e.getKey().equals(id) && e.getValue().equals(pair)) {
                return e.getKey();
            }
        }
        return null;
    }

    @Override
    protected void begin() {
        for (Contestant c : strangers()) {
            throwIn.put(c.getUuid(), 20 * (2 + random.nextInt(4)));
        }
    }

    @Override
    protected void running() {
        ticks++;
        trackBalls();
        // fallen?
        for (UUID id : playing()) {
            LivingEntity entity = entity(id);
            if (entity != null && entity.getY() < oy - 3.5) {
                fail(id, "You fell from your rib.");
            }
        }
        // refills
        if (ticks % (20 * 30) == 0) {
            for (UUID id : players) {
                ServerPlayerEntity player = player(id);
                if (player != null && isPlaying(id)) {
                    player.getInventory().offerOrDrop(balls(4));
                }
            }
        }
        strangersThink();
        // one pair left?
        Set<Integer> pairs = new HashSet<>();
        for (UUID id : playing()) {
            pairs.add(pairOf.getOrDefault(id, -1));
        }
        if (pairs.size() <= 1) {
            if (!pairs.isEmpty()) {
                for (UUID id : playing()) {
                    clear(id);
                }
            }
            for (UUID id : queens) {
                if (seats.get(id) == Fate.CLEARED) {
                    continue;
                }
                fail(id, null);
            }
            finish();
        }
    }

    /** Glowing trails, and when a ball vanishes next to someone, it hit them: hard. */
    private void trackBalls() {
        List<UUID> gone = new ArrayList<>(balls.keySet());
        for (SnowballEntity ball : world.getEntitiesByClass(SnowballEntity.class, bounds, e -> true)) {
            UUID id = ball.getUuid();
            gone.remove(id);
            Entity owner = ball.getOwner();
            balls.put(id, new Object[]{ball.getPos(), ball.getVelocity(), owner == null ? null : owner.getUuid()});
            if (ticks % 2 == 0) {
                world.spawnParticles(ParticleTypes.GLOW, ball.getX(), ball.getY(), ball.getZ(), 1, 0.0, 0.0, 0.0, 0.0);
            }
        }
        for (UUID id : gone) {
            Object[] last = balls.remove(id);
            Vec3d at = ((Vec3d) last[0]).add(((Vec3d) last[1]).multiply(0.5));
            Vec3d dir = (Vec3d) last[1];
            UUID owner = (UUID) last[2];
            LivingEntity hit = null;
            double best = 1.8 * 1.8;
            for (LivingEntity target : living()) {
                if (target.getUuid().equals(owner)) {
                    continue;
                }
                double d = target.getPos().add(0, 1, 0).squaredDistanceTo(at);
                if (d < best) {
                    best = d;
                    hit = target;
                }
            }
            if (hit != null) {
                Vec3d push = new Vec3d(dir.x, 0, dir.z).normalize().multiply(1.1);
                hit.addVelocity(push.x, 0.35, push.z);
                hit.velocityModified = true;
                world.playSound(null, hit.getX(), hit.getY(), hit.getZ(), SoundEvents.ENTITY_SLIME_SQUISH, SoundCategory.PLAYERS, 1.2F, 0.8F);
                world.spawnParticles(ParticleTypes.GLOW, hit.getX(), hit.getY() + 1.0, hit.getZ(), 12, 0.3, 0.3, 0.3, 0.05);
            }
        }
    }

    private void strangersThink() {
        for (Contestant c : strangers()) {
            UUID id = c.getUuid();
            Integer rib = ribOf.get(id);
            if (rib == null) {
                continue;
            }
            // keep to the middle of the rib, stepping now and then to dodge
            if (c.arrived(0.8) && random.nextFloat() < 0.05F) {
                c.go(new Vec3d(ribX(rib) + 0.5 + random.nextInt(2), oy, z0() + 3 + random.nextInt(LENGTH - 6) + 0.5), 0.8);
            }
            int wait = throwIn.merge(id, -1, Integer::sum);
            if (wait > 0) {
                continue;
            }
            throwIn.put(id, 20 + random.nextInt(30));
            LivingEntity target = null;
            String said = told.get(id);
            if (said != null && said.startsWith("AIM_AT_")) {
                for (LivingEntity other : living()) {
                    if (said.equals("AIM_AT_" + tagOf(nameOf(other.getUuid()))) && c.squaredDistanceTo(other) < 24 * 24) {
                        target = other;
                    }
                }
            }
            double best = target != null ? 0.0 : 18.0 * 18.0;
            for (LivingEntity other : living()) {
                if (pairOf.getOrDefault(other.getUuid(), -1).equals(pairOf.get(id))) {
                    continue;
                }
                double d = c.squaredDistanceTo(other);
                if (d < best) {
                    best = d;
                    target = other;
                }
            }
            if (target == null) {
                continue;
            }
            c.look(target);
            SnowballEntity ball = new SnowballEntity(world, c);
            Vec3d to = target.getPos().add(0, 1.0, 0).subtract(c.getEyePos());
            ball.setVelocity(to.x, to.y + to.horizontalLength() * 0.08, to.z, 1.5F, 6.0F * (1.2F - c.judgment()));
            world.spawnEntity(ball);
            world.playSound(null, c.getX(), c.getY(), c.getZ(), SoundEvents.ENTITY_SNOWBALL_THROW, SoundCategory.PLAYERS, 0.8F, 0.6F);
            if (random.nextFloat() < 0.05F) {
                c.speak("You're on a narrow concrete rib over a deep shaft, throwing glowing balls to knock the others off.",
                        Lines.pick(random, "Get down! Low!", "Aim for their legs!", "Don't look down. Don't look down."));
            }
        }
    }

    @Override
    protected void more(ServerPlayerEntity player, Hud hud) {
        Set<Integer> pairs = new HashSet<>();
        for (UUID id : playing()) {
            pairs.add(pairOf.getOrDefault(id, -1));
        }
        UUID mate = mateOf(player.getUuid());
        hud.line("§fPAIRS LEFT §e" + pairs.size());
        hud.line("§fPARTNER " + (mate == null ? "§7none" : (isPlaying(mate) ? "§a" : "§8") + nameOf(mate)));
    }

    // --- what a stranger can say they'll do, and then does ---------------------------------------------------

    @Override
    public Map<String, String> options(Contestant who) {
        UUID id = who.getUuid();
        if (!isPlaying(id) || !ribOf.containsKey(id)) {
            return Map.of();
        }
        Map<String, String> options = new LinkedHashMap<>();
        for (LivingEntity other : living()) {
            if (!pairOf.getOrDefault(other.getUuid(), -1).equals(pairOf.get(id))) {
                options.put("AIM_AT_" + tagOf(nameOf(other.getUuid())), "throw your balls at " + nameOf(other.getUuid()) + " from now on");
            }
        }
        options.put("HOLD_FIRE", "stop throwing for a while");
        return options;
    }

    @Override
    protected Map<String, String> optionWords(Contestant who) {
        Map<String, String> words = new LinkedHashMap<>();
        for (LivingEntity other : living()) {
            words.put("AIM_AT_" + tagOf(nameOf(other.getUuid())), "\\b(aim at|aiming at|go for|hit|knock) " + nameOf(other.getUuid()).toLowerCase() + "\\b");
        }
        words.put("HOLD_FIRE", "\\b(hold fire|stop throwing|cease fire)\\b");
        return words;
    }

    @Override
    public void chose(Contestant who, String option) {
        if (option.equals("HOLD_FIRE")) {
            throwIn.put(who.getUuid(), 20 * 15);
        }
    }

    @Override
    public String situation(Contestant who) {
        return "You're playing TARGET, the queen of clubs, in a water pump plant: you and your partner stand on a narrow concrete rib "
                + "over a deep empty shaft, throwing glowing balls to knock the other pairs off their ribs. Fall and you die. The last pair "
                + "standing clears it." + (queens.contains(who.getUuid()) ? " You and your partner are the Queen of Clubs' pair, citizens here." : "");
    }

    @Override
    protected void ended() {
        for (SnowballEntity ball : world.getEntitiesByClass(SnowballEntity.class, bounds, e -> true)) {
            ball.discard();
        }
        for (UUID id : players) {
            ServerPlayerEntity player = player(id);
            if (player != null) {
                for (int i = 0; i < player.getInventory().size(); i++) {
                    if (player.getInventory().getStack(i).isOf(Items.SNOWBALL)) {
                        player.getInventory().setStack(i, ItemStack.EMPTY);
                    }
                }
            }
        }
    }
}
