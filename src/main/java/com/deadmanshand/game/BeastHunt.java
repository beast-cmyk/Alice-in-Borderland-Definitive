package com.deadmanshand.game;

import com.deadmanshand.Builder;
import com.deadmanshand.DeadMansHand;
import com.deadmanshand.Hud;
import com.deadmanshand.npc.Contestant;
import com.deadmanshand.npc.Lines;
import com.deadmanshand.npc.Panther;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
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
 * Six of Spades, in an amusement park after dark: the carousel, the big wheel, the haunted house, and the big cats let
 * out of their cages. Everyone is prey. Kill every predator and everyone left clears it; die and you're out. Weapons are
 * allowed: there's a rack of them by the gate, for whoever gets there first.
 */
final class BeastHunt extends Game {
    private static final Panther.Beast[] PACK = {Panther.Beast.LION, Panther.Beast.LION, Panther.Beast.CHEETAH, Panther.Beast.CHEETAH,
            Panther.Beast.LEOPARD, Panther.Beast.LEOPARD, Panther.Beast.COUGAR, Panther.Beast.PANTHER};

    private final List<UUID> beasts = new ArrayList<>();
    private final Map<UUID, Integer> swing = new HashMap<>();
    private BlockPos rack;
    private BlockPos hideout;
    private int ticks;

    BeastHunt(Kind kind, ServerWorld world, BlockPos origin) {
        super(kind, world, origin);
    }

    @Override
    protected void plan(Builder b) {
        int ox = origin.getX();
        int oy = origin.getY();
        int oz = origin.getZ();
        int x0 = ox - 24;
        int x1 = ox + 25;
        int z0 = oz - 47;
        int z1 = oz + 2;
        bounds = new Box(x0 - 1, oy - 2, z0 - 1, x1 + 2, oy + 30, z1 + 2);
        entry = new Box(ox - 4, oy, oz - 4, ox + 5, oy + 3, oz + 1);
        entryPos = new BlockPos(ox, oy, oz - 2);
        approach = new BlockPos(ox, oy, oz + 7);
        rack = new BlockPos(ox + 4, oy, oz - 5);
        hideout = new BlockPos(ox + 15, oy, oz - 14);

        BlockState air = Blocks.AIR.getDefaultState();
        BlockState path = Blocks.SMOOTH_SANDSTONE.getDefaultState();
        b.fill(x0, oy, z0, x1, oy + 28, z1, air);
        b.fill(x0, oy - 1, z0, x1, oy - 1, z1, Blocks.GRASS_BLOCK.getDefaultState());
        // The fence all round, high enough that nothing jumps it; the gate in the south side
        b.walls(x0, oy, z0, x1, oy + 2, z1, Blocks.IRON_BARS.getDefaultState());
        b.fill(ox - 2, oy, z1, ox + 2, oy + 2, z1, air);
        // Paths: a loop and a spine
        b.fill(ox - 1, oy - 1, z0 + 2, ox + 1, oy - 1, z1, path);
        b.fill(x0 + 3, oy - 1, oz - 24, x1 - 3, oy - 1, oz - 22, path);
        b.fill(x0 + 3, oy - 1, z0 + 3, x1 - 3, oy - 1, z0 + 4, path);
        // The arch over the gate
        b.fill(ox - 14, oy, z1 + 1, ox - 14, oy + 9, z1 + 1, Blocks.RED_CONCRETE.getDefaultState());
        b.fill(ox + 14, oy, z1 + 1, ox + 14, oy + 9, z1 + 1, Blocks.RED_CONCRETE.getDefaultState());
        b.fill(ox - 14, oy + 9, z1 + 1, ox + 14, oy + 10, z1 + 1, Blocks.RED_CONCRETE.getDefaultState());
        int lx = ox - 13;
        for (char c : "FUNLAND".toCharArray()) {
            String[] glyph = Glyphs.of(c);
            for (int row = 0; row < 5; row++) {
                for (int col = 0; col < 3; col++) {
                    if (glyph[row].charAt(col) == '#') {
                        b.set(lx + col, oy + 8 - row, z1 + 2, Blocks.SHROOMLIGHT.getDefaultState());
                    }
                }
            }
            lx += 4;
        }
        b.sign(new BlockPos(ox, oy + 2, z1 + 2), Direction.SOUTH, "GAME VENUE", "", "step inside", "to register");
        b.set(ox, oy + 2, z1 + 1, Blocks.RED_CONCRETE.getDefaultState());
        // Ticket booths and the weapons rack just inside
        booth(b, ox - 6, oy, oz - 3, Blocks.RED_WOOL.getDefaultState());
        booth(b, ox + 6, oy, oz - 3, Blocks.BLUE_WOOL.getDefaultState());
        b.set(rack, Blocks.CHEST.getDefaultState());
        b.sign(rack.up(), Direction.SOUTH, "WEAPONS", "allowed", "", "");
        b.set(rack.up().north(), Blocks.OAK_PLANKS.getDefaultState());
        b.then(this::stockRack);
        // The carousel
        int cx = ox - 12;
        int cz = oz - 15;
        for (int x = -5; x <= 5; x++) {
            for (int z = -5; z <= 5; z++) {
                int d = x * x + z * z;
                if (d <= 26) {
                    b.set(cx + x, oy - 1, cz + z, Blocks.SMOOTH_QUARTZ.getDefaultState());
                    b.set(cx + x, oy + 5, cz + z, (Math.floorMod(x + z, 2) == 0 ? Blocks.RED_WOOL : Blocks.WHITE_WOOL).getDefaultState());
                }
                if (d >= 9 && d <= 13 && Math.floorMod(x * 3 + z, 3) == 0) {
                    b.fill(cx + x, oy, cz + z, cx + x, oy + 4, cz + z, Blocks.OAK_FENCE.getDefaultState());
                    b.set(cx + x, oy + 1, cz + z, (Math.floorMod(x, 2) == 0 ? Blocks.WHITE_TERRACOTTA : Blocks.BROWN_TERRACOTTA).getDefaultState());
                }
            }
        }
        b.fill(cx, oy, cz, cx, oy + 5, cz, Blocks.GOLD_BLOCK.getDefaultState());
        b.set(cx, oy + 6, cz, Blocks.SEA_LANTERN.getDefaultState());
        // The big wheel, standing in the north-east corner
        int wx = ox + 12;
        int wy = oy + 13;
        int wz = oz - 38;
        for (int a = 0; a < 360; a += 4) {
            double r = Math.toRadians(a);
            int x = (int) Math.round(Math.cos(r) * 10);
            int y = (int) Math.round(Math.sin(r) * 10);
            b.set(wx + x, wy + y, wz, Blocks.IRON_BLOCK.getDefaultState());
        }
        for (int a = 0; a < 360; a += 45) {
            double r = Math.toRadians(a);
            for (int k = 1; k < 10; k++) {
                b.set(wx + (int) Math.round(Math.cos(r) * k), wy + (int) Math.round(Math.sin(r) * k), wz, Blocks.IRON_BARS.getDefaultState());
            }
            int gx = wx + (int) Math.round(Math.cos(r) * 10);
            int gy = wy + (int) Math.round(Math.sin(r) * 10);
            b.fill(gx - 1, gy - 2, wz - 1, gx + 1, gy - 1, wz + 1, (a % 90 == 0 ? Blocks.YELLOW_TERRACOTTA : Blocks.CYAN_TERRACOTTA).getDefaultState());
        }
        for (int y = oy; y < wy; y++) {
            int spread = (wy - y) / 2;
            b.set(wx - spread, y, wz, Blocks.IRON_BLOCK.getDefaultState());
            b.set(wx + spread, y, wz, Blocks.IRON_BLOCK.getDefaultState());
        }
        b.set(wx, wy, wz, Blocks.SEA_LANTERN.getDefaultState());
        // The haunted house: somewhere to hide, if you like the dark
        int hx = ox + 15;
        int hz = oz - 14;
        b.fill(hx - 5, oy - 1, hz - 5, hx + 5, oy + 6, hz + 5, Blocks.BLACK_CONCRETE.getDefaultState());
        b.fill(hx - 4, oy, hz - 4, hx + 4, oy + 4, hz + 4, air);
        b.fill(hx - 4, oy, hz - 1, hx + 1, oy + 3, hz - 1, Blocks.BLACK_CONCRETE.getDefaultState());
        b.fill(hx, oy, hz + 5, hx, oy + 1, hz + 5, air);
        b.fill(hx - 5, oy + 7, hz - 5, hx + 5, oy + 7, hz + 5, Blocks.PURPLE_TERRACOTTA.getDefaultState());
        b.set(hx, oy + 2, hz + 6, Blocks.JACK_O_LANTERN.getDefaultState());
        // Food stalls and lamp posts along the paths
        booth(b, x0 + 6, oy, oz - 30, Blocks.YELLOW_WOOL.getDefaultState());
        booth(b, x0 + 6, oy, oz - 40, Blocks.LIME_WOOL.getDefaultState());
        booth(b, ox - 5, oy, oz - 33, Blocks.ORANGE_WOOL.getDefaultState());
        for (int z = z0 + 6; z < z1 - 2; z += 7) {
            for (int x : new int[]{ox - 2, ox + 2}) {
                b.fill(x, oy, z, x, oy + 2, z, Blocks.OAK_FENCE.getDefaultState());
                b.set(x, oy + 3, z, Blocks.LANTERN.getDefaultState());
            }
        }
        // The cages the cats came out of, back by the north fence
        for (int k = 0; k < 4; k++) {
            int kx = x0 + 4 + k * 6;
            b.walls(kx, oy, z0 + 1, kx + 4, oy + 3, z0 + 5, Blocks.IRON_BARS.getDefaultState());
            b.fill(kx + 1, oy, z0 + 5, kx + 3, oy + 2, z0 + 5, air);
            b.fill(kx, oy + 4, z0 + 1, kx + 4, oy + 4, z0 + 5, Blocks.SMOOTH_STONE_SLAB.getDefaultState());
        }
    }

    private void booth(Builder b, int x, int y, int z, BlockState cloth) {
        b.fill(x - 1, y, z - 1, x + 1, y, z + 1, Blocks.OAK_PLANKS.getDefaultState());
        b.set(x, y, z, Blocks.AIR.getDefaultState());
        b.fill(x - 1, y + 1, z - 1, x - 1, y + 2, z - 1, Blocks.OAK_FENCE.getDefaultState());
        b.fill(x + 1, y + 1, z - 1, x + 1, y + 2, z - 1, Blocks.OAK_FENCE.getDefaultState());
        b.fill(x - 1, y + 1, z + 1, x - 1, y + 2, z + 1, Blocks.OAK_FENCE.getDefaultState());
        b.fill(x + 1, y + 1, z + 1, x + 1, y + 2, z + 1, Blocks.OAK_FENCE.getDefaultState());
        b.fill(x - 1, y + 3, z - 1, x + 1, y + 3, z + 1, cloth);
    }

    private void stockRack() {
        if (world.getBlockEntity(rack) instanceof ChestBlockEntity chest) {
            chest.setStack(10, new ItemStack(Items.IRON_SWORD));
            chest.setStack(11, new ItemStack(Items.IRON_SWORD));
            chest.setStack(12, new ItemStack(Items.IRON_AXE));
            chest.setStack(13, new ItemStack(Items.BOW));
            chest.setStack(14, new ItemStack(Items.ARROW, 24));
            chest.setStack(15, new ItemStack(Items.CROSSBOW));
            chest.setStack(16, new ItemStack(Items.ARROW, 16));
            chest.setStack(4, new ItemStack(Items.STONE_SWORD));
        }
    }

    @Override
    protected String[] rules() {
        return new String[]{
                "You are all zebras. The predators are loose in the park.",
                "Kill every predator, and it's GAME CLEAR for everyone still alive.",
                "If a predator kills you, it's game over for you. If they kill everyone, the game is over.",
                "Weapons are allowed. There's a rack by the gate.",
                "There is no time limit."};
    }

    @Override
    protected int timeLimit() {
        return 20 * 60 * 30;
    }

    private void gate(BlockState state) {
        for (int x = origin.getX() - 2; x <= origin.getX() + 2; x++) {
            for (int y = origin.getY(); y <= origin.getY() + 2; y++) {
                world.setBlockState(new BlockPos(x, y, origin.getZ() + 2), state, net.minecraft.block.Block.NOTIFY_ALL);
            }
        }
    }

    @Override
    protected void arrange() {
        gate(Blocks.IRON_BARS.getDefaultState());
        stockRack();
        int i = 0;
        for (LivingEntity entity : living()) {
            entity.teleport(origin.getX() - 3.5 + (i % 8), origin.getY(), origin.getZ() - 7.5 - (i / 8));
            if (entity instanceof Contestant contestant) {
                contestant.hold();
            }
            i++;
        }
    }

    @Override
    protected void begin() {
        int ox = origin.getX();
        int oz = origin.getZ();
        for (int k = 0; k < PACK.length; k++) {
            Panther beast = DeadMansHand.PANTHER.create(world);
            if (beast == null) {
                continue;
            }
            int kx = ox - 24 + 4 + (k % 4) * 6 + 2;
            beast.refreshPositionAndAngles(kx + 0.5, origin.getY(), oz - 47 + 3.5, 180.0F, 0.0F);
            beast.become(PACK[k]);
            beast.addScoreboardTag(DeadMansHand.ID);
            world.spawnEntity(beast);
            beasts.add(beast.getUuid());
        }
        world.playSound(null, ox, origin.getY(), oz - 40, SoundEvents.ENTITY_HORSE_ANGRY, SoundCategory.HOSTILE, 3.0F, 0.4F);
        announce("The cages are open.");
        for (Contestant contestant : strangers()) {
            contestant.stopAll();
        }
    }

    private List<Panther> alive() {
        List<Panther> list = new ArrayList<>();
        for (UUID id : beasts) {
            Entity entity = world.getEntity(id);
            if (entity instanceof Panther beast && beast.isAlive()) {
                list.add(beast);
            }
        }
        return list;
    }

    @Override
    protected void running() {
        ticks++;
        List<Panther> pack = alive();
        if (pack.isEmpty()) {
            announce("Every predator is dead.");
            for (UUID id : playing()) {
                clear(id);
            }
            finish();
            return;
        }
        for (Panther beast : pack) {
            if (!bounds.contains(beast.getPos())) {
                beast.refreshPositionAndAngles(origin.getX() + 0.5, origin.getY(), origin.getZ() - 30.5, beast.getYaw(), 0.0F);
            }
        }
        for (Contestant contestant : strangers()) {
            think(contestant, pack);
        }
    }

    /** The strangers: the brave ones take a weapon and go hunting, the rest run and hide. */
    private void think(Contestant contestant, List<Panther> pack) {
        Panther nearest = null;
        double best = Double.MAX_VALUE;
        for (Panther beast : pack) {
            double d = contestant.squaredDistanceTo(beast);
            if (d < best) {
                best = d;
                nearest = beast;
            }
        }
        if (nearest == null) {
            return;
        }
        String said = told.get(contestant.getUuid());
        boolean fighter = "FIGHT".equals(said) || !"HIDE".equals(said) && (contestant.nerve() > 0.6F
                || contestant.nature == Contestant.Nature.CLEVER && pack.size() <= 3);
        if (fighter) {
            contestant.look(nearest);
            contestant.go(nearest.getBlockPos(), 1.25);
            int ready = swing.merge(contestant.getUuid(), -1, Integer::sum);
            if (best < 2.6 * 2.6 && ready <= 0) {
                swing.put(contestant.getUuid(), 14);
                contestant.swingHand(net.minecraft.util.Hand.MAIN_HAND);
                nearest.damage(DamageSource.mob(contestant), 4.0F + random.nextInt(4));
                if (!nearest.isAlive()) {
                    contestant.say(Lines.pick(random, "Got one!", "That's one down!", "Come on, who's next?"));
                }
            }
            if (random.nextFloat() < 0.003F) {
                contestant.speak("You're hunting big cats through an amusement park with a weapon from the rack.",
                        Lines.pick(random, "Stay together! They pick off the ones who stray!", "Behind the carousel!", "Watch the cheetahs, they're fast!"));
            }
        } else if (best < 14.0 * 14.0) {
            Vec3d away = contestant.getPos().subtract(nearest.getPos()).normalize().multiply(10.0);
            BlockPos run = best < 8.0 * 8.0 ? hideout : new BlockPos(contestant.getPos().add(away));
            contestant.go(run, 1.35);
            if (random.nextFloat() < 0.01F) {
                contestant.speak("A big cat is coming your way and you have no weapon.", Lines.pick(random, "Run!", "It's coming! It's coming!",
                        "Into the haunted house, quick!"));
            }
        } else if (contestant.arrived(1.5)) {
            contestant.go(hideout.add(random.nextInt(5) - 2, 0, random.nextInt(5) - 2), 0.9);
        }
    }

    @Override
    protected void more(ServerPlayerEntity player, Hud hud) {
        hud.line("§fPREDATORS LEFT §c" + alive().size() + "§7 of " + PACK.length);
    }

    // --- what a stranger can say they'll do, and then does ---------------------------------------------------

    @Override
    protected boolean followable() {
        return true;
    }

    @Override
    public Map<String, String> options(Contestant who) {
        return isPlaying(who.getUuid()) ? Map.of("FIGHT", "turn and fight the big cats with whatever you're holding",
                "HIDE", "keep away from them and hide in the haunted house") : Map.of();
    }

    @Override
    protected Map<String, String> optionWords(Contestant who) {
        return Map.of("FIGHT", "\\b(fight (them|it|back)|take (them|it) on|kill (them|it))\\b", "HIDE", "\\b(hide|hiding|haunted house)\\b");
    }

    @Override
    public String situation(Contestant who) {
        return "You're playing BEAST HUNT, the six of spades, in a dark amusement park: lions, cheetahs, leopards, a cougar and a black "
                + "panther have been let out of their cages. Everyone is prey; if all the predators die, everyone left clears it. Weapons "
                + "are allowed and there was a rack of them by the gate. " + alive().size() + " predators are still alive.";
    }

    @Override
    protected void ended() {
        for (Panther beast : alive()) {
            beast.discard();
        }
        beasts.clear();
        gate(Blocks.AIR.getDefaultState());
    }
}
