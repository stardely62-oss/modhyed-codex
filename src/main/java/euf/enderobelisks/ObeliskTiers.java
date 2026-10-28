package euf.enderobelisks;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Range and upgrade cost per obelisk tier, from the server config. */
public final class ObeliskTiers {
	public static final int MAX_TIER = 4;

	private ObeliskTiers() {
	}

	public static int range(int tier) {
		return ObeliskConfig.get().ranges[Math.max(0, Math.min(MAX_TIER, tier))];
	}

	/** Items consumed to go from {@code tier} to {@code tier + 1}; empty at the max tier. */
	public static List<ItemStack> upgradeCost(int tier) {
		if (tier < 0 || tier >= MAX_TIER) {
			return List.of();
		}
		List<ItemStack> cost = new ArrayList<>();
		for (ObeliskConfig.Cost entry : ObeliskConfig.get().upgradeCosts.get(tier)) {
			Item item = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(entry.item == null ? "" : entry.item));
			if (item != Items.AIR && entry.count > 0) {
				cost.add(new ItemStack(item, entry.count));
			}
		}
		return cost;
	}
}
