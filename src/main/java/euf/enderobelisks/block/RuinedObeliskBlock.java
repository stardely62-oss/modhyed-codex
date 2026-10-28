package euf.enderobelisks.block;

import euf.enderobelisks.EnderObelisks;
import euf.enderobelisks.ObeliskCriteria;
import euf.enderobelisks.ObeliskService;
import euf.enderobelisks.ObeliskSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The broken stump of an obelisk, found in ruins around the world. Right click it with a time shard and it rises
 * again as a working obelisk that belongs to you.
 */
public class RuinedObeliskBlock extends Block {
	private static final VoxelShape SHAPE = Shapes.or(
			Block.box(0.5, 0, 0.5, 15.5, 2.5, 15.5),
			Block.box(2.5, 2.5, 2.5, 13.5, 4, 13.5),
			Block.box(5, 4, 5, 11, 10, 11));

	public RuinedObeliskBlock(Properties settings) {
		super(settings);
	}

	@Override
	@SuppressWarnings("deprecation")
	public InteractionResult useWithoutItem(BlockState state, Level world, BlockPos pos, Player player, BlockHitResult hit) {
		if (world.isClientSide()) {
			return InteractionResult.SUCCESS;
		}
		ItemStack held = player.getItemInHand(InteractionHand.MAIN_HAND);
		if (!held.is(EnderObelisks.TIME_SHARD.get())) {
			player.displayClientMessage(Component.translatable("ender_obelisks.ruin.hint"), true);
			return InteractionResult.CONSUME;
		}
		restore((ServerLevel) world, pos, player, held);
		return InteractionResult.CONSUME;
	}

    @Override
    protected net.minecraft.world.ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level world, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (!stack.is(EnderObelisks.TIME_SHARD.get())) return net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (world instanceof ServerLevel serverWorld) restore(serverWorld, pos, player, stack);
        return net.minecraft.world.ItemInteractionResult.sidedSuccess(world.isClientSide());
    }

	/** Turns the ruin back into an obelisk owned by the player, using up one time shard (none in creative). */
	public static boolean restore(ServerLevel world, BlockPos pos, Player player, ItemStack shard) {
		if (!world.getBlockState(pos.above()).canBeReplaced()) {
			player.displayClientMessage(Component.translatable("ender_obelisks.ruin.no_space"), true);
			return false;
		}
		Direction facing = player.getDirection().getOpposite();
		BlockState obelisk = EnderObelisks.OBELISK.get().defaultBlockState().setValue(ObeliskBlock.FACING, facing);
		world.setBlockAndUpdate(pos.above(), obelisk.setValue(ObeliskBlock.HALF, DoubleBlockHalf.UPPER));
		world.setBlockAndUpdate(pos, obelisk.setValue(ObeliskBlock.HALF, DoubleBlockHalf.LOWER));
		ObeliskService.register(world, pos, player, null);
		if (!player.getAbilities().instabuild) {
			shard.shrink(1);
		}
		world.sendParticles(ParticleTypes.REVERSE_PORTAL, pos.getX() + 0.5, pos.getY() + 1.2, pos.getZ() + 0.5, 80, 0.4, 0.9, 0.4, 0.05);
		world.playSound(null, pos, ObeliskSounds.UPGRADE.get(), SoundSource.BLOCKS, 1.0f, 0.8f);
		world.playSound(null, pos, ObeliskSounds.PLACE.get(), SoundSource.BLOCKS, 1.0f, 0.7f);
		player.displayClientMessage(Component.translatable("ender_obelisks.ruin.restored"), true);
		if (player instanceof ServerPlayer serverPlayer) {
			ObeliskCriteria.RESTORED.get().trigger(serverPlayer, 1);
		}
		return true;
	}

	@Override
	@SuppressWarnings("deprecation")
	public VoxelShape getShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) {
		return SHAPE;
	}

	/** Ash drifts up from the broken stump. */
	@Override
	public void animateTick(BlockState state, Level world, BlockPos pos, RandomSource random) {
		if (random.nextInt(3) == 0) {
			world.addParticle(ParticleTypes.WHITE_ASH, pos.getX() + 0.3 + random.nextDouble() * 0.4, pos.getY() + 0.65,
					pos.getZ() + 0.3 + random.nextDouble() * 0.4, 0, 0.02, 0);
		}
	}
}
