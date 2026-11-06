package com.deadmanshand.game;

import com.deadmanshand.Builder;
import com.deadmanshand.DeadMansHand;
import com.deadmanshand.Hud;
import com.deadmanshand.npc.Contestant;
import com.deadmanshand.npc.Lines;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LightBlock;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
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
 * Ten of Clubs, in a match factory: twenty-five huge rooms in a five-by-five grid, all of them pitch dark. The bingo
 * card is the grid itself. Strike a match in a room and you can read the number painted on its walls, and that number
 * goes on everyone's card in the room's square; the middle is free. A row, a column or a diagonal, and everyone still
 * alive clears it. Each of you has nine matches, and when your last one goes out, so do you.
 */
final class Bingo extends Game {
    private static final int MATCHES = 9;
    private static final int BURN = 20 * 10;
    private static final int ROOM = 7;
    /** Each room has walls of its own, so its number is painted on its own side of a double wall. */
    private static final int PITCH = ROOM + 2;
    private static final int HIGH = 7;

    private int gx0;
    private int gz0;
    private int oy;
    private final int[][] numbers = new int[5][5];
    private final boolean[][] found = new boolean[5][5];
    /** Matches burning now: whose, and how long they have left. */
    private final Map<UUID, Integer> burning = new HashMap<>();
    /** Where each burning match's light is. */
    private final Map<UUID, BlockPos> lights = new HashMap<>();
    /** The strangers' matches (players carry theirs as items). */
    private final Map<UUID, Integer> strangerMatches = new HashMap<>();
    private final Map<UUID, Integer> nextStrike = new HashMap<>();
    private BlockPos lobbyDoor;
    private int ticks;

    Bingo(Kind kind, ServerWorld world, BlockPos origin) {
        super(kind, world, origin);
    }

    @Override
    protected void plan(Builder b) {
        int ox = origin.getX();
        oy = origin.getY();
        int oz = origin.getZ();
        gx0 = ox - 22;
        gz0 = oz - 47;
        int gx1 = gx0 + 5 * PITCH - 1;
        int gz1 = gz0 + 5 * PITCH - 1;
        bounds = new Box(gx0 - 1, oy - 2, gz0 - 1, gx1 + 2, oy + HIGH + 3, oz + 4);
        entry = new Box(ox - 4, oy, oz - 2, ox + 5, oy + 3, oz + 2);
        entryPos = new BlockPos(ox, oy, oz);
        approach = new BlockPos(ox, oy, oz + 7);
        lobbyDoor = new BlockPos(ox, oy, gz1);

        // The numbers: bingo columns, 1-15 under B, 16-30 under I and so on; the middle is free
        for (int c = 0; c < 5; c++) {
            List<Integer> pool = new ArrayList<>();
            for (int n = 15 * c + 1; n <= 15 * c + 15; n++) {
                pool.add(n);
            }
            for (int r = 0; r < 5; r++) {
                numbers[r][c] = pool.remove(random.nextInt(pool.size()));
            }
        }
        numbers[2][2] = 0;

        BlockState dark = Blocks.BLACK_CONCRETE.getDefaultState();
        BlockState brick = Blocks.BRICKS.getDefaultState();
        BlockState paint = Blocks.WHITE_CONCRETE.getDefaultState();
        BlockState air = Blocks.AIR.getDefaultState();
        // The grid: dark walls inside, brick outside, a black ceiling
        b.fill(gx0, oy - 1, gz0, gx1, oy + HIGH, gz1, dark);
        b.fill(gx0, oy - 1, gz0, gx1, oy - 1, gz1, Blocks.GRAY_CONCRETE.getDefaultState());
        b.walls(gx0, oy, gz0, gx1, oy + HIGH, gz1, brick);
        b.fill(gx0, oy + HIGH + 1, gz0, gx1, oy + HIGH + 1, gz1, Blocks.BRICK_SLAB.getDefaultState());
        // an outer skin of brick, so the numbers can't be read from the street
        b.fill(gx0 - 1, oy - 1, gz0 - 1, gx0 - 1, oy + HIGH, gz1, brick);
        b.fill(gx1 + 1, oy - 1, gz0 - 1, gx1 + 1, oy + HIGH, gz1, brick);
        b.fill(gx0 - 1, oy - 1, gz0 - 1, gx1 + 1, oy + HIGH, gz0 - 1, brick);
        b.fill(gx0 - 1, oy - 1, gz1 + 1, gx0 + 16, oy + HIGH, gz1 + 1, brick);
        b.fill(gx0 + 28, oy - 1, gz1 + 1, gx1 + 1, oy + HIGH, gz1 + 1, brick);
        for (int r = 0; r < 5; r++) {
            for (int c = 0; c < 5; c++) {
                int x0 = gx0 + 1 + PITCH * c;
                int z0 = gz0 + 1 + PITCH * r;
                b.fill(x0, oy, z0, x0 + ROOM - 1, oy + HIGH - 1, z0 + ROOM - 1, air);
                // doorways through the double walls to the east and south neighbours
                if (c < 4) {
                    b.fill(x0 + ROOM, oy, z0 + 3, x0 + ROOM + 1, oy + 1, z0 + 3, air);
                }
                if (r < 4) {
                    b.fill(x0 + 3, oy, z0 + ROOM, x0 + 3, oy + 1, z0 + ROOM + 1, air);
                }
            }
        }
        // The number, painted big on all four walls of every room, above the doorways
        for (int r = 0; r < 5; r++) {
            for (int c = 0; c < 5; c++) {
                if (numbers[r][c] > 0) {
                    paintNumber(b, r, c, paint);
                }
            }
        }
        // The lobby on the street, with the door into the grid and the big card on the wall
        b.fill(ox - 5, oy - 1, gz1 + 1, ox + 5, oy + HIGH, oz + 2, brick);
        b.fill(ox - 4, oy, gz1 + 1, ox + 4, oy + HIGH - 1, oz + 1, air);
        b.fill(ox - 1, oy, oz + 2, ox + 1, oy + 2, oz + 2, air);
        b.fill(ox - 4, oy - 1, gz1 + 1, ox + 4, oy - 1, oz + 1, Blocks.SMOOTH_STONE.getDefaultState());
        b.fill(lobbyDoor.getX(), oy, lobbyDoor.getZ(), lobbyDoor.getX(), oy + 1, lobbyDoor.getZ(), air);
        b.set(ox - 3, oy + 3, oz + 1, Blocks.LANTERN.getDefaultState());
        b.set(ox + 3, oy + 3, oz + 1, Blocks.LANTERN.getDefaultState());
        b.set(ox, oy + HIGH - 1, oz, Blocks.SEA_LANTERN.getDefaultState());
        b.sign(new BlockPos(ox, oy + 3, oz + 3), Direction.SOUTH, "GAME VENUE", "", "step inside", "to register");
        for (int r = 0; r < 5; r++) {
            for (int c = 0; c < 5; c++) {
                b.sign(boardSign(r, c), Direction.SOUTH, "", r == 2 && c == 2 ? "FREE" : "?", "", "");
            }
        }
        // A chimney, so it looks like what it is
        int cx = gx1 - 4;
        int cz = gz0 + 4;
        b.fill(cx - 1, oy + HIGH + 1, cz - 1, cx + 1, oy + HIGH + 14, cz + 1, brick);
        b.fill(cx, oy + HIGH + 2, cz, cx, oy + HIGH + 14, cz, air);
    }

    private BlockPos boardSign(int r, int c) {
        return new BlockPos(origin.getX() - 2 + c, oy + 6 - r, gz0 + 5 * PITCH);
    }

    private void paintNumber(Builder b, int r, int c, BlockState paint) {
        String text = String.valueOf(numbers[r][c]);
        int x0 = gx0 + 1 + PITCH * c;
        int z0 = gz0 + 1 + PITCH * r;
        int width = text.length() * 4 - 1;
        int start = (ROOM - width) / 2;
        for (int k = 0; k < text.length(); k++) {
            String[] glyph = Glyphs.of(text.charAt(k));
            for (int row = 0; row < 5; row++) {
                for (int col = 0; col < 3; col++) {
                    if (glyph[row].charAt(col) != '#') {
                        continue;
                    }
                    int along = start + k * 4 + col;
                    int y = oy + 6 - row;
                    // north wall (read facing north: west to east), south wall (facing south: east to west)
                    b.set(x0 + along, y, z0 - 1, paint);
                    b.set(x0 + ROOM - 1 - along, y, z0 + ROOM, paint);
                    // west wall (facing west: south to north), east wall (facing east: north to south)
                    b.set(x0 - 1, y, z0 + ROOM - 1 - along, paint);
                    b.set(x0 + ROOM, y, z0 + along, paint);
                }
            }
        }
    }

    /** The room (row, column) whose inside {@code pos} is in, or null in a wall or doorway. */
    private int[] roomAt(Vec3d pos) {
        int lx = (int) Math.floor(pos.x) - gx0;
        int lz = (int) Math.floor(pos.z) - gz0;
        if (lx < 0 || lz < 0 || lx >= 5 * PITCH || lz >= 5 * PITCH) {
            return null;
        }
        int inX = lx % PITCH;
        int inZ = lz % PITCH;
        if (inX == 0 || inX == PITCH - 1 || inZ == 0 || inZ == PITCH - 1) {
            return null;
        }
        return new int[]{lz / PITCH, lx / PITCH};
    }

    private BlockPos centre(int r, int c) {
        return new BlockPos(gx0 + 4 + PITCH * c, oy, gz0 + 4 + PITCH * r);
    }

    private boolean inGrid(Vec3d pos) {
        return pos.x >= gx0 && pos.x < gx0 + 5 * PITCH && pos.z >= gz0 && pos.z < gz0 + 5 * PITCH && pos.y < oy + HIGH + 1;
    }

    @Override
    protected String[] rules() {
        return new String[]{
                "Each of you has nine matches.",
                "The rooms are dark. Light a match and you can read the room's number. It goes on the bingo card.",
                "The card is the grid of rooms. The middle square is free.",
                "Complete a row, a column or a diagonal and everyone left clears the game.",
                "When your last match goes out, it's game over for you."};
    }

    @Override
    protected int timeLimit() {
        return 20 * 60 * 30;
    }

    @Override
    protected void arrange() {
        world.setBlockState(lobbyDoor, Blocks.BRICKS.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(lobbyDoor.up(), Blocks.BRICKS.getDefaultState(), Block.NOTIFY_ALL);
        List<int[]> rooms = new ArrayList<>();
        for (int r = 0; r < 5; r++) {
            for (int c = 0; c < 5; c++) {
                if (r != 2 || c != 2) {
                    rooms.add(new int[]{r, c});
                }
            }
        }
        int i = 0;
        for (LivingEntity entity : living()) {
            int[] room = rooms.get(random.nextInt(rooms.size()));
            BlockPos at = centre(room[0], room[1]);
            entity.teleport(at.getX() + 0.5 + (i % 3) - 1, oy, at.getZ() + 0.5 + (i / 3 % 3) - 1);
            if (entity instanceof ServerPlayerEntity player) {
                player.getInventory().offerOrDrop(new ItemStack(DeadMansHand.MATCH, MATCHES));
            } else if (entity instanceof Contestant contestant) {
                strangerMatches.put(contestant.getUuid(), MATCHES);
                contestant.hold();
            }
            i++;
        }
    }

    @Override
    protected void begin() {
        for (Contestant contestant : strangers()) {
            contestant.stopAll();
            nextStrike.put(contestant.getUuid(), 20 * (3 + random.nextInt(12)));
        }
        Contestant first = strangers().isEmpty() ? null : strangers().get(0);
        if (first != null) {
            first.speak("The bingo game has started: you're in a pitch-dark room of the match factory with nine matches.",
                    Lines.pick(random, "I can't see a thing.", "Don't waste them. Nine matches. Nine.", "Hello? Is anyone in here?"));
        }
    }

    @Override
    protected boolean usedItem(ServerPlayerEntity player, ItemStack stack) {
        if (!stack.isOf(DeadMansHand.MATCH) || phase != Phase.RUNNING) {
            return false;
        }
        if (burning.containsKey(player.getUuid())) {
            tell(player, "Your match is still burning.", Formatting.GRAY);
            return true;
        }
        stack.decrement(1);
        strike(player);
        return true;
    }

    /** {@code who} strikes a match: light, and the number of the room they're in. */
    private void strike(LivingEntity who) {
        burning.put(who.getUuid(), BURN);
        world.playSound(null, who.getX(), who.getY(), who.getZ(), SoundEvents.ITEM_FLINTANDSTEEL_USE, SoundCategory.PLAYERS, 1.0F, 1.2F);
        int[] room = roomAt(who.getPos());
        if (room == null) {
            tell(who, "You light a match in a doorway. There's no number here: you're between rooms.", Formatting.GRAY);
            return;
        }
        int r = room[0];
        int c = room[1];
        if (r == 2 && c == 2) {
            tell(who, "This room's walls are bare: it's the free square, the middle of the card.", Formatting.YELLOW);
            return;
        }
        if (found[r][c]) {
            tell(who, "This room is " + numbers[r][c] + ". It's already on the card.", Formatting.GRAY);
            return;
        }
        found[r][c] = true;
        announce("A number has been found: " + numbers[r][c] + ".");
        if (world.getBlockEntity(boardSign(r, c)) != null) {
            Builder.write(world, boardSign(r, c), "", String.valueOf(numbers[r][c]), "", "");
        }
        if (who instanceof Contestant contestant) {
            contestant.say(Lines.pick(random, "It's " + numbers[r][c] + "!", "This one's " + numbers[r][c] + ".",
                    numbers[r][c] + "! Got one!"));
        }
        if (bingo()) {
            sound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0F);
            announce("BINGO.");
            for (UUID id : playing()) {
                clear(id);
            }
            finish();
        }
    }

    private boolean bingo() {
        boolean d1 = true;
        boolean d2 = true;
        for (int i = 0; i < 5; i++) {
            boolean row = true;
            boolean col = true;
            for (int j = 0; j < 5; j++) {
                row &= marked(i, j);
                col &= marked(j, i);
            }
            if (row || col) {
                return true;
            }
            d1 &= marked(i, i);
            d2 &= marked(i, 4 - i);
        }
        return d1 || d2;
    }

    private boolean marked(int r, int c) {
        return (r == 2 && c == 2) || found[r][c];
    }

    @Override
    protected void running() {
        ticks++;
        // The matches burn down, and their light goes with whoever holds them
        for (UUID id : new ArrayList<>(burning.keySet())) {
            int left = burning.get(id) - 1;
            LivingEntity holder = entity(id);
            if (left <= 0 || holder == null || !holder.isAlive()) {
                burning.remove(id);
                douse(id);
                if (holder != null && holder.isAlive() && isPlaying(id) && matchesLeft(holder) <= 0) {
                    fail(id, "Your last match has gone out.");
                }
                continue;
            }
            burning.put(id, left);
            follow(id, holder);
            if (ticks % 4 == 0) {
                world.spawnParticles(ParticleTypes.FLAME, holder.getX(), holder.getEyeY() - 0.4, holder.getZ(), 1, 0.05, 0.02, 0.05, 0.0);
            }
        }
        // Everyone else is in the dark
        if (ticks % 10 == 0) {
            for (UUID id : playing()) {
                ServerPlayerEntity player = player(id);
                if (player == null) {
                    continue;
                }
                if (inGrid(player.getPos()) && !nearLight(player)) {
                    player.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, 40, 0, false, false));
                } else {
                    player.removeStatusEffect(StatusEffects.BLINDNESS);
                }
            }
        }
        strangersThink();
    }

    private int matchesLeft(LivingEntity who) {
        if (who instanceof ServerPlayerEntity player) {
            int count = 0;
            for (int i = 0; i < player.getInventory().size(); i++) {
                ItemStack stack = player.getInventory().getStack(i);
                if (stack.isOf(DeadMansHand.MATCH)) {
                    count += stack.getCount();
                }
            }
            return count;
        }
        return strangerMatches.getOrDefault(who.getUuid(), 0);
    }

    private boolean nearLight(LivingEntity who) {
        for (UUID id : burning.keySet()) {
            LivingEntity holder = entity(id);
            if (holder != null && holder.squaredDistanceTo(who) < 6.0 * 6.0) {
                return true;
            }
        }
        return false;
    }

    private void follow(UUID id, LivingEntity holder) {
        BlockPos at = new BlockPos(holder.getEyePos());
        BlockPos was = lights.get(id);
        if (at.equals(was)) {
            return;
        }
        douse(id);
        if (world.isAir(at)) {
            world.setBlockState(at, Blocks.LIGHT.getDefaultState().with(LightBlock.LEVEL_15, 12), Block.NOTIFY_LISTENERS);
            lights.put(id, at);
        }
    }

    private void douse(UUID id) {
        BlockPos was = lights.remove(id);
        if (was != null && world.getBlockState(was).isOf(Blocks.LIGHT)) {
            world.setBlockState(was, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
        }
    }

    private void strangersThink() {
        for (Contestant contestant : strangers()) {
            UUID id = contestant.getUuid();
            int[] room = roomAt(contestant.getPos());
            // Feel your way to the next room
            if (contestant.arrived(1.5)) {
                int[] here = room != null ? room : new int[]{2, 2};
                List<int[]> next = new ArrayList<>();
                int[][] steps = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
                for (int[] s : steps) {
                    int r = here[0] + s[0];
                    int c = here[1] + s[1];
                    if (r >= 0 && r < 5 && c >= 0 && c < 5) {
                        // the sharp ones keep count of the doors and head for rooms nobody has lit
                        if (contestant.judgment() < 0.6F || !found[r][c] || random.nextFloat() < 0.25F) {
                            next.add(new int[]{r, c});
                        }
                    }
                }
                if (next.isEmpty()) {
                    next.add(new int[]{Math.min(4, here[0] + 1), here[1]});
                }
                int[] go = next.get(random.nextInt(next.size()));
                contestant.go(centre(go[0], go[1]), 0.55);
            }
            int wait = nextStrike.merge(id, -1, Integer::sum);
            int left = strangerMatches.getOrDefault(id, 0);
            if (wait > 0 || left <= 0 || burning.containsKey(id) || room == null) {
                continue;
            }
            boolean worth = !found[room[0]][room[1]] && (room[0] != 2 || room[1] != 2);
            boolean knows = contestant.judgment() > 0.6F;
            float chance = knows ? (worth ? 0.8F : 0.05F) : 0.45F;
            if (left == 1) {
                chance *= 0.3F;     // the last one: everyone hesitates
            }
            if (random.nextFloat() < chance) {
                strangerMatches.put(id, left - 1);
                if (left == 1) {
                    contestant.speak("You're striking your very last match in the dark.", Lines.pick(random, "This is my last one...",
                            "Last match. Please, please be a new one.", "If this goes out, I go with it."));
                }
                strike(contestant);
            }
            nextStrike.put(id, 20 * (6 + random.nextInt(10)));
        }
    }

    @Override
    protected void more(ServerPlayerEntity player, Hud hud) {
        for (int r = 0; r < 5; r++) {
            StringBuilder line = new StringBuilder();
            for (int c = 0; c < 5; c++) {
                if (r == 2 && c == 2) {
                    line.append("§eFR ");
                } else if (found[r][c]) {
                    String n = String.valueOf(numbers[r][c]);
                    line.append("§a").append(n.length() < 2 ? "0" + n : n).append(' ');
                } else {
                    line.append("§8?? ");
                }
            }
            hud.line(line.toString().trim());
        }
        hud.line("§fMATCHES §e" + matchesLeft(player) + (burning.containsKey(player.getUuid()) ? "  §6LIT " + Hud.clock(burning.get(player.getUuid())) : ""));
    }

    // --- what a stranger can say they'll do, and then does ---------------------------------------------------

    @Override
    protected boolean followable() {
        return true;
    }

    @Override
    public Map<String, String> options(Contestant who) {
        UUID id = who.getUuid();
        int left = strangerMatches.getOrDefault(id, 0);
        if (!isPlaying(id) || left <= 0 || burning.containsKey(id)) {
            return Map.of();
        }
        return Map.of("STRIKE", "strike one of your " + left + " matches here, now", "SAVE_MATCHES", "hold on to your matches for a while");
    }

    @Override
    protected Map<String, String> optionWords(Contestant who) {
        return Map.of("STRIKE", "\\b(strike (a|my|one)|light (a|my|one) match|lighting (a|my) match)\\b",
                "SAVE_MATCHES", "\\b(save (my|the) matches|keep my matches|hold on to (my|the) matches)\\b");
    }

    @Override
    public void chose(Contestant who, String option) {
        UUID id = who.getUuid();
        if (option.equals("STRIKE") && strangerMatches.getOrDefault(id, 0) > 0 && !burning.containsKey(id) && roomAt(who.getPos()) != null) {
            strangerMatches.merge(id, -1, Integer::sum);
            strike(who);
        } else if (option.equals("SAVE_MATCHES")) {
            nextStrike.put(id, 20 * 30);
        }
    }

    @Override
    public String situation(Contestant who) {
        int count = 0;
        for (int r = 0; r < 5; r++) {
            for (int c = 0; c < 5; c++) {
                if (found[r][c]) {
                    count++;
                }
            }
        }
        return "You're playing BINGO, the ten of clubs, in a pitch-dark match factory of twenty-five huge rooms in a five-by-five grid. "
                + "Lighting a match lets you read a room's number, which goes on the shared bingo card in that room's square; the middle "
                + "is free. A full line clears everyone. You have " + strangerMatches.getOrDefault(who.getUuid(), 0) + " matches left; "
                + "when your last goes out you die. " + count + " rooms have been found so far.";
    }

    @Override
    protected void ended() {
        for (UUID id : new ArrayList<>(lights.keySet())) {
            douse(id);
        }
        burning.clear();
        world.setBlockState(lobbyDoor, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(lobbyDoor.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        for (UUID id : players) {
            ServerPlayerEntity player = player(id);
            if (player != null) {
                player.removeStatusEffect(StatusEffects.BLINDNESS);
                for (int i = 0; i < player.getInventory().size(); i++) {
                    if (player.getInventory().getStack(i).isOf(DeadMansHand.MATCH)) {
                        player.getInventory().setStack(i, ItemStack.EMPTY);
                    }
                }
            }
        }
    }
}
