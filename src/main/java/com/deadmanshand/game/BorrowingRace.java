package com.deadmanshand.game;

import com.deadmanshand.Builder;
import com.deadmanshand.DeadMansHand;
import com.deadmanshand.Hud;
import com.deadmanshand.npc.Contestant;
import com.deadmanshand.npc.Lines;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.RedstoneLampBlock;
import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.DyeableItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
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
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Two of Clubs, in a power plant with the alarm going. The rules were never shown on screen, only teams of two and
 * three running the corridors after something. Here it is a borrowing race: each pair draws a slip naming a thing,
 * the thing is somewhere in the plant, and the pair has to bring it to the control room desk together before the alarm
 * stops.
 */
final class BorrowingRace extends Game {
    private static final String[] THINGS = {"a red hard hat", "the shift log", "a pressure gauge", "a fuse", "the master key",
            "a coil of cable", "a safety lamp", "a fire bucket", "a spanner"};

    private static final class Team {
        final List<UUID> members = new ArrayList<>();
        int thing;
        UUID carrier;
        boolean done;
    }

    private final List<Team> teams = new ArrayList<>();
    private final List<BlockPos> barrels = new ArrayList<>();
    private final List<BlockPos> lamps = new ArrayList<>();
    private BlockPos desk;
    private int ticks;

    BorrowingRace(Kind kind, ServerWorld world, BlockPos origin) {
        super(kind, world, origin);
    }

    @Override
    protected void plan(Builder b) {
        int ox = origin.getX();
        int oy = origin.getY();
        int oz = origin.getZ();
        bounds = new Box(ox - 24, oy - 2, oz - 46, ox + 25, oy + 16, oz + 3);
        entry = new Box(ox - 3, oy, oz - 4, ox + 4, oy + 3, oz + 1);
        entryPos = new BlockPos(ox, oy, oz - 2);
        approach = new BlockPos(ox, oy, oz + 6);
        desk = new BlockPos(ox, oy, oz - 42);

        BlockState air = Blocks.AIR.getDefaultState();
        BlockState wall = Blocks.LIGHT_GRAY_CONCRETE.getDefaultState();
        BlockState dark = Blocks.GRAY_CONCRETE.getDefaultState();
        int x0 = ox - 23;
        int x1 = ox + 23;
        int z0 = oz - 45;
        int z1 = oz - 6;
        int top = oy + 14;
        // The turbine hall
        b.fill(x0, oy - 1, z0, x1, top, z1, wall);
        b.fill(x0 + 1, oy, z0 + 1, x1 - 1, top - 1, z1 - 1, air);
        b.fill(x0 + 1, oy - 1, z0 + 1, x1 - 1, oy - 1, z1 - 1, Blocks.POLISHED_ANDESITE.getDefaultState());
        for (int x = x0 + 2; x < x1; x += 2) {
            b.fill(x, oy + 9, z0, x, oy + 11, z0, Blocks.GLASS.getDefaultState());
            b.fill(x, oy + 9, z1, x, oy + 11, z1, Blocks.GLASS.getDefaultState());
        }
        for (int x = x0 + 4; x < x1; x += 8) {
            b.set(x, top - 1, oz - 25, Blocks.SEA_LANTERN.getDefaultState());
            b.set(x, top - 1, oz - 12, Blocks.SEA_LANTERN.getDefaultState());
            b.set(x, top - 1, oz - 38, Blocks.SEA_LANTERN.getDefaultState());
        }
        // Three turbines, end to end across the hall, each with its generator
        for (int z : new int[]{oz - 33, oz - 25, oz - 17}) {
            b.fill(ox - 13, oy, z - 1, ox + 13, oy + 2, z + 1, dark);
            b.fill(ox - 13, oy + 1, z - 1, ox + 13, oy + 1, z + 1, Blocks.IRON_BLOCK.getDefaultState());
            b.fill(ox - 12, oy + 3, z, ox + 12, oy + 3, z, Blocks.IRON_BARS.getDefaultState());
            b.fill(ox + 10, oy, z - 1, ox + 13, oy + 3, z + 1, Blocks.CYAN_TERRACOTTA.getDefaultState());
            for (int x = ox - 12; x <= ox + 8; x += 5) {
                b.fill(x, oy + 3, z, x, top - 1, z, Blocks.IRON_BARS.getDefaultState());
            }
        }
        // Storage bays down both sides, barrels in each
        for (int side = -1; side <= 1; side += 2) {
            int inner = ox + side * 18;
            int outer = ox + side * 22;
            for (int k = 0; k < 4; k++) {
                int zc = oz - 41 + 8 * k;
                b.fill(Math.min(inner, outer), oy, zc - 3, Math.max(inner, outer), oy + 4, zc - 3, dark);
                b.fill(Math.min(inner, outer), oy, zc + 3, Math.max(inner, outer), oy + 4, zc + 3, dark);
                BlockPos one = new BlockPos(outer, oy, zc - 1);
                BlockPos two = new BlockPos(outer - side, oy, zc + 1);
                for (BlockPos at : new BlockPos[]{one, two}) {
                    b.set(at, Blocks.BARREL.getDefaultState());
                    barrels.add(at);
                }
                b.set(outer, oy, zc + 2, Blocks.OAK_PLANKS.getDefaultState());
                b.set(outer, oy + 1, zc + 2, Blocks.BARREL.getDefaultState());
            }
        }
        // The control room, glassed in, with the desk where things are handed in
        b.fill(ox - 6, oy, oz - 44, ox + 6, oy + 4, oz - 37, dark);
        b.fill(ox - 5, oy, oz - 43, ox + 5, oy + 3, oz - 38, air);
        b.fill(ox - 5, oy + 1, oz - 37, ox + 5, oy + 2, oz - 37, Blocks.GLASS.getDefaultState());
        b.fill(ox, oy, oz - 37, ox, oy + 1, oz - 37, air);
        b.set(desk, Blocks.LECTERN.getDefaultState().with(HorizontalFacingBlock.FACING, Direction.SOUTH));
        b.fill(ox - 5, oy, oz - 43, ox - 2, oy, oz - 43, Blocks.CYAN_TERRACOTTA.getDefaultState());
        b.fill(ox + 2, oy, oz - 43, ox + 5, oy, oz - 43, Blocks.CYAN_TERRACOTTA.getDefaultState());
        b.set(ox, oy + 3, oz - 40, Blocks.SEA_LANTERN.getDefaultState());
        b.sign(new BlockPos(ox, oy + 2, oz - 36), Direction.SOUTH, "CONTROL", "ROOM", "hand in", "here");
        // The alarm lights
        for (int z : new int[]{oz - 40, oz - 28, oz - 16}) {
            for (int x : new int[]{x0 + 1, x1 - 1}) {
                BlockPos lamp = new BlockPos(x, oy + 6, z);
                b.set(lamp, Blocks.REDSTONE_LAMP.getDefaultState());
                lamps.add(lamp);
            }
        }
        // The gatehouse on the street
        b.fill(ox - 4, oy - 1, z1 + 1, ox + 4, oy + 4, oz + 1, dark);
        b.fill(ox - 3, oy, z1 + 1, ox + 3, oy + 3, oz, air);
        b.fill(ox - 1, oy, oz + 1, ox + 1, oy + 2, oz + 1, air);
        b.fill(ox - 1, oy, z1, ox + 1, oy + 2, z1, air);
        b.set(ox, oy + 3, oz - 2, Blocks.SEA_LANTERN.getDefaultState());
        b.sign(new BlockPos(ox, oy + 3, oz + 2), Direction.SOUTH, "GAME VENUE", "", "step inside", "to register");
        // A cooling tower beside it, for the skyline
        int tx = x1 - 5;
        int tz = z0 + 5;
        for (int y = top + 1; y <= top + 18; y++) {
            int r = y < top + 12 ? 4 : 3;
            for (int x = -r; x <= r; x++) {
                for (int z = -r; z <= r; z++) {
                    int d = x * x + z * z;
                    if (d <= r * r && d > (r - 1) * (r - 1)) {
                        b.set(tx + x, y, tz + z, wall);
                    }
                }
            }
        }
    }

    private static ItemStack thing(int index) {
        Item item = switch (index) {
            case 0 -> Items.LEATHER_HELMET;
            case 1 -> Items.WRITABLE_BOOK;
            case 2 -> Items.CLOCK;
            case 3 -> Items.REDSTONE_TORCH;
            case 4 -> Items.TRIPWIRE_HOOK;
            case 5 -> Items.CHAIN;
            case 6 -> Items.LANTERN;
            case 7 -> Items.BUCKET;
            default -> Items.IRON_INGOT;
        };
        ItemStack stack = new ItemStack(item);
        if (index == 0) {
            ((DyeableItem) Items.LEATHER_HELMET).setColor(stack, 0xC02020);
        }
        String name = THINGS[index].replaceFirst("^(a|the) ", "");
        stack.setCustomName(Text.literal(Character.toUpperCase(name.charAt(0)) + name.substring(1)).formatted(Formatting.YELLOW));
        stack.getOrCreateNbt().putInt("DmhBorrow", index);
        return stack;
    }

    private static int thingOf(ItemStack stack) {
        return stack.hasNbt() && stack.getNbt().contains("DmhBorrow") ? stack.getNbt().getInt("DmhBorrow") : -1;
    }

    @Override
    protected String[] rules() {
        return new String[]{
                "You play in pairs. Each pair draws a slip with something to borrow.",
                "The thing on your slip is somewhere in this plant. Find it.",
                "Bring it to the control room and hand it in at the desk, with your partner beside you.",
                "When the alarm stops, every pair that hasn't handed theirs in loses.",
                "Time limit: eight minutes."};
    }

    @Override
    protected int timeLimit() {
        return 20 * 60 * 8;
    }

    @Override
    protected void arrange() {
        List<LivingEntity> everyone = living();
        Collections.shuffle(everyone, new java.util.Random(random.nextLong()));
        List<Integer> deck = new ArrayList<>();
        for (int i = 0; i < THINGS.length; i++) {
            deck.add(i);
        }
        Collections.shuffle(deck, new java.util.Random(random.nextLong()));
        // Pairs; with an odd number, the last one makes a three
        for (int i = 0; i < everyone.size(); i += 2) {
            Team team = new Team();
            team.thing = deck.remove(0);
            team.members.add(everyone.get(i).getUuid());
            if (i + 1 < everyone.size()) {
                team.members.add(everyone.get(i + 1).getUuid());
            }
            teams.add(team);
        }
        if (everyone.size() % 2 == 1 && teams.size() > 1) {
            Team lone = teams.remove(teams.size() - 1);
            teams.get(teams.size() - 1).members.addAll(lone.members);
            deck.add(lone.thing);
        }
        // Hide the things: each team's in a barrel of its own, with junk and a few decoys in the rest
        List<BlockPos> spots = new ArrayList<>(barrels);
        Collections.shuffle(spots, new java.util.Random(random.nextLong()));
        int s = 0;
        for (Team t : teams) {
            put(spots.get(s++), thing(t.thing));
        }
        for (; s < spots.size(); s++) {
            put(spots.get(s), random.nextFloat() < 0.35F && !deck.isEmpty() ? thing(deck.get(random.nextInt(deck.size())))
                    : new ItemStack(random.nextBoolean() ? Items.COAL : Items.PAPER, 1 + random.nextInt(4)));
        }
        int i = 0;
        for (LivingEntity entity : everyone) {
            entity.teleport(origin.getX() - 4.5 + (i % 10), origin.getY(), origin.getZ() - 9.5 - (i / 10));
            if (entity instanceof ServerPlayerEntity player) {
                Team t = teamOf(player.getUuid());
                ItemStack slip = new ItemStack(DeadMansHand.SLIP);
                slip.setCustomName(Text.literal("Borrow: " + THINGS[t.thing]).formatted(Formatting.YELLOW));
                player.getInventory().offerOrDrop(slip);
                StringBuilder partners = new StringBuilder();
                for (UUID m : t.members) {
                    if (!m.equals(player.getUuid())) {
                        partners.append(partners.length() > 0 ? " and " : "").append(nameOf(m));
                    }
                }
                tell(player, "Your slip says: " + THINGS[t.thing].toUpperCase() + ". You're with " + partners + ".", Formatting.YELLOW);
            } else if (entity instanceof Contestant contestant) {
                contestant.hold();
            }
            i++;
        }
    }

    private void put(BlockPos pos, ItemStack stack) {
        if (world.getBlockEntity(pos) instanceof BarrelBlockEntity barrel) {
            for (int k = 0; k < barrel.size(); k++) {
                barrel.setStack(k, ItemStack.EMPTY);
            }
            barrel.setStack(random.nextInt(barrel.size()), stack);
        }
    }

    private Team teamOf(UUID id) {
        for (Team t : teams) {
            if (t.members.contains(id)) {
                return t;
            }
        }
        return null;
    }

    @Override
    protected void begin() {
        for (Contestant contestant : strangers()) {
            contestant.stopAll();
        }
    }

    @Override
    protected void running() {
        ticks++;
        if (ticks % 20 == 0) {
            sound(SoundEvents.BLOCK_NOTE_BLOCK_PLING, 2.0F);
        }
        if (ticks % 10 == 0) {
            boolean on = ticks % 20 == 0;
            for (BlockPos lamp : lamps) {
                world.setBlockState(lamp, Blocks.REDSTONE_LAMP.getDefaultState().with(RedstoneLampBlock.LIT, on), net.minecraft.block.Block.NOTIFY_LISTENERS);
            }
        }
        if (timeLeft == 20 * 60 || timeLeft == 20 * 30) {
            announce(timeLeft == 20 * 60 ? "One minute." : "Thirty seconds.");
        }
        for (Team team : teams) {
            if (!team.done) {
                think(team);
            }
        }
    }

    /** The strangers in a team: search the barrels, take the thing, bring it in. */
    private void think(Team team) {
        for (UUID id : team.members) {
            Contestant contestant = contestant(id);
            if (contestant == null || !isPlaying(id)) {
                continue;
            }
            if (id.equals(team.carrier)) {
                contestant.go(desk.south(2), 1.2);
                if (contestant.squaredDistanceTo(Vec3d.ofCenter(desk)) < 9.0 && together(team)) {
                    handIn(team);
                    return;
                }
                continue;
            }
            if (team.carrier != null) {
                LivingEntity carrier = entity(team.carrier);
                if (carrier != null && contestant.arrived(2.0)) {
                    contestant.go(carrier instanceof ServerPlayerEntity ? carrier.getBlockPos() : desk.south(2), 1.2);
                }
                continue;
            }
            // look in the barrels, one after another
            if (contestant.arrived(1.8)) {
                BlockPos near = nearestBarrel(contestant.getPos());
                if (near != null && contestant.squaredDistanceTo(Vec3d.ofCenter(near)) < 4.5 && take(near, team.thing)) {
                    team.carrier = id;
                    contestant.speak("You found " + THINGS[team.thing] + ", the thing on your slip. Now to the control room with your partner.",
                            Lines.pick(random, "Got it! " + capital(THINGS[team.thing]) + "! Control room, now!", "Here! I've got it!",
                                    "Found it. Come on, to the desk!"));
                    continue;
                }
                BlockPos next = barrels.get(random.nextInt(barrels.size()));
                contestant.go(next.offset(next.getX() < origin.getX() ? Direction.EAST : Direction.WEST), 1.15);
            }
        }
    }

    private static String capital(String text) {
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private BlockPos nearestBarrel(Vec3d pos) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos barrel : barrels) {
            double d = pos.squaredDistanceTo(Vec3d.ofCenter(barrel));
            if (d < bestDistance) {
                best = barrel;
                bestDistance = d;
            }
        }
        return best;
    }

    /** Takes the team's thing out of this barrel, if it's in there. */
    private boolean take(BlockPos pos, int thing) {
        if (world.getBlockEntity(pos) instanceof BarrelBlockEntity barrel) {
            for (int k = 0; k < barrel.size(); k++) {
                if (thingOf(barrel.getStack(k)) == thing) {
                    barrel.setStack(k, ItemStack.EMPTY);
                    return true;
                }
            }
        }
        return false;
    }

    /** Everyone in the team who's still alive is at the desk. */
    private boolean together(Team team) {
        for (UUID id : team.members) {
            LivingEntity member = entity(id);
            if (member != null && isPlaying(id) && member.squaredDistanceTo(Vec3d.ofCenter(desk)) > 7.0 * 7.0) {
                return false;
            }
        }
        return true;
    }

    private void handIn(Team team) {
        team.done = true;
        world.playSound(null, desk.getX(), desk.getY(), desk.getZ(), SoundEvents.BLOCK_BELL_USE, SoundCategory.BLOCKS, 1.5F, 1.4F);
        StringBuilder who = new StringBuilder();
        for (UUID id : team.members) {
            if (isPlaying(id)) {
                who.append(who.length() > 0 ? " and " : "").append(nameOf(id));
                clear(id);
            }
        }
        announce("Received: " + THINGS[team.thing] + ", from " + who + ".");
        boolean all = true;
        for (Team t : teams) {
            all &= t.done || t.members.stream().noneMatch(this::isPlaying);
        }
        if (all) {
            finish();
        }
    }

    @Override
    protected ActionResult use(ServerPlayerEntity player, BlockPos pos, net.minecraft.block.BlockState state) {
        if (!pos.equals(desk) || phase != Phase.RUNNING || !isPlaying(player.getUuid())) {
            return ActionResult.PASS;
        }
        Team team = teamOf(player.getUuid());
        if (team == null || team.done) {
            return ActionResult.SUCCESS;
        }
        ItemStack held = player.getMainHandStack();
        int thing = thingOf(held);
        if (thing < 0) {
            tell(player, "Hold the thing on your slip and use the desk to hand it in.", Formatting.GRAY);
            return ActionResult.SUCCESS;
        }
        if (thing != team.thing) {
            tell(player, "That's not what your slip says. Yours says " + THINGS[team.thing] + ".", Formatting.RED);
            return ActionResult.SUCCESS;
        }
        if (!together(team)) {
            tell(player, "Your partner has to be here with you.", Formatting.RED);
            return ActionResult.SUCCESS;
        }
        held.decrement(1);
        handIn(team);
        return ActionResult.SUCCESS;
    }

    @Override
    protected void died(UUID id) {
        Team team = teamOf(id);
        if (team != null && id.equals(team.carrier)) {
            team.carrier = null;    // what they carried is gone with them
        }
    }

    @Override
    protected void more(ServerPlayerEntity player, Hud hud) {
        Team team = teamOf(player.getUuid());
        if (team == null) {
            return;
        }
        hud.line("§fBORROW §e" + THINGS[team.thing].toUpperCase());
        StringBuilder partners = new StringBuilder();
        for (UUID m : team.members) {
            if (!m.equals(player.getUuid())) {
                partners.append(partners.length() > 0 ? ", " : "").append(isPlaying(m) ? "§f" : "§8").append(nameOf(m));
            }
        }
        hud.line("§fWITH " + partners);
        if (team.carrier != null) {
            hud.line("§a" + nameOf(team.carrier) + " has it");
        }
    }

    // --- what a stranger can say they'll do, and then does ---------------------------------------------------

    @Override
    protected boolean followable() {
        return true;
    }

    @Override
    public String situation(Contestant who) {
        Team team = teamOf(who.getUuid());
        String thing = team == null ? "something" : THINGS[team.thing];
        return "You're playing the BORROWING RACE, the two of clubs, in a power plant with the alarm going. You and your partner drew a "
                + "slip: you have to find " + thing + " somewhere in the plant and hand it in at the control room desk together before the "
                + "alarm stops. " + (team != null && team.carrier != null ? nameOf(team.carrier) + " has found it." : "You haven't found it yet.");
    }

    @Override
    protected void ended() {
        for (UUID id : players) {
            ServerPlayerEntity player = player(id);
            if (player != null) {
                for (int i = 0; i < player.getInventory().size(); i++) {
                    ItemStack stack = player.getInventory().getStack(i);
                    if (stack.isOf(DeadMansHand.SLIP) || thingOf(stack) >= 0) {
                        player.getInventory().setStack(i, ItemStack.EMPTY);
                    }
                }
            }
        }
        for (BlockPos lamp : lamps) {
            world.setBlockState(lamp, Blocks.REDSTONE_LAMP.getDefaultState(), net.minecraft.block.Block.NOTIFY_LISTENERS);
        }
    }
}
