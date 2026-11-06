package com.deadmanshand.game;

import com.deadmanshand.Builder;
import com.deadmanshand.Hud;
import com.deadmanshand.city.City;
import com.deadmanshand.npc.Contestant;
import com.deadmanshand.npc.Guns;
import com.deadmanshand.npc.Lines;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.GhastEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
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

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * King of Spades: there's no venue. The game is the whole city, and the King walks it with an anti-tank rifle, an
 * assault rifle, a pistol and a knife, his blimp hanging over him wherever he goes. You get no weapons, though there
 * are some to be found. Outlive him, or bring him down.
 */
final class Survival extends Game {
    private static final int RANGE = 96;

    private final Map<UUID, Integer> cool = new HashMap<>();
    private UUID king;
    private GhastEntity blimp;
    private BlockPos depotA;
    private BlockPos depotB;
    private Vec3d lastSeen;
    private Vec3d roam;
    private LivingEntity prey;
    private int burst;
    private int rifle;
    private int fire;
    private int knife;
    private int ticks;
    private int oy;

    Survival(Kind kind, ServerWorld world, BlockPos origin) {
        super(kind, world, origin);
    }

    @Override
    protected void plan(Builder b) {
        int ox = origin.getX();
        oy = origin.getY();
        int oz = origin.getZ();
        // until the game starts, the depot is the venue; then it's the city
        bounds = new Box(ox - 24, oy - 2, oz - 47, ox + 26, oy + 12, oz + 3);
        entry = new Box(ox - 3, oy, oz - 4, ox + 4, oy + 3, oz + 1);
        entryPos = new BlockPos(ox, oy, oz - 2);
        approach = new BlockPos(ox, oy, oz + 6);
        depotA = new BlockPos(ox - 6, oy, oz - 20);
        depotB = new BlockPos(ox + 6, oy, oz - 20);
        BlockState air = Blocks.AIR.getDefaultState();
        b.fill(ox - 24, oy, oz - 47, ox + 25, oy + 10, oz + 2, air);
        b.fill(ox - 24, oy - 1, oz - 47, ox + 25, oy - 1, oz + 2, Blocks.GRAY_CONCRETE_POWDER.getDefaultState());
        b.walls(ox - 22, oy, oz - 40, ox + 22, oy + 2, oz + 1, Blocks.IRON_BARS.getDefaultState());
        b.fill(ox - 2, oy, oz + 1, ox + 2, oy + 2, oz + 1, air);
        // A bunker: the King's armoury, left open
        b.fill(ox - 10, oy - 1, oz - 30, ox + 10, oy + 5, oz - 14, Blocks.GRAY_CONCRETE.getDefaultState());
        b.fill(ox - 9, oy, oz - 29, ox + 9, oy + 4, oz - 15, air);
        b.fill(ox - 2, oy, oz - 14, ox + 2, oy + 3, oz - 14, air);
        b.fill(ox - 9, oy + 4, oz - 29, ox + 9, oy + 4, oz - 15, Blocks.GRAY_CONCRETE.getDefaultState());
        b.set(ox, oy + 3, oz - 22, Blocks.REDSTONE_LAMP.getDefaultState());
        b.set(ox, oy + 4, oz - 22, Blocks.REDSTONE_BLOCK.getDefaultState());
        b.set(depotA, Blocks.CHEST.getDefaultState());
        b.set(depotB, Blocks.CHEST.getDefaultState());
        b.then(this::stock);
        // Sandbags and crates in the yard
        for (int k = 0; k < 8; k++) {
            int x = ox - 18 + Math.floorMod(k * 11, 36);
            int z = oz - 38 + Math.floorMod(k * 7, 20);
            b.fill(x, oy, z, x + 2, oy, z, Blocks.SMOOTH_SANDSTONE.getDefaultState());
        }
        for (int k = 0; k < 5; k++) {
            b.set(ox - 16 + 8 * k, oy, oz - 8, Blocks.BARREL.getDefaultState());
        }
        // A spade painted on the bunker roof, for the blimp to see
        int[][] spade = {{0, -3}, {-1, -2}, {0, -2}, {1, -2}, {-2, -1}, {-1, -1}, {0, -1}, {1, -1}, {2, -1}, {-2, 0}, {-1, 0}, {0, 0}, {1, 0}, {2, 0},
                {-1, 1}, {1, 1}, {0, 1}, {0, 2}, {-1, 3}, {0, 3}, {1, 3}};
        for (int[] p : spade) {
            b.set(ox + p[0], oy + 5, oz - 22 + p[1], Blocks.BLACK_CONCRETE.getDefaultState());
        }
        b.sign(new BlockPos(ox + 3, oy + 2, oz + 2), Direction.SOUTH, "GAME VENUE", "", "step inside", "to register");
        b.fill(ox + 3, oy, oz + 1, ox + 3, oy + 2, oz + 1, Blocks.IRON_BLOCK.getDefaultState());
    }

    private void stock() {
        if (world.getBlockEntity(depotA) instanceof ChestBlockEntity a) {
            a.setStack(10, new ItemStack(Items.BOW));
            a.setStack(11, new ItemStack(Items.ARROW, 32));
            a.setStack(13, new ItemStack(Items.IRON_SWORD));
            a.setStack(15, new ItemStack(Items.SHIELD));
            a.setStack(16, new ItemStack(Items.BREAD, 8));
        }
        if (world.getBlockEntity(depotB) instanceof ChestBlockEntity b) {
            b.setStack(10, new ItemStack(Items.CROSSBOW));
            b.setStack(11, new ItemStack(Items.ARROW, 24));
            b.setStack(13, new ItemStack(Items.IRON_CHESTPLATE));
            b.setStack(15, new ItemStack(Items.STONE_AXE));
            b.setStack(16, new ItemStack(Items.BREAD, 8));
        }
    }

    @Override
    protected String[] rules() {
        return new String[]{
                "Survival. The game is the whole city: there is no venue.",
                "The King of Spades is coming for you. He is armed. You are not, though weapons can be found.",
                "Defeat the King of Spades, or survive until the game ends, and you clear it."};
    }

    @Override
    protected int timeLimit() {
        return 25 * 60 * 20;
    }

    @Override
    protected void arrange() {
        stock();
        int bx = Math.floorDiv(origin.getX(), City.PITCH);
        int bz = Math.floorDiv(origin.getZ(), City.PITCH);
        BlockPos at = new BlockPos((bx + 2) * City.PITCH + City.ROAD / 2, oy, bz * City.PITCH + City.ROAD / 2);
        Contestant k = citizen("King of Spades", at);
        if (k != null) {
            king = k.getUuid();
            k.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH).setBaseValue(200.0);
            k.setHealth(200.0F);
            k.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.CROSSBOW));
            k.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
            k.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
            k.hold();
            GhastEntity g = EntityType.GHAST.create(world);
            if (g != null) {
                g.setAiDisabled(true);
                g.setNoGravity(true);
                g.setSilent(true);
                g.setInvulnerable(true);
                g.setPersistent();
                g.setCustomName(Text.literal("♠").formatted(Formatting.DARK_GRAY));
                g.refreshPositionAndAngles(at.getX() + 0.5, oy + 24, at.getZ() + 0.5, 0.0F, 0.0F);
                world.spawnEntity(g);
                blimp = g;
            }
        }
        for (Contestant c : strangers()) {
            if (!c.getUuid().equals(king)) {
                c.hold();
            }
        }
    }

    @Override
    protected void begin() {
        int cx = origin.getX();
        int cz = origin.getZ();
        bounds = new Box(cx - 640, oy - 40, cz - 640, cx + 640, oy + 200, cz + 640);
        for (Contestant c : strangers()) {
            c.stopAll();
        }
        world.playSound(null, origin.getX(), oy, origin.getZ(), SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.HOSTILE, 3.0F, 0.6F);
        Contestant k = contestant(king);
        if (k != null) {
            k.stopAll();
            k.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, timeLimit(), 0, false, false));
        }
    }

    @Override
    protected void running() {
        ticks++;
        Contestant k = contestant(king);
        if (king != null && (k == null || !isPlaying(king))) {
            announce("The King of Spades is dead.");
            if (blimp != null) {
                blimp.discard();
                blimp = null;
            }
            for (java.util.UUID id : playing()) {
                clear(id);
            }
            finish();
            return;
        }
        if (k == null) {
            return;
        }
        if (blimp != null && !blimp.isRemoved()) {
            blimp.refreshPositionAndAngles(k.getX(), Math.max(k.getY(), oy) + 24, k.getZ(), k.getYaw(), 0.0F);
            if (ticks % 40 == 0) {
                world.playSound(null, blimp.getX(), blimp.getY(), blimp.getZ(), SoundEvents.ITEM_ELYTRA_FLYING, SoundCategory.AMBIENT, 0.6F, 0.5F);
            }
        }
        hunt(k);
        for (Contestant c : strangers()) {
            if (!c.getUuid().equals(king)) {
                hide(c, k);
            }
        }
    }

    // --- the King ---------------------------------------------------------------------------------------------

    private void hunt(Contestant k) {
        if (ticks % 20 == 0 || prey == null || !prey.isAlive() || !isPlaying(prey.getUuid())) {
            // whoever he can see, nearest first; failing that, whoever's nearest
            prey = null;
            double bestSeen = RANGE * RANGE;
            double bestAny = RANGE * RANGE;
            LivingEntity any = null;
            for (LivingEntity e : living()) {
                if (e == k) {
                    continue;
                }
                double d = k.squaredDistanceTo(e);
                if (d < bestSeen && k.canSee(e)) {
                    bestSeen = d;
                    prey = e;
                }
                if (d < bestAny) {
                    bestAny = d;
                    any = e;
                }
            }
            if (prey == null) {
                prey = any;
            }
        }
        burst = Math.max(0, burst - 1);
        rifle = Math.max(0, rifle - 1);
        knife = Math.max(0, knife - 1);
        if (prey == null) {
            if (lastSeen != null && k.squaredDistanceTo(lastSeen) > 4) {
                toward(k, lastSeen, 1.0);
            } else {
                lastSeen = null;
                if (roam == null || k.squaredDistanceTo(roam) < 9 || ticks % 600 == 0) {
                    roam = new Vec3d(origin.getX() + random.nextInt(161) - 80, oy, origin.getZ() + random.nextInt(161) - 80);
                }
                toward(k, roam, 0.9);
            }
            return;
        }
        double d = Math.sqrt(k.squaredDistanceTo(prey));
        boolean seen = k.canSee(prey);
        if (!seen) {
            toward(k, prey.getPos(), 1.1);
            return;
        }
        lastSeen = prey.getPos();
        k.look(prey);
        if (d < 2.5) {
            if (knife == 0) {
                knife = 14;
                k.swingHand(Hand.MAIN_HAND);
                prey.damage(DamageSource.mob(k), 9.0F);
            }
            k.hold();
            return;
        }
        if (d > 36) {
            // the anti-tank rifle
            k.hold();
            if (rifle == 0) {
                rifle = 70;
                world.playSound(null, k.getX(), k.getY(), k.getZ(), SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 2.5F, 1.8F);
                Guns.shoot(k, prey, random, 40.0F, 3.0);
            }
            if (d > 70) {
                toward(k, prey.getPos(), 1.0);
            }
            return;
        }
        // the assault rifle, in bursts
        if (d > 14) {
            toward(k, prey.getPos(), 0.9);
        } else {
            k.hold();
        }
        if (burst == 0) {
            burst = 30 + random.nextInt(20);
            fire = 4;
        }
        if (fire > 0 && ticks % 3 == 0) {
            fire--;
            Guns.shoot(k, prey, random, 4.5F, 1.2);
        }
        if (ticks % 200 == 0 && random.nextFloat() < 0.4F) {
            k.say(Lines.pick(random, "Run, then.", "I can see you.", "There's nowhere in this city I won't find you.", "Hold still."));
        }
    }

    /** Walk {@code k} towards a far point a stretch at a time. */
    private static void toward(Contestant k, Vec3d to, double speed) {
        Vec3d step = to.subtract(k.getPos());
        double len = Math.sqrt(step.horizontalLengthSquared());
        if (len > 24) {
            to = k.getPos().add(step.multiply(24 / len));
        }
        k.go(to, speed);
    }

    // --- everyone else ---------------------------------------------------------------------------------------------

    private void hide(Contestant c, Contestant k) {
        double d = Math.sqrt(c.squaredDistanceTo(k));
        boolean seen = k.canSee(c);
        int ready = cool.merge(c.getUuid(), -1, Integer::sum);
        String said = told.get(c.getUuid());
        boolean fighter = !c.getMainHandStack().isEmpty() && ("FIGHT".equals(said) || !"HIDE".equals(said) && c.nerve() > 0.6F);
        if (fighter && d < 3.0) {
            c.look(k);
            if (ready <= 0) {
                cool.put(c.getUuid(), 14);
                c.swingHand(Hand.MAIN_HAND);
                k.damage(DamageSource.mob(c), 6.0F);
            }
            c.go(k.getPos(), 1.2);
            return;
        }
        if (d < 45 && (seen || d < 15)) {
            Vec3d away = c.getPos().subtract(k.getPos()).multiply(1, 0, 1).normalize().multiply(16);
            c.go(c.getPos().add(away), 1.35);
            if (random.nextFloat() < 0.01F) {
                c.say(Lines.pick(random, "He's here!", "Get down!", "Run!"));
            }
            return;
        }
        if (fighter && d < 20) {
            c.go(k.getPos(), 1.1);
            return;
        }
        // arm yourself if you can, then keep your head down
        if (c.getMainHandStack().isEmpty() && c.squaredDistanceTo(Vec3d.ofCenter(depotA)) < 30 * 30 && (c.nature == Contestant.Nature.BRAVE
                || c.nature == Contestant.Nature.RECKLESS || c.nature == Contestant.Nature.CLEVER)) {
            if (c.squaredDistanceTo(Vec3d.ofCenter(depotA)) < 2.5 * 2.5) {
                c.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
            } else {
                c.go(depotA.south(1), 1.1);
            }
            return;
        }
        if (c.arrived(1.5) || ticks % 400 == 0) {
            c.go(c.getPos().add(random.nextInt(21) - 10, 0, random.nextInt(21) - 10), 0.8);
        }
    }

    @Override
    protected void timeUp() {
        announce("The King of Spades withdraws. Everyone still alive has survived.");
        if (blimp != null) {
            blimp.discard();
            blimp = null;
        }
        Contestant k = contestant(king);
        for (UUID id : playing()) {
            if (!id.equals(king)) {
                clear(id);
            }
        }
        if (king != null) {
            seats.put(king, Fate.CLEARED);
        }
        if (k != null) {
            k.discard();
        }
        finish();
    }

    @Override
    protected void ended() {
        if (blimp != null) {
            blimp.discard();
            blimp = null;
        }
    }

    // --- show ---------------------------------------------------------------------------------------------

    @Override
    protected void more(ServerPlayerEntity player, Hud hud) {
        LivingEntity k = entity(king);
        if (k != null) {
            double d = Math.sqrt(player.squaredDistanceTo(k));
            hud.line("§fTHE KING §c" + Math.round(k.getHealth()) + " hp §7" + (d < 30 ? "§4close" : Math.round(d) + "m away"));
        }
        hud.line("§fALIVE §e" + Math.max(0, playing().size() - 1));
    }

    // --- what a stranger can say they'll do, and then does ---------------------------------------------------

    @Override
    protected boolean followable() {
        return true;
    }

    @Override
    public Map<String, String> options(Contestant who) {
        return isPlaying(who.getUuid()) && !who.getUuid().equals(king) ? Map.of("FIGHT", "go after the King of Spades with your weapon",
                "HIDE", "keep out of his sight and hide") : Map.of();
    }

    @Override
    protected Map<String, String> optionWords(Contestant who) {
        return Map.of("FIGHT", "\\b(go after him|take him (on|down)|fight (him|back)|kill him)\\b", "HIDE", "\\b(hide|hiding|stay out of sight|keep down)\\b");
    }

    @Override
    public String situation(Contestant who) {
        if (who.getUuid().equals(king)) {
            return "You're the King of Spades, a citizen of this place, walking the empty city with an anti-tank rifle and an assault rifle, "
                    + "hunting every challenger in it.";
        }
        return "You're playing SURVIVAL, the king of spades: the whole city is the venue and a masked gunman, the King of Spades, is "
                + "hunting everyone with rifles, his blimp hanging over him. Survive him or bring him down.";
    }
}
