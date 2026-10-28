package euf.enderobelisks;

import java.util.function.Supplier;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvent;

/** Sound events of the obelisk. They reuse vanilla recordings at other pitches (assets/ender_obelisks/sounds.json). */
public final class ObeliskSounds {
 public static final DeferredRegister<SoundEvent> REGISTER = DeferredRegister.create(BuiltInRegistries.SOUND_EVENT, EnderObelisks.MOD_ID);
	public static final Supplier<SoundEvent> HUM = register("obelisk.hum");
	public static final Supplier<SoundEvent> PLACE = register("obelisk.place");
	public static final Supplier<SoundEvent> CHANNEL = register("obelisk.channel");
	public static final Supplier<SoundEvent> TELEPORT = register("obelisk.teleport");
	public static final Supplier<SoundEvent> CANCEL = register("obelisk.cancel");
	public static final Supplier<SoundEvent> UPGRADE = register("obelisk.upgrade");

	private ObeliskSounds() {
	}

	private static Supplier<SoundEvent> register(String name) {
		return REGISTER.register(name, () -> SoundEvent.createVariableRangeEvent(EnderObelisks.id(name)));
	}

	/** Forces class loading from the mod initializer, so the events are registered in time. */
	public static void init() {
	}
}
