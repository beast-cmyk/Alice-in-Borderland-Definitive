package com.deadmanshand.game;

import com.deadmanshand.Builder;
import com.deadmanshand.Hud;
import com.deadmanshand.Laser;
import com.deadmanshand.npc.Contestant;
import com.deadmanshand.npc.Lines;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.passive.ParrotEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
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
 * Runaway Train: eight carriages, and you start in the last. Go forward a carriage at a time; no going back. Each one
 * locks behind you and fills with gas, oxygen or poison, and you have thirty seconds to decide whether to wear your
 * mask. You have five canisters for it, and they can't be taken from you. Four of the carriages are poison. There's a
 * canary in every carriage. When the siren goes in the cab, stop the train.
 */
final class RunawayTrain extends Game {
    private static final int CARS = 8;
    private static final int MOVE = 15 * 20;
    private static final int DECIDE = 30 * 20;
    private static final int GAS = 4 * 20;
    private static final int STOP = 45 * 20;

    private enum Step { MOVE, DECIDE, GAS, STOP }

    private final boolean[] poison = new boolean[CARS + 1];
    private final Map<UUID, Integer> canisters = new HashMap<>();
    private final Set<UUID> masked = new HashSet<>();
    private final Map<UUID, Integer> decideAt = new HashMap<>();
    private final Map<Integer, ParrotEntity> canary = new HashMap<>();
    private Step step = Step.MOVE;
    /** The carriage everyone is going into, 7 down to 1 (the cab). */
    private int car = CARS;
    private int timer;
    private int ticks;
    private int base;
    private int tz;
    private int oy;
    private BlockPos brake;

    RunawayTrain(Kind kind, ServerWorld world, BlockPos origin) {
        super(kind, world, origin);
    }

    /** West wall of carriage {@code i} (1 is the cab, at the west end). */
    private int wall(int i) {
        return base + 6 * (i - 1);
    }

    private int carOf(double x) {
        return (int) Math.floor((x - base) / 6.0) + 1;
    }

    private boolean inside(LivingEntity e, int i) {
        return e.getX() > wall(i) + 1 && e.getX() < wall(i) + 6 && Math.abs(e.getZ() - (tz + 0.5)) < 2 && e.getY() > oy;
    }

    @Override
    protected void plan(Builder b) {
        int ox = origin.getX();
        oy = origin.getY();
        int oz = origin.getZ();
        base = ox - 23;
        tz = oz - 20;
        brake = new BlockPos(base + 1, oy + 1, tz);
        bounds = new Box(ox - 24, oy - 2, oz - 47, ox + 26, oy + 10, oz + 3);
        entry = new Box(ox - 3, oy, oz - 4, ox + 4, oy + 3, oz + 1);
        entryPos = new BlockPos(ox, oy, oz - 2);
        approach = new BlockPos(ox, oy, oz + 6);
        // which carriages are poison: four of the seven you walk into
        for (int i = 1; i <= CARS; i++) {
            poison[i] = false;
        }
        for (int n = 0; n < 4; ) {
            int i = 1 + random.nextInt(CARS - 1);
            if (!poison[i]) {
                poison[i] = true;
                n++;
            }
        }
        BlockState air = Blocks.AIR.getDefaultState();
        BlockState body = Blocks.WHITE_CONCRETE.getDefaultState();
        BlockState stripe = Blocks.GREEN_CONCRETE.getDefaultState();
        b.fill(ox - 24, oy, oz - 47, ox + 25, oy + 8, oz + 2, air);
        b.fill(ox - 24, oy - 1, oz - 47, ox + 25, oy - 1, oz + 2, Blocks.GRAVEL.getDefaultState());
        // the track
        b.fill(ox - 24, oy - 1, tz - 1, ox + 25, oy - 1, tz + 1, Blocks.POLISHED_ANDESITE.getDefaultState());
        // the platform and its canopy
        b.fill(ox - 24, oy - 1, tz + 3, ox + 25, oy, tz + 7, Blocks.SMOOTH_STONE.getDefaultState());
        b.fill(ox - 24, oy + 1, tz + 3, ox + 25, oy + 1, tz + 3, Blocks.YELLOW_CARPET.getDefaultState());
        for (int x = ox - 22; x <= ox + 24; x += 8) {
            b.fill(x, oy + 1, tz + 7, x, oy + 4, tz + 7, Blocks.IRON_BARS.getDefaultState());
        }
        b.fill(ox - 24, oy + 5, tz + 4, ox + 25, oy + 5, tz + 7, Blocks.LIGHT_GRAY_CONCRETE.getDefaultState());
        b.fill(ox - 2, oy, tz + 8, ox + 2, oy, oz - 1, Blocks.SMOOTH_STONE_SLAB.getDefaultState());
        // the carriages
        for (int i = 1; i <= CARS; i++) {
            int w = wall(i);
            b.fill(w, oy, tz - 2, w + 6, oy + 4, tz + 2, body);
            b.fill(w + 1, oy + 1, tz - 1, w + 5, oy + 3, tz + 1, air);
            b.fill(w, oy + 1, tz - 2, w + 6, oy + 1, tz - 2, stripe);
            b.fill(w, oy + 1, tz + 2, w + 6, oy + 1, tz + 2, stripe);
            b.fill(w + 1, oy + 2, tz - 2, w + 5, oy + 2, tz - 2, Blocks.GLASS_PANE.getDefaultState());
            b.fill(w + 1, oy + 2, tz + 2, w + 5, oy + 2, tz + 2, Blocks.GLASS_PANE.getDefaultState());
            b.fill(w + 1, oy + 1, tz - 1, w + 5, oy + 1, tz - 1, Blocks.BLUE_CARPET.getDefaultState());
            b.set(w + 3, oy + 3, tz, Blocks.SEA_LANTERN.getDefaultState());
            // the canary's cage
            b.set(w + 4, oy + 1, tz + 1, Blocks.SPRUCE_FENCE.getDefaultState());
            b.set(w + 4, oy + 3, tz + 1, Blocks.CHAIN.getDefaultState());
            // the door into the next carriage, barred
            if (i > 1) {
                b.fill(w, oy + 1, tz, w, oy + 2, tz, Blocks.IRON_BARS.getDefaultState());
            }
            b.sign(new BlockPos(w + 2, oy + 3, tz - 1), Direction.SOUTH, "", "CAR " + i, "", "");
        }
        // the cab: the windscreen, the brake
        b.fill(base, oy + 1, tz - 1, base, oy + 3, tz + 1, Blocks.BLACK_STAINED_GLASS.getDefaultState());
        b.set(brake, Blocks.REDSTONE_LAMP.getDefaultState());
        b.set(brake.down(), Blocks.IRON_BLOCK.getDefaultState());
        // the door in from the platform, at the back
        b.fill(wall(CARS) + 3, oy + 1, tz + 2, wall(CARS) + 3, oy + 2, tz + 2, air);
        b.sign(new BlockPos(ox + 3, oy + 2, oz + 2), Direction.SOUTH, "GAME VENUE", "", "step inside", "to register");
        b.fill(ox + 3, oy, oz + 1, ox + 3, oy + 2, oz + 1, Blocks.IRON_BLOCK.getDefaultState());
    }

    @Override
    protected String[] rules() {
        return new String[]{
                "Runaway Train. Go from the last carriage to the cab. You can't go back.",
                "Every carriage locks behind you and fills with gas: oxygen, or poison. Four of them are poison.",
                "You have thirty seconds in each to decide whether to wear your mask. You have five canisters. Nobody can take yours.",
                "When the siren sounds in the cab, stop the train."};
    }

    @Override
    protected int timeLimit() {
        return 20 * 60 * 20;
    }

    @Override
    protected void arrange() {
        for (UUID id : playing()) {
            canisters.put(id, 5);
        }
        int i = 0;
        for (LivingEntity e : living()) {
            e.teleport(wall(CARS) + 1.5 + (i % 4), oy + 1, tz - 0.5 + (i / 4 % 3));
            if (e instanceof Contestant c) {
                c.hold();
            }
            i++;
        }
        world.setBlockState(new BlockPos(wall(CARS) + 3, oy + 1, tz + 2), Blocks.WHITE_CONCRETE.getDefaultState());
        world.setBlockState(new BlockPos(wall(CARS) + 3, oy + 2, tz + 2), Blocks.WHITE_CONCRETE.getDefaultState());
        for (int k = 1; k < CARS; k++) {
            ParrotEntity bird = EntityType.PARROT.create(world);
            if (bird != null) {
                bird.setAiDisabled(true);
                bird.setPersistent();
                bird.setInvulnerable(true);
                bird.refreshPositionAndAngles(wall(k) + 4.5, oy + 2, tz + 1.5, 90.0F, 0.0F);
                world.spawnEntity(bird);
                canary.put(k, bird);
            }
        }
    }

    @Override
    protected void begin() {
        world.playSound(null, origin.getX(), oy + 1, tz, SoundEvents.BLOCK_IRON_DOOR_CLOSE, SoundCategory.BLOCKS, 1.5F, 0.7F);
        announce("The train is moving.");
        openDoor();
    }

    @Override
    protected void ended() {
        for (ParrotEntity bird : canary.values()) {
            bird.discard();
        }
    }

    // --- the carriages ---------------------------------------------------------------------------------------------

    private void openDoor() {
        car--;
        step = Step.MOVE;
        timer = MOVE;
        masked.clear();
        int w = wall(car + 1);
        world.setBlockState(new BlockPos(w, oy + 1, tz), Blocks.AIR.getDefaultState());
        world.setBlockState(new BlockPos(w, oy + 2, tz), Blocks.AIR.getDefaultState());
        world.playSound(null, w, oy + 1, tz, SoundEvents.BLOCK_IRON_DOOR_OPEN, SoundCategory.BLOCKS, 1.2F, 1.0F);
        announce(car == 1 ? "The door to the cab is open. Fifteen seconds." : "The door to car " + car + " is open. Fifteen seconds.");
    }

    private void lockDoor() {
        int w = wall(car + 1);
        world.setBlockState(new BlockPos(w, oy + 1, tz), Blocks.IRON_BARS.getDefaultState());
        world.setBlockState(new BlockPos(w, oy + 2, tz), Blocks.IRON_BARS.getDefaultState());
        world.playSound(null, w, oy + 1, tz, SoundEvents.BLOCK_IRON_DOOR_CLOSE, SoundCategory.BLOCKS, 1.5F, 0.7F);
        for (UUID id : playing()) {
            LivingEntity e = entity(id);
            if (e != null && !inside(e, car)) {
                announce(nameOf(id) + " was left behind in car " + (car + 1) + ".");
                kill(id, Laser.POISON, "You were left behind. The carriage behind fills with gas.");
            }
        }
        step = Step.DECIDE;
        timer = DECIDE;
        announce("Car " + car + " is locked. Thirty seconds: mask or no mask.");
        for (UUID id : playing()) {
            ServerPlayerEntity p = player(id);
            if (p != null) {
                choices(p, "Canisters: " + canisters.get(id) + ".", "§aMASK ON|mask", "§7NO MASK|nomask");
            }
            decideAt.put(id, 60 + random.nextInt(DECIDE - 140));
        }
    }

    private void gas() {
        step = Step.GAS;
        timer = GAS;
        boolean bad = poison[car];
        int w = wall(car);
        world.playSound(null, w + 3, oy + 3, tz, SoundEvents.BLOCK_FIRE_EXTINGUISH, SoundCategory.BLOCKS, 2.0F, 0.5F);
        for (UUID id : playing()) {
            if (masked.contains(id)) {
                canisters.merge(id, -1, Integer::sum);
            }
        }
        announce(bad ? "Car " + car + ": poison." : "Car " + car + ": oxygen.");
        ParrotEntity bird = canary.remove(car);
        if (bird != null && bad) {
            world.spawnParticles(ParticleTypes.POOF, bird.getX(), bird.getY() + 0.3, bird.getZ(), 8, 0.1, 0.1, 0.1, 0.01);
            bird.discard();
        } else if (bird != null) {
            canary.put(car, bird);
        }
        for (UUID id : playing()) {
            if (bad && !masked.contains(id)) {
                kill(id, Laser.POISON, "Poison, and no mask.");
            }
        }
    }

    private void wear(UUID id, boolean on) {
        if (step != Step.DECIDE) {
            return;
        }
        if (on && canisters.getOrDefault(id, 0) <= 0) {
            tell(entity(id), "Your mask has no canisters left.", Formatting.RED);
            return;
        }
        if (on) {
            masked.add(id);
        } else {
            masked.remove(id);
        }
        tell(entity(id), on ? "Mask on." : "Mask off.", on ? Formatting.GREEN : Formatting.GRAY);
    }

    @Override
    protected void answered(ServerPlayerEntity player, String answer) {
        String a = answer.trim().toLowerCase();
        if (a.equals("mask")) {
            wear(player.getUuid(), true);
        } else if (a.equals("nomask")) {
            wear(player.getUuid(), false);
        }
    }

    @Override
    protected ActionResult use(ServerPlayerEntity player, BlockPos pos, BlockState state) {
        if (pos.equals(brake)) {
            if (step == Step.STOP) {
                stop(player.getUuid());
            } else {
                tell(player, "The brake won't move. Not yet.", Formatting.GRAY);
            }
            return ActionResult.SUCCESS;
        }
        return ActionResult.PASS;
    }

    private void stop(UUID who) {
        announce(nameOf(who) + " pulls the brake. The train screams to a stop.");
        world.playSound(null, brake, SoundEvents.BLOCK_ANVIL_LAND, SoundCategory.BLOCKS, 2.0F, 0.5F);
        for (UUID id : playing()) {
            clear(id);
        }
        finish();
    }

    @Override
    protected void running() {
        ticks++;
        if (ticks % 40 == 0) {
            world.playSound(null, origin.getX(), oy + 1, tz, SoundEvents.ENTITY_MINECART_RIDING, SoundCategory.AMBIENT, 1.2F, 0.6F);
        }
        // the canaries: the ones in clean air sing
        if (ticks % 30 == 0) {
            for (Map.Entry<Integer, ParrotEntity> e : canary.entrySet()) {
                if (!poison[e.getKey()]) {
                    ParrotEntity bird = e.getValue();
                    world.spawnParticles(ParticleTypes.NOTE, bird.getX(), bird.getY() + 0.7, bird.getZ(), 1, 0.1, 0.1, 0.1, 1.0);
                    world.playSound(null, bird.getX(), bird.getY(), bird.getZ(), SoundEvents.ENTITY_PARROT_AMBIENT, SoundCategory.NEUTRAL, 0.5F, 1.6F);
                }
            }
        }
        switch (step) {
            case MOVE -> {
                for (Contestant c : strangers()) {
                    c.go(new Vec3d(wall(car) + 2.5 + Math.floorMod(c.getUuid().hashCode(), 3), oy + 1, tz + 0.5), 1.2);
                }
                if (--timer <= 0) {
                    lockDoor();
                }
            }
            case DECIDE -> {
                for (Contestant c : strangers()) {
                    UUID id = c.getUuid();
                    c.hold();
                    if (decideAt.merge(id, -1, Integer::sum) == 0) {
                        boolean on = decide(c);
                        wear(id, on);
                        if (random.nextFloat() < 0.35F) {
                            c.say(on ? Lines.pick(random, "Mask on. I'm not risking it.", "Mask. Better safe.") : Lines.pick(random,
                                    "No mask. The bird's singing.", "I'm saving my canisters.", "No mask. I think it's clean."));
                        }
                    }
                }
                if (--timer <= 0) {
                    gas();
                }
            }
            case GAS -> {
                if (poison[car] && ticks % 4 == 0) {
                    world.spawnParticles(ParticleTypes.ITEM_SLIME, wall(car) + 3.5, oy + 2.5, tz + 0.5, 12, 2.0, 0.8, 0.8, 0.01);
                } else if (ticks % 4 == 0) {
                    world.spawnParticles(ParticleTypes.CLOUD, wall(car) + 3.5, oy + 2.5, tz + 0.5, 6, 2.0, 0.8, 0.8, 0.01);
                }
                if (--timer <= 0) {
                    if (car == 1) {
                        step = Step.STOP;
                        timer = STOP;
                        announce("A siren in the cab. Stop the train!");
                    } else {
                        openDoor();
                    }
                }
            }
            case STOP -> {
                if (ticks % 10 == 0) {
                    world.playSound(null, brake, SoundEvents.BLOCK_BELL_USE, SoundCategory.BLOCKS, 2.0F, 1.6F);
                }
                for (Contestant c : strangers()) {
                    c.go(Vec3d.ofCenter(brake).add(1.0, -0.5, 0), 1.2);
                    if (c.squaredDistanceTo(Vec3d.ofCenter(brake)) < 2.5 * 2.5 && random.nextFloat() < 0.05F) {
                        stop(c.getUuid());
                        return;
                    }
                }
                if (--timer <= 0) {
                    announce("Nobody stopped it.");
                    for (UUID id : playing()) {
                        kill(id, Laser.BLAST, "The train crashed.");
                    }
                    finish();
                }
            }
        }
    }

    /** Whether a stranger wears the mask: the sharp ones listen to the canary; the rest weigh their canisters. */
    private boolean decide(Contestant c) {
        int left = canisters.getOrDefault(c.getUuid(), 0);
        if (left <= 0) {
            return false;
        }
        if (c.judgment() > 0.6F && random.nextFloat() < c.judgment()) {
            return poison[car];
        }
        if (c.nature == Contestant.Nature.TIMID || c.feelings.fear > 0.6F) {
            return true;      // too scared to gamble with their life
        }
        if (c.feelings.grief > 0.7F && c.feelings.hope < 0.15F && random.nextFloat() < 0.3F) {
            return false;     // past caring
        }
        int carsLeft = car;
        return random.nextFloat() < Math.min(1.0F, (float) left / carsLeft + 0.2F);
    }

    // --- what a stranger can say they'll do, and then does ---------------------------------------------------

    @Override
    public Map<String, String> options(Contestant who) {
        if (step != Step.DECIDE) {
            return Map.of();
        }
        Map<String, String> options = new LinkedHashMap<>();
        options.put("MASK", "put your mask on for this carriage (uses one of your " + canisters.getOrDefault(who.getUuid(), 0) + " canisters)");
        options.put("NO_MASK", "go without the mask in this carriage, and save the canister");
        return options;
    }

    @Override
    protected Map<String, String> optionWords(Contestant who) {
        return Map.of("MASK", "\\b(mask on|put (my|the|a) mask on|put on (my|the|a) mask|wear(ing)? (my|the|a) mask|use (a|my|one) canister)\\b",
                "NO_MASK", "\\b(no mask|without (a|the|my) mask|save (my|a|the) canister|mask off)\\b");
    }

    @Override
    public void chose(Contestant who, String option) {
        if (step == Step.DECIDE) {
            wear(who.getUuid(), option.equals("MASK"));
            decideAt.put(who.getUuid(), -1);
        }
    }

    // --- show ---------------------------------------------------------------------------------------------

    @Override
    protected void more(ServerPlayerEntity player, Hud hud) {
        UUID id = player.getUuid();
        hud.line("§fCAR §e" + car + "  §fCANISTERS §e" + canisters.getOrDefault(id, 0));
        hud.line(switch (step) {
            case MOVE -> "§eDOOR OPEN " + Hud.clock(timer) + " §7move forward";
            case DECIDE -> (masked.contains(id) ? "§aMASK ON " : "§7NO MASK ") + Hud.clock(timer);
            case GAS -> "§cGAS";
            case STOP -> "§c§lSTOP THE TRAIN §r§7(use the brake) " + Hud.clock(timer);
        });
    }

    @Override
    public String situation(Contestant who) {
        return "You're playing RUNAWAY TRAIN: eight carriages, going forward one at a time, no way back. Each carriage locks and fills with "
                + "oxygen or poison; you decide each time whether to wear your mask, and you have " + canisters.getOrDefault(who.getUuid(), 0)
                + " canisters left. Four carriages are poison. Each carriage has a canary in a cage" + (canary.containsKey(car)
                ? (poison[car] ? "; this one's canary is silent and still" : "; this one's canary is singing") : "") + ". You're in car " + car + ".";
    }
}
