package com.deadmanshand.game;

import com.deadmanshand.Builder;
import com.deadmanshand.Hud;
import com.deadmanshand.Visa;
import com.deadmanshand.npc.Contestant;
import com.deadmanshand.npc.Guns;
import com.deadmanshand.npc.Lines;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Ten of Hearts, at the camp. One night a girl is found at the front desk with a knife in her heart, and the speakers
 * announce a game: whoever killed her is the witch; find the witch and throw them into the bonfire. The guard decides
 * to burn everyone they don't need. The truth is in her hands and in her diary: she did it herself. She is the witch,
 * and burning her body ends it for everyone.
 */
final class WitchHunt extends Game {
    private UUID body;
    private String bodyName = "the girl";
    private boolean burned;
    private int purgeAt;
    private int burnedCount;
    private boolean guardTurned;
    private boolean hinted;
    private boolean solvedByStranger;
    private final Set<UUID> attackedGuard = new HashSet<>();
    private int ticks;

    WitchHunt(Kind kind, ServerWorld world, BlockPos origin) {
        super(kind, world, origin);
    }

    @Override
    protected void plan(Builder b) {
        bounds = Camp.area();
        entry = Camp.area();
        entryPos = Camp.reception().south(4);
        approach = Camp.hangout(random);
        if (!Camp.built(world)) {
            Camp.build(b, world, origin);
        }
    }

    @Override
    protected boolean campGame() {
        return true;
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
    protected String[] rules() {
        return new String[]{
                "A girl has been found dead at the front desk, with a knife in her heart.",
                "The one who killed her is the witch. The witch is here, in this camp, and could be a man or a woman.",
                "Find the witch and throw them into the bonfire in the garden.",
                "If the witch burns, everyone left alive clears the game.",
                "Leave the camp and the laser finds you. Time limit: twenty minutes."};
    }

    @Override
    protected int timeLimit() {
        return 20 * 60 * 20;
    }

    @Override
    protected void arrange() {
        // Everyone who lives here is in it
        for (Contestant c : world.getEntitiesByClass(Contestant.class, Camp.area(), c -> c.isAlive() && c.free() && !c.role.isEmpty())) {
            seat(c);
        }
        // She's found at the desk
        Contestant girl = null;
        for (UUID id : playing()) {
            Contestant c = contestant(id);
            if (c != null && c.role.equals("dealer")) {
                girl = c;
                break;
            }
        }
        if (girl == null) {
            girl = Contestant.spawn(world, Camp.reception(), random, "dealer", Camp.reception());
        }
        if (girl != null) {
            seats.remove(girl.getUuid());
            body = girl.getUuid();
            bodyName = girl.name();
            BlockPos at = Camp.reception();
            girl.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 180.0F, 0.0F);
            girl.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
            girl.makeBody();
        }
        titleAll(Text.literal("A SCREAM").formatted(Formatting.RED), Text.literal("from the lobby").formatted(Formatting.GRAY));
        sound(SoundEvents.ENTITY_PLAYER_HURT, 0.7F);
    }

    @Override
    protected void begin() {
        purgeAt = 20 * (80 + random.nextInt(70));
        Contestant leader = Camp.leader(world);
        if (leader != null && isPlaying(leader.getUuid())) {
            leader.speak("A girl from the camp lies dead in the lobby and a game has begun: find the witch who killed her and burn them.",
                    Lines.pick(random, "Nobody leaves. We find the witch, tonight.", "One of you did this. One of you.",
                            "Search the rooms. Everyone stays where the guard can see them."));
        }
    }

    @Override
    protected void running() {
        ticks++;
        Contestant girl = girl();
        // She's carried to the fire, or somehow ends up in it
        if (girl != null && !burned) {
            Entity carrier = girl.getVehicle();
            Vec3d fire = Vec3d.ofBottomCenter(Camp.fire());
            Vec3d at = carrier != null ? carrier.getPos() : girl.getPos();
            if (at.squaredDistanceTo(fire) < 3.2 * 3.2) {
                burnWitch(girl, carrier);
                return;
            }
        }
        if (ticks >= purgeAt) {
            purge();
        }
        strangersThink(girl);
        if (timeLeft == 20 * 60 * 5 || timeLeft == 20 * 60) {
            announce(timeLeft / (20 * 60) + (timeLeft == 20 * 60 ? " minute left." : " minutes left."));
        }
    }

    @Nullable
    private Contestant girl() {
        if (body == null) {
            return null;
        }
        Entity entity = world.getEntity(body);
        return entity instanceof Contestant c ? c : null;
    }

    private void burnWitch(Contestant girl, Entity carrier) {
        burned = true;
        girl.stopRiding();
        BlockPos fire = Camp.fire();
        girl.refreshPositionAndAngles(fire.getX() + 0.5, fire.getY(), fire.getZ() + 0.5, 0.0F, 0.0F);
        world.spawnParticles(ParticleTypes.FLAME, fire.getX() + 0.5, fire.getY() + 1.0, fire.getZ() + 0.5, 80, 0.8, 1.0, 0.8, 0.05);
        world.spawnParticles(ParticleTypes.LARGE_SMOKE, fire.getX() + 0.5, fire.getY() + 2.0, fire.getZ() + 0.5, 40, 0.6, 1.0, 0.6, 0.02);
        world.playSound(null, fire.getX(), fire.getY(), fire.getZ(), SoundEvents.ITEM_FIRECHARGE_USE, SoundCategory.BLOCKS, 2.0F, 0.6F);
        girl.discard();
        String who = carrier instanceof ServerPlayerEntity player ? player.getEntityName() : carrier instanceof Contestant c ? c.name() : "someone";
        announce(who + " has thrown " + bodyName + " into the fire.");
        announce("The witch has burned.");
        for (UUID id : playing()) {
            clear(id);
        }
        finish();
    }

    /** The guard's answer: burn everyone who isn't useful. Later, anyone at all. */
    private void purge() {
        if (ticks == purgeAt) {
            announce("Gunfire in the garden.");
            Contestant chief = null;
            for (Contestant c : strangers()) {
                if (c.role.equals("guard")) {
                    chief = c;
                    break;
                }
            }
            if (chief != null) {
                chief.speak("The guard has decided to find the witch by force: kill everyone who isn't useful and burn the bodies.",
                        Lines.pick(random, "If we burn everyone, we burn the witch. Simple.", "Guards! Anyone who isn't one of us goes on the fire.",
                                "We don't need all of them. Start with the useless ones."));
            }
        }
        if (!guardTurned && timeLeft < timeLimit() * 2 / 5) {
            guardTurned = true;
            announce("The guard has turned on everyone.");
        }
        for (Contestant guard : strangers()) {
            if (!guard.role.equals("guard") || (ticks + guard.getId()) % 25 != 0) {
                continue;
            }
            LivingEntity target = null;
            double best = 30.0 * 30.0;
            for (LivingEntity prey : living()) {
                if (prey == guard || prey instanceof Contestant c && (c.role.equals("guard") || c.role.equals("leader"))) {
                    continue;
                }
                boolean player = prey instanceof ServerPlayerEntity;
                if (player && !guardTurned && !attackedGuard.contains(prey.getUuid())) {
                    continue;
                }
                double d = guard.squaredDistanceTo(prey);
                if (d < best && guard.canSee(prey)) {
                    best = d;
                    target = prey;
                }
            }
            if (target == null) {
                if (guard.arrived(2.0)) {
                    guard.go(Camp.hangout(random), 1.0);
                }
                continue;
            }
            guard.look(target);
            guard.go(target.getBlockPos(), 1.1);
            boolean wasAlive = target.isAlive();
            Guns.shoot(guard, target, random, 5.0F, 0.8);
            if (wasAlive && !target.isAlive() && target instanceof Contestant dead) {
                burnedCount++;
                BlockPos fire = Camp.fire();
                world.spawnParticles(ParticleTypes.FLAME, fire.getX() + 0.5, fire.getY() + 1.0, fire.getZ() + 0.5, 30, 0.6, 0.8, 0.6, 0.03);
                if (burnedCount % 3 == 1) {
                    announce("They're throwing the bodies on the fire. The game goes on.");
                }
                dead.discard();
            }
        }
    }

    private void strangersThink(@Nullable Contestant girl) {
        boolean purging = ticks >= purgeAt;
        for (Contestant c : strangers()) {
            if (c.role.equals("guard")) {
                continue;
            }
            // said they'd carry her to the fire: go and do it
            if ("BURN_HER".equals(told.get(c.getUuid())) && girl != null && girl.getVehicle() == null) {
                c.go(girl.getPos(), 1.2);
                if (c.squaredDistanceTo(girl) < 2.2 * 2.2) {
                    girl.startRiding(c, true);
                    told.remove(c.getUuid());
                }
                continue;
            }
            // Run from the guns
            if (purging) {
                Contestant danger = null;
                for (Contestant g : strangers()) {
                    if (g.role.equals("guard") && g.squaredDistanceTo(c) < 18.0 * 18.0) {
                        danger = g;
                    }
                }
                if (danger != null && !c.role.equals("leader")) {
                    Vec3d away = c.getPos().subtract(danger.getPos()).normalize().multiply(9.0);
                    c.go(new BlockPos(c.getPos().add(away)), 1.35);
                    if (random.nextFloat() < 0.004F) {
                        c.speak("The guard is shooting people and throwing them on the bonfire.", Lines.pick(random, "They're killing everyone!",
                                "Hide! Upstairs!", "This is insane, it's not the witch, it's us!"));
                    }
                    continue;
                }
            }
            // Accusations
            if (random.nextFloat() < 0.0015F) {
                List<Contestant> others = new ArrayList<>(strangers());
                others.remove(c);
                if (!others.isEmpty()) {
                    Contestant accused = others.get(random.nextInt(others.size()));
                    c.speak("Everyone is looking for the witch who killed " + bodyName + ". You suspect " + accused.name() + ".",
                            Lines.pick(random, "It was " + accused.name() + ". I saw them by the desk.", accused.name() + " was up all night. Ask them where.",
                                    "Why is " + accused.name() + " so calm?"));
                }
            }
            // The one who works it out
            if (girl != null && !hinted && c.judgment() > 0.85F && c.squaredDistanceTo(girl) < 6.0 * 6.0 && random.nextFloat() < 0.01F) {
                hinted = true;
                c.speak("You've noticed something: the knife is in " + bodyName + "'s own hand, gripped tight.",
                        Lines.pick(random, "Look at her hand. She's holding the knife herself.", "Nobody stabbed her. Look how she's holding it.",
                                "Wait... what if nobody killed her?"));
            }
            if (girl != null && !solvedByStranger && girl.getVehicle() == null && c.judgment() > 0.92F && timeLeft < timeLimit() / 4
                    && random.nextFloat() < 0.0008F) {
                solvedByStranger = true;
                c.speak("You've understood it: " + bodyName + " killed herself, so she is the witch. Her body has to burn.",
                        "It's her. She's the witch. She did it herself. Help me carry her!");
                girl.startRiding(c, true);
            }
            if (girl != null && girl.getVehicle() == c) {
                c.go(Camp.fire(), 1.1);
                continue;
            }
            if (c.arrived(1.5) && random.nextFloat() < 0.02F) {
                c.go(Camp.hangout(random), 0.8);
            }
        }
    }

    @Override
    protected boolean touched(ServerPlayerEntity player, Entity target) {
        Contestant girl = girl();
        if (girl != null && target == girl) {
            if (girl.getVehicle() == player) {
                girl.stopRiding();
                girl.refreshPositionAndAngles(player.getX(), player.getY(), player.getZ(), player.getYaw(), 0.0F);
                tell(player, "You lay her down.", Formatting.GRAY);
            } else if (girl.getVehicle() == null) {
                girl.startRiding(player, true);
                tell(player, "You lift " + bodyName + " onto your back. The knife is still in her hand.", Formatting.GRAY);
            }
            return true;
        }
        if (target instanceof Contestant c && isPlaying(c.getUuid()) && !c.isBody()) {
            Vec3d fire = Vec3d.ofBottomCenter(Camp.fire());
            if (player.getPos().squaredDistanceTo(fire) < 5.0 * 5.0 && c.getPos().squaredDistanceTo(fire) < 5.0 * 5.0) {
                BlockPos at = Camp.fire();
                c.stopAll();
                c.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0F, 0.0F);
                c.setOnFireFor(10);
                c.damage(DamageSource.IN_FIRE, 1000.0F);
                announce(player.getEntityName() + " has thrown " + c.name() + " into the fire.");
                tell(player, c.name() + " burns. Nothing happens. Whoever the witch is, it wasn't " + c.name() + ".", Formatting.RED);
                return true;
            }
        }
        return false;
    }

    @Override
    protected void died(UUID id) {
        LivingEntity entity = entity(id);
        if (entity instanceof Contestant c) {
            for (Entity passenger : c.getPassengerList()) {
                passenger.stopRiding();
            }
        }
    }

    /** Hitting one of the guard makes you a target. */
    void hurtGuard(ServerPlayerEntity player) {
        attackedGuard.add(player.getUuid());
    }

    @Override
    protected void more(ServerPlayerEntity player, Hud hud) {
        hud.line("§cTHE WITCH IS AMONG YOU");
        Contestant girl = girl();
        if (girl != null && girl.getVehicle() == player) {
            hud.line("§eYou're carrying " + bodyName);
        }
        if (burnedCount > 0) {
            hud.line("§7Bodies on the fire: " + burnedCount);
        }
    }

    // --- what a stranger can say they'll do, and then does ---------------------------------------------------

    @Override
    protected boolean followable() {
        return true;
    }

    @Override
    public Map<String, String> options(Contestant who) {
        if (!isPlaying(who.getUuid()) || who.role.equals("guard")) {
            return Map.of();
        }
        return Map.of("BURN_HER", "pick up the dead girl's body and carry it to the bonfire, because you think she is the witch");
    }

    @Override
    protected Map<String, String> optionWords(Contestant who) {
        return Map.of("BURN_HER", "\\b(burn her|carry her|her body on the fire|she'?s the witch)\\b");
    }

    @Override
    public String situation(Contestant who) {
        return "You're playing WITCH HUNT, the ten of hearts, at the Lagoon, the resort camp where you live. " + bodyName + ", a quiet girl "
                + "from the camp, was found dead at the front desk with a knife in her heart. Whoever killed her is the witch; the witch must "
                + "be thrown into the bonfire in the garden for everyone to clear the game. " + (ticks >= purgeAt
                ? "The guard is shooting people and burning the bodies. " : "") + "You are " + (who.role.isEmpty() ? "a newcomer" : "one of the "
                + who.role + "s") + ". " + timeLeft / (20 * 60) + " minutes left.";
    }

    @Override
    protected void ended() {
        Contestant girl = girl();
        if (girl != null) {
            girl.stopRiding();
            girl.discard();
        }
        if (world.getServer() != null) {
            Visa visa = Visa.get(world.getServer());
            visa.campFallen = true;
            visa.markDirty();
        }
    }
}
