package com.deadmanshand.game;

import com.deadmanshand.Builder;
import com.deadmanshand.Hud;
import com.deadmanshand.npc.Contestant;
import com.deadmanshand.npc.Lines;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Jack of Spades, in a dojo: a bare wooden hall, dark except over the middle. The Jack and his crew come at everyone with
 * pipes and blades; melee weapons are allowed. Defeat the Jack and everyone left standing clears it. Die and you don't.
 */
final class Champion extends Game {
    private final Set<UUID> crew = new HashSet<>();
    private final Map<UUID, Integer> swing = new HashMap<>();
    private UUID jack;
    private BlockPos rack;
    private int ticks;

    Champion(Kind kind, ServerWorld world, BlockPos origin) {
        super(kind, world, origin);
    }

    @Override
    protected void plan(Builder b) {
        int ox = origin.getX();
        int oy = origin.getY();
        int oz = origin.getZ();
        bounds = new Box(ox - 19, oy - 2, oz - 41, ox + 20, oy + 12, oz + 3);
        entry = new Box(ox - 3, oy, oz - 5, ox + 4, oy + 3, oz + 1);
        entryPos = new BlockPos(ox, oy, oz - 3);
        approach = new BlockPos(ox, oy, oz + 6);
        rack = new BlockPos(ox + 3, oy, oz - 8);
        BlockState wood = Blocks.SPRUCE_PLANKS.getDefaultState();
        BlockState air = Blocks.AIR.getDefaultState();
        b.fill(ox - 18, oy - 1, oz - 40, ox + 18, oy + 10, oz - 6, wood);
        b.fill(ox - 17, oy, oz - 39, ox + 17, oy + 8, oz - 7, air);
        b.fill(ox - 17, oy - 1, oz - 39, ox + 17, oy - 1, oz - 7, Blocks.STRIPPED_OAK_LOG.getDefaultState());
        // The ring on the floor, and the wooden bars round the walls
        for (int a = 0; a < 360; a += 3) {
            double r = Math.toRadians(a);
            b.set(ox + (int) Math.round(Math.cos(r) * 13), oy - 1, oz - 23 + (int) Math.round(Math.sin(r) * 11), Blocks.DARK_OAK_PLANKS.getDefaultState());
        }
        for (int x = ox - 16; x <= ox + 16; x += 2) {
            b.fill(x, oy, oz - 39, x, oy + 5, oz - 39, Blocks.SPRUCE_FENCE.getDefaultState());
            b.fill(x, oy, oz - 7, x, oy + 5, oz - 7, Blocks.SPRUCE_FENCE.getDefaultState());
        }
        for (int z = oz - 37; z <= oz - 9; z += 2) {
            b.fill(ox - 17, oy, z, ox - 17, oy + 5, z, Blocks.SPRUCE_FENCE.getDefaultState());
            b.fill(ox + 17, oy, z, ox + 17, oy + 5, z, Blocks.SPRUCE_FENCE.getDefaultState());
        }
        b.fill(ox - 17, oy + 6, oz - 39, ox + 17, oy + 6, oz - 39, Blocks.WHITE_WOOL.getDefaultState());
        // Light only over the middle
        b.fill(ox - 1, oy + 8, oz - 24, ox + 1, oy + 8, oz - 22, Blocks.GLOWSTONE.getDefaultState());
        b.set(ox, oy + 7, oz - 23, Blocks.LANTERN.getDefaultState().with(net.minecraft.block.LanternBlock.HANGING, true));
        // A rack by the door for anyone who came empty-handed
        b.set(rack, Blocks.CHEST.getDefaultState());
        b.then(this::stockRack);
        // The entrance
        b.fill(ox - 4, oy - 1, oz - 6, ox + 4, oy + 4, oz + 1, wood);
        b.fill(ox - 3, oy, oz - 5, ox + 3, oy + 3, oz, air);
        b.fill(ox - 1, oy, oz + 1, ox + 1, oy + 2, oz + 1, air);
        b.fill(ox - 1, oy, oz - 6, ox + 1, oy + 2, oz - 6, air);
        b.set(ox, oy + 3, oz - 3, Blocks.LANTERN.getDefaultState());
        b.sign(new BlockPos(ox, oy + 3, oz + 2), Direction.SOUTH, "GAME VENUE", "", "step inside", "to register");
        // The roof: dark tiles, upturned at the corners
        b.fill(ox - 19, oy + 10, oz - 41, ox + 19, oy + 10, oz - 5, Blocks.DEEPSLATE_TILES.getDefaultState());
        b.fill(ox - 16, oy + 11, oz - 38, ox + 16, oy + 11, oz - 8, Blocks.DEEPSLATE_TILES.getDefaultState());
    }

    private void stockRack() {
        if (world.getBlockEntity(rack) instanceof ChestBlockEntity chest) {
            chest.setStack(11, new ItemStack(Items.IRON_SWORD));
            chest.setStack(12, new ItemStack(Items.STONE_SWORD));
            chest.setStack(13, new ItemStack(Items.IRON_AXE));
            chest.setStack(14, new ItemStack(Items.STONE_AXE));
            chest.setStack(15, new ItemStack(Items.WOODEN_SWORD));
        }
    }

    @Override
    protected String[] rules() {
        return new String[]{
                "Defend yourselves against the Jack of Spades and his men.",
                "Melee weapons are allowed. There's a rack by the door.",
                "Defeat the Jack of Spades and everyone left standing clears the game.",
                "If you die, it's game over."};
    }

    @Override
    protected int timeLimit() {
        return 20 * 60 * 20;
    }

    @Override
    protected void arrange() {
        stockRack();
        int ox = origin.getX();
        int oz = origin.getZ();
        Contestant j = citizen("Gen", new BlockPos(ox, origin.getY(), oz - 34));
        if (j != null) {
            jack = j.getUuid();
            arm(j, Items.IRON_SWORD, 60.0);
        }
        for (int k = 0; k < 4; k++) {
            Contestant man = citizen(new String[]{"Dai", "Ryo", "Sho", "Tetsu"}[k], new BlockPos(ox - 6 + 4 * k, origin.getY(), oz - 32));
            if (man != null) {
                crew.add(man.getUuid());
                arm(man, k % 2 == 0 ? Items.IRON_AXE : Items.STONE_SWORD, 26.0);
            }
        }
        int i = 0;
        for (UUID id : playing()) {
            LivingEntity entity = entity(id);
            if (entity == null || id.equals(jack) || crew.contains(id)) {
                continue;
            }
            entity.teleport(ox - 4.5 + (i % 10), origin.getY(), oz - 12.5 - (i / 10));
            if (entity instanceof Contestant c) {
                c.hold();
                if (c.nature == Contestant.Nature.BRAVE || c.nature == Contestant.Nature.RECKLESS) {
                    c.equipStack(EquipmentSlot.MAINHAND, new ItemStack(random.nextBoolean() ? Items.IRON_SWORD : Items.STONE_AXE));
                }
            }
            i++;
        }
    }

    private void arm(Contestant c, net.minecraft.item.Item weapon, double health) {
        c.equipStack(EquipmentSlot.MAINHAND, new ItemStack(weapon));
        c.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH).setBaseValue(health);
        c.setHealth((float) health);
        c.hold();
    }

    @Override
    protected void begin() {
        Contestant j = contestant(jack);
        if (j != null) {
            j.speak("You're the Jack of Spades, a citizen of this place, and the brawl in your dojo has just begun.",
                    Lines.pick(random, "Come on, then. All of you.", "Let's see who's still standing.", "Nobody leaves this room on their feet."));
        }
        for (Contestant c : strangers()) {
            c.stopAll();
        }
    }

    private boolean theirs(UUID id) {
        return id.equals(jack) || crew.contains(id);
    }

    @Override
    protected void running() {
        ticks++;
        if (jack != null && !isPlaying(jack)) {
            announce("The Jack of Spades is down.");
            for (UUID id : crew) {
                fail(id, null);
            }
            for (UUID id : playing()) {
                clear(id);
            }
            finish();
            return;
        }
        for (Contestant c : strangers()) {
            fight(c);
        }
        boolean anyone = false;
        for (UUID id : playing()) {
            anyone |= !theirs(id);
        }
        if (!anyone) {
            finish();
        }
    }

    /** Close in and hit, or keep away, depending on who you are. */
    private void fight(Contestant c) {
        UUID id = c.getUuid();
        boolean ours = theirs(id);
        LivingEntity target = null;
        double best = Double.MAX_VALUE;
        for (LivingEntity other : living()) {
            if (other == c || theirs(other.getUuid()) == ours) {
                continue;
            }
            // the strangers go for the Jack once they've nerve; his men go for anyone
            double d = c.squaredDistanceTo(other);
            if (!ours && other.getUuid().equals(jack)) {
                d *= 0.6;
            }
            if (d < best) {
                best = d;
                target = other;
            }
        }
        if (target == null) {
            return;
        }
        String said = told.get(id);
        boolean fighter = ours || "FIGHT".equals(said) || !"HIDE".equals(said) && (c.nerve() > 0.55F || c.getHealth() > 14
                && c.nature == Contestant.Nature.CLEVER);
        if (!fighter) {
            if (best < 8 * 8) {
                Vec3d away = c.getPos().subtract(target.getPos()).normalize().multiply(6.0);
                c.go(new BlockPos(c.getPos().add(away)), 1.3);
            }
            return;
        }
        c.look(target);
        c.go(target.getBlockPos(), ours ? 1.2 : 1.15);
        int ready = swing.merge(id, -1, Integer::sum);
        if (best < 2.4 * 2.4 && ready <= 0) {
            swing.put(id, id.equals(jack) ? 12 : 16);
            c.swingHand(Hand.MAIN_HAND);
            float damage = id.equals(jack) ? 7.0F : ours ? 5.0F : c.getMainHandStack().isEmpty() ? 2.0F : 5.0F;
            target.damage(DamageSource.mob(c), damage);
            if (!target.isAlive() && random.nextFloat() < 0.5F) {
                c.say(Lines.pick(random, ours ? "Next." : "Got him!", ours ? "Who's next?" : "Keep going!", "Stay down."));
            }
        }
    }

    @Override
    protected void more(ServerPlayerEntity player, Hud hud) {
        LivingEntity j = entity(jack);
        hud.line("§fTHE JACK " + (j != null && j.isAlive() ? "§c" + Math.round(j.getHealth()) + " hp" : "§adown"));
        int men = 0;
        for (UUID id : crew) {
            if (isPlaying(id)) {
                men++;
            }
        }
        hud.line("§fHIS MEN §e" + men);
    }

    // --- what a stranger can say they'll do, and then does ---------------------------------------------------

    @Override
    protected boolean followable() {
        return true;
    }

    @Override
    public Map<String, String> options(Contestant who) {
        return isPlaying(who.getUuid()) && !theirs(who.getUuid()) ? Map.of("FIGHT", "fight the Jack and his men", "HIDE", "keep out of their reach")
                : Map.of();
    }

    @Override
    protected Map<String, String> optionWords(Contestant who) {
        return Map.of("FIGHT", "\\b(fight (them|him|back)|take (them|him) on|go for the jack)\\b", "HIDE", "\\b(keep away|hide|hiding|stay back)\\b");
    }

    @Override
    public String situation(Contestant who) {
        return "You're playing CHAMPION, the jack of spades, in a dark dojo: the Jack of Spades and his four men are attacking everyone "
                + "with weapons. Defeat the Jack and everyone left clears it. " + (theirs(who.getUuid()) ? "You're on the Jack's side, a citizen."
                : "You're fighting for your life.");
    }
}
