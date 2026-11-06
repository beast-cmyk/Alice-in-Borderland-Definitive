package com.deadmanshand.npc;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.RaycastContext;

/** A shot from a gun: a trail of sparks, a crack, and damage if it finds the target before a wall does. */
public final class Guns {
    private Guns() {
    }

    /** One shot from {@code shooter} at {@code target}; it misses more the further away they are. True if it hit. */
    public static boolean shoot(LivingEntity shooter, LivingEntity target, Random random, float damage, double accuracy) {
        if (!(shooter.world instanceof ServerWorld server)) {
            return false;
        }
        Vec3d from = shooter.getEyePos().add(Puppet.gaze(shooter).multiply(0.6));
        Vec3d aim = target.getPos().add(0.0, target.getHeight() * 0.6, 0.0);
        double distance = aim.distanceTo(from);
        double spread = (0.06 + distance * 0.035) / Math.max(0.2, accuracy);
        Vec3d direction = aim.subtract(from).normalize()
                .add((random.nextDouble() - 0.5) * spread, (random.nextDouble() - 0.5) * spread, (random.nextDouble() - 0.5) * spread).normalize();
        Vec3d end = from.add(direction.multiply(distance + 6.0));
        BlockHitResult wall = server.raycast(new RaycastContext(from, end, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, shooter));
        if (wall.getType() != HitResult.Type.MISS) {
            end = wall.getPos();
        }
        boolean hit = target.getBoundingBox().expand(0.15).raycast(from, end).isPresent();
        Vec3d stop = hit ? aim : end;
        Vec3d step = stop.subtract(from);
        int steps = (int) Math.max(2, step.length() * 2.0);
        for (int i = 1; i <= steps; i++) {
            Vec3d at = from.add(step.multiply(i / (double) steps));
            server.spawnParticles(ParticleTypes.CRIT, at.x, at.y, at.z, 1, 0.0, 0.0, 0.0, 0.0);
        }
        server.spawnParticles(ParticleTypes.SMOKE, from.x, from.y, from.z, 2, 0.05, 0.05, 0.05, 0.01);
        server.playSound(null, shooter.getX(), shooter.getY(), shooter.getZ(), SoundEvents.ENTITY_FIREWORK_ROCKET_BLAST, SoundCategory.HOSTILE, 1.6F,
                1.6F + random.nextFloat() * 0.3F);
        if (hit) {
            target.damage(DamageSource.mob(shooter), damage);
        }
        return hit;
    }
}
