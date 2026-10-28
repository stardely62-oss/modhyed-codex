package euf.enderobelisks;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Codec-backed advancement triggers with the original per-trigger thresholds. */
public final class ObeliskCriteria {
    public static final DeferredRegister<CriterionTrigger<?>> REGISTER = DeferredRegister.create(Registries.TRIGGER_TYPE, EnderObelisks.MOD_ID);
    public static final Supplier<NumberCriterion> TELEPORTED = REGISTER.register("teleported", () -> new NumberCriterion("min_distance"));
    public static final Supplier<NumberCriterion> UPGRADED = REGISTER.register("upgraded", () -> new NumberCriterion("min_tier"));
    public static final Supplier<NumberCriterion> RETURNED = REGISTER.register("returned", () -> new NumberCriterion("min_distance"));
    public static final Supplier<NumberCriterion> RESTORED = REGISTER.register("restored", () -> new NumberCriterion("min_count"));
    public static final class NumberCriterion extends SimpleCriterionTrigger<Conditions> {
        private final Codec<Conditions> codec;
        NumberCriterion(String field) {
            codec = RecordCodecBuilder.create(instance -> instance.group(
                EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(Conditions::player),
                Codec.DOUBLE.optionalFieldOf(field, 0d).forGetter(Conditions::minimum)
            ).apply(instance, Conditions::new));
        }
        @Override public Codec<Conditions> codec() { return codec; }
        public void trigger(ServerPlayer player, double value) { trigger(player, conditions -> value >= conditions.minimum()); }
    }
    public record Conditions(Optional<ContextAwarePredicate> player, double minimum) implements SimpleCriterionTrigger.SimpleInstance {}
}
