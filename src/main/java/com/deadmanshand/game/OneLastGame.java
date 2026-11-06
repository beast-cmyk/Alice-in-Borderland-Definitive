package com.deadmanshand.game;

import com.deadmanshand.Borderland;
import com.deadmanshand.Builder;
import com.deadmanshand.Hud;
import com.deadmanshand.Visa;
import com.deadmanshand.npc.Contestant;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
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

import java.util.UUID;

/**
 * One Last Game, the Joker's: a white, silent place where nothing moves, a table, two cards face down, and the one who
 * watches over all of this. Pick a card. Then there's a choice that isn't a game at all: go back to the living, or stay.
 */
final class OneLastGame extends Game {
    private static final int CHOOSE = 90 * 20;

    private UUID watchman;
    private BlockPos left;
    private BlockPos right;
    private BlockPos home;
    private BlockPos stay;
    private boolean picked;
    private int timer;
    private int oy;

    OneLastGame(Kind kind, ServerWorld world, BlockPos origin) {
        super(kind, world, origin);
    }

    @Override
    protected boolean instantEntry() {
        return true;
    }

    @Override
    protected boolean fillSeats() {
        return false;
    }

    @Override
    protected void plan(Builder b) {
        int ox = origin.getX();
        oy = origin.getY();
        int oz = origin.getZ();
        int tz = oz - 22;
        bounds = new Box(ox - 24, oy - 2, oz - 47, ox + 26, oy + 16, oz + 3);
        entry = new Box(ox - 3, oy, oz - 4, ox + 4, oy + 3, oz + 1);
        entryPos = new BlockPos(ox, oy, oz - 2);
        approach = new BlockPos(ox, oy, oz + 6);
        left = new BlockPos(ox - 1, oy + 1, tz);
        right = new BlockPos(ox + 1, oy + 1, tz);
        home = new BlockPos(ox - 8, oy, tz - 8);
        stay = new BlockPos(ox + 8, oy, tz - 8);
        BlockState air = Blocks.AIR.getDefaultState();
        BlockState white = Blocks.WHITE_CONCRETE.getDefaultState();
        // A white room with no corners you can find: floor, walls and ceiling all the same
        b.fill(ox - 24, oy - 1, oz - 47, ox + 25, oy + 14, oz + 2, white);
        b.fill(ox - 22, oy, oz - 45, ox + 23, oy + 12, oz - 1, air);
        b.fill(ox - 2, oy, oz - 1, ox + 2, oy + 2, oz + 2, air);
        for (int x = ox - 20; x <= ox + 20; x += 8) {
            for (int z = oz - 42; z <= oz - 4; z += 8) {
                b.set(x, oy + 12, z, Blocks.SEA_LANTERN.getDefaultState());
            }
        }
        // the table and the two cards
        b.fill(ox - 2, oy, tz, ox + 2, oy, tz, Blocks.BLACK_CONCRETE.getDefaultState());
        b.set(left, Blocks.WHITE_CARPET.getDefaultState());
        b.set(right, Blocks.WHITE_CARPET.getDefaultState());
        b.sign(new BlockPos(ox - 1, oy, tz + 1), Direction.SOUTH, "", "THIS", "CARD", "");
        b.sign(new BlockPos(ox + 1, oy, tz + 1), Direction.SOUTH, "", "THIS", "CARD", "");
        // the two ways it can end: back the way you came, and the water
        b.fill(home.getX() - 1, oy, home.getZ() - 1, home.getX() + 1, oy + 3, home.getZ() + 1, Blocks.LIGHT_BLUE_STAINED_GLASS.getDefaultState());
        b.fill(home.getX(), oy, home.getZ(), home.getX(), oy + 2, home.getZ(), Blocks.END_ROD.getDefaultState());
        b.fill(stay.getX() - 2, oy - 1, stay.getZ() - 2, stay.getX() + 2, oy - 1, stay.getZ() + 2, Blocks.WATER.getDefaultState());
        b.set(stay, Blocks.WATER.getDefaultState());
    }

    @Override
    protected String[] rules() {
        return new String[]{
                "One last game.",
                "There are two cards on the table. Choose one."};
    }

    @Override
    protected int timeLimit() {
        return 3 * CHOOSE;
    }

    @Override
    protected void arrange() {
        Contestant w = citizen("The Watchman", new BlockPos(origin.getX(), oy, origin.getZ() - 24));
        if (w != null) {
            watchman = w.getUuid();
            w.hold();
        }
        for (UUID id : playing()) {
            if (!id.equals(watchman)) {
                LivingEntity e = entity(id);
                if (e != null) {
                    e.teleport(origin.getX() + 0.5, oy, origin.getZ() - 19.5);
                }
            }
        }
    }

    @Override
    protected void begin() {
        timer = CHOOSE;
        Contestant w = contestant(watchman);
        if (w != null) {
            w.lookAt(new Vec3d(origin.getX() + 0.5, oy + 1.6, origin.getZ() - 19.5));
            w.speak("You are the one who watches over this world. A challenger has reached you, the last game. You're calm and kind, "
                    + "and a little sad.", "You came a long way. Sit down. One of these is yours.");
        }
        for (UUID id : playing()) {
            ServerPlayerEntity p = player(id);
            if (p != null) {
                choices(p, "Choose a card:", "The left one|left", "The right one|right");
            }
        }
    }

    // --- the choice ---------------------------------------------------------------------------------------------

    private void pick(ServerPlayerEntity player, boolean leftCard) {
        if (picked) {
            return;
        }
        picked = true;
        timer = CHOOSE;
        BlockPos at = leftCard ? left : right;
        world.playSound(null, at, SoundEvents.ITEM_BOOK_PAGE_TURN, SoundCategory.BLOCKS, 1.0F, 0.8F);
        world.spawnParticles(ParticleTypes.END_ROD, at.getX() + 0.5, at.getY() + 0.3, at.getZ() + 0.5, 20, 0.2, 0.2, 0.2, 0.02);
        Borderland.title(player, Text.literal("JOKER").formatted(Formatting.LIGHT_PURPLE), Text.literal("").formatted(Formatting.WHITE));
        player.sendMessage(Text.literal("You turn it over. The Joker. You turn the other one over too: the Joker.").formatted(Formatting.LIGHT_PURPLE), false);
        Contestant w = contestant(watchman);
        if (w != null) {
            w.speak("The challenger has picked a card; both are Jokers. There was never a winning card. You tell them the last thing is a choice: "
                    + "go back to the living, or stay here.", "Both Jokers. There was never anything to win. All that's left is to choose.");
        }
        choices(player, "Go back, or stay?", "§bGo back to the living|home", "§9Stay|stay");
        player.sendMessage(Text.literal("(Or walk to it: the light, or the water.)").formatted(Formatting.GRAY), false);
    }

    private void goHome(ServerPlayerEntity player) {
        announce(nameOf(player.getUuid()) + " goes back.");
        world.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.BLOCK_BEACON_POWER_SELECT, SoundCategory.PLAYERS, 1.0F, 1.0F);
        clear(player.getUuid());
        Borderland.title(player, Text.literal("GAME CLEAR").formatted(Formatting.WHITE), Text.literal("you wake up").formatted(Formatting.GRAY));
        player.sendMessage(Text.literal("A white light, a sound like the sea, and then a hospital ceiling. Somebody is holding your hand. "
                + "You don't remember why. You're alive.").formatted(Formatting.AQUA), false);
        Borderland.sendHome(player);
        finish();
    }

    private void stayHere(ServerPlayerEntity player) {
        announce(nameOf(player.getUuid()) + " stays.");
        clear(player.getUuid());
        Visa.Data data = Visa.of(player);
        data.visa += 365 * Visa.DAY;
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.WATER_BREATHING, 200, 0, false, false));
        Borderland.title(player, Text.literal("CITIZEN").formatted(Formatting.BLUE), Text.literal("this is your world now").formatted(Formatting.GRAY));
        player.sendMessage(Text.literal("You walk into the water and it closes over you, warm. When you open your eyes you're back in the city, "
                + "and your visa no longer counts down the way it did. You live here now.").formatted(Formatting.BLUE), false);
        player.teleport(approach.getX() + 0.5, approach.getY(), approach.getZ() + 0.5);
        finish();
    }

    @Override
    protected void answered(ServerPlayerEntity player, String answer) {
        String a = answer.trim().toLowerCase();
        if (!picked && (a.equals("left") || a.equals("right"))) {
            pick(player, a.equals("left"));
        } else if (picked && a.equals("home")) {
            goHome(player);
        } else if (picked && a.equals("stay")) {
            stayHere(player);
        }
    }

    @Override
    protected ActionResult use(ServerPlayerEntity player, BlockPos pos, BlockState state) {
        if (!picked && (pos.equals(left) || pos.equals(left.down()))) {
            pick(player, true);
            return ActionResult.SUCCESS;
        }
        if (!picked && (pos.equals(right) || pos.equals(right.down()))) {
            pick(player, false);
            return ActionResult.SUCCESS;
        }
        return ActionResult.PASS;
    }

    @Override
    protected boolean attacked(ServerPlayerEntity player, Entity target) {
        return true;
    }

    @Override
    protected void running() {
        for (UUID id : playing()) {
            ServerPlayerEntity p = player(id);
            if (p == null) {
                continue;
            }
            if (picked && p.squaredDistanceTo(Vec3d.ofBottomCenter(home)) < 2.0 * 2.0) {
                goHome(p);
                return;
            }
            if (picked && p.squaredDistanceTo(Vec3d.ofBottomCenter(stay)) < 2.5 * 2.5) {
                stayHere(p);
                return;
            }
        }
        if (world.getTime() % 20 == 0) {
            world.spawnParticles(ParticleTypes.SPLASH, stay.getX() + 0.5, oy + 0.2, stay.getZ() + 0.5, 30, 1.5, 0.1, 1.5, 0.2);
            world.spawnParticles(ParticleTypes.END_ROD, home.getX() + 0.5, oy + 1.5, home.getZ() + 0.5, 3, 0.4, 0.8, 0.4, 0.01);
        }
        if (--timer <= 0) {
            for (UUID id : playing()) {
                ServerPlayerEntity p = player(id);
                if (p != null) {
                    p.sendMessage(Text.literal("You don't choose. Staying is a choice too.").formatted(Formatting.GRAY), false);
                    if (!picked) {
                        pick(p, true);
                    }
                    stayHere(p);
                    return;
                }
            }
            finish();
        }
    }

    @Override
    protected void timeUp() {
        // nobody dies at the last table
        for (UUID id : playing()) {
            clear(id);
        }
    }

    @Override
    protected void ended() {
        Contestant w = contestant(watchman);
        if (w != null) {
            w.discard();
        }
    }

    @Override
    protected void more(ServerPlayerEntity player, Hud hud) {
        hud.line(picked ? "§fThe light, or the water." : "§fTwo cards. Choose one.");
    }

    @Override
    public String situation(Contestant who) {
        return "You are the Watchman, at the very end of everything: a white, silent room, a table, two cards face down, both of them Jokers. "
                + "The challenger in front of you has survived every game. " + (picked ? "They've turned the cards over; now they must choose "
                + "whether to go back to the living or stay." : "They haven't chosen a card yet.");
    }
}
