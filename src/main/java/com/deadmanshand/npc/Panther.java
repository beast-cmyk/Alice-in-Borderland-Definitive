package com.deadmanshand.npc;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.ai.goal.ActiveTargetGoal;
import net.minecraft.entity.ai.goal.LookAroundGoal;
import net.minecraft.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.entity.ai.goal.PounceAtTargetGoal;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.ai.goal.WanderAroundGoal;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.world.World;

/** A big cat loose in the dark: a panther by default; a lion, a cheetah, a leopard or a cougar at the park. */
public class Panther extends PathAwareEntity {
    private static final TrackedData<Integer> VARIANT = DataTracker.registerData(Panther.class, TrackedDataHandlerRegistry.INTEGER);

    public enum Beast {
        PANTHER("all_black", 1.7F, 30.0, 0.36, 7.0, 0.55F),
        LION("red", 2.0F, 44.0, 0.34, 9.0, 0.4F),
        CHEETAH("ocelot", 1.6F, 24.0, 0.44, 5.0, 0.8F),
        LEOPARD("calico", 1.65F, 28.0, 0.38, 6.0, 0.6F),
        COUGAR("persian", 1.75F, 32.0, 0.37, 7.0, 0.5F);

        /** The vanilla cat coat it wears (textures/entity/cat/...). */
        public final String coat;
        public final float scale;
        public final double health;
        public final double speed;
        public final double damage;
        public final float pitch;

        Beast(String coat, float scale, double health, double speed, double damage, float pitch) {
            this.coat = coat;
            this.scale = scale;
            this.health = health;
            this.speed = speed;
            this.damage = damage;
            this.pitch = pitch;
        }
    }

    public Panther(EntityType<? extends Panther> type, World world) {
        super(type, world);
        setPersistent();
    }

    public static DefaultAttributeContainer.Builder attributes() {
        return MobEntity.createMobAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 30.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.36)
                .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 7.0)
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 28.0)
                .add(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, 0.4);
    }

    @Override
    protected void initDataTracker() {
        super.initDataTracker();
        dataTracker.startTracking(VARIANT, 0);
    }

    public Beast beast() {
        Beast[] all = Beast.values();
        return all[Math.floorMod(dataTracker.get(VARIANT), all.length)];
    }

    /** Turns it into {@code beast}: its coat, its size, its strength. */
    public void become(Beast beast) {
        dataTracker.set(VARIANT, beast.ordinal());
        getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH).setBaseValue(beast.health);
        getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED).setBaseValue(beast.speed);
        getAttributeInstance(EntityAttributes.GENERIC_ATTACK_DAMAGE).setBaseValue(beast.damage);
        getAttributeInstance(EntityAttributes.GENERIC_FOLLOW_RANGE).setBaseValue(40.0);
        setHealth((float) beast.health);
    }

    @Override
    protected void initGoals() {
        goalSelector.add(0, new SwimGoal(this));
        goalSelector.add(1, new PounceAtTargetGoal(this, 0.45F));
        goalSelector.add(2, new MeleeAttackGoal(this, 1.25, true));
        goalSelector.add(5, new WanderAroundGoal(this, 0.6));
        goalSelector.add(6, new LookAroundGoal(this));
        targetSelector.add(1, new ActiveTargetGoal<>(this, PlayerEntity.class, false));
        targetSelector.add(2, new ActiveTargetGoal<>(this, Contestant.class, false));
    }

    @Override
    public boolean canImmediatelyDespawn(double distanceSquared) {
        return false;
    }

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        nbt.putInt("Beast", beast().ordinal());
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        dataTracker.set(VARIANT, nbt.getInt("Beast"));
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.ENTITY_CAT_HISS;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.ENTITY_CAT_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.ENTITY_CAT_DEATH;
    }

    @Override
    public float getSoundPitch() {
        return beast().pitch;
    }
}
