package euf.enderobelisks.compat;

import euf.enderobelisks.EnderObelisks;
import euf.enderobelisks.ObeliskColors;
import euf.enderobelisks.client.ObeliskClientCache;
import euf.enderobelisks.net.ObeliskSync;
import java.util.ArrayList;
import java.util.List;
import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.IClientPlugin;
import journeymap.api.v2.client.display.MarkerOverlay;
import journeymap.api.v2.client.model.MapImage;
import journeymap.api.v2.client.model.TextProperties;
import journeymap.api.v2.common.JourneyMapPlugin;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;

/**
 * Shows every obelisk you may use as a marker on JourneyMap's maps, in its colour, with its name.
 * JourneyMap (API 2.0, JourneyMap 6) loads this through the "journeymap" entrypoint; without JourneyMap it is
 * never touched. It compiles against stand-ins of the API (src/jmstubs) that are not packaged.
 */
@JourneyMapPlugin(apiVersion = "2.0.0")
public class JourneyMapCompat implements IClientPlugin {
	private static final ResourceLocation ICON = EnderObelisks.id("textures/gui/map_marker.png");
	/** How often to check that JourneyMap still has our markers (it drops overlays when the world changes). */
	private static final int CHECK_TICKS = 100;

	private IClientAPI api;
	private final List<MarkerOverlay> shown = new ArrayList<>();
	private int ticks;

	@Override
	public String getModId() {
		return EnderObelisks.MOD_ID;
	}

	@Override
	public void initialize(IClientAPI jmClientApi) {
		this.api = jmClientApi;
		ObeliskClientCache.addListener(() -> Minecraft.getInstance().execute(this::refresh));
		NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> {
			if (++ticks % CHECK_TICKS == 0 && !shown.isEmpty() && !api.exists(shown.get(0))) {
				refresh();
			}
		});
		EnderObelisks.LOGGER.info("JourneyMap integration ready");
	}

	private void refresh() {
		api.removeAll(EnderObelisks.MOD_ID);
		shown.clear();
		for (ObeliskSync.Entry entry : ObeliskClientCache.entries()) {
			int rgb = ObeliskColors.rgb(entry.color());
			MapImage icon = new MapImage(ICON, 16, 16).setColor(rgb).setDisplayWidth(12).setDisplayHeight(12)
					.setAnchorX(6).setAnchorY(6);
			MarkerOverlay marker = new MarkerOverlay(EnderObelisks.MOD_ID, entry.pos(), icon);
			marker.setDimension(ResourceKey.create(Registries.DIMENSION, entry.dimension()));
			marker.setLabel(entry.name());
			marker.setTitle(entry.name() + " · " + entry.ownerName());
			marker.setTextProperties(new TextProperties().setColor(rgb).setBackgroundOpacity(0.4f).setOffsetY(10));
			try {
				api.show(marker);
				shown.add(marker);
			} catch (Exception e) {
				EnderObelisks.LOGGER.warn("JourneyMap refused the marker for {}", entry.name(), e);
			}
		}
	}
}
