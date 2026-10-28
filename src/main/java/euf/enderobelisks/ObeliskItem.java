package euf.enderobelisks;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

/** The obelisk item: hints on what it does (recipe viewers only show the recipe); the lore comes from the client. */
public class ObeliskItem extends BlockItem {
	public ObeliskItem(Block block, Properties settings) {
		super(block, settings);
	}

	@Override
	public void appendHoverText(ItemStack stack, Item.TooltipContext tooltipContext, List<Component> tooltip, TooltipFlag context) {
		tooltip.add(Component.translatable("ender_obelisks.tooltip.use").withStyle(ChatFormatting.DARK_GRAY));
		tooltip.add(Component.translatable("ender_obelisks.tooltip.upgrade").withStyle(ChatFormatting.DARK_GRAY));
		tooltip.add(Component.translatable("ender_obelisks.tooltip.name").withStyle(ChatFormatting.DARK_GRAY));
	}
}
