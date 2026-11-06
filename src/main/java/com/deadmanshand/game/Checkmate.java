package com.deadmanshand.game;

import com.deadmanshand.Builder;
import com.deadmanshand.Hud;
import com.deadmanshand.npc.Contestant;
import com.deadmanshand.npc.Lines;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.DyeableItem;
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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Queen of Spades, in a power station's yard: sixteen challengers in blue against the queen's four in red. Every vest has
 * a button on its back. The teams take turns, sixteen rounds: on your turn, chase the others and press the buttons on
 * their backs. Pressed, you're shocked still for the rest of the turn, and next round you're on the other side. Each
 * team's king can't change sides. After sixteen rounds the bigger team clears; the smaller one dies.
 */
final class Checkmate extends Game {
    private static final int ROUNDS = 16;
    private static final int ROUND = 20 * 40;

    /** true: blue (challengers); false: red (the queen's). */
    private final Map<UUID, Boolean> blue = new HashMap<>();
    private final Set<UUID> shocked = new HashSet<>();
    private final Set<UUID> switching = new HashSet<>();
    private final Map<UUID, ItemStack> chests = new HashMap<>();
    private final Map<UUID, Integer> press = new HashMap<>();
    private UUID queen;
    private UUID blueKing;
    private int round;
    private int left;
    private int oy;

    Checkmate(Kind kind, ServerWorld world, BlockPos origin) {
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
        bounds = new Box(x0, oy - 2, z0, x1 + 1, oy + 24, z1 + 1);
        entry = new Box(ox - 3, oy, oz - 4, ox + 4, oy + 3, oz + 1);
        entryPos = new BlockPos(ox, oy, oz - 2);
        approach = new BlockPos(ox, oy, oz + 7);
        BlockState air = Blocks.AIR.getDefaultState();
        BlockState steel = Blocks.IRON_BLOCK.getDefaultState();
        BlockState white = Blocks.WHITE_CONCRETE.getDefaultState();
        b.fill(x0, oy, z0, x1, oy + 22, z1, air);
        b.fill(x0, oy - 1, z0, x1, oy - 1, z1, Blocks.GRAY_CONCRETE.getDefaultState());
        b.walls(x0, oy, z0, x1, oy + 2, z1, Blocks.IRON_BARS.getDefaultState());
        b.fill(ox - 2, oy, z1, ox + 2, oy + 2, z1, air);
        // Three storage tanks
        for (int[] t : new int[][]{{ox - 14, oz - 36, 6}, {ox + 12, oz - 34, 5}, {ox - 12, oz - 14, 4}}) {
            for (int y = oy; y < oy + 12; y++) {
                for (int x = -t[2]; x <= t[2]; x++) {
                    for (int z = -t[2]; z <= t[2]; z++) {
                        int d = x * x + z * z;
                        if (d <= t[2] * t[2] && d > (t[2] - 1) * (t[2] - 1)) {
                            b.set(t[0] + x, y, t[1] + z, white);
                        }
                    }
                }
            }
            b.fill(t[0] - t[2] + 1, oy + 12, t[1] - t[2] + 1, t[0] + t[2] - 1, oy + 12, t[1] + t[2] - 1, Blocks.SMOOTH_STONE_SLAB.getDefaultState());
        }
        // Pipes on legs to run along and swing from, and scaffolding to climb to them
        for (int z : new int[]{oz - 25, oz - 21}) {
            b.fill(x0 + 3, oy + 5, z, x1 - 3, oy + 5, z, steel);
            for (int x = x0 + 4; x < x1 - 3; x += 7) {
                b.fill(x, oy, z, x, oy + 4, z, Blocks.IRON_BARS.getDefaultState());
            }
        }
        for (int[] s : new int[][]{{ox, oz - 23}, {ox - 20, oz - 23}, {ox + 20, oz - 23}}) {
            b.fill(s[0], oy, s[1], s[0], oy + 5, s[1], Blocks.SCAFFOLDING.getDefaultState());
        }
        // A turbine house and crates for cover
        b.fill(ox + 6, oy, oz - 14, ox + 16, oy + 6, oz - 7, Blocks.LIGHT_GRAY_CONCRETE.getDefaultState());
        b.fill(ox + 7, oy, oz - 13, ox + 15, oy + 5, oz - 8, air);
        b.fill(ox + 6, oy, oz - 11, ox + 6, oy + 2, oz - 10, air);
        b.fill(ox + 16, oy, oz - 11, ox + 16, oy + 2, oz - 10, air);
        for (int k = 0; k < 10; k++) {
            int x = x0 + 4 + Math.floorMod(k * 17, 42);
            int z = z0 + 4 + Math.floorMod(k * 23, 40);
            b.fill(x, oy, z, x + 1, oy + Math.floorMod(k, 2), z + 1, Blocks.BARREL.getDefaultState());
        }
        // Floodlights
        for (int[] l : new int[][]{{x0 + 1, z0 + 1}, {x1 - 1, z0 + 1}, {x0 + 1, z1 - 1}, {x1 - 1, z1 - 1}}) {
            b.fill(l[0], oy, l[1], l[0], oy + 9, l[1], Blocks.IRON_BARS.getDefaultState());
            b.set(l[0], oy + 10, l[1], Blocks.SEA_LANTERN.getDefaultState());
        }
        b.sign(new BlockPos(ox + 3, oy + 2, z1 + 1), Direction.SOUTH, "GAME VENUE", "", "step inside", "to register");
        b.set(ox + 3, oy + 2, z1, Blocks.IRON_BLOCK.getDefaultState());
        b.fill(ox + 3, oy, z1, ox + 3, oy + 1, z1, Blocks.IRON_BLOCK.getDefaultState());
    }

    @Override
    protected String[] rules() {
        return new String[]{
                "Challengers wear blue, the Queen's team red. Every vest has a button on its back.",
                "The teams take turns, sixteen rounds. On your turn, press the buttons on the others' backs (use them from behind).",
                "Pressed, you're shocked still for the rest of the turn, and next round you're on the other team.",
                "Each team has a king, who can't change teams. Fighting with your hands is allowed.",
                "After sixteen rounds the bigger team clears the game. The other team dies."};
    }

    @Override
    protected int timeLimit() {
        return ROUNDS * ROUND + 20 * 20;
    }

    @Override
    protected void arrange() {
        int ox = origin.getX();
        int oz = origin.getZ();
        Contestant q = citizen("Risa", new BlockPos(ox, oy, oz - 40));
        if (q != null) {
            queen = q.getUuid();
        }
        for (int k = 0; k < 3; k++) {
            citizen(new String[]{"Kaname", "Yuri", "Sato"}[k], new BlockPos(ox - 4 + 4 * k, oy, oz - 38));
        }
        int i = 0;
        for (UUID id : playing()) {
            LivingEntity entity = entity(id);
            if (entity == null) {
                continue;
            }
            boolean red = entity instanceof Contestant c && c.role.equals("citizen");
            blue.put(id, !red);
            if (!red) {
                entity.teleport(ox - 7.5 + (i % 15), oy, oz - 6.5 - (i / 15));
                i++;
            }
            vest(id);
            if (entity instanceof Contestant c) {
                c.hold();
            }
        }
        // The Queen names the challengers' king
        for (UUID id : playing()) {
            if (blue.get(id) && entity(id) instanceof Contestant) {
                blueKing = id;
                break;
            }
        }
        if (blueKing != null) {
            announce("The Queen has chosen the challengers' king: " + nameOf(blueKing) + ".");
        }
    }

    private void vest(UUID id) {
        LivingEntity entity = entity(id);
        if (entity == null) {
            return;
        }
        ItemStack vest = new ItemStack(Items.LEATHER_CHESTPLATE);
        ((DyeableItem) Items.LEATHER_CHESTPLATE).setColor(vest, blue.get(id) ? 0x2050D0 : 0xC02020);
        vest.setCustomName(Text.literal(blue.get(id) ? "Blue vest" : "Red vest"));
        if (entity instanceof ServerPlayerEntity player && !chests.containsKey(id)) {
            chests.put(id, player.getEquippedStack(EquipmentSlot.CHEST).copy());
        }
        entity.equipStack(EquipmentSlot.CHEST, vest);
    }

    @Override
    protected void begin() {
        nextRound();
    }

    private boolean blueTurn() {
        return round % 2 == 1;
    }

    private void nextRound() {
        for (UUID id : switching) {
            if (isPlaying(id) && !id.equals(queen) && !id.equals(blueKing)) {
                blue.put(id, !blue.get(id));
                vest(id);
                tell(entity(id), "You're on the " + (blue.get(id) ? "blue" : "red") + " team now.", blue.get(id) ? Formatting.BLUE : Formatting.RED);
            }
        }
        switching.clear();
        shocked.clear();
        round++;
        left = ROUND;
        announce("Round " + round + " of " + ROUNDS + ". " + (blueTurn() ? "Blue" : "Red") + " hunts. Blue " + count(true) + ", red " + count(false) + ".");
    }

    private int count(boolean side) {
        int n = 0;
        for (UUID id : playing()) {
            if (blue.getOrDefault(id, true) == side) {
                n++;
            }
        }
        return n;
    }

    @Override
    protected void running() {
        if (--left <= 0) {
            if (round >= ROUNDS) {
                end();
                return;
            }
            nextRound();
        }
        for (UUID id : shocked) {
            LivingEntity entity = entity(id);
            if (entity != null) {
                entity.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 10, 10, false, false));
                if (left % 10 == 0) {
                    world.spawnParticles(ParticleTypes.ELECTRIC_SPARK, entity.getX(), entity.getY() + 1.2, entity.getZ(), 4, 0.2, 0.3, 0.2, 0.05);
                }
            }
        }
        strangersThink();
    }

    private void end() {
        int b = count(true);
        int r = count(false);
        boolean blueWins = b > r;
        announce("Sixteen rounds. Blue " + b + ", red " + r + ". " + (blueWins ? "Blue" : "Red") + " wins.");
        for (UUID id : playing()) {
            if (blue.getOrDefault(id, true) == blueWins) {
                clear(id);
            } else {
                fail(id, "Your team was smaller.");
            }
        }
        finish();
    }

    /** Is {@code attacker} behind {@code target}? */
    private static boolean behind(LivingEntity attacker, LivingEntity target) {
        Vec3d facing = Vec3d.fromPolar(0.0F, target.getBodyYaw());
        Vec3d to = attacker.getPos().subtract(target.getPos()).multiply(1, 0, 1).normalize();
        return facing.dotProduct(to) < -0.3;
    }

    private void pressButton(LivingEntity attacker, LivingEntity target) {
        UUID id = target.getUuid();
        if (shocked.contains(id)) {
            return;
        }
        shocked.add(id);
        if (!id.equals(queen) && !id.equals(blueKing)) {
            switching.add(id);
        }
        world.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.ENTITY_LIGHTNING_BOLT_IMPACT, SoundCategory.PLAYERS, 0.6F, 2.0F);
        world.spawnParticles(ParticleTypes.ELECTRIC_SPARK, target.getX(), target.getY() + 1.2, target.getZ(), 20, 0.3, 0.4, 0.3, 0.1);
        target.damage(net.minecraft.entity.damage.DamageSource.LIGHTNING_BOLT, 1.0F);
        if (target instanceof Contestant c) {
            c.hold();
        }
        tell(target, "Your button was pressed. You can't move until the turn ends.", Formatting.RED);
        tell(attacker, "You pressed " + nameOf(id) + "'s button.", Formatting.GREEN);
    }

    @Override
    protected boolean touched(ServerPlayerEntity player, Entity target) {
        if (!(target instanceof LivingEntity living) || !isPlaying(target.getUuid()) || shocked.contains(player.getUuid())) {
            return false;
        }
        boolean mine = blue.getOrDefault(player.getUuid(), true);
        if (mine != blueTurn() || blue.getOrDefault(target.getUuid(), true) == mine) {
            tell(player, mine != blueTurn() ? "It's not your team's turn to hunt." : "That's your own team.", Formatting.GRAY);
            return true;
        }
        if (!behind(player, living)) {
            tell(player, "Get behind them: the button's on their back.", Formatting.GRAY);
            return true;
        }
        pressButton(player, living);
        return true;
    }

    private void strangersThink() {
        boolean blueHunts = blueTurn();
        for (Contestant c : strangers()) {
            UUID id = c.getUuid();
            if (shocked.contains(id)) {
                c.hold();
                continue;
            }
            boolean mine = blue.getOrDefault(id, true);
            LivingEntity nearest = null;
            double best = Double.MAX_VALUE;
            for (LivingEntity other : living()) {
                if (blue.getOrDefault(other.getUuid(), true) == mine || shocked.contains(other.getUuid())) {
                    continue;
                }
                double d = c.squaredDistanceTo(other);
                if (d < best) {
                    best = d;
                    nearest = other;
                }
            }
            if (nearest == null) {
                continue;
            }
            if (mine == blueHunts) {
                // circle round behind them
                Vec3d back = nearest.getPos().subtract(Vec3d.fromPolar(0.0F, nearest.getBodyYaw()).multiply(1.4));
                c.go(back, 1.3);
                c.look(nearest);
                int ready = press.merge(id, -1, Integer::sum);
                if (best < 2.0 * 2.0 && ready <= 0 && behind(c, nearest)) {
                    press.put(id, 10);
                    c.swingHand(net.minecraft.util.Hand.MAIN_HAND);
                    pressButton(c, nearest);
                    if (random.nextFloat() < 0.3F) {
                        c.say(Lines.pick(random, "Got you!", "You're ours next round.", "Button!"));
                    }
                }
            } else {
                // keep your back away from them
                c.look(nearest);
                if (best < 10 * 10) {
                    Vec3d away = c.getPos().subtract(nearest.getPos()).normalize().multiply(5.0);
                    c.go(c.getPos().add(away), 1.3);
                }
            }
        }
    }

    @Override
    protected void more(ServerPlayerEntity player, Hud hud) {
        boolean mine = blue.getOrDefault(player.getUuid(), true);
        hud.role(mine ? "BLUE" : "RED", mine ? 0x4070FF : 0xFF3030);
        hud.line("§fROUND §e" + round + "/" + ROUNDS + "  " + (mine == blueTurn() ? "§aYOU HUNT " : "§cTHEY HUNT ") + Hud.clock(left));
        hud.line("§9BLUE " + count(true) + "  §cRED " + count(false));
        if (shocked.contains(player.getUuid())) {
            hud.line("§cSHOCKED until the turn ends");
        }
    }

    // --- what a stranger can say they'll do, and then does ---------------------------------------------------

    @Override
    protected boolean followable() {
        return true;
    }

    @Override
    public String situation(Contestant who) {
        boolean mine = blue.getOrDefault(who.getUuid(), true);
        return "You're playing CHECKMATE, the queen of spades, in a power station yard. You're on the " + (mine ? "blue (challengers)" : "red "
                + "(the Queen's)") + " team. Teams take turns; on your turn you press buttons on the backs of the other team's vests, which "
                + "shocks them and turns them to your side next round. Round " + round + " of 16; blue " + count(true) + ", red " + count(false) + ".";
    }

    @Override
    protected void ended() {
        for (UUID id : seats.keySet()) {
            LivingEntity entity = entity(id);
            if (entity instanceof ServerPlayerEntity player) {
                ItemStack old = chests.get(id);
                player.equipStack(EquipmentSlot.CHEST, old == null ? ItemStack.EMPTY : old);
            }
        }
    }
}
