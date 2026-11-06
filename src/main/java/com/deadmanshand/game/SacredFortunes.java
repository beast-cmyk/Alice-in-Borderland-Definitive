package com.deadmanshand.game;

import com.deadmanshand.Builder;
import com.deadmanshand.Hud;
import com.deadmanshand.Laser;
import com.deadmanshand.npc.Contestant;
import com.deadmanshand.npc.Lines;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.particle.ParticleTypes;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sacred Fortunes, at a shrine. Ten fortune slips are drawn from the box, one each, a minute for every draw. Some are
 * plain fortunes. Most ask a question with a number for an answer, and a wrong answer brings down fire arrows: one for
 * every unit you were out by. Get under cover when they fall. Run, and it's the laser. The last slip asks something no one
 * can answer exactly, and what falls then, nothing on the ground survives. Read the fortunes: they say where to go.
 */
final class SacredFortunes extends Game {
    private static final int SLIPS = 10;
    private static final int DRAW = 20 * 20;
    private static final int ANSWER = 60 * 20;

    private record Slip(String text, long answer) {
        boolean question() {
            return answer >= 0;
        }
    }

    private static final Slip[] QUESTIONS = {
            new Slip("How many hours are there in a week?", 168),
            new Slip("How many legs do three spiders and two cats have between them?", 32),
            new Slip("Add up every number from 1 to 20.", 210),
            new Slip("What is the square root of 1,764?", 42),
            new Slip("How many seconds are there in an hour?", 3600),
            new Slip("The corners of a pentagon: how many degrees, added together?", 540),
            new Slip("How many keys does a piano have?", 88),
            new Slip("How many minutes are there in a day?", 1440),
            new Slip("Seven times eight, minus six?", 50),
            new Slip("How many squares are on a chessboard, counting every size?", 204),
            new Slip("How many cards in a deck, without the jokers, times three?", 156),
            new Slip("Twelve squared, take away the days in a leap year, and add three hundred?", 78),
    };
    private static final String[] FORTUNES = {
            "GREAT BLESSING. What you are looking for lies low, beneath what is offered.",
            "BLESSING. The coins fall into the box, and keep falling. Follow them down.",
            "SMALL BLESSING. When the sky burns, the earth is kind to those inside it.",
    };
    /** No one alive could answer it exactly. */
    private static final Slip LAST = new Slip("How many people were alive in the world on the night the fireworks came?", 8_045_311_447L);

    private final List<Slip> slips = new ArrayList<>();
    private final List<UUID> order = new ArrayList<>();
    private final List<String> read = new ArrayList<>();
    /** Strangers who've worked out where the way down is. */
    private final java.util.Set<UUID> tunnel = new java.util.HashSet<>();
    private int drawn;
    private int timer;
    private UUID drawer;
    private Slip open;
    private long given = Long.MIN_VALUE;
    /** Arrows still to fall, how many per tick, and whether it's the last fall. */
    private int arrows;
    private int fall;
    private boolean last;
    private int lastLeft;
    private BlockPos box;
    private BlockPos offering;
    private int oy;
    private int ticks;
    private int cx0;
    private int cx1;
    private int cz0;
    private int cz1;

    SacredFortunes(Kind kind, ServerWorld world, BlockPos origin) {
        super(kind, world, origin);
    }

    @Override
    protected void plan(Builder b) {
        int ox = origin.getX();
        oy = origin.getY();
        int oz = origin.getZ();
        cx0 = ox - 21;
        cx1 = ox + 21;
        cz0 = oz - 44;
        cz1 = oz - 3;
        bounds = new Box(ox - 24, oy - 9, oz - 47, ox + 26, oy + 16, oz + 3);
        entry = new Box(ox - 3, oy, oz - 4, ox + 4, oy + 3, oz + 1);
        entryPos = new BlockPos(ox, oy, oz - 2);
        approach = new BlockPos(ox, oy, oz + 6);
        box = new BlockPos(ox, oy, oz - 18);
        offering = new BlockPos(ox, oy, oz - 32);
        BlockState air = Blocks.AIR.getDefaultState();
        BlockState red = Blocks.RED_CONCRETE.getDefaultState();
        BlockState roof = Blocks.DEEPSLATE_TILES.getDefaultState();
        BlockState wood = Blocks.DARK_OAK_PLANKS.getDefaultState();
        b.fill(ox - 24, oy, oz - 47, ox + 25, oy + 14, oz + 2, air);
        b.fill(ox - 24, oy - 1, oz - 47, ox + 25, oy - 1, oz + 2, Blocks.GRAVEL.getDefaultState());
        b.fill(ox - 1, oy - 1, oz - 31, ox + 1, oy - 1, oz + 2, Blocks.SMOOTH_STONE.getDefaultState());
        // The wall round the grounds
        b.walls(ox - 22, oy, oz - 45, ox + 22, oy + 3, oz - 2, Blocks.WHITE_CONCRETE.getDefaultState());
        b.walls(ox - 22, oy + 4, oz - 45, ox + 22, oy + 4, oz - 2, roof);
        b.fill(ox - 2, oy, oz - 2, ox + 2, oy + 3, oz - 2, air);
        b.fill(ox - 2, oy + 4, oz - 2, ox + 2, oy + 4, oz - 2, air);
        // The torii at the gate
        b.fill(ox - 4, oy, oz - 1, ox - 4, oy + 6, oz - 1, red);
        b.fill(ox + 4, oy, oz - 1, ox + 4, oy + 6, oz - 1, red);
        b.fill(ox - 6, oy + 7, oz - 1, ox + 6, oy + 7, oz - 1, Blocks.BLACK_CONCRETE.getDefaultState());
        b.fill(ox - 5, oy + 5, oz - 1, ox + 5, oy + 5, oz - 1, red);
        // Covered walkways down both sides: the only cover from the arrows
        for (int side : new int[]{-1, 1}) {
            int xa = side < 0 ? ox - 21 : ox + 18;
            int xb = side < 0 ? ox - 18 : ox + 21;
            b.fill(xa, oy + 4, oz - 42, xb, oy + 4, oz - 5, roof);
            int px = side < 0 ? ox - 18 : ox + 18;
            for (int z = oz - 42; z <= oz - 5; z += 4) {
                b.fill(px, oy, z, px, oy + 3, z, red);
            }
            b.fill(xa, oy - 1, oz - 42, xb, oy - 1, oz - 5, Blocks.SMOOTH_STONE.getDefaultState());
        }
        // The shrine hall
        b.fill(ox - 8, oy - 1, oz - 44, ox + 8, oy - 1, oz - 34, Blocks.STONE_BRICKS.getDefaultState());
        b.fill(ox - 7, oy, oz - 43, ox + 7, oy + 4, oz - 35, wood);
        b.fill(ox - 6, oy, oz - 42, ox + 6, oy + 3, oz - 36, air);
        b.fill(ox - 2, oy, oz - 35, ox + 2, oy + 3, oz - 35, air);
        b.fill(ox - 9, oy + 5, oz - 45, ox + 9, oy + 5, oz - 33, roof);
        b.fill(ox - 7, oy + 6, oz - 43, ox + 7, oy + 6, oz - 35, roof);
        b.fill(ox - 4, oy + 7, oz - 41, ox + 4, oy + 7, oz - 37, roof);
        b.set(ox, oy + 4, oz - 34, Blocks.LANTERN.getDefaultState().with(net.minecraft.block.LanternBlock.HANGING, true));
        b.set(ox, oy + 3, oz - 40, Blocks.GOLD_BLOCK.getDefaultState());
        // The bell rope and the offering box in front of the hall
        b.fill(ox, oy + 1, oz - 34, ox, oy + 3, oz - 34, Blocks.CHAIN.getDefaultState());
        b.set(offering, Blocks.SPRUCE_PLANKS.getDefaultState());
        // Under the offering box: a shaft and a tunnel, sealed until the end
        b.fill(ox - 1, oy - 8, oz - 33, ox + 1, oy - 2, oz - 8, Blocks.STONE_BRICKS.getDefaultState());
        b.fill(ox, oy - 7, oz - 32, ox, oy - 2, oz - 32, air);
        b.set(ox, oy - 1, oz - 32, Blocks.SMOOTH_STONE.getDefaultState());
        b.set(ox, oy - 7, oz - 32, Blocks.HAY_BLOCK.getDefaultState());
        b.fill(ox, oy - 6, oz - 31, ox, oy - 5, oz - 9, air);
        for (int z = oz - 30; z <= oz - 10; z += 5) {
            b.set(ox, oy - 4, z, Blocks.LANTERN.getDefaultState().with(net.minecraft.block.LanternBlock.HANGING, true));
        }
        // The fortune box in the middle of the grounds, and stone lanterns
        b.set(box.down(), Blocks.STONE_BRICKS.getDefaultState());
        b.set(box, Blocks.BARREL.getDefaultState());
        for (int[] p : new int[][]{{ox - 6, oz - 26}, {ox + 6, oz - 26}, {ox - 6, oz - 10}, {ox + 6, oz - 10}}) {
            b.set(p[0], oy, p[1], Blocks.STONE_BRICK_WALL.getDefaultState());
            b.set(p[0], oy + 1, p[1], Blocks.LANTERN.getDefaultState());
        }
        b.sign(new BlockPos(ox + 3, oy + 1, oz - 18), Direction.EAST, "FORTUNES", "Use the box", "when it's", "your turn.");
        b.set(ox + 2, oy + 1, oz - 18, Blocks.STONE_BRICKS.getDefaultState());
        b.set(ox + 2, oy, oz - 18, Blocks.STONE_BRICKS.getDefaultState());
        b.sign(new BlockPos(ox + 6, oy + 2, oz + 2), Direction.SOUTH, "GAME VENUE", "", "step inside", "to register");
        b.fill(ox + 6, oy, oz + 1, ox + 6, oy + 2, oz + 1, Blocks.STONE_BRICKS.getDefaultState());
    }

    @Override
    protected String[] rules() {
        return new String[]{
                "Sacred Fortunes. Ten fortune slips will be drawn, one by each of you in turn. You may draw only once.",
                "You have one minute for each draw. Read your fortune well.",
                "A wrong answer brings down fire arrows: as many as you were wrong by.",
                "Leave the grounds and it's the laser."};
    }

    @Override
    protected int timeLimit() {
        return 30 * 60 * 20;
    }

    @Override
    protected void arrange() {
        List<Slip> questions = new ArrayList<>(List.of(QUESTIONS));
        java.util.Random shuffle = new java.util.Random(random.nextLong());
        Collections.shuffle(questions, shuffle);
        List<Slip> deck = new ArrayList<>(questions.subList(0, SLIPS - 1 - FORTUNES.length));
        for (String f : FORTUNES) {
            deck.add(new Slip(f, -1));
        }
        Collections.shuffle(deck, shuffle);
        deck.add(LAST);
        slips.clear();
        slips.addAll(deck);
        order.addAll(playing());
        Collections.shuffle(order, shuffle);
        int i = 0;
        for (LivingEntity e : living()) {
            e.teleport(origin.getX() - 4.5 + (i % 10), oy, origin.getZ() - 12.5 - (i / 10));
            if (e instanceof Contestant c) {
                c.hold();
            }
            i++;
        }
    }

    @Override
    protected void begin() {
        for (Contestant c : strangers()) {
            c.stopAll();
        }
        nextDrawer();
    }

    // --- the draws ---------------------------------------------------------------------------------------------

    private void nextDrawer() {
        open = null;
        given = Long.MIN_VALUE;
        if (drawn >= SLIPS) {
            return;
        }
        drawer = null;
        for (int k = 0; k < order.size(); k++) {
            UUID id = order.get((drawn + k) % order.size());
            if (isPlaying(id)) {
                drawer = id;
                break;
            }
        }
        if (drawer == null) {
            return;
        }
        timer = DRAW;
        announce("Slip " + (drawn + 1) + " of " + SLIPS + ": " + nameOf(drawer) + " draws.");
        tell(entity(drawer), "Your turn: go to the fortune box and use it.", Formatting.GOLD);
    }

    @Override
    protected ActionResult use(ServerPlayerEntity player, BlockPos pos, BlockState state) {
        if (pos.equals(box)) {
            if (player.getUuid().equals(drawer) && open == null && arrows == 0) {
                draw(player.getUuid());
            } else {
                tell(player, "It isn't your turn to draw.", Formatting.GRAY);
            }
            return ActionResult.SUCCESS;
        }
        return ActionResult.PASS;
    }

    private void draw(UUID id) {
        open = slips.get(drawn);
        drawn++;
        world.playSound(null, box, SoundEvents.ITEM_BOOK_PAGE_TURN, SoundCategory.BLOCKS, 1.0F, 1.0F);
        announce(nameOf(id) + "'s slip: \"" + open.text + "\"");
        if (!open.question()) {
            read.add(open.text);
            announce("A fortune. Nothing falls.");
            open = null;
            nextDrawer();
            return;
        }
        timer = ANSWER;
        ServerPlayerEntity player = player(id);
        if (player != null) {
            tell(player, "Answer with /answer and a number. One minute.", Formatting.YELLOW);
        }
        if (open == LAST) {
            // the ground opens under the offering box
            world.setBlockState(offering, Blocks.AIR.getDefaultState());
            world.setBlockState(offering.down(), Blocks.AIR.getDefaultState());
            world.playSound(null, offering, SoundEvents.BLOCK_WOODEN_TRAPDOOR_OPEN, SoundCategory.BLOCKS, 1.5F, 0.6F);
        }
    }

    private void answer(UUID id, long value) {
        if (open == null || !id.equals(drawer) || !open.question()) {
            return;
        }
        long off = Math.abs(value - open.answer);
        announce(nameOf(id) + " answers " + String.format("%,d", value) + ". " + (off == 0 ? "Correct." : "The answer was "
                + String.format("%,d", open.answer) + "."));
        Slip was = open;
        open = null;
        if (was == LAST) {
            last = true;
            lastLeft = 20 * 20;
            announce("Out by " + String.format("%,d", off) + ". That many arrows.");
            world.playSound(null, origin.getX(), oy + 10, origin.getZ() - 20, SoundEvents.ENTITY_WITHER_SPAWN, SoundCategory.HOSTILE, 3.0F, 0.6F);
            return;
        }
        if (off == 0) {
            nextDrawer();
            return;
        }
        arrows = (int) Math.min(off, 160);
        fall = Math.max(1, arrows / 60);
        announce(off + (off == 1 ? " arrow." : " arrows. Take cover!"));
        world.playSound(null, origin.getX(), oy + 10, origin.getZ() - 20, SoundEvents.ENTITY_ARROW_SHOOT, SoundCategory.HOSTILE, 3.0F, 0.5F);
    }

    @Override
    protected void answered(ServerPlayerEntity player, String text) {
        try {
            answer(player.getUuid(), Long.parseLong(text.trim().replace(",", "").replace("_", "")));
        } catch (NumberFormatException e) {
            tell(player, "A number, please.", Formatting.GRAY);
        }
    }

    // --- the arrows ---------------------------------------------------------------------------------------------

    private void loose(int n) {
        for (int k = 0; k < n; k++) {
            double x = cx0 + random.nextDouble() * (cx1 - cx0);
            double z = cz0 + random.nextDouble() * (cz1 - cz0);
            ArrowEntity arrow = new ArrowEntity(world, x, oy + 24, z);
            arrow.setVelocity((random.nextDouble() - 0.5) * 0.2, -2.6, (random.nextDouble() - 0.5) * 0.2);
            arrow.setOnFireFor(100);
            arrow.pickupType = PersistentProjectileEntity.PickupPermission.DISALLOWED;
            world.spawnEntity(arrow);
        }
    }

    private boolean underground(LivingEntity e) {
        return e.getY() < oy - 3;
    }

    @Override
    protected void running() {
        ticks++;
        if (arrows > 0) {
            int n = Math.min(arrows, fall);
            arrows -= n;
            loose(n);
            if (arrows == 0) {
                nextDrawer();
            }
        } else if (last) {
            loose(6);
            if (ticks % 10 == 0) {
                world.spawnParticles(ParticleTypes.FLAME, origin.getX(), oy + 6, origin.getZ() - 22, 120, 18, 4, 18, 0.02);
                world.playSound(null, origin.getX(), oy + 6, origin.getZ() - 22, SoundEvents.BLOCK_FIRE_AMBIENT, SoundCategory.BLOCKS, 3.0F, 0.7F);
            }
            for (LivingEntity e : living()) {
                if (!underground(e) && ticks % 10 == 0) {
                    // there's no cover from this: the roofs are burning
                    e.setOnFireFor(4);
                    e.damage(Laser.ARROWS, 4.0F);
                    if (!e.isAlive()) {
                        seats.put(e.getUuid(), Fate.DEAD);
                        died(e.getUuid());
                    }
                }
            }
            if (--lastLeft <= 0) {
                last = false;
                announce("The arrows stop.");
                for (UUID id : playing()) {
                    LivingEntity e = entity(id);
                    if (e != null && underground(e)) {
                        clear(id);
                    } else {
                        kill(id, Laser.ARROWS, "Nothing on the ground lived through that.");
                    }
                }
                finish();
            }
            return;
        } else if (drawer != null && isPlaying(drawer)) {
            if (--timer <= 0) {
                if (open == null) {
                    tell(entity(drawer), "Time. The slip is drawn for you.", Formatting.GRAY);
                    draw(drawer);
                } else if (open.question()) {
                    announce(nameOf(drawer) + " gives no answer.");
                    answer(drawer, 0);
                }
            }
        } else if (drawn < SLIPS && arrows == 0) {
            nextDrawer();
        }
        for (Contestant c : strangers()) {
            think(c);
        }
    }

    private BlockPos cover(Contestant c) {
        int side = c.getX() < origin.getX() ? -1 : 1;
        int z = (int) Math.max(origin.getZ() - 40, Math.min(origin.getZ() - 6, c.getZ()));
        return new BlockPos(origin.getX() + side * 20, oy, z);
    }

    private void think(Contestant c) {
        UUID id = c.getUuid();
        if (arrows > 0) {
            c.go(cover(c), 1.35);
            return;
        }
        if (open == LAST || last) {
            // the ones who read the fortunes know where to go
            boolean knows = underground(c) || tunnel.contains(id) || !read.isEmpty() && (c.nature == Contestant.Nature.CLEVER
                    || read.size() >= 2 && c.judgment() > 0.55F || read.size() >= 3 && Math.floorMod(id.hashCode(), 3) == 0);
            if (underground(c)) {
                c.go(new BlockPos(origin.getX(), oy - 6, origin.getZ() - 12), 1.2);
            } else if (knows) {
                c.go(Vec3d.ofBottomCenter(offering), 1.35);     // into the hole
                if (tunnel.add(id)) {
                    c.say(Lines.pick(random, "Beneath what is offered... the offering box! Under it!", "The box! Get down under the box!",
                            "This way, under the shrine!"));
                }
            } else {
                c.go(cover(c), 1.35);
            }
        } else if (id.equals(drawer)) {
            if (open == null) {
                if (c.squaredDistanceTo(Vec3d.ofCenter(box)) < 2.6 * 2.6) {
                    draw(id);
                } else {
                    c.go(box.south(), 1.0);
                }
            } else if (open.question() && timer < ANSWER - 60 - Math.floorMod(id.hashCode(), 300)) {
                answer(id, guess(c, open));
            }
        } else if (c.arrived(1.0) && ticks % 100 == Math.floorMod(id.hashCode(), 100)) {
            c.go(new BlockPos(origin.getX() - 6 + random.nextInt(13), oy, origin.getZ() - 22 + random.nextInt(14)), 0.7);
        }
        if (id.equals(drawer) && open == LAST && timer < ANSWER - 200) {
            answer(id, guess(c, open));
        }
    }

    private long guess(Contestant c, Slip slip) {
        if (slip == LAST) {
            return 7_900_000_000L + random.nextInt(200) * 1_000_000L;
        }
        if (random.nextFloat() < 0.3F + c.judgment() * 0.6F) {
            return slip.answer;
        }
        long spread = Math.max(2, slip.answer / 5);
        return Math.max(0, slip.answer + (long) ((random.nextDouble() - 0.5) * 2 * spread));
    }

    // --- what a stranger can say they'll do, and then does ---------------------------------------------------

    @Override
    public Map<String, String> options(Contestant who) {
        Map<String, String> options = new LinkedHashMap<>();
        if (who.getUuid().equals(drawer) && open != null && open.question()) {
            options.put("ANSWER_N", "give N as your answer to the slip: for example [ANSWER 42]. Only when you're sure");
        }
        if (open == LAST || last) {
            options.put("TUNNEL", "run to the offering box in front of the shrine and drop down the hole under it");
            options.put("COVER", "run for the covered walkway");
        }
        return options;
    }

    @Override
    protected Map<String, String> optionWords(Contestant who) {
        Map<String, String> words = new LinkedHashMap<>();
        if (who.getUuid().equals(drawer) && open != null && open.question()) {
            words.put("ANSWER_N", "\\b(answer is|answer|it'?s|i say|i'?ll say|going with)\\s+(\\d[\\d,]*)\\b");
        }
        return words;
    }

    @Override
    public void chose(Contestant who, String option) {
        if (option.startsWith("ANSWER_")) {
            try {
                answer(who.getUuid(), Long.parseLong(option.substring(7)));
            } catch (NumberFormatException ignored) {
            }
        } else if (option.equals("TUNNEL")) {
            tunnel.add(who.getUuid());
        }
    }

    // --- show ---------------------------------------------------------------------------------------------

    @Override
    protected void more(ServerPlayerEntity player, Hud hud) {
        hud.line("§fSLIPS DRAWN §e" + drawn + "/" + SLIPS);
        if (arrows > 0 || last) {
            hud.line("§c§lARROWS: TAKE COVER");
        } else if (drawer != null) {
            hud.line(drawer.equals(player.getUuid()) ? "§aYOUR SLIP " + Hud.clock(timer) : "§7" + nameOf(drawer) + "'s slip " + Hud.clock(timer));
        }
        if (open != null) {
            hud.line("§e" + (open.text.length() > 46 ? open.text.substring(0, 46) + "..." : open.text));
        }
    }

    // --- what a stranger can say they'll do, and then does ---------------------------------------------------

    @Override
    protected boolean followable() {
        return true;
    }

    @Override
    public String situation(Contestant who) {
        StringBuilder s = new StringBuilder("You're playing SACRED FORTUNES at a shrine: ten slips drawn in turn; most are questions with a number for an answer, "
                + "and a wrong answer brings fire arrows down, one for each unit you were out by; the covered walkways are the only cover. ");
        if (!read.isEmpty()) {
            s.append("The fortunes so far said: ").append(String.join(" ", read)).append(' ');
        }
        if (open != null) {
            s.append(nameOf(drawer)).append("'s slip asks: ").append(open.text).append(' ');
        }
        if (last) {
            s.append("The sky is raining fire and the roofs are burning.");
        }
        return s.toString();
    }
}
