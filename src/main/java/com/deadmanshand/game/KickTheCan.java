package com.deadmanshand.game;

import com.deadmanshand.Builder;
import com.deadmanshand.Hud;
import com.deadmanshand.Laser;
import com.deadmanshand.npc.Contestant;
import com.deadmanshand.npc.Lines;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Kick the Can, in a warehouse: ten cans in the middle, every one a bomb. Pick one up and its fuse starts: sixty seconds.
 * Get it back to its own numbered base before it goes off, and you're through. It's heavy, so you're slow with it; hand it
 * to someone else (use them) and the fuse starts again, five seconds shorter each time. Get hurt while you hold it and it
 * goes off. Ten cans: no more than ten of you get out.
 */
final class KickTheCan extends Game {
    private static final int CANS = 10;
    private static final int FUSE = 60 * 20;

    private final BlockPos[] pile = new BlockPos[CANS];
    private final BlockPos[] base = new BlockPos[CANS];
    private final boolean[] home = new boolean[CANS];
    private final boolean[] gone = new boolean[CANS];
    /** Who holds which can, how long its fuse has left, and how long the next holder will get. */
    private final Map<UUID, Integer> holding = new HashMap<>();
    private final int[] fuse = new int[CANS];
    private final int[] next = new int[CANS];
    private final Map<UUID, Float> health = new HashMap<>();
    private final Map<UUID, Integer> cool = new HashMap<>();
    private int oy;
    private int ticks;

    KickTheCan(Kind kind, ServerWorld world, BlockPos origin) {
        super(kind, world, origin);
    }

    @Override
    protected void plan(Builder b) {
        int ox = origin.getX();
        oy = origin.getY();
        int oz = origin.getZ();
        int x0 = ox - 23;
        int x1 = ox + 24;
        int z0 = oz - 46;
        int z1 = oz - 5;
        int cz = oz - 26;
        bounds = new Box(ox - 24, oy - 2, oz - 47, ox + 26, oy + 14, oz + 3);
        entry = new Box(ox - 3, oy, oz - 4, ox + 4, oy + 3, oz + 1);
        entryPos = new BlockPos(ox, oy, oz - 2);
        approach = new BlockPos(ox, oy, oz + 6);
        BlockState air = Blocks.AIR.getDefaultState();
        BlockState wall = Blocks.LIGHT_GRAY_CONCRETE.getDefaultState();
        BlockState shelf = Blocks.SPRUCE_PLANKS.getDefaultState();
        b.fill(ox - 24, oy, oz - 47, ox + 25, oy + 12, oz + 2, air);
        b.fill(ox - 24, oy - 1, oz - 47, ox + 25, oy - 1, oz + 2, Blocks.GRAY_CONCRETE.getDefaultState());
        // The warehouse shell
        b.fill(x0, oy - 1, z0, x1, oy + 10, z1, wall);
        b.fill(x0 + 1, oy, z0 + 1, x1 - 1, oy + 9, z1 - 1, air);
        b.fill(x0 + 1, oy - 1, z0 + 1, x1 - 1, oy - 1, z1 - 1, Blocks.SMOOTH_STONE.getDefaultState());
        for (int x = x0 + 4; x < x1; x += 6) {
            b.fill(x, oy + 9, z0 + 2, x, oy + 9, z1 - 2, Blocks.SEA_LANTERN.getDefaultState());
        }
        // Rows of shelving, east and west of the middle, with gaps to get through
        for (int side : new int[]{-1, 1}) {
            for (int row = 0; row < 4; row++) {
                int x = ox + side * (8 + row * 4);
                for (int z = z0 + 3; z < z1 - 3; z++) {
                    int gap = Math.floorMod(z - z0 + row * 7, 14);
                    if (gap > 2) {
                        b.fill(x, oy, z, x, oy + 3, z, (z % 3 == 0) ? Blocks.BARREL.getDefaultState() : shelf);
                    }
                }
            }
        }
        // A mezzanine along the north wall, with steps up at both ends
        int my = oy + 5;
        b.fill(x0 + 1, my - 1, z0 + 1, x1 - 1, my - 1, z0 + 7, Blocks.STRIPPED_OAK_LOG.getDefaultState());
        b.fill(x0 + 1, my, z0 + 7, x1 - 1, my, z0 + 7, Blocks.IRON_BARS.getDefaultState());
        for (int i = 0; i < 5; i++) {
            b.fill(x0 + 1, oy, z0 + 12 - i, x0 + 2, oy + i, z0 + 12 - i, wall);
            b.fill(x1 - 2, oy, z0 + 12 - i, x1 - 1, oy + i, z0 + 12 - i, wall);
        }
        b.fill(x0 + 1, my, z0 + 7, x0 + 2, my, z0 + 7, air);
        b.fill(x1 - 2, my, z0 + 7, x1 - 1, my, z0 + 7, air);
        // The ten cans, in the middle
        for (int k = 0; k < CANS; k++) {
            pile[k] = new BlockPos(ox - 2 + (k % 5), oy, cz - 1 + (k / 5) * 2);
            b.set(pile[k], Blocks.CAULDRON.getDefaultState());
        }
        b.fill(ox - 3, oy - 1, cz - 2, ox + 3, oy - 1, cz + 2, Blocks.YELLOW_CONCRETE.getDefaultState());
        // The ten bases: some close, some among the shelves, some up on the mezzanine
        int[][] spots = {{ox, oy, z1 - 2}, {x0 + 2, oy, cz}, {x1 - 2, oy, cz}, {x0 + 2, oy, z1 - 3}, {x1 - 2, oy, z1 - 3},
                {ox - 14, oy, z0 + 9}, {ox + 14, oy, z0 + 9}, {ox - 10, my, z0 + 2}, {ox + 10, my, z0 + 2}, {ox, my, z0 + 2}};
        for (int k = 0; k < CANS; k++) {
            int[] s = spots[k];
            base[k] = new BlockPos(s[0], s[1], s[2]);
            b.set(base[k], Blocks.REDSTONE_LAMP.getDefaultState());
            b.set(base[k].down(), Blocks.POLISHED_BLACKSTONE.getDefaultState());
            Direction face = s[2] <= z0 + 2 ? Direction.SOUTH : s[0] <= x0 + 2 ? Direction.EAST : s[0] >= x1 - 2 ? Direction.WEST : Direction.NORTH;
            b.sign(base[k].offset(face), face, "", "BASE " + (k + 1), "", "");
        }
        b.fill(ox - 1, oy, z1, ox + 1, oy + 2, z1, air);
        b.sign(new BlockPos(ox + 3, oy + 2, oz + 2), Direction.SOUTH, "GAME VENUE", "", "step inside", "to register");
        b.fill(ox + 3, oy, oz + 1, ox + 3, oy + 2, oz + 1, Blocks.IRON_BLOCK.getDefaultState());
    }

    @Override
    protected String[] rules() {
        return new String[]{
                "Kick the Can. Ten cans in the middle, and every one is a bomb.",
                "Pick a can up and its fuse starts: sixty seconds. Put it back on its own numbered base in time, and you clear the game.",
                "Hand it on to someone (use them) and the fuse starts again, five seconds shorter every time.",
                "If you're hurt while you're holding one, or the time runs out, it goes off. There are only ten cans."};
    }

    @Override
    protected int timeLimit() {
        return 12 * 60 * 20;
    }

    @Override
    protected void arrange() {
        for (int k = 0; k < CANS; k++) {
            next[k] = FUSE;
        }
        int i = 0;
        for (LivingEntity e : living()) {
            e.teleport(origin.getX() - 6.5 + (i % 14), oy, origin.getZ() - 9.5);
            if (e instanceof Contestant c) {
                c.hold();
            }
            i++;
        }
        world.setBlockState(new BlockPos(origin.getX(), oy, origin.getZ() - 5), Blocks.IRON_BARS.getDefaultState());
        world.setBlockState(new BlockPos(origin.getX() - 1, oy, origin.getZ() - 5), Blocks.IRON_BARS.getDefaultState());
        world.setBlockState(new BlockPos(origin.getX() + 1, oy, origin.getZ() - 5), Blocks.IRON_BARS.getDefaultState());
        world.setBlockState(new BlockPos(origin.getX(), oy + 1, origin.getZ() - 5), Blocks.IRON_BARS.getDefaultState());
        world.setBlockState(new BlockPos(origin.getX() - 1, oy + 1, origin.getZ() - 5), Blocks.IRON_BARS.getDefaultState());
        world.setBlockState(new BlockPos(origin.getX() + 1, oy + 1, origin.getZ() - 5), Blocks.IRON_BARS.getDefaultState());
    }

    @Override
    protected void begin() {
        for (Contestant c : strangers()) {
            c.stopAll();
        }
    }

    @Override
    protected void ended() {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = 0; dy < 2; dy++) {
                world.setBlockState(new BlockPos(origin.getX() + dx, oy + dy, origin.getZ() - 5), Blocks.AIR.getDefaultState());
            }
        }
    }

    // --- the cans ---------------------------------------------------------------------------------------------

    private void pickUp(LivingEntity who, int k) {
        UUID id = who.getUuid();
        if (holding.containsKey(id) || home[k] || gone[k] || !world.getBlockState(pile[k]).isOf(Blocks.CAULDRON)) {
            return;
        }
        world.setBlockState(pile[k], Blocks.AIR.getDefaultState());
        give(who, k);
        announce(nameOf(id) + " has picked up can " + (k + 1) + ".");
    }

    private void give(LivingEntity who, int k) {
        UUID id = who.getUuid();
        holding.put(id, k);
        fuse[k] = next[k];
        next[k] = Math.max(5 * 20, next[k] - 5 * 20);
        health.put(id, who.getHealth());
        cool.put(id, 20);
        world.playSound(null, who.getX(), who.getY(), who.getZ(), SoundEvents.ENTITY_TNT_PRIMED, SoundCategory.PLAYERS, 1.0F, 1.2F);
        tell(who, "You're holding can " + (k + 1) + ". Base " + (k + 1) + ". " + fuse[k] / 20 + " seconds.", Formatting.GOLD);
    }

    private void handOff(LivingEntity from, LivingEntity to) {
        UUID a = from.getUuid();
        UUID b = to.getUuid();
        Integer k = holding.get(a);
        if (k == null || holding.containsKey(b) || !isPlaying(b) || cool.getOrDefault(a, 0) > 0) {
            return;
        }
        holding.remove(a);
        give(to, k);
        announce(nameOf(a) + " hands can " + (k + 1) + " to " + nameOf(b) + ". " + fuse[k] / 20 + " seconds.");
    }

    private void place(LivingEntity who, int baseIndex) {
        UUID id = who.getUuid();
        Integer k = holding.get(id);
        if (k == null) {
            return;
        }
        if (k != baseIndex) {
            tell(who, "That's base " + (baseIndex + 1) + ". Yours is base " + (k + 1) + ".", Formatting.RED);
            return;
        }
        holding.remove(id);
        home[k] = true;
        world.setBlockState(base[k], Blocks.GLOWSTONE.getDefaultState());
        world.setBlockState(base[k].up(), Blocks.CAULDRON.getDefaultState());
        world.playSound(null, base[k], SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 1.0F, 1.0F);
        announce("Can " + (k + 1) + " is home. " + nameOf(id) + " is through.");
        who.removeStatusEffect(StatusEffects.SLOWNESS);
        clear(id);
        who.teleport(approach.getX() + 0.5, approach.getY(), approach.getZ() + 0.5);
    }

    private void explode(UUID id, String why) {
        Integer k = holding.remove(id);
        if (k == null) {
            return;
        }
        gone[k] = true;
        LivingEntity e = entity(id);
        if (e != null) {
            world.playSound(null, e.getX(), e.getY(), e.getZ(), SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.PLAYERS, 3.0F, 1.0F);
            world.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, e.getX(), e.getY() + 1, e.getZ(), 1, 0, 0, 0, 0);
            for (LivingEntity near : living()) {
                if (near != e && near.squaredDistanceTo(e) < 4 * 4) {
                    near.damage(Laser.BLAST, 6.0F);
                    Vec3d push = near.getPos().subtract(e.getPos()).normalize().multiply(0.8);
                    near.addVelocity(push.x, 0.4, push.z);
                    near.velocityModified = true;
                }
            }
        }
        announce("Can " + (k + 1) + " went off.");
        kill(id, Laser.BLAST, why);
    }

    @Override
    protected ActionResult use(ServerPlayerEntity player, BlockPos pos, BlockState state) {
        for (int k = 0; k < CANS; k++) {
            if (pos.equals(pile[k])) {
                pickUp(player, k);
                return ActionResult.SUCCESS;
            }
            if (pos.equals(base[k])) {
                place(player, k);
                return ActionResult.SUCCESS;
            }
        }
        return ActionResult.PASS;
    }

    @Override
    protected boolean touched(ServerPlayerEntity player, Entity target) {
        if (target instanceof LivingEntity living && holding.containsKey(player.getUuid())) {
            handOff(player, living);
            return true;
        }
        return false;
    }

    @Override
    protected void died(UUID id) {
        if (holding.containsKey(id)) {
            Integer k = holding.remove(id);
            gone[k] = true;
            announce("Can " + (k + 1) + " went off.");
        }
    }

    @Override
    protected void running() {
        ticks++;
        cool.replaceAll((id, t) -> Math.max(0, t - 1));
        for (Map.Entry<UUID, Integer> e : new ArrayList<>(holding.entrySet())) {
            UUID id = e.getKey();
            int k = e.getValue();
            LivingEntity who = entity(id);
            if (who == null) {
                continue;
            }
            who.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 10, 2, false, false));
            if (ticks % 4 == 0) {
                world.spawnParticles(ParticleTypes.SMOKE, who.getX(), who.getY() + 2.1, who.getZ(), 2, 0.05, 0.05, 0.05, 0.01);
            }
            if (fuse[k] % 20 == 0 && fuse[k] <= 200) {
                world.playSound(null, who.getX(), who.getY(), who.getZ(), SoundEvents.BLOCK_NOTE_BLOCK_HAT, SoundCategory.PLAYERS, 1.0F, 2.0F);
            }
            if (--fuse[k] <= 0) {
                explode(id, "The can went off in your hands.");
            } else if (who.getHealth() < health.getOrDefault(id, who.getHealth()) - 0.01F) {
                explode(id, "You were hit while holding it.");
            }
            health.put(id, who.getHealth());
        }
        int left = 0;
        for (int k = 0; k < CANS; k++) {
            left += home[k] || gone[k] ? 0 : 1;
        }
        if (left == 0 && holding.isEmpty()) {
            announce("No cans left.");
            for (UUID id : playing()) {
                kill(id, Laser.BLAST, "There were no cans left for you.");
            }
            finish();
            return;
        }
        for (Contestant c : strangers()) {
            think(c);
        }
    }

    @Override
    protected void timeUp() {
        for (UUID id : new ArrayList<>(holding.keySet())) {
            explode(id, "Time.");
        }
        for (UUID id : playing()) {
            kill(id, Laser.BLAST, "Time. Every can left goes off.");
        }
    }

    /** Take a can, run it home, and pass it on when the fuse won't last. */
    private void think(Contestant c) {
        UUID id = c.getUuid();
        Integer k = holding.get(id);
        if (k != null) {
            Vec3d home = Vec3d.ofBottomCenter(base[k]);
            double d = Math.sqrt(c.squaredDistanceTo(home));
            if (d < 2.2) {
                place(c, k);
                return;
            }
            // too far for what's left of the fuse? find someone fresh close by
            double canGo = fuse[k] / 20.0 * 1.6;
            if (d > canGo * 0.9 && cool.getOrDefault(id, 0) == 0) {
                for (LivingEntity other : living()) {
                    if (other != c && !holding.containsKey(other.getUuid()) && other.squaredDistanceTo(c) < 3.0 * 3.0) {
                        c.say(Lines.pick(random, "Take it! Base " + (k + 1) + ", run!", "Here, I won't make it. Base " + (k + 1) + "!"));
                        handOff(c, other);
                        return;
                    }
                }
            }
            c.go(home.add(0, 0, base[k].getZ() > origin.getZ() - 26 ? -1.2 : 1.2), 1.3);
            return;
        }
        // waiting for a hand-off: anyone running a can past you
        for (Map.Entry<UUID, Integer> e : holding.entrySet()) {
            LivingEntity runner = entity(e.getKey());
            if (runner != null && fuse[e.getValue()] < 25 * 20 && runner.squaredDistanceTo(c) < 10 * 10) {
                c.go(runner.getPos(), 1.3);
                return;
            }
        }
        // or go and get one
        int best = -1;
        double d = Double.MAX_VALUE;
        for (int j = 0; j < CANS; j++) {
            if (!home[j] && !gone[j] && world.getBlockState(pile[j]).isOf(Blocks.CAULDRON)) {
                double dd = c.squaredDistanceTo(Vec3d.ofCenter(pile[j])) + (c.nature == Contestant.Nature.CLEVER ? (Math.abs(base[j].getX() - pile[j].getX()) + Math.abs(base[j].getZ() - pile[j].getZ())) * 2 : 0);
                if (dd < d) {
                    d = dd;
                    best = j;
                }
            }
        }
        if (best < 0) {
            return;
        }
        if (c.squaredDistanceTo(Vec3d.ofCenter(pile[best])) < 2.2 * 2.2) {
            if (c.nerve() > 0.3F || random.nextFloat() < 0.01F) {
                pickUp(c, best);
            }
        } else if (c.nerve() > 0.3F || ticks > 20 * 60) {
            c.go(Vec3d.ofBottomCenter(pile[best]).add(0, 0, 1.2), 1.1);
        }
    }

    // --- what a stranger can say they'll do, and then does ---------------------------------------------------

    @Override
    public Map<String, String> options(Contestant who) {
        Map<String, String> options = new LinkedHashMap<>();
        if (holding.containsKey(who.getUuid())) {
            options.put("PASS_IT", "hand your can to whoever's closest to you");
        }
        return options;
    }

    @Override
    protected Map<String, String> optionWords(Contestant who) {
        return holding.containsKey(who.getUuid()) ? Map.of("PASS_IT", "\\b(take it|here,? take|catch|pass it|hand it)\\b") : Map.of();
    }

    @Override
    public void chose(Contestant who, String option) {
        if (option.equals("PASS_IT")) {
            LivingEntity near = null;
            double best = 4 * 4;
            for (LivingEntity other : living()) {
                double d = other.squaredDistanceTo(who);
                if (other != who && d < best && !holding.containsKey(other.getUuid())) {
                    best = d;
                    near = other;
                }
            }
            if (near != null) {
                cool.put(who.getUuid(), 0);
                handOff(who, near);
            }
        }
    }

    // --- show ---------------------------------------------------------------------------------------------

    @Override
    protected void more(ServerPlayerEntity player, Hud hud) {
        Integer k = holding.get(player.getUuid());
        if (k != null) {
            hud.line("§c§lCAN " + (k + 1) + " §r§fto BASE " + (k + 1) + "  §c" + Hud.clock(fuse[k]));
        }
        int home = 0;
        int left = 0;
        for (int j = 0; j < CANS; j++) {
            home += this.home[j] ? 1 : 0;
            left += !this.home[j] && !gone[j] ? 1 : 0;
        }
        hud.line("§fCANS HOME §a" + home + "  §fLEFT §e" + left);
        for (Map.Entry<UUID, Integer> e : holding.entrySet()) {
            if (!e.getKey().equals(player.getUuid())) {
                hud.line("§7" + nameOf(e.getKey()) + ": can " + (e.getValue() + 1) + " " + Hud.clock(fuse[e.getValue()]));
            }
        }
    }

    // --- what a stranger can say they'll do, and then does ---------------------------------------------------

    @Override
    protected boolean followable() {
        return true;
    }

    @Override
    public String situation(Contestant who) {
        Integer k = holding.get(who.getUuid());
        return "You're playing KICK THE CAN in a warehouse: ten cans, each a bomb. Pick one up and its fuse starts; carry it to its own numbered "
                + "base and you're through. Hand it to someone and the fuse restarts five seconds shorter. Getting hurt while holding it sets it off. "
                + (k != null ? "You're holding can " + (k + 1) + ", with " + fuse[k] / 20 + " seconds left." : "You're not holding a can.");
    }
}
