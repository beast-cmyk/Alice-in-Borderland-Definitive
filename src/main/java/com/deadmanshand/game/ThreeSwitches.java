package com.deadmanshand.game;

import com.deadmanshand.Builder;
import com.deadmanshand.Hud;
import com.deadmanshand.npc.Contestant;
import com.deadmanshand.npc.Lines;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.LeverBlock;
import net.minecraft.block.RedstoneLampBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.WallMountedBlock;
import net.minecraft.block.enums.WallMountLocation;
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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Four of Diamonds, in a sewer reservoir under the city. Three switches, A, B and C; one of them lights the bulb in
 * the room next door. While its door is closed you may flip them as you like; while it's open, each only once. One
 * answer, on the panel. Meanwhile the water is rising, and when it reaches the transformer, everyone in here dies.
 */
final class ThreeSwitches extends Game {
    private static final int DEPTH = 20;
    private static final int RISE_TICKS = 20 * 72;
    private static final String[] NAMES = {"A", "B", "C"};

    private int ox;
    private int oz;
    private int ry;
    private final BlockPos[] levers = new BlockPos[3];
    private final BlockPos[] answers = new BlockPos[3];
    private BlockPos lamp;
    private BlockPos door;
    private BlockPos doorButton;
    private Box room;
    private Box wire;
    private int right;
    private boolean doorOpen;
    private final boolean[] flippedWhileOpen = new boolean[3];
    private int heat;
    private int level;
    private int ticks;
    private boolean answered;
    private boolean anyoneFlipped;
    /** The stranger working the puzzle out, and how far they've got. */
    private UUID solver;
    private int step;
    private int stepAt;
    private boolean sacrificed;

    ThreeSwitches(Kind kind, ServerWorld world, BlockPos origin) {
        super(kind, world, origin);
    }

    private int waterY() {
        return ry - 5 + level;
    }

    @Override
    protected void plan(Builder b) {
        ox = origin.getX();
        oz = origin.getZ();
        int oy = origin.getY();
        ry = oy - DEPTH;
        right = random.nextInt(3);
        bounds = new Box(ox - 16, ry - 7, oz - 57, ox + 17, oy + 5, oz + 2);
        entry = new Box(ox - 2, oy, oz - 4, ox + 3, oy + 3, oz + 1);
        entryPos = new BlockPos(ox, oy, oz - 2);
        approach = new BlockPos(ox, oy, oz + 9);

        BlockState air = Blocks.AIR.getDefaultState();
        BlockState wall = Blocks.STONE_BRICKS.getDefaultState();
        BlockState mossy = Blocks.MOSSY_STONE_BRICKS.getDefaultState();
        // The hut and the stairs down
        b.fill(ox - 4, oy, oz - 6, ox + 4, oy + 4, oz, Blocks.GRAY_CONCRETE.getDefaultState());
        b.fill(ox - 3, oy, oz - 5, ox + 3, oy + 3, oz - 1, air);
        b.fill(ox - 1, oy, oz, ox + 1, oy + 2, oz, air);
        b.set(ox, oy + 3, oz - 3, Blocks.SEA_LANTERN.getDefaultState());
        b.sign(new BlockPos(ox, oy + 3, oz + 1), Direction.SOUTH, "GAME VENUE", "", "step inside", "to register");
        for (int i = 0; i <= DEPTH; i++) {
            int y = oy - i;
            int z = oz - 5 - i;
            b.fill(ox - 2, y - 1, z, ox + 2, y + 3, z, wall);
            b.fill(ox - 1, y, z, ox + 1, y + 2, z, air);
            b.fill(ox - 1, y - 1, z, ox + 1, y - 1, z, Blocks.STONE_BRICK_STAIRS.getDefaultState().with(StairsBlock.FACING, Direction.SOUTH));
        }
        // The reservoir: a gallery along the south wall, the basin below it to the north
        b.fill(ox - 16, ry - 7, oz - 56, ox + 16, ry + 7, oz - 25, wall);
        b.fill(ox - 15, ry - 6, oz - 55, ox + 15, ry + 6, oz - 26, air);
        b.fill(ox - 15, ry - 6, oz - 55, ox + 15, ry - 6, oz - 32, mossy);
        b.fill(ox - 15, ry - 6, oz - 31, ox + 15, ry - 1, oz - 26, wall);
        b.fill(ox - 15, ry, oz - 31, ox + 15, ry, oz - 31, Blocks.IRON_BARS.getDefaultState());
        b.fill(ox - 1, ry, oz - 26, ox + 1, ry + 2, oz - 24, air);
        for (int x = ox - 14; x <= ox + 14; x += 7) {
            b.set(x, ry + 5, oz - 28, Blocks.SEA_LANTERN.getDefaultState());
            b.set(x, ry + 5, oz - 45, Blocks.SEA_LANTERN.getDefaultState());
            b.fill(x, ry - 5, oz - 55, x, ry + 5, oz - 55, Blocks.POLISHED_ANDESITE.getDefaultState());
        }
        // The water already in the bottom
        b.fill(ox - 15, ry - 5, oz - 55, ox + 15, ry - 5, oz - 32, Blocks.WATER.getDefaultState());
        // The transformer, on the gallery at the west end, with its live wires
        b.fill(ox - 14, ry, oz - 30, ox - 12, ry + 2, oz - 28, Blocks.IRON_BLOCK.getDefaultState());
        b.set(ox - 13, ry + 3, oz - 29, Blocks.LIGHTNING_ROD.getDefaultState());
        b.set(ox - 14, ry + 3, oz - 30, Blocks.LIGHTNING_ROD.getDefaultState());
        b.fill(ox - 11, ry, oz - 30, ox - 8, ry, oz - 30, Blocks.BLACK_CONCRETE.getDefaultState());
        b.sign(new BlockPos(ox - 10, ry + 1, oz - 26), Direction.NORTH, "DANGER", "HIGH", "VOLTAGE", "");
        wire = new Box(ox - 11, ry, oz - 31, ox - 7, ry + 1.5, oz - 29);
        // The three switches on the south wall
        for (int i = 0; i < 3; i++) {
            levers[i] = new BlockPos(ox - 6 + 2 * i, ry + 1, oz - 26);
            b.set(levers[i], Blocks.LEVER.getDefaultState().with(WallMountedBlock.FACE, WallMountLocation.WALL)
                    .with(HorizontalFacingBlock.FACING, Direction.NORTH).with(LeverBlock.POWERED, false));
            b.sign(levers[i].up(), Direction.NORTH, "", "SWITCH", NAMES[i], "");
        }
        // The answer panel
        for (int i = 0; i < 3; i++) {
            answers[i] = new BlockPos(ox + 2 + 2 * i, ry + 1, oz - 26);
            b.set(answers[i], Blocks.STONE_BUTTON.getDefaultState().with(WallMountedBlock.FACE, WallMountLocation.WALL)
                    .with(HorizontalFacingBlock.FACING, Direction.NORTH));
            b.sign(answers[i].up(), Direction.NORTH, "ANSWER", NAMES[i], "one answer", "only");
        }
        // The bulb room at the east end, behind an iron door
        room = new Box(ox + 10, ry, oz - 30, ox + 15, ry + 3, oz - 26);
        b.fill(ox + 9, ry, oz - 31, ox + 15, ry + 3, oz - 26, wall);
        b.fill(ox + 10, ry, oz - 30, ox + 14, ry + 2, oz - 27, air);
        lamp = new BlockPos(ox + 12, ry + 2, oz - 28);
        b.set(lamp, Blocks.REDSTONE_LAMP.getDefaultState().with(RedstoneLampBlock.LIT, false));
        door = new BlockPos(ox + 9, ry, oz - 28);
        b.door(door, Blocks.IRON_DOOR, Direction.WEST);
        doorButton = new BlockPos(ox + 8, ry + 1, oz - 27);
        b.set(doorButton, Blocks.STONE_BUTTON.getDefaultState().with(WallMountedBlock.FACE, WallMountLocation.WALL)
                .with(HorizontalFacingBlock.FACING, Direction.WEST));
        b.sign(new BlockPos(ox + 8, ry + 2, oz - 29), Direction.WEST, "", "DOOR", "", "");
    }

    @Override
    protected String[] rules() {
        return new String[]{
                "There are three switches: A, B and C. One of them lights the bulb in the next room.",
                "While the door is closed, you may flip the switches as much as you like.",
                "While the door is open, each switch may be flipped only once. The door won't close while anyone is inside.",
                "Give your answer on the panel. One answer only.",
                "The game lasts until the water reaches the electrical equipment."};
    }

    @Override
    protected int timeLimit() {
        return RISE_TICKS * 5;
    }

    @Override
    protected void arrange() {
        int i = 0;
        for (LivingEntity entity : living()) {
            entity.teleport(ox - 4 + (i % 8) + 0.5, ry, oz - 29 + (i / 8) * 1.0);
            if (entity instanceof Contestant contestant) {
                contestant.hold();
            }
            i++;
        }
    }

    @Override
    protected void begin() {
        for (Contestant contestant : strangers()) {
            contestant.stopAll();
        }
        Contestant cleverest = null;
        for (Contestant contestant : strangers()) {
            if (contestant.judgment() >= 0.62F && (cleverest == null || contestant.judgment() > cleverest.judgment())) {
                cleverest = contestant;
            }
        }
        solver = cleverest == null ? null : cleverest.getUuid();
        Contestant first = strangers().isEmpty() ? null : strangers().get(0);
        if (first != null) {
            first.speak("The game started in a sewer reservoir. Three switches, one bulb behind a door, and the water is rising toward the transformer.",
                    Lines.pick(random, "We only get one answer. Nobody touch the panel.", "Don't touch the wires. Did you see what happened?", "There's a trick to this. There has to be."));
        }
    }

    @Override
    protected void running() {
        ticks++;
        // The bulb and its heat
        boolean lit = world.getBlockState(levers[right]).getBlock() instanceof LeverBlock && world.getBlockState(levers[right]).get(LeverBlock.POWERED);
        BlockState lampState = world.getBlockState(lamp);
        if (lampState.getBlock() instanceof RedstoneLampBlock && lampState.get(RedstoneLampBlock.LIT) != lit) {
            world.setBlockState(lamp, lampState.with(RedstoneLampBlock.LIT, lit), Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
        }
        heat = lit ? Math.min(900, heat + 2) : Math.max(0, heat - 1);
        // The water
        int due = ticks / RISE_TICKS;
        if (due > level && level < 5) {
            level = due;
            rise();
        }
        // The live wires bite
        if (ticks % 4 == 0) {
            world.spawnParticles(ParticleTypes.ELECTRIC_SPARK, ox - 9.5, ry + 0.6, oz - 29.5, 3, 1.5, 0.3, 0.3, 0.05);
        }
        for (UUID id : playing()) {
            LivingEntity entity = entity(id);
            if (entity != null && wire.contains(entity.getPos())) {
                electrocute(id, entity, "You touched a live wire.");
            }
        }
        strangersThink();
    }

    private void rise() {
        int y = waterY();
        if (level >= 5) {
            announce("The water has reached the electrical equipment.");
            for (UUID id : playing()) {
                LivingEntity entity = entity(id);
                if (entity != null) {
                    electrocute(id, entity, "The water reached the transformer.");
                }
            }
            return;
        }
        for (int x = ox - 15; x <= ox + 15; x++) {
            for (int z = oz - 55; z <= oz - 32; z++) {
                world.setBlockState(new BlockPos(x, y, z), Blocks.WATER.getDefaultState(), Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
            }
        }
        world.playSound(null, ox, ry, oz - 40, SoundEvents.BLOCK_WATER_AMBIENT, SoundCategory.BLOCKS, 3.0F, 0.6F);
        announce(level == 4 ? "The water is one step from the transformer." : "The water is rising.");
    }

    private void electrocute(UUID id, LivingEntity entity, String why) {
        world.spawnParticles(ParticleTypes.ELECTRIC_SPARK, entity.getX(), entity.getY() + 1.0, entity.getZ(), 40, 0.4, 0.8, 0.4, 0.2);
        world.playSound(null, entity.getX(), entity.getY(), entity.getZ(), SoundEvents.ENTITY_LIGHTNING_BOLT_IMPACT, SoundCategory.HOSTILE, 1.2F, 1.6F);
        fail(id, why);
    }

    @Override
    protected ActionResult use(ServerPlayerEntity player, BlockPos pos, BlockState state) {
        if (phase != Phase.RUNNING) {
            return ActionResult.SUCCESS;
        }
        if (!isPlaying(player.getUuid())) {
            return ActionResult.PASS;
        }
        for (int i = 0; i < 3; i++) {
            if (pos.equals(levers[i])) {
                if (!flip(i, player)) {
                    tell(player, "While the door is open, each switch may be flipped only once.", Formatting.RED);
                }
                return ActionResult.SUCCESS;
            }
            if (pos.equals(answers[i])) {
                answer(i, player);
                return ActionResult.SUCCESS;
            }
        }
        if (pos.equals(doorButton) || state.getBlock() instanceof DoorBlock) {
            toggleDoor(player);
            return ActionResult.SUCCESS;
        }
        if (pos.equals(lamp)) {
            touch(player);
            return ActionResult.SUCCESS;
        }
        return ActionResult.PASS;
    }

    /** Flips switch {@code i}; false if the rules won't allow it. */
    private boolean flip(int i, LivingEntity who) {
        if (doorOpen && flippedWhileOpen[i]) {
            return false;
        }
        BlockState state = world.getBlockState(levers[i]);
        if (!(state.getBlock() instanceof LeverBlock)) {
            return false;
        }
        boolean on = !state.get(LeverBlock.POWERED);
        world.setBlockState(levers[i], state.with(LeverBlock.POWERED, on), Block.NOTIFY_ALL);
        world.playSound(null, levers[i], SoundEvents.BLOCK_LEVER_CLICK, SoundCategory.BLOCKS, 0.6F, on ? 0.6F : 0.5F);
        if (doorOpen) {
            flippedWhileOpen[i] = true;
        }
        if (who instanceof ServerPlayerEntity) {
            anyoneFlipped = true;
        }
        return true;
    }

    private boolean on(int i) {
        BlockState state = world.getBlockState(levers[i]);
        return state.getBlock() instanceof LeverBlock && state.get(LeverBlock.POWERED);
    }

    private void toggleDoor(LivingEntity who) {
        if (doorOpen) {
            for (LivingEntity entity : living()) {
                if (room.contains(entity.getPos())) {
                    tell(who, "The door won't close while someone is inside.", Formatting.RED);
                    return;
                }
            }
        }
        doorOpen = !doorOpen;
        if (!doorOpen) {
            java.util.Arrays.fill(flippedWhileOpen, false);
        }
        BlockState state = world.getBlockState(door);
        if (state.getBlock() instanceof DoorBlock doorBlock) {
            doorBlock.setOpen(null, world, state, door, doorOpen);
        }
    }

    private void touch(LivingEntity who) {
        boolean lit = world.getBlockState(lamp).get(RedstoneLampBlock.LIT);
        tell(who, lit ? "The bulb is on. It's hot to the touch." : heat > 150 ? "The bulb is off... but it's still warm."
                : "The bulb is off, and cold.", Formatting.YELLOW);
    }

    private void answer(int i, LivingEntity who) {
        if (answered) {
            tell(who, "An answer has already been given.", Formatting.RED);
            return;
        }
        answered = true;
        announce(nameOf(who.getUuid()) + " answers: switch " + NAMES[i] + ".");
        if (i == right) {
            announce("Correct. GAME CLEAR.");
            for (UUID id : playing()) {
                clear(id);
            }
        } else {
            announce("Wrong. The switch was " + NAMES[right] + ". GAME OVER.");
            for (UUID id : playing()) {
                LivingEntity entity = entity(id);
                if (entity != null) {
                    electrocute(id, entity, "Wrong answer.");
                }
            }
        }
        finish();
    }

    private void strangersThink() {
        Contestant worker = solver == null ? null : contestant(solver);
        if (worker != null && !answered) {
            if (anyoneFlipped && step == 0) {
                worker.speak("Another player has started flipping the switches themselves.",
                        "You've got an idea? Go on then. I'm watching.");
                solver = null;
            } else {
                solve(worker);
            }
        }
        // As the water nears the top, somebody always panics
        if (level >= 4 && !answered && ticks % 200 == 0) {
            for (Contestant contestant : strangers()) {
                if ((contestant.nature == Contestant.Nature.TIMID || contestant.nature == Contestant.Nature.RECKLESS) && random.nextFloat() < 0.35F) {
                    contestant.speak("The water is almost at the transformer and you can't stand waiting any more.", "I can't wait any more! I'm pressing it!");
                    answer(random.nextInt(3), contestant);
                    return;
                }
            }
        }
        for (Contestant contestant : strangers()) {
            if (contestant.getUuid().equals(solver)) {
                continue;
            }
            if (contestant.quiet() && random.nextFloat() < 0.004F) {
                contestant.speak("You're waiting on the gallery while the water rises. Switches A, B, C; one lights a bulb behind the door.",
                        Lines.pick(random, "Think. Heat. Light. There's something there.", "How long until the water gets here?",
                                "Whatever you do, don't guess."));
            }
        }
    }

    /** The classic way: one switch on for a while, then off, the next one on, then go and feel the bulb. */
    private void solve(Contestant worker) {
        int wait = ticks - stepAt;
        switch (step) {
            case 0 -> {
                if (ticks > 20 * 45) {
                    worker.speak("Nobody has a plan, so you start yours: switch A on and leave it for a while, to heat the bulb.",
                            "I've got it. Everyone stay back. A goes on first, and we wait.");
                    next();
                }
            }
            case 1 -> {
                if (walkTo(worker, levers[0])) {
                    if (!on(0)) {
                        flip(0, worker);
                    }
                    next();
                }
            }
            case 2 -> {
                if (wait > 20 * 30 && walkTo(worker, levers[0])) {
                    if (on(0)) {
                        flip(0, worker);
                    }
                    next();
                }
            }
            case 3 -> {
                if (walkTo(worker, levers[1])) {
                    if (!on(1)) {
                        flip(1, worker);
                    }
                    worker.say("A off. B on. Now the door.");
                    next();
                }
            }
            case 4 -> {
                if (walkTo(worker, doorButton)) {
                    if (!doorOpen) {
                        toggleDoor(worker);
                    }
                    next();
                }
            }
            case 5 -> {
                worker.go(new BlockPos(lamp.getX(), ry, lamp.getZ()), 1.0);
                if (worker.squaredDistanceTo(Vec3d.ofCenter(lamp)) < 7.0) {
                    boolean lit = world.getBlockState(lamp).get(RedstoneLampBlock.LIT);
                    int guess = lit ? 1 : heat > 150 ? 0 : 2;
                    worker.speak("You felt the bulb: it's " + (lit ? "on" : heat > 150 ? "off but warm" : "off and cold") + ", so the answer is switch "
                            + NAMES[guess] + ".", lit ? "It's on. It's B." : heat > 150 ? "Off, but warm. It was A!" : "Off and cold. Then it's C.");
                    stepAt = ticks;
                    step = 6 + guess;
                }
            }
            default -> {
                int guess = step - 6;
                if (wait > 40 && walkTo(worker, answers[guess])) {
                    answer(guess, worker);
                }
            }
        }
    }

    private void next() {
        step++;
        stepAt = ticks;
    }

    private boolean walkTo(Contestant worker, BlockPos block) {
        BlockPos stand = new BlockPos(block.getX(), ry, block.getZ() - 1);
        worker.go(stand, 1.0);
        worker.lookAt(Vec3d.ofCenter(block));
        return worker.squaredDistanceTo(Vec3d.ofBottomCenter(stand)) < 2.2;
    }

    @Override
    protected void ended() {
        doorOpen = false;
    }

    @Override
    protected void more(ServerPlayerEntity player, Hud hud) {
        hud.line("§bWATER  §f" + "█".repeat(level) + "§7" + "█".repeat(5 - level));
        hud.line("§7Door: " + (doorOpen ? "§eOPEN" : "closed"));
    }

    // --- what a stranger can say they'll do, and then does ---------------------------------------------------

    @Override
    protected boolean followable() {
        return true;
    }

    @Override
    public Map<String, String> options(Contestant who) {
        if (answered || !isPlaying(who.getUuid())) {
            return Map.of();
        }
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 0; i < 3; i++) {
            options.put("ANSWER_" + NAMES[i], "go to the panel and give switch " + NAMES[i] + " as the one answer for everyone. Only if you're sure");
        }
        if (solver == null) {
            options.put("WORK_IT_OUT", "take charge and work the switches yourself, the careful way");
        }
        return options;
    }

    @Override
    protected Map<String, String> optionWords(Contestant who) {
        return Map.of("ANSWER_A", "\\b(answer is a|it'?s a|switch a)\\b", "ANSWER_B", "\\b(answer is b|it'?s b|switch b)\\b",
                "ANSWER_C", "\\b(answer is c|it'?s c|switch c)\\b");
    }

    @Override
    public void chose(Contestant who, String option) {
        if (answered) {
            return;
        }
        for (int i = 0; i < 3; i++) {
            if (option.equals("ANSWER_" + NAMES[i])) {
                answer(i, who);
                return;
            }
        }
        if (option.equals("WORK_IT_OUT") && solver == null) {
            solver = who.getUuid();
        }
    }

    @Override
    public String situation(Contestant who) {
        StringBuilder text = new StringBuilder("You're playing THREE SWITCHES, the four of diamonds, in a sewer reservoir. Three switches A, B, C; one "
                + "lights a bulb in the room behind the door. While the door is closed you can flip them freely; while it's open, each only once. One "
                + "answer only, on the panel. The water is rising and when it reaches the transformer everyone dies. ");
        text.append("Water level: ").append(level).append(" of 5. The door is ").append(doorOpen ? "open" : "closed").append(". Switches on: ");
        boolean any = false;
        for (int i = 0; i < 3; i++) {
            if (on(i)) {
                text.append(NAMES[i]).append(' ');
                any = true;
            }
        }
        if (!any) {
            text.append("none");
        }
        return text.toString();
    }

    /** While the rules are read: the wires are already live, and one stranger gets too close to them. */
    @Override
    protected void briefingTick(int t) {
        for (UUID id : playing()) {
            LivingEntity entity = entity(id);
            if (entity != null && wire.contains(entity.getPos())) {
                electrocute(id, entity, "You touched a live wire.");
            }
        }
        if (t % 4 == 0) {
            world.spawnParticles(ParticleTypes.ELECTRIC_SPARK, ox - 9.5, ry + 0.6, oz - 29.5, 3, 1.5, 0.3, 0.3, 0.05);
        }
        if (sacrificed || t < 200 || strangers().size() < 5) {
            return;
        }
        if (sacrificed || t < 200 || strangers().size() < 5) {
            return;
        }
        sacrificed = true;
        for (Contestant contestant : strangers()) {
            if (contestant.nature == Contestant.Nature.RECKLESS || contestant.judgment() < 0.3F) {
                contestant.go(new BlockPos(ox - 9, ry, oz - 30), 1.0);
                contestant.speak("You're curious about the black cables by the transformer while the rules are being read.", "What are these, cables? They're warm...");
                return;
            }
        }
    }
}
