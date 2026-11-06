package com.deadmanshand.game;

import com.deadmanshand.Builder;
import com.deadmanshand.Hud;
import com.deadmanshand.npc.Contestant;
import com.deadmanshand.npc.Lines;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.LivingEntity;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Two of Spades: an unfinished tower with an open lift shaft, and a lift with nothing to stand on, only a bar
 * underneath. Hang on to it all the way to the roof. It stops now and then, for as long as it likes. Let go and you
 * fall the height of the building. Hold SNEAK to keep your grip; your grip wears out, slower if you've eaten.
 */
final class HumanElevator extends Game {
    private static final int FLOORS = 16;
    private static final int PIT = 30;
    private static final int STEP_TICKS = 8;

    private int cx;
    private int cz;
    private int oy;
    private int carY;
    private int topCarY;
    private int stepIn;
    private int stoppedFor;
    private final List<int[]> stops = new ArrayList<>();
    private final Map<UUID, Integer> spot = new HashMap<>();
    private final Map<UUID, Float> grip = new HashMap<>();
    private final Map<UUID, Float> drain = new HashMap<>();
    private final Map<UUID, Integer> letGo = new HashMap<>();
    /** When each stranger can next dig in for a second wind. */
    private final Map<UUID, Integer> nextDigIn = new HashMap<>();
    private final List<UUID> falling = new ArrayList<>();
    private int ticks;

    HumanElevator(Kind kind, ServerWorld world, BlockPos origin) {
        super(kind, world, origin);
    }

    private int top() {
        return oy + 4 * FLOORS;
    }

    @Override
    protected void plan(Builder b) {
        int ox = origin.getX();
        oy = origin.getY();
        int oz = origin.getZ();
        cx = ox;
        cz = oz - 16;
        carY = oy + 3;
        topCarY = top() - 1;
        bounds = new Box(ox - 11, oy - PIT - 2, oz - 27, ox + 12, top() + 8, oz + 3);
        entry = new Box(ox - 3, oy, oz - 11, ox + 4, oy + 3, oz - 6);
        entryPos = new BlockPos(ox, oy, oz - 8);
        approach = new BlockPos(ox, oy, oz + 2);

        BlockState air = Blocks.AIR.getDefaultState();
        BlockState pillar = Blocks.GRAY_CONCRETE.getDefaultState();
        BlockState slab = Blocks.SMOOTH_STONE.getDefaultState();
        BlockState glass = Blocks.LIGHT_GRAY_STAINED_GLASS.getDefaultState();
        int x0 = ox - 10;
        int x1 = ox + 10;
        int z0 = oz - 26;
        int z1 = oz - 6;
        b.fill(x0, oy, z0, x1, top() + 3, z1, air);
        // The floors: concrete slabs, pillars, glass between them (some floors never got their glass)
        for (int f = 0; f <= FLOORS; f++) {
            int y = oy + 4 * f - 1;
            b.fill(x0, y, z0, x1, y, z1, slab);
            if (f == FLOORS) {
                break;
            }
            boolean bare = f > 2 && Math.floorMod(f * 7, 5) == 0;
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    boolean edge = x == x0 || x == x1 || z == z0 || z == z1;
                    if (!edge) {
                        continue;
                    }
                    boolean post = Math.floorMod(x - x0, 5) == 0 && Math.floorMod(z - z0, 5) == 0;
                    if (post) {
                        b.fill(x, y + 1, z, x, y + 4, z, pillar);
                    } else if (!bare) {
                        b.fill(x, y + 1, z, x, y + 3, z, glass);
                    } else {
                        b.set(x, y + 1, z, Blocks.IRON_BARS.getDefaultState());
                    }
                }
            }
            if (f > 0 && f % 3 == 0) {
                b.set(x0 + 2, y + 3, z0 + 2, Blocks.SEA_LANTERN.getDefaultState());
                b.set(x1 - 2, y + 3, z1 - 2, Blocks.SEA_LANTERN.getDefaultState());
            }
        }
        b.walls(x0, top(), z0, x1, top(), z1, Blocks.STONE_BRICK_WALL.getDefaultState());
        // The shaft: seven across, walled in glass on every floor, open to the sky, with a pit underneath
        b.fill(cx - 4, oy - PIT - 1, cz - 4, cx + 4, top() - 1, cz + 4, Blocks.GRAY_STAINED_GLASS.getDefaultState());
        b.fill(cx - 3, oy - PIT, cz - 3, cx + 3, top() + 2, cz + 3, air);
        b.fill(cx - 4, oy - PIT - 1, cz - 4, cx + 4, oy - 2, cz + 4, Blocks.STONE.getDefaultState());
        b.fill(cx - 3, oy - PIT, cz - 3, cx + 3, oy - 2, cz + 3, air);
        b.fill(cx - 3, oy - PIT - 1, cz - 3, cx + 3, oy - PIT - 1, cz + 3, Blocks.GRAVEL.getDefaultState());
        // You start standing on a glass floor under the lift: it won't be there for long
        b.fill(cx - 3, oy, cz - 3, cx + 3, oy, cz + 3, Blocks.GLASS.getDefaultState());
        // The ground floor: a door on the street; the shaft behind glass in the middle
        b.fill(ox - 1, oy, z1, ox + 1, oy + 2, z1, air);
        b.set(ox, oy + 2, oz - 9, Blocks.SEA_LANTERN.getDefaultState());
        b.sign(new BlockPos(ox, oy + 3, z1 + 1), Direction.SOUTH, "GAME VENUE", "", "step inside", "to register");
        // The cable all the way up
        b.fill(cx, carY + 4, cz, cx, top() + 2, cz, Blocks.CHAIN.getDefaultState());
        car(b, carY);
    }

    /** The lift: a platform with a bar under it and a cage round the top. */
    private void car(Builder b, int y) {
        b.fill(cx - 1, y, cz - 1, cx + 1, y, cz + 1, Blocks.IRON_BLOCK.getDefaultState());
        b.fill(cx - 1, y + 1, cz - 1, cx + 1, y + 2, cz + 1, Blocks.IRON_BARS.getDefaultState());
        b.set(cx, y + 1, cz, Blocks.AIR.getDefaultState());
        b.set(cx, y + 2, cz, Blocks.AIR.getDefaultState());
        b.fill(cx - 1, y + 3, cz - 1, cx + 1, y + 3, cz + 1, Blocks.SMOOTH_STONE_SLAB.getDefaultState());
        b.set(cx, y + 3, cz, Blocks.REDSTONE_LAMP.getDefaultState());
    }

    private void moveCar() {
        // the old car goes, the new one is one higher; the cable above it gets one shorter
        for (int x = cx - 1; x <= cx + 1; x++) {
            for (int z = cz - 1; z <= cz + 1; z++) {
                world.setBlockState(new BlockPos(x, carY, z), Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
            }
        }
        carY++;
        Builder b = new Builder(world);
        car(b, carY);
        b.step(64);
    }

    private Vec3d hangSpot(int index) {
        int i = index % 9;
        double dx = (i % 3 - 1) * 0.95;
        double dz = (i / 3 - 1) * 0.95;
        return new Vec3d(cx + 0.5 + dx, carY - 2.0, cz + 0.5 + dz);
    }

    @Override
    protected String[] rules() {
        return new String[]{
                "Hang on to the bar under the lift. Hold SNEAK to keep your grip.",
                "The lift goes up to the roof. It will stop on the way, for as long as it likes.",
                "Your grip gets weaker the longer you hang. Eat well and it lasts longer.",
                "Fall and it's game over. Reach the roof and you clear the game.",
                "Time limit: until the lift reaches the top."};
    }

    @Override
    protected int timeLimit() {
        return 20 * 60 * 10;
    }

    @Override
    protected void arrange() {
        int i = 0;
        for (LivingEntity entity : living()) {
            Vec3d at = hangSpot(i);
            entity.teleport(at.x, oy + 1.0, at.z);
            spot.put(entity.getUuid(), i);
            if (entity instanceof Contestant contestant) {
                contestant.hold();
                drain.put(contestant.getUuid(), 0.018F + random.nextFloat() * 0.03F);
            }
            grip.put(entity.getUuid(), 100.0F);
            i++;
        }
        // Where it will stop on the way up, and for how long
        int count = 4 + random.nextInt(3);
        for (int k = 0; k < count; k++) {
            int at = carY + 6 + random.nextInt(topCarY - carY - 10);
            stops.add(new int[]{at, 20 * (6 + random.nextInt(25))});
        }
    }

    @Override
    protected void begin() {
        for (UUID id : playing()) {
            LivingEntity entity = entity(id);
            if (entity != null) {
                entity.setNoGravity(true);
                entity.fallDistance = 0.0F;
            }
        }
        // The floor goes
        for (int x = cx - 3; x <= cx + 3; x++) {
            for (int z = cz - 3; z <= cz + 3; z++) {
                world.setBlockState(new BlockPos(x, oy, z), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
        world.playSound(null, cx, oy, cz, SoundEvents.BLOCK_GLASS_BREAK, SoundCategory.BLOCKS, 2.0F, 0.6F);
        titleAll(Text.literal("HOLD ON").formatted(Formatting.RED), Text.literal("Hold SNEAK").formatted(Formatting.WHITE));
        stepIn = 40;
        Contestant first = strangers().isEmpty() ? null : strangers().get(0);
        if (first != null) {
            first.speak("The floor just vanished from under your feet. You're hanging from the bar under the lift.",
                    Lines.pick(random, "Don't let go. Whatever happens, don't let go.", "Oh god, it's going up.", "How high does this go?"));
        }
    }

    @Override
    protected void running() {
        ticks++;
        // The lift: up a block at a time, with stops on the way
        if (stoppedFor > 0) {
            if (--stoppedFor == 0) {
                jolt();
                world.playSound(null, cx, carY, cz, SoundEvents.BLOCK_IRON_DOOR_OPEN, SoundCategory.BLOCKS, 1.5F, 0.5F);
            }
        } else if (--stepIn <= 0 && carY < topCarY) {
            stepIn = STEP_TICKS;
            moveCar();
            if (ticks % 3 == 0) {
                world.playSound(null, cx, carY, cz, SoundEvents.ENTITY_MINECART_RIDING, SoundCategory.BLOCKS, 0.6F, 0.6F);
            }
            for (int[] stop : stops) {
                if (stop[0] == carY) {
                    stoppedFor = stop[1];
                    jolt();
                    world.playSound(null, cx, carY, cz, SoundEvents.BLOCK_IRON_DOOR_OPEN, SoundCategory.BLOCKS, 1.5F, 0.4F);
                }
            }
        }
        for (UUID id : playing()) {
            LivingEntity entity = entity(id);
            if (entity == null) {
                continue;
            }
            if (falling.contains(id)) {
                if (entity.isOnGround() || entity.getY() < carY - 8.0) {
                    fail(id, "You fell.");
                }
                continue;
            }
            hang(id, entity);
        }
        if (carY >= topCarY && stoppedFor <= 0) {
            arrive();
        }
    }

    private void jolt() {
        for (UUID id : spot.keySet()) {
            grip.computeIfPresent(id, (k, g) -> g - 4.0F);
        }
    }

    private void hang(UUID id, LivingEntity entity) {
        float g = grip.getOrDefault(id, 100.0F);
        float rate;
        if (entity instanceof ServerPlayerEntity player) {
            int food = player.getHungerManager().getFoodLevel();
            rate = 0.028F * (food >= 14 ? 1.0F : food >= 8 ? 1.5F : 2.5F);
            boolean holding = player.isSneaking() || ticks < 20;
            int loose = holding ? 0 : letGo.merge(id, 1, Integer::sum);
            if (holding) {
                letGo.remove(id);
            }
            if (loose > 3) {
                drop(id, entity, "You let go.");
                return;
            }
        } else {
            rate = drain.getOrDefault(id, 0.03F);
        }
        g -= rate;
        grip.put(id, g);
        if (g <= 0.0F) {
            drop(id, entity, "Your hands give out.");
            if (entity instanceof Contestant contestant) {
                contestant.say(Lines.pick(random, "I can't... I can't hold...", "No no no no—", "Help me!"));
            }
            return;
        }
        if (entity instanceof Contestant contestant && g < 25.0F && random.nextFloat() < 0.004F) {
            contestant.speak("Your arms are burning and your grip is going.", Lines.pick(random, "My arms...", "I can't feel my fingers.",
                    "How much further?"));
        }
        Vec3d at = hangSpot(spot.getOrDefault(id, 0));
        entity.setNoGravity(true);
        entity.fallDistance = 0.0F;
        entity.setVelocity(Vec3d.ZERO);
        if (entity.getPos().squaredDistanceTo(at) > 0.04) {
            if (entity instanceof ServerPlayerEntity player) {
                player.networkHandler.requestTeleport(at.x, at.y, at.z, player.getYaw(), player.getPitch());
            } else {
                entity.refreshPositionAndAngles(at.x, at.y, at.z, entity.getYaw(), entity.getPitch());
            }
        }
    }

    private void drop(UUID id, LivingEntity entity, String why) {
        spot.remove(id);
        falling.add(id);
        entity.setNoGravity(false);
        tell(entity, why, Formatting.RED);
    }

    private void arrive() {
        world.playSound(null, cx, carY, cz, SoundEvents.BLOCK_BELL_USE, SoundCategory.BLOCKS, 2.0F, 1.0F);
        int i = 0;
        for (UUID id : playing()) {
            LivingEntity entity = entity(id);
            if (entity == null || falling.contains(id)) {
                continue;
            }
            unfloat(entity);
            entity.teleport(cx + 5.5 + (i % 3), top() + 0.1, cz - 2.5 + (i / 3));
            clear(id);
            if (entity instanceof Contestant contestant) {
                contestant.say(Lines.pick(random, "We made it... we actually made it.", "I'm never letting go of anything again.",
                        "My hands. I can't open my hands."));
            }
            i++;
        }
        finish();
    }

    @Override
    protected void died(UUID id) {
        LivingEntity entity = entity(id);
        if (entity != null) {
            unfloat(entity);
        }
    }

    @Override
    protected void more(ServerPlayerEntity player, Hud hud) {
        float g = Math.max(0.0F, grip.getOrDefault(player.getUuid(), 100.0F));
        int bars = Math.round(g / 10.0F);
        hud.line("§fGRIP " + (g > 50 ? "§a" : g > 25 ? "§e" : "§c") + "|".repeat(bars) + "§8" + "|".repeat(10 - bars) + " §f" + Math.round(g) + "%");
        hud.line("§fFLOOR §e" + Math.max(0, (carY - oy) / 4) + "§7 of " + FLOORS + (stoppedFor > 0 ? "  §cSTOPPED" : ""));
        hud.line("§7Hold SNEAK to hang on");
    }

    // --- what a stranger can say they'll do, and then does ---------------------------------------------------

    @Override
    public Map<String, String> options(Contestant who) {
        if (!isPlaying(who.getUuid()) || !grip.containsKey(who.getUuid())) {
            return Map.of();
        }
        return Map.of("HOLD_ON", "dig in and find the strength to hold on harder", "LET_GO", "let go of the bar, and fall");
    }

    @Override
    protected Map<String, String> optionWords(Contestant who) {
        return Map.of("HOLD_ON", "\\b(hold on|holding on|not letting go|keep holding)\\b", "LET_GO", "\\b(let go|letting go)\\b");
    }

    @Override
    public void chose(Contestant who, String option) {
        UUID id = who.getUuid();
        if (option.equals("HOLD_ON") && grip.containsKey(id) && ticks >= nextDigIn.getOrDefault(id, 0)) {
            nextDigIn.put(id, ticks + 20 * 20);
            grip.merge(id, 8.0F, Float::sum);
        } else if (option.equals("LET_GO") && grip.containsKey(id)) {
            drop(id, who, "You let go.");
        }
    }

    @Override
    public String situation(Contestant who) {
        return "You're playing HUMAN ELEVATOR, the two of spades: you're hanging by your hands from a bar under a lift that is slowly "
                + "rising up an open shaft in an unfinished tower. It's on floor " + (carY - oy) / 4 + " of " + FLOORS + "."
                + (stoppedFor > 0 ? " It has stopped, and nobody knows for how long." : "") + " Your grip is at "
                + Math.round(grip.getOrDefault(who.getUuid(), 100.0F)) + " percent. If you let go you fall to your death.";
    }

    @Override
    protected void ended() {
        for (UUID id : seats.keySet()) {
            LivingEntity entity = entity(id);
            if (entity != null) {
                unfloat(entity);
            }
        }
    }
}
