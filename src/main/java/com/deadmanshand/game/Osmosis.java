package com.deadmanshand.game;

import com.deadmanshand.Builder;
import com.deadmanshand.Hud;
import com.deadmanshand.npc.Contestant;
import com.deadmanshand.npc.Lines;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * King of Clubs, in a yard of shipping containers: two teams of five, each with 10,000 points split between its members
 * and shown on their bracelets. Touch someone from the other team and it's a battle: whoever has more points wins, and
 * the loser pays the winner 500. Teammates touching each other count as one. Six items are hidden in the containers.
 * Touch the other team's base for 10,000; while you're touching your own base your points have no limit, and anyone who
 * battles you there pays 10,000. Below zero you're out. When time runs out the team with more points clears; the other
 * team dies.
 */
final class Osmosis extends Game {
    private static final int[] ITEMS = {500, 1000, 1500, 2000, 2500, 3000};
    private static final double CONTACT = 1.7;

    private final Map<UUID, Boolean> blue = new HashMap<>();
    private final Map<UUID, Integer> points = new HashMap<>();
    private final Set<String> fought = new HashSet<>();
    private final Set<UUID> raided = new HashSet<>();
    private final Map<UUID, Integer> cool = new HashMap<>();
    private final Map<BlockPos, Integer> items = new LinkedHashMap<>();
    /** Bracelets left lying where someone fell, and what's on them. */
    private final Map<UUID, Integer> bracelets = new HashMap<>();
    private BlockPos blueBase;
    private BlockPos redBase;
    private UUID king;
    private int ticks;
    private int oy;

    Osmosis(Kind kind, ServerWorld world, BlockPos origin) {
        super(kind, world, origin);
    }

    @Override
    protected void plan(Builder b) {
        int ox = origin.getX();
        oy = origin.getY();
        int oz = origin.getZ();
        int x0 = ox - 24;
        int x1 = ox + 25;
        int z0 = oz - 47;
        int z1 = oz + 2;
        bounds = new Box(x0, oy - 2, z0, x1 + 1, oy + 16, z1 + 1);
        entry = new Box(ox - 3, oy, oz - 4, ox + 4, oy + 3, oz + 1);
        entryPos = new BlockPos(ox, oy, oz - 2);
        approach = new BlockPos(ox, oy, oz + 7);
        blueBase = new BlockPos(ox, oy, oz - 9);
        redBase = new BlockPos(ox, oy, z0 + 3);
        BlockState air = Blocks.AIR.getDefaultState();
        b.fill(x0, oy, z0, x1, oy + 14, z1, air);
        b.fill(x0, oy - 1, z0, x1, oy - 1, z1, Blocks.GRAY_CONCRETE.getDefaultState());
        for (int x = x0 + 2; x < x1; x += 6) {
            b.fill(x, oy - 1, z0 + 1, x, oy - 1, z1 - 1, Blocks.YELLOW_CONCRETE.getDefaultState());
        }
        b.walls(x0, oy, z0, x1, oy + 3, z1, Blocks.IRON_BARS.getDefaultState());
        b.fill(ox - 2, oy, z1, ox + 2, oy + 3, z1, air);
        // The containers: four rows of five, doors at alternate ends, a few stacked
        BlockState[] paint = {Blocks.ORANGE_CONCRETE.getDefaultState(), Blocks.BLUE_CONCRETE.getDefaultState(), Blocks.RED_CONCRETE.getDefaultState(),
                Blocks.GREEN_CONCRETE.getDefaultState(), Blocks.CYAN_CONCRETE.getDefaultState(), Blocks.WHITE_CONCRETE.getDefaultState()};
        List<BlockPos> insides = new ArrayList<>();
        int n = 0;
        for (int row = 0; row < 4; row++) {
            int z = z0 + 9 + row * 7;
            for (int col = 0; col < 5; col++) {
                int x = x0 + 2 + col * 9;
                BlockState c = paint[Math.floorMod(n * 5 + row, paint.length)];
                boolean west = (row + col) % 2 == 0;
                b.fill(x, oy, z, x + 6, oy + 2, z + 2, c);
                b.fill(x + 1, oy, z + 1, x + 5, oy + 1, z + 1, air);
                b.fill(west ? x : x + 6, oy, z + 1, west ? x : x + 6, oy + 1, z + 1, air);
                // corrugation
                for (int k = x; k <= x + 6; k += 2) {
                    b.set(k, oy + 2, z, Blocks.IRON_BARS.getDefaultState());
                    b.set(k, oy + 2, z + 2, Blocks.IRON_BARS.getDefaultState());
                }
                b.set(west ? x + 5 : x + 1, oy + 2, z + 1, Blocks.SEA_LANTERN.getDefaultState());
                insides.add(new BlockPos(west ? x + 5 : x + 1, oy, z + 1));
                if (n % 3 == 1) {
                    b.fill(x, oy + 3, z, x + 6, oy + 5, z + 2, paint[Math.floorMod(n + 2, paint.length)]);
                }
                n++;
            }
        }
        // The six items, each glowing at the back of a container
        int[] which = {1, 4, 7, 10, 13, 18};
        items.clear();
        for (int i = 0; i < ITEMS.length; i++) {
            BlockPos at = insides.get(which[i]);
            items.put(at, ITEMS[i]);
            b.set(at, Blocks.OCHRE_FROGLIGHT.getDefaultState());
        }
        // The bases
        base(b, blueBase, Blocks.BLUE_CONCRETE.getDefaultState(), Blocks.LIGHT_BLUE_STAINED_GLASS.getDefaultState());
        base(b, redBase, Blocks.RED_CONCRETE.getDefaultState(), Blocks.RED_WOOL.getDefaultState());
        // Floodlights
        for (int[] l : new int[][]{{x0 + 1, z0 + 1}, {x1 - 1, z0 + 1}, {x0 + 1, z1 - 1}, {x1 - 1, z1 - 1}, {x0 + 1, oz - 23}, {x1 - 1, oz - 23}}) {
            b.fill(l[0], oy, l[1], l[0], oy + 9, l[1], Blocks.IRON_BARS.getDefaultState());
            b.set(l[0], oy + 10, l[1], Blocks.SEA_LANTERN.getDefaultState());
        }
        b.sign(new BlockPos(ox + 3, oy + 2, z1 + 1), Direction.SOUTH, "GAME VENUE", "", "step inside", "to register");
        b.fill(ox + 3, oy, z1, ox + 3, oy + 2, z1, Blocks.IRON_BLOCK.getDefaultState());
    }

    private static void base(Builder b, BlockPos at, BlockState pillar, BlockState top) {
        b.fill(at.getX() - 2, at.getY() - 1, at.getZ() - 2, at.getX() + 2, at.getY() - 1, at.getZ() + 2, pillar);
        b.fill(at.getX(), at.getY(), at.getZ(), at.getX(), at.getY() + 3, at.getZ(), pillar);
        b.set(at.getX(), at.getY() + 4, at.getZ(), top);
        b.set(at.getX(), at.getY() + 5, at.getZ(), Blocks.SEA_LANTERN.getDefaultState());
    }

    @Override
    protected String[] rules() {
        return new String[]{
                "Two teams of five. Each team has 10,000 points, split between its members; your bracelet shows yours.",
                "Touch a player from the other team to battle. More points wins; the loser pays the winner 500. Teammates touching you add theirs.",
                "You can only battle the same player once. Six items worth points are hidden in the containers.",
                "Touch the other team's base for 10,000. While touching your own base your points are unlimited, and the loser pays 10,000.",
                "Below zero points, you're out. When time runs out the team with more points clears; the other team is out."};
    }

    @Override
    protected int timeLimit() {
        return 15 * 60 * 20;
    }

    @Override
    protected void arrange() {
        int ox = origin.getX();
        Contestant k = citizen("Haruto", redBase.south(2));
        if (k != null) {
            king = k.getUuid();
        }
        String[] men = {"Kenji", "Mei", "Shun", "Nao"};
        for (int i = 0; i < men.length; i++) {
            citizen(men[i], new BlockPos(ox - 6 + 4 * i, oy, redBase.getZ() + 4));
        }
        int i = 0;
        List<UUID> ours = new ArrayList<>();
        for (UUID id : playing()) {
            LivingEntity entity = entity(id);
            if (entity == null) {
                continue;
            }
            boolean red = entity instanceof Contestant c && c.role.equals("citizen");
            blue.put(id, !red);
            if (red) {
                points.put(id, id.equals(king) ? 6000 : 1000);
            } else {
                ours.add(id);
                entity.teleport(ox - 4.5 + 2 * (i % 5), oy, blueBase.getZ() + 3.5 + (i / 5));
                i++;
            }
            if (entity instanceof Contestant c) {
                c.hold();
            }
        }
        for (UUID id : ours) {
            points.put(id, 10000 / Math.max(1, ours.size()));
        }
    }

    @Override
    protected void begin() {
        Contestant k = contestant(king);
        if (k != null) {
            k.speak("You're the King of Clubs, a citizen of this place, and your container-yard game has just begun.",
                    Lines.pick(random, "Let's see how you spend your points.", "Come and touch my base, if you can.", "Five against five. Fair, isn't it?"));
        }
        for (UUID id : playing()) {
            tell(entity(id), "Your bracelet: " + points.get(id) + " points.", Formatting.YELLOW);
        }
    }

    // --- the rules ---------------------------------------------------------------------------------------------

    private boolean side(UUID id) {
        return blue.getOrDefault(id, true);
    }

    private BlockPos home(UUID id) {
        return side(id) ? blueBase : redBase;
    }

    private static boolean touching(LivingEntity who, BlockPos base) {
        double dx = who.getX() - (base.getX() + 0.5);
        double dz = who.getZ() - (base.getZ() + 0.5);
        return dx * dx + dz * dz < 1.5 * 1.5 && Math.abs(who.getY() - base.getY()) < 3;
    }

    /** Points standing behind {@code who} right now: theirs, and any teammates in a chain of contact with them. */
    private long pool(LivingEntity who) {
        boolean team = side(who.getUuid());
        Set<UUID> seen = new HashSet<>();
        Deque<LivingEntity> queue = new ArrayDeque<>();
        queue.add(who);
        seen.add(who.getUuid());
        long total = 0;
        List<LivingEntity> mates = new ArrayList<>();
        for (LivingEntity e : living()) {
            if (side(e.getUuid()) == team) {
                mates.add(e);
            }
        }
        while (!queue.isEmpty()) {
            LivingEntity e = queue.poll();
            if (touching(e, home(e.getUuid()))) {
                return Long.MAX_VALUE;
            }
            total += points.getOrDefault(e.getUuid(), 0);
            for (LivingEntity m : mates) {
                if (!seen.contains(m.getUuid()) && m.squaredDistanceTo(e) < CONTACT * CONTACT) {
                    seen.add(m.getUuid());
                    queue.add(m);
                }
            }
        }
        return total;
    }

    private static String key(UUID a, UUID b) {
        return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
    }

    private void battle(LivingEntity a, LivingEntity b) {
        UUID ia = a.getUuid();
        UUID ib = b.getUuid();
        if (side(ia) == side(ib) || !isPlaying(ia) || !isPlaying(ib) || cool.getOrDefault(ia, 0) > 0) {
            return;
        }
        cool.put(ia, 30);
        cool.put(ib, 30);
        if (!fought.add(key(ia, ib))) {
            // a second battle with the same player: both bracelets shock
            for (LivingEntity e : new LivingEntity[]{a, b}) {
                e.damage(DamageSource.LIGHTNING_BOLT, 2.0F);
                e.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 60, 4, false, false));
                world.spawnParticles(ParticleTypes.ELECTRIC_SPARK, e.getX(), e.getY() + 1.0, e.getZ(), 15, 0.3, 0.4, 0.3, 0.1);
                tell(e, "You've already battled " + nameOf(e == a ? ib : ia) + ". Both bracelets shock.", Formatting.RED);
            }
            world.playSound(null, a.getX(), a.getY(), a.getZ(), SoundEvents.ENTITY_LIGHTNING_BOLT_IMPACT, SoundCategory.PLAYERS, 0.5F, 2.0F);
            return;
        }
        long pa = pool(a);
        long pb = pool(b);
        if (pa == pb) {
            tell(a, "Battle with " + nameOf(ib) + ": a draw.", Formatting.GRAY);
            tell(b, "Battle with " + nameOf(ia) + ": a draw.", Formatting.GRAY);
            return;
        }
        LivingEntity winner = pa > pb ? a : b;
        LivingEntity loser = winner == a ? b : a;
        int pay = touching(winner, home(winner.getUuid())) ? 10000 : 500;
        points.merge(loser.getUuid(), -pay, Integer::sum);
        points.merge(winner.getUuid(), pay, Integer::sum);
        world.playSound(null, winner.getX(), winner.getY(), winner.getZ(), SoundEvents.BLOCK_NOTE_BLOCK_PLING, SoundCategory.PLAYERS, 1.0F, 1.6F);
        world.playSound(null, loser.getX(), loser.getY(), loser.getZ(), SoundEvents.BLOCK_NOTE_BLOCK_PLING, SoundCategory.PLAYERS, 1.0F, 0.5F);
        tell(winner, "You won the battle with " + nameOf(loser.getUuid()) + ": +" + pay + ". Bracelet: " + points.get(winner.getUuid()) + ".", Formatting.GREEN);
        tell(loser, "You lost the battle with " + nameOf(winner.getUuid()) + ": -" + pay + ". Bracelet: " + points.get(loser.getUuid()) + ".", Formatting.RED);
        if (points.get(loser.getUuid()) < 0) {
            fail(loser.getUuid(), "Your points fell below zero.");
        }
    }

    @Override
    protected boolean touched(ServerPlayerEntity player, Entity target) {
        return contact(player, target);
    }

    @Override
    protected boolean attacked(ServerPlayerEntity player, Entity target) {
        return contact(player, target);
    }

    private boolean contact(ServerPlayerEntity player, Entity target) {
        if (target instanceof LivingEntity living && isPlaying(target.getUuid())) {
            if (side(player.getUuid()) == side(target.getUuid())) {
                tell(player, "That's your teammate. Stand close and your points count together.", Formatting.GRAY);
            } else {
                battle(player, living);
            }
            return true;
        }
        return false;
    }

    @Override
    protected ActionResult use(ServerPlayerEntity player, BlockPos pos, BlockState state) {
        if (items.containsKey(pos)) {
            take(player, pos);
            return ActionResult.SUCCESS;
        }
        return ActionResult.PASS;
    }

    private void take(LivingEntity who, BlockPos pos) {
        Integer value = items.remove(pos);
        if (value == null) {
            return;
        }
        world.setBlockState(pos, Blocks.AIR.getDefaultState());
        points.merge(who.getUuid(), value, Integer::sum);
        world.playSound(null, pos, SoundEvents.BLOCK_NOTE_BLOCK_CHIME, SoundCategory.PLAYERS, 1.0F, 1.4F);
        tell(who, "You found an item: +" + value + ". Bracelet: " + points.get(who.getUuid()) + ".", Formatting.GOLD);
        announce("An item has been taken. " + items.size() + (items.size() == 1 ? " is" : " are") + " left.");
    }

    @Override
    protected void running() {
        ticks++;
        cool.replaceAll((id, t) -> Math.max(0, t - 1));
        for (LivingEntity e : living()) {
            UUID id = e.getUuid();
            BlockPos enemy = side(id) ? redBase : blueBase;
            if (!raided.contains(id) && touching(e, enemy)) {
                raided.add(id);
                points.merge(id, 10000, Integer::sum);
                world.playSound(null, enemy, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 1.0F, 1.0F);
                announce(nameOf(id) + " has touched the " + (side(id) ? "red" : "blue") + " base: +10,000.");
            }
            // a bracelet on the ground: pick it up and it adds to yours
            for (Map.Entry<UUID, Integer> entry : new ArrayList<>(bracelets.entrySet())) {
                Entity item = world.getEntity(entry.getKey());
                if (item == null) {
                    bracelets.remove(entry.getKey());
                } else if (item.squaredDistanceTo(e) < 1.5 * 1.5) {
                    item.discard();
                    bracelets.remove(entry.getKey());
                    points.merge(id, entry.getValue(), Integer::sum);
                    tell(e, "You put on a second bracelet: +" + entry.getValue() + ". Bracelet: " + points.get(id) + ".", Formatting.GOLD);
                }
            }
        }
        for (Contestant c : strangers()) {
            think(c);
        }
        if (count(true) == 0 || count(false) == 0) {
            timeUp();
        }
    }

    private int count(boolean side) {
        int n = 0;
        for (UUID id : playing()) {
            if (side(id) == side) {
                n++;
            }
        }
        return n;
    }

    private long total(boolean side) {
        long sum = 0;
        for (UUID id : playing()) {
            if (side(id) == side) {
                sum += points.getOrDefault(id, 0);
            }
        }
        return sum;
    }

    @Override
    protected void timeUp() {
        long b = total(true);
        long r = total(false);
        boolean blueWins = b > r;
        announce("Time. Blue " + b + " points, red " + r + ". " + (b == r ? "A draw: nobody clears." : (blueWins ? "Blue" : "Red") + " clears the game."));
        for (UUID id : playing()) {
            if (b != r && side(id) == blueWins) {
                clear(id);
            } else {
                fail(id, "Your team had fewer points.");
            }
        }
        finish();
    }

    @Override
    protected void died(UUID id) {
        int left = points.getOrDefault(id, 0);
        LivingEntity entity = entity(id);
        if (left > 0 && entity != null) {
            ItemStack stack = new ItemStack(Items.CLOCK);
            stack.setCustomName(Text.literal("Bracelet: " + left + " points").formatted(Formatting.YELLOW));
            ItemEntity item = new ItemEntity(world, entity.getX(), entity.getY() + 0.5, entity.getZ(), stack, 0, 0.2, 0);
            item.setPickupDelay(32767);
            item.setCustomName(stack.getName());
            item.setCustomNameVisible(true);
            world.spawnEntity(item);
            bracelets.put(item.getUuid(), left);
        }
        points.put(id, 0);
    }

    // --- the strangers ---------------------------------------------------------------------------------------------

    private void think(Contestant c) {
        UUID id = c.getUuid();
        boolean team = side(id);
        BlockPos own = home(id);
        BlockPos enemyBase = team ? redBase : blueBase;
        LivingEntity near = null;
        double best = Double.MAX_VALUE;
        for (LivingEntity e : living()) {
            if (side(e.getUuid()) != team) {
                double d = c.squaredDistanceTo(e);
                if (d < best) {
                    best = d;
                    near = e;
                }
            }
        }
        // a bracelet lying about is worth the walk
        for (UUID b : bracelets.keySet()) {
            Entity item = world.getEntity(b);
            if (item != null && c.squaredDistanceTo(item) < 15 * 15) {
                c.go(item.getPos(), 1.2);
                return;
            }
        }
        boolean isKing = id.equals(king);
        int phase = Math.floorMod(ticks / 20 + id.hashCode(), 120);
        String job;
        if (isKing) {
            job = phase < 85 ? "guard" : "hunt";
        } else if (team) {
            job = switch (c.nature) {
                case CLEVER -> items.isEmpty() ? "guard" : "search";
                case BRAVE, RECKLESS -> phase < 60 ? "raid" : "hunt";
                default -> "guard";
            };
        } else {
            int n = Math.floorMod(id.hashCode(), 4);
            job = n == 0 ? "raid" : n == 1 ? (items.isEmpty() ? "hunt" : "search") : n == 2 ? "hunt" : (phase < 60 ? "guard" : "raid");
        }
        if (told.containsKey(id)) {
            job = told.get(id).toLowerCase();
        }
        if (raided.contains(id) && job.equals("raid")) {
            job = "guard";
        }
        boolean atHome = touching(c, own);
        // close enough to touch: decide whether to battle
        if (near != null && best < 1.8 * 1.8 && !fought.contains(key(id, near.getUuid()))) {
            long mine = pool(c);
            boolean sure = mine == Long.MAX_VALUE || mine > points.getOrDefault(near.getUuid(), 0) * (c.judgment() > 0.7F ? 1.0 : 0.6);
            if (sure || job.equals("hunt") && random.nextFloat() < 0.05F) {
                c.look(near);
                c.swingHand(Hand.MAIN_HAND);
                battle(c, near);
                if (random.nextFloat() < 0.3F) {
                    c.say(Lines.pick(random, "Battle.", "Let's see your bracelet.", "Touch."));
                }
                return;
            }
        }
        switch (job) {
            case "guard" -> {
                if (!atHome) {
                    c.go(Vec3d.ofBottomCenter(own).add(isKing ? 0 : (id.hashCode() % 2 == 0 ? 1.0 : -1.0), 0, team ? 1.0 : -1.0), 1.1);
                } else {
                    c.hold();
                    if (near != null) {
                        c.look(near);
                    }
                }
            }
            case "search" -> {
                BlockPos target = null;
                double d = Double.MAX_VALUE;
                for (BlockPos at : items.keySet()) {
                    double dd = c.squaredDistanceTo(Vec3d.ofCenter(at));
                    if (dd < d) {
                        d = dd;
                        target = at;
                    }
                }
                if (target != null) {
                    if (d < 2.0 * 2.0) {
                        take(c, target);
                    } else {
                        c.go(target, 1.1);
                    }
                }
            }
            case "raid" -> {
                // keep clear of anyone guarding it
                if (near != null && best < 4 * 4 && touching(near, enemyBase)) {
                    Vec3d away = c.getPos().subtract(near.getPos()).normalize().multiply(4.0);
                    c.go(c.getPos().add(away), 1.3);
                } else {
                    c.go(Vec3d.ofBottomCenter(enemyBase), 1.25);
                }
            }
            default -> {
                if (near != null && !touching(near, enemyBase)) {
                    c.go(near.getPos(), 1.2);
                    c.look(near);
                } else {
                    c.go(Vec3d.ofBottomCenter(own), 1.0);
                }
            }
        }
    }

    // --- show ---------------------------------------------------------------------------------------------

    @Override
    protected void more(ServerPlayerEntity player, Hud hud) {
        UUID id = player.getUuid();
        boolean team = side(id);
        hud.role(team ? "BLUE" : "RED", team ? 0x4070FF : 0xFF3030);
        hud.line("§fBRACELET §e" + points.getOrDefault(id, 0) + (touching(player, home(id)) ? " §b(at base: unlimited)" : ""));
        hud.line("§fTEAM §e" + total(team) + " §7(" + count(team) + " left)");
        hud.line("§fITEMS LEFT §e" + items.size() + (raided.contains(id) ? "  §aBASE TOUCHED" : ""));
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
        Map<String, String> options = new LinkedHashMap<>();
        options.put("RAID", "run for the other team's base and touch it for 10,000");
        options.put("GUARD", "stand touching your own base, where your points have no limit");
        if (!items.isEmpty()) {
            options.put("SEARCH", "search the containers for the hidden items");
        }
        options.put("HUNT", "go after the other team and battle them");
        return options;
    }

    @Override
    protected Map<String, String> optionWords(Contestant who) {
        return Map.of("RAID", "\\b(their base|enemy base|other base)\\b", "GUARD", "\\b(our base|my base|guard)\\b",
                "SEARCH", "\\b(containers|the items|search)\\b");
    }

    @Override
    public String situation(Contestant who) {
        boolean team = side(who.getUuid());
        return "You're playing OSMOSIS, the king of clubs, in a shipping-container yard: two teams of five, each splitting 10,000 points. "
                + "Touching someone from the other team is a battle: more points wins, the loser pays 500; teammates touching you add theirs. "
                + "Touching the enemy base gives 10,000; at your own base your points are unlimited. Below zero you're out. You're on the "
                + (team ? "blue (challengers')" : "red (the King's)") + " team with " + points.getOrDefault(who.getUuid(), 0) + " points; your team has "
                + total(team) + ".";
    }
}
