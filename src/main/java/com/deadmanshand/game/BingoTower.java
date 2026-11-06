package com.deadmanshand.game;

import com.deadmanshand.Builder;
import com.deadmanshand.Hud;
import com.deadmanshand.Laser;
import com.deadmanshand.npc.Contestant;
import com.deadmanshand.npc.Lines;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.projectile.thrown.SnowballEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bingo Tower: a red and white lattice tower, and a bingo card shared by everyone on it. Climb, and press the numbered
 * buttons on its rings: each one marks its number on the card. The FREE square in the middle is a button at the very top.
 * Complete a line, with FREE pressed, and you clear it. But every press drops steel balls from above onto whoever's
 * climbing. You're roped to a partner. Fall to the ground and you're out.
 */
final class BingoTower extends Game {
    /** Ring heights above the street, their half-widths, and whether they're full decks. */
    private static final int[] Y = {0, 8, 16, 24, 32, 40, 48};
    private static final int[] R = {9, 8, 7, 6, 5, 4, 1};
    private static final boolean[] DECK = {false, false, false, true, false, true, true};
    private static final int[] BUTTONS = {0, 6, 6, 5, 4, 3, 0};

    private final int[][] card = new int[5][5];
    private final boolean[][] marked = new boolean[5][5];
    private final Map<BlockPos, int[]> buttons = new LinkedHashMap<>();
    private final Map<BlockPos, Integer> buttonLevel = new HashMap<>();
    private final Map<UUID, UUID> rope = new HashMap<>();
    private final Map<UUID, Double> highest = new HashMap<>();
    private final Map<UUID, Object[]> balls = new HashMap<>();
    private final Map<UUID, BlockPos> aim = new HashMap<>();
    private BlockPos free;
    private int cx;
    private int cz;
    private int oy;
    private int ticks;

    BingoTower(Kind kind, ServerWorld world, BlockPos origin) {
        super(kind, world, origin);
    }

    private boolean ring(int level, int dx, int dz) {
        int m = Math.max(Math.abs(dx), Math.abs(dz));
        return DECK[level] ? m <= R[level] : m == R[level] || m == R[level] - 1;
    }

    /** The scaffolding up to {@code level}, on the ring below it. */
    private List<int[]> ladders(int level) {
        int r = R[level] == 1 ? 1 : R[level];
        List<int[]> list = new ArrayList<>();
        if (level % 2 == 1) {
            list.add(new int[]{0, -r});
            list.add(new int[]{0, r});
        } else {
            list.add(new int[]{-r, 0});
            list.add(new int[]{r, 0});
        }
        return list;
    }

    @Override
    protected void plan(Builder b) {
        int ox = origin.getX();
        oy = origin.getY();
        int oz = origin.getZ();
        cx = ox;
        cz = oz - 24;
        bounds = new Box(ox - 24, oy - 2, oz - 47, ox + 26, oy + Y[6] + 10, oz + 3);
        entry = new Box(ox - 3, oy, oz - 4, ox + 4, oy + 3, oz + 1);
        entryPos = new BlockPos(ox, oy, oz - 2);
        approach = new BlockPos(ox, oy, oz + 6);
        // the card
        List<Integer> col = new ArrayList<>();
        java.util.Random shuffle = new java.util.Random(random.nextLong());
        for (int c = 0; c < 5; c++) {
            col.clear();
            for (int n = 1; n <= 15; n++) {
                col.add(c * 15 + n);
            }
            Collections.shuffle(col, shuffle);
            for (int r = 0; r < 5; r++) {
                card[r][c] = col.get(r);
                marked[r][c] = false;
            }
        }
        card[2][2] = 0;
        BlockState air = Blocks.AIR.getDefaultState();
        BlockState red = Blocks.RED_CONCRETE.getDefaultState();
        BlockState white = Blocks.WHITE_CONCRETE.getDefaultState();
        BlockState steel = Blocks.ORANGE_CONCRETE.getDefaultState();
        b.fill(ox - 24, oy, oz - 47, ox + 25, oy + Y[6] + 6, oz + 2, air);
        b.fill(ox - 24, oy - 1, oz - 47, ox + 25, oy - 1, oz + 2, Blocks.GRAY_CONCRETE.getDefaultState());
        b.fill(cx - 12, oy - 1, cz - 12, cx + 12, oy - 1, cz + 12, Blocks.SMOOTH_STONE.getDefaultState());
        // the rings and decks, and the legs between them in red and white bands
        for (int level = 1; level < Y.length; level++) {
            int y = oy + Y[level];
            for (int dx = -R[level]; dx <= R[level]; dx++) {
                for (int dz = -R[level]; dz <= R[level]; dz++) {
                    if (ring(level, dx, dz)) {
                        b.set(cx + dx, y, cz + dz, steel);
                    }
                }
            }
            for (int sx : new int[]{-1, 1}) {
                for (int sz : new int[]{-1, 1}) {
                    for (int yy = oy + Y[level - 1]; yy < y; yy++) {
                        b.set(cx + sx * R[level], yy, cz + sz * R[level], (yy / 4) % 2 == 0 ? red : white);
                    }
                }
            }
            // cross-bracing on the faces
            if (level < 6) {
                for (int yy = oy + Y[level - 1] + 1; yy < y; yy++) {
                    int t = (yy - oy - Y[level - 1]) * 2 * R[level] / (Y[level] - Y[level - 1]);
                    for (int[] f : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                        int along = -R[level] + t;
                        int bx = f[0] != 0 ? cx + f[0] * R[level] : cx + along;
                        int bz = f[1] != 0 ? cz + f[1] * R[level] : cz + along;
                        b.set(bx, yy, bz, Blocks.IRON_BARS.getDefaultState());
                    }
                }
            }
            // the scaffolding up to it
            for (int[] l : ladders(level)) {
                int from = oy + Y[level - 1] + (level == 1 ? 0 : 1);
                b.fill(cx + l[0], from, cz + l[1], cx + l[0], y, cz + l[1], Blocks.SCAFFOLDING.getDefaultState());
            }
        }
        // the spire, and FREE on top of it
        b.fill(cx, oy + Y[6] + 1, cz, cx, oy + Y[6] + 1, cz, Blocks.GOLD_BLOCK.getDefaultState());
        free = new BlockPos(cx, oy + Y[6] + 1, cz);
        b.fill(cx, oy + Y[6] + 2, cz, cx, oy + Y[6] + 5, cz, Blocks.LIGHTNING_ROD.getDefaultState());
        b.sign(new BlockPos(cx, oy + Y[6] + 1, cz + 1), Direction.SOUTH, "", "FREE", "", "");
        // the numbered buttons on the rings
        buttons.clear();
        buttonLevel.clear();
        List<int[]> cells = new ArrayList<>();
        for (int r = 0; r < 5; r++) {
            for (int c = 0; c < 5; c++) {
                if (r != 2 || c != 2) {
                    cells.add(new int[]{r, c});
                }
            }
        }
        Collections.shuffle(cells, shuffle);
        int k = 0;
        for (int level = 1; level <= 5; level++) {
            int y = oy + Y[level] + 1;
            int r = R[level];
            int[][] spots = {{-r + 1, -r}, {r - 1, -r}, {-r, r - 2}, {r, 2 - r}, {-r + 2, r}, {r - 2, r}, {-r, 0}, {r, 0}};
            for (int n = 0; n < BUTTONS[level] && k < cells.size(); n++) {
                int[] s = spots[n];
                BlockPos at = new BlockPos(cx + s[0], y, cz + s[1]);
                int[] cell = cells.get(k++);
                buttons.put(at, cell);
                buttonLevel.put(at, level);
                b.set(at, Blocks.REDSTONE_LAMP.getDefaultState());
                Direction out = Math.abs(s[1]) == r ? (s[1] < 0 ? Direction.NORTH : Direction.SOUTH) : (s[0] < 0 ? Direction.WEST : Direction.EAST);
                b.sign(at.offset(out), out, "", String.valueOf(card[cell[0]][cell[1]]), "", "");
            }
        }
        b.sign(new BlockPos(ox + 3, oy + 2, oz + 2), Direction.SOUTH, "GAME VENUE", "", "step inside", "to register");
        b.fill(ox + 3, oy, oz + 1, ox + 3, oy + 2, oz + 1, Blocks.IRON_BLOCK.getDefaultState());
    }

    @Override
    protected String[] rules() {
        return new String[]{
                "Bingo Tower. Everyone shares one bingo card. The numbers are buttons on the tower: press one and its number is marked.",
                "The FREE square is the button at the very top.",
                "Complete a line, across, down or corner to corner, with FREE pressed, and everyone clears the game.",
                "Every press drops steel balls from above. You're roped to a partner. Fall to the ground and it's game over."};
    }

    @Override
    protected int timeLimit() {
        return 18 * 60 * 20;
    }

    @Override
    protected void arrange() {
        List<UUID> all = new ArrayList<>(playing());
        Collections.shuffle(all, new java.util.Random(random.nextLong()));
        for (int i = 0; i + 1 < all.size(); i += 2) {
            rope.put(all.get(i), all.get(i + 1));
            rope.put(all.get(i + 1), all.get(i));
        }
        int i = 0;
        for (UUID id : all) {
            LivingEntity e = entity(id);
            if (e == null) {
                continue;
            }
            double a = i * Math.PI * 2 / Math.max(1, all.size());
            e.teleport(cx + 0.5 + Math.cos(a) * 11, oy, cz + 0.5 + Math.sin(a) * 11);
            if (e instanceof Contestant c) {
                c.hold();
            }
            ServerPlayerEntity p = player(id);
            if (p != null && rope.containsKey(id)) {
                tell(p, "You're roped to " + nameOf(rope.get(id)) + ".", Formatting.YELLOW);
            }
            i++;
        }
    }

    @Override
    protected void begin() {
        for (Contestant c : strangers()) {
            c.stopAll();
        }
    }

    // --- the card ---------------------------------------------------------------------------------------------

    private boolean line() {
        if (!marked[2][2]) {
            return false;
        }
        for (int r = 0; r < 5; r++) {
            boolean row = true;
            boolean column = true;
            for (int c = 0; c < 5; c++) {
                row &= marked[r][c];
                column &= marked[c][r];
            }
            if (row || column) {
                return true;
            }
        }
        boolean d1 = true;
        boolean d2 = true;
        for (int i = 0; i < 5; i++) {
            d1 &= marked[i][i];
            d2 &= marked[i][4 - i];
        }
        return d1 || d2;
    }

    @Override
    protected ActionResult use(ServerPlayerEntity player, BlockPos pos, BlockState state) {
        if (pos.equals(free) || buttons.containsKey(pos)) {
            press(player, pos);
            return ActionResult.SUCCESS;
        }
        return ActionResult.PASS;
    }

    private void press(LivingEntity who, BlockPos pos) {
        int[] cell = pos.equals(free) ? new int[]{2, 2} : buttons.get(pos);
        if (cell == null || marked[cell[0]][cell[1]]) {
            return;
        }
        marked[cell[0]][cell[1]] = true;
        world.setBlockState(pos, Blocks.GLOWSTONE.getDefaultState());
        world.playSound(null, pos, SoundEvents.BLOCK_NOTE_BLOCK_BELL, SoundCategory.BLOCKS, 2.0F, 1.2F);
        int n = card[cell[0]][cell[1]];
        announce(nameOf(who.getUuid()) + " presses " + (n == 0 ? "FREE" : n) + ".");
        if (line()) {
            announce("BINGO.");
            for (UUID id : playing()) {
                clear(id);
            }
            finish();
            return;
        }
        // the price: steel balls from the top
        world.playSound(null, cx, oy + Y[6], cz, SoundEvents.BLOCK_CHAIN_BREAK, SoundCategory.BLOCKS, 3.0F, 0.6F);
        for (int k = 0; k < 7; k++) {
            int level = 1 + random.nextInt(5);
            int r = R[level];
            int side = random.nextInt(4);
            int along = random.nextInt(2 * r + 1) - r;
            double x = cx + 0.5 + (side == 0 ? r + 0.0 : side == 1 ? -r : along);
            double z = cz + 0.5 + (side == 2 ? r + 0.0 : side == 3 ? -r : along);
            SnowballEntity ball = new SnowballEntity(world, x, oy + Y[6] + 4 + random.nextInt(4), z);
            ball.setItem(new ItemStack(Items.IRON_NUGGET));
            ball.setVelocity((random.nextDouble() - 0.5) * 0.05, -0.6, (random.nextDouble() - 0.5) * 0.05);
            world.spawnEntity(ball);
        }
    }

    // --- play ---------------------------------------------------------------------------------------------

    private int levelOf(LivingEntity e) {
        int best = 0;
        for (int level = 1; level < Y.length; level++) {
            if (e.getY() >= oy + Y[level] + 0.7) {
                best = level;
            }
        }
        return best;
    }

    @Override
    protected void running() {
        ticks++;
        trackBalls();
        for (LivingEntity e : living()) {
            UUID id = e.getUuid();
            highest.merge(id, e.getY(), Math::max);
            // the ground
            if (e.isOnGround() && e.getY() < oy + 0.5 && highest.getOrDefault(id, 0.0) > oy + 4) {
                announce(nameOf(id) + " fell.");
                kill(id, Laser.FALL, "You fell.");
                continue;
            }
            // the rope
            UUID mate = rope.get(id);
            LivingEntity other = mate == null || !isPlaying(mate) ? null : entity(mate);
            if (other != null && e.getY() > other.getY() && e.squaredDistanceTo(other) > 9 * 9) {
                Vec3d pull = other.getPos().subtract(e.getPos()).normalize().multiply(0.3);
                e.addVelocity(pull.x, Math.min(0, pull.y), pull.z);
                e.velocityModified = true;
                if (ticks % 20 == 0) {
                    tell(e, "The rope pulls at you: " + nameOf(mate) + " is too far below.", Formatting.RED);
                }
            }
        }
        for (Contestant c : strangers()) {
            climb(c);
        }
    }

    /** As in Target: a ball that vanishes next to someone hit them. */
    private void trackBalls() {
        List<UUID> gone = new ArrayList<>(balls.keySet());
        for (SnowballEntity ball : world.getEntitiesByClass(SnowballEntity.class, bounds, e -> true)) {
            gone.remove(ball.getUuid());
            balls.put(ball.getUuid(), new Object[]{ball.getPos(), ball.getVelocity()});
            if (ticks % 3 == 0) {
                world.spawnParticles(ParticleTypes.CRIT, ball.getX(), ball.getY(), ball.getZ(), 1, 0, 0, 0, 0);
            }
        }
        for (UUID id : gone) {
            Object[] last = balls.remove(id);
            Vec3d at = (Vec3d) last[0];
            for (LivingEntity target : living()) {
                if (target.getPos().add(0, 1, 0).squaredDistanceTo(at) < 1.6 * 1.6) {
                    Vec3d out = target.getPos().subtract(new Vec3d(cx + 0.5, target.getY(), cz + 0.5)).normalize().multiply(0.9);
                    target.addVelocity(out.x, 0.2, out.z);
                    target.velocityModified = true;
                    target.damage(net.minecraft.entity.damage.DamageSource.FALLING_BLOCK, 2.0F);
                    world.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.BLOCK_ANVIL_LAND, SoundCategory.PLAYERS, 0.6F, 1.8F);
                }
            }
        }
    }

    /** Up the scaffolding a level at a time, pressing what's worth pressing on the way. */
    private void climb(Contestant c) {
        UUID id = c.getUuid();
        int level = levelOf(c);
        BlockPos target = aim.get(id);
        if (target == null || target.equals(free) && marked[2][2] || buttons.containsKey(target) && marked[buttons.get(target)[0]][buttons.get(target)[1]]) {
            target = choose(c);
            aim.put(id, target);
        }
        if (target == null) {
            return;
        }
        int want = target.equals(free) ? 6 : buttonLevel.get(target);
        if (want == level) {
            if (c.squaredDistanceTo(Vec3d.ofCenter(target)) < 2.6 * 2.6) {
                c.swingHand(net.minecraft.util.Hand.MAIN_HAND);
                press(c, target);
                aim.remove(id);
                if (random.nextFloat() < 0.3F) {
                    c.say(Lines.pick(random, "Got it! Watch out above!", "Pressed! Heads down!", "That's one more."));
                }
            } else {
                Vec3d at = Vec3d.ofBottomCenter(target);
                Vec3d in = new Vec3d(cx + 0.5 - at.x, 0, cz + 0.5 - at.z);
                c.go(in.lengthSquared() < 0.01 ? at.add(1, 0, 0) : at.add(in.normalize().multiply(1.2)), 1.0);
            }
            return;
        }
        // up the nearest scaffolding to the next level
        int next = level + 1;
        if (next >= Y.length) {
            return;
        }
        int[] best = null;
        double d = Double.MAX_VALUE;
        for (int[] l : ladders(next)) {
            double dd = c.squaredDistanceTo(cx + l[0] + 0.5, c.getY(), cz + l[1] + 0.5);
            if (dd < d) {
                d = dd;
                best = l;
            }
        }
        if (best == null) {
            return;
        }
        Vec3d foot = new Vec3d(cx + best[0] + 0.5, oy + Y[level] + (level == 0 ? 0 : 1), cz + best[1] + 0.5);
        if (Math.abs(c.getX() - foot.x) < 0.6 && Math.abs(c.getZ() - foot.z) < 0.6) {
            // in the scaffolding: climb
            c.hold();
            if (c.getY() < oy + Y[next] + 1.1) {
                c.setVelocity(0, 0.2, 0);
                c.velocityModified = true;
            } else {
                // step off onto the ring, towards the middle
                Vec3d in = new Vec3d(cx + 0.5 - foot.x, 0, cz + 0.5 - foot.z).normalize();
                c.go(new Vec3d(foot.x + in.x * 1.2, oy + Y[next] + 1, foot.z + in.z * 1.2), 1.0);
            }
        } else {
            c.go(foot, 1.0);
        }
    }

    /** The button a stranger goes for: low and useful first, then FREE when a line is nearly done. */
    private BlockPos choose(Contestant c) {
        BlockPos best = null;
        double score = Double.MAX_VALUE;
        for (Map.Entry<BlockPos, int[]> e : buttons.entrySet()) {
            int[] cell = e.getValue();
            if (marked[cell[0]][cell[1]]) {
                continue;
            }
            double s = buttonLevel.get(e.getKey()) * 10 + c.squaredDistanceTo(Vec3d.ofCenter(e.getKey())) * 0.01 + random.nextDouble() * 6;
            if (c.judgment() > 0.6F && (cell[0] == 2 || cell[1] == 2 || cell[0] == cell[1] || cell[0] + cell[1] == 4)) {
                s -= 8;     // lines through the middle need FREE anyway
            }
            if (s < score) {
                score = s;
                best = e.getKey();
            }
        }
        int done = 0;
        for (boolean[] row : marked) {
            for (boolean m : row) {
                done += m ? 1 : 0;
            }
        }
        if (!marked[2][2] && (best == null || done >= 6 && c.nature != Contestant.Nature.TIMID)) {
            return free;
        }
        return best;
    }

    // --- show ---------------------------------------------------------------------------------------------

    @Override
    protected void more(ServerPlayerEntity player, Hud hud) {
        for (int r = 0; r < 5; r++) {
            StringBuilder s = new StringBuilder();
            for (int c = 0; c < 5; c++) {
                int n = card[r][c];
                String text = n == 0 ? "FR" : String.format("%2d", n);
                s.append(marked[r][c] ? "§a§l" + text + "§r " : "§7" + text + " ");
            }
            hud.line(s.toString().trim());
        }
        UUID mate = rope.get(player.getUuid());
        hud.line("§fROPED TO §e" + (mate == null ? "nobody" : nameOf(mate) + (isPlaying(mate) ? "" : " §c(gone)")));
    }

    // --- what a stranger can say they'll do, and then does ---------------------------------------------------

    @Override
    public Map<String, String> options(Contestant who) {
        if (!isPlaying(who.getUuid())) {
            return Map.of();
        }
        Map<String, String> options = new LinkedHashMap<>();
        options.put("PRESS_N", "climb to the button numbered N and press it: for example [PRESS 27]");
        if (!marked[2][2]) {
            options.put("FREE", "climb all the way to the top and press FREE");
        }
        return options;
    }

    @Override
    protected Map<String, String> optionWords(Contestant who) {
        return Map.of("PRESS_N", "\\b(press|number|button)\\s+(\\d{1,2})\\b", "FREE", "\\b(the free|press free|to the top)\\b");
    }

    @Override
    public void chose(Contestant who, String option) {
        if (option.equals("FREE") && !marked[2][2]) {
            aim.put(who.getUuid(), free);
            return;
        }
        if (option.startsWith("PRESS_")) {
            try {
                int n = Integer.parseInt(option.substring(6));
                for (Map.Entry<BlockPos, int[]> e : buttons.entrySet()) {
                    int[] cell = e.getValue();
                    if (card[cell[0]][cell[1]] == n && !marked[cell[0]][cell[1]]) {
                        aim.put(who.getUuid(), e.getKey());
                    }
                }
            } catch (NumberFormatException ignored) {
            }
        }
    }

    @Override
    public String situation(Contestant who) {
        return "You're playing BINGO TOWER: climbing a tall lattice tower, pressing numbered buttons to mark a shared bingo card. The FREE "
                + "square is a button at the very top. A line with FREE clears it for everyone. Every press drops steel balls on the climbers, "
                + "you're roped to " + (rope.containsKey(who.getUuid()) ? nameOf(rope.get(who.getUuid())) : "nobody") + ", and falling to the ground kills you. "
                + "You're on level " + levelOf(who) + " of 6.";
    }
}
