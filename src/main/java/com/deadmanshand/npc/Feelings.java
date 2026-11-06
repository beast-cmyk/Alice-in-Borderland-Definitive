package com.deadmanshand.npc;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * What someone is feeling: fear, grief, anger and hope, each from 0 to 1. Things that happen to them and in front of them
 * push these up; time lets them settle back towards where that kind of person usually sits. And they change what that
 * person does: fear clouds judgement and can freeze you or send you running; grief and anger make you take risks; hope
 * steadies you. They also colour everything the AI has them say.
 */
public final class Feelings {
    public float fear;
    public float grief;
    public float anger;
    public float hope = 0.3F;
    /** Who they're grieving for, and who they're angry with, if anyone in particular. */
    @Nullable
    public String griefFor;
    @Nullable
    public String angerAt;
    /** Ticks left frozen with fear. */
    int frozen;
    /** Deaths already seen, so each one hits once: who, and when. */
    private final Map<UUID, Long> seen = new HashMap<>();
    private int clock;
    /** When anyone may next cry out at a death. */
    private static long nextCry;

    private static float clamp(float v) {
        return Math.max(0.0F, Math.min(1.0F, v));
    }

    float baseFear(Contestant.Nature n) {
        return switch (n) {
            case TIMID -> 0.3F;
            case BRAVE, RECKLESS -> 0.05F;
            default -> 0.15F;
        };
    }

    float baseAnger(Contestant.Nature n) {
        return switch (n) {
            case RECKLESS -> 0.15F;
            case SELFISH -> 0.1F;
            default -> 0.05F;
        };
    }

    float baseHope(Contestant.Nature n) {
        return n == Contestant.Nature.KIND ? 0.4F : n == Contestant.Nature.TIMID ? 0.2F : 0.3F;
    }

    /** How much a scare gets to them. */
    private static float scares(Contestant.Nature n) {
        return switch (n) {
            case TIMID -> 1.5F;
            case BRAVE -> 0.5F;
            case RECKLESS -> 0.6F;
            case CLEVER -> 0.8F;
            default -> 1.0F;
        };
    }

    /** How much a loss gets to them. */
    private static float grieves(Contestant.Nature n) {
        return switch (n) {
            case KIND -> 1.5F;
            case SELFISH -> 0.4F;
            case RECKLESS -> 0.8F;
            default -> 1.0F;
        };
    }

    /** Once a tick, from the contestant. */
    void tick(Contestant c) {
        if (frozen > 0) {
            frozen--;
        }
        if (++clock % 10 != 0 || !(c.world instanceof ServerWorld world)) {
            return;
        }
        // deaths nearby
        long now = world.getTime();
        seen.values().removeIf(t -> now - t > 1200);
        for (LivingEntity other : world.getEntitiesByClass(LivingEntity.class, new Box(c.getBlockPos()).expand(20.0),
                e -> e != c && (e instanceof PlayerEntity || e instanceof Contestant) && (e.isDead() || e.getHealth() <= 0.0F))) {
            if (seen.containsKey(other.getUuid()) || other instanceof Contestant k && k.isBody()) {
                continue;
            }
            seen.put(other.getUuid(), now);
            if (c.canSee(other) || c.squaredDistanceTo(other) < 8 * 8) {
                witnessDeath(c, other);
            }
        }
        if (clock % 20 != 0) {
            return;
        }
        // settling back, a little every second: fear and anger fade fastest, grief slowest
        Contestant.Nature n = c.nature;
        fear += (baseFear(n) - fear) * 0.02F;
        anger += (baseAnger(n) - anger) * 0.015F;
        hope += (baseHope(n) - hope) * 0.008F;
        grief += -grief * 0.003F;
        if (grief < 0.05F) {
            griefFor = null;
        }
        if (anger < 0.15F) {
            angerAt = null;
        }
        // terror: now and then it freezes you to the spot
        if (fear > 0.75F && frozen == 0 && c.busy() && c.getRandom().nextFloat() < 0.04F * scares(n)) {
            frozen = 30 + c.getRandom().nextInt(30);
            if (c.getRandom().nextFloat() < 0.4F) {
                c.say(Lines.pick(c.getRandom(), "I can't... I can't move...", "No, no, no...", "Wait. Just wait. Please."));
            }
        }
        // what it looks like from outside
        if (fear > 0.65F) {
            world.spawnParticles(ParticleTypes.FALLING_WATER, c.getX(), c.getEyeY() + 0.1, c.getZ(), 1, 0.2, 0.05, 0.2, 0.0);
        }
        if (anger > 0.6F && clock % 40 == 0) {
            world.spawnParticles(ParticleTypes.ANGRY_VILLAGER, c.getX(), c.getEyeY() + 0.4, c.getZ(), 1, 0.1, 0.05, 0.1, 0.0);
        }
    }

    private void witnessDeath(Contestant c, LivingEntity dead) {
        Contestant.Nature n = c.nature;
        float near = c.squaredDistanceTo(dead) < 8 * 8 ? 1.0F : 0.6F;
        float close = Math.max(0.0F, c.trust(dead.getUuid()));
        String name = dead instanceof Contestant k ? k.name() : dead.getEntityName();
        fear = clamp(fear + 0.25F * scares(n) * near);
        grief = clamp(grief + (0.12F + 0.4F * close) * grieves(n));
        hope = clamp(hope - 0.1F);
        if (close > 0.2F || griefFor == null) {
            griefFor = name;
        }
        c.remember("You watched " + name + " die" + (close > 0.3F ? ", and you'd come to care about them." : "."));
        // one cry at a time, or a crowd all talking at once drowns itself out
        long now = c.world.getTime();
        if ((close > 0.3F || now >= nextCry) && c.getRandom().nextFloat() < 0.7F) {
            nextCry = now + 60;
            c.speak("You've just watched " + name + " die, right in front of you" + (close > 0.3F ? "; you'd come to care about them" : "")
                    + ". Say what comes out of you.", Lines.grief(c, name, close > 0.3F));
        }
    }

    /** Hurt by something: scared, and angry with whoever did it. */
    void hurt(Contestant c, DamageSource source, float amount) {
        Contestant.Nature n = c.nature;
        fear = clamp(fear + amount * 0.04F * scares(n));
        Entity attacker = source.getAttacker();
        if (attacker != null && attacker != c) {
            anger = clamp(anger + amount * 0.03F * (n == Contestant.Nature.RECKLESS ? 1.5F : 1.0F));
            angerAt = attacker instanceof Contestant k ? k.name() : attacker.getEntityName();
            c.trust(attacker.getUuid(), -0.15F);
        }
    }

    /** Lied to, betrayed, used. */
    public void betrayed(Contestant c, @Nullable String by) {
        anger = clamp(anger + 0.3F);
        hope = clamp(hope - 0.05F);
        if (by != null) {
            angerAt = by;
        }
    }

    /** Something went right: someone got out, a door was safe, the right car started. */
    public void lifted(float by) {
        hope = clamp(hope + by);
        fear = clamp(fear - by * 0.5F);
    }

    /** A real scare that isn't anyone dying: a roar in the dark, an explosion. */
    public void scared(Contestant c, float by) {
        fear = clamp(fear + by * scares(c.nature));
    }

    /** For the voice: how they feel, in words. */
    public String describe() {
        StringBuilder s = new StringBuilder();
        if (fear > 0.75F) {
            s.append("You're terrified, barely holding together. ");
        } else if (fear > 0.45F) {
            s.append("You're frightened. ");
        }
        if (grief > 0.6F) {
            s.append("You're wrecked with grief").append(griefFor != null ? " for " + griefFor : "").append(". ");
        } else if (grief > 0.3F) {
            s.append("You're grieving").append(griefFor != null ? " " + griefFor : "").append(". ");
        }
        if (anger > 0.6F) {
            s.append("You're furious").append(angerAt != null ? " with " + angerAt : "").append(". ");
        } else if (anger > 0.35F) {
            s.append("You're angry").append(angerAt != null ? " with " + angerAt : "").append(". ");
        }
        if (hope > 0.65F) {
            s.append("You feel real hope that you'll get through this. ");
        } else if (hope < 0.12F) {
            s.append("You've almost stopped believing you'll get out. ");
        }
        return s.length() == 0 ? "You're tense but steady." : s.toString().trim();
    }

    void save(NbtCompound nbt) {
        NbtCompound f = new NbtCompound();
        f.putFloat("Fear", fear);
        f.putFloat("Grief", grief);
        f.putFloat("Anger", anger);
        f.putFloat("Hope", hope);
        if (griefFor != null) {
            f.putString("GriefFor", griefFor);
        }
        if (angerAt != null) {
            f.putString("AngerAt", angerAt);
        }
        nbt.put("Feelings", f);
    }

    void load(NbtCompound nbt, Contestant.Nature n) {
        if (!nbt.contains("Feelings")) {
            fear = baseFear(n);
            anger = baseAnger(n);
            hope = baseHope(n);
            return;
        }
        NbtCompound f = nbt.getCompound("Feelings");
        fear = f.getFloat("Fear");
        grief = f.getFloat("Grief");
        anger = f.getFloat("Anger");
        hope = f.getFloat("Hope");
        griefFor = f.contains("GriefFor") ? f.getString("GriefFor") : null;
        angerAt = f.contains("AngerAt") ? f.getString("AngerAt") : null;
    }
}
