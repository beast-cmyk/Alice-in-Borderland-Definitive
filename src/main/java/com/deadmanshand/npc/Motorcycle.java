package com.deadmanshand.npc;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/** An old diesel motorcycle, left in the dark with the key in it. Right-click to get on; W to go, A/D to steer. */
public class Motorcycle extends MobEntity {
    public Motorcycle(EntityType<? extends Motorcycle> type, World world) {
        super(type, world);
        stepHeight = 1.1F;
        setPersistent();
    }

    public static DefaultAttributeContainer.Builder attributes() {
        return MobEntity.createMobAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 40.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.5)
                .add(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, 1.0);
    }

    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        return !source.isOutOfWorld();
    }

    @Override
    protected ActionResult interactMob(PlayerEntity player, Hand hand) {
        if (hasPassengers()) {
            return ActionResult.PASS;
        }
        if (!world.isClient) {
            player.setYaw(getYaw());
            player.startRiding(this);
            world.playSound(null, getX(), getY(), getZ(), SoundEvents.ENTITY_MINECART_RIDING, SoundCategory.NEUTRAL, 0.6F, 0.6F);
        }
        return ActionResult.success(world.isClient);
    }

    @Nullable
    @Override
    public Entity getPrimaryPassenger() {
        return getFirstPassenger();
    }

    @Override
    public double getMountedHeightOffset() {
        return 0.55;
    }

    @Override
    public void travel(Vec3d movementInput) {
        if (isAlive() && getPrimaryPassenger() instanceof LivingEntity rider) {
            setYaw(rider.getYaw());
            prevYaw = getYaw();
            setPitch(0.0F);
            bodyYaw = getYaw();
            headYaw = bodyYaw;
            float forward = rider.forwardSpeed;
            float sideways = rider.sidewaysSpeed * 0.25F;
            if (forward < 0.0F) {
                forward *= 0.3F;
            }
            if (isLogicalSideForUpdatingMovement()) {
                setMovementSpeed(0.62F);
                super.travel(new Vec3d(sideways, movementInput.y, forward));
            } else {
                setVelocity(Vec3d.ZERO);
            }
            if (!world.isClient && forward > 0.0F && age % 8 == 0) {
                world.playSound(null, getX(), getY(), getZ(), SoundEvents.ENTITY_MINECART_RIDING, SoundCategory.NEUTRAL, 0.5F, 0.5F);
            }
            return;
        }
        super.travel(Vec3d.ZERO);
    }

    @Override
    public boolean canImmediatelyDespawn(double distanceSquared) {
        return false;
    }
}
