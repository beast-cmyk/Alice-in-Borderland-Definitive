package com.deadmanshand.game;

import com.deadmanshand.Builder;
import com.deadmanshand.Hud;
import com.deadmanshand.Laser;
import com.deadmanshand.npc.Contestant;
import com.deadmanshand.npc.Lines;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeavesBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Jack of Diamonds, in a tatami room: four at a mahjong table, one of them the Jack. Collars on; leave your seat and it
 * goes off. Four hands, one deal each. After the last hand, first place clears the game. Everyone else is out.
 * The rules are mahjong's, kept simple: any complete hand wins (four sets and a pair, or seven pairs), no calls but a win.
 */
final class Mahjong extends Game {
    private static final int HANDS = 4;
    private static final int TURN = 25 * 20;
    private static final int CLAIM = 8 * 20;
    private static final String[] HONOURS = {"E", "S", "W", "N", "Wh", "Gr", "Rd"};

    private enum Step { PAUSE, TURN, CLAIM, OVER }

    private final List<UUID> table = new ArrayList<>();
    private final Map<UUID, Vec3d> seatAt = new HashMap<>();
    private final Map<UUID, List<Integer>> hands = new HashMap<>();
    private final Map<UUID, List<Integer>> discards = new HashMap<>();
    private final Map<UUID, Integer> points = new HashMap<>();
    private final List<Integer> wall = new ArrayList<>();
    private final List<UUID> claimants = new ArrayList<>();
    private UUID jack;
    private Step step = Step.PAUSE;
    private int hand;
    private int current;
    private int timer;
    private int npcWait;
    private int lastTile = -1;
    private UUID lastFrom;
    private int tz;
    private int oy;

    Mahjong(Kind kind, ServerWorld world, BlockPos origin) {
        super(kind, world, origin);
    }

    @Override
    protected void plan(Builder b) {
        int ox = origin.getX();
        oy = origin.getY();
        int oz = origin.getZ();
        tz = oz - 22;
        int rx0 = ox - 7;
        int rx1 = ox + 7;
        int rz0 = tz - 7;
        int rz1 = tz + 7;
        bounds = new Box(ox - 24, oy - 2, oz - 47, ox + 26, oy + 12, oz + 3);
        entry = new Box(ox - 3, oy, oz - 4, ox + 4, oy + 3, oz + 1);
        entryPos = new BlockPos(ox, oy, oz - 2);
        approach = new BlockPos(ox, oy, oz + 6);
        BlockState air = Blocks.AIR.getDefaultState();
        // The garden
        b.fill(ox - 24, oy, oz - 47, ox + 25, oy + 10, oz + 2, air);
        b.fill(ox - 24, oy - 1, oz - 47, ox + 25, oy - 1, oz + 2, Blocks.MOSS_BLOCK.getDefaultState());
        b.fill(ox - 1, oy - 1, rz1 + 1, ox + 1, oy - 1, oz + 2, Blocks.GRAVEL.getDefaultState());
        for (int z = rz1 + 2; z <= oz; z += 3) {
            b.set(ox, oy - 1, z, Blocks.SMOOTH_STONE.getDefaultState());
        }
        b.fill(ox + 6, oy - 1, oz - 9, ox + 12, oy - 1, oz - 5, Blocks.WATER.getDefaultState());
        for (int[] p : new int[][]{{ox - 3, oz - 4}, {ox + 3, rz1 + 3}, {ox - 3, rz1 + 3}}) {
            b.set(p[0], oy, p[1], Blocks.STONE_BRICK_WALL.getDefaultState());
            b.set(p[0], oy + 1, p[1], Blocks.LANTERN.getDefaultState());
        }
        BlockState leaves = Blocks.AZALEA_LEAVES.getDefaultState().with(LeavesBlock.PERSISTENT, true);
        b.walls(ox - 24, oy, oz - 47, ox + 25, oy + 1, oz + 2, leaves);
        b.fill(ox - 2, oy, oz + 2, ox + 2, oy + 1, oz + 2, air);
        b.sign(new BlockPos(ox + 3, oy + 1, oz + 3), Direction.SOUTH, "GAME VENUE", "", "step inside", "to register");
        b.set(ox + 3, oy + 1, oz + 2, Blocks.STONE_BRICKS.getDefaultState());
        b.set(ox + 3, oy, oz + 2, Blocks.STONE_BRICKS.getDefaultState());
        // The room: tatami, paper walls, a dark roof
        b.fill(rx0, oy - 1, rz0, rx1, oy - 1, rz1, Blocks.HAY_BLOCK.getDefaultState());
        for (int x = rx0; x <= rx1; x += 4) {
            b.fill(x, oy - 1, rz0, x, oy - 1, rz1, Blocks.DARK_OAK_PLANKS.getDefaultState());
        }
        b.walls(rx0, oy, rz0, rx1, oy + 3, rz1, Blocks.WHITE_WOOL.getDefaultState());
        for (int x = rx0; x <= rx1; x += 2) {
            b.fill(x, oy, rz0, x, oy + 3, rz0, Blocks.STRIPPED_OAK_LOG.getDefaultState());
            b.fill(x, oy, rz1, x, oy + 3, rz1, Blocks.STRIPPED_OAK_LOG.getDefaultState());
        }
        for (int z = rz0; z <= rz1; z += 2) {
            b.fill(rx0, oy, z, rx0, oy + 3, z, Blocks.STRIPPED_OAK_LOG.getDefaultState());
            b.fill(rx1, oy, z, rx1, oy + 3, z, Blocks.STRIPPED_OAK_LOG.getDefaultState());
        }
        b.fill(ox - 1, oy, rz1, ox + 1, oy + 2, rz1, air);
        b.fill(rx0 - 1, oy + 4, rz0 - 1, rx1 + 1, oy + 4, rz1 + 1, Blocks.DARK_OAK_PLANKS.getDefaultState());
        b.fill(rx0 - 2, oy + 5, rz0 - 2, rx1 + 2, oy + 5, rz1 + 2, Blocks.DEEPSLATE_TILES.getDefaultState());
        b.fill(rx0 + 1, oy + 6, rz0 + 1, rx1 - 1, oy + 6, rz1 - 1, Blocks.DEEPSLATE_TILES.getDefaultState());
        // The table, the cushions, the lamp
        b.fill(ox - 1, oy, tz - 1, ox + 1, oy, tz + 1, Blocks.DARK_OAK_PLANKS.getDefaultState());
        b.fill(ox - 1, oy + 1, tz - 1, ox + 1, oy + 1, tz + 1, Blocks.GREEN_CARPET.getDefaultState());
        for (int[] s : new int[][]{{0, -2}, {2, 0}, {0, 2}, {-2, 0}}) {
            b.set(ox + s[0], oy, tz + s[1], Blocks.RED_CARPET.getDefaultState());
        }
        b.set(ox, oy + 3, tz, Blocks.LANTERN.getDefaultState().with(net.minecraft.block.LanternBlock.HANGING, true));
        b.set(rx0 + 1, oy + 3, rz0 + 1, Blocks.LANTERN.getDefaultState().with(net.minecraft.block.LanternBlock.HANGING, true));
        b.set(rx1 - 1, oy + 3, rz0 + 1, Blocks.LANTERN.getDefaultState().with(net.minecraft.block.LanternBlock.HANGING, true));
    }

    @Override
    protected String[] rules() {
        return new String[]{
                "Mahjong, four players, one of them the Jack of Diamonds. You're wearing a collar. Leave your seat and it goes off.",
                "Draw a tile, discard a tile. Any complete hand wins: four sets (three alike, or a run of three in one suit) and a pair, or seven pairs.",
                "Win off the wall (tsumo) and everyone pays you; win off a discard (ron) and the one who threw it pays.",
                "Four hands. Everyone starts with 25,000. After the last hand, first place clears the game. Everyone else is out."};
    }

    @Override
    protected int timeLimit() {
        return 40 * 60 * 20;
    }

    @Override
    protected void arrange() {
        int ox = origin.getX();
        Contestant j = citizen("Kazu", new BlockPos(ox, oy, tz - 2));
        if (j != null) {
            jack = j.getUuid();
        }
        double[][] places = {{0, 2}, {-2, 0}, {2, 0}, {0, -2}};
        int i = 0;
        for (UUID id : playing()) {
            if (id.equals(jack) || table.size() >= 3) {
                continue;
            }
            table.add(id);
        }
        // anyone beyond four watches from the edge of the room; they won't be dealt in
        if (jack != null) {
            table.add(Math.min(2, table.size()), jack);
        }
        for (UUID id : table) {
            double[] p = places[i++];
            Vec3d at = new Vec3d(ox + p[0] + 0.5, oy, tz + p[1] + 0.5);
            seatAt.put(id, at);
            points.put(id, 25000);
            LivingEntity e = entity(id);
            if (e != null) {
                e.teleport(at.x, at.y, at.z);
                if (e instanceof Contestant c) {
                    c.hold();
                }
            }
        }
        for (UUID id : playing()) {
            if (!table.contains(id)) {
                fail(id, "There were only four seats.");
            }
        }
    }

    @Override
    protected void begin() {
        Contestant j = contestant(jack);
        if (j != null) {
            j.speak("You're the Jack of Diamonds, a citizen of this place, sitting down to mahjong with three challengers. You're very good.",
                    Lines.pick(random, "Shall we? East round, four hands.", "I do love a quiet game.", "Don't get up. Ever."));
        }
        newHand();
    }

    // --- tiles ---------------------------------------------------------------------------------------------

    private static String name(int t) {
        if (t < 27) {
            return (t % 9 + 1) + "mps".substring(t / 9, t / 9 + 1);
        }
        return HONOURS[t - 27];
    }

    private static String coloured(int t) {
        String c = t < 9 ? "§c" : t < 18 ? "§9" : t < 27 ? "§a" : t >= 31 ? "§6" : "§f";
        return c + name(t);
    }

    private static int[] counts(List<Integer> tiles) {
        int[] c = new int[34];
        for (int t : tiles) {
            c[t]++;
        }
        return c;
    }

    private static boolean sevenPairs(int[] c) {
        int pairs = 0;
        for (int n : c) {
            if (n == 2) {
                pairs++;
            } else if (n != 0) {
                return false;
            }
        }
        return pairs == 7;
    }

    private static boolean sets(int[] c, int from) {
        int i = from;
        while (i < 34 && c[i] == 0) {
            i++;
        }
        if (i == 34) {
            return true;
        }
        if (c[i] >= 3) {
            c[i] -= 3;
            boolean ok = sets(c, i);
            c[i] += 3;
            if (ok) {
                return true;
            }
        }
        if (i < 27 && i % 9 <= 6 && c[i + 1] > 0 && c[i + 2] > 0) {
            c[i]--;
            c[i + 1]--;
            c[i + 2]--;
            boolean ok = sets(c, i);
            c[i]++;
            c[i + 1]++;
            c[i + 2]++;
            return ok;
        }
        return false;
    }

    private static boolean complete(int[] c) {
        if (sevenPairs(c)) {
            return true;
        }
        for (int p = 0; p < 34; p++) {
            if (c[p] >= 2) {
                c[p] -= 2;
                boolean ok = sets(c, 0);
                c[p] += 2;
                if (ok) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int shanten(int[] c) {
        int pairs = 0;
        int kinds = 0;
        for (int n : c) {
            if (n >= 2) {
                pairs++;
            }
            if (n > 0) {
                kinds++;
            }
        }
        int seven = 6 - pairs + Math.max(0, 7 - kinds);
        int[] best = {8};
        search(c, 0, 0, 0, 0, best);
        return Math.min(seven, best[0]);
    }

    private static void search(int[] c, int i, int sets, int partials, int pair, int[] best) {
        while (i < 34 && c[i] == 0) {
            i++;
        }
        if (i == 34) {
            int t = Math.min(partials, 4 - sets);
            best[0] = Math.min(best[0], 8 - 2 * sets - t - pair);
            return;
        }
        if (c[i] >= 3) {
            c[i] -= 3;
            search(c, i, sets + 1, partials, pair, best);
            c[i] += 3;
        }
        boolean suited = i < 27;
        if (suited && i % 9 <= 6 && c[i + 1] > 0 && c[i + 2] > 0) {
            c[i]--;
            c[i + 1]--;
            c[i + 2]--;
            search(c, i, sets + 1, partials, pair, best);
            c[i]++;
            c[i + 1]++;
            c[i + 2]++;
        }
        if (c[i] >= 2) {
            c[i] -= 2;
            if (pair == 0) {
                search(c, i, sets, partials, 1, best);
            }
            search(c, i, sets, partials + 1, pair, best);
            c[i] += 2;
        }
        if (suited && i % 9 <= 7 && c[i + 1] > 0) {
            c[i]--;
            c[i + 1]--;
            search(c, i, sets, partials + 1, pair, best);
            c[i]++;
            c[i + 1]++;
        }
        if (suited && i % 9 <= 6 && c[i + 2] > 0) {
            c[i]--;
            c[i + 2]--;
            search(c, i, sets, partials + 1, pair, best);
            c[i]++;
            c[i + 2]++;
        }
        c[i]--;
        search(c, i, sets, partials, pair, best);
        c[i]++;
    }

    /** What a winning hand is worth, and why. */
    private static int han(int[] c, boolean tsumo, List<String> why) {
        int han = 1;
        why.add("a complete hand");
        if (tsumo) {
            han++;
            why.add("tsumo");
        }
        boolean simples = true;
        boolean honours = false;
        int suit = -1;
        boolean oneSuit = true;
        for (int t = 0; t < 34; t++) {
            if (c[t] == 0) {
                continue;
            }
            if (t >= 27) {
                honours = true;
                simples = false;
            } else {
                if (t % 9 == 0 || t % 9 == 8) {
                    simples = false;
                }
                if (suit < 0) {
                    suit = t / 9;
                } else if (suit != t / 9) {
                    oneSuit = false;
                }
            }
        }
        if (simples) {
            han++;
            why.add("all simples");
        }
        if (sevenPairs(c)) {
            han += 2;
            why.add("seven pairs");
        } else {
            boolean triplets = true;
            int twos = 0;
            for (int n : c) {
                if (n == 1 || n == 4) {
                    triplets = false;
                }
                if (n == 2) {
                    twos++;
                }
            }
            if (triplets && twos == 1) {
                han += 2;
                why.add("all triplets");
            }
        }
        if (oneSuit && suit >= 0) {
            han += honours ? 2 : 5;
            why.add(honours ? "half flush" : "full flush");
        }
        for (int t = 31; t < 34; t++) {
            if (c[t] >= 3) {
                han++;
                why.add(HONOURS[t - 27] + " dragons");
            }
        }
        return han;
    }

    // --- play ---------------------------------------------------------------------------------------------

    private void newHand() {
        hand++;
        wall.clear();
        for (int t = 0; t < 34; t++) {
            for (int k = 0; k < 4; k++) {
                wall.add(t);
            }
        }
        Collections.shuffle(wall, new java.util.Random(random.nextLong()));
        hands.clear();
        discards.clear();
        for (UUID id : table) {
            if (!isPlaying(id)) {
                continue;
            }
            List<Integer> h = new ArrayList<>();
            for (int k = 0; k < 13; k++) {
                h.add(wall.remove(wall.size() - 1));
            }
            Collections.sort(h);
            hands.put(id, h);
            discards.put(id, new ArrayList<>());
        }
        current = Math.floorMod(hand - 1, table.size());
        announce("Hand " + hand + " of " + HANDS + ". " + nameOf(table.get(current)) + " deals.");
        world.playSound(null, origin.getX(), oy, tz, SoundEvents.BLOCK_WOOD_HIT, SoundCategory.BLOCKS, 1.0F, 1.6F);
        step = Step.PAUSE;
        timer = 60;
    }

    private void draw() {
        UUID id = table.get(current);
        if (!isPlaying(id) || !hands.containsKey(id)) {
            next();
            return;
        }
        if (wall.size() <= 14) {
            announce("The wall is down to the dead wall. Exhaustive draw: no one wins this hand.");
            over();
            return;
        }
        int t = wall.remove(wall.size() - 1);
        List<Integer> h = hands.get(id);
        h.add(t);
        step = Step.TURN;
        timer = TURN;
        npcWait = 30 + random.nextInt(40);
        ServerPlayerEntity player = player(id);
        if (player != null) {
            Collections.sort(h);
            showHand(player, t);
        }
    }

    private void showHand(ServerPlayerEntity player, int drawn) {
        List<Integer> h = hands.get(player.getUuid());
        List<String> options = new ArrayList<>();
        if (complete(counts(h))) {
            options.add("§d§lTSUMO!|tsumo");
        }
        for (int k = 0; k < h.size(); k++) {
            options.add(coloured(h.get(k)) + "|d" + k);
        }
        player.sendMessage(Text.literal("You drew " + name(drawn) + ".").formatted(Formatting.GRAY), false);
        choices(player, "Discard:", options.toArray(new String[0]));
    }

    private void discard(UUID id, int index) {
        List<Integer> h = hands.get(id);
        if (h == null || index < 0 || index >= h.size()) {
            return;
        }
        int t = h.remove(index);
        Collections.sort(h);
        discards.get(id).add(t);
        lastTile = t;
        lastFrom = id;
        world.playSound(null, origin.getX(), oy + 1, tz, SoundEvents.BLOCK_WOOD_HIT, SoundCategory.BLOCKS, 0.8F, 2.0F);
        for (ServerPlayerEntity p : audience()) {
            p.sendMessage(Text.literal(nameOf(id) + " discards " + coloured(t) + "§7."), false);
        }
        claimants.clear();
        for (int k = 1; k < table.size(); k++) {
            UUID other = table.get((current + k) % table.size());
            List<Integer> oh = hands.get(other);
            if (isPlaying(other) && oh != null) {
                int[] c = counts(oh);
                c[t]++;
                if (complete(c)) {
                    claimants.add(other);
                }
            }
        }
        nextClaim();
    }

    private void nextClaim() {
        while (!claimants.isEmpty()) {
            UUID who = claimants.get(0);
            if (!isPlaying(who)) {
                claimants.remove(0);
                continue;
            }
            ServerPlayerEntity player = player(who);
            if (player == null) {
                win(who, false);
                return;
            }
            step = Step.CLAIM;
            timer = CLAIM;
            choices(player, nameOf(lastFrom) + "'s " + name(lastTile) + " completes your hand.", "§d§lRON!|ron", "Pass|pass");
            return;
        }
        next();
    }

    private void next() {
        current = (current + 1) % table.size();
        step = Step.PAUSE;
        timer = 6;
    }

    private void win(UUID who, boolean tsumo) {
        List<Integer> h = new ArrayList<>(hands.get(who));
        if (!tsumo) {
            h.add(lastTile);
        }
        Collections.sort(h);
        List<String> why = new ArrayList<>();
        int han = han(counts(h), tsumo, why);
        StringBuilder shown = new StringBuilder();
        for (int t : h) {
            shown.append(coloured(t)).append(' ');
        }
        announce(nameOf(who) + ": " + (tsumo ? "Tsumo!" : "Ron!") + " " + han + " han (" + String.join(", ", why) + ").");
        for (ServerPlayerEntity p : audience()) {
            p.sendMessage(Text.literal(shown.toString().trim()), false);
        }
        if (tsumo) {
            for (UUID id : table) {
                if (!id.equals(who) && isPlaying(id)) {
                    points.merge(id, -400 * han, Integer::sum);
                    points.merge(who, 400 * han, Integer::sum);
                }
            }
        } else {
            points.merge(lastFrom, -1000 * han, Integer::sum);
            points.merge(who, 1000 * han, Integer::sum);
        }
        LivingEntity e = entity(who);
        if (e != null) {
            world.spawnParticles(ParticleTypes.END_ROD, e.getX(), e.getY() + 1.5, e.getZ(), 20, 0.4, 0.4, 0.4, 0.05);
        }
        world.playSound(null, origin.getX(), oy + 1, tz, SoundEvents.BLOCK_NOTE_BLOCK_CHIME, SoundCategory.BLOCKS, 1.0F, 1.0F);
        Contestant c = contestant(who);
        if (c != null) {
            c.say(Lines.pick(random, tsumo ? "Tsumo." : "Ron.", "That's mine.", "Sorry about that."));
        }
        over();
    }

    private void over() {
        step = Step.OVER;
        timer = 100;
        StringBuilder s = new StringBuilder("Standings: ");
        for (UUID id : ranking()) {
            s.append(nameOf(id)).append(' ').append(points.get(id)).append("  ");
        }
        announce(s.toString().trim());
    }

    private List<UUID> ranking() {
        List<UUID> r = new ArrayList<>();
        for (UUID id : table) {
            if (isPlaying(id)) {
                r.add(id);
            }
        }
        // ties go to the house
        r.sort((a, b) -> {
            int c = Integer.compare(points.get(b), points.get(a));
            return c != 0 ? c : Boolean.compare(b.equals(jack), a.equals(jack));
        });
        return r;
    }

    private void end() {
        List<UUID> r = ranking();
        if (r.isEmpty()) {
            finish();
            return;
        }
        announce("The game is over. First place: " + nameOf(r.get(0)) + ".");
        clear(r.get(0));
        for (int k = 1; k < r.size(); k++) {
            collar(r.get(k));
        }
        finish();
    }

    private void collar(UUID id) {
        if (!isPlaying(id)) {
            return;
        }
        seats.put(id, Fate.DEAD);
        LivingEntity e = entity(id);
        if (e != null && e.isAlive()) {
            world.playSound(null, e.getX(), e.getY(), e.getZ(), SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.PLAYERS, 0.8F, 1.6F);
            world.spawnParticles(ParticleTypes.EXPLOSION, e.getX(), e.getY() + 1.5, e.getZ(), 1, 0, 0, 0, 0);
            e.damage(Laser.COLLAR, 1000.0F);
        }
        died(id);
    }

    @Override
    protected void running() {
        // nobody leaves their seat
        for (UUID id : table) {
            Vec3d at = seatAt.get(id);
            LivingEntity e = entity(id);
            if (isPlaying(id) && e != null && at != null && e.getPos().squaredDistanceTo(at) > 1.6 * 1.6) {
                announce(nameOf(id) + " left their seat.");
                collar(id);
            }
            if (e instanceof Contestant c && isPlaying(id)) {
                c.hold();
                c.look(entity(table.get((table.indexOf(id) + 2) % table.size())));
            }
        }
        if (playing().size() <= 1) {
            for (UUID id : playing()) {
                clear(id);
            }
            finish();
            return;
        }
        switch (step) {
            case PAUSE -> {
                if (--timer <= 0) {
                    draw();
                }
            }
            case TURN -> {
                UUID id = table.get(current);
                if (!isPlaying(id)) {
                    next();
                    return;
                }
                Contestant c = contestant(id);
                if (c != null) {
                    if (--npcWait <= 0) {
                        npcTurn(c);
                    }
                } else if (--timer <= 0) {
                    List<Integer> h = hands.get(id);
                    tell(entity(id), "Too slow: you discard what you drew.", Formatting.GRAY);
                    discard(id, h.size() - 1);
                }
            }
            case CLAIM -> {
                if (--timer <= 0) {
                    claimants.remove(0);
                    nextClaim();
                }
            }
            case OVER -> {
                if (--timer <= 0) {
                    if (hand >= HANDS) {
                        end();
                    } else {
                        newHand();
                    }
                }
            }
        }
    }

    private void npcTurn(Contestant c) {
        UUID id = c.getUuid();
        List<Integer> h = hands.get(id);
        if (complete(counts(h))) {
            win(id, true);
            return;
        }
        int[] cs = counts(h);
        int best = Integer.MAX_VALUE;
        List<Integer> candidates = new ArrayList<>();
        Map<Integer, Integer> score = new HashMap<>();
        for (int t = 0; t < 34; t++) {
            if (cs[t] == 0) {
                continue;
            }
            cs[t]--;
            int s = shanten(cs);
            cs[t]++;
            score.put(t, s);
            best = Math.min(best, s);
        }
        float skill = id.equals(jack) ? 1.0F : c.judgment();
        int slack = random.nextFloat() > skill ? 1 : 0;
        for (Map.Entry<Integer, Integer> e : score.entrySet()) {
            if (e.getValue() <= best + slack) {
                candidates.add(e.getKey());
            }
        }
        // among equals, let go of honours and terminals first
        candidates.sort((a, b) -> Integer.compare(keep(b, cs), keep(a, cs)));
        int pick = candidates.get(slack > 0 ? random.nextInt(candidates.size()) : candidates.size() - 1);
        discard(id, h.indexOf(pick));
    }

    /** How much a tile is worth holding on to: lower goes first. */
    private static int keep(int t, int[] c) {
        if (t >= 27) {
            return c[t] >= 2 ? 3 : 0;
        }
        int n = t % 9;
        int near = 0;
        for (int d = -2; d <= 2; d++) {
            int u = t + d;
            if (d != 0 && n + d >= 0 && n + d <= 8 && c[u] > 0) {
                near++;
            }
        }
        return near * 2 + (n == 0 || n == 8 ? 0 : 1) + (c[t] >= 2 ? 3 : 0);
    }

    @Override
    protected void answered(ServerPlayerEntity player, String answer) {
        UUID id = player.getUuid();
        String a = answer.trim().toLowerCase();
        if (step == Step.TURN && table.indexOf(id) == current) {
            if (a.equals("tsumo") && complete(counts(hands.get(id)))) {
                win(id, true);
            } else if (a.startsWith("d")) {
                try {
                    discard(id, Integer.parseInt(a.substring(1)));
                } catch (NumberFormatException ignored) {
                }
            }
        } else if (step == Step.CLAIM && !claimants.isEmpty() && claimants.get(0).equals(id)) {
            if (a.equals("ron")) {
                win(id, false);
            } else if (a.equals("pass")) {
                claimants.remove(0);
                nextClaim();
            }
        }
    }

    @Override
    protected boolean attacked(ServerPlayerEntity player, Entity target) {
        tell(player, "Hands on the table.", Formatting.GRAY);
        return true;
    }

    // --- show ---------------------------------------------------------------------------------------------

    @Override
    protected void more(ServerPlayerEntity player, Hud hud) {
        UUID id = player.getUuid();
        hud.line("§fHAND §e" + hand + "/" + HANDS + "  §fWALL §e" + Math.max(0, wall.size() - 14));
        if (!table.isEmpty()) {
            UUID now = table.get(current);
            hud.line(now.equals(id) && step == Step.TURN ? "§aYOUR TURN " + Hud.clock(timer) : "§7" + nameOf(now) + "'s turn"
                    + (lastTile >= 0 ? "  §7last: " + coloured(lastTile) : ""));
        }
        for (UUID u : ranking()) {
            hud.line((u.equals(id) ? "§e> " : "§7") + nameOf(u) + " §f" + points.get(u));
        }
    }

    // --- what a stranger can say they'll do, and then does ---------------------------------------------------

    @Override
    public Map<String, String> options(Contestant who) {
        UUID id = who.getUuid();
        if (step != Step.TURN || table.isEmpty() || !table.get(current).equals(id) || !hands.containsKey(id)) {
            return Map.of();
        }
        Map<String, String> options = new LinkedHashMap<>();
        for (int t : hands.get(id)) {
            options.putIfAbsent("DISCARD_" + tagOf(name(t)), "throw away your " + name(t));
        }
        return options;
    }

    @Override
    public void chose(Contestant who, String option) {
        UUID id = who.getUuid();
        if (step != Step.TURN || !table.get(current).equals(id)) {
            return;
        }
        List<Integer> h = hands.get(id);
        for (int k = 0; k < h.size(); k++) {
            if (option.equals("DISCARD_" + tagOf(name(h.get(k))))) {
                discard(id, k);
                return;
            }
        }
    }

    @Override
    public String situation(Contestant who) {
        List<UUID> r = ranking();
        int place = r.indexOf(who.getUuid()) + 1;
        return "You're playing MAHJONG, the jack of diamonds, at a low table in a tatami room, wearing a collar that goes off if you leave "
                + "your seat. Four hands; only first place survives. It's hand " + hand + " of 4 and you're in place " + place + " with "
                + points.getOrDefault(who.getUuid(), 0) + " points." + (who.getUuid().equals(jack) ? " You're the Jack: a citizen, and very good." : "");
    }
}
