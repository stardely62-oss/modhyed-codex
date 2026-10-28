package euf.enderobelisks.block;

import euf.enderobelisks.EnderObelisks;
import euf.enderobelisks.ObeliskConfig;
import euf.enderobelisks.ObeliskInfo;
import euf.enderobelisks.ObeliskService;
import euf.enderobelisks.ObeliskSounds;
import java.util.Optional;
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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * Two blocks tall, like a door: the lower half carries the block entity and the registry entry, the upper half holds
 * the cage with the floating eye. Right click opens the teleport menu; only the owner (or an operator) can break it.
 */
public class ObeliskBlock extends Block implements EntityBlock {
	public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
	public static final EnumProperty<DoubleBlockHalf> HALF = BlockStateProperties.DOUBLE_BLOCK_HALF;

	// A stepped plinth and the shaft below; the slimmer shaft and the cage above (the cap is decoration only)
	private static final VoxelShape LOWER_SHAPE = Shapes.or(
			Block.box(0.5, 0, 0.5, 15.5, 2.5, 15.5),
			Block.box(2.5, 2.5, 2.5, 13.5, 4, 13.5),
			Block.box(3.5, 4, 3.5, 12.5, 6.5, 12.5),
			Block.box(4.5, 6.5, 4.5, 11.5, 7.5, 11.5),
			Block.box(5, 7.5, 5, 11, 16, 11));
	private static final VoxelShape UPPER_SHAPE = Shapes.or(
			Block.box(5.5, 0, 5.5, 10.5, 7, 10.5),
			Block.box(3.5, 7, 3.5, 12.5, 16, 12.5));

	public ObeliskBlock(Properties settings) {
		super(settings);
		registerDefaultState(getStateDefinition().any().setValue(FACING, Direction.NORTH).setValue(HALF, DoubleBlockHalf.LOWER));
	}

	public static BlockPos lowerPos(BlockState state, BlockPos pos) {
		return state.getValue(HALF) == DoubleBlockHalf.UPPER ? pos.below() : pos;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING, HALF);
	}

	@Nullable
	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return state.getValue(HALF) == DoubleBlockHalf.LOWER ? new ObeliskBlockEntity(pos, state) : null;
	}

	@Nullable
	@Override
	public BlockState getStateForPlacement(BlockPlaceContext ctx) {
		BlockPos pos = ctx.getClickedPos();
		Level world = ctx.getLevel();
		if (pos.getY() < world.getMaxBuildHeight() - 1 && world.getBlockState(pos.above()).canBeReplaced(ctx)) {
			return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
		}
		return null;
	}

	@Override
	public void setPlacedBy(Level world, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
		world.setBlock(pos.above(), state.setValue(HALF, DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
		if (world instanceof ServerLevel serverWorld) {
			world.playSound(null, pos, ObeliskSounds.PLACE.get(), SoundSource.BLOCKS, 1.0f, 1.0f);
			String name = stack.has(net.minecraft.core.component.DataComponents.CUSTOM_NAME) ? stack.getHoverName().getString() : null;
			ObeliskService.register(serverWorld, pos, placer instanceof Player player ? player : null, name);
		}
	}

	@Override
	@SuppressWarnings("deprecation")
	public BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
			LevelAccessor world, BlockPos pos, BlockPos neighborPos) {
		DoubleBlockHalf half = state.getValue(HALF);
		// The two halves live and die together
		if (direction.getAxis() == Direction.Axis.Y && (half == DoubleBlockHalf.LOWER) == (direction == Direction.UP)) {
			boolean partnerOk = neighborState.is(this) && neighborState.getValue(HALF) != half;
			return partnerOk ? state : Blocks.AIR.defaultBlockState();
		}
		return super.updateShape(state, direction, neighborState, world, pos, neighborPos);
	}

	@Override
	@SuppressWarnings("deprecation")
	public boolean canSurvive(BlockState state, LevelReader world, BlockPos pos) {
		if (state.getValue(HALF) == DoubleBlockHalf.UPPER) {
			BlockState below = world.getBlockState(pos.below());
			return below.is(this) && below.getValue(HALF) == DoubleBlockHalf.LOWER;
		}
		return super.canSurvive(state, world, pos);
	}

	@Override
	@SuppressWarnings("deprecation")
	public void onRemove(BlockState state, Level world, BlockPos pos, BlockState newState, boolean moved) {
		if (!state.is(newState.getBlock()) && state.getValue(HALF) == DoubleBlockHalf.LOWER && world instanceof ServerLevel serverWorld) {
			ObeliskService.unregister(serverWorld, pos);
		}
		super.onRemove(state, world, pos, newState, moved);
	}

	@Override
	@SuppressWarnings("deprecation")
	public float getDestroyProgress(BlockState state, Player player, BlockGetter world, BlockPos pos) {
		return mayBreak(state, player, world, pos) ? super.getDestroyProgress(state, player, world, pos) : 0.0f;
	}

	/** Owner or operator only. The client may not know the owner, so it digs as usual and the server decides. */
	public static boolean mayBreak(BlockState state, Player player, BlockGetter world, BlockPos pos) {
		if (!(world instanceof ServerLevel serverWorld) || !ObeliskConfig.get().ownerOnlyBreak) {
			return true;
		}
		Optional<ObeliskInfo> info = ObeliskService.at(serverWorld, lowerPos(state, pos));
		return info.isEmpty() || ObeliskService.isOwner(info.get(), player);
	}

	@Override
	@SuppressWarnings("deprecation")
	public InteractionResult useWithoutItem(BlockState state, Level world, BlockPos pos, Player player, BlockHitResult hit) {
		if (world.isClientSide()) {
			return InteractionResult.SUCCESS;
		}
		ServerLevel serverWorld = (ServerLevel) world;
		ServerPlayer serverPlayer = (ServerPlayer) player;
		BlockPos lower = lowerPos(state, pos);
		// An obelisk set by command has no entry yet: the first player to use it claims it
		ObeliskInfo info = ObeliskService.at(serverWorld, lower).orElseGet(() -> ObeliskService.register(serverWorld, lower, player, null));
		if (!ObeliskService.canUse(info, player)) {
			player.displayClientMessage(Component.translatable("ender_obelisks.message.private", info.ownerName), true);
			return InteractionResult.CONSUME;
		}
		ObeliskService.openMenu(serverPlayer, lower);
		return InteractionResult.CONSUME;
	}

    @Override
    protected net.minecraft.world.ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level world, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (player.isShiftKeyDown() && stack.is(EnderObelisks.RETURN_SHARD.get())) return net.minecraft.world.ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
        useWithoutItem(state, world, pos, player, hit);
        return net.minecraft.world.ItemInteractionResult.sidedSuccess(world.isClientSide());
    }

	@Override
	@SuppressWarnings("deprecation")
	public VoxelShape getShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) {
		return state.getValue(HALF) == DoubleBlockHalf.LOWER ? LOWER_SHAPE : UPPER_SHAPE;
	}

	/** Idle animation: pale enchanting glyphs drawn into the cage from all sides, and a low hum. */
	@Override
	public void animateTick(BlockState state, Level world, BlockPos pos, RandomSource random) {
		if (state.getValue(HALF) != DoubleBlockHalf.UPPER) {
			return;
		}
		// A low hum now and then, heard only close by
		if (random.nextInt(8) == 0) {
			world.playLocalSound(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, ObeliskSounds.HUM.get(), SoundSource.BLOCKS,
					0.35f, 0.9f + random.nextFloat() * 0.2f, false);
		}
		for (int i = 0; i < 3; i++) {
			// ENCHANT particles fly from (pos + velocity) back to pos, so this pulls them into the eye
			world.addParticle(ParticleTypes.ENCHANT, pos.getX() + 0.5, pos.getY() + 0.6, pos.getZ() + 0.5,
					(random.nextDouble() - 0.5) * 2.0, (random.nextDouble() - 0.3) * 1.5, (random.nextDouble() - 0.5) * 2.0);
		}
	}

	@Override
	@SuppressWarnings("deprecation")
	public BlockState rotate(BlockState state, Rotation rotation) {
		return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
	}

	@Override
	@SuppressWarnings("deprecation")
	public BlockState mirror(BlockState state, Mirror mirror) {
		return state.rotate(mirror.getRotation(state.getValue(FACING)));
	}
}
