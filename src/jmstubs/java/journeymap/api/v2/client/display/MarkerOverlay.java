// Compile-only stand-in for JourneyMap API 2.0 (journeymap-api 1.20.1_2.0): the real classes come with JourneyMap.
// Only the members Ender Obelisks uses, with the exact signatures; never packaged.
package journeymap.api.v2.client.display;

import journeymap.api.v2.client.model.MapImage;
import net.minecraft.core.BlockPos;

public final class MarkerOverlay extends Overlay {
	public MarkerOverlay(String modId, BlockPos point, MapImage icon) {
		throw new UnsupportedOperationException();
	}
}
