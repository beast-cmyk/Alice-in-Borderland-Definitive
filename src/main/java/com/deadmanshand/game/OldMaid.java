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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Old Maid, at a table on an office floor, with every chair wired up. Seven cards each, the Joker among them; pairs go
 * on the table. In turn, take one card from the player on your right. Here the Joker is what you want: whoever holds it
 * at the end clears the game. Have it taken from you and your chair goes off. Run out of cards, or leave your chair, and
 * it goes off too.
 */
final class OldMaid extends Game {
    private static final int JOKER = 0;
    private static final int TURN = 30 * 20;
    private static final String[] RANK = {"Joker", "A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K"};
    private static final String[] SUITS = {"♠", "♥", "♦", "♣"};

    private final List<UUID> ring = new ArrayList<>();
    private final Map<UUID, Vec3d> chair = new HashMap<>();
    /** Each hand: card ids. A card id is rank * 4 + suit; the Joker is 0. */
    private final Map<UUID, List<Integer>> hands = new HashMap<>();
    private int turn;
    private int timer;
    private int wait;
    private int ty;

    OldMaid(Kind kind, ServerWorld world, BlockPos origin) {
        super(kind, world, origin);
    }

    private static int rank(int card) {
        return card / 4;
    }

    private static String name(int card) {
        if (card == JOKER) {
            return "§dJoker";
        }
        int suit = card % 4;
        return (suit == 1 || suit == 2 ? "§c" : "§f") + RANK[rank(card)] + SUITS[suit];
    }

    @Override
    protected void plan(Builder b) {
        int ox = origin.getX();
        int oy = origin.getY();
        int oz = origin.getZ();
        ty = oy;
        bounds = new Box(ox - 24, oy - 2, oz - 47, ox + 26, oy + 12, oz + 3);
        entry = new Box(ox - 3, oy, oz - 4, ox + 4, oy + 3, oz + 1);
        entryPos = new BlockPos(ox, oy, oz - 2);
        approach = new BlockPos(ox, oy, oz + 6);
        BlockState air = Blocks.AIR.getDefaultState();
        BlockState wall = Blocks.WHITE_CONCRETE.getDefaultState();
        int x0 = ox - 16;
        int x1 = ox + 16;
        int z0 = oz - 40;
        int z1 = oz - 6;
        int tz = oz - 23;
        // An office block's ground floor: the open-plan floor, grey carpet, strip lights, desks pushed to the walls
        b.fill(ox - 24, oy, oz - 47, ox + 25, oy + 10, oz + 2, air);
        b.fill(x0, oy - 1, z0, x1, oy + 6, z1, wall);
        b.fill(x0 + 1, oy, z0 + 1, x1 - 1, oy + 4, z1 - 1, air);
        b.fill(x0 + 1, oy - 1, z0 + 1, x1 - 1, oy - 1, z1 - 1, Blocks.GRAY_WOOL.getDefaultState());
        b.walls(x0, oy + 1, z0, x1, oy + 3, z1, Blocks.LIGHT_GRAY_STAINED_GLASS.getDefaultState());
        for (int x = x0 + 3; x < x1; x += 5) {
            b.fill(x, oy + 4, z0 + 3, x, oy + 4, z1 - 3, Blocks.SEA_LANTERN.getDefaultState());
        }
        for (int x = x0 + 2; x < x1 - 1; x += 3) {
            b.fill(x, oy, z0 + 1, x + 1, oy, z0 + 1, Blocks.SPRUCE_SLAB.getDefaultState());
            b.set(x, oy + 1, z0 + 1, Blocks.BLACK_STAINED_GLASS_PANE.getDefaultState());
            b.fill(x, oy, z1 - 1, x + 1, oy, z1 - 1, Blocks.SPRUCE_SLAB.getDefaultState());
        }
        // The table, the chairs round it, and the cable running from each chair to a box on the wall
        b.fill(ox - 1, oy, tz - 1, ox + 1, oy, tz + 1, Blocks.DARK_OAK_PLANKS.getDefaultState());
        b.fill(ox - 1, oy + 1, tz - 1, ox + 1, oy + 1, tz + 1, Blocks.GREEN_CARPET.getDefaultState());
        for (int k = 0; k < 7; k++) {
            double a = Math.PI * 2 * k / 7;
            int cx = ox + (int) Math.round(Math.sin(a) * 3.2);
            int cz = tz + (int) Math.round(Math.cos(a) * 3.2);
            b.set(cx, oy - 1, cz, Blocks.IRON_BLOCK.getDefaultState());
            b.set(cx, oy, cz, Blocks.BLACK_CARPET.getDefaultState());
        }
        b.fill(x1 - 1, oy, tz - 1, x1 - 1, oy + 2, tz + 1, Blocks.IRON_BLOCK.getDefaultState());
        b.set(x1 - 1, oy + 2, tz, Blocks.REDSTONE_LAMP.getDefaultState());
        b.set(ox, oy + 4, tz, Blocks.GLOWSTONE.getDefaultState());
        // The door
        b.fill(ox - 1, oy, z1, ox + 1, oy + 2, z1, air);
        b.fill(ox - 1, oy, z1 + 1, ox + 1, oy + 2, oz, air);
        b.sign(new BlockPos(ox + 3, oy + 2, oz + 2), Direction.SOUTH, "GAME VENUE", "", "step inside", "to register");
        b.fill(ox + 3, oy, oz + 1, ox + 3, oy + 2, oz + 1, Blocks.IRON_BLOCK.getDefaultState());
    }

    @Override
    protected String[] rules() {
        return new String[]{
                "Old Maid. Seven cards each, the Joker among them. Matching pairs go on the table.",
                "In turn, take one card from the player on your right.",
                "Whoever holds the Joker at the end clears the game.",
                "If the Joker is taken from you, if you run out of cards, or if you leave your chair, the chair goes off."};
    }

    @Override
    protected int timeLimit() {
        return 30 * 60 * 20;
    }

    @Override
    protected void arrange() {
        int ox = origin.getX();
        int tz = origin.getZ() - 23;
        ring.addAll(playing());
        Collections.shuffle(ring, new java.util.Random(random.nextLong()));
        while (ring.size() > 7) {
            fail(ring.remove(ring.size() - 1), "There were only seven chairs.");
        }
        int n = ring.size();
        for (int k = 0; k < n; k++) {
            double a = Math.PI * 2 * Math.round(k * 7.0 / n) / 7;
            Vec3d at = new Vec3d(ox + Math.round(Math.sin(a) * 3.2) + 0.5, ty, tz + Math.round(Math.cos(a) * 3.2) + 0.5);
            UUID id = ring.get(k);
            chair.put(id, at);
            LivingEntity e = entity(id);
            if (e != null) {
                e.teleport(at.x, at.y, at.z);
                if (e instanceof Contestant c) {
                    c.hold();
                    c.lookAt(new Vec3d(ox + 0.5, ty + 1, tz + 0.5));
                }
            }
        }
        // The deck: the Joker and enough pairs that everyone starts with seven (each rank makes two pairs)
        java.util.Random shuffle = new java.util.Random(random.nextLong());
        List<int[]> pairs = new ArrayList<>();
        for (int r = 1; r <= 13; r++) {
            pairs.add(new int[]{r * 4, r * 4 + 1});
            pairs.add(new int[]{r * 4 + 2, r * 4 + 3});
        }
        Collections.shuffle(pairs, shuffle);
        List<Integer> deck = new ArrayList<>();
        deck.add(JOKER);
        for (int k = 0; k < Math.min(pairs.size(), (7 * n - 1) / 2); k++) {
            deck.add(pairs.get(k)[0]);
            deck.add(pairs.get(k)[1]);
        }
        Collections.shuffle(deck, shuffle);
        for (UUID id : ring) {
            hands.put(id, new ArrayList<>());
        }
        for (int i = 0; i < deck.size(); i++) {
            hands.get(ring.get(i % n)).add(deck.get(i));
        }
    }

    @Override
    protected void begin() {
        for (UUID id : ring) {
            discardPairs(id, false);
            show(id);
        }
        for (UUID id : new ArrayList<>(ring)) {
            if (isPlaying(id) && hands.get(id).isEmpty()) {
                announce(nameOf(id) + " put down every card as pairs, and has none left.");
                shock(id, "You ran out of cards.");
            }
        }
        if (checkEnd()) {
            return;
        }
        announce("Pairs on the table. " + nameOf(ring.get(0)) + " draws first.");
        turn = 0;
        startTurn();
    }

    // --- play ---------------------------------------------------------------------------------------------

    private List<UUID> seated() {
        List<UUID> list = new ArrayList<>();
        for (UUID id : ring) {
            if (isPlaying(id)) {
                list.add(id);
            }
        }
        return list;
    }

    /** The player on {@code id}'s right: the next one still in, going round. */
    private UUID right(UUID id) {
        int i = ring.indexOf(id);
        for (int k = 1; k < ring.size(); k++) {
            UUID other = ring.get((i + k) % ring.size());
            if (isPlaying(other)) {
                return other;
            }
        }
        return null;
    }

    private UUID current() {
        List<UUID> in = seated();
        if (in.isEmpty()) {
            return null;
        }
        UUID id = ring.get(Math.floorMod(turn, ring.size()));
        return isPlaying(id) ? id : null;
    }

    private void startTurn() {
        for (int k = 0; k < ring.size() && current() == null; k++) {
            turn++;
        }
        UUID id = current();
        if (id == null) {
            return;
        }
        timer = TURN;
        wait = 40 + random.nextInt(60);
        UUID from = right(id);
        ServerPlayerEntity player = player(id);
        if (player != null && from != null) {
            int n = hands.get(from).size();
            String[] options = new String[n];
            for (int k = 0; k < n; k++) {
                options[k] = "[" + (k + 1) + "]|" + (k + 1);
            }
            choices(player, "Your turn. Take a card from " + nameOf(from) + ":", options);
        }
    }

    private void discardPairs(UUID id, boolean say) {
        List<Integer> hand = hands.get(id);
        boolean found = true;
        while (found) {
            found = false;
            for (int a = 0; a < hand.size() && !found; a++) {
                for (int b = a + 1; b < hand.size() && !found; b++) {
                    if (hand.get(a) != JOKER && rank(hand.get(a)) == rank(hand.get(b))) {
                        String pair = name(hand.get(a)) + " §7and " + name(hand.get(b));
                        hand.remove(b);
                        hand.remove(a);
                        found = true;
                        if (say) {
                            for (ServerPlayerEntity p : audience()) {
                                p.sendMessage(Text.literal("§7" + nameOf(id) + " puts down a pair: " + pair), false);
                            }
                        }
                    }
                }
            }
        }
        Collections.shuffle(hand, new java.util.Random(random.nextLong()));
    }

    private void show(UUID id) {
        ServerPlayerEntity player = player(id);
        if (player == null) {
            return;
        }
        StringBuilder s = new StringBuilder("§7Your hand: ");
        for (int c : hands.get(id)) {
            s.append(name(c)).append(' ');
        }
        player.sendMessage(Text.literal(s.toString().trim()), false);
    }

    /** {@code id} takes card {@code index} from the player on their right. */
    private void draw(UUID id, int index) {
        UUID from = right(id);
        if (from == null) {
            return;
        }
        List<Integer> theirs = hands.get(from);
        if (theirs.isEmpty()) {
            return;
        }
        int card = theirs.remove(Math.max(0, Math.min(theirs.size() - 1, index)));
        hands.get(id).add(card);
        world.playSound(null, origin.getX(), ty + 1, origin.getZ() - 23, SoundEvents.ITEM_BOOK_PAGE_TURN, SoundCategory.PLAYERS, 1.0F, 1.2F);
        tell(entity(id), "You took " + name(card) + "§r from " + nameOf(from) + ".", Formatting.WHITE);
        tell(entity(from), nameOf(id) + " took your " + name(card) + "§r.", Formatting.GRAY);
        if (card == JOKER) {
            announce(nameOf(id) + " has taken the Joker from " + nameOf(from) + ".");
            shock(from, "The Joker was taken from you.");     // what's left of their hand passes to their left: to whoever took it
        } else if (theirs.isEmpty()) {
            announce(nameOf(from) + " has no cards left.");
            shock(from, "You ran out of cards.");
        }
        discardPairs(id, true);
        if (isPlaying(id) && hands.get(id).isEmpty()) {
            announce(nameOf(id) + " has no cards left.");
            shock(id, "You ran out of cards.");
        }
        for (UUID u : seated()) {
            show(u);
        }
        if (!checkEnd()) {
            turn = ring.indexOf(id) + 1;
            startTurn();
        }
    }

    private boolean checkEnd() {
        List<UUID> in = seated();
        if (in.size() > 1) {
            return false;
        }
        for (UUID id : in) {
            announce(nameOf(id) + " holds the Joker.");
            clear(id);
        }
        finish();
        return true;
    }

    private void shock(UUID id, String why) {
        LivingEntity e = entity(id);
        if (e != null) {
            world.spawnParticles(ParticleTypes.ELECTRIC_SPARK, e.getX(), e.getY() + 1.0, e.getZ(), 60, 0.4, 0.8, 0.4, 0.2);
            world.playSound(null, e.getX(), e.getY(), e.getZ(), SoundEvents.ENTITY_LIGHTNING_BOLT_IMPACT, SoundCategory.PLAYERS, 1.5F, 1.4F);
        }
        kill(id, Laser.SHOCK, why);
    }

    @Override
    protected void died(UUID id) {
        // their cards stay in play: whoever's on their left picks them up
        List<Integer> hand = hands.get(id);
        if (hand != null && !hand.isEmpty() && phase == Phase.RUNNING) {
            UUID heir = null;
            int i = ring.indexOf(id);
            for (int k = ring.size() - 1; k > 0; k--) {
                UUID other = ring.get((i + k) % ring.size());
                if (isPlaying(other)) {
                    heir = other;
                    break;
                }
            }
            if (heir != null) {
                hands.get(heir).addAll(hand);
                hand.clear();
                discardPairs(heir, true);
            }
        }
    }

    @Override
    protected void running() {
        // nobody gets up
        for (UUID id : seated()) {
            LivingEntity e = entity(id);
            Vec3d at = chair.get(id);
            if (e != null && at != null && e.getPos().squaredDistanceTo(at) > 1.4 * 1.4) {
                announce(nameOf(id) + " got up from their chair.");
                shock(id, "You left your chair.");
            } else if (e instanceof Contestant c) {
                c.hold();
            }
        }
        if (checkEnd()) {
            return;
        }
        UUID id = current();
        if (id == null) {
            turn++;
            startTurn();
            return;
        }
        Contestant c = contestant(id);
        if (c != null) {
            if (--wait == 0) {
                UUID from = right(id);
                int n = from == null ? 0 : hands.get(from).size();
                if (n > 0) {
                    c.look(entity(from));
                    draw(id, pick(c, from));
                }
            }
        } else if (--timer <= 0) {
            UUID from = right(id);
            tell(entity(id), "Too slow: you take a card at random.", Formatting.GRAY);
            draw(id, from == null ? 0 : random.nextInt(Math.max(1, hands.get(from).size())));
        }
    }

    /** Which card a stranger takes: mostly luck, but a good reader of faces sometimes spots where the Joker is. */
    private int pick(Contestant c, UUID from) {
        List<Integer> theirs = hands.get(from);
        int joker = theirs.indexOf(JOKER);
        if (joker >= 0 && random.nextFloat() < c.judgment() * 0.25F) {
            return joker;
        }
        return random.nextInt(theirs.size());
    }

    @Override
    protected void answered(ServerPlayerEntity player, String answer) {
        UUID id = player.getUuid();
        if (!id.equals(current())) {
            tell(player, "It's not your turn.", Formatting.GRAY);
            return;
        }
        try {
            draw(id, Integer.parseInt(answer.trim()) - 1);
        } catch (NumberFormatException e) {
            tell(player, "Pick a card by its number.", Formatting.GRAY);
        }
    }

    @Override
    protected boolean attacked(ServerPlayerEntity player, Entity target) {
        tell(player, "Stay in your chair.", Formatting.GRAY);
        return true;
    }

    // --- what a stranger can say they'll do, and then does ---------------------------------------------------

    @Override
    public Map<String, String> options(Contestant who) {
        if (!who.getUuid().equals(current())) {
            return Map.of();
        }
        UUID from = right(who.getUuid());
        int n = from == null ? 0 : hands.get(from).size();
        Map<String, String> options = new LinkedHashMap<>();
        options.put("TAKE_N", "take card number N (1 to " + n + ") from " + (from == null ? "the player on your right" : nameOf(from))
                + "'s hand, counting from their left: for example [TAKE 2]");
        return options;
    }

    @Override
    protected Map<String, String> optionWords(Contestant who) {
        return Map.of("TAKE_N", "\\b(card|number)\\s+(\\d)\\b");
    }

    @Override
    public void chose(Contestant who, String option) {
        if (who.getUuid().equals(current()) && option.startsWith("TAKE_")) {
            try {
                draw(who.getUuid(), Integer.parseInt(option.substring(5)) - 1);
            } catch (NumberFormatException ignored) {
            }
        }
    }

    // --- show ---------------------------------------------------------------------------------------------

    @Override
    protected void more(ServerPlayerEntity player, Hud hud) {
        UUID id = player.getUuid();
        List<Integer> mine = hands.get(id);
        if (mine != null) {
            StringBuilder s = new StringBuilder("§f");
            for (int c : mine) {
                s.append(name(c)).append(' ');
            }
            hud.line(s.toString().trim());
        }
        UUID now = current();
        if (now != null) {
            hud.line(now.equals(id) ? "§aYOUR DRAW " + Hud.clock(timer) : "§7" + nameOf(now) + " is drawing");
        }
        for (UUID u : seated()) {
            if (!u.equals(id)) {
                hud.line("§7" + nameOf(u) + " §f" + hands.get(u).size() + " cards");
            }
        }
    }

    @Override
    public String situation(Contestant who) {
        List<Integer> mine = hands.getOrDefault(who.getUuid(), List.of());
        StringBuilder s = new StringBuilder();
        for (int c : mine) {
            s.append(c == JOKER ? "Joker" : RANK[rank(c)]).append(' ');
        }
        return "You're playing OLD MAID at a table on an office floor; every chair is wired to shock you. Each turn you take a card from the "
                + "player on your right. Whoever holds the Joker at the end clears it; have it taken from you, run out of cards, or leave your "
                + "chair, and you're electrocuted. Your hand: " + s.toString().trim() + "." + (mine.contains(JOKER)
                ? " You hold the Joker: you must not let anyone see which card it is." : " You want the Joker.");
    }
}
