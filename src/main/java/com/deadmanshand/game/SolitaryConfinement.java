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
import net.minecraft.particle.ParticleTypes;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.Team;
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
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Jack of Hearts, in a prison. Everyone wears a collar with a suit on the back: everyone else can see yours, you can't.
 * You have time to talk; then everyone goes into a solitary cell and says what their own suit is. Wrong, or silent,
 * and the collar goes off. Then the suits change and it starts again. One of you is the Jack of Hearts, lying to
 * everyone. When the Jack dies, it's over and everyone left clears it. If only you and the Jack are left, only the Jack
 * lives. No violence.
 */
final class SolitaryConfinement extends Game {
    private static final int FREE = 20 * 150;
    private static final int CELLS = 20 * 45;
    private static final int ROUNDS = 12;
    private static final Card.Suit[] SUITS = Card.Suit.values();

    private final List<Box> cells = new ArrayList<>();
    private final List<BlockPos> terminals = new ArrayList<>();
    private final Map<UUID, Card.Suit> mark = new HashMap<>();
    /** What each stranger believes their own mark is this round, and who told them. */
    private final Map<UUID, Card.Suit> belief = new HashMap<>();
    private final Map<UUID, UUID> source = new HashMap<>();
    /** What everyone (players included) answered in their cell. */
    private final Map<UUID, Card.Suit> answer = new HashMap<>();
    /** How much the others suspect each person of being the Jack. */
    private final Map<UUID, Float> suspicion = new HashMap<>();
    private final Map<UUID, UUID> asking = new HashMap<>();
    private final Map<UUID, Integer> askedAt = new HashMap<>();
    private UUID jack;
    private int round;
    private int phaseLeft;
    private boolean inCells;
    private int ticks;

    SolitaryConfinement(Kind kind, ServerWorld world, BlockPos origin) {
        super(kind, world, origin);
    }

    @Override
    protected void plan(Builder b) {
        int ox = origin.getX();
        int oy = origin.getY();
        int oz = origin.getZ();
        bounds = new Box(ox - 21, oy - 2, oz - 46, ox + 22, oy + 10, oz + 3);
        entry = new Box(ox - 3, oy, oz - 4, ox + 4, oy + 3, oz + 1);
        entryPos = new BlockPos(ox, oy, oz - 2);
        approach = new BlockPos(ox, oy, oz + 6);
        BlockState wall = Blocks.WHITE_CONCRETE.getDefaultState();
        BlockState dark = Blocks.GRAY_CONCRETE.getDefaultState();
        BlockState air = Blocks.AIR.getDefaultState();
        b.fill(ox - 20, oy - 1, oz - 45, ox + 20, oy + 8, oz + 1, dark);
        b.fill(ox - 19, oy - 1, oz - 44, ox + 19, oy - 1, oz, Blocks.POLISHED_ANDESITE.getDefaultState());
        // The common hall, where everyone talks
        b.fill(ox - 9, oy, oz - 43, ox + 9, oy + 6, oz - 6, air);
        for (int z = oz - 40; z <= oz - 10; z += 6) {
            for (int x = ox - 6; x <= ox + 6; x += 6) {
                b.fill(x - 1, oy, z, x + 1, oy, z, Blocks.SMOOTH_STONE_SLAB.getDefaultState());
            }
            b.set(ox, oy + 6, z, Blocks.SEA_LANTERN.getDefaultState());
        }
        // The cells: ten down each side, each with a terminal in its back wall
        for (int side = -1; side <= 1; side += 2) {
            int door = ox + side * 10;
            int back = ox + side * 14;
            for (int k = 0; k < 10; k++) {
                int zc = oz - 42 + 3 * k;
                int x0 = Math.min(door + side, back - side);
                int x1 = Math.max(door + side, back - side);
                b.fill(x0, oy, zc, x1, oy + 2, zc + 1, air);
                b.fill(door, oy, zc, door, oy + 1, zc + 1, air);
                b.fill(back, oy + 1, zc, back, oy + 1, zc + 1, Blocks.BLACK_CONCRETE.getDefaultState());
                b.set(back, oy + 2, zc, Blocks.REDSTONE_LAMP.getDefaultState());
                cells.add(new Box(x0, oy, zc, x1 + 1, oy + 3, zc + 2));
                terminals.add(new BlockPos(back, oy + 1, zc));
                terminals.add(new BlockPos(back, oy + 1, zc + 1));
            }
            b.fill(door, oy + 3, oz - 42, door, oy + 6, oz - 13, wall);
        }
        // The way in from the street
        b.fill(ox - 3, oy, oz - 5, ox + 3, oy + 3, oz, air);
        b.fill(ox - 1, oy, oz + 1, ox + 1, oy + 2, oz + 1, air);
        b.fill(ox - 1, oy, oz - 6, ox + 1, oy + 2, oz - 6, air);
        b.set(ox, oy + 3, oz - 2, Blocks.SEA_LANTERN.getDefaultState());
        b.sign(new BlockPos(ox, oy + 3, oz + 2), Direction.SOUTH, "GAME VENUE", "", "step inside", "to register");
        b.set(ox, oy + 3, oz + 1, dark);
        // A watchtower on the roof, and razor wire round it
        b.walls(ox - 20, oy + 9, oz - 45, ox + 20, oy + 9, oz + 1, Blocks.IRON_BARS.getDefaultState());
        b.fill(ox - 2, oy + 9, oz - 24, ox + 2, oy + 13, oz - 20, wall);
        b.fill(ox - 1, oy + 12, oz - 23, ox + 1, oy + 12, oz - 21, Blocks.GLASS.getDefaultState());
    }

    @Override
    protected String[] rules() {
        return new String[]{
                "Each of you wears a collar with a suit mark on the back. You can't see your own. Don't try.",
                "You may talk freely. When the cells open, go into a cell alone and say the mark on your own collar.",
                "Wrong, or silent, and your collar goes off. Then every mark changes and a new round begins.",
                "One of you is the Jack of Hearts. When the Jack dies, everyone left clears the game.",
                "If only the Jack and one other are left, only the Jack lives. Violence is forbidden."};
    }

    @Override
    protected int timeLimit() {
        return ROUNDS * (FREE + CELLS) + 40;
    }

    @Override
    protected void arrange() {
        // The Jack is one of them, and doesn't look any different
        List<Contestant> strangers = strangers();
        Contestant theJack = strangers.isEmpty() ? citizen("Kei", entryPos) : strangers.get(random.nextInt(strangers.size()));
        if (theJack != null) {
            jack = theJack.getUuid();
            theJack.role = "citizen";
            theJack.wits = 0.95F;
        }
        int i = 0;
        for (LivingEntity entity : living()) {
            entity.teleport(origin.getX() - 4.5 + (i % 9), origin.getY(), origin.getZ() - 20.5 + (i / 9) * 2);
            if (entity instanceof Contestant c) {
                c.hold();
                c.setCustomNameVisible(true);
            }
            i++;
        }
    }

    @Override
    protected void begin() {
        newRound();
    }

    private void newRound() {
        round++;
        inCells = false;
        phaseLeft = FREE;
        mark.clear();
        belief.clear();
        source.clear();
        answer.clear();
        for (UUID id : playing()) {
            Card.Suit suit = SUITS[random.nextInt(SUITS.length)];
            mark.put(id, suit);
            show(id, suit);
        }
        announce("Round " + round + ". The marks have changed. You have " + FREE / 20 / 60 + " and a half minutes.");
        for (Contestant c : strangers()) {
            c.stopAll();
        }
    }

    /** Your mark goes on your name tag, where everyone but you can see it. */
    private void show(UUID id, Card.Suit suit) {
        LivingEntity entity = entity(id);
        if (entity instanceof Contestant c) {
            String name = nameOf(id);
            c.setCustomName(Text.literal(name + " ").append(Text.literal(String.valueOf(suit.symbol))
                    .formatted(suit.red ? Formatting.RED : Formatting.WHITE)));
        } else if (entity instanceof ServerPlayerEntity player && world.getServer() != null) {
            Scoreboard board = world.getServer().getScoreboard();
            String teamName = "dmh_" + suit.id;
            Team team = board.getTeam(teamName);
            if (team == null) {
                team = board.addTeam(teamName);
                team.setSuffix(Text.literal(" " + suit.symbol).formatted(suit.red ? Formatting.RED : Formatting.WHITE));
            }
            board.addPlayerToTeam(player.getEntityName(), team);
        }
    }

    private void hide(UUID id) {
        LivingEntity entity = entity(id);
        if (entity instanceof Contestant c) {
            c.setCustomName(Text.literal(nameOf(id)));
            c.setCustomNameVisible(false);
        } else if (entity instanceof ServerPlayerEntity player && world.getServer() != null) {
            world.getServer().getScoreboard().clearPlayerTeam(player.getEntityName());
        }
    }

    @Override
    protected void running() {
        ticks++;
        phaseLeft--;
        if (!inCells && phaseLeft <= 0) {
            inCells = true;
            phaseLeft = CELLS;
            announce("Into the cells. One to a cell. Say your mark at the terminal.");
            sound(SoundEvents.BLOCK_BELL_USE, 0.6F);
            for (UUID id : players) {
                ServerPlayerEntity player = player(id);
                if (player != null && isPlaying(id)) {
                    choices(player, "In your cell, your mark is:", "♠ Spades|spades", "♥ Hearts|hearts", "♦ Diamonds|diamonds", "♣ Clubs|clubs");
                }
            }
            sendStrangersToCells();
        } else if (inCells && phaseLeft == 20 * 10) {
            announce("Ten seconds.");
        } else if (inCells && phaseLeft <= 0) {
            judge();
            if (phase == Phase.RUNNING) {
                newRound();
            }
            return;
        }
        if (!inCells) {
            talk();
        }
    }

    // --- the strangers -----------------------------------------------------------------------------------------

    private boolean isJack(UUID id) {
        return id.equals(jack);
    }

    /** What {@code teller} says {@code asker}'s mark is: the truth, unless they're the Jack or think the asker is. */
    private Card.Suit report(UUID teller, UUID asker) {
        Card.Suit truth = mark.get(asker);
        boolean lie = isJack(teller) ? random.nextFloat() < 0.75F : suspicion.getOrDefault(asker, 0.0F) > 0.6F;
        if (!isJack(teller) && entity(teller) instanceof Contestant c && entity(asker) instanceof ServerPlayerEntity && c.trust(asker) < -0.3F) {
            lie = true;
        }
        return lie ? other(truth) : truth;
    }

    private Card.Suit other(Card.Suit not) {
        Card.Suit pick;
        do {
            pick = SUITS[random.nextInt(SUITS.length)];
        } while (pick == not);
        return pick;
    }

    private void talk() {
        List<Contestant> strangers = strangers();
        for (Contestant c : strangers) {
            UUID id = c.getUuid();
            if (c.arrived(1.5) && random.nextFloat() < 0.02F) {
                c.go(new BlockPos(origin.getX() - 8 + random.nextInt(17), origin.getY(), origin.getZ() - 41 + random.nextInt(34)), 0.7);
            }
            // ask someone you trust what your mark is
            if ((ticks + id.hashCode()) % 70 == 0 && !strangers.isEmpty()) {
                Contestant teller = strangers.get(random.nextInt(strangers.size()));
                if (teller != c && suspicion.getOrDefault(teller.getUuid(), 0.0F) < 0.6F) {
                    belief.put(id, report(teller.getUuid(), id));
                    source.put(id, teller.getUuid());
                }
            }
            // the Jack plants doubts
            if (isJack(id) && random.nextFloat() < 0.0015F && strangers.size() > 2) {
                Contestant target = strangers.get(random.nextInt(strangers.size()));
                if (target != c) {
                    suspicion.merge(target.getUuid(), 0.2F, Float::sum);
                    c.speak("You're secretly the Jack of Hearts. You're casting suspicion on " + target.name() + ".",
                            Lines.pick(random, "Watch " + target.name() + ". Something's off.", "I think " + target.name() + " lied to me last round.",
                                    target.name() + " is too calm. Too calm."));
                }
            }
            // ask the player
            for (UUID pid : players) {
                ServerPlayerEntity player = player(pid);
                if (player == null || !isPlaying(pid) || c.squaredDistanceTo(player) > 6.0 * 6.0) {
                    continue;
                }
                Integer last = askedAt.get(id);
                if (!belief.containsKey(id) && (last == null || ticks - last > 20 * 25) && random.nextFloat() < 0.01F) {
                    askedAt.put(id, ticks);
                    asking.put(pid, id);
                    c.look(player);
                    c.say(Lines.pick(random, "Hey. What's on my collar? Tell me straight.", "You can see mine. What is it?",
                            player.getEntityName() + ", my mark. Please. What is it?"));
                }
            }
        }
    }

    private void sendStrangersToCells() {
        List<Integer> free = new ArrayList<>();
        for (int k = 0; k < cells.size(); k++) {
            free.add(k);
        }
        java.util.Collections.shuffle(free, new java.util.Random(random.nextLong()));
        int n = 0;
        for (Contestant c : strangers()) {
            if (n < free.size()) {
                Vec3d centre = cells.get(free.get(n++)).getCenter();
                c.go(new Vec3d(centre.x, origin.getY(), centre.z), 1.2);
            }
        }
    }

    @Nullable
    private Box cellOf(LivingEntity entity) {
        for (Box cell : cells) {
            if (cell.contains(entity.getPos())) {
                return cell;
            }
        }
        return null;
    }

    /** The end of a round: everyone answers, and the wrong ones die. */
    private void judge() {
        Map<Box, Integer> occupants = new HashMap<>();
        for (LivingEntity entity : living()) {
            Box cell = cellOf(entity);
            if (cell != null) {
                occupants.merge(cell, 1, Integer::sum);
            }
        }
        List<UUID> dead = new ArrayList<>();
        for (UUID id : playing()) {
            LivingEntity entity = entity(id);
            if (entity == null) {
                continue;
            }
            if (entity instanceof Contestant) {
                answer.putIfAbsent(id, belief.getOrDefault(id, SUITS[random.nextInt(SUITS.length)]));
            }
            Box cell = cellOf(entity);
            Card.Suit said = answer.get(id);
            String why = cell == null ? "You weren't in a cell." : occupants.getOrDefault(cell, 0) > 1 ? "Your cell wasn't solitary."
                    : said == null ? "You said nothing." : said != mark.get(id) ? "Your mark was " + mark.get(id).title + "." : null;
            if (why != null) {
                dead.add(id);
                collar(id, why);
                // whoever told them is now suspected of being the Jack
                UUID liar = source.get(id);
                if (liar != null) {
                    suspicion.merge(liar, 0.5F, Float::sum);
                }
            }
        }
        if (!isPlaying(jack)) {
            announce("The Jack of Hearts is dead.");
            for (UUID id : playing()) {
                clear(id);
            }
            finish();
            return;
        }
        List<UUID> left = playing();
        if (left.size() <= 2) {
            for (UUID id : left) {
                if (!isJack(id)) {
                    collar(id, "Only the Jack was left with you, and only the Jack lives.");
                }
            }
            finish();
        }
    }

    private void collar(UUID id, String why) {
        LivingEntity entity = entity(id);
        if (entity != null) {
            world.spawnParticles(ParticleTypes.EXPLOSION, entity.getX(), entity.getEyeY() - 0.3, entity.getZ(), 1, 0.0, 0.0, 0.0, 0.0);
            world.playSound(null, entity.getX(), entity.getY(), entity.getZ(), SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.PLAYERS, 1.2F, 1.3F);
        }
        seats.put(id, Fate.DEAD);
        if (entity != null && entity.isAlive()) {
            tell(entity, why, Formatting.RED);
            entity.damage(Laser.COLLAR, 1000.0F);
        }
        hide(id);
        died(id);
    }

    // --- the player ---------------------------------------------------------------------------------------------

    @Override
    protected void heard(ServerPlayerEntity player, String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        Card.Suit said = suitIn(lower);
        UUID pid = player.getUuid();
        // answering someone who asked
        UUID asker = asking.get(pid);
        if (said != null && asker != null && entity(asker) instanceof Contestant c && c.squaredDistanceTo(player) < 10.0 * 10.0) {
            asking.remove(pid);
            if (c.trust(pid) > -0.2F && suspicion.getOrDefault(pid, 0.0F) < 0.6F) {
                belief.put(asker, said);
                source.put(asker, pid);
                c.say(Lines.pick(random, "Thank you. I'll trust you.", said.title + ". Okay. Okay.", "If you're lying to me..."));
            }
            return;
        }
        // accusing someone of being the Jack
        if (lower.contains("jack")) {
            for (Contestant c : strangers()) {
                if (lower.contains(nameOf(c.getUuid()).toLowerCase(Locale.ROOT))) {
                    for (Contestant listener : strangers()) {
                        if (listener != c && listener.squaredDistanceTo(player) < 15.0 * 15.0 && listener.trust(pid) >= 0.0F) {
                            suspicion.merge(c.getUuid(), 0.4F / Math.max(1, strangers().size() / 4), Float::sum);
                        }
                    }
                    suspicion.merge(c.getUuid(), 0.3F, Float::sum);
                    Contestant someone = nearestStranger(player, c);
                    if (someone != null) {
                        someone.say(Lines.pick(random, "You think it's " + c.name() + "? ...Then we tell them nothing true.",
                                "I'll watch " + c.name() + ".", "If you're wrong, we kill an innocent person."));
                    }
                    return;
                }
            }
        }
        // asking what your own mark is
        if (lower.matches(".*\\b(my|mine|me|i)\\b.*") && lower.matches(".*\\b(mark|suit|sign|symbol|collar|what am i)\\b.*")) {
            Contestant teller = nearestStranger(player, null);
            if (teller != null) {
                Card.Suit told = report(teller.getUuid(), pid);
                teller.look(player);
                teller.say(Lines.pick(random, "Yours is " + told.symbol + " " + told.title + ".", told.title + ". You've got " + told.title + ".",
                        "It's " + told.symbol + ", " + told.title + "."));
            }
        }
    }

    @Nullable
    private Contestant nearestStranger(ServerPlayerEntity player, @Nullable Contestant not) {
        Contestant best = null;
        double bestDistance = 9.0 * 9.0;
        for (Contestant c : strangers()) {
            double d = c.squaredDistanceTo(player);
            if (c != not && d < bestDistance) {
                best = c;
                bestDistance = d;
            }
        }
        return best;
    }

    @Nullable
    private static Card.Suit suitIn(String text) {
        if (text.matches(".*\\b(spade|spades)\\b.*") || text.contains("♠")) {
            return Card.Suit.SPADES;
        }
        if (text.matches(".*\\b(heart|hearts)\\b.*") || text.contains("♥")) {
            return Card.Suit.HEARTS;
        }
        if (text.matches(".*\\b(diamond|diamonds)\\b.*") || text.contains("♦")) {
            return Card.Suit.DIAMONDS;
        }
        if (text.matches(".*\\b(club|clubs)\\b.*") || text.contains("♣")) {
            return Card.Suit.CLUBS;
        }
        return null;
    }

    @Override
    protected void answered(ServerPlayerEntity player, String text) {
        Card.Suit said = suitIn(text.toLowerCase(Locale.ROOT));
        if (!inCells) {
            tell(player, "You answer in your cell, when the cells open.", Formatting.GRAY);
            return;
        }
        if (cellOf(player) == null) {
            tell(player, "Get into a cell first.", Formatting.RED);
            return;
        }
        if (said == null) {
            tell(player, "Say spades, hearts, diamonds or clubs.", Formatting.GRAY);
            return;
        }
        answer.put(player.getUuid(), said);
        tell(player, "You've said " + said.symbol + " " + said.title + ". You can change it until the time is up.", Formatting.YELLOW);
    }

    @Override
    protected ActionResult use(ServerPlayerEntity player, BlockPos pos, BlockState state) {
        if (terminals.contains(pos) && inCells) {
            choices(player, "Your mark is:", "♠ Spades|spades", "♥ Hearts|hearts", "♦ Diamonds|diamonds", "♣ Clubs|clubs");
            return ActionResult.SUCCESS;
        }
        return ActionResult.PASS;
    }

    @Override
    protected boolean attacked(ServerPlayerEntity player, Entity target) {
        collar(player.getUuid(), "Violence is forbidden.");
        return true;
    }

    @Override
    protected void more(ServerPlayerEntity player, Hud hud) {
        hud.line("§fROUND §e" + round + "  " + (inCells ? "§cCELLS " : "§7talk ") + Hud.clock(phaseLeft));
        hud.line(inCells ? "§cIn a cell, alone: /answer your mark" : "§7Everyone's marks are on their name tags");
        Card.Suit said = answer.get(player.getUuid());
        if (inCells) {
            hud.line("§fYOUR ANSWER " + (said == null ? "§cnone" : "§e" + said.symbol + " " + said.title));
        }
    }

    // --- what a stranger can say they'll do, and then does ---------------------------------------------------

    @Override
    public Map<String, String> options(Contestant who) {
        if (!inCells || !isPlaying(who.getUuid()) || cellOf(who) == null) {
            return Map.of();
        }
        Map<String, String> options = new java.util.LinkedHashMap<>();
        for (Card.Suit suit : SUITS) {
            options.put("ANSWER_" + suit.name(), "answer, alone in your cell, that your mark is " + suit.title.toLowerCase(Locale.ROOT));
        }
        return options;
    }

    @Override
    protected Map<String, String> optionWords(Contestant who) {
        if (!inCells) {
            return Map.of();
        }
        Map<String, String> words = new java.util.LinkedHashMap<>();
        words.put("ANSWER_SPADES", "\\bspades?\\b");
        words.put("ANSWER_HEARTS", "\\bhearts?\\b");
        words.put("ANSWER_DIAMONDS", "\\bdiamonds?\\b");
        words.put("ANSWER_CLUBS", "\\bclubs?\\b");
        return words;
    }

    @Override
    public void chose(Contestant who, String option) {
        if (!inCells || cellOf(who) == null || !option.startsWith("ANSWER_")) {
            return;
        }
        try {
            answer.put(who.getUuid(), Card.Suit.valueOf(option.substring(7)));
        } catch (IllegalArgumentException ignored) {
        }
    }

    @Override
    public String situation(Contestant who) {
        UUID id = who.getUuid();
        Card.Suit believed = belief.get(id);
        return "You're playing SOLITARY CONFINEMENT, the jack of hearts, in a prison. Everyone's collar shows a suit mark on the back; you "
                + "can't see your own, only other people's (" + (mark.isEmpty() ? "" : "the person you're talking to has "
                + "a mark you can see") + "). Each round you must go alone into a cell and say your own mark; wrong means your collar explodes. "
                + "One player is secretly the Jack of Hearts and lies. " + (isJack(id) ? "YOU are the Jack of Hearts: you lie to others about "
                + "their marks, but never admit it." : "") + (believed != null ? " You've been told your mark is " + believed.title + "." : "")
                + " Round " + round + (inCells ? ", everyone is going into the cells now." : ".");
    }

    @Override
    protected void ended() {
        for (UUID id : seats.keySet()) {
            hide(id);
        }
    }
}
