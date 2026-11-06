package com.deadmanshand.game;

import com.deadmanshand.Borderland;
import com.deadmanshand.Builder;
import com.deadmanshand.DeadMansHand;
import com.deadmanshand.Hud;
import com.deadmanshand.Laser;
import com.deadmanshand.Visa;
import com.deadmanshand.npc.Contestant;
import com.deadmanshand.npc.Lines;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.GameMode;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One game, from the moment its venue lights up to the last body. The venue goes up; you step in to register; entry
 * closes a minute after the first player; the rules are read out; the game runs to its time limit; whoever cleared it
 * gets the card and the days. Leave the venue while it's running and the laser finds you. Server thread only.
 */
public abstract class Game {
    public enum Phase {BUILDING, OPEN, BRIEFING, RUNNING, ENDED}

    public enum Fate {PLAYING, CLEARED, DEAD}

    /** Entry closes this long after the first player registers. */
    protected static final int ENTRY_TICKS = 20 * 60;
    protected static final int BRIEF_TICKS = 20 * 16;
    private static int counter;

    public final int id = ++counter;
    public final Kind kind;
    public final ServerWorld world;
    /** Where the venue stands: its entrance, at ground level. */
    public final BlockPos origin;
    protected final Random random;
    protected final Builder builder;
    /** The venue. Leave it while the game is on and you're lasered. */
    protected Box bounds;
    /** Step in here to register. */
    protected Box entry;
    /** The middle of the entry, where the strangers walk to. */
    protected BlockPos entryPos;
    /** Where new strangers turn up, a little way off. */
    protected BlockPos approach;
    public Phase phase = Phase.BUILDING;
    protected int phaseTicks;
    protected int timeLeft;
    private int entryLeft = -1;
    boolean closed;
    private boolean planned;
    protected final LinkedHashMap<UUID, Fate> seats = new LinkedHashMap<>();
    protected final Set<UUID> players = new HashSet<>();
    protected final Map<UUID, String> names = new HashMap<>();
    private final Map<UUID, GameMode> modes = new HashMap<>();
    private final List<UUID> recruits = new ArrayList<>();
    @Nullable
    private ServerBossBar bar;

    protected Game(Kind kind, ServerWorld world, BlockPos origin) {
        this.kind = kind;
        this.world = world;
        this.origin = origin;
        this.random = world.random;
        this.builder = new Builder(world);
    }

    // --- what each game says ----------------------------------------------------------------------------------

    /** Sets {@link #bounds}, {@link #entry}, {@link #entryPos} and {@link #approach}, and queues the venue's blocks. */
    protected abstract void plan(Builder b);

    /** The rules, as the announcer reads them (and the phone shows them). */
    protected abstract String[] rules();

    /** Entry is closed: put everyone where they start. */
    protected abstract void arrange();

    /** The game starts. */
    protected abstract void begin();

    /** Every tick while it runs. */
    protected abstract void running();

    /** Time limit, in ticks. */
    protected abstract int timeLimit();

    /** Time ran out: by default, anyone who hasn't cleared dies. */
    protected void timeUp() {
        for (UUID id : playing()) {
            fail(id, "Time's up.");
        }
    }

    /** For the AI: what's going on in this game right now, from {@code who}'s point of view. */
    public abstract String situation(Contestant who);

    /** A player used a block inside the venue. */
    protected ActionResult use(ServerPlayerEntity player, BlockPos pos, BlockState state) {
        return ActionResult.PASS;
    }

    /** A player said something nearby. */
    protected void heard(ServerPlayerEntity player, String message) {
    }

    /** A player playing used one of the game's items (a match, a slip...). True if the game took it. */
    protected boolean usedItem(ServerPlayerEntity player, ItemStack stack) {
        return false;
    }

    /** A player playing right-clicked someone. True if the game took it. */
    protected boolean touched(ServerPlayerEntity player, Entity target) {
        return false;
    }

    /** A player playing hit someone. True to cancel the hit. */
    protected boolean attacked(ServerPlayerEntity player, Entity target) {
        return false;
    }

    /** A player typed /answer while playing. */
    protected void answered(ServerPlayerEntity player, String answer) {
        player.sendMessage(Text.literal("There's nothing to answer in this game.").formatted(Formatting.GRAY), false);
    }

    /** Extra lines for the phone on screen. */
    protected void more(ServerPlayerEntity player, Hud hud) {
    }

    /** Someone playing died (any cause). */
    protected void died(UUID id) {
    }

    /** The camp's own game: its people play it. */
    protected boolean campGame() {
        return false;
    }

    /** Strangers turn up to fill the empty places when entry closes. */
    protected boolean fillSeats() {
        return true;
    }

    /** Entry closes the moment the first player is in (a game that springs on you). */
    protected boolean instantEntry() {
        return false;
    }

    /** Every tick while the rules are being read. */
    protected void briefingTick(int t) {
    }

    /** It's over: tidy up (dealers, props). */
    protected void ended() {
    }

    // --- what strangers can decide to do, so what they say and what they do are the same thing ----------------

    /**
     * What {@code who} could decide to do right now, for the AI that speaks for them: a tag (one word, capitals) and what it
     * means. When their line ends with one of these tags they really do it. Empty when there's nothing to choose.
     */
    public Map<String, String> options(Contestant who) {
        return Map.of();
    }

    /**
     * Ways of saying each option in plain words (regular expressions, lower case), for when a line commits to one without
     * its tag: "I'm taking the die door".
     */
    protected Map<String, String> optionWords(Contestant who) {
        return Map.of();
    }

    /** {@code who} has said they'll do {@code option}: now they do it. */
    public void chose(Contestant who, String option) {
    }

    private static final java.util.regex.Pattern NEGATED = java.util.regex.Pattern.compile(
            "\\b(not|never|don'?t|won'?t|can'?t|shouldn'?t|wouldn'?t|isn'?t|no way|avoid|stay away|anything but|except)\\b");
    private static final java.util.regex.Pattern DOING = java.util.regex.Pattern.compile(
            "\\b(go|going|goes|gonna|take|taking|try|trying|open|opening|pick|picking|choose|choosing|chose|head|heading|run|running|"
                    + "through|i'?ll|let'?s|follow|following|use|using|answer|answering|vote|voting|come|coming|stay|staying|wait|waiting|stick|"
                    + "strike|striking|light|press|pressing|fight|fighting|hide|hiding|grab|get)\\b");

    /**
     * Which option {@code said} commits to, if exactly one: the clause has to mention the option and something being done
     * about it, and mustn't be a "not".
     */
    @Nullable
    public final String understood(Contestant who, String said) {
        Map<String, String> words = new LinkedHashMap<>(optionWords(who));
        if (followable() && isPlaying(who.getUuid())) {
            words.putIfAbsent("FOLLOW", "\\b(follow you|with you|right behind you|lead the way|i'?ll follow|after you|follow me|come with me|"
                    + "stick with me|stay with me|this way)\\b");
            words.putIfAbsent("STAY", "\\b(stay here|wait here|stay put|i'?ll wait|keep watch here|stay there|wait there)\\b");
        }
        if (words.isEmpty() || said == null) {
            return null;
        }
        String line = said.toLowerCase(java.util.Locale.ROOT);
        String found = null;
        for (String clause : line.split("[.!?;]|\\bbut\\b")) {
            if (NEGATED.matcher(clause).find() || !DOING.matcher(clause).find()) {
                continue;
            }
            String here = mentioned(words, clause);
            if ("".equals(here)) {
                // both named ("the LIVE sign's a trap, I'm taking the DIE door"): it's the one after what they're doing
                java.util.regex.Matcher verb = DOING.matcher(clause);
                int at = 0;
                while (verb.find()) {
                    at = verb.start();
                }
                here = mentioned(words, clause.substring(at));
            }
            if (here == null) {
                continue;
            }
            if (here.isEmpty() || found != null && !found.equals(here)) {
                return null;
            }
            found = here;
        }
        // "Left door. I'm going." : one option named, something being done, and no "not" anywhere
        if (found == null && !NEGATED.matcher(line).find() && DOING.matcher(line).find()) {
            String here = mentioned(words, line);
            found = here == null || here.isEmpty() ? null : here;
        }
        return found;
    }

    /**
     * The one option {@code text} mentions; empty if it mentions more than one; null if none. An option whose tag ends in
     * "_N" takes a number: the last number its words matched goes on the end ("PICK_37").
     */
    @Nullable
    private static String mentioned(Map<String, String> words, String text) {
        String found = null;
        for (Map.Entry<String, String> option : words.entrySet()) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(option.getValue()).matcher(text);
            if (m.find()) {
                String key = option.getKey();
                if (key.endsWith("_N")) {
                    java.util.regex.Matcher digits = java.util.regex.Pattern.compile("\\d{1,4}").matcher(m.group());
                    String number = null;
                    while (digits.find()) {
                        number = digits.group();
                    }
                    if (number == null) {
                        continue;
                    }
                    key = key.substring(0, key.length() - 1) + number;
                }
                if (found != null) {
                    return "";
                }
                found = key;
            }
        }
        return found;
    }

    /** Whether {@code tag} is something {@code who} can do now: one of the options, or a numbered one ("PICK_37" for "PICK_N"). */
    public final boolean offers(Contestant who, String tag) {
        Map<String, String> options = allOptions(who);
        if (options.containsKey(tag)) {
            return true;
        }
        int cut = tag.lastIndexOf('_');
        return cut > 0 && tag.substring(cut + 1).matches("\\d{1,4}") && options.containsKey(tag.substring(0, cut) + "_N");
    }

    // --- running it ----------------------------------------------------------------------------------------

    /** Builds the venue without starting a game (so the city has it standing before anyone plays). */
    Builder prebuild() {
        planned = true;
        plan(builder);
        return builder;
    }

    void tick() {
        if (!planned) {
            planned = true;
            plan(builder);
        }
        phaseTicks++;
        switch (phase) {
            case BUILDING -> {
                if (builder.step(6000)) {
                    phase(Phase.OPEN);
                    recruit(Math.max(0, kind.seats - 1));
                }
            }
            case OPEN -> registration();
            case BRIEFING -> {
                builder.step(6000);
                watch();
                border();
                briefing();
                briefingTick(phaseTicks);
                if (phaseTicks >= BRIEF_TICKS) {
                    phase(Phase.RUNNING);
                    timeLeft = timeLimit();
                    begin();
                }
            }
            case RUNNING -> {
                builder.step(6000);
                watch();
                border();
                if (phase == Phase.RUNNING) {
                    timeLeft--;
                    running();
                    if (phase == Phase.RUNNING) {
                        carryOutOrders();
                    }
                }
                if (phase == Phase.RUNNING && timeLeft <= 0) {
                    timeUp();
                    finish();
                }
                if (phase == Phase.RUNNING && playing().isEmpty()) {
                    finish();
                }
                if (phase == Phase.RUNNING && playersPlaying().isEmpty()) {
                    offscreen();
                }
                updateBar();
            }
            case ENDED -> {
                if (phaseTicks > 20 * 40) {
                    closed = true;
                }
            }
        }
    }

    private void phase(Phase next) {
        phase = next;
        phaseTicks = 0;
    }

    private void registration() {
        if (phaseTicks % 10 == 0) {
            beacon();
        }
        for (ServerPlayerEntity player : world.getPlayers()) {
            if (player.isSpectator() || seats.containsKey(player.getUuid()) || !entry.contains(player.getPos())) {
                continue;
            }
            if (Games.gameOf(player) != null) {
                continue;
            }
            if (seats.size() >= kind.seats) {
                player.sendMessage(Text.literal("Entry is full.").formatted(Formatting.RED), true);
                continue;
            }
            register(player);
            if (instantEntry()) {
                closeEntry();
                return;
            }
        }
        for (UUID id : recruits) {
            Contestant contestant = contestant(id);
            if (contestant == null || seats.containsKey(id)) {
                continue;
            }
            // Strangers leave at least one place for a player
            if (entry.contains(contestant.getPos()) && seats.size() < kind.seats - 1) {
                seat(contestant);
            } else if (phaseTicks % 20 == 0) {
                contestant.go(entryPos, 1.0);
            }
        }
        for (UUID id : new ArrayList<>(players)) {
            ServerPlayerEntity player = player(id);
            if (player != null && isPlaying(id) && bounds != null && !bounds.contains(player.getPos())) {
                fail(id, "You registered, and then you tried to leave.");
            }
        }
        if (entryLeft < 0 && !players.isEmpty()) {
            entryLeft = ENTRY_TICKS;
            announce("Entry has begun. Entry closes in one minute.");
        }
        if (entryLeft > 0) {
            entryLeft--;
            if (entryLeft == 0 || seats.size() >= kind.seats) {
                closeEntry();
            }
        } else if (players.isEmpty() && phaseTicks > 20 * 60 * 8 && world.isDay()) {
            closed = true;      // nobody came: the lights go out
            release();
        }
    }

    private void register(ServerPlayerEntity player) {
        seats.put(player.getUuid(), Fate.PLAYING);
        players.add(player.getUuid());
        names.put(player.getUuid(), player.getEntityName());
        GameMode mode = player.interactionManager.getGameMode();
        if (mode == GameMode.SURVIVAL) {
            modes.put(player.getUuid(), mode);
            Visa.of(player).restore = mode.getName();
            player.changeGameMode(GameMode.ADVENTURE);
        }
        if (!player.getInventory().containsAny(Set.of(DeadMansHand.PHONE))) {
            player.getInventory().offerOrDrop(new ItemStack(DeadMansHand.PHONE));
        }
        player.sendMessage(text("Entry accepted. Take a phone. The game begins when entry closes.", Formatting.YELLOW), false);
        player.sendMessage(Text.literal("You're registered. Leave the venue now, or at any time until the game is over, and the laser finds you.")
                .formatted(Formatting.RED, Formatting.ITALIC), false);
        world.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.BLOCK_NOTE_BLOCK_CHIME, SoundCategory.BLOCKS, 1.0F, 1.2F);
    }

    protected void seat(Contestant contestant) {
        seats.put(contestant.getUuid(), Fate.PLAYING);
        names.put(contestant.getUuid(), contestant.name());
        contestant.gameId = id;
        contestant.stopAll();
    }

    /** Free strangers nearby come to play; new ones turn up for the rest. */
    private void recruit(int count) {
        for (Contestant contestant : world.getEntitiesByClass(Contestant.class, new Box(origin).expand(160.0),
                c -> c.isAlive() && c.free() && (c.role.isEmpty() || campGame()) && Games.gameOf(c) == null)) {
            if (recruits.size() >= count) {
                break;
            }
            recruits.add(contestant.getUuid());
        }
        while (recruits.size() < count) {
            BlockPos at = approach.add(random.nextInt(5) - 2, 0, random.nextInt(3) - 1);
            Contestant contestant = Contestant.spawn(world, at, random);
            if (contestant == null) {
                break;
            }
            recruits.add(contestant.getUuid());
        }
    }

    private void release() {
        for (UUID id : recruits) {
            Contestant contestant = contestant(id);
            if (contestant != null && !seats.containsKey(id)) {
                contestant.gameId = -1;
                contestant.stopAll();
            }
        }
    }

    /** Close entry now (or when the minute is up): fill the empty places and read the rules. */
    public boolean closeEntryNow() {
        if (phase != Phase.OPEN || players.isEmpty()) {
            return false;
        }
        closeEntry();
        return true;
    }

    private void closeEntry() {
        int missing = kind.seats - seats.size();
        for (UUID id : recruits) {
            if (missing <= 0) {
                break;
            }
            Contestant contestant = contestant(id);
            if (contestant != null && !seats.containsKey(id)) {
                if (!entry.contains(contestant.getPos())) {
                    contestant.refreshPositionAndAngles(entryPos.getX() + 0.5, entryPos.getY(), entryPos.getZ() + 0.5, contestant.getYaw(), 0.0F);
                }
                seat(contestant);
                missing--;
            }
        }
        while (fillSeats() && missing-- > 0) {
            Contestant contestant = Contestant.spawn(world, entryPos, random);
            if (contestant != null) {
                seat(contestant);
            }
        }
        release();
        phase(Phase.BRIEFING);
        bar = new ServerBossBar(Text.literal(kind.title), BossBar.Color.RED, BossBar.Style.PROGRESS);
        for (UUID id : players) {
            ServerPlayerEntity player = player(id);
            if (player != null) {
                bar.addPlayer(player);
            }
        }
        arrange();
    }

    private void briefing() {
        int t = phaseTicks;
        if (t == 1) {
            announce("Entry is closed. The game will now begin.");
            titleAll(Text.literal("GAME").formatted(Formatting.GOLD), Text.literal(kind.title).formatted(Formatting.WHITE));
            sound(SoundEvents.BLOCK_BELL_USE, 0.8F);
        } else if (t == 70) {
            if (kind.card.rank() == Card.JOKER) {
                titleAll(Text.literal("DIFFICULTY").formatted(Formatting.GOLD), Text.literal("JOKER").formatted(Formatting.LIGHT_PURPLE));
                announce("Game: " + kind.title + ". Difficulty: the Joker.");
            } else {
                titleAll(Text.literal("DIFFICULTY").formatted(Formatting.GOLD), Text.literal(kind.card.suit().symbol + "  " + kind.card.title().toUpperCase()
                        + "  " + kind.card.suit().symbol).formatted(kind.card.suit().red ? Formatting.RED : Formatting.WHITE));
                announce("Game: " + kind.title + ". Difficulty: " + kind.card.title() + " - " + kind.card.suit().kind + ".");
            }
        } else if (t >= 100 && (t - 100) % 30 == 0) {
            String[] rules = rules();
            int i = (t - 100) / 30;
            if (i < rules.length) {
                announce(rules[i]);
            } else if (i == rules.length) {
                announce("Good luck.");
            }
        }
    }

    /** Anyone playing who has died, vanished or logged off is out. */
    private void watch() {
        for (UUID id : playing()) {
            LivingEntity entity = entity(id);
            if (entity == null || !entity.isAlive()) {
                seats.put(id, Fate.DEAD);
                died(id);
            }
        }
    }

    private void border() {
        if (bounds == null) {
            return;
        }
        for (UUID id : playing()) {
            LivingEntity entity = entity(id);
            if (entity != null && !bounds.contains(entity.getPos())) {
                fail(id, "You left the venue during the game.");
            }
        }
    }

    /** No players left in it: the strangers' game plays out without you. */
    private void offscreen() {
        for (UUID id : playing()) {
            if (random.nextFloat() < 0.4F) {
                clear(id);
            } else {
                fail(id, null);
            }
        }
        finish();
    }

    private void updateBar() {
        if (bar == null || phaseTicks % 10 != 0) {
            return;
        }
        int limit = Math.max(1, timeLimit());
        bar.setPercent(Math.max(0.0F, Math.min(1.0F, timeLeft / (float) limit)));
        bar.setName(Text.literal(kind.title + "  -  " + Hud.clock(timeLeft)));
    }

    /** Over: the survivors get their days and their card. */
    protected void finish() {
        if (phase == Phase.ENDED) {
            return;
        }
        for (UUID id : playing()) {
            fail(id, "The game is over.");
        }
        phase(Phase.ENDED);
        for (UUID id : players) {
            ServerPlayerEntity player = player(id);
            if (player != null) {
                giveBack(player);
            }
        }
        for (UUID id : seats.keySet()) {
            Contestant contestant = contestant(id);
            if (contestant != null) {
                contestant.gameId = -1;
                contestant.stopAll();
            }
        }
        if (bar != null) {
            bar.clearPlayers();
        }
        int cleared = (int) seats.values().stream().filter(f -> f == Fate.CLEARED).count();
        announce(cleared == 0 ? "The game is over. There are no survivors." : "The game is over. Survivors: " + cleared + ".");
        ended();
    }

    /** The server is stopping or the game is being thrown away. */
    void abandon() {
        for (UUID id : players) {
            ServerPlayerEntity player = player(id);
            if (player != null) {
                giveBack(player);
            }
        }
        if (bar != null) {
            bar.clearPlayers();
        }
        ended();
    }

    private void giveBack(ServerPlayerEntity player) {
        GameMode mode = modes.remove(player.getUuid());
        if (mode != null && player.interactionManager.getGameMode() == GameMode.ADVENTURE) {
            player.changeGameMode(mode);
        }
        Visa.of(player).restore = "";
        if (bar != null) {
            bar.removePlayer(player);
        }
    }

    /** {@code id} has cleared the game. */
    protected void clear(UUID id) {
        if (seats.get(id) != Fate.PLAYING) {
            return;
        }
        seats.put(id, Fate.CLEARED);
        // someone made it: that lifts everyone still in it a little, and whoever it was a lot
        for (UUID other : seats.keySet()) {
            Contestant c = contestant(other);
            if (c != null) {
                c.feelings.lifted(other.equals(id) ? 0.4F : 0.08F);
            }
        }
        ServerPlayerEntity player = player(id);
        if (player != null) {
            Visa.Data data = Visa.of(player);
            data.visa += kind.card.days() * Visa.DAY;
            data.cards.add(kind.card.code());
            data.cleared++;
            player.getInventory().offerOrDrop(kind.card.stack());
            giveBack(player);
            Borderland.title(player, Text.literal("GAME CLEAR").formatted(Formatting.GREEN),
                    Text.literal("+" + kind.card.days() + (kind.card.days() == 1 ? " day" : " days") + " on your visa").formatted(Formatting.WHITE));
            world.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 1.0F, 1.0F);
        }
        Contestant contestant = contestant(id);
        if (contestant != null) {
            contestant.visa += kind.card.days() * Visa.DAY;
            contestant.cleared++;
        }
    }

    /** {@code id} loses: the laser. */
    protected void fail(UUID id, @Nullable String why) {
        if (seats.get(id) != Fate.PLAYING) {
            return;
        }
        seats.put(id, Fate.DEAD);
        LivingEntity entity = entity(id);
        if (entity != null && entity.isAlive()) {
            Laser.strike(entity, why);
        }
        died(id);
    }

    // --- choices every walking-about game shares: go with someone, stay put, or go your own way ------------------

    /** A name as part of a tag: "Riku" becomes "RIKU". */
    protected static String tagOf(String name) {
        return name.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
    }

    /** What strangers have said they'll do, for games that only need to remember it: the tag, by stranger. */
    protected final Map<UUID, String> told = new HashMap<>();
    private final Map<UUID, UUID> leader = new HashMap<>();
    private final Map<UUID, Long> orderEnds = new HashMap<>();
    private final Map<UUID, Vec3d> stayAt = new HashMap<>();

    /** Whether strangers here can be asked to come with someone, or to stay where they are: games where people walk about freely. */
    protected boolean followable() {
        return false;
    }

    /** Everything {@code who} could decide to do now: this game's own options, and, where it makes sense, following and staying. */
    public final Map<String, String> allOptions(Contestant who) {
        Map<String, String> all = new LinkedHashMap<>(options(who));
        UUID id = who.getUuid();
        if (followable() && isPlaying(id)) {
            UUID asker = who.lastTalkedTo(world.getTime());
            if (asker != null && isPlaying(asker) && !asker.equals(id)) {
                all.putIfAbsent("FOLLOW", "go with " + nameOf(asker) + " and stay close to them for the next minute, instead of your own plan");
            }
            all.putIfAbsent("STAY", "stay right where you are for the next half minute");
            if (leader.containsKey(id) || stayAt.containsKey(id)) {
                all.putIfAbsent("OWN_WAY", "stop following or waiting and go back to doing what you think is best");
            }
        }
        return all;
    }

    /** {@code who} has committed to {@code option}: the shared ones are handled here, the rest by the game. */
    public final void choose(Contestant who, String option) {
        UUID id = who.getUuid();
        long now = world.getTime();
        switch (option) {
            case "FOLLOW" -> {
                UUID asker = who.lastTalkedTo(now);
                if (asker != null) {
                    leader.put(id, asker);
                    stayAt.remove(id);
                    orderEnds.put(id, now + 20 * 60);
                    who.trust(asker, 0.05F);
                }
            }
            case "STAY" -> {
                stayAt.put(id, who.getPos());
                leader.remove(id);
                orderEnds.put(id, now + 20 * 30);
            }
            case "OWN_WAY" -> {
                leader.remove(id);
                stayAt.remove(id);
                orderEnds.remove(id);
            }
            default -> {
                told.put(id, option);
                chose(who, option);
            }
        }
    }

    /** After the game's own orders each tick: anyone who's said they'll follow or stay does that instead. */
    private void carryOutOrders() {
        if (leader.isEmpty() && stayAt.isEmpty()) {
            return;
        }
        long now = world.getTime();
        for (UUID id : new ArrayList<>(orderEnds.keySet())) {
            Contestant c = contestant(id);
            UUID lead = leader.get(id);
            LivingEntity guide = lead == null ? null : entity(lead);
            boolean over = c == null || !isPlaying(id) || now > orderEnds.get(id) || lead != null && (guide == null || !isPlaying(lead));
            if (over) {
                if (c != null && lead != null && isPlaying(id) && now > orderEnds.get(id)) {
                    c.say(Lines.pick(random, "I'll find my own way from here.", "Alright, I'm going on alone.", "Thanks. I've got it from here."));
                }
                leader.remove(id);
                stayAt.remove(id);
                orderEnds.remove(id);
                continue;
            }
            if (c.held()) {
                continue;       // the game has them standing still (shocked, seated, waiting their turn): that comes first
            }
            if (guide != null) {
                if (c.squaredDistanceTo(guide) > 2.5 * 2.5) {
                    c.go(guide.getPos(), 1.25);
                } else {
                    c.go(c.getPos(), 1.0);
                    c.look(guide);
                }
            } else if (stayAt.containsKey(id)) {
                c.go(stayAt.get(id), 1.0);
            }
        }
    }

    /** {@code id} loses, killed by the game itself ({@code how}: a shock, a blast, gas) rather than by the laser. */
    protected void kill(UUID id, net.minecraft.entity.damage.DamageSource how, @Nullable String why) {
        if (seats.get(id) != Fate.PLAYING) {
            return;
        }
        seats.put(id, Fate.DEAD);
        LivingEntity entity = entity(id);
        if (entity instanceof ServerPlayerEntity player && (player.isCreative() || player.isSpectator())) {
            tell(player, (why == null ? "" : why + " ") + "(You'd be dead, but you're in creative.)", Formatting.RED);
        } else if (entity != null && entity.isAlive()) {
            if (why != null) {
                tell(entity, why, Formatting.RED);
            }
            entity.damage(how, 1000.0F);
        }
        died(id);
    }

    // --- helpers ---------------------------------------------------------------------------------------------

    public boolean has(UUID id) {
        return seats.containsKey(id);
    }

    public boolean isPlaying(UUID id) {
        return seats.get(id) == Fate.PLAYING;
    }

    public List<UUID> playing() {
        List<UUID> list = new ArrayList<>();
        for (Map.Entry<UUID, Fate> entry : seats.entrySet()) {
            if (entry.getValue() == Fate.PLAYING) {
                list.add(entry.getKey());
            }
        }
        return list;
    }

    private List<UUID> playersPlaying() {
        List<UUID> list = new ArrayList<>();
        for (UUID id : players) {
            if (seats.get(id) == Fate.PLAYING) {
                list.add(id);
            }
        }
        return list;
    }

    /** Everyone still playing who is here. */
    protected List<LivingEntity> living() {
        List<LivingEntity> list = new ArrayList<>();
        for (UUID id : playing()) {
            LivingEntity entity = entity(id);
            if (entity != null) {
                list.add(entity);
            }
        }
        return list;
    }

    protected List<Contestant> strangers() {
        List<Contestant> list = new ArrayList<>();
        for (UUID id : playing()) {
            Contestant contestant = contestant(id);
            if (contestant != null) {
                list.add(contestant);
            }
        }
        return list;
    }

    @Nullable
    protected LivingEntity entity(UUID id) {
        ServerPlayerEntity player = world.getServer().getPlayerManager().getPlayer(id);
        if (player != null) {
            return player.world == world ? player : null;
        }
        Entity entity = world.getEntity(id);
        return entity instanceof LivingEntity living ? living : null;
    }

    @Nullable
    protected ServerPlayerEntity player(UUID id) {
        ServerPlayerEntity player = world.getServer().getPlayerManager().getPlayer(id);
        return player != null && player.world == world ? player : null;
    }

    @Nullable
    protected Contestant contestant(UUID id) {
        Entity entity = world.getEntity(id);
        return entity instanceof Contestant contestant && contestant.isAlive() ? contestant : null;
    }

    protected String nameOf(UUID id) {
        return names.getOrDefault(id, "someone");
    }

    /** Everyone who should hear the announcer: the players in it, and anyone close. */
    protected List<ServerPlayerEntity> audience() {
        List<ServerPlayerEntity> list = new ArrayList<>();
        Box near = (bounds != null ? bounds : new Box(origin)).expand(32.0);
        for (ServerPlayerEntity player : world.getPlayers()) {
            if (players.contains(player.getUuid()) || near.contains(player.getPos())) {
                list.add(player);
            }
        }
        return list;
    }

    /** The announcer: a bright, calm voice from every speaker. */
    public void announce(String message) {
        MutableText line = Text.literal("[" + kind.card.suit().symbol + "] ").formatted(kind.card.suit().red ? Formatting.RED : Formatting.GRAY)
                .append(Text.literal(message).formatted(Formatting.GOLD));
        for (ServerPlayerEntity player : audience()) {
            player.sendMessage(line, false);
        }
    }

    protected void titleAll(Text title, Text subtitle) {
        for (UUID id : players) {
            ServerPlayerEntity player = player(id);
            if (player != null) {
                Borderland.title(player, title, subtitle);
            }
        }
    }

    protected void tell(LivingEntity who, String message, Formatting color) {
        if (who instanceof ServerPlayerEntity player) {
            player.sendMessage(Text.literal(message).formatted(color), false);
        }
    }

    protected void sound(net.minecraft.sound.SoundEvent sound, float pitch) {
        Vec3d at = Vec3d.ofCenter(entryPos != null ? entryPos : origin);
        for (ServerPlayerEntity player : audience()) {
            player.playSound(sound, SoundCategory.MASTER, 1.0F, pitch);
        }
        world.playSound(null, at.x, at.y, at.z, sound, SoundCategory.BLOCKS, 0.5F, pitch);
    }

    protected static MutableText text(String message, Formatting color) {
        return Text.literal(message).formatted(color);
    }

    /** The venue's light, a pale column over the entrance you can see from far off. */
    private void beacon() {
        double x = entryPos.getX() + 0.5;
        double z = entryPos.getZ() + 0.5;
        for (ServerPlayerEntity player : world.getPlayers()) {
            double distance = player.squaredDistanceTo(x, entryPos.getY(), z);
            if (distance > 320.0 * 320.0 || distance < 6.0 * 6.0) {
                continue;
            }
            for (int h = 0; h < 48; h += 2) {
                world.spawnParticles(player, ParticleTypes.END_ROD, true, x, entryPos.getY() + 3 + h, z, 1, 0.05, 0.4, 0.05, 0.0);
            }
        }
    }

    // --- what the manager asks ---------------------------------------------------------------------------------

    public void hud(ServerPlayerEntity player, Hud hud) {
        boolean secret = phase == Phase.BUILDING || phase == Phase.OPEN;
        hud.line(secret ? "§6??? §fGAME: unknown until entry closes" : kind.card.shortName() + " §f" + kind.title);
        switch (phase) {
            case OPEN -> hud.line(entryLeft > 0 ? "§eENTRY CLOSES " + Hud.clock(entryLeft) : "§7Entry is open");
            case BRIEFING -> hud.line("§eLISTEN TO THE RULES");
            case RUNNING -> hud.line("§fTIME " + Hud.clock(timeLeft));
            case ENDED -> hud.line("§7GAME OVER");
            default -> {
            }
        }
        if (seats.get(player.getUuid()) == Fate.CLEARED) {
            hud.line("§aGAME CLEAR");
        } else if (phase == Phase.BRIEFING || phase == Phase.RUNNING) {
            more(player, hud);
        }
    }

    /** The phone: the rules again. */
    public void phone(ServerPlayerEntity player) {
        if (phase == Phase.BUILDING || phase == Phase.OPEN) {
            player.sendMessage(Text.literal("GAME: ???   Nobody knows what it is until entry closes. Then the rules are read out.")
                    .formatted(Formatting.GRAY), false);
            return;
        }
        player.sendMessage(Text.literal("GAME: " + kind.title + "   DIFFICULTY: " + kind.card.title().toUpperCase())
                .formatted(kind.card.suit().red ? Formatting.RED : Formatting.WHITE), false);
        for (String rule : rules()) {
            player.sendMessage(Text.literal("  " + rule).formatted(Formatting.GOLD), false);
        }
        if (phase == Phase.RUNNING) {
            player.sendMessage(Text.literal("  Time left: " + Hud.clock(timeLeft)).formatted(Formatting.GRAY), false);
        }
    }

    public boolean inside(BlockPos pos) {
        return bounds != null && bounds.contains(Vec3d.ofCenter(pos));
    }

    public boolean near(Entity entity, double margin) {
        return bounds != null && bounds.expand(margin).contains(entity.getPos());
    }

    public BlockPos entrance() {
        return entryPos != null ? entryPos : origin;
    }

    public Box bounds() {
        return bounds;
    }

    ActionResult useBlock(ServerPlayerEntity player, BlockPos pos, BlockState state) {
        if (phase == Phase.BRIEFING || phase == Phase.RUNNING) {
            return use(player, pos, state);
        }
        return ActionResult.PASS;
    }

    void hear(ServerPlayerEntity player, String message) {
        if (phase == Phase.RUNNING && seats.containsKey(player.getUuid())) {
            heard(player, message);
        }
    }

    boolean use(ServerPlayerEntity player, ItemStack stack) {
        return (phase == Phase.BRIEFING || phase == Phase.RUNNING) && isPlaying(player.getUuid()) && usedItem(player, stack);
    }

    boolean attack(ServerPlayerEntity player, Entity target) {
        return (phase == Phase.BRIEFING || phase == Phase.RUNNING) && isPlaying(player.getUuid()) && attacked(player, target);
    }

    /** Clickable choices in chat: each runs /answer with its word. */
    protected static void choices(ServerPlayerEntity player, String prompt, String... options) {
        MutableText line = Text.literal(prompt + " ").formatted(Formatting.GOLD);
        for (String option : options) {
            String[] parts = option.split("\\|", 2);
            String label = parts[0];
            String answer = parts.length > 1 ? parts[1] : parts[0];
            line.append(Text.literal("[" + label + "]").styled(style -> style.withColor(Formatting.AQUA)
                    .withClickEvent(new net.minecraft.text.ClickEvent(net.minecraft.text.ClickEvent.Action.RUN_COMMAND, "/answer " + answer))
                    .withHoverEvent(new net.minecraft.text.HoverEvent(net.minecraft.text.HoverEvent.Action.SHOW_TEXT, Text.literal("/answer " + answer)))));
            line.append(Text.literal(" "));
        }
        player.sendMessage(line, false);
    }

    /** One of this place's citizens: a face card's owner, playing their own game. */
    protected Contestant citizen(String name, BlockPos at) {
        Contestant c = Contestant.spawn(world, at, random, "citizen", null);
        if (c != null) {
            c.setCustomName(Text.literal(name));
            c.wits = 0.95F;
            seat(c);
        }
        return c;
    }

    boolean touch(ServerPlayerEntity player, Entity target) {
        return phase == Phase.RUNNING && isPlaying(player.getUuid()) && touched(player, target);
    }

    void answer(ServerPlayerEntity player, String answer) {
        if (isPlaying(player.getUuid()) && (phase == Phase.BRIEFING || phase == Phase.RUNNING)) {
            answered(player, answer);
        }
    }

    /** Puts {@code who} back to normal after anything a game did to them (floating, effects). */
    protected static void unfloat(LivingEntity who) {
        if (who.hasNoGravity()) {
            who.setNoGravity(false);
        }
    }
}
