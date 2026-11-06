package com.deadmanshand.game;

import com.deadmanshand.Builder;
import com.deadmanshand.Hud;
import com.deadmanshand.npc.Contestant;
import com.deadmanshand.npc.Lines;
import com.deadmanshand.npc.Puppet;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeavesBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.DyeableItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Seven of Hearts, in a glasshouse full of hedges and trees. One of the four is the wolf; the others are lambs; your
 * goggles tell you which. When the wolf and a lamb look each other in the eye, they swap. When the fifteen minutes
 * are up, every lamb's collar goes off, and the wolf walks out alone.
 */
final class WolfAndLambs extends Game {
    private static final int HALF = 24;
    private static final double CONTACT_DEGREES = 14.0;
    private static final double CONTACT_RANGE = 26.0;

    private UUID wolf;
    private int ticks;
    private int lastSwap = -1000;
    private final Map<String, Integer> gazing = new HashMap<>();
    private final List<BlockPos> hides = new ArrayList<>();
    private final List<BlockPos> walks = new ArrayList<>();
    /** Strangers who know (or think they know) who the wolf is. */
    private final Map<UUID, UUID> suspect = new HashMap<>();
    /** Strangers who've decided they won't take your place. */
    private final Set<UUID> sparing = new HashSet<>();
    private final Map<UUID, BlockPos> target = new HashMap<>();

    WolfAndLambs(Kind kind, ServerWorld world, BlockPos origin) {
        super(kind, world, origin);
    }

    @Override
    protected void plan(Builder b) {
        int ox = origin.getX();
        int oy = origin.getY();
        int oz = origin.getZ();
        int x0 = ox - HALF;
        int x1 = ox + HALF;
        int z0 = oz - 2 * HALF;
        int z1 = oz;
        bounds = new Box(x0, oy - 1, z0, x1 + 1, oy + 12, z1 + 1);
        entry = new Box(ox - 2, oy, z1 - 3, ox + 3, oy + 3, z1);
        entryPos = new BlockPos(ox, oy, z1 - 2);
        approach = new BlockPos(ox, oy, z1 + 8);

        BlockState air = Blocks.AIR.getDefaultState();
        BlockState glass = Blocks.GLASS.getDefaultState();
        BlockState frame = Blocks.WHITE_CONCRETE.getDefaultState();
        b.fill(x0, oy, z0, x1, oy + 11, z1, air);
        b.fill(x0, oy - 1, z0, x1, oy - 1, z1, Blocks.GRASS_BLOCK.getDefaultState());
        // The glasshouse: glass walls on white ribs, a glass roof
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                boolean wall = x == x0 || x == x1 || z == z0 || z == z1;
                boolean rib = Math.floorMod(x - x0, 6) == 0 || Math.floorMod(z - z0, 6) == 0;
                if (wall) {
                    b.fill(x, oy, z, x, oy + 9, z, rib ? frame : glass);
                }
                b.set(x, oy + 10, z, rib ? frame : glass);
            }
        }
        b.fill(ox - 1, oy, z1, ox + 1, oy + 2, z1, air);
        // Paths: a ring and a cross
        for (int x = x0 + 1; x < x1; x++) {
            for (int z = z0 + 1; z < z1; z++) {
                int dx = Math.abs(x - ox);
                int dz = Math.abs(z - (oz - HALF));
                boolean ring = dx <= 18 && dz <= 18 && (dx >= 17 || dz >= 17);
                boolean cross = dx <= 1 || dz <= 1;
                if (ring || cross) {
                    b.set(x, oy - 1, z, Blocks.GRAVEL.getDefaultState());
                }
            }
        }
        int cz = oz - HALF;
        // North-west: a hedge maze
        for (int x = x0 + 3; x <= ox - 4; x++) {
            for (int z = z0 + 3; z <= cz - 4; z++) {
                boolean line = Math.floorMod(x - x0, 4) == 3 || Math.floorMod(z - z0, 4) == 3;
                boolean gap = (x * 7 + z * 13) % 5 == 0;
                if (line && !gap && !onPath(x, z, ox, cz)) {
                    b.fill(x, oy, z, x, oy + 2, z, Blocks.AZALEA_LEAVES.getDefaultState().with(LeavesBlock.PERSISTENT, true));
                } else if (!line && Math.floorMod(x + z, 9) == 0) {
                    hides.add(new BlockPos(x, oy, z));
                }
            }
        }
        // North-east: the palm house, tall jungle trees
        for (int i = 0; i < 9; i++) {
            int x = ox + 4 + random.nextInt(HALF - 7);
            int z = z0 + 3 + random.nextInt(HALF - 7);
            if (!onPath(x, z, ox, cz)) {
                tree(b, x, oy, z, Blocks.JUNGLE_LOG.getDefaultState(), Blocks.JUNGLE_LEAVES.getDefaultState().with(LeavesBlock.PERSISTENT, true), 7);
                hides.add(new BlockPos(x + 1, oy, z + 1));
            }
        }
        for (int i = 0; i < 30; i++) {
            int x = ox + 3 + random.nextInt(HALF - 5);
            int z = z0 + 2 + random.nextInt(HALF - 5);
            if (!onPath(x, z, ox, cz)) {
                b.set(x, oy, z, Blocks.FERN.getDefaultState());
            }
        }
        // South-east: the pond with a little bridge
        for (int x = ox + 4; x <= x1 - 3; x++) {
            for (int z = cz + 4; z <= z1 - 3; z++) {
                double dx = (x - (ox + 13)) / 8.0;
                double dz = (z - (cz + 12)) / 6.0;
                if (dx * dx + dz * dz < 1.0) {
                    b.set(x, oy - 1, z, Blocks.WATER.getDefaultState());
                    b.set(x, oy - 2, z, Blocks.SAND.getDefaultState());
                    if (random.nextFloat() < 0.06F) {
                        b.set(x, oy, z, Blocks.LILY_PAD.getDefaultState());
                    }
                }
            }
        }
        for (int x = ox + 5; x <= ox + 21; x++) {
            b.set(x, oy, cz + 12, Blocks.SPRUCE_SLAB.getDefaultState());
        }
        hides.add(new BlockPos(ox + 13, oy, cz + 7));
        // South-west: flower beds and bushes
        for (int x = x0 + 3; x <= ox - 4; x++) {
            for (int z = cz + 4; z <= z1 - 4; z++) {
                if (onPath(x, z, ox, cz)) {
                    continue;
                }
                if (Math.floorMod(z, 3) == 0) {
                    b.set(x, oy, z, (x + z) % 2 == 0 ? Blocks.POPPY.getDefaultState() : Blocks.CORNFLOWER.getDefaultState());
                } else if (random.nextFloat() < 0.05F) {
                    b.fill(x, oy, z, x, oy + 1, z, Blocks.FLOWERING_AZALEA_LEAVES.getDefaultState().with(LeavesBlock.PERSISTENT, true));
                    hides.add(new BlockPos(x, oy, z + 1));
                }
            }
        }
        // Trees along the ring
        for (int i = 0; i < 14; i++) {
            int x = x0 + 3 + random.nextInt(2 * HALF - 5);
            int z = z0 + 3 + random.nextInt(2 * HALF - 5);
            if (!onPath(x, z, ox, cz) && Math.abs(x - ox) > 3) {
                boolean birch = random.nextBoolean();
                tree(b, x, oy, z, birch ? Blocks.BIRCH_LOG.getDefaultState() : Blocks.OAK_LOG.getDefaultState(),
                        (birch ? Blocks.BIRCH_LEAVES : Blocks.OAK_LEAVES).getDefaultState().with(LeavesBlock.PERSISTENT, true), 5);
                hides.add(new BlockPos(x, oy, z + 1));
            }
        }
        // A fountain in the middle, benches and lamps along the paths
        b.fill(ox - 2, oy, cz - 2, ox + 2, oy, cz + 2, Blocks.STONE_BRICKS.getDefaultState());
        b.fill(ox - 1, oy, cz - 1, ox + 1, oy, cz + 1, Blocks.WATER.getDefaultState());
        b.set(ox, oy, cz, Blocks.STONE_BRICK_WALL.getDefaultState());
        b.set(ox, oy + 1, cz, Blocks.SEA_LANTERN.getDefaultState());
        for (int k = -16; k <= 16; k += 8) {
            if (k != 0) {
                b.set(ox + 3, oy, cz + k, Blocks.OAK_STAIRS.getDefaultState().with(StairsBlock.FACING, Direction.EAST));
                b.set(ox - 3, oy, cz + k, Blocks.OAK_STAIRS.getDefaultState().with(StairsBlock.FACING, Direction.WEST));
                b.set(ox + k, oy, cz + 3, Blocks.LANTERN.getDefaultState());
            }
        }
        for (int i = 0; i < 24; i++) {
            walks.add(new BlockPos(x0 + 3 + random.nextInt(2 * HALF - 5), oy, z0 + 3 + random.nextInt(2 * HALF - 5)));
        }
        b.sign(new BlockPos(ox + 2, oy + 1, z1 + 1), Direction.SOUTH, "GAME VENUE", "", "step inside", "to register");
        b.set(ox + 2, oy, z1 + 1, Blocks.WHITE_CONCRETE.getDefaultState());
    }

    private static boolean onPath(int x, int z, int ox, int cz) {
        int dx = Math.abs(x - ox);
        int dz = Math.abs(z - cz);
        return dx <= 2 || dz <= 2 || dx <= 19 && dz <= 19 && (dx >= 16 || dz >= 16);
    }

    private static void tree(Builder b, int x, int y, int z, BlockState log, BlockState leaves, int height) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = height - 2; dy <= height + 1; dy++) {
                    int r = dy > height ? 1 : 2;
                    if (Math.abs(dx) <= r && Math.abs(dz) <= r && !(Math.abs(dx) == 2 && Math.abs(dz) == 2)) {
                        b.set(x + dx, y + dy, z + dz, leaves);
                    }
                }
            }
        }
        b.fill(x, y, z, x, y + height, z, log);
    }

    @Override
    protected String[] rules() {
        return new String[]{
                "One of you is the wolf. The others are lambs. Your goggles tell you which you are.",
                "When the wolf and a lamb look each other in the eye, the lamb becomes the wolf, and the wolf becomes a lamb.",
                "When the time limit runs out, every lamb's collar goes off.",
                "Only the wolf survives.",
                "Time limit: fifteen minutes."};
    }

    @Override
    protected int timeLimit() {
        return 20 * 60 * 15;
    }

    @Override
    protected void arrange() {
        List<LivingEntity> everyone = living();
        BlockPos[] corners = {origin.add(-16, 0, -40), origin.add(16, 0, -40), origin.add(-16, 0, -8), origin.add(16, 0, -8)};
        for (int i = 0; i < everyone.size(); i++) {
            BlockPos at = corners[i % corners.length];
            LivingEntity entity = everyone.get(i);
            entity.teleport(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
            ItemStack goggles = new ItemStack(Items.LEATHER_HELMET);
            ((DyeableItem) Items.LEATHER_HELMET).setColor(goggles, 0x1E1E1E);
            goggles.setCustomName(Text.literal("Goggles").formatted(Formatting.GRAY));
            entity.equipStack(EquipmentSlot.HEAD, goggles);
            if (entity instanceof ServerPlayerEntity player) {
                player.getInventory().offerOrDrop(new ItemStack(Items.SPYGLASS));
            }
            if (entity instanceof Contestant contestant) {
                contestant.hold();
            }
        }
        wolf = everyone.isEmpty() ? null : everyone.get(random.nextInt(everyone.size())).getUuid();
    }

    @Override
    protected void begin() {
        for (UUID id : playing()) {
            LivingEntity entity = entity(id);
            if (entity instanceof ServerPlayerEntity player) {
                tell(player, id.equals(wolf) ? "Your goggles flash red: YOU ARE THE WOLF." : "Your goggles glow white: you are a lamb.",
                        id.equals(wolf) ? Formatting.RED : Formatting.WHITE);
            }
            if (entity instanceof Contestant contestant) {
                contestant.stopAll();
                contestant.remember(id.equals(wolf) ? "At the start of the wolf game, your goggles told you that YOU are the wolf." : "Your goggles told you you're a lamb.");
            }
        }
    }

    @Override
    protected void running() {
        ticks++;
        // Keep the goggles on
        if (ticks % 20 == 0) {
            for (LivingEntity entity : living()) {
                if (entity.getEquippedStack(EquipmentSlot.HEAD).getItem() != Items.LEATHER_HELMET) {
                    ItemStack goggles = new ItemStack(Items.LEATHER_HELMET);
                    ((DyeableItem) Items.LEATHER_HELMET).setColor(goggles, 0x1E1E1E);
                    goggles.setCustomName(Text.literal("Goggles").formatted(Formatting.GRAY));
                    entity.equipStack(EquipmentSlot.HEAD, goggles);
                }
            }
        }
        if (wolf == null || !isPlaying(wolf)) {
            List<UUID> left = playing();
            if (left.isEmpty()) {
                return;
            }
            wolf = left.get(random.nextInt(left.size()));     // the wolf died: the goggles choose again
            announce("The wolf is gone. The goggles have chosen a new wolf.");
            tellRoles();
        }
        LivingEntity theWolf = entity(wolf);
        if (theWolf != null && ticks - lastSwap > 40) {
            for (LivingEntity lamb : living()) {
                if (lamb == theWolf) {
                    continue;
                }
                String key = lamb.getUuid() + "|" + wolf;
                if (eyeContact(theWolf, lamb)) {
                    int held = gazing.merge(key, 1, Integer::sum);
                    if (held >= 3) {
                        swap(theWolf, lamb);
                        gazing.clear();
                        break;
                    }
                } else {
                    gazing.remove(key);
                }
            }
        }
        strangersThink();
        if (timeLeft == 20 * 60 || timeLeft == 20 * 60 * 5 || timeLeft == 20 * 10) {
            announce(timeLeft == 20 * 10 ? "Ten seconds." : timeLeft / (20 * 60) + (timeLeft == 20 * 60 ? " minute left." : " minutes left."));
        }
    }

    /** Both looking straight into each other's eyes, close enough, nothing in between. */
    private boolean eyeContact(LivingEntity a, LivingEntity b) {
        Vec3d eyeA = a.getEyePos();
        Vec3d eyeB = b.getEyePos();
        Vec3d ab = eyeB.subtract(eyeA);
        double distance = ab.length();
        if (distance > CONTACT_RANGE || distance < 0.3) {
            return false;
        }
        Vec3d dir = ab.multiply(1.0 / distance);
        double cos = Math.cos(Math.toRadians(CONTACT_DEGREES));
        if (Puppet.gaze(a).dotProduct(dir) < cos || Puppet.gaze(b).dotProduct(dir.multiply(-1.0)) < cos) {
            return false;
        }
        return world.raycast(new RaycastContext(eyeA, eyeB, RaycastContext.ShapeType.VISUAL, RaycastContext.FluidHandling.NONE, a)).getType()
                == HitResult.Type.MISS;
    }

    private void swap(LivingEntity oldWolf, LivingEntity lamb) {
        wolf = lamb.getUuid();
        lastSwap = ticks;
        announce("The wolf has changed.");
        world.playSound(null, lamb.getX(), lamb.getY(), lamb.getZ(), SoundEvents.ENTITY_WOLF_HOWL, SoundCategory.HOSTILE, 1.2F, 0.9F);
        tellRoles();
        if (lamb instanceof Contestant contestant) {
            contestant.remember("You caught " + nameOf(oldWolf.getUuid()) + "'s eyes in the wolf game: YOU are the wolf now, and they're a lamb.");
            suspect.put(contestant.getUuid(), contestant.getUuid());
            contestant.speak("You just looked " + nameOf(oldWolf.getUuid()) + " in the eye and became the wolf. Now they'll die unless they catch you.",
                    Lines.pick(random, "I'm sorry. I'm so sorry.", "It had to be someone.", "Don't look at me like that."));
            if (oldWolf instanceof ServerPlayerEntity) {
                contestant.trust(oldWolf.getUuid(), -0.3F);
            }
        }
        if (oldWolf instanceof Contestant contestant) {
            contestant.remember(nameOf(lamb.getUuid()) + " caught your eyes in the wolf game. They're the wolf now; you're a lamb.");
            suspect.put(contestant.getUuid(), lamb.getUuid());
            contestant.speak(nameOf(lamb.getUuid()) + " just caught your eyes and took the wolf from you. You're a lamb now: you'll die unless you get it back.",
                    Lines.pick(random, "No! Give it back!", "You did that on purpose.", "I trusted you."));
        }
    }

    private void tellRoles() {
        for (UUID id : playing()) {
            ServerPlayerEntity player = player(id);
            if (player != null) {
                tell(player, id.equals(wolf) ? "Your goggles flash red: YOU ARE THE WOLF." : "Your goggles glow white: you are a lamb.",
                        id.equals(wolf) ? Formatting.RED : Formatting.WHITE);
            }
        }
    }

    private void strangersThink() {
        List<LivingEntity> everyone = living();
        for (Contestant contestant : strangers()) {
            UUID id = contestant.getUuid();
            boolean isWolf = id.equals(wolf);
            LivingEntity nearest = null;
            double nearestDistance = Double.MAX_VALUE;
            for (LivingEntity other : everyone) {
                if (other == contestant) {
                    continue;
                }
                double d = contestant.squaredDistanceTo(other);
                if (d < nearestDistance && contestant.canSee(other)) {
                    nearest = other;
                    nearestDistance = d;
                }
            }
            if (isWolf) {
                // Hide, and never look anyone in the face
                if (nearest != null && nearestDistance < 14.0 * 14.0) {
                    Vec3d away = contestant.getPos().subtract(nearest.getPos()).normalize().multiply(10.0);
                    contestant.lookAt(contestant.getEyePos().add(away));
                    BlockPos spot = farthestHide(nearest);
                    if (!spot.equals(target.get(id))) {
                        target.put(id, spot);
                        contestant.go(spot, 1.3);
                    }
                } else {
                    BlockPos spot = target.get(id);
                    if (spot == null || contestant.arrived(1.0)) {
                        spot = hides.isEmpty() ? origin : hides.get(random.nextInt(hides.size()));
                        target.put(id, spot);
                        contestant.go(spot, 1.0);
                    }
                    if (contestant.arrived(1.5)) {
                        contestant.lookAt(Vec3d.ofCenter(spot).add(random.nextDouble() - 0.5, 0.0, random.nextDouble() - 0.5).add(0, 1.4, 0).add(
                                Vec3d.ofCenter(spot).subtract(Vec3d.ofCenter(origin.add(0, 0, -HALF))).normalize()));
                    }
                }
                continue;
            }
            // A lamb: find the wolf's eyes, unless it has chosen to spare them
            UUID suspected = suspect.get(id);
            LivingEntity mark = suspected != null && !suspected.equals(id) ? entity(suspected) : nearest;
            if (mark != null && sparing.contains(id) && mark.getUuid().equals(suspected)) {
                Vec3d away = contestant.getPos().subtract(mark.getPos()).normalize().multiply(10.0);
                contestant.lookAt(contestant.getEyePos().add(away));
                if (contestant.squaredDistanceTo(mark) < 12.0 * 12.0) {
                    contestant.go(farthestHide(mark), 1.1);
                }
                continue;
            }
            if (mark != null && contestant.canSee(mark)) {
                contestant.look(mark);
                if (contestant.squaredDistanceTo(mark) > 5.0 * 5.0) {
                    contestant.go(mark.getPos(), 1.15);
                } else {
                    contestant.hold();
                }
                if (contestant.quiet() && random.nextFloat() < 0.01F) {
                    contestant.speak("You're trying to catch " + nameOf(mark.getUuid()) + "'s eyes, in case they're the wolf.",
                            Lines.pick(random, "Look at me. Just look at me.", "Are you the wolf? Look me in the eye.", "Why won't you look at me?"));
                }
            } else {
                contestant.look(null);
                BlockPos walk = target.get(id);
                if (walk == null || contestant.arrived(1.5)) {
                    walk = walks.get(random.nextInt(walks.size()));
                    target.put(id, walk);
                    contestant.go(walk, 1.0);
                }
            }
        }
    }

    private BlockPos farthestHide(LivingEntity from) {
        BlockPos best = origin;
        double bestDistance = -1.0;
        for (int i = 0; i < 6 && !hides.isEmpty(); i++) {
            BlockPos spot = hides.get(random.nextInt(hides.size()));
            double distance = from.squaredDistanceTo(Vec3d.ofCenter(spot));
            if (distance > bestDistance) {
                best = spot;
                bestDistance = distance;
            }
        }
        return best;
    }

    @Override
    protected void heard(ServerPlayerEntity player, String message) {
        String lower = message.toLowerCase();
        boolean claimsWolf = lower.matches(".*\\b(i'?m|i am) (the )?wolf\\b.*");
        boolean begs = lower.matches(".*\\b(look at me|take it|catch my eyes|you take it|save yourself)\\b.*");
        if (!claimsWolf && !begs) {
            return;
        }
        for (Contestant contestant : strangers()) {
            if (contestant.squaredDistanceTo(player) > 24.0 * 24.0 || contestant.getUuid().equals(wolf)) {
                continue;
            }
            suspect.put(contestant.getUuid(), player.getUuid());
            boolean spare = contestant.nature == Contestant.Nature.KIND && contestant.trust(player.getUuid()) > 0.2F
                    || contestant.nature == Contestant.Nature.KIND && random.nextFloat() < 0.35F;
            if (spare) {
                sparing.add(contestant.getUuid());
                contestant.speak(player.getEntityName() + " just said they're the wolf and offered to let you take it. You refuse: you won't let them die for you.",
                        Lines.pick(random, "No. You live. I mean it.", "I'm not taking it. Don't ask me again.", "Then hide. Hide from me."));
            } else {
                contestant.speak(player.getEntityName() + " just told you they're the wolf. Taking it means you live and they die.",
                        Lines.pick(random, "Then look at me.", "I'm sorry. I want to live.", "Stand still. Please."));
            }
        }
    }

    @Override
    protected void timeUp() {
        announce("Time is up.");
        for (UUID id : playing()) {
            if (id.equals(wolf)) {
                continue;
            }
            LivingEntity lamb = entity(id);
            if (lamb != null) {
                world.spawnParticles(ParticleTypes.EXPLOSION, lamb.getX(), lamb.getEyeY() - 0.3, lamb.getZ(), 1, 0.0, 0.0, 0.0, 0.0);
                world.playSound(null, lamb.getX(), lamb.getY(), lamb.getZ(), SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 1.0F, 1.4F);
            }
            fail(id, "Your collar went off.");
        }
        if (wolf != null) {
            clear(wolf);
        }
    }

    @Override
    protected void more(ServerPlayerEntity player, Hud hud) {
        if (phase == Phase.RUNNING && isPlaying(player.getUuid())) {
            boolean isWolf = player.getUuid().equals(wolf);
            hud.role(isWolf ? "WOLF" : "LAMB", isWolf ? 0xFF3030 : 0xFFFFFF);
        }
    }

    // --- what a stranger can say they'll do, and then does ---------------------------------------------------

    @Override
    protected boolean followable() {
        return true;
    }

    @Override
    public Map<String, String> options(Contestant who) {
        UUID id = who.getUuid();
        if (id.equals(wolf) || !isPlaying(id)) {
            return Map.of();
        }
        UUID s = suspect.get(id);
        String them = s == null || s.equals(id) ? "whoever you think is the wolf" : nameOf(s);
        return Map.of("SPARE", "let " + them + " stay the wolf: don't look them in the eye, even though it costs you your life",
                "CATCH", "try to catch the wolf's eyes, and take its place");
    }

    @Override
    protected Map<String, String> optionWords(Contestant who) {
        return Map.of("SPARE", "\\b(spare (you|them|him|her)|let (you|them|him|her) (live|stay|be the wolf)|won'?t look)\\b",
                "CATCH", "\\b(catch (its|the wolf'?s|your) eyes|take (its|your) place)\\b");
    }

    @Override
    public void chose(Contestant who, String option) {
        if (option.equals("SPARE")) {
            sparing.add(who.getUuid());
        } else if (option.equals("CATCH")) {
            sparing.remove(who.getUuid());
        }
    }

    @Override
    public String situation(Contestant who) {
        boolean isWolf = who.getUuid().equals(wolf);
        UUID suspected = suspect.get(who.getUuid());
        return "You're playing WOLF AND LAMBS, the seven of hearts, in a glasshouse garden. Four of you wear goggles and collars. One is "
                + "the wolf, the rest lambs. When the wolf and a lamb look each other in the eye, they swap. When time runs out every lamb's "
                + "collar explodes and only the wolf lives. " + (isWolf ? "You are the WOLF right now: you have to keep everyone from looking you in the eye."
                : "You are a LAMB right now: you die unless you catch the wolf's eyes.")
                + (suspected != null && !suspected.equals(who.getUuid()) ? " You think the wolf is " + nameOf(suspected) + "." : "")
                + " " + timeLeft / 20 / 60 + " minutes left.";
    }

    @Override
    protected void ended() {
        for (UUID id : seats.keySet()) {
            LivingEntity entity = entity(id);
            if (entity != null && entity.getEquippedStack(EquipmentSlot.HEAD).getItem() == Items.LEATHER_HELMET
                    && entity.getEquippedStack(EquipmentSlot.HEAD).hasCustomName()) {
                entity.equipStack(EquipmentSlot.HEAD, ItemStack.EMPTY);
            }
        }
    }

    @Override
    protected void died(UUID id) {
        target.remove(id);
    }

    /** For tests: who the wolf is. */
    UUID wolf() {
        return wolf;
    }
}
