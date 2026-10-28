package euf.enderobelisks.test;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** With -Dender_obelisks.genworld=true: lets the dedicated server create its world, then stops it (runGenworld). */
public class GenWorldServer implements DedicatedServerModInitializer {
	private static final Logger LOGGER = LoggerFactory.getLogger("ender_obelisks-genworld");

	@Override
	public void onInitializeServer() {
		if (!Boolean.getBoolean("ender_obelisks.genworld")) {
			return;
		}
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			LOGGER.info("[genworld] world created, stopping");
			server.halt(false);
		});
	}
}
