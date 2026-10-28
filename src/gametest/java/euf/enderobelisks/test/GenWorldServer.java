package euf.enderobelisks.test;


import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** With -Dender_obelisks.genworld=true: lets the dedicated server create its world, then stops it (runGenworld). */
public class GenWorldServer {
	private static final Logger LOGGER = LoggerFactory.getLogger("ender_obelisks-genworld");

	public void onInitializeServer() {
		if (!Boolean.getBoolean("ender_obelisks.genworld")) {
			return;
		}
		NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) -> {
 var server = event.getServer();
			LOGGER.info("[genworld] world created, stopping");
			server.halt(false);
		});
	}
}
