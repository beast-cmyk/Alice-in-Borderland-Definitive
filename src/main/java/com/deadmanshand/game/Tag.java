package com.deadmanshand.game;

import com.deadmanshand.Builder;
import com.deadmanshand.DeadMansHand;
import com.deadmanshand.Hud;
import com.deadmanshand.Laser;
import com.deadmanshand.npc.Contestant;
import com.deadmanshand.npc.Hunter;
import com.deadmanshand.npc.Lines;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Five of Spades: a nine-storey L-shaped apartment block with three stairwells. Somewhere in it is the safe room, with
 * a spade on the wall and two buttons: two different people must press them at the same moment. You have twenty
 * minutes before the bomb goes off. "It" walks the galleries in a horse's head with a submachine gun; the mask means
 * it only sees straight ahead. If it touches you, you're dead. The ground around the building is mined.
 */
final class Tag extends Game {
    private static final int FLOORS = 9;
    private static final int HUNTERS = 2;

    private record Flat(int floor, int index, boolean north, BlockPos center, BlockPos door) {
    }

    private int cx;
    private int cz;
    private final List<Flat> flats = new ArrayList<>();
    private Flat goal;
    private BlockPos buttonA;
    private BlockPos buttonB;
    private final Map<UUID, Integer> pressedA = new HashMap<>();
    private final Map<UUID, Integer> pressedB = new HashMap<>();
    private final List<UUID> hunters = new ArrayList<>();
    private final Map<UUID, Vec3d> lastSeen = new HashMap<>();
    private final Map<UUID, UUID> chasing = new HashMap<>();
    private final Map<UUID, Integer> lostFor = new HashMap<>();
    private final Map<UUID, Integer> shotAt = new HashMap<>();
    private final Map<UUID, Set<Integer>> searched = new HashMap<>();
    private final Map<UUID, Integer> searching = new HashMap<>();
    private boolean goalKnown;
    private int ticks;
    private int countdown = -1;

    Tag(Kind kind, ServerWorld world, BlockPos origin) {
        super(kind, world, origin);
    }

    private int base(int floor) {
        return origin.getY() + 4 * floor;
    }

    @Override
    protected void plan(Builder b) {
        cx = origin.getX() - 4;
        cz = origin.getZ();
        int oy = origin.getY();
        int top = base(FLOORS);
        bounds = new Box(cx - 1, oy - 1, cz - 45, cx + 46, top + 3, cz + 2);
        entry = new Box(cx, oy, cz - 2, cx + 9, oy + 3, cz + 2);
        entryPos = new BlockPos(cx + 4, oy, cz);
        approach = new BlockPos(cx + 4, oy, cz + 7);

        BlockState wall = Blocks.LIGHT_GRAY_CONCRETE.getDefaultState();
        BlockState slab = Blocks.SMOOTH_STONE.getDefaultState();
        BlockState rail = Blocks.IRON_BARS.getDefaultState();
        BlockState air = Blocks.AIR.getDefaultState();
        // Clear the lot, lay the mined ground of the courtyard
        b.fill(cx - 1, oy, cz - 45, cx + 45, top + 3, cz + 1, air);
        b.fill(cx - 1, oy - 1, cz - 45, cx + 45, oy - 1, cz + 1, Blocks.STONE_BRICKS.getDefaultState());
        b.fill(cx + 10, oy - 1, cz - 34, cx + 45, oy - 1, cz + 1, Blocks.COARSE_DIRT.getDefaultState());
        for (int x = cx + 12; x <= cx + 43; x += 5) {
            for (int z = cz - 32; z <= cz - 1; z += 5) {
                b.set(x, oy, z, Blocks.STONE_PRESSURE_PLATE.getDefaultState());     // the mines
            }
        }
        for (int f = 0; f < FLOORS; f++) {
            int y = base(f);
            // North wing: floor, outer wall, flats, front wall, gallery, railing
            b.fill(cx, y - 1, cz - 44, cx + 44, y - 1, cz - 36, slab);
            b.fill(cx + 8, y, cz - 44, cx + 36, y + 2, cz - 44, wall);
            b.fill(cx + 8, y, cz - 38, cx + 36, y + 2, cz - 38, wall);
            for (int i = 0; i < 7; i++) {
                int wx = cx + 8 + 4 * i;
                b.fill(wx, y, cz - 43, wx, y + 2, cz - 39, wall);
                b.fill(wx + 1, y, cz - 43, wx + 3, y + 2, cz - 39, air);
                b.fill(wx + 2, y, cz - 38, wx + 2, y + 1, cz - 38, air);
                b.set(wx + 2, y + 1, cz - 44, Blocks.GLASS_PANE.getDefaultState());
                furnish(b, wx + 1, y, cz - 43, true);
                flats.add(new Flat(f, i, true, new BlockPos(wx + 2, y, cz - 41), new BlockPos(wx + 2, y, cz - 37)));
            }
            b.fill(cx + 36, y, cz - 43, cx + 36, y + 2, cz - 39, wall);
            b.fill(cx + 8, y, cz - 35, cx + 44, y, cz - 35, rail);
            // West wing
            b.fill(cx, y - 1, cz - 36, cx + 8, y - 1, cz, slab);
            b.fill(cx, y, cz - 36, cx, y + 2, cz - 8, wall);
            b.fill(cx + 6, y, cz - 36, cx + 6, y + 2, cz - 8, wall);
            for (int j = 0; j < 7; j++) {
                int wz = cz - 36 + 4 * j;
                b.fill(cx + 1, y, wz, cx + 5, y + 2, wz, wall);
                b.fill(cx + 1, y, wz + 1, cx + 5, y + 2, wz + 3, air);
                b.fill(cx + 6, y, wz + 2, cx + 6, y + 1, wz + 2, air);
                b.set(cx, y + 1, wz + 2, Blocks.GLASS_PANE.getDefaultState());
                furnish(b, cx + 1, y, wz + 1, false);
                flats.add(new Flat(f, 7 + j, false, new BlockPos(cx + 3, y, wz + 2), new BlockPos(cx + 7, y, wz + 2)));
            }
            b.fill(cx + 1, y, cz - 8, cx + 5, y + 2, cz - 8, wall);
            b.fill(cx + 9, y, cz - 35, cx + 9, y, cz - 8, rail);
            if (f > 0) {
                // the open edges beside the south stairwell
                b.fill(cx + 9, y, cz - 7, cx + 9, y, cz, rail);
                b.set(cx + 8, y, cz, rail);
            }
            // Lights on the galleries, every few doors
            for (int x = cx + 10; x <= cx + 40; x += 8) {
                b.set(x, y + 2, cz - 37, Blocks.SEA_LANTERN.getDefaultState());
            }
            for (int z = cz - 33; z <= cz - 9; z += 8) {
                b.set(cx + 8, y + 2, z, Blocks.SEA_LANTERN.getDefaultState());
            }
        }
        // The three stairwells: at the corner, at the east end and at the south end
        stairs(b, cx, cz - 44, false, false);
        stairs(b, cx + 37, cz - 44, true, false);
        stairs(b, cx, cz - 7, false, true);
        // Roof
        b.fill(cx, top - 1, cz - 44, cx + 44, top - 1, cz - 36, slab);
        b.fill(cx, top - 1, cz - 36, cx + 8, top - 1, cz, slab);
        b.fill(cx, top, cz - 44, cx + 44, top, cz - 44, wall);
        b.fill(cx, top, cz - 44, cx, top, cz, wall);
        b.sign(new BlockPos(cx + 4, oy + 2, cz + 1), Direction.SOUTH, "GAME VENUE", "", "step inside", "to register");
        b.set(cx + 2, oy + 2, cz + 2, Blocks.SEA_LANTERN.getDefaultState());
        b.set(cx + 6, oy + 2, cz + 2, Blocks.SEA_LANTERN.getDefaultState());

        // The safe room: one flat, somewhere from the second floor up
        List<Flat> candidates = new ArrayList<>();
        for (Flat flat : flats) {
            if (flat.floor >= 1) {
                candidates.add(flat);
            }
        }
        goal = candidates.get(random.nextInt(candidates.size()));
        int y = goal.center.getY();
        if (goal.north) {
            int x0 = goal.center.getX() - 1;
            buttonA = new BlockPos(x0, y + 1, cz - 42);
            buttonB = new BlockPos(x0 + 2, y + 1, cz - 40);
            b.fill(x0, y, cz - 43, x0 + 2, y + 2, cz - 39, air);
            b.sign(new BlockPos(x0 + 1, y + 1, cz - 43), Direction.SOUTH, "♠", "SAFE ZONE", "two of you", "press at once");
            b.set(buttonA, Blocks.STONE_BUTTON.getDefaultState().with(WallMountedBlock.FACE, WallMountLocation.WALL).with(net.minecraft.block.HorizontalFacingBlock.FACING, Direction.EAST));
            b.set(buttonB, Blocks.STONE_BUTTON.getDefaultState().with(WallMountedBlock.FACE, WallMountLocation.WALL).with(net.minecraft.block.HorizontalFacingBlock.FACING, Direction.WEST));
            b.set(x0 + 1, y + 2, cz - 41, Blocks.REDSTONE_LAMP.getDefaultState());
        } else {
            int z0 = goal.center.getZ() - 1;
            buttonA = new BlockPos(cx + 2, y + 1, z0);
            buttonB = new BlockPos(cx + 4, y + 1, z0 + 2);
            b.fill(cx + 1, y, z0, cx + 5, y + 2, z0 + 2, air);
            b.sign(new BlockPos(cx + 1, y + 1, z0 + 1), Direction.EAST, "♠", "SAFE ZONE", "two of you", "press at once");
            b.set(buttonA, Blocks.STONE_BUTTON.getDefaultState().with(WallMountedBlock.FACE, WallMountLocation.WALL).with(net.minecraft.block.HorizontalFacingBlock.FACING, Direction.SOUTH));
            b.set(buttonB, Blocks.STONE_BUTTON.getDefaultState().with(WallMountedBlock.FACE, WallMountLocation.WALL).with(net.minecraft.block.HorizontalFacingBlock.FACING, Direction.NORTH));
        }
    }

    /** A little furniture so there's somewhere to hide: a bed, a table, a wardrobe. */
    private void furnish(Builder b, int x0, int y, int z0, boolean north) {
        if (random.nextFloat() < 0.6F) {
            b.set(north ? x0 : x0 + 4, y, north ? z0 : z0 + 2, Blocks.OAK_PLANKS.getDefaultState());
            b.set(north ? x0 : x0 + 4, y + 1, north ? z0 : z0 + 2, Blocks.BOOKSHELF.getDefaultState());
        }
        if (random.nextFloat() < 0.5F) {
            b.set(north ? x0 + 2 : x0 + 2, y, north ? z0 + 3 : z0, Blocks.WHITE_WOOL.getDefaultState());
        }
    }

    /**
     * A stairwell of 8 x 8 inside, NW corner (sx, sz): two short flights per floor with a landing halfway; you walk on
     * the floor's landing along the south side and the strip along the east side. Solid on the west and north; bars
     * on the east and south where that side faces the outside.
     */
    private void stairs(Builder b, int sx, int sz, boolean eastOutside, boolean southOutside) {
        BlockState air = Blocks.AIR.getDefaultState();
        BlockState wall = Blocks.GRAY_CONCRETE.getDefaultState();
        BlockState floor = Blocks.SMOOTH_STONE.getDefaultState();
        BlockState step = Blocks.STONE_BRICK_STAIRS.getDefaultState();
        int top = base(FLOORS);
        b.fill(sx, base(0), sz, sx + 7, top + 1, sz + 7, air);
        b.fill(sx, base(0), sz, sx, top, sz + 7, wall);
        b.fill(sx, base(0), sz, sx + 7, top, sz, wall);
        for (int f = 0; f < FLOORS; f++) {
            int y = base(f);
            b.fill(sx + 1, y - 1, sz + 6, sx + 7, y - 1, sz + 7, floor);
            b.fill(sx + 6, y - 1, sz + 1, sx + 7, y - 1, sz + 7, floor);
            if (eastOutside) {
                b.fill(sx + 7, y, sz + 1, sx + 7, y, sz + 7, Blocks.IRON_BARS.getDefaultState());
            }
            if (southOutside && f > 0) {
                b.fill(sx + 1, y, sz + 7, sx + 7, y, sz + 7, Blocks.IRON_BARS.getDefaultState());
            }
            if (f == FLOORS - 1) {
                b.fill(sx + 1, y + 3, sz + 1, sx + 7, y + 3, sz + 7, floor);
                continue;
            }
            // up the west lane, northwards
            for (int x = sx + 1; x <= sx + 2; x++) {
                b.set(x, y, sz + 5, step.with(StairsBlock.FACING, Direction.NORTH));
                b.set(x, y, sz + 4, wall);
                b.set(x, y + 1, sz + 4, step.with(StairsBlock.FACING, Direction.NORTH));
            }
            // the landing halfway up
            b.fill(sx + 1, y + 1, sz + 1, sx + 5, y + 1, sz + 3, floor);
            // on up the east lane, southwards
            for (int x = sx + 4; x <= sx + 5; x++) {
                b.set(x, y + 2, sz + 4, step.with(StairsBlock.FACING, Direction.SOUTH));
                b.set(x, y + 2, sz + 5, wall);
                b.set(x, y + 3, sz + 5, step.with(StairsBlock.FACING, Direction.SOUTH));
            }
            b.fill(sx + 3, y, sz + 4, sx + 3, y + 3, sz + 5, wall);
            b.set(sx + 6, y + 2, sz + 1, Blocks.SEA_LANTERN.getDefaultState());
        }
    }

    @Override
    protected String[] rules() {
        return new String[]{
                "Somewhere in this building is the safe zone. Find it before the time limit.",
                "In the safe zone, two different people must press its two buttons at the same moment.",
                "\"It\" is hunting you. If it touches you, you lose.",
                "When the time limit runs out, a bomb in the building goes off.",
                "The grounds around the building are mined. Time limit: twenty minutes."};
    }

    @Override
    protected int timeLimit() {
        return 20 * 60 * 20;
    }

    @Override
    protected void arrange() {
        int i = 0;
        for (LivingEntity entity : living()) {
            entity.teleport(cx + 2 + i % 5, origin.getY(), cz - 1 - i / 5 * 0.5);
            if (entity instanceof Contestant contestant) {
                contestant.hold();
            }
            i++;
        }
        for (int h = 0; h < HUNTERS; h++) {
            Hunter hunter = DeadMansHand.HUNTER.create(world);
            if (hunter == null) {
                continue;
            }
            BlockPos at = h == 0 ? new BlockPos(cx + 40, base(4), cz - 37) : new BlockPos(cx + 7, base(6), cz - 20);
            hunter.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, random.nextFloat() * 360.0F, 0.0F);
            hunter.addScoreboardTag(DeadMansHand.ID);
            world.spawnEntity(hunter);
            hunter.hold();
            hunters.add(hunter.getUuid());
        }
    }

    @Override
    protected void begin() {
        for (Contestant contestant : strangers()) {
            contestant.stopAll();
            searched.put(contestant.getUuid(), new HashSet<>());
        }
        for (Hunter hunter : hunterList()) {
            hunter.stopAll();
        }
        Contestant first = strangers().isEmpty() ? null : strangers().get(0);
        if (first != null) {
            first.speak("The game just started. Something in a horse mask is walking the galleries above you with a gun.",
                    Lines.pick(random, "Split up. Whoever finds it, shout.", "It's wearing a horse's head. A horse's head with a gun.", "Stay low. Don't let it see you."));
        }
        world.playSound(null, cx + 22, base(4), cz - 22, SoundEvents.ENTITY_HORSE_ANGRY, SoundCategory.HOSTILE, 3.0F, 0.6F);
    }

    private List<Hunter> hunterList() {
        List<Hunter> list = new ArrayList<>();
        for (UUID id : hunters) {
            if (world.getEntity(id) instanceof Hunter hunter && hunter.isAlive()) {
                list.add(hunter);
            }
        }
        return list;
    }

    @Override
    protected void running() {
        ticks++;
        // The mines
        for (UUID id : playing()) {
            LivingEntity entity = entity(id);
            if (entity != null && entity.getX() > cx + 9.6 && entity.getZ() > cz - 34.6 && entity.getY() < origin.getY() + 1.0) {
                world.spawnParticles(ParticleTypes.EXPLOSION, entity.getX(), entity.getY(), entity.getZ(), 2, 0.3, 0.3, 0.3, 0.0);
                world.playSound(null, entity.getX(), entity.getY(), entity.getZ(), SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 1.5F, 1.0F);
                fail(id, "You stepped on a mine.");
            }
        }
        for (Hunter hunter : hunterList()) {
            hunt(hunter);
        }
        strangersThink();
        if (countdown > 0 && --countdown == 0) {
            pressTogether();
        }
        if (timeLeft == 20 * 60 || timeLeft == 20 * 60 * 5 || timeLeft == 20 * 30) {
            announce(timeLeft == 20 * 30 ? "Thirty seconds." : timeLeft / (20 * 60) + (timeLeft == 20 * 60 ? " minute left." : " minutes left."));
        }
    }

    private void hunt(Hunter hunter) {
        UUID id = hunter.getUuid();
        LivingEntity target = null;
        UUID targetId = chasing.get(id);
        if (targetId != null) {
            LivingEntity known = entity(targetId);
            target = known != null && known.isAlive() && isPlaying(targetId) ? known : null;
        }
        if (ticks % 5 == 0) {
            for (LivingEntity prey : living()) {
                if (hunter.sees(prey) && (target == null || hunter.squaredDistanceTo(prey) < hunter.squaredDistanceTo(target))) {
                    target = prey;
                }
            }
        }
        if (target != null && hunter.sees(target)) {
            chasing.put(id, target.getUuid());
            lastSeen.put(id, target.getPos());
            lostFor.put(id, 0);
            hunter.look(target);
            hunter.go(target.getPos(), 1.25);
            double distance = hunter.distanceTo(target);
            if (distance < 1.4) {
                fail(target.getUuid(), "It touched you. You're it no longer: you're out.");
                world.playSound(null, hunter.getX(), hunter.getY(), hunter.getZ(), SoundEvents.ENTITY_HORSE_DEATH, SoundCategory.HOSTILE, 1.5F, 0.6F);
            } else if (distance < 18.0 && ticks - shotAt.getOrDefault(id, -100) > 22) {
                shotAt.put(id, ticks);
                hunter.shoot(target, random);
                hunter.shoot(target, random);
            }
            return;
        }
        int lost = lostFor.merge(id, 1, Integer::sum);
        Vec3d last = lastSeen.get(id);
        if (last != null && lost < 80) {
            hunter.look(null);
            hunter.go(last, 1.0);
            if (hunter.squaredDistanceTo(last) < 2.0) {
                lastSeen.remove(id);
            }
            return;
        }
        chasing.remove(id);
        // Patrol: walk to a door on a floor near its own
        if (hunter.arrived(1.5) || ticks % 400 == 0) {
            int floor = Math.max(0, Math.min(FLOORS - 1, (int) ((hunter.getY() - origin.getY()) / 4) + random.nextInt(3) - 1));
            List<Flat> choices = new ArrayList<>();
            for (Flat flat : flats) {
                if (flat.floor == floor) {
                    choices.add(flat);
                }
            }
            Flat next = choices.get(random.nextInt(choices.size()));
            hunter.look(null);
            hunter.go(next.door, 0.9);
        }
    }

    private void strangersThink() {
        List<Hunter> list = hunterList();
        for (Contestant contestant : strangers()) {
            UUID id = contestant.getUuid();
            // Run from it if it's close and coming
            Hunter danger = null;
            for (Hunter hunter : list) {
                if (hunter.squaredDistanceTo(contestant) < 12.0 * 12.0 && hunter.canSee(contestant)) {
                    danger = hunter;
                }
            }
            if (danger != null && !atButton(contestant)) {
                if (contestant.quiet() && random.nextFloat() < 0.05F) {
                    contestant.speak("The thing in the horse mask is right there, close, looking your way.",
                            Lines.pick(random, "It's here! Run!", "Don't look at it. Just go!", "Oh god, it's coming up the stairs."));
                }
                Flat away = farthestFlat(contestant, danger);
                contestant.go(away.center, 1.35);
                searching.remove(id);
                continue;
            }
            if (goalKnown || contestant.squaredDistanceTo(Vec3d.ofCenter(goal.center)) < 9.0) {
                if (!goalKnown) {
                    goalKnown = true;
                    announceFind(contestant);
                }
                BlockPos stand = freeButton(contestant);
                contestant.go(stand, 1.1);
                contestant.lookAt(Vec3d.ofCenter(goal.door));
                continue;
            }
            Integer target = searching.get(id);
            if (target == null || contestant.arrived(1.2)) {
                if (target != null) {
                    searched.computeIfAbsent(id, k -> new HashSet<>()).add(target);
                }
                Flat next = nextFlat(contestant);
                searching.put(id, flats.indexOf(next));
                contestant.go(next.center, 1.0);
            }
        }
        // Two strangers at the buttons count down and press together
        if (countdown < 0 && strangerAt(buttonA) != null && strangerAt(buttonB) != null) {
            countdown = 60;
            Contestant one = strangerAt(buttonA);
            if (one != null) {
                one.say("On three. One... two...");
            }
        }
    }

    private void announceFind(Contestant finder) {
        finder.speak("You found the safe zone: floor " + (goal.floor + 1) + ", the room with the spade and two buttons. Shout it to everyone.",
                "It's here! Floor " + (goal.floor + 1) + "! The spade room! I need someone on the other button!");
        for (Contestant other : strangers()) {
            other.remember(finder.name() + " found the safe zone on floor " + (goal.floor + 1) + ".");
        }
    }

    private boolean atButton(Contestant contestant) {
        return contestant.squaredDistanceTo(Vec3d.ofCenter(buttonA)) < 3.0 || contestant.squaredDistanceTo(Vec3d.ofCenter(buttonB)) < 3.0;
    }

    private Contestant strangerAt(BlockPos button) {
        for (Contestant contestant : strangers()) {
            if (contestant.squaredDistanceTo(Vec3d.ofCenter(button)) < 3.0) {
                return contestant;
            }
        }
        return null;
    }

    private BlockPos freeButton(Contestant contestant) {
        Contestant atA = strangerAt(buttonA);
        Contestant atB = strangerAt(buttonB);
        BlockPos a = standBy(buttonA);
        BlockPos b = standBy(buttonB);
        if (atA == contestant) {
            return a;
        }
        if (atB == contestant) {
            return b;
        }
        if (atA == null) {
            return a;
        }
        return atB == null ? b : goal.center;
    }

    private BlockPos standBy(BlockPos button) {
        return new BlockPos(button.getX(), button.getY() - 1, button.getZ()).offset(facingOf(button));
    }

    private Direction facingOf(BlockPos button) {
        BlockState state = world.getBlockState(button);
        return state.contains(net.minecraft.block.HorizontalFacingBlock.FACING) ? state.get(net.minecraft.block.HorizontalFacingBlock.FACING) : Direction.UP;
    }

    private Flat nextFlat(Contestant contestant) {
        Set<Integer> done = searched.computeIfAbsent(contestant.getUuid(), k -> new HashSet<>());
        int floor = (int) ((contestant.getY() - origin.getY()) / 4);
        Flat best = null;
        double bestScore = Double.MAX_VALUE;
        for (int i = 0; i < flats.size(); i++) {
            if (done.contains(i)) {
                continue;
            }
            Flat flat = flats.get(i);
            double score = contestant.squaredDistanceTo(Vec3d.ofCenter(flat.center)) + Math.abs(flat.floor - floor) * 120.0 + random.nextInt(200);
            if (score < bestScore) {
                best = flat;
                bestScore = score;
            }
        }
        return best != null ? best : flats.get(random.nextInt(flats.size()));
    }

    private Flat farthestFlat(Contestant contestant, Hunter hunter) {
        int floor = (int) ((contestant.getY() - origin.getY()) / 4);
        Flat best = flats.get(0);
        double bestDistance = -1.0;
        for (Flat flat : flats) {
            if (Math.abs(flat.floor - floor) > 1) {
                continue;
            }
            double distance = hunter.squaredDistanceTo(Vec3d.ofCenter(flat.center)) - contestant.squaredDistanceTo(Vec3d.ofCenter(flat.center)) * 0.5;
            if (distance > bestDistance) {
                best = flat;
                bestDistance = distance;
            }
        }
        return best;
    }

    @Override
    protected ActionResult use(ServerPlayerEntity player, BlockPos pos, BlockState state) {
        if (phase != Phase.RUNNING || !isPlaying(player.getUuid())) {
            return ActionResult.PASS;
        }
        if (pos.equals(buttonA) || pos.equals(buttonB)) {
            boolean a = pos.equals(buttonA);
            (a ? pressedA : pressedB).put(player.getUuid(), ticks);
            if (!goalKnown) {
                goalKnown = true;
            }
            // A stranger waiting at the other button presses with you
            Contestant partner = strangerAt(a ? buttonB : buttonA);
            if (partner != null) {
                (a ? pressedB : pressedA).put(partner.getUuid(), ticks);
                partner.swingHand(net.minecraft.util.Hand.MAIN_HAND);
                partner.say(Lines.pick(random, "Now!", "Together!", "Pressing!"));
            }
            check();
        }
        return ActionResult.PASS;
    }

    private void pressTogether() {
        Contestant a = strangerAt(buttonA);
        Contestant b = strangerAt(buttonB);
        if (a == null || b == null) {
            return;
        }
        a.say("Three!");
        pressedA.put(a.getUuid(), ticks);
        pressedB.put(b.getUuid(), ticks);
        a.swingHand(net.minecraft.util.Hand.MAIN_HAND);
        b.swingHand(net.minecraft.util.Hand.MAIN_HAND);
        check();
    }

    /** Both buttons pressed within a quarter of a second, by two different people: the bomb is off. */
    private void check() {
        for (Map.Entry<UUID, Integer> one : pressedA.entrySet()) {
            for (Map.Entry<UUID, Integer> two : pressedB.entrySet()) {
                if (!one.getKey().equals(two.getKey()) && Math.abs(one.getValue() - two.getValue()) <= 5 && ticks - one.getValue() <= 5) {
                    cleared();
                    return;
                }
            }
        }
    }

    private void cleared() {
        announce("The safe zone has been reached. The bomb is disarmed. GAME CLEAR.");
        for (UUID id : playing()) {
            clear(id);
        }
        for (Hunter hunter : hunterList()) {
            Laser.strike(hunter, null);     // its collar
        }
        finish();
    }

    @Override
    protected void timeUp() {
        announce("Time's up.");
        for (int i = 0; i < 12; i++) {
            world.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, cx + random.nextInt(45), base(random.nextInt(FLOORS)), cz - random.nextInt(45), 1, 0.0, 0.0, 0.0, 0.0);
        }
        world.playSound(null, cx + 22, base(4), cz - 22, SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 6.0F, 0.5F);
        super.timeUp();
    }

    @Override
    protected void ended() {
        for (Hunter hunter : hunterList()) {
            hunter.discard();
        }
        hunters.clear();
    }

    @Override
    protected void heard(ServerPlayerEntity player, String message) {
        String lower = message.toLowerCase();
        if (lower.matches(".*\\b(found|here|safe|button|spade)\\b.*") && player.squaredDistanceTo(Vec3d.ofCenter(goal.center)) < 16.0) {
            goalKnown = true;
            for (Contestant contestant : strangers()) {
                contestant.remember(player.getEntityName() + " shouted that they found the safe zone on floor " + (goal.floor + 1) + ".");
            }
        }
    }

    @Override
    protected void more(ServerPlayerEntity player, Hud hud) {
        int floor = (int) Math.floor((player.getY() - origin.getY()) / 4.0) + 1;
        hud.line("§7FLOOR " + Math.max(1, Math.min(FLOORS, floor)));
        if (goalKnown) {
            hud.line("§eSafe zone: floor " + (goal.floor + 1));
        }
    }

    // --- what a stranger can say they'll do, and then does ---------------------------------------------------

    @Override
    protected boolean followable() {
        return true;
    }

    @Override
    public String situation(Contestant who) {
        int floor = (int) ((who.getY() - origin.getY()) / 4) + 1;
        StringBuilder text = new StringBuilder("You're playing TAG, the five of spades, in a nine-storey apartment block. Somewhere in it is a safe "
                + "room with a spade on the wall; two people must press its two buttons at the same moment before the bomb goes off. Something "
                + "in a horse's head with a submachine gun is hunting you; if it touches you, you die. The mask means it only sees straight "
                + "ahead. You're on floor " + floor + ". " + timeLeft / 20 / 60 + " minutes left.");
        if (goalKnown) {
            text.append(" The safe room is on floor ").append(goal.floor + 1).append('.');
        }
        return text.toString();
    }

    @Override
    protected void died(UUID id) {
        searching.remove(id);
        for (Contestant contestant : strangers()) {
            if (contestant.quiet() && random.nextFloat() < 0.3F) {
                contestant.speak(nameOf(id) + " was just killed by the thing in the horse mask.",
                        Lines.pick(random, "It got " + nameOf(id) + ". It got them.", "Keep moving. Don't stop.", "That could have been me."));
                break;
            }
        }
    }
}
