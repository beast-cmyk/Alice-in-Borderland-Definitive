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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Zombie Hunt, in a virus research institute: four groups, each dealt one suit, seven number cards each. Twenty rounds of
 * one-on-one matches at the tables: play a card of your suit, higher wins, and the winner takes a card from the loser.
 * Each group has one Zombie card, which beats anything and infects whoever it beats. Everyone has one Shotgun, good once,
 * that kills a zombie and does nothing to a human. A few have a Vaccine, which stops a Zombie card and cures its player,
 * though never yourself. Run out of number cards and you're out. After twenty rounds, whichever side is smaller, human or
 * zombie, dies.
 */
final class ZombieHunt extends Game {
    private static final int ROUNDS = 20;
    private static final int CHOOSE = 25 * 20;
    private static final int REVEAL = 5 * 20;
    private static final String[] SUIT = {"♠", "♥", "♦", "♣"};
    private static final String[] RANK = {"A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K"};

    private final Map<UUID, Integer> suit = new HashMap<>();
    private final Map<UUID, List<Integer>> hand = new HashMap<>();
    private final Set<UUID> zombie = new HashSet<>();
    private final Set<UUID> shotgun = new HashSet<>();
    private final Set<UUID> vaccine = new HashSet<>();
    private final Map<UUID, UUID> against = new HashMap<>();
    private final Map<UUID, Integer> table = new HashMap<>();
    private final Map<UUID, String> played = new HashMap<>();
    private final Map<UUID, Integer> decideAt = new HashMap<>();
    /** What each stranger has found out: who they know to be a zombie. */
    private final Map<UUID, Set<UUID>> knows = new HashMap<>();
    private final List<Vec3d[]> tables = new ArrayList<>();
    private int round;
    private int timer;
    private boolean revealing;
    private int oy;

    ZombieHunt(Kind kind, ServerWorld world, BlockPos origin) {
        super(kind, world, origin);
    }

    private static String card(int id) {
        int s = id / 13;
        return (s == 1 || s == 2 ? "§c" : "§f") + RANK[id % 13] + SUIT[s];
    }

    private static int value(int id) {
        return id % 13 + 1;
    }

    @Override
    protected void plan(Builder b) {
        int ox = origin.getX();
        oy = origin.getY();
        int oz = origin.getZ();
        bounds = new Box(ox - 24, oy - 2, oz - 47, ox + 26, oy + 10, oz + 3);
        entry = new Box(ox - 3, oy, oz - 4, ox + 4, oy + 3, oz + 1);
        entryPos = new BlockPos(ox, oy, oz - 2);
        approach = new BlockPos(ox, oy, oz + 6);
        BlockState air = Blocks.AIR.getDefaultState();
        BlockState white = Blocks.WHITE_CONCRETE.getDefaultState();
        int x0 = ox - 20;
        int x1 = ox + 20;
        int z0 = oz - 44;
        int z1 = oz - 6;
        b.fill(ox - 24, oy, oz - 47, ox + 25, oy + 8, oz + 2, air);
        b.fill(ox - 24, oy - 1, oz - 47, ox + 25, oy - 1, oz + 2, Blocks.GRAY_CONCRETE.getDefaultState());
        // The institute: white walls, a pale floor, strip lights, glass partitions
        b.fill(x0, oy - 1, z0, x1, oy + 5, z1, white);
        b.fill(x0 + 1, oy, z0 + 1, x1 - 1, oy + 4, z1 - 1, air);
        b.fill(x0 + 1, oy - 1, z0 + 1, x1 - 1, oy - 1, z1 - 1, Blocks.SMOOTH_QUARTZ.getDefaultState());
        b.walls(x0, oy + 1, z0, x1, oy + 2, z1, Blocks.LIGHT_GRAY_STAINED_GLASS.getDefaultState());
        for (int x = x0 + 3; x < x1; x += 6) {
            b.fill(x, oy + 4, z0 + 2, x, oy + 4, z1 - 2, Blocks.SEA_LANTERN.getDefaultState());
        }
        for (int x = x0 + 9; x < x1 - 4; x += 10) {
            b.fill(x, oy, z0 + 1, x, oy + 3, z0 + 9, Blocks.GLASS_PANE.getDefaultState());
        }
        // Lab benches along the north wall, a biohazard sign
        for (int x = x0 + 1; x < x1; x++) {
            b.set(x, oy, z0 + 1, (x % 3 == 0) ? Blocks.BREWING_STAND.getDefaultState() : Blocks.SMOOTH_QUARTZ_SLAB.getDefaultState());
        }
        b.fill(ox - 2, oy + 1, z0 + 1, ox + 2, oy + 3, z0 + 1, Blocks.YELLOW_CONCRETE.getDefaultState());
        b.fill(ox - 1, oy + 2, z0 + 1, ox + 1, oy + 2, z0 + 1, Blocks.BLACK_CONCRETE.getDefaultState());
        b.set(ox, oy + 1, z0 + 1, Blocks.BLACK_CONCRETE.getDefaultState());
        b.set(ox, oy + 3, z0 + 1, Blocks.BLACK_CONCRETE.getDefaultState());
        // Eight tables, a chair each side
        tables.clear();
        for (int j = 0; j < 2; j++) {
            for (int i = 0; i < 4; i++) {
                int tx = ox - 12 + 8 * i;
                int tz = oz - 32 + 14 * j;
                b.set(tx, oy, tz, Blocks.SMOOTH_QUARTZ.getDefaultState());
                b.set(tx, oy + 1, tz, Blocks.WHITE_CARPET.getDefaultState());
                b.set(tx, oy, tz - 2, Blocks.SPRUCE_SLAB.getDefaultState());
                b.set(tx, oy, tz + 2, Blocks.SPRUCE_SLAB.getDefaultState());
                b.set(tx, oy + 4, tz, Blocks.GLOWSTONE.getDefaultState());
                tables.add(new Vec3d[]{new Vec3d(tx + 0.5, oy + 0.5, tz - 1.5), new Vec3d(tx + 0.5, oy + 0.5, tz + 2.5)});
            }
        }
        b.fill(ox - 1, oy, z1, ox + 1, oy + 2, z1, air);
        b.sign(new BlockPos(ox + 3, oy + 2, oz + 2), Direction.SOUTH, "GAME VENUE", "", "step inside", "to register");
        b.fill(ox + 3, oy, oz + 1, ox + 3, oy + 2, oz + 1, Blocks.IRON_BLOCK.getDefaultState());
    }

    @Override
    protected String[] rules() {
        return new String[]{
                "Zombie Hunt. Four groups, each dealt one suit: seven number cards each. Twenty rounds of one-on-one matches.",
                "Play a card of your own suit. Higher wins, and the winner takes the loser's card. Run out of number cards and you're out.",
                "Each group has one Zombie card. It beats any card, and whoever it beats is infected and gets a Zombie card of their own.",
                "Everyone has one Shotgun: it kills a zombie, and does nothing to a human. A Vaccine stops a Zombie card and cures its player, never yourself.",
                "After twenty rounds, whichever side is smaller, humans or zombies, is out."};
    }

    @Override
    protected int timeLimit() {
        return ROUNDS * (CHOOSE + REVEAL) + 20 * 30;
    }

    @Override
    protected void arrange() {
        List<UUID> all = new ArrayList<>(playing());
        Collections.shuffle(all, new java.util.Random(random.nextLong()));
        for (int i = 0; i < all.size(); i++) {
            UUID id = all.get(i);
            int s = i % 4;
            suit.put(id, s);
            List<Integer> cards = new ArrayList<>();
            for (int k = 0; k < 7; k++) {
                cards.add(s * 13 + random.nextInt(13));
            }
            hand.put(id, cards);
            shotgun.add(id);
            knows.put(id, new HashSet<>());
        }
        // One Zombie card per group, and a Vaccine per group, to someone else
        for (int s = 0; s < 4; s++) {
            List<UUID> group = new ArrayList<>();
            for (UUID id : all) {
                if (suit.get(id) == s) {
                    group.add(id);
                }
            }
            if (group.isEmpty()) {
                continue;
            }
            Collections.shuffle(group, new java.util.Random(random.nextLong()));
            zombie.add(group.get(0));
            if (group.size() > 1) {
                vaccine.add(group.get(1));
            }
        }
        for (UUID id : all) {
            ServerPlayerEntity p = player(id);
            if (p != null) {
                tell(p, "Your suit: " + SUIT[suit.get(id)] + ". " + (zombie.contains(id) ? "You hold a ZOMBIE card: you're a zombie. Nobody knows." : "You're human.")
                        + (vaccine.contains(id) ? " You hold a VACCINE." : ""), zombie.contains(id) ? Formatting.DARK_GREEN : Formatting.WHITE);
            }
        }
    }

    @Override
    protected void begin() {
        nextRound();
    }

    // --- rounds ---------------------------------------------------------------------------------------------

    private int numbers(UUID id) {
        return hand.get(id).size();
    }

    private List<Integer> playable(UUID id) {
        List<Integer> list = new ArrayList<>();
        for (int c : hand.get(id)) {
            if (c / 13 == suit.get(id)) {
                list.add(c);
            }
        }
        Collections.sort(list, (a, b) -> Integer.compare(value(a), value(b)));
        return list;
    }

    private void nextRound() {
        round++;
        revealing = false;
        timer = CHOOSE;
        against.clear();
        table.clear();
        played.clear();
        List<UUID> pool = new ArrayList<>(playing());
        Collections.shuffle(pool, new java.util.Random(random.nextLong()));
        int t = 0;
        while (pool.size() >= 2 && t < tables.size()) {
            UUID a = pool.remove(0);
            int pick = 0;
            for (int k = 0; k < pool.size(); k++) {
                if (!suit.get(pool.get(k)).equals(suit.get(a))) {
                    pick = k;
                    break;
                }
            }
            UUID b = pool.remove(pick);
            against.put(a, b);
            against.put(b, a);
            table.put(a, t);
            table.put(b, t);
            seat(a, tables.get(t)[0], tables.get(t)[1]);
            seat(b, tables.get(t)[1], tables.get(t)[0]);
            t++;
        }
        for (UUID id : pool) {
            tell(entity(id), "You sit this round out.", Formatting.GRAY);
        }
        announce("Round " + round + " of " + ROUNDS + ".");
        for (UUID id : against.keySet()) {
            decideAt.put(id, 40 + random.nextInt(200));
            ServerPlayerEntity p = player(id);
            if (p != null) {
                offer(p);
            }
        }
    }

    private void seat(UUID id, Vec3d at, Vec3d facing) {
        LivingEntity e = entity(id);
        if (e == null) {
            return;
        }
        e.teleport(at.x, at.y, at.z);
        if (e instanceof Contestant c) {
            c.hold();
            c.lookAt(facing.add(0, 1.2, 0));
        }
    }

    private void offer(ServerPlayerEntity p) {
        UUID id = p.getUuid();
        List<String> options = new ArrayList<>();
        for (int c : playable(id)) {
            options.add(card(c) + "|n" + c);
        }
        if (zombie.contains(id)) {
            options.add("§2ZOMBIE|zombie");
        }
        if (shotgun.contains(id)) {
            options.add("§4SHOTGUN|shotgun");
        }
        if (vaccine.contains(id)) {
            options.add("§bVACCINE|vaccine");
        }
        choices(p, "Your match against " + nameOf(against.get(id)) + ". Play:", options.toArray(new String[0]));
    }

    private boolean play(UUID id, String what) {
        if (revealing || !against.containsKey(id) || played.containsKey(id)) {
            return false;
        }
        if (what.equals("zombie") && !zombie.contains(id) || what.equals("shotgun") && !shotgun.contains(id)
                || what.equals("vaccine") && !vaccine.contains(id)) {
            return false;
        }
        if (what.startsWith("n")) {
            try {
                int c = Integer.parseInt(what.substring(1));
                if (!hand.get(id).contains(c) || c / 13 != suit.get(id)) {
                    return false;
                }
            } catch (NumberFormatException e) {
                return false;
            }
        }
        played.put(id, what);
        tell(entity(id), "Played. Waiting for " + nameOf(against.get(id)) + ".", Formatting.GRAY);
        return true;
    }

    @Override
    protected void answered(ServerPlayerEntity player, String answer) {
        if (!play(player.getUuid(), answer.trim().toLowerCase())) {
            tell(player, "You can't play that now.", Formatting.GRAY);
        }
    }

    private void reveal() {
        revealing = true;
        timer = REVEAL;
        Set<UUID> done = new HashSet<>();
        for (UUID a : new ArrayList<>(against.keySet())) {
            UUID b = against.get(a);
            if (done.contains(a) || !isPlaying(a) || !isPlaying(b)) {
                continue;
            }
            done.add(a);
            done.add(b);
            for (UUID id : new UUID[]{a, b}) {
                played.computeIfAbsent(id, k -> {
                    List<Integer> mine = playable(k);
                    return mine.isEmpty() ? "none" : "n" + mine.get(random.nextInt(mine.size()));
                });
            }
            match(a, b);
        }
        // anyone left with no number cards is out
        for (UUID id : playing()) {
            if (numbers(id) == 0) {
                announce(nameOf(id) + " has no number cards left.");
                kill(id, Laser.SHOCK, "You ran out of number cards.");
            }
        }
    }

    private String shown(UUID id) {
        String p = played.get(id);
        return switch (p) {
            case "zombie" -> "§2ZOMBIE";
            case "shotgun" -> "§4SHOTGUN";
            case "vaccine" -> "§bVACCINE";
            case "none" -> "§7nothing";
            default -> card(Integer.parseInt(p.substring(1)));
        };
    }

    private void match(UUID a, UUID b) {
        String pa = played.get(a);
        String pb = played.get(b);
        String line = nameOf(a) + " " + shown(a) + "§r vs " + nameOf(b) + " " + shown(b) + "§r: ";
        // the shotguns first
        for (UUID[] pair : new UUID[][]{{a, b}, {b, a}}) {
            if (played.get(pair[0]).equals("shotgun")) {
                shotgun.remove(pair[0]);
                boom(pair[0]);
                if (zombie.contains(pair[1])) {
                    tellBoth(a, b, line + nameOf(pair[1]) + " was a zombie, and is shot dead.");
                    announce("A shot at table " + (table.get(a) + 1) + ". " + nameOf(pair[1]) + " was a zombie.");
                    learnAll(pair[1]);
                    kill(pair[1], Laser.BLAST, "You were shot. You were a zombie.");
                    return;
                }
                tellBoth(a, b, line + nameOf(pair[1]) + " is human: the shotgun does nothing.");
            }
        }
        boolean za = pa.equals("zombie");
        boolean zb = pb.equals("zombie");
        if (za && zb) {
            tellBoth(a, b, line + "two zombies. Nothing happens.");
            return;
        }
        if (za || zb) {
            UUID z = za ? a : b;
            UUID other = za ? b : a;
            if (played.get(other).equals("vaccine")) {
                vaccine.remove(other);
                zombie.remove(z);
                tellBoth(a, b, line + "the Vaccine stops the Zombie card. " + nameOf(z) + " is cured.");
                return;
            }
            if (played.get(other).equals("shotgun")) {
                return;     // handled above: they were human, the shot did nothing, and the zombie card... was stopped by a dead zombie
            }
            if (!zombie.contains(other)) {
                zombie.add(other);
                knows.get(other).add(z);
                tellBoth(a, b, line + nameOf(other) + " is infected.");
                LivingEntity e = entity(other);
                if (e != null) {
                    world.spawnParticles(ParticleTypes.SQUID_INK, e.getX(), e.getY() + 1.4, e.getZ(), 12, 0.2, 0.3, 0.2, 0.02);
                }
            } else {
                tellBoth(a, b, line + nameOf(other) + " was already a zombie.");
            }
            return;
        }
        int va = pa.startsWith("n") ? value(Integer.parseInt(pa.substring(1))) : 0;
        int vb = pb.startsWith("n") ? value(Integer.parseInt(pb.substring(1))) : 0;
        if (va == vb) {
            tellBoth(a, b, line + "a draw.");
            return;
        }
        UUID win = va > vb ? a : b;
        UUID lose = win == a ? b : a;
        String lost = played.get(lose);
        int taken;
        if (lost.startsWith("n")) {
            taken = Integer.parseInt(lost.substring(1));
        } else if (!hand.get(lose).isEmpty()) {
            taken = hand.get(lose).get(random.nextInt(hand.get(lose).size()));
        } else {
            taken = -1;
        }
        if (taken >= 0) {
            hand.get(lose).remove(Integer.valueOf(taken));
            hand.get(win).add(taken);
        }
        world.playSound(null, tables.get(table.get(a))[0].x, oy + 1, tables.get(table.get(a))[0].z, SoundEvents.ITEM_BOOK_PAGE_TURN, SoundCategory.PLAYERS, 1.0F, 1.0F);
        tellBoth(a, b, line + nameOf(win) + " wins" + (taken >= 0 ? " and takes " + card(taken) + "§r." : "."));
    }

    private void boom(UUID shooter) {
        LivingEntity e = entity(shooter);
        if (e != null) {
            world.playSound(null, e.getX(), e.getY(), e.getZ(), SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.PLAYERS, 1.2F, 1.8F);
            world.spawnParticles(ParticleTypes.SMOKE, e.getX(), e.getY() + 1.3, e.getZ(), 15, 0.2, 0.2, 0.2, 0.05);
        }
    }

    private void learnAll(UUID z) {
        for (Set<UUID> k : knows.values()) {
            k.add(z);
        }
    }

    private void tellBoth(UUID a, UUID b, String text) {
        for (UUID id : new UUID[]{a, b}) {
            ServerPlayerEntity p = player(id);
            if (p != null) {
                p.sendMessage(Text.literal(text), false);
            }
        }
    }

    @Override
    protected void running() {
        if (!revealing) {
            for (UUID id : against.keySet()) {
                if (!played.containsKey(id) && contestant(id) != null && decideAt.merge(id, -1, Integer::sum) <= 0) {
                    play(id, choose(contestant(id)));
                }
            }
            boolean all = true;
            for (UUID id : against.keySet()) {
                if (isPlaying(id) && !played.containsKey(id)) {
                    all = false;
                }
            }
            if (--timer <= 0 || all) {
                reveal();
            }
        } else if (--timer <= 0) {
            if (round >= ROUNDS) {
                judge();
            } else {
                nextRound();
            }
        }
    }

    /** What a stranger plays. */
    private String choose(Contestant c) {
        UUID id = c.getUuid();
        UUID them = against.get(id);
        boolean known = knows.get(id).contains(them);
        if (known && !zombie.contains(id)) {
            if (vaccine.contains(id)) {
                return "vaccine";
            }
            if (shotgun.contains(id)) {
                return "shotgun";
            }
        }
        if (zombie.contains(id)) {
            // the clever ones see it: spread it, and the zombies will be the bigger side
            float spread = c.nature == Contestant.Nature.CLEVER ? 0.9F : c.nature == Contestant.Nature.KIND ? 0.3F : 0.6F;
            if (random.nextFloat() < spread) {
                return "zombie";
            }
        } else if (shotgun.contains(id) && (c.nature == Contestant.Nature.RECKLESS || c.nature == Contestant.Nature.SELFISH
                || c.feelings.anger > 0.5F || c.feelings.fear > 0.7F) && random.nextFloat() < 0.12F + c.feelings.anger * 0.3F + c.feelings.fear * 0.15F) {
            return "shotgun";
        }
        List<Integer> mine = playable(id);
        if (mine.isEmpty()) {
            return zombie.contains(id) ? "zombie" : shotgun.contains(id) ? "shotgun" : "none";
        }
        return "n" + mine.get(random.nextFloat() < c.judgment() ? mine.size() - 1 : random.nextInt(mine.size()));
    }

    private void judge() {
        int z = 0;
        int h = 0;
        for (UUID id : playing()) {
            if (zombie.contains(id)) {
                z++;
            } else {
                h++;
            }
        }
        announce("Twenty rounds. Humans: " + h + ". Zombies: " + z + ".");
        boolean zombiesWin = z > h;
        boolean draw = z == h;
        for (UUID id : playing()) {
            boolean isZ = zombie.contains(id);
            if (draw || isZ == zombiesWin) {
                clear(id);
            } else {
                kill(id, Laser.BLAST, isZ ? "The zombies were the smaller side." : "The humans were the smaller side.");
            }
        }
        finish();
    }

    @Override
    protected boolean attacked(ServerPlayerEntity player, Entity target) {
        tell(player, "Play your cards.", Formatting.GRAY);
        return true;
    }

    // --- what a stranger can say they'll do, and then does ---------------------------------------------------

    @Override
    public Map<String, String> options(Contestant who) {
        UUID id = who.getUuid();
        if (revealing || !against.containsKey(id) || played.containsKey(id)) {
            return Map.of();
        }
        Map<String, String> options = new LinkedHashMap<>();
        options.put("PLAY_N", "play your number card with value N (1 to 13) in this match: for example [PLAY 9]");
        if (zombie.contains(id)) {
            options.put("ZOMBIE", "play your Zombie card: it beats anything and infects your opponent");
        }
        if (shotgun.contains(id)) {
            options.put("SHOTGUN", "use your one Shotgun on your opponent: it kills them if they're a zombie");
        }
        if (vaccine.contains(id)) {
            options.put("VACCINE", "play your Vaccine: it stops a Zombie card and cures your opponent");
        }
        return options;
    }

    @Override
    protected Map<String, String> optionWords(Contestant who) {
        Map<String, String> words = new LinkedHashMap<>();
        words.put("ZOMBIE", "\\b(zombie card|infect you|infect them)\\b");
        words.put("SHOTGUN", "\\b(shotgun|shoot)\\b");
        words.put("VACCINE", "\\b(vaccine|cure you)\\b");
        return words;
    }

    @Override
    public void chose(Contestant who, String option) {
        UUID id = who.getUuid();
        if (option.startsWith("PLAY_")) {
            try {
                int v = Integer.parseInt(option.substring(5));
                for (int c : playable(id)) {
                    if (value(c) == v) {
                        play(id, "n" + c);
                        return;
                    }
                }
            } catch (NumberFormatException ignored) {
            }
        } else {
            play(id, option.toLowerCase());
        }
    }

    // --- show ---------------------------------------------------------------------------------------------

    @Override
    protected void more(ServerPlayerEntity player, Hud hud) {
        UUID id = player.getUuid();
        if (!suit.containsKey(id)) {
            return;
        }
        boolean z = zombie.contains(id);
        hud.role(z ? "ZOMBIE" : "HUMAN", z ? 0x2E8B2E : 0xFFFFFF);
        hud.line("§fROUND §e" + round + "/" + ROUNDS + "  §fSUIT " + SUIT[suit.get(id)]);
        StringBuilder s = new StringBuilder("§f");
        for (int c : hand.get(id)) {
            s.append(card(c)).append(' ');
        }
        hud.line(s.toString().trim());
        hud.line((shotgun.contains(id) ? "§4SHOTGUN " : "") + (vaccine.contains(id) ? "§bVACCINE " : "") + (z ? "§2ZOMBIE" : ""));
        if (against.containsKey(id)) {
            hud.line("§7vs " + nameOf(against.get(id)) + (played.containsKey(id) ? "  §aplayed" : "  " + Hud.clock(timer)));
        }
    }

    @Override
    public String situation(Contestant who) {
        UUID id = who.getUuid();
        if (!suit.containsKey(id)) {
            return "You're playing ZOMBIE HUNT in a virus research institute.";
        }
        StringBuilder s = new StringBuilder("You're playing ZOMBIE HUNT in a virus research institute: twenty rounds of one-on-one card matches. "
                + "Higher card of your suit wins and takes the loser's card; a Zombie card beats anything and infects the loser; a Shotgun kills a "
                + "zombie and does nothing to a human; a Vaccine stops a Zombie card. At the end, whichever side is smaller, humans or zombies, dies. ");
        s.append("Round ").append(round).append(" of 20. You are ").append(zombie.contains(id) ? "secretly a ZOMBIE" : "human")
                .append(". You have ").append(numbers(id)).append(" number cards").append(shotgun.contains(id) ? ", a shotgun" : "")
                .append(vaccine.contains(id) ? ", a vaccine" : "").append('.');
        if (against.containsKey(id)) {
            s.append(" You're facing ").append(nameOf(against.get(id))).append(knows.get(id).contains(against.get(id)) ? ", who you know is a zombie." : ".");
        }
        return s.toString();
    }
}
