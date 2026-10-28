package euf.enderobelisks.test;

import euf.enderobelisks.EnderObelisks;
import euf.enderobelisks.ObeliskConfig;
import euf.enderobelisks.ObeliskInfo;
import euf.enderobelisks.ObeliskService;
import euf.enderobelisks.ObeliskService.TeleportResult;
import euf.enderobelisks.ObeliskTiers;
import euf.enderobelisks.ReturnShardItem;
import euf.enderobelisks.block.ObeliskBlock;
import euf.enderobelisks.block.ObeliskBlockEntity;
import euf.enderobelisks.block.RuinedObeliskBlock;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

public class ObeliskGameTests implements FabricGameTest {
	/** Sets both halves the way placing does (upper first, so the lower half never sees air above it). */
	static void setObelisk(ServerLevel world, BlockPos lower, Direction facing) {
		BlockState state = EnderObelisks.OBELISK.get().getDefaultState().with(ObeliskBlock.FACING, facing);
		world.setBlockAndUpdate(lower.above(), state.setValue(ObeliskBlock.HALF, DoubleBlockHalf.UPPER));
		world.setBlockAndUpdate(lower, state.setValue(ObeliskBlock.HALF, DoubleBlockHalf.LOWER));
	}

	@GameTest(template = EMPTY_STRUCTURE)
	public void placingRegistersAndBreakingTheTopRemovesEverything(GameTestHelper ctx) {
		ServerLevel world = ctx.getLevel();
		BlockPos lower = ctx.absolutePos(new BlockPos(2, 2, 2));
		setObelisk(world, lower, Direction.NORTH);
		ObeliskInfo info = ObeliskService.register(world, lower, null, "Test");

		check(ObeliskService.at(world, lower).isPresent(), "obelisk not in the registry");
		check(info.name.equals("Test") && info.tier == 0 && info.range() == 500 && info.isPublic,
				"new obelisk should be public, tier 0, 500 blocks, named Test: " + info.name + " " + info.tier);
		check(world.getBlockEntity(lower) instanceof ObeliskBlockEntity entity && info.id.equals(entity.getObeliskId())
				&& entity.getName().equals("Test"), "the lower half should carry the synced block entity");
		check(world.getBlockEntity(lower.above()) == null, "the upper half should have no block entity");
		check(ObeliskService.register(world, lower, null, "Again").id.equals(info.id), "registering twice should keep one entry");

		world.destroyBlock(lower.above(), false);
		check(world.getBlockState(lower).isAir(), "breaking the top half should remove the bottom half");
		check(ObeliskService.at(world, lower).isEmpty(), "a broken obelisk should leave the registry");
		ctx.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE)
	public void tiersGrowFrom500To5000(GameTestHelper ctx) {
		int[] expected = {500, 1000, 2000, 3500, 5000};
		for (int tier = 0; tier <= ObeliskTiers.MAX_TIER; tier++) {
			check(ObeliskTiers.range(tier) == expected[tier], "tier " + tier + " range " + ObeliskTiers.range(tier));
		}
		check(ObeliskTiers.range(99) == 5000 && ObeliskTiers.range(-1) == 500, "range must clamp");
		check(ObeliskTiers.upgradeCost(3).get(0).isOf(Items.NETHER_STAR), "the last upgrade costs a nether star");
		check(ObeliskTiers.upgradeCost(ObeliskTiers.MAX_TIER).isEmpty(), "no upgrade past the max tier");
		check(ObeliskService.clampName("  " + "x".repeat(50) + " ").length() == ObeliskService.MAX_NAME_LENGTH, "names are clamped");
		ctx.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE)
	public void recipesUseTheTimeShard(GameTestHelper ctx) {
		ServerLevel world = ctx.getLevel();
		Recipe<?> shard = world.getRecipeManager().byKey(EnderObelisks.id("time_shard"))
				.orElseThrow(() -> new GameTestException("no time shard recipe"));
		check(shard.getResultItem(world.registryAccess()).is(EnderObelisks.TIME_SHARD.get()), "the time shard recipe makes a time shard");
		check(shard.getIngredients().stream().anyMatch(i -> i.test(new ItemStack(Items.REDSTONE)))
				&& shard.getIngredients().stream().anyMatch(i -> i.test(new ItemStack(Items.EMERALD))), "time shard = redstone + emeralds");
		Recipe<?> obelisk = world.getRecipeManager().byKey(EnderObelisks.id("obelisk"))
				.orElseThrow(() -> new GameTestException("no obelisk recipe"));
		for (ItemStack needed : List.of(new ItemStack(EnderObelisks.TIME_SHARD.get()), new ItemStack(Items.DIAMOND),
				new ItemStack(Items.OBSIDIAN), new ItemStack(Items.ENDER_PEARL))) {
			check(obelisk.getIngredients().stream().anyMatch(i -> i.test(needed)), "the obelisk recipe needs " + needed.getItem());
		}
		ctx.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = "player")
	public void teleportMovesThePlayerAndStartsTheCooldown(GameTestHelper ctx) {
		ServerLevel world = ctx.getLevel();
		BlockPos a = ctx.absolutePos(new BlockPos(1, 2, 1));
		BlockPos b = ctx.absolutePos(new BlockPos(6, 2, 6));
		for (int x = -1; x <= 1; x++) {
			for (int z = -1; z <= 1; z++) {
				world.setBlockAndUpdate(a.offset(x, -1, z), Blocks.STONE.defaultBlockState());
				world.setBlockAndUpdate(b.offset(x, -1, z), Blocks.STONE.defaultBlockState());
			}
		}
		setObelisk(world, a, Direction.SOUTH);
		setObelisk(world, b, Direction.NORTH);
		ObeliskInfo infoA = ObeliskService.register(world, a, null, "A");
		ObeliskInfo infoB = ObeliskService.register(world, b, null, "B");

		ServerPlayer player = (ServerPlayer) ctx.makeMockServerPlayerInLevel();
		player.moveTo(a.getX() + 0.5, a.getY(), a.getZ() + 1.5, 0, 0);
		ObeliskService.clearCooldown(player);

		check(ObeliskService.teleport(player, a, infoA.id) == TeleportResult.SAME, "jumping to yourself is refused");
		TeleportResult first = ObeliskService.teleport(player, a, infoB.id);
		check(first == TeleportResult.OK, "teleport A -> B failed: " + first);
		check(player.position().closerThan(b.getCenter(), 2.5), "player should land next to B, is at " + player.position());
		check(ObeliskService.cooldownLeft(player) > 0, "a jump should start the cooldown");
		check(ObeliskService.teleport(player, b, infoA.id) == TeleportResult.COOLDOWN, "a second jump during the cooldown is refused");
		check(ObeliskService.teleport(player, a, infoB.id) == TeleportResult.NO_SOURCE, "you must stand at the source obelisk");

		ObeliskService.clearCooldown(player);
		check(ObeliskService.teleport(player, b, infoA.id) == TeleportResult.OK, "after the cooldown B -> A works");

		world.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
		world.destroyBlock(a, false);
		world.destroyBlock(b, false);
		ctx.succeed();
	}

	/** A floor of stone under a 3x3 around each spot, so players and pets have somewhere to land. */
	static void floor(ServerLevel world, BlockPos... spots) {
		for (BlockPos spot : spots) {
			for (int x = -1; x <= 1; x++) {
				for (int z = -1; z <= 1; z++) {
					world.setBlockAndUpdate(spot.offset(x, -1, z), Blocks.STONE.defaultBlockState());
				}
			}
		}
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = "warmup", timeoutTicks = 300)
	public void warmupChannelsThenJumpsAndMovingCancelsIt(GameTestHelper ctx) {
		ServerLevel world = ctx.getLevel();
		BlockPos a = ctx.absolutePos(new BlockPos(1, 2, 1));
		BlockPos b = ctx.absolutePos(new BlockPos(6, 2, 6));
		floor(world, a, b);
		setObelisk(world, a, Direction.SOUTH);
		setObelisk(world, b, Direction.NORTH);
		ObeliskService.register(world, a, null, "A");
		ObeliskInfo infoB = ObeliskService.register(world, b, null, "B");
		ServerPlayer player = (ServerPlayer) ctx.makeMockServerPlayerInLevel();
		player.moveTo(a.getX() + 0.5, a.getY(), a.getZ() + 1.5, 0, 0);
		ObeliskService.clearCooldown(player);
		int warmup = ObeliskConfig.get().warmupSeconds * 20;

		// First try: the player steps away halfway through, so the jump is called off
		check(ObeliskService.startTeleport(player, a, infoB.id) == TeleportResult.OK, "the warmup should start");
		check(ObeliskService.isChanneling(player), "the player should be channelling");
		ctx.runAfterDelay(warmup / 2, () -> {
			player.moveTo(a.getX() + 0.5, a.getY(), a.getZ() + 2.5, 0, 0);
			ctx.runAfterDelay(2, () -> {
				check(!ObeliskService.isChanneling(player), "moving should cancel the warmup");
				check(player.position().closerThan(a.getCenter(), 3), "the player should still be at A");
				// Second try: stand still until it fires
				player.moveTo(a.getX() + 0.5, a.getY(), a.getZ() + 1.5, 0, 0);
				check(ObeliskService.startTeleport(player, a, infoB.id) == TeleportResult.OK, "the second warmup should start");
				ctx.runAfterDelay(warmup + 5, () -> {
					check(player.position().closerThan(b.getCenter(), 2.5), "after the warmup the player should be at B, is at " + player.position());
					world.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
					world.destroyBlock(a, false);
					world.destroyBlock(b, false);
					ctx.succeed();
				});
			});
		});
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = "pets")
	public void petsAndLeashedAnimalsComeAlong(GameTestHelper ctx) {
		ServerLevel world = ctx.getLevel();
		BlockPos a = ctx.absolutePos(new BlockPos(1, 2, 1));
		BlockPos b = ctx.absolutePos(new BlockPos(6, 2, 6));
		floor(world, a, b);
		setObelisk(world, a, Direction.SOUTH);
		setObelisk(world, b, Direction.NORTH);
		ObeliskService.register(world, a, null, "A");
		ObeliskInfo infoB = ObeliskService.register(world, b, null, "B");
		ServerPlayer player = (ServerPlayer) ctx.makeMockServerPlayerInLevel();
		player.moveTo(a.getX() + 0.5, a.getY(), a.getZ() + 1.5, 0, 0);
		ObeliskService.clearCooldown(player);

		Wolf wolf = EntityType.WOLF.create(world);
		wolf.moveTo(a.getX() + 1.5, a.getY(), a.getZ() + 1.5, 0, 0);
		wolf.tame(player);
		wolf.setOrderedToSit(false);
		world.addFreshEntity(wolf);
		Wolf sitter = EntityType.WOLF.create(world);
		sitter.moveTo(a.getX() - 0.5, a.getY(), a.getZ() + 1.5, 0, 0);
		sitter.tame(player);
		sitter.setOrderedToSit(true);
		world.addFreshEntity(sitter);
		Cow cow = EntityType.COW.create(world);
		cow.moveTo(a.getX() + 0.5, a.getY(), a.getZ() + 2.5, 0, 0);
		world.addFreshEntity(cow);
		cow.setLeashedTo(player, true);

		check(ObeliskService.teleport(player, a, infoB.id) == TeleportResult.OK, "teleport failed");
		check(wolf.position().closerThan(b.getCenter(), 3), "a following pet should come along, is at " + wolf.position());
		check(cow.position().closerThan(b.getCenter(), 3), "an animal on the lead should come along, is at " + cow.position());
		check(sitter.position().closerThan(a.getCenter(), 3), "a pet told to sit should stay");

		wolf.discard();
		sitter.discard();
		cow.discard();
		world.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
		world.destroyBlock(a, false);
		world.destroyBlock(b, false);
		ctx.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE)
	public void pistonsCannotMoveIt(GameTestHelper ctx) {
		ServerLevel world = ctx.getLevel();
		BlockPos lower = ctx.absolutePos(new BlockPos(4, 2, 3));
		setObelisk(world, lower, Direction.NORTH);
		ObeliskService.register(world, lower, null, "Pushed");
		BlockPos piston = lower.west();
		world.setBlockAndUpdate(piston, Blocks.PISTON.defaultBlockState().setValue(DirectionalBlock.FACING, Direction.EAST));
		world.setBlockAndUpdate(piston.west(), Blocks.REDSTONE_BLOCK.defaultBlockState());
		ctx.runAfterDelay(10, () -> {
			check(world.getBlockState(lower).is(EnderObelisks.OBELISK.get()) && world.getBlockState(lower.above()).is(EnderObelisks.OBELISK.get()),
					"a piston must not move or break the obelisk");
			check(ObeliskService.at(world, lower).isPresent(), "the obelisk should still be registered");
			world.destroyBlock(lower, false);
			ctx.succeed();
		});
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = "player")
	public void strangersCannotBreakItEvenInCreative(GameTestHelper ctx) {
		ServerLevel world = ctx.getLevel();
		BlockPos lower = ctx.absolutePos(new BlockPos(3, 2, 3));
		setObelisk(world, lower, Direction.NORTH);
		ObeliskService.register(world, lower, null, "Not yours");
		ServerPlayer stranger = (ServerPlayer) ctx.makeMockServerPlayerInLevel();
		boolean op = stranger.hasPermissions(2);
		boolean broke = stranger.gameMode.destroyBlock(lower.above());
		check(broke == op, "a creative stranger broke someone else's obelisk");
		check(world.getBlockState(lower).is(EnderObelisks.OBELISK.get()) || op, "the obelisk should still stand");
		world.removePlayerImmediately(stranger, Entity.RemovalReason.DISCARDED);
		world.destroyBlock(lower, false);
		ctx.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = "player")
	public void ownerSetsColourAndUnknownFriendsAreRefused(GameTestHelper ctx) {
		ServerLevel world = ctx.getLevel();
		BlockPos lower = ctx.absolutePos(new BlockPos(2, 2, 2));
		setObelisk(world, lower, Direction.NORTH);
		ServerPlayer owner = (ServerPlayer) ctx.makeMockServerPlayerInLevel();
		owner.moveTo(lower.getX() + 0.5, lower.getY(), lower.getZ() - 1.5, 0, 0);
		ObeliskInfo info = ObeliskService.register(world, lower, owner, "Mine");
		check(ObeliskService.setColor(owner, lower, 5) && info.color == 5, "the owner should set the colour");
		check(world.getBlockEntity(lower) instanceof ObeliskBlockEntity entity && entity.getColor() == 5,
				"the colour should reach the block entity (name plate)");
		check(!ObeliskService.addFriend(owner, lower, "NobodyEverPlayedHere"), "an unknown name must be refused");
		check(info.friends.isEmpty(), "nobody should have been added");
		world.removePlayerImmediately(owner, Entity.RemovalReason.DISCARDED);
		world.destroyBlock(lower, false);
		ctx.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = "player")
	public void returnShardBringsYouBackToItsObelisk(GameTestHelper ctx) {
		ServerLevel world = ctx.getLevel();
		BlockPos home = ctx.absolutePos(new BlockPos(1, 2, 1));
		floor(world, home);
		setObelisk(world, home, Direction.SOUTH);
		ObeliskInfo info = ObeliskService.register(world, home, null, "Home");
		ServerPlayer player = (ServerPlayer) ctx.makeMockServerPlayerInLevel();
		player.moveTo(home.getX() + 6.5, home.getY(), home.getZ() + 6.5, 0, 0);

		ItemStack unbound = new ItemStack(EnderObelisks.RETURN_SHARD.get());
		check(ReturnShardItem.goHome(player, unbound) == TeleportResult.NO_TARGET, "an unbound shard goes nowhere");
		ItemStack shard = new ItemStack(EnderObelisks.RETURN_SHARD.get());
		ReturnShardItem.bind(shard, info);
		check(ReturnShardItem.goHome(player, shard) == TeleportResult.OK, "the bound shard should bring the player back");
		check(player.position().closerThan(home.getCenter(), 2.5), "the player should be next to home, is at " + player.position());

		world.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
		world.destroyBlock(home, false);
		ctx.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE)
	public void ruinTemplateLoadsAndIsRegisteredForWorldGeneration(GameTestHelper ctx) {
		ServerLevel world = ctx.getLevel();
		ResourceLocation id = EnderObelisks.id("ruined_obelisk");
		check(world.registryAccess().registryOrThrow(Registries.STRUCTURE).containsKey(id), "the ruin structure is not registered");
		StructureTemplate template = world.getStructureManager().get(id)
				.orElseThrow(() -> new GameTestAssertException("the ruin template did not load"));
		check(template.getSize().equals(new Vec3i(7, 5, 7)), "the ruin should be 7x5x7, is " + template.getSize());
		BlockPos origin = ctx.absolutePos(new BlockPos(0, 1, 0));
		template.placeInWorld(world, origin, origin, new StructurePlaceSettings(), world.getRandom(), Block.UPDATE_CLIENTS);
		check(world.getBlockState(origin.offset(3, 1, 3)).is(EnderObelisks.RUINED_OBELISK.get()), "the ruin should have its ruined obelisk");
		check(world.getBlockState(origin.offset(1, 0, 1)).is(Blocks.CHEST), "the ruin should have its chest");
		ctx.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = "player")
	public void aTimeShardRestoresARuin(GameTestHelper ctx) {
		ServerLevel world = ctx.getLevel();
		BlockPos ruin = ctx.absolutePos(new BlockPos(3, 2, 3));
		world.setBlockAndUpdate(ruin, EnderObelisks.RUINED_OBELISK.get().getDefaultState());
		ServerPlayer player = (ServerPlayer) ctx.makeMockServerPlayerInLevel();
		player.moveTo(ruin.getX() + 0.5, ruin.getY(), ruin.getZ() - 1.5, 0, 0);
		ItemStack shard = new ItemStack(EnderObelisks.TIME_SHARD.get());
		check(RuinedObeliskBlock.restore(world, ruin, player, shard), "the restoration failed");
		check(world.getBlockState(ruin).is(EnderObelisks.OBELISK.get()) && world.getBlockState(ruin.above()).is(EnderObelisks.OBELISK.get()),
				"the ruin should now be a two-block obelisk");
		check(ObeliskService.at(world, ruin).map(o -> o.owner.equals(player.getUuid())).orElse(false),
				"the restored obelisk should belong to the player");
		world.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
		world.destroyBlock(ruin, false);
		ctx.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = "player")
	public void rangeAndAccessRules(GameTestHelper ctx) {
		ServerLevel world = ctx.getLevel();
		ServerPlayer player = (ServerPlayer) ctx.makeMockServerPlayerInLevel();
		ObeliskService.clearCooldown(player);
		boolean op = player.hasPermissions(2);
		BlockPos origin = ctx.absolutePos(BlockPos.ZERO);
		UUID stranger = UUID.randomUUID();

		ObeliskInfo source = new ObeliskInfo(UUID.randomUUID(), world.dimension(), origin, player.getUUID(), "me", "src", true, 0);
		ObeliskInfo near = new ObeliskInfo(UUID.randomUUID(), world.dimension(), origin.offset(400, 0, 0), stranger, "x", "near", true, 0);
		ObeliskInfo far = new ObeliskInfo(UUID.randomUUID(), world.dimension(), origin.offset(700, 0, 0), stranger, "x", "far", true, 0);
		ObeliskInfo secret = new ObeliskInfo(UUID.randomUUID(), world.dimension(), origin.offset(100, 0, 0), stranger, "x", "secret", false, 0);

		check(ObeliskService.check(player, source, near) == TeleportResult.OK, "400 blocks is within tier 0");
		check(ObeliskService.check(player, source, far) == TeleportResult.TOO_FAR, "700 blocks is beyond tier 0");
		source.tier = 1;
		check(ObeliskService.check(player, source, far) == TeleportResult.OK, "700 blocks is within tier 1 (1000)");
		check(ObeliskService.check(player, source, secret) == (op ? TeleportResult.OK : TeleportResult.NO_ACCESS),
				"someone else's private obelisk is closed");
		secret.friends.put(player.getUUID(), "me");
		check(ObeliskService.check(player, source, secret) == TeleportResult.OK, "a friend may use a private obelisk");
		ObeliskInfo nether = new ObeliskInfo(UUID.randomUUID(), Level.NETHER, origin, stranger, "x", "nether", true, 4);
		check(ObeliskService.check(player, source, nether) == TeleportResult.OTHER_DIMENSION,
				"jumps between dimensions are off by default");

		// Breaking: only the owner (or an operator)
		BlockPos lower = ctx.absolutePos(new BlockPos(3, 2, 3));
		setObelisk(world, lower, Direction.NORTH);
		ObeliskInfo owned = ObeliskService.register(world, lower, null, "Owned by nobody here");
		check(ObeliskBlock.mayBreak(world.getBlockState(lower), player, world, lower) == op, "a stranger may not break it");
		check(world.getBlockState(lower).getDestroyProgress(player, world, lower) == 0.0f || op,
				"mining someone else's obelisk makes no progress");
		check(owned.isPublic, "obelisks start public");

		world.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
		world.destroyBlock(lower, false);
		ctx.succeed();
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new GameTestAssertException(message);
		}
	}
}
