package euf.enderobelisks;

import euf.enderobelisks.ObeliskService.TeleportResult;
import euf.enderobelisks.block.ObeliskBlock;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * A one-way ticket home. Sneak-right-click an obelisk to bind it; then hold right click anywhere for a few seconds to
 * return to that obelisk (with your pets). Used up on arrival; a cooldown follows.
 */
public class ReturnShardItem extends Item {
	private static final String KEY = "Obelisk";

	public ReturnShardItem(Properties settings) {
		super(settings);
	}

    @Nullable
    private static CompoundTag binding(ItemStack stack) {
        CompoundTag root = stack.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
        return root.contains(KEY, net.minecraft.nbt.Tag.TAG_COMPOUND) ? root.getCompound(KEY) : null;
    }
    @Nullable
    static UUID boundId(ItemStack stack) {
		CompoundTag nbt = binding(stack);
		return nbt != null && nbt.hasUUID("id") ? nbt.getUUID("id") : null;
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		Player player = context.getPlayer();
		BlockState state = context.getLevel().getBlockState(context.getClickedPos());
		if (player == null || !player.isShiftKeyDown() || !(state.getBlock() instanceof ObeliskBlock)) {
			return InteractionResult.PASS;
		}
		if (context.getLevel() instanceof ServerLevel world) {
			Optional<ObeliskInfo> info = ObeliskService.at(world, ObeliskBlock.lowerPos(state, context.getClickedPos()));
			if (info.isEmpty() || !ObeliskService.canUse(info.get(), player)) {
				player.displayClientMessage(Component.translatable("ender_obelisks.teleport.no_access"), true);
				return InteractionResult.FAIL;
			}
			bind(context.getItemInHand(), info.get());
			player.displayClientMessage(Component.translatable("ender_obelisks.return.bound", info.get().name), true);
			world.playSound(null, context.getClickedPos(), ObeliskSounds.PLACE.get(), SoundSource.PLAYERS, 0.6f, 1.4f);
		}
		return InteractionResult.sidedSuccess(context.getLevel().isClientSide());
	}

	public static void bind(ItemStack stack, ObeliskInfo info) {
		CompoundTag nbt = new CompoundTag();
		nbt.putUUID("id", info.id);
		nbt.putString("name", info.name);
		nbt.putString("dim", info.dimension.location().toString());
		nbt.putLong("pos", info.pos.asLong());
        CompoundTag root = stack.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
        root.put(KEY, nbt);
        stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(root));
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level world, Player user, InteractionHand hand) {
		ItemStack stack = user.getItemInHand(hand);
		if (boundId(stack) == null) {
			if (!world.isClientSide()) {
				user.displayClientMessage(Component.translatable("ender_obelisks.return.unbound"), true);
			}
			return InteractionResultHolder.fail(stack);
		}
		user.startUsingItem(hand);
		if (!world.isClientSide()) {
			world.playSound(null, user.getX(), user.getY(), user.getZ(), ObeliskSounds.CHANNEL.get(), SoundSource.PLAYERS, 0.6f, 0.8f);
		}
		return InteractionResultHolder.consume(stack);
	}

	@Override
	public int getUseDuration(ItemStack stack, LivingEntity user) {
		return ObeliskConfig.get().returnShardSeconds * 20;
	}

	@Override
	public UseAnim getUseAnimation(ItemStack stack) {
		return UseAnim.BOW;
	}

	@Override
	public void onUseTick(Level world, LivingEntity user, ItemStack stack, int remainingUseTicks) {
		if (world instanceof ServerLevel serverWorld && remainingUseTicks % 2 == 0) {
			double a = remainingUseTicks * 0.4;
			serverWorld.sendParticles(ParticleTypes.REVERSE_PORTAL, user.getX() + Math.cos(a) * 0.8, user.getY() + 1.0,
					user.getZ() + Math.sin(a) * 0.8, 2, 0.05, 0.3, 0.05, 0);
			if (user instanceof ServerPlayer player) {
				// Without a visible charge the hold looked like nothing happens: show it filling above the hotbar
				int total = getUseDuration(stack, user);
				player.displayClientMessage(Component.translatable("ender_obelisks.return.channel", boundName(stack),
						bar(1 - remainingUseTicks / (float) total)), true);
			}
		}
	}

	@Override
	public void releaseUsing(ItemStack stack, Level world, LivingEntity user, int remainingUseTicks) {
		if (!world.isClientSide() && user instanceof ServerPlayer player && remainingUseTicks > 0) {
			player.displayClientMessage(Component.translatable("ender_obelisks.return.released", ObeliskConfig.get().returnShardSeconds)
					.withStyle(ChatFormatting.YELLOW), true);
		}
	}

	private static String boundName(ItemStack stack) {
		CompoundTag nbt = binding(stack);
		return nbt == null ? "" : nbt.getString("name");
	}

	/** Ten blocks, lit ender teal up to the charge. */
	private static Component bar(float progress) {
		int lit = Math.round(10 * Math.max(0, Math.min(1, progress)));
		return Component.literal("■".repeat(lit)).withStyle(ChatFormatting.DARK_AQUA)
				.append(Component.literal("■".repeat(10 - lit)).withStyle(ChatFormatting.DARK_GRAY));
	}

	@Override
	public ItemStack finishUsingItem(ItemStack stack, Level world, LivingEntity user) {
		if (!(user instanceof ServerPlayer player)) {
			return stack;
		}
		TeleportResult result = goHome(player, stack);
		if (result != TeleportResult.OK) {
			player.displayClientMessage(Component.translatable("ender_obelisks.teleport." + result.name().toLowerCase(Locale.ROOT)), true);
			return stack;
		}
		player.getCooldowns().addCooldown(this, ObeliskConfig.get().returnShardCooldownSeconds * 20);
		if (!player.getAbilities().instabuild) {
			stack.shrink(1);
		}
		return stack;
	}

	/** The checks and the jump, without the hold-to-use part (tests call this directly). */
	public static TeleportResult goHome(ServerPlayer player, ItemStack stack) {
		UUID id = boundId(stack);
		ObeliskInfo target = id == null ? null : ObeliskRegistry.get(player.getServer()).byId(id).orElse(null);
		if (target == null) {
			return TeleportResult.NO_TARGET;
		}
		if (!ObeliskService.canUse(target, player)) {
			return TeleportResult.NO_ACCESS;
		}
		boolean sameDimension = target.dimension.equals(player.level().dimension());
		if (!sameDimension && !ObeliskConfig.get().returnShardCrossDimension) {
			return TeleportResult.OTHER_DIMENSION;
		}
		double distance = sameDimension ? Math.sqrt(player.blockPosition().distSqr(target.pos)) : 0;
		TeleportResult result = ObeliskService.arrive(player, target);
		if (result == TeleportResult.OK) {
			ObeliskCriteria.RETURNED.get().trigger(player, distance);
		}
		return result;
	}

	@Override
	public void appendHoverText(ItemStack stack, Item.TooltipContext tooltipContext, List<Component> tooltip, TooltipFlag context) {
		CompoundTag nbt = binding(stack);
		if (nbt == null) {
			tooltip.add(Component.translatable("ender_obelisks.return.tooltip_unbound").withStyle(ChatFormatting.GRAY));
		} else {
			tooltip.add(Component.translatable("ender_obelisks.return.tooltip_bound", nbt.getString("name")).withStyle(ChatFormatting.AQUA));
			tooltip.add(Component.translatable("ender_obelisks.return.tooltip_use", ObeliskConfig.get().returnShardSeconds).withStyle(ChatFormatting.GRAY));
		}
	}

	@Override
	public boolean isFoil(ItemStack stack) {
		return boundId(stack) != null;
	}
}
