package com.deadmanshand.npc;

import com.deadmanshand.DeadMansHand;
import com.deadmanshand.Laser;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

/**
 * The one who's "it": a dealer in a horse's head, with a submachine gun. The mask means it only sees what's straight
 * in front of it. It can't be hurt; it can only be outrun, hidden from, or beaten at the game. Its own collar goes off
 * if the players win.
 */
public class Hunter extends Puppet {
    /** Half the angle it can see through the mask's eye holes, in degrees. */
    public static final double SIGHT_DEGREES = 26.0;
    public static final double SIGHT_RANGE = 20.0;

    public Hunter(EntityType<? extends Hunter> type, World world) {
        super(type, world);
        equipStack(EquipmentSlot.MAINHAND, new ItemStack(DeadMansHand.GUN));
        setEquipmentDropChance(EquipmentSlot.MAINHAND, 0.0F);
    }

    public static DefaultAttributeContainer.Builder attributes() {
        return MobEntity.createMobAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 40.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.3)
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 64.0)
                .add(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, 1.0);
    }

    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        return source != Laser.LASER && !source.isOutOfWorld();
    }

    /** Can it see {@code target} through the mask: in front of it, close enough, nothing in the way? */
    public boolean sees(LivingEntity target) {
        Vec3d to = target.getEyePos().subtract(getEyePos());
        double distance = to.length();
        if (distance > SIGHT_RANGE || distance < 0.01) {
            return distance < 1.6;
        }
        double cos = gaze(this).dotProduct(to.multiply(1.0 / distance));
        return cos > Math.cos(Math.toRadians(SIGHT_DEGREES)) && canSee(target);
    }

    /** One shot at {@code target}: it misses more the further away they are, and walls stop it. */
    public void shoot(LivingEntity target, Random random) {
        Guns.shoot(this, target, random, 4.0F, 1.0);
    }
}
