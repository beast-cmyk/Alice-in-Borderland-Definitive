package com.deadmanshand.npc;

import com.deadmanshand.Borderland;
import com.deadmanshand.DeadMansHand;
import com.deadmanshand.Laser;
import com.deadmanshand.Visa;
import com.deadmanshand.game.Game;
import com.deadmanshand.game.Games;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ai.goal.LookAroundGoal;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.ai.goal.WanderAroundFarGoal;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Another player: someone who woke up over there the same night you did. They have a name, a past, a nature and a
 * visa of their own; they remember you, and how you treated them. Between games they wander; when a venue lights up
 * they come.
 */
public class Contestant extends Puppet {
    private static final TrackedData<Integer> SKIN = DataTracker.registerData(Contestant.class, TrackedDataHandlerRegistry.INTEGER);
    /** A body: lies still where it fell (and is drawn lying down). */
    private static final TrackedData<Boolean> BODY = DataTracker.registerData(Contestant.class, TrackedDataHandlerRegistry.BOOLEAN);
    public static final int SKINS = 16;

    public enum Nature {
        BRAVE("brave: you act when others freeze"),
        TIMID("timid: you scare easily and hate deciding"),
        SELFISH("selfish: you look after yourself first, and you're not ashamed of it"),
        KIND("kind: you'd rather save someone than yourself"),
        CLEVER("clever and calm: you think before you move"),
        RECKLESS("reckless: you act first and think later");

        public final String text;

        Nature(String text) {
            this.text = text;
        }
    }

    private static final String[] NAMES = {"Haruto", "Mei", "Daichi", "Sora", "Ren", "Yui", "Kenji", "Aiko", "Riku", "Hana", "Kaito", "Emi",
            "Sho", "Rina", "Jun", "Mika", "Hiro", "Saki", "Tomo", "Nao", "Kei", "Aya", "Yuto", "Miyu", "Marco", "Lena", "Tariq", "Priya",
            "Owen", "Ines", "Dev", "Zara", "Felix", "Noor", "Mateo", "Ivy"};
    private static final String[] PASTS = {"a convenience-store clerk", "a nurse", "a delivery rider", "a university student", "an office worker",
            "a high-school student", "a cook", "a taxi driver", "a programmer", "a hairdresser", "a builder", "a bartender", "a teacher",
            "a waiter", "out of work", "a musician", "a personal trainer", "an accountant", "a security guard", "a shop owner"};

    public Nature nature = Nature.KIND;
    /** How sharp they are, 0 to 1: who solves a riddle, who panics. */
    public float wits = 0.5F;
    public int years = 25;
    public String past = "an office worker";
    public int visa = Visa.DAY * 2;
    public int cleared;
    /** The game they're playing (or waiting for), or -1. */
    public int gameId = -1;
    /** At the camp: "resident", "guard", "leader" or "dealer"; empty for anyone out on the streets. */
    public String role = "";
    /** Where they live (the camp); they don't wander far from it. */
    @Nullable
    public BlockPos home;
    private final Map<UUID, Float> trust = new HashMap<>();
    /** The last player who talked to them, and when: whoever "come with me" means. */
    @Nullable
    private UUID talkedTo;
    private long talkedAt;
    /** Fear, grief, anger, hope: what they're feeling, and what it does to them. */
    public final Feelings feelings = new Feelings();
    private final Deque<String> memories = new ArrayDeque<>();
    private int quietUntil;

    public Contestant(EntityType<? extends Contestant> type, World world) {
        super(type, world);
    }

    public static DefaultAttributeContainer.Builder attributes() {
        return MobEntity.createMobAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 20.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.3)
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 64.0);
    }

    @Override
    protected void initDataTracker() {
        super.initDataTracker();
        dataTracker.startTracking(SKIN, 0);
        dataTracker.startTracking(BODY, false);
    }

    public boolean isBody() {
        return dataTracker.get(BODY);
    }

    /** From now on just a body: no will, no voice, lying where it is. */
    public void makeBody() {
        dataTracker.set(BODY, true);
        stopAll();
        setAiDisabled(true);
        setSilent(true);
        setInvulnerable(true);
    }

    public void talkedTo(UUID player, long now) {
        talkedTo = player;
        talkedAt = now;
    }

    /** Whoever talked to them in the last minute, if anyone. */
    @Nullable
    public UUID lastTalkedTo(long now) {
        return talkedTo != null && now - talkedAt < 20 * 60 ? talkedTo : null;
    }

    /** How sharp they are right now: their wits, clouded by fear and grief, steadied a little by hope. */
    public float judgment() {
        float calm = 1.0F - 0.5F * Math.max(0.0F, feelings.fear - 0.3F) - 0.3F * feelings.grief;
        return Math.max(0.05F, Math.min(1.0F, wits * calm + 0.05F * feelings.hope));
    }

    /** How ready they are to take a risk right now: who they are, then fear holding them back, anger and grief pushing them. */
    public float nerve() {
        float base = switch (nature) {
            case RECKLESS -> 0.85F;
            case BRAVE -> 0.7F;
            case CLEVER -> 0.5F;
            case KIND -> 0.45F;
            case SELFISH -> 0.4F;
            case TIMID -> 0.2F;
        };
        return Math.max(0.0F, Math.min(1.0F, base - 0.5F * feelings.fear + 0.3F * feelings.anger + 0.25F * feelings.grief + 0.1F * feelings.hope));
    }

    /** Frozen with fear this moment: no moving, whatever they were told. */
    @Override
    public boolean paralysed() {
        return feelings.frozen > 0;
    }

    @Override
    public boolean damage(DamageSource source, float amount) {
        boolean hurt = super.damage(source, amount);
        if (hurt && !world.isClient && isAlive()) {
            feelings.hurt(this, source, amount);
        }
        return hurt;
    }

    public boolean free() {
        return gameId < 0 && !isBody();
    }

    @Override
    protected boolean busy() {
        return gameId >= 0 && !isBody();
    }

    @Override
    protected double hurry() {
        return 1.0 + 0.25 * Math.max(0.0F, feelings.fear - 0.5F);
    }

    @Override
    protected void idleGoals() {
        goalSelector.add(5, new WanderAroundFarGoal(this, 0.6));
        goalSelector.add(6, new LookAtEntityGoal(this, PlayerEntity.class, 10.0F));
        goalSelector.add(7, new LookAroundGoal(this));
    }

    public int skin() {
        return Math.floorMod(dataTracker.get(SKIN), SKINS);
    }

    @Nullable
    public static Contestant spawn(ServerWorld world, BlockPos pos, Random random) {
        return spawn(world, pos, random, "", null);
    }

    /** Someone new at {@code pos}; with a role and a home if they belong somewhere (the camp). */
    @Nullable
    public static Contestant spawn(ServerWorld world, BlockPos pos, Random random, String role, @Nullable BlockPos home) {
        Contestant contestant = DeadMansHand.CONTESTANT.create(world);
        if (contestant == null) {
            return null;
        }
        contestant.refreshPositionAndAngles(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, random.nextFloat() * 360.0F, 0.0F);
        contestant.randomize(random);
        contestant.role = role;
        contestant.home = home;
        world.spawnEntity(contestant);
        return contestant;
    }

    private void randomize(Random random) {
        dataTracker.set(SKIN, random.nextInt(SKINS));
        setCustomName(Text.literal(NAMES[random.nextInt(NAMES.length)]));
        nature = Nature.values()[random.nextInt(Nature.values().length)];
        wits = 0.15F + random.nextFloat() * 0.8F;
        if (nature == Nature.CLEVER) {
            wits = Math.max(wits, 0.75F);
        } else if (nature == Nature.RECKLESS) {
            wits = Math.min(wits, 0.5F);
        }
        years = 17 + random.nextInt(40);
        past = PASTS[random.nextInt(PASTS.length)];
        visa = Visa.DAY + random.nextInt(Visa.DAY * 3);
    }

    public String name() {
        Text name = getCustomName();
        return name == null ? "Stranger" : name.getString();
    }

    public float trust(UUID player) {
        return trust.getOrDefault(player, 0.0F);
    }

    public void trust(UUID player, float change) {
        trust.put(player, Math.max(-1.0F, Math.min(1.0F, trust(player) + change)));
    }

    public void remember(String memory) {
        String m = memory.toLowerCase();
        if (m.contains("lied") || m.contains("betray") || m.contains("tricked") || m.contains("set you up")) {
            feelings.betrayed(this, null);
        }
        memories.addLast(memory);
        while (memories.size() > 8) {
            memories.pollFirst();
        }
    }

    public Iterable<String> memories() {
        return memories;
    }

    @Nullable
    public Game game() {
        return gameId < 0 ? null : Games.byId(gameId);
    }

    /** Says it out loud: everyone within earshot reads it in chat. */
    public void say(String line) {
        if (!(world instanceof ServerWorld server) || line.isBlank()) {
            return;
        }
        Text text = Text.literal("<" + name() + "> ").formatted(Formatting.WHITE).append(Text.literal(line).formatted(Formatting.WHITE));
        for (ServerPlayerEntity player : server.getPlayers()) {
            if (player.squaredDistanceTo(this) < 30.0 * 30.0) {
                player.sendMessage(text, false);
            }
        }
        quietUntil = age + 60;
    }

    /** A line for this moment: the AI writes it if there is one, otherwise {@code fallback}. */
    public void speak(String moment, String fallback) {
        if (age < quietUntil || !isAlive()) {
            return;
        }
        quietUntil = age + 100;
        Voice.line(this, moment, fallback);
    }

    public boolean quiet() {
        return age >= quietUntil;
    }

    /** The stranger you're talking to: the one you're facing, or failing that the nearest. */
    @Nullable
    public static Contestant listening(ServerPlayerEntity player, double range) {
        Contestant best = null;
        double bestScore = Double.MAX_VALUE;
        Vec3d eye = player.getEyePos();
        Vec3d look = player.getRotationVec(1.0F);
        for (Contestant contestant : player.world.getEntitiesByClass(Contestant.class, player.getBoundingBox().expand(range), Contestant::isAlive)) {
            Vec3d to = contestant.getEyePos().subtract(eye);
            double distance = to.length();
            double facing = distance < 0.01 ? 1.0 : look.dotProduct(to.multiply(1.0 / distance));
            double score = distance * (facing > 0.85 ? 0.4 : facing > 0.3 ? 1.0 : 3.0);
            if (score < bestScore) {
                best = contestant;
                bestScore = score;
            }
        }
        return best;
    }

    @Override
    public void tick() {
        super.tick();
        if (world.isClient || !isAlive()) {
            return;
        }
        if (isBody()) {
            return;
        }
        feelings.tick(this);
        if (Borderland.is(world) && --visa <= 0) {
            visa = 0;
            if (gameId < 0) {
                Laser.strike(this, null);      // somewhere out there, someone's visa ran out
            }
        }
        if (gameId >= 0 && age % 40 == 0 && game() == null) {
            gameId = -1;
            stopAll();
        }
        // People with a home stay near it between games
        if (home != null && gameId < 0 && age % 60 == 0 && goal() == null && squaredDistanceTo(Vec3d.ofBottomCenter(home)) > 26.0 * 26.0) {
            go(home.add(random.nextInt(13) - 6, 0, random.nextInt(13) - 6), 0.8);
        }
        if (home != null && gameId < 0 && goal() != null && arrived(1.5)) {
            stopAll();
        }
    }

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        nbt.putInt("Skin", skin());
        nbt.putString("Nature", nature.name());
        nbt.putFloat("Wits", wits);
        nbt.putInt("Years", years);
        nbt.putString("Past", past);
        nbt.putInt("Visa", visa);
        nbt.putInt("Cleared", cleared);
        nbt.putString("Role", role);
        nbt.putBoolean("Body", isBody());
        if (home != null) {
            nbt.putIntArray("Home", new int[]{home.getX(), home.getY(), home.getZ()});
        }
        NbtCompound trusts = new NbtCompound();
        for (Map.Entry<UUID, Float> entry : trust.entrySet()) {
            trusts.putFloat(entry.getKey().toString(), entry.getValue());
        }
        nbt.put("Trust", trusts);
        NbtList list = new NbtList();
        for (String memory : memories) {
            list.add(NbtString.of(memory));
        }
        nbt.put("Memories", list);
        feelings.save(nbt);
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        dataTracker.set(SKIN, nbt.getInt("Skin"));
        try {
            nature = Nature.valueOf(nbt.getString("Nature"));
        } catch (IllegalArgumentException e) {
            nature = Nature.KIND;
        }
        wits = nbt.contains("Wits") ? nbt.getFloat("Wits") : 0.5F;
        years = nbt.contains("Years") ? nbt.getInt("Years") : 25;
        past = nbt.contains("Past") ? nbt.getString("Past") : past;
        visa = nbt.contains("Visa") ? nbt.getInt("Visa") : Visa.DAY;
        cleared = nbt.getInt("Cleared");
        role = nbt.getString("Role");
        int[] at = nbt.getIntArray("Home");
        home = at.length == 3 ? new BlockPos(at[0], at[1], at[2]) : null;
        if (nbt.getBoolean("Body")) {
            dataTracker.set(BODY, true);
        }
        NbtCompound trusts = nbt.getCompound("Trust");
        for (String key : trusts.getKeys()) {
            try {
                trust.put(UUID.fromString(key), trusts.getFloat(key));
            } catch (IllegalArgumentException ignored) {
                // not an id
            }
        }
        NbtList list = nbt.getList("Memories", NbtElement.STRING_TYPE);
        for (int i = 0; i < list.size(); i++) {
            memories.addLast(list.getString(i));
        }
        feelings.load(nbt, nature);
        gameId = -1;    // games don't survive a restart
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.ENTITY_PLAYER_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.ENTITY_PLAYER_DEATH;
    }
}
