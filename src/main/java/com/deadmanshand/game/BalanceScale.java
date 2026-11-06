package com.deadmanshand.game;

import com.deadmanshand.Builder;
import com.deadmanshand.Hud;
import com.deadmanshand.Laser;
import com.deadmanshand.npc.Contestant;
import com.deadmanshand.npc.Lines;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.LivingEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Formatting;
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
 * King of Diamonds, in a hall like a supreme court. Five stand at five scales. Every round each chooses a number from
 * 0 to 100; the average times 0.8 is the target, and whoever is closest wins the round. Everyone else loses a point.
 * At minus ten the scale tips, and what's in the vat above you comes down. Every time someone goes, a new rule is
 * added. The last one standing clears it.
 */
final class BalanceScale extends Game {
    private static final int CHOOSE = 20 * 40;
    private static final int SHOW = 20 * 10;
    private static final int[] PODIUM_X = {-12, -6, 0, 6, 12};

    private final List<UUID> order = new ArrayList<>();
    private final Map<UUID, Integer> score = new HashMap<>();
    private final Map<UUID, Integer> chosen = new HashMap<>();
    private final Map<UUID, BlockPos> podium = new HashMap<>();
    private int rules;
    private int round;
    private int phaseLeft;
    private boolean choosing;
    private double lastAverage = 50.0;
    private String lastResult = "";
    private UUID king;
    private int oy;
    private int pz;

    BalanceScale(Kind kind, ServerWorld world, BlockPos origin) {
        super(kind, world, origin);
    }

    @Override
    protected void plan(Builder b) {
        int ox = origin.getX();
        oy = origin.getY();
        int oz = origin.getZ();
        pz = oz - 22;
        bounds = new Box(ox - 17, oy - 2, oz - 37, ox + 18, oy + 14, oz + 1);
        entry = new Box(ox - 3, oy, oz - 7, ox + 4, oy + 3, oz - 2);
        entryPos = new BlockPos(ox, oy, oz - 5);
        approach = new BlockPos(ox, oy, oz + 6);
        BlockState marble = Blocks.SMOOTH_QUARTZ.getDefaultState();
        BlockState dark = Blocks.POLISHED_BLACKSTONE.getDefaultState();
        BlockState air = Blocks.AIR.getDefaultState();
        b.fill(ox - 16, oy - 1, oz - 36, ox + 16, oy + 12, oz - 2, marble);
        b.fill(ox - 15, oy, oz - 35, ox + 15, oy + 11, oz - 3, air);
        b.fill(ox - 15, oy - 1, oz - 35, ox + 15, oy - 1, oz - 3, Blocks.POLISHED_DIORITE.getDefaultState());
        for (int x = ox - 15; x <= ox + 15; x += 5) {
            b.fill(x, oy, oz - 35, x, oy + 11, oz - 35, Blocks.QUARTZ_PILLAR.getDefaultState());
        }
        // The five scales: a stand for each of you, and a vat above each stand
        for (int k = 0; k < 5; k++) {
            int x = ox + PODIUM_X[k];
            b.fill(x - 1, oy, pz - 1, x + 1, oy, pz + 1, dark);
            b.fill(x - 2, oy + 1, pz - 2, x + 2, oy + 1, pz - 2, Blocks.GLASS_PANE.getDefaultState());
            b.fill(x - 1, oy + 7, pz - 1, x + 1, oy + 7, pz + 1, Blocks.GOLD_BLOCK.getDefaultState());
            b.fill(x - 1, oy + 8, pz - 1, x + 1, oy + 9, pz + 1, Blocks.LIME_STAINED_GLASS.getDefaultState());
            b.fill(x, oy + 10, pz, x, oy + 11, pz, Blocks.CHAIN.getDefaultState());
            b.set(x, oy + 1, pz + 2, Blocks.LIGHT_WEIGHTED_PRESSURE_PLATE.getDefaultState());
            b.sign(new BlockPos(x, oy + 4, oz - 34), Direction.SOUTH, "", "0", "", "");
        }
        b.fill(ox - 15, oy + 3, oz - 35, ox + 15, oy + 5, oz - 35, Blocks.BLACK_CONCRETE.getDefaultState());
        b.sign(new BlockPos(ox, oy + 7, oz - 34), Direction.SOUTH, "TARGET", "", "", "");
        b.set(ox, oy + 7, oz - 35, Blocks.BLACK_CONCRETE.getDefaultState());
        for (int x = ox - 12; x <= ox + 12; x += 6) {
            b.set(x, oy + 11, oz - 12, Blocks.SEA_LANTERN.getDefaultState());
            b.set(x, oy + 11, oz - 28, Blocks.SEA_LANTERN.getDefaultState());
        }
        b.fill(ox - 1, oy, oz - 2, ox + 1, oy + 2, oz - 2, air);
        b.sign(new BlockPos(ox, oy + 3, oz - 1), Direction.SOUTH, "GAME VENUE", "", "step inside", "to register");
    }

    @Override
    protected String[] rules() {
        List<String> list = new ArrayList<>(List.of(
                "Every round, choose a number from 0 to 100 (/answer and the number).",
                "The average of everyone's numbers, times 0.8, is the target. Whoever is closest wins the round.",
                "Everyone else loses a point. At minus ten, your scale tips.",
                "Each time someone is eliminated, a new rule is added. The last one standing clears the game."));
        if (rules >= 1) {
            list.add("Added: if two or more choose the same number, it's invalid and they each lose a point.");
        }
        if (rules >= 2) {
            list.add("Added: if someone chooses exactly the target, everyone else loses two points.");
        }
        if (rules >= 3) {
            list.add("Added: if someone chooses 0, whoever chooses 100 wins the round.");
        }
        return list.toArray(new String[0]);
    }

    @Override
    protected int timeLimit() {
        return 20 * 60 * 40;
    }

    @Override
    protected void arrange() {
        Contestant k = citizen("Asuka", entryPos);
        if (k != null) {
            king = k.getUuid();
        }
        int i = 0;
        for (UUID id : playing()) {
            LivingEntity entity = entity(id);
            if (entity == null || i >= 5) {
                continue;
            }
            BlockPos at = new BlockPos(origin.getX() + PODIUM_X[i], oy + 1, pz);
            podium.put(id, at);
            order.add(id);
            score.put(id, 0);
            entity.teleport(at.getX() + 0.5, oy + 1, at.getZ() + 0.5);
            if (entity instanceof Contestant c) {
                c.hold();
            }
            i++;
        }
        // anyone past the fifth scale has nowhere to stand
        for (UUID id : playing()) {
            if (!podium.containsKey(id)) {
                fail(id, "There are only five scales.");
            }
        }
    }

    @Override
    protected void begin() {
        startRound();
    }

    private void startRound() {
        round++;
        choosing = true;
        phaseLeft = CHOOSE;
        chosen.clear();
        announce("Round " + round + ". Choose your number.");
        for (UUID id : players) {
            ServerPlayerEntity player = player(id);
            if (player != null && isPlaying(id)) {
                tell(player, "Type /answer and a number from 0 to 100. You have " + CHOOSE / 20 + " seconds.", Formatting.YELLOW);
            }
        }
    }

    @Override
    protected void running() {
        // nobody leaves their scale
        for (UUID id : playing()) {
            LivingEntity entity = entity(id);
            BlockPos at = podium.get(id);
            if (entity != null && at != null && entity.squaredDistanceTo(Vec3d.ofBottomCenter(at)) > 2.5 * 2.5) {
                fail(id, "You left your scale.");
            }
        }
        phaseLeft--;
        if (choosing) {
            strangersChoose();
            if (phaseLeft <= 0) {
                reveal();
            }
        } else if (phaseLeft <= 0) {
            if (playing().size() <= 1) {
                for (UUID id : playing()) {
                    clear(id);
                }
                finish();
            } else {
                startRound();
            }
        }
    }

    private void strangersChoose() {
        for (UUID id : playing()) {
            if (chosen.containsKey(id) || !(entity(id) instanceof Contestant c)) {
                continue;
            }
            if (phaseLeft > CHOOSE - 20 * (4 + Math.abs(id.hashCode() % 20))) {
                continue;
            }
            chosen.put(id, think(c));
        }
    }

    /** How a stranger picks: guessing how deep the others think, a little noise, and the added rules. */
    private int think(Contestant c) {
        int alive = playing().size();
        if (alive == 2 && rules >= 3) {
            float r = random.nextFloat();
            return r < 0.4F ? 0 : r < 0.7F ? 1 : 100;
        }
        if (alive == 2) {
            return random.nextFloat() < 0.8F ? 0 : 1;
        }
        int depth = Math.round(c.judgment() * 4.0F) + (c.getUuid().equals(king) ? 1 : 0);
        double guess = (round == 1 ? 50.0 : lastAverage) * Math.pow(0.8, depth);
        double noise = (random.nextDouble() - 0.5) * (c.getUuid().equals(king) ? 4.0 : 14.0) * (1.2 - c.judgment());
        int pick = (int) Math.round(Math.max(0.0, Math.min(100.0, guess + noise)));
        if (rules >= 1 && random.nextFloat() < 0.5F) {
            pick = Math.max(0, Math.min(100, pick + random.nextInt(5) - 2));
        }
        return pick;
    }

    private void reveal() {
        choosing = false;
        phaseLeft = SHOW;
        List<UUID> alive = playing();
        Map<Integer, Integer> counts = new HashMap<>();
        double sum = 0;
        int n = 0;
        for (UUID id : alive) {
            Integer c = chosen.get(id);
            if (c != null) {
                counts.merge(c, 1, Integer::sum);
                sum += c;
                n++;
            }
        }
        double target = n == 0 ? 0 : sum / n * 0.8;
        lastAverage = n == 0 ? lastAverage : sum / n;
        // who can win: anyone who chose, minus duplicates once that rule is in
        List<UUID> valid = new ArrayList<>();
        for (UUID id : alive) {
            Integer c = chosen.get(id);
            if (c != null && !(rules >= 1 && counts.get(c) > 1)) {
                valid.add(id);
            }
        }
        List<UUID> winners = new ArrayList<>();
        boolean zeroHundred = rules >= 3 && alive.size() == 2 && chosen.containsValue(0) && chosen.containsValue(100);
        if (zeroHundred) {
            for (UUID id : valid) {
                if (chosen.get(id) == 100) {
                    winners.add(id);
                }
            }
        } else {
            double best = Double.MAX_VALUE;
            for (UUID id : valid) {
                double d = Math.abs(chosen.get(id) - target);
                if (d < best - 1e-9) {
                    best = d;
                    winners.clear();
                    winners.add(id);
                } else if (Math.abs(d - best) < 1e-9) {
                    winners.add(id);
                }
            }
        }
        boolean exact = false;
        for (UUID id : winners) {
            exact |= rules >= 2 && Math.abs(chosen.get(id) - target) < 0.5;
        }
        StringBuilder text = new StringBuilder();
        for (UUID id : alive) {
            Integer c = chosen.get(id);
            text.append(text.length() > 0 ? ", " : "").append(nameOf(id)).append(" ").append(c == null ? "-" : c);
        }
        announce("Numbers: " + text + ".");
        announce(String.format("Average %.2f, times 0.8: %.2f.%s", lastAverage, target, zeroHundred ? " Someone chose 0, so 100 wins." : ""));
        StringBuilder names = new StringBuilder();
        for (UUID id : winners) {
            names.append(names.length() > 0 ? " and " : "").append(nameOf(id));
        }
        announce(winners.isEmpty() ? "Nobody wins this round." : "This round goes to " + names + "." + (exact ? " Exactly right: everyone else loses two." : ""));
        lastResult = String.format("%.2f", target);
        sound(SoundEvents.BLOCK_ANVIL_LAND, 1.4F);
        int lost = 0;
        for (UUID id : alive) {
            if (winners.contains(id)) {
                continue;
            }
            score.merge(id, exact ? -2 : -1, Integer::sum);
        }
        updateBoard();
        for (UUID id : alive) {
            if (score.getOrDefault(id, 0) <= -10) {
                acid(id);
                lost++;
            }
        }
        if (lost > 0 && playing().size() > 1) {
            rules = Math.min(3, rules + lost);
            String[] all = rules();
            announce("A new rule. " + all[all.length - 1].replace("Added: ", ""));
        }
        if (!isPlaying(king) && king != null) {
            for (UUID id : alive) {
                if (entity(id) instanceof Contestant c && isPlaying(id) && random.nextFloat() < 0.3F) {
                    c.say(Lines.pick(random, "The King's gone...", "It's just us now.", "Don't think. Just choose."));
                    break;
                }
            }
        }
    }

    private void updateBoard() {
        for (int k = 0; k < order.size(); k++) {
            UUID id = order.get(k);
            BlockPos sign = new BlockPos(origin.getX() + PODIUM_X[k], oy + 4, origin.getZ() - 34);
            if (world.getBlockEntity(sign) != null) {
                Builder.write(world, sign, nameOf(id), String.valueOf(score.getOrDefault(id, 0)), isPlaying(id) ? "" : "OUT", "");
            }
        }
        BlockPos target = new BlockPos(origin.getX(), oy + 7, origin.getZ() - 34);
        if (world.getBlockEntity(target) != null) {
            Builder.write(world, target, "TARGET", lastResult, "round " + round, "");
        }
    }

    /** The scale tips. */
    private void acid(UUID id) {
        LivingEntity entity = entity(id);
        BlockPos at = podium.get(id);
        if (at != null) {
            world.spawnParticles(ParticleTypes.ITEM_SLIME, at.getX() + 0.5, at.getY() + 5.0, at.getZ() + 0.5, 120, 0.6, 2.0, 0.6, 0.05);
            world.spawnParticles(ParticleTypes.LARGE_SMOKE, at.getX() + 0.5, at.getY() + 1.0, at.getZ() + 0.5, 30, 0.5, 0.6, 0.5, 0.02);
            world.playSound(null, at.getX(), at.getY(), at.getZ(), SoundEvents.BLOCK_FIRE_EXTINGUISH, SoundCategory.BLOCKS, 2.0F, 0.5F);
        }
        announce(nameOf(id) + " reached minus ten. The scale tips.");
        seats.put(id, Fate.DEAD);
        if (entity != null && entity.isAlive()) {
            tell(entity, "Your scale tipped.", Formatting.RED);
            entity.damage(Laser.ACID, 1000.0F);
        }
        died(id);
    }

    @Override
    protected void answered(ServerPlayerEntity player, String text) {
        if (!choosing) {
            tell(player, "Wait for the next round.", Formatting.GRAY);
            return;
        }
        try {
            int n = Integer.parseInt(text.trim());
            if (n < 0 || n > 100) {
                throw new NumberFormatException();
            }
            chosen.put(player.getUuid(), n);
            tell(player, "You've chosen " + n + ". You can change it until the time is up.", Formatting.YELLOW);
        } catch (NumberFormatException e) {
            tell(player, "A whole number from 0 to 100.", Formatting.RED);
        }
    }

    @Override
    protected void more(ServerPlayerEntity player, Hud hud) {
        hud.line("§fROUND §e" + round + "  " + (choosing ? "§aCHOOSE " : "§7results ") + Hud.clock(phaseLeft));
        hud.line("§fYOUR POINTS §e" + score.getOrDefault(player.getUuid(), 0) + "§7  (out at -10)");
        Integer c = chosen.get(player.getUuid());
        hud.line("§fYOUR NUMBER " + (c == null ? (choosing ? "§cnone yet: /answer 0-100" : "§7-") : "§e" + c));
        StringBuilder all = new StringBuilder("§7");
        for (UUID id : order) {
            if (!id.equals(player.getUuid())) {
                all.append(nameOf(id)).append(isPlaying(id) ? " " + score.getOrDefault(id, 0) : " out").append("  ");
            }
        }
        hud.line(all.toString().trim());
    }

    // --- what a stranger can say they'll do, and then does ---------------------------------------------------

    @Override
    public Map<String, String> options(Contestant who) {
        if (!choosing || !isPlaying(who.getUuid())) {
            return Map.of();
        }
        return Map.of("PICK_N", "choose your number N, from 0 to 100, for this round: for example [PICK 37]. Only do this when you mean it");
    }

    @Override
    protected Map<String, String> optionWords(Contestant who) {
        return choosing ? Map.of("PICK_N", "\\b(go with|going with|pick|picking|choose|choosing|my number is|i'?ll say|i'?ll put|answer)\\s+(\\d{1,3})\\b")
                : Map.of();
    }

    @Override
    public void chose(Contestant who, String option) {
        if (!choosing || !option.startsWith("PICK_")) {
            return;
        }
        try {
            int n = Integer.parseInt(option.substring(5));
            if (n >= 0 && n <= 100) {
                chosen.put(who.getUuid(), n);
            }
        } catch (NumberFormatException ignored) {
        }
    }

    @Override
    public String situation(Contestant who) {
        return "You're playing BALANCE SCALE, the king of diamonds, standing at one of five scales in a courtroom-like hall. Each round "
                + "everyone picks a number 0-100; the average times 0.8 is the target; closest wins and everyone else loses a point; at -10 "
                + "acid pours on you. Your points: " + score.getOrDefault(who.getUuid(), 0) + ". Round " + round + "."
                + (who.getUuid().equals(king) ? " You are the King of Diamonds, a citizen of this place, and you play to win." : "");
    }
}
