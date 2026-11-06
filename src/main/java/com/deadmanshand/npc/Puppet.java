package com.deadmanshand.npc;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.ai.pathing.MobNavigation;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;

/**
 * Someone a game can direct: walk there, look at that, stand still. The games decide; this does it. When nobody is
 * giving orders, its own idle goals take over.
 */
public abstract class Puppet extends PathAwareEntity {
    @Nullable
    Vec3d goal;
    double speed = 1.0;
    @Nullable
    Entity lookEntity;
    @Nullable
    Vec3d lookPoint;
    boolean frozen;

    protected Puppet(EntityType<? extends PathAwareEntity> type, World world) {
        super(type, world);
        if (getNavigation() instanceof MobNavigation navigation) {
            navigation.setCanPathThroughDoors(true);
            navigation.setCanEnterOpenDoors(true);
        }
        setPersistent();
    }

    @Override
    protected void initGoals() {
        goalSelector.add(0, new SwimGoal(this));
        goalSelector.add(1, new Orders(this));
        idleGoals();
    }

    /** What it does when nobody is directing it. */
    protected void idleGoals() {
    }

    /** In the middle of something (a game): no wandering off or gazing about when there are no orders; stand and watch. */
    protected boolean busy() {
        return false;
    }

    /** Told to stand still (by the game) right now. */
    public boolean held() {
        return frozen;
    }

    /** Too frightened to move, this moment. */
    public boolean paralysed() {
        return false;
    }

    /** How much faster than told it goes (fear makes people run). */
    protected double hurry() {
        return 1.0;
    }

    public void go(BlockPos pos, double speed) {
        go(Vec3d.ofBottomCenter(pos), speed);
    }

    public void go(Vec3d target, double speed) {
        goal = target;
        this.speed = speed;
        frozen = false;
    }

    /** Look at someone (into their eyes, if they look back). */
    public void look(@Nullable Entity entity) {
        lookEntity = entity;
        lookPoint = null;
    }

    public void lookAt(Vec3d point) {
        lookPoint = point;
        lookEntity = null;
    }

    /** Stand still where you are. */
    public void hold() {
        goal = null;
        frozen = true;
        getNavigation().stop();
    }

    public void stopAll() {
        goal = null;
        frozen = false;
        lookEntity = null;
        lookPoint = null;
        getNavigation().stop();
    }

    public boolean arrived(double within) {
        return goal == null || squaredDistanceTo(goal) < within * within;
    }

    @Nullable
    public Vec3d goal() {
        return goal;
    }

    /** Where it's really looking: its head, not its body. */
    public static Vec3d gaze(Entity entity) {
        float yaw = entity instanceof net.minecraft.entity.LivingEntity living ? living.getHeadYaw() : entity.getYaw();
        return Vec3d.fromPolar(entity.getPitch(), yaw);
    }

    @Override
    public boolean canImmediatelyDespawn(double distanceSquared) {
        return false;
    }

    private static final class Orders extends Goal {
        private final Puppet mob;
        private int repath;
        /** Where it was a second ago, and how many seconds it's been getting nowhere. */
        private Vec3d was = Vec3d.ZERO;
        private int stuck;
        private int check;
        /** Walking straight at the goal instead of following a path (there wasn't one, or the path got it stuck). */
        private boolean direct;
        @Nullable
        private Vec3d lastGoal;
        @Nullable
        private Entity watching;
        private int watchTicks;

        Orders(Puppet mob) {
            this.mob = mob;
            setControls(EnumSet.of(Control.MOVE, Control.LOOK));
        }

        @Override
        public boolean canStart() {
            return mob.goal != null || mob.frozen || mob.lookEntity != null || mob.lookPoint != null || mob.busy();
        }

        @Override
        public boolean shouldContinue() {
            return canStart();
        }

        @Override
        public boolean shouldRunEveryTick() {
            return true;
        }

        @Override
        public void stop() {
            mob.getNavigation().stop();
            direct = false;
            stuck = 0;
        }

        @Override
        public void tick() {
            if (mob.frozen || mob.paralysed()) {
                mob.getNavigation().stop();
                direct = false;
                stuck = 0;
            } else if (mob.goal != null) {
                Vec3d goal = mob.goal;
                if (lastGoal == null || lastGoal.squaredDistanceTo(goal) > 1.0) {
                    // a new place to go: start over with the pathfinder
                    lastGoal = goal;
                    direct = false;
                    stuck = 0;
                    repath = 0;
                }
                double dx = goal.x - mob.getX();
                double dz = goal.z - mob.getZ();
                double flat = dx * dx + dz * dz;
                if (flat < 0.55 * 0.55 && Math.abs(goal.y - mob.getY()) < 1.3) {
                    // there: stop, and stay stopped (no shuffling round the exact spot)
                    mob.getNavigation().stop();
                    direct = false;
                    stuck = 0;
                } else {
                    if (--repath <= 0) {
                        repath = 20;
                        if (!direct) {
                            boolean found = mob.getNavigation().startMovingTo(goal.x, goal.y, goal.z, mob.speed * mob.hurry());
                            if (!found && flat < 12 * 12) {
                                direct = true;      // no path to it, but it's close: walk straight there
                            }
                        }
                    }
                    if (++check >= 20) {
                        check = 0;
                        stuck = mob.getPos().squaredDistanceTo(was) < 0.3 * 0.3 ? stuck + 1 : 0;
                        was = mob.getPos();
                    }
                    if (stuck >= 2) {
                        // two seconds getting nowhere: swap between the pathfinder and walking straight
                        stuck = 0;
                        direct = !direct;
                        mob.getNavigation().stop();
                        repath = 0;
                    }
                    if (direct) {
                        double close = Math.sqrt(flat);
                        mob.getMoveControl().moveTo(goal.x, goal.y, goal.z, (close < 1.5 ? mob.speed * 0.5 : mob.speed) * mob.hurry());
                        if (mob.horizontalCollision && mob.isOnGround()) {
                            mob.getJumpControl().setActive();
                        }
                    }
                }
            }
            if (mob.goal == null && !mob.frozen) {
                mob.getNavigation().stop();     // busy with no orders: stand where you are
            }
            if (mob.lookEntity != null && mob.lookEntity.isAlive()) {
                mob.getLookControl().lookAt(mob.lookEntity, 30.0F, 30.0F);
            } else if (mob.lookPoint != null) {
                mob.getLookControl().lookAt(mob.lookPoint.x, mob.lookPoint.y, mob.lookPoint.z, 30.0F, 30.0F);
            } else if (mob.goal == null) {
                // nobody to look at in particular: whoever's nearest, unhurried
                if (watchTicks-- <= 0) {
                    watchTicks = 40 + mob.getRandom().nextInt(60);
                    watching = mob.world.getClosestPlayer(mob, 8.0);
                }
                if (watching != null && watching.isAlive()) {
                    mob.getLookControl().lookAt(watching, 10.0F, 10.0F);
                }
            }
        }
    }
}
