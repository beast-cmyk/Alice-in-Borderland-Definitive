package com.deadmanshand.game;

import com.deadmanshand.Builder;
import com.deadmanshand.DeadMansHand;
import com.deadmanshand.Hud;
import com.deadmanshand.npc.Contestant;
import com.deadmanshand.npc.Lines;
import com.deadmanshand.npc.Motorcycle;
import com.deadmanshand.npc.Panther;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.potion.PotionUtil;
import net.minecraft.potion.Potions;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Four of Clubs: an underground highway. You wake up beside a graffiti-covered bus. The rules only say "reach the
 * goal", and your phone shows a distance that grows as you run. Halfway there's a supply table; further on, a black
 * panther in the dark; further still, a motorcycle; at the end, a wall. When someone reaches the wall, it breaks, and
 * the water comes. The goal was the bus all along: "GOAL" is painted on the side you can't see.
 * The real tunnel was ten kilometres and two hours; this one is scaled to fit.
 */
final class Distance extends Game {
    private static final int LENGTH = 400;
    private static final int DEPTH = 26;

    private int ty;
    private int startZ;
    private int endZ;
    private int ox;
    private Box bus;
    private BlockPos busDoor;
    private int floodAt = -1;
    private double front;
    private final Map<UUID, Integer> underwater = new HashMap<>();
    private final Map<UUID, Integer> realisedAt = new HashMap<>();
    private final List<UUID> props = new ArrayList<>();
    private int ticks;

    Distance(Kind kind, ServerWorld world, BlockPos origin) {
        super(kind, world, origin);
    }

    @Override
    protected void plan(Builder b) {
        ox = origin.getX();
        int oy = origin.getY();
        int oz = origin.getZ();
        ty = oy - DEPTH;
        startZ = oz - DEPTH - 3;
        endZ = startZ - LENGTH;
        bounds = new Box(ox - 5, ty - 2, endZ - 2, ox + 10, oy + 5, oz + 2);
        entry = new Box(ox - 2, oy, oz - 4, ox + 3, oy + 3, oz + 1);
        entryPos = new BlockPos(ox, oy, oz - 2);
        approach = new BlockPos(ox, oy, oz + 9);

        BlockState air = Blocks.AIR.getDefaultState();
        BlockState wall = Blocks.LIGHT_GRAY_CONCRETE.getDefaultState();
        // The entrance hut on the street, and the stairs down
        b.fill(ox - 4, oy, oz - 6, ox + 4, oy + 4, oz, Blocks.GRAY_CONCRETE.getDefaultState());
        b.fill(ox - 3, oy, oz - 5, ox + 3, oy + 3, oz - 1, air);
        b.fill(ox - 1, oy, oz, ox + 1, oy + 2, oz, air);
        b.set(ox, oy + 3, oz - 3, Blocks.SEA_LANTERN.getDefaultState());
        b.sign(new BlockPos(ox, oy + 3, oz + 1), Direction.SOUTH, "GAME VENUE", "", "step inside", "to register");
        for (int i = 0; i <= DEPTH; i++) {
            int y = oy - i;
            int z = oz - 5 - i;
            b.fill(ox - 2, y - 1, z, ox + 2, y + 3, z, Blocks.STONE_BRICKS.getDefaultState());
            b.fill(ox - 1, y, z, ox + 1, y + 2, z, air);
            b.fill(ox - 1, y - 1, z, ox + 1, y - 1, z, Blocks.STONE_BRICK_STAIRS.getDefaultState().with(StairsBlock.FACING, Direction.SOUTH));
            if (i % 6 == 0) {
                b.set(ox + 2, y + 1, z, Blocks.SEA_LANTERN.getDefaultState());
            }
        }
        // The tunnel
        for (int z = startZ + 2; z >= endZ; z--) {
            b.fill(ox - 4, ty - 1, z, ox + 4, ty + 5, z, wall);
            b.fill(ox - 3, ty, z, ox + 3, ty + 4, z, air);
            b.fill(ox - 3, ty - 1, z, ox + 3, ty - 1, z, Blocks.GRAY_CONCRETE.getDefaultState());
            if (Math.floorMod(z, 6) < 3) {
                b.set(ox, ty - 1, z, Blocks.YELLOW_CONCRETE.getDefaultState());
            }
            if (Math.floorMod(z, 10) == 0 && random.nextFloat() < 0.7F) {
                b.set(ox - 4, ty + 3, z, Blocks.SEA_LANTERN.getDefaultState());
                b.set(ox + 4, ty + 3, z, Blocks.SEA_LANTERN.getDefaultState());
            }
            if (random.nextFloat() < 0.03F) {
                b.set(ox + (random.nextBoolean() ? -4 : 4), ty + 1 + random.nextInt(3), z, Blocks.CRACKED_STONE_BRICKS.getDefaultState());
            }
        }
        // The stairs come out at the start of the tunnel
        b.fill(ox - 1, ty, startZ + 1, ox + 1, ty + 2, startZ + 3, air);
        // The end wall, cracked
        b.fill(ox - 3, ty, endZ, ox + 3, ty + 4, endZ, Blocks.CRACKED_STONE_BRICKS.getDefaultState());
        b.sign(new BlockPos(ox, ty + 2, endZ + 1), Direction.SOUTH, "", "?", "", "");
        // Every hundred metres, the distance painted on the wall
        for (int k = 1; k < 4; k++) {
            b.sign(new BlockPos(ox + 3, ty + 2, startZ - k * 100), Direction.WEST, "", (k * 100) + " m", "", "");
        }
        bus(b);
        // Halfway: a supply table
        int supply = startZ - LENGTH / 2;
        b.fill(ox + 2, ty, supply - 1, ox + 3, ty, supply + 1, Blocks.OAK_PLANKS.getDefaultState());
        b.set(ox + 3, ty + 1, supply, Blocks.BARREL.getDefaultState());
        b.set(ox + 3, ty + 2, supply, Blocks.LANTERN.getDefaultState());
        b.then(() -> {
            if (world.getBlockEntity(new BlockPos(ox + 3, ty + 1, supply)) instanceof BarrelBlockEntity barrel) {
                for (int i = 0; i < 6; i++) {
                    barrel.setStack(i, new ItemStack(Items.BREAD, 2));
                }
                for (int i = 6; i < 12; i++) {
                    barrel.setStack(i, PotionUtil.setPotion(new ItemStack(Items.POTION), Potions.WATER));
                }
            }
        });
        // The panther's den: a dark alcove off the east wall
        int den = startZ - LENGTH * 3 / 5;
        b.fill(ox + 4, ty, den - 3, ox + 8, ty + 3, den + 3, air);
        b.fill(ox + 4, ty - 1, den - 3, ox + 8, ty - 1, den + 3, Blocks.COARSE_DIRT.getDefaultState());
        b.fill(ox + 9, ty - 1, den - 4, ox + 9, ty + 4, den + 4, wall);
        b.fill(ox + 4, ty + 4, den - 4, ox + 9, ty + 4, den + 4, wall);
        b.fill(ox + 4, ty - 1, den - 4, ox + 9, ty + 4, den - 4, wall);
        b.fill(ox + 4, ty - 1, den + 4, ox + 9, ty + 4, den + 4, wall);
        b.set(ox + 8, ty, den, Blocks.BONE_BLOCK.getDefaultState());
    }

    /** The bus: yellow, battered, sprayed all over; on the side against the wall, in red: GOAL. */
    private void bus(Builder b) {
        int x0 = ox - 2;
        int x1 = ox;
        int z1 = startZ - 2;
        int z0 = z1 - 15;
        bus = new Box(x0 + 1, ty, z0 + 1, x1, ty + 3, z1);
        busDoor = new BlockPos(x1, ty, z1 - 3);
        BlockState body = Blocks.YELLOW_CONCRETE.getDefaultState();
        b.fill(x0, ty, z0, x1, ty + 3, z1, body);
        b.fill(x0 + 1, ty, z0 + 1, x1 - 1, ty + 2, z1 - 1, Blocks.AIR.getDefaultState());
        b.fill(x0 + 1, ty - 1, z0 + 1, x1 - 1, ty - 1, z1 - 1, Blocks.SMOOTH_STONE.getDefaultState());
        // Windows on the side you can see; the wall side is solid, so the letters on it stay clean
        for (int z = z0 + 1; z < z1; z++) {
            if (Math.floorMod(z, 3) != 0) {
                b.set(x1, ty + 2, z, Blocks.BLACK_STAINED_GLASS.getDefaultState());
            }
        }
        b.set(x0, ty, z0, Blocks.BLACK_CONCRETE.getDefaultState());
        b.set(x0, ty, z1, Blocks.BLACK_CONCRETE.getDefaultState());
        b.set(x1, ty, z0, Blocks.BLACK_CONCRETE.getDefaultState());
        b.set(x1, ty, z1, Blocks.BLACK_CONCRETE.getDefaultState());
        b.fill(x0 + 1, ty, z0, x1 - 1, ty + 2, z0, Blocks.BLACK_STAINED_GLASS.getDefaultState());
        b.fill(busDoor.getX(), ty, busDoor.getZ(), busDoor.getX(), ty + 1, busDoor.getZ() - 1, Blocks.AIR.getDefaultState());
        // Graffiti all over the side you can see
        BlockState[] spray = {Blocks.MAGENTA_CONCRETE.getDefaultState(), Blocks.LIME_CONCRETE.getDefaultState(), Blocks.CYAN_CONCRETE.getDefaultState(),
                Blocks.BLACK_CONCRETE.getDefaultState(), Blocks.WHITE_CONCRETE.getDefaultState()};
        for (int z = z0 + 1; z < z1 - 5; z++) {
            for (int y = ty; y <= ty + 1; y++) {
                if (random.nextFloat() < 0.35F) {
                    b.set(x1, y, z, spray[random.nextInt(spray.length)]);
                }
            }
        }
        // "GOAL", 3 x 4 letters, on the wall side (read from the gap between the bus and the tunnel wall)
        String[][] letters = {
                {"###", "#..", "#.#", "###"},
                {"###", "#.#", "#.#", "###"},
                {"###", "#.#", "###", "#.#"},
                {"#..", "#..", "#..", "###"}};
        // Seen from the gap you face east, so left to right runs north to south
        int z = z0 + 1;
        for (String[] letter : letters) {
            for (int col = 0; col < 3; col++) {
                for (int row = 0; row < 4; row++) {
                    if (letter[row].charAt(col) == '#') {
                        b.set(x0, ty + 3 - row, z + col, Blocks.RED_CONCRETE.getDefaultState());
                    }
                }
            }
            z += 4;
        }
    }

    @Override
    protected String[] rules() {
        return new String[]{
                "Reach the goal within the time limit.",
                "Your phone shows the distance.",
                "Time limit: six minutes."};
    }

    @Override
    protected int timeLimit() {
        return 20 * 60 * 6;
    }

    @Override
    protected void arrange() {
        int i = 0;
        for (LivingEntity entity : living()) {
            entity.teleport(ox + 1.5 + (i % 2), ty, startZ - 4 - i * 1.5);
            if (entity instanceof Contestant contestant) {
                contestant.hold();
            }
            i++;
        }
    }

    @Override
    protected void begin() {
        Panther panther = DeadMansHand.PANTHER.create(world);
        int den = startZ - LENGTH * 3 / 5;
        if (panther != null) {
            panther.refreshPositionAndAngles(ox + 7.5, ty, den + 0.5, 270.0F, 0.0F);
            panther.addScoreboardTag(DeadMansHand.ID);
            world.spawnEntity(panther);
            props.add(panther.getUuid());
        }
        Motorcycle bike = DeadMansHand.MOTORCYCLE.create(world);
        if (bike != null) {
            bike.refreshPositionAndAngles(ox - 2.0, ty, startZ - LENGTH * 4 / 5 + 0.5, 180.0F, 0.0F);
            bike.addScoreboardTag(DeadMansHand.ID);
            world.spawnEntity(bike);
            props.add(bike.getUuid());
        }
        for (Contestant contestant : strangers()) {
            contestant.stopAll();
            // Most run for it; the sharpest may work it out later
            if (contestant.judgment() > 0.8F && random.nextFloat() < 0.5F) {
                realisedAt.put(contestant.getUuid(), 20 * (40 + random.nextInt(120)));
            }
        }
        Contestant first = strangers().isEmpty() ? null : strangers().get(0);
        if (first != null) {
            first.speak("The game began in a dark highway tunnel beside an old bus. The rules only say: reach the goal. The tunnel goes on and on.",
                    Lines.pick(random, "Reach the goal? What goal? It just goes on.", "Run. It has to be at the end.", "Six minutes. Move!"));
        }
    }

    /** Your distance from the goal: from the bus door. */
    private int distance(LivingEntity entity) {
        double dx = entity.getX() - (busDoor.getX() + 0.5);
        double dz = entity.getZ() - (busDoor.getZ() + 0.5);
        return (int) Math.round(Math.sqrt(dx * dx + dz * dz));
    }

    @Override
    protected void running() {
        ticks++;
        for (UUID id : playing()) {
            LivingEntity entity = entity(id);
            if (entity == null) {
                continue;
            }
            if (bus.contains(entity.getPos()) || entity.getVehicle() != null && bus.contains(entity.getVehicle().getPos())) {
                clear(id);
                if (entity instanceof Contestant contestant) {
                    contestant.hold();
                    contestant.speak("You're inside the bus: it was the goal all along. You've cleared the game.",
                            Lines.pick(random, "It was the bus. The whole time.", "We ran all that way for nothing.", "GOAL. It was written right on it."));
                }
                continue;
            }
            if (floodAt < 0 && entity.getZ() < endZ + 10) {
                floodAt = ticks + 60;
                world.playSound(null, ox, ty + 2, endZ + 2, SoundEvents.BLOCK_STONE_BREAK, SoundCategory.BLOCKS, 3.0F, 0.5F);
            }
        }
        if (floodAt < 0 && timeLeft < 20 * 100) {
            floodAt = ticks;
        }
        if (floodAt > 0 && ticks >= floodAt) {
            flood();
        }
        strangersThink();
    }

    private void flood() {
        if (ticks == floodAt) {
            front = endZ;
            announce("Water is pouring into the tunnel.");
            world.playSound(null, ox, ty + 2, endZ + 2, SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.BLOCKS, 4.0F, 0.6F);
        }
        double stop = startZ - 40;
        double next = Math.min(stop, front + 0.32);
        for (int z = (int) Math.floor(front); z <= (int) Math.floor(next); z++) {
            for (int x = ox - 3; x <= ox + 3; x++) {
                for (int y = ty; y <= ty + 2; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (world.isAir(pos)) {
                        world.setBlockState(pos, Blocks.WATER.getDefaultState(), Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
                    }
                }
            }
        }
        front = next;
        if (ticks % 4 == 0) {
            world.spawnParticles(ParticleTypes.SPLASH, ox + 0.5, ty + 2.5, front, 40, 3.0, 1.0, 0.5, 0.3);
            world.playSound(null, ox, ty + 1, front, SoundEvents.BLOCK_WATER_AMBIENT, SoundCategory.BLOCKS, 2.0F, 0.6F);
        }
        for (UUID id : playing()) {
            LivingEntity entity = entity(id);
            if (entity == null) {
                continue;
            }
            if (entity.getZ() < front && entity.getZ() > endZ - 1) {
                int under = underwater.merge(id, 1, Integer::sum);
                entity.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 30, 2));
                if (under % 20 == 0) {
                    entity.damage(net.minecraft.entity.damage.DamageSource.DROWN, 3.0F);
                }
                if (under > 20 * 7) {
                    fail(id, "The water took you.");
                }
            } else {
                underwater.remove(id);
            }
        }
    }

    private void strangersThink() {
        for (Contestant contestant : strangers()) {
            UUID id = contestant.getUuid();
            Integer realise = realisedAt.get(id);
            boolean worked = realise != null && ticks >= realise;
            boolean fleeing = floodAt > 0 && ticks >= floodAt;
            if (worked && realise == ticks) {
                contestant.speak("You've just realised the number on your phone isn't how far you've run: it's how far you are from the goal, and it "
                                + "keeps growing. The goal is back at the start: the bus.",
                        "Wait. The number goes UP the further we run. It's the distance FROM the goal. The bus! Go back!");
                for (Contestant other : strangers()) {
                    if (other != contestant && other.squaredDistanceTo(contestant) < 20 * 20) {
                        realisedAt.putIfAbsent(other.getUuid(), ticks + 10);
                    }
                }
            }
            if (worked || fleeing) {
                contestant.go(new BlockPos(busDoor.getX() + 1, ty, busDoor.getZ()), 1.35);
                if (contestant.squaredDistanceTo(busDoor.getX() + 1.0, ty, busDoor.getZ()) < 2.5) {
                    contestant.go(new BlockPos(busDoor.getX() - 1, ty, busDoor.getZ() - 1), 1.0);
                }
                continue;
            }
            // Run for the end
            if (contestant.arrived(2.0)) {
                contestant.go(new BlockPos(ox + random.nextInt(5) - 2, ty, (int) Math.max(endZ + 3, contestant.getZ() - 40)), 1.2);
            }
        }
    }

    @Override
    protected void heard(ServerPlayerEntity player, String message) {
        String lower = message.toLowerCase();
        if (lower.matches(".*\\b(bus|go back|start|goal)\\b.*")) {
            for (Contestant contestant : strangers()) {
                if (contestant.squaredDistanceTo(player) < 30.0 * 30.0 && (contestant.trust(player.getUuid()) > -0.1F || contestant.judgment() > 0.5F)) {
                    realisedAt.putIfAbsent(contestant.getUuid(), ticks + 20);
                }
            }
        }
    }

    @Override
    protected void more(ServerPlayerEntity player, Hud hud) {
        hud.line("§fDISTANCE  §e" + distance(player) + " m");
    }

    // --- what a stranger can say they'll do, and then does ---------------------------------------------------

    @Override
    protected boolean followable() {
        return true;
    }

    @Override
    public Map<String, String> options(Contestant who) {
        UUID id = who.getUuid();
        Integer r = realisedAt.get(id);
        return r != null && ticks >= r ? Map.of("KEEP_RUNNING", "turn round again and keep running down the tunnel")
                : Map.of("BUS", "turn back and run to the bus at the start, because the goal is the bus");
    }

    @Override
    protected Map<String, String> optionWords(Contestant who) {
        return Map.of("BUS", "\\b(the bus|go back|back to the start|turn back|turn around)\\b");
    }

    @Override
    public void chose(Contestant who, String option) {
        if (option.equals("BUS")) {
            realisedAt.put(who.getUuid(), ticks);
        } else if (option.equals("KEEP_RUNNING")) {
            realisedAt.remove(who.getUuid());
        }
    }

    @Override
    public String situation(Contestant who) {
        return "You're playing DISTANCE, the four of clubs, in a long dark highway tunnel underground. The rules only say: reach the goal within "
                + "six minutes; your phone shows a distance. You started beside a graffiti-covered bus. Your phone says " + distance(who)
                + " metres right now." + (floodAt > 0 && ticks >= floodAt ? " Water is flooding the tunnel from the far end!" : "")
                + (realisedAt.containsKey(who.getUuid()) && ticks >= realisedAt.get(who.getUuid()) ? " You've worked out the goal is the bus." : "");
    }

    @Override
    protected void ended() {
        for (UUID id : props) {
            if (world.getEntity(id) != null) {
                world.getEntity(id).discard();
            }
        }
        props.clear();
    }
}
