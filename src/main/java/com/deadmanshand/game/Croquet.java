package com.deadmanshand.game;

import com.deadmanshand.Builder;
import com.deadmanshand.Hud;
import com.deadmanshand.npc.Contestant;
import com.deadmanshand.npc.Lines;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeavesBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Queen of Hearts, on a rooftop garden: croquet against the Queen. Finish three sets, by the ordinary rules, and you've
 * cleared it. Quit and you haven't. Between sets there's tea. While you play, she talks to you, and the longer it goes
 * on the less sure you are where you really are.
 */
final class Croquet extends Game {
    private static final double FRICTION = 0.94;
    private static final int TURN = 30 * 20;
    private static final int TEA = 25 * 20;
    private static final int SETS = 3;

    private static final class Ball {
        final UUID owner;
        final String colour;
        double x;
        double z;
        double vx;
        double vz;
        /** Hoops through so far: 0-3, then 4 means only the stake is left. */
        int next;
        ArmorStandEntity stand;

        Ball(UUID owner, String colour) {
            this.owner = owner;
            this.colour = colour;
        }

        boolean moving() {
            return vx * vx + vz * vz > 0.015 * 0.015;
        }
    }

    private enum Step { AIM, ROLL, TEA }

    private final Map<UUID, Ball> balls = new LinkedHashMap<>();
    private final List<UUID> order = new ArrayList<>();
    private final Map<UUID, Integer> whisper = new HashMap<>();
    private final Map<UUID, Integer> beeps = new HashMap<>();
    private final int[][] hoops = new int[4][2];
    private int sx;
    private int sz;
    private int ry;
    private int cx0;
    private int cx1;
    private int cz0;
    private int cz1;
    private UUID queen;
    private Step step = Step.AIM;
    private int turn;
    private int timer;
    private int set = 1;
    private boolean bonus;
    private UUID hitter;
    private int npcWait;

    Croquet(Kind kind, ServerWorld world, BlockPos origin) {
        super(kind, world, origin);
    }

    @Override
    protected void plan(Builder b) {
        int ox = origin.getX();
        int oy = origin.getY();
        int oz = origin.getZ();
        ry = oy + 15;
        int bx0 = ox - 21;
        int bx1 = ox + 21;
        int bz0 = oz - 46;
        int bz1 = oz - 6;
        cx0 = ox - 17;
        cx1 = ox + 17;
        cz0 = oz - 44;
        cz1 = oz - 21;
        hoops[0] = new int[]{ox - 9, oz - 27};
        hoops[1] = new int[]{ox - 9, oz - 39};
        hoops[2] = new int[]{ox + 9, oz - 39};
        hoops[3] = new int[]{ox + 9, oz - 27};
        sx = ox;
        sz = oz - 33;
        bounds = new Box(ox - 24, oy - 2, oz - 47, ox + 26, ry + 8, oz + 3);
        entry = new Box(ox - 3, oy, oz - 5, ox + 4, oy + 3, oz + 1);
        entryPos = new BlockPos(ox, oy, oz - 2);
        approach = new BlockPos(ox, oy, oz + 6);
        BlockState air = Blocks.AIR.getDefaultState();
        BlockState white = Blocks.WHITE_CONCRETE.getDefaultState();
        // The building: white, with bands of window
        b.fill(bx0, oy - 1, bz0, bx1, ry - 1, bz1, white);
        b.fill(bx0 + 1, oy, bz0 + 1, bx1 - 1, ry - 2, bz1 - 1, air);
        for (int y = oy + 2; y < ry - 1; y += 3) {
            b.walls(bx0, y, bz0, bx1, y + 1, bz1, Blocks.LIGHT_BLUE_STAINED_GLASS.getDefaultState());
        }
        b.fill(bx0, ry - 1, bz0, bx1, ry - 1, bz1, Blocks.SMOOTH_STONE.getDefaultState());
        b.walls(bx0, ry, bz0, bx1, ry, bz1, white);
        b.walls(bx0, ry + 1, bz0, bx1, ry + 1, bz1, Blocks.GLASS_PANE.getDefaultState());
        // The lift lobby out front, under an awning
        b.fill(ox - 4, oy - 1, oz - 5, ox + 4, oy - 1, oz + 1, Blocks.POLISHED_ANDESITE.getDefaultState());
        b.fill(ox - 4, oy, oz - 5, ox + 4, oy + 3, oz + 1, air);
        b.fill(ox - 4, oy + 4, oz - 5, ox + 4, oy + 4, oz + 1, Blocks.WHITE_CONCRETE.getDefaultState());
        for (int[] p : new int[][]{{ox - 4, oz + 1}, {ox + 4, oz + 1}}) {
            b.fill(p[0], oy, p[1], p[0], oy + 3, p[1], Blocks.QUARTZ_PILLAR.getDefaultState());
        }
        b.fill(ox - 1, oy, bz1, ox + 1, oy + 2, bz1, Blocks.IRON_BLOCK.getDefaultState());
        b.set(ox, oy + 3, bz1, Blocks.REDSTONE_LAMP.getDefaultState());
        b.sign(new BlockPos(ox + 3, oy + 2, oz + 2), Direction.SOUTH, "GAME VENUE", "", "step inside", "to register");
        b.set(ox + 3, oy + 2, oz + 1, Blocks.QUARTZ_PILLAR.getDefaultState());
        // The lawn, the hedge round it, the hoops and the stake
        b.fill(cx0, ry - 1, cz0, cx1, ry - 1, cz1, Blocks.GRASS_BLOCK.getDefaultState());
        for (int x = cx0; x <= cx1; x += 2) {
            b.fill(x, ry - 1, cz0, x, ry - 1, cz1, Blocks.MOSS_BLOCK.getDefaultState());
        }
        BlockState hedge = Blocks.AZALEA_LEAVES.getDefaultState().with(LeavesBlock.PERSISTENT, true);
        b.walls(cx0 - 1, ry, cz0 - 1, cx1 + 1, ry, cz1 + 1, hedge);
        b.fill(ox - 1, ry, cz1 + 1, ox + 1, ry, cz1 + 1, air);
        for (int[] h : hoops) {
            b.set(h[0] - 1, ry, h[1], Blocks.IRON_BARS.getDefaultState());
            b.set(h[0] + 1, ry, h[1], Blocks.IRON_BARS.getDefaultState());
            b.fill(h[0] - 1, ry + 1, h[1], h[0] + 1, ry + 1, h[1], Blocks.IRON_BARS.getDefaultState());
        }
        b.fill(sx, ry, sz, sx, ry + 1, sz, Blocks.SPRUCE_FENCE.getDefaultState());
        b.set(sx, ry + 2, sz, Blocks.RED_WOOL.getDefaultState());
        // Roses along the edges
        for (int x = bx0 + 2; x <= bx1 - 2; x += 3) {
            b.set(x, ry - 1, bz0 + 1, Blocks.GRASS_BLOCK.getDefaultState());
            b.set(x, ry, bz0 + 1, Blocks.POPPY.getDefaultState());
        }
        // The tea table
        int tz = oz - 13;
        b.fill(ox - 6, ry - 1, oz - 17, ox + 6, ry - 1, oz - 9, Blocks.SMOOTH_QUARTZ.getDefaultState());
        b.fill(ox - 2, ry, tz - 1, ox + 2, ry, tz + 1, Blocks.SMOOTH_QUARTZ_SLAB.getDefaultState());
        for (int x = ox - 2; x <= ox + 2; x += 2) {
            b.set(x, ry, tz - 2, Blocks.SPRUCE_SLAB.getDefaultState());
            b.set(x, ry, tz + 2, Blocks.SPRUCE_SLAB.getDefaultState());
        }
        b.set(ox - 3, ry, tz, Blocks.SPRUCE_SLAB.getDefaultState());
        b.set(ox + 3, ry, tz, Blocks.SPRUCE_SLAB.getDefaultState());
        for (int[] p : new int[][]{{ox - 5, oz - 16}, {ox + 5, oz - 16}, {ox - 5, oz - 10}, {ox + 5, oz - 10}}) {
            b.fill(p[0], ry, p[1], p[0], ry + 3, p[1], Blocks.SPRUCE_FENCE.getDefaultState());
        }
        b.fill(ox - 5, ry + 4, oz - 16, ox + 5, ry + 4, oz - 10, Blocks.WHITE_WOOL.getDefaultState());
        b.fill(ox - 4, ry + 4, oz - 15, ox + 4, ry + 4, oz - 11, Blocks.PINK_WOOL.getDefaultState());
        b.set(ox, ry + 3, tz, Blocks.LANTERN.getDefaultState().with(net.minecraft.block.LanternBlock.HANGING, true));
    }

    @Override
    protected String[] rules() {
        return new String[]{
                "A game of croquet against the Queen of Hearts.",
                "Hit your ball with your mallet (punch it; a full swing hits harder) through the four hoops in order, then strike the stake.",
                "Take turns. Going through your hoop earns another shot. The first to strike the stake ends the set.",
                "Finish three sets and you clear the game. Quit, and it's game over."};
    }

    @Override
    protected int timeLimit() {
        return 30 * 60 * 20;
    }

    @Override
    protected void arrange() {
        Contestant q = citizen("Sera", new BlockPos(origin.getX() + 2, origin.getY(), origin.getZ() - 3));
        if (q != null) {
            queen = q.getUuid();
        }
        for (Contestant c : strangers()) {
            c.hold();
        }
    }

    @Override
    protected void begin() {
        String[] colours = {"blue", "red", "black", "yellow", "green"};
        Item[] wool = {Items.BLUE_WOOL, Items.RED_WOOL, Items.BLACK_WOOL, Items.YELLOW_WOOL, Items.GREEN_WOOL};
        int i = 0;
        for (UUID id : playing()) {
            if (id.equals(queen)) {
                continue;
            }
            order.add(id);
            balls.put(id, new Ball(id, colours[Math.min(i, 4)]));
            i++;
        }
        if (queen != null) {
            order.add(queen);
            balls.put(queen, new Ball(queen, "pink"));
        }
        i = 0;
        for (Ball ball : balls.values()) {
            ItemStack head = new ItemStack(ball.colour.equals("pink") ? Items.PINK_WOOL : wool[Math.min(i, 4)]);
            ArmorStandEntity stand = new ArmorStandEntity(world, 0, ry, 0);
            NbtCompound nbt = new NbtCompound();
            nbt.putBoolean("Invisible", true);
            nbt.putBoolean("NoBasePlate", true);
            stand.readCustomDataFromNbt(nbt);
            stand.setNoGravity(true);
            stand.setInvulnerable(true);
            stand.equipStack(EquipmentSlot.HEAD, head);
            stand.setCustomName(Text.literal(nameOf(ball.owner) + "'s ball"));
            stand.setCustomNameVisible(true);
            ball.stand = stand;
            world.spawnEntity(stand);
            i++;
        }
        toStart();
        Contestant q = contestant(queen);
        if (q != null) {
            q.speak("You're the Queen of Hearts, a citizen of this place, about to play croquet on your rooftop garden.",
                    Lines.pick(random, "Shall we play? Three sets. It's only a game.", "What a lovely day for croquet.", "Don't look so worried. It's croquet."));
        }
        startTurn(0);
    }

    /** Balls back on the line and everyone behind them. */
    private void toStart() {
        int i = 0;
        for (Ball ball : balls.values()) {
            ball.x = origin.getX() - 4.5 + 2 * i;
            ball.z = cz1 - 1.5;
            ball.vx = 0;
            ball.vz = 0;
            ball.next = 0;
            place(ball);
            LivingEntity e = entity(ball.owner);
            if (e != null) {
                e.teleport(ball.x, ry, ball.z + 1.5);
            }
            i++;
        }
    }

    private void place(Ball ball) {
        if (ball.stand != null) {
            ball.stand.setPosition(ball.x, ry - 1.6, ball.z);
        }
    }

    private UUID current() {
        return order.isEmpty() ? null : order.get(Math.floorMod(turn, order.size()));
    }

    private void startTurn(int index) {
        for (int k = 0; k < order.size(); k++) {
            UUID id = order.get(Math.floorMod(index + k, order.size()));
            if (isPlaying(id) && balls.containsKey(id)) {
                turn = Math.floorMod(index + k, order.size());
                step = Step.AIM;
                timer = TURN;
                npcWait = 40 + random.nextInt(60);
                LivingEntity e = entity(id);
                tell(e, "Your turn.", Formatting.GOLD);
                return;
            }
        }
    }

    // --- play ---------------------------------------------------------------------------------------------

    @Override
    protected void running() {
        mindGames();
        switch (step) {
            case AIM -> {
                UUID id = current();
                if (id == null || !isPlaying(id)) {
                    startTurn(turn + 1);
                    return;
                }
                Ball ball = balls.get(id);
                if (timer % 10 == 0 && ball != null) {
                    Vec3d at = target(ball);
                    world.spawnParticles(ParticleTypes.END_ROD, at.x, ry + 0.3, at.z, 2, 0.1, 0.1, 0.1, 0.0);
                }
                Contestant c = contestant(id);
                if (c != null && ball != null) {
                    LivingEntity e = c;
                    if (e.squaredDistanceTo(ball.x, ry, ball.z) > 2.5 * 2.5) {
                        c.go(new Vec3d(ball.x, ry, ball.z), 1.0);
                    } else if (--npcWait <= 0) {
                        c.look(ball.stand);
                        c.swingHand(Hand.MAIN_HAND);
                        npcHit(c, ball);
                    }
                }
                if (step == Step.AIM && --timer <= 0) {
                    tell(entity(id), "You let your turn go by.", Formatting.GRAY);
                    startTurn(turn + 1);
                }
            }
            case ROLL -> {
                roll();
                if (step == Step.ROLL && (--timer <= 0 || balls.values().stream().noneMatch(Ball::moving))) {
                    for (Ball ball : balls.values()) {
                        ball.vx = 0;
                        ball.vz = 0;
                    }
                    if (bonus && isPlaying(hitter)) {
                        bonus = false;
                        tell(entity(hitter), "Through the hoop: another shot.", Formatting.GREEN);
                        startTurn(turn);
                    } else {
                        startTurn(turn + 1);
                    }
                }
            }
            case TEA -> {
                for (Contestant c : strangers()) {
                    c.hold();
                }
                if (--timer <= 0) {
                    set++;
                    toStart();
                    announce("Set " + set + " of " + SETS + ".");
                    startTurn(set - 1);
                } else if (timer == TEA - 60) {
                    Contestant q = contestant(queen);
                    if (q != null) {
                        q.speak("You're the Queen of Hearts, pouring tea between sets of croquet, working on the challengers' minds.",
                                Lines.pick(random, "More tea? You look tired.", "You can stop whenever you like, you know.",
                                        "Does any of this feel real to you?"));
                    }
                }
            }
        }
    }

    private void hit(UUID who, Ball ball, double yaw, double power) {
        ball.vx = -Math.sin(Math.toRadians(yaw)) * power;
        ball.vz = Math.cos(Math.toRadians(yaw)) * power;
        hitter = who;
        bonus = false;
        step = Step.ROLL;
        timer = 15 * 20;
        world.playSound(null, ball.x, ry, ball.z, SoundEvents.BLOCK_WOOD_HIT, SoundCategory.PLAYERS, 1.0F, 1.4F);
    }

    @Override
    protected boolean attacked(ServerPlayerEntity player, Entity target) {
        Ball ball = null;
        for (Ball b : balls.values()) {
            if (b.stand == target) {
                ball = b;
            }
        }
        if (ball == null) {
            return false;
        }
        if (!ball.owner.equals(player.getUuid())) {
            tell(player, "That's " + nameOf(ball.owner) + "'s ball.", Formatting.GRAY);
        } else if (step != Step.AIM || !player.getUuid().equals(current())) {
            tell(player, "It's not your turn.", Formatting.GRAY);
        } else {
            double power = 0.15 + 0.85 * player.getAttackCooldownProgress(0.5F);
            hit(player.getUuid(), ball, player.getYaw(), power);
        }
        return true;
    }

    /** Where {@code ball}'s owner should be aiming next. */
    private Vec3d target(Ball ball) {
        if (ball.next >= hoops.length) {
            return new Vec3d(sx + 0.5, ry, sz + 0.5);
        }
        int[] h = hoops[ball.next];
        return new Vec3d(h[0] + 0.5, ry, h[1] + 0.5);
    }

    private void npcHit(Contestant c, Ball ball) {
        Vec3d aim;
        double extra;
        if (ball.next >= hoops.length) {
            aim = new Vec3d(sx + 0.5, ry, sz + 0.5);
            extra = 1.0;
        } else {
            int[] h = hoops[ball.next];
            double hx = h[0] + 0.5;
            double hz = h[1] + 0.5;
            double side = Math.signum(ball.z - hz);
            if (side == 0) {
                side = 1;
            }
            double dx = Math.abs(ball.x - hx);
            double dz = Math.abs(ball.z - hz);
            if (dx < 0.4 && dz < 4.0) {
                aim = new Vec3d(hx, ry, hz - side * 2.0);
                extra = 0.0;
            } else {
                aim = new Vec3d(hx, ry, hz + side * 1.8);
                extra = 0.0;
                if (dz < 1.0) {
                    // on the hoop's line but off to one side: come round
                    aim = new Vec3d(ball.x + Math.signum(hx - ball.x) * 0.5, ry, hz + side * 2.2);
                }
            }
        }
        double ddx = aim.x - ball.x;
        double ddz = aim.z - ball.z;
        double dist = Math.sqrt(ddx * ddx + ddz * ddz) + extra;
        double yaw = Math.toDegrees(Math.atan2(-ddx, ddz));
        float skill = c.judgment();
        yaw += (random.nextFloat() - random.nextFloat()) * (1.0 - skill) * 14.0;
        double power = Math.min(1.0, dist * (1.0 - FRICTION) * 1.08) * (1.0 + (random.nextFloat() - 0.5) * (1.0 - skill) * 0.4);
        hit(c.getUuid(), ball, yaw, Math.max(0.08, power));
    }

    private void roll() {
        for (Ball ball : balls.values()) {
            if (!ball.moving()) {
                ball.vx = 0;
                ball.vz = 0;
                continue;
            }
            double ox = ball.x;
            double oz = ball.z;
            double nx = ox + ball.vx;
            double nz = oz + ball.vz;
            // the hedge
            if (nx < cx0 + 0.3 || nx > cx1 + 0.7) {
                ball.vx = -ball.vx * 0.5;
                nx = ox;
            }
            if (nz < cz0 + 0.3 || nz > cz1 + 0.7) {
                ball.vz = -ball.vz * 0.5;
                nz = oz;
            }
            // hoop legs
            boolean blocked = false;
            for (int[] h : hoops) {
                if (inBlock(nx, nz, h[0] - 1, h[1], 0.15) || inBlock(nx, nz, h[0] + 1, h[1], 0.15)) {
                    blocked = true;
                }
            }
            if (blocked) {
                ball.vx = -ball.vx * 0.4;
                ball.vz = -ball.vz * 0.4;
                nx = ox;
                nz = oz;
                world.playSound(null, ox, ry, oz, SoundEvents.BLOCK_ANVIL_LAND, SoundCategory.PLAYERS, 0.15F, 2.0F);
            }
            // the stake
            if (inBlock(nx, nz, sx, sz, 0.2)) {
                if (ball.next >= hoops.length) {
                    ball.x = nx;
                    ball.z = nz;
                    place(ball);
                    world.playSound(null, sx, ry, sz, SoundEvents.BLOCK_WOOD_HIT, SoundCategory.PLAYERS, 1.5F, 0.8F);
                    setOver(ball.owner);
                    return;
                }
                ball.vx = -ball.vx * 0.5;
                ball.vz = -ball.vz * 0.5;
                nx = ox;
                nz = oz;
            }
            // through its next hoop?
            if (ball.next < hoops.length) {
                int[] h = hoops[ball.next];
                double line = h[1] + 0.5;
                if ((oz - line) * (nz - line) < 0) {
                    double t = (line - oz) / (nz - oz);
                    double cx = ox + (nx - ox) * t;
                    if (cx > h[0] + 0.05 && cx < h[0] + 0.95) {
                        ball.next++;
                        if (ball.owner.equals(hitter)) {
                            bonus = true;
                        }
                        world.playSound(null, nx, ry, nz, SoundEvents.BLOCK_NOTE_BLOCK_CHIME, SoundCategory.PLAYERS, 1.0F, 1.2F + 0.1F * ball.next);
                        tell(entity(ball.owner), ball.next >= hoops.length ? "Through the last hoop. Now the stake." : "Through hoop " + ball.next + ".",
                                Formatting.GREEN);
                    }
                }
            }
            ball.x = nx;
            ball.z = nz;
            ball.vx *= FRICTION;
            ball.vz *= FRICTION;
            place(ball);
        }
        // one ball hitting another passes its speed on
        List<Ball> all = new ArrayList<>(balls.values());
        for (int a = 0; a < all.size(); a++) {
            for (int b = a + 1; b < all.size(); b++) {
                Ball p = all.get(a);
                Ball q = all.get(b);
                double dx = q.x - p.x;
                double dz = q.z - p.z;
                if (dx * dx + dz * dz < 0.45 * 0.45 && (p.moving() || q.moving())) {
                    Ball fast = p.moving() && (!q.moving() || p.vx * p.vx + p.vz * p.vz > q.vx * q.vx + q.vz * q.vz) ? p : q;
                    Ball slow = fast == p ? q : p;
                    slow.vx = fast.vx * 0.9;
                    slow.vz = fast.vz * 0.9;
                    fast.vx *= 0.15;
                    fast.vz *= 0.15;
                    world.playSound(null, slow.x, ry, slow.z, SoundEvents.BLOCK_WOOD_HIT, SoundCategory.PLAYERS, 1.0F, 1.8F);
                }
            }
        }
    }

    private static boolean inBlock(double x, double z, int bx, int bz, double pad) {
        return x > bx - pad && x < bx + 1 + pad && z > bz - pad && z < bz + 1 + pad;
    }

    private void setOver(UUID winner) {
        announce(nameOf(winner) + " has struck the stake. Set " + set + " is over.");
        for (Ball ball : balls.values()) {
            ball.vx = 0;
            ball.vz = 0;
        }
        if (set >= SETS) {
            announce("Three sets played.");
            for (UUID id : playing()) {
                if (!id.equals(queen)) {
                    clear(id);
                }
            }
            if (queen != null) {
                fail(queen, null);
            }
            finish();
            return;
        }
        step = Step.TEA;
        timer = TEA;
        announce("Tea. The next set begins in " + TEA / 20 + " seconds.");
        int i = 0;
        int tz = origin.getZ() - 13;
        double[][] seatsAt = {{-2, -2}, {0, -2}, {2, -2}, {-2, 2}, {0, 2}, {2, 2}, {-3, 0}, {3, 0}};
        for (UUID id : playing()) {
            LivingEntity e = entity(id);
            if (e != null) {
                double[] s = seatsAt[i % seatsAt.length];
                e.teleport(origin.getX() + s[0] + 0.5, ry + 0.5, tz + s[1] + 0.5);
                i++;
            }
            if (player(id) != null) {
                choices(player(id), "Tea is served. Will you go on?", "Keep playing|continue", "Quit|quit");
            } else if (contestant(id) != null && !id.equals(queen)) {
                Contestant c = contestant(id);
                float despair = (c.nature == Contestant.Nature.TIMID ? 0.15F : 0.0F) + c.feelings.grief * 0.35F + c.feelings.fear * 0.2F
                        - c.feelings.hope * 0.3F;
                if (random.nextFloat() < despair) {
                    c.say(Lines.pick(random, "I can't do this anymore.", "I'm done. I quit.", "None of this is real anyway."));
                    quit(id);
                }
            }
        }
    }

    private void quit(UUID id) {
        announce(nameOf(id) + " has quit.");
        fail(id, "You quit.");
    }

    @Override
    protected void answered(ServerPlayerEntity player, String answer) {
        String a = answer.trim().toLowerCase();
        if (a.equals("quit")) {
            quit(player.getUuid());
        } else if (a.equals("continue")) {
            tell(player, "Then play on.", Formatting.GRAY);
        }
    }

    /** She talks to you while you play, and the longer it goes on the less you trust what you see. */
    private void mindGames() {
        for (UUID id : playing()) {
            ServerPlayerEntity player = player(id);
            if (player == null) {
                continue;
            }
            int b = beeps.getOrDefault(id, 0);
            if (b > 0) {
                beeps.put(id, b - 1);
                if (b % 20 == 0) {
                    player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_PLING, SoundCategory.MASTER, 0.5F, 2.0F);
                }
            }
            int w = whisper.getOrDefault(id, 20 * 40) - 1;
            whisper.put(id, w);
            if (w > 0) {
                continue;
            }
            whisper.put(id, 20 * (50 + random.nextInt(70)) - set * 200);
            String line = Lines.pick(random,
                    "\"You hit your head, you know. You're lying in a hospital bed. Can you hear the monitor?\"",
                    "\"Your friends aren't here. They never were. You made them up.\"",
                    "\"Look at the sky. Does it look real to you?\"",
                    "\"The doctors say you might wake up, if you stop playing.\"",
                    "\"Why keep hitting a ball about? Just quit, and you can rest.\"",
                    "\"Everyone you've lost here is fine. It's only you who isn't.\"");
            player.sendMessage(Text.literal("Sera, close to your ear: " + line).formatted(Formatting.LIGHT_PURPLE), false);
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.NAUSEA, 20 * 7, 0, false, false));
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, 25, 0, false, false));
            beeps.put(id, 20 * 6);
            if (set >= 2 && random.nextFloat() < 0.5F) {
                choices(player, "Stop?", "Quit|quit", "Keep playing|continue");
            }
        }
    }

    @Override
    protected void ended() {
        for (Ball ball : balls.values()) {
            if (ball.stand != null) {
                ball.stand.discard();
            }
        }
    }

    // --- show ---------------------------------------------------------------------------------------------

    @Override
    protected void more(ServerPlayerEntity player, Hud hud) {
        UUID id = player.getUuid();
        Ball mine = balls.get(id);
        hud.line("§fSET §e" + set + "/" + SETS + (step == Step.TEA ? "  §dTEA " + Hud.clock(timer) : ""));
        UUID now = current();
        if (step != Step.TEA && now != null) {
            hud.line(id.equals(now) ? "§aYOUR SHOT " + (step == Step.AIM ? Hud.clock(timer) : "") : "§7" + nameOf(now) + "'s shot");
        }
        if (mine != null) {
            hud.line("§fYOUR BALL §e" + mine.colour + "  §fNEXT §e" + (mine.next >= hoops.length ? "the stake" : "hoop " + (mine.next + 1)));
        }
        Ball hers = balls.get(queen);
        if (hers != null) {
            hud.line("§dTHE QUEEN §7" + (hers.next >= hoops.length ? "at the stake" : "hoop " + (hers.next + 1)));
        }
    }

    // --- what a stranger can say they'll do, and then does ---------------------------------------------------

    @Override
    public Map<String, String> options(Contestant who) {
        if (step != Step.TEA || who.getUuid().equals(queen) || !isPlaying(who.getUuid())) {
            return Map.of();
        }
        return Map.of("KEEP_PLAYING", "carry on to the next set", "QUIT", "give up and quit the game, which means the laser");
    }

    @Override
    protected Map<String, String> optionWords(Contestant who) {
        return step == Step.TEA ? Map.of("QUIT", "\\b(quit|quitting|give up|giving up)\\b", "KEEP_PLAYING", "\\b(keep playing|play on|carry on|next set)\\b")
                : Map.of();
    }

    @Override
    public void chose(Contestant who, String option) {
        if (step == Step.TEA && option.equals("QUIT") && isPlaying(who.getUuid()) && !who.getUuid().equals(queen)) {
            quit(who.getUuid());
        }
    }

    @Override
    public String situation(Contestant who) {
        Ball mine = balls.get(who.getUuid());
        return "You're playing CROQUET, the queen of hearts, on a rooftop garden: three sets of croquet against the Queen; finish them all "
                + "and you clear it, quit and you die. Set " + set + " of 3. " + (mine == null ? "" : "Your " + mine.colour + " ball needs "
                + (mine.next >= hoops.length ? "to strike the stake." : "hoop " + (mine.next + 1) + "."))
                + (who.getUuid().equals(queen) ? " You're the Queen: a citizen, calm, and unsettling." : " The Queen keeps whispering that none of this is real.");
    }
}
