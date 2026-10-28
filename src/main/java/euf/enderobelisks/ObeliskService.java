package euf.enderobelisks;

import com.mojang.authlib.GameProfile;
import euf.enderobelisks.block.ObeliskBlock;
import euf.enderobelisks.block.ObeliskBlockEntity;
import euf.enderobelisks.net.ObeliskPackets;
import euf.enderobelisks.net.ObeliskSync;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Server-side rules: placing, access, teleporting, upgrading, renaming. Every packet handler goes through here. */
public final class ObeliskService {
	public static final int MAX_NAME_LENGTH = 32;
	/** How close a player must stand to an obelisk to work its menu. */
	public static final double USE_DISTANCE = 6.0;
	/** Moving further than this from where the warmup started cancels it. */
	private static final double WARMUP_MOVE_LIMIT = 0.6;

	public enum TeleportResult { OK, NO_SOURCE, NO_TARGET, SAME, NO_ACCESS, TOO_FAR, COOLDOWN, NO_SPACE, OTHER_DIMENSION, BUSY }

	/** A jump that is still channelling. */
	private record Pending(ServerPlayer player, BlockPos source, UUID target, long startTick, Vec3 startPos) {}

	private static final Map<UUID, Long> lastTeleport = new HashMap<>();
	private static final Map<UUID, Pending> pending = new HashMap<>();

	private ObeliskService() {
	}

	/** Registers a freshly placed obelisk; {@code owner} may be null for obelisks set by commands or tests. */
	public static ObeliskInfo register(ServerLevel world, BlockPos lower, @Nullable Player owner, @Nullable String customName) {
		ObeliskRegistry registry = ObeliskRegistry.get(world.getServer());
		Optional<ObeliskInfo> existing = registry.at(world.dimension(), lower);
		if (existing.isPresent()) {
			return existing.get();
		}
		UUID ownerId = owner != null ? owner.getUUID() : new UUID(0, 0);
		String ownerName = owner != null ? owner.getGameProfile().getName() : "?";
		String name = customName != null && !customName.isBlank() ? clampName(customName)
				: Component.translatableWithFallback("ender_obelisks.default_name", "Obelisk #%s", registry.all().size() + 1).getString();
		ObeliskInfo info = new ObeliskInfo(UUID.randomUUID(), world.dimension(), lower, ownerId, ownerName, name, true, 0);
		registry.put(info);
		syncBlockEntity(world, info);
		ObeliskSync.broadcast(world.getServer());
		return info;
	}

	public static void unregister(ServerLevel world, BlockPos lower) {
		ObeliskRegistry registry = ObeliskRegistry.get(world.getServer());
		registry.at(world.dimension(), lower).ifPresent(info -> {
			registry.remove(info.id);
			ObeliskSync.broadcast(world.getServer());
		});
	}

	public static Optional<ObeliskInfo> at(ServerLevel world, BlockPos lower) {
		return ObeliskRegistry.get(world.getServer()).at(world.dimension(), lower);
	}

	public static boolean isOwner(ObeliskInfo info, Player player) {
		return info.owner.equals(player.getUUID()) || player.hasPermissions(2);
	}

	/** Public obelisks are open to everyone; private ones to their owner, operators and the owner's friends list. */
	public static boolean canUse(ObeliskInfo info, Player player) {
		return info.isPublic || isOwner(info, player) || info.friends.containsKey(player.getUUID());
	}

	public static int cooldownLeft(ServerPlayer player) {
		Long last = lastTeleport.get(player.getUUID());
		if (last == null) {
			return 0;
		}
		return (int) Math.max(0, last + ObeliskConfig.get().cooldownSeconds * 20L - player.getServer().getTickCount());
	}

	/** Forgets cooldowns and channelling jumps when a server (or a singleplayer world) stops. */
	public static void reset() {
		lastTeleport.clear();
		pending.clear();
	}

	/** For tests: lets the next teleport go through at once. */
	public static void clearCooldown(Player player) {
		lastTeleport.remove(player.getUUID());
	}

	public static boolean isNear(ServerPlayer player, BlockPos lower) {
		return player.position().distanceToSqr(Vec3.atCenterOf(lower.above())) <= USE_DISTANCE * USE_DISTANCE;
	}

	public static TeleportResult check(ServerPlayer player, ObeliskInfo source, @Nullable ObeliskInfo target) {
		if (target == null) {
			return TeleportResult.NO_TARGET;
		}
		if (target.id.equals(source.id)) {
			return TeleportResult.SAME;
		}
		if (!canUse(source, player) || !canUse(target, player)) {
			return TeleportResult.NO_ACCESS;
		}
		if (!target.dimension.equals(source.dimension)) {
			ObeliskConfig config = ObeliskConfig.get();
			if (!config.crossDimension || source.tier < config.crossDimensionMinTier) {
				return TeleportResult.OTHER_DIMENSION;
			}
		} else if (source.distanceTo(target) > source.range()) {
			return TeleportResult.TOO_FAR;
		}
		if (cooldownLeft(player) > 0) {
			return TeleportResult.COOLDOWN;
		}
		return TeleportResult.OK;
	}

	/**
	 * What the menu's Teleport button does: checks the jump, then channels it for the configured warmup (moving or
	 * taking damage cancels it) and only then teleports. With no warmup it jumps at once.
	 */
	public static TeleportResult startTeleport(ServerPlayer player, BlockPos sourcePos, UUID targetId) {
		if (pending.containsKey(player.getUUID())) {
			return TeleportResult.BUSY;
		}
		Optional<ObeliskInfo> source = at(player.serverLevel(), sourcePos);
		if (source.isEmpty() || !isNear(player, sourcePos)) {
			return TeleportResult.NO_SOURCE;
		}
		TeleportResult result = check(player, source.get(), ObeliskRegistry.get(player.getServer()).byId(targetId).orElse(null));
		if (result != TeleportResult.OK) {
			return result;
		}
		if (ObeliskConfig.get().warmupSeconds <= 0) {
			return teleport(player, sourcePos, targetId);
		}
		pending.put(player.getUUID(), new Pending(player, sourcePos, targetId, player.getServer().getTickCount(), player.position()));
		player.serverLevel().playSound(null, player.getX(), player.getY(), player.getZ(), ObeliskSounds.CHANNEL.get(),
				SoundSource.PLAYERS, 0.6f, 1.0f);
		return TeleportResult.OK;
	}

	public static boolean isChanneling(Player player) {
		return pending.containsKey(player.getUUID());
	}

	/** Called when a player takes damage: a channelling jump is broken off. */
	public static void onDamaged(ServerPlayer player) {
		if (pending.remove(player.getUUID()) != null) {
			cancelled(player, "ender_obelisks.teleport.interrupted_damage");
		}
	}

	/** Every server tick: draws the channelling effect, cancels jumps of players who moved, finishes the rest. */
	public static void tick(MinecraftServer server) {
		int warmupTicks = ObeliskConfig.get().warmupSeconds * 20;
		for (Iterator<Map.Entry<UUID, Pending>> it = pending.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<UUID, Pending> entry = it.next();
			Pending jump = entry.getValue();
			ServerPlayer player = jump.player();
			if (player.isRemoved() || !player.isAlive()) {
				it.remove();
				continue;
			}
			if (player.position().distanceToSqr(jump.startPos()) > WARMUP_MOVE_LIMIT * WARMUP_MOVE_LIMIT) {
				it.remove();
				cancelled(player, "ender_obelisks.teleport.interrupted_move");
				continue;
			}
			long elapsed = server.getTickCount() - jump.startTick();
			if (elapsed >= warmupTicks) {
				it.remove();
				TeleportResult result = teleport(player, jump.source(), jump.target());
				if (result != TeleportResult.OK) {
					player.displayClientMessage(Component.translatable("ender_obelisks.teleport." + result.name().toLowerCase(Locale.ROOT)), true);
				}
				continue;
			}
			// A spiral of portal particles closing in around the player, thicker as the jump nears
			ServerLevel world = player.serverLevel();
			float progress = (float) elapsed / warmupTicks;
			for (int i = 0; i < 2 + (int) (progress * 6); i++) {
				double a = (elapsed * 0.35) + i * Math.PI * 2 / 3;
				double r = 1.2 - progress * 0.8;
				world.sendParticles(ParticleTypes.REVERSE_PORTAL, player.getX() + Math.cos(a) * r, player.getY() + 0.1 + progress * 1.8,
						player.getZ() + Math.sin(a) * r, 1, 0, 0, 0, 0);
			}
			if (elapsed % 20 == 0) {
				int secondsLeft = Mth.ceil((warmupTicks - elapsed) / 20.0f);
				player.displayClientMessage(Component.translatable("ender_obelisks.teleport.channeling", secondsLeft), true);
			}
		}
	}

	private static void cancelled(ServerPlayer player, String message) {
		player.displayClientMessage(Component.translatable(message), true);
		player.serverLevel().playSound(null, player.getX(), player.getY(), player.getZ(), ObeliskSounds.CANCEL.get(),
				SoundSource.PLAYERS, 0.6f, 1.0f);
	}

	/** The jump itself, with no warmup: re-checks everything, moves the player and their pets, starts the cooldown. */
	public static TeleportResult teleport(ServerPlayer player, BlockPos sourcePos, UUID targetId) {
		ServerLevel world = player.serverLevel();
		Optional<ObeliskInfo> source = at(world, sourcePos);
		if (source.isEmpty() || !isNear(player, sourcePos)) {
			return TeleportResult.NO_SOURCE;
		}
		ObeliskRegistry registry = ObeliskRegistry.get(world.getServer());
		ObeliskInfo target = registry.byId(targetId).orElse(null);
		TeleportResult result = check(player, source.get(), target);
		if (result != TeleportResult.OK) {
			return result;
		}
		TeleportResult arrived = arrive(player, target);
		if (arrived == TeleportResult.OK) {
			lastTeleport.put(player.getUUID(), (long) world.getServer().getTickCount());
			ObeliskCriteria.TELEPORTED.get().trigger(player, target.dimension.equals(source.get().dimension) ? source.get().distanceTo(target) : 0);
		}
		return arrived;
	}

	/**
	 * Moves the player (and their pets) next to an obelisk, with no range or cooldown checks: the end of an obelisk
	 * jump, and what a return shard does.
	 */
	public static TeleportResult arrive(ServerPlayer player, ObeliskInfo target) {
		ServerLevel world = player.serverLevel();
		ServerLevel targetWorld = world.getServer().getLevel(target.dimension);
		// Loads the target chunk; an obelisk destroyed while its chunk was unloaded is dropped from the registry here
		if (targetWorld == null || !(targetWorld.getBlockState(target.pos).getBlock() instanceof ObeliskBlock)) {
			ObeliskRegistry.get(world.getServer()).remove(target.id);
			ObeliskSync.broadcast(world.getServer());
			return TeleportResult.NO_TARGET;
		}
		Optional<Vec3> landing = findLanding(targetWorld, target.pos);
		if (landing.isEmpty()) {
			return TeleportResult.NO_SPACE;
		}

		List<Entity> companions = ObeliskConfig.get().takePets ? companions(player) : List.of();
		effects(world, player.position());
		Vec3 spot = landing.get();
		// Face away from the obelisk you arrive at
		Vec3 away = spot.subtract(Vec3.atBottomCenterOf(target.pos));
		float yaw = (float) (Math.atan2(-away.x, away.z) * 180.0 / Math.PI);
		player.teleportTo(targetWorld, spot.x, spot.y, spot.z, yaw, 0.0f);
		player.fallDistance = 0.0f;
		for (int i = 0; i < companions.size(); i++) {
			// Spread them a little around the landing spot so they don't stack inside each other
			double a = i * 2.4;
			companions.get(i).teleportTo(targetWorld, spot.x + Math.cos(a) * 0.6, spot.y, spot.z + Math.sin(a) * 0.6,
					EnumSet.noneOf(RelativeMovement.class), companions.get(i).getYRot(), companions.get(i).getXRot());
		}
		effects(targetWorld, spot);
		return TeleportResult.OK;
	}

	/** Tamed pets of this player that are not told to sit, and any mob on this player's lead. */
	static List<Entity> companions(ServerPlayer player) {
		double r = ObeliskConfig.get().petRadius;
		return player.serverLevel().getEntities(player, new AABB(player.blockPosition()).inflate(r), e ->
				(e instanceof TamableAnimal pet && pet.isTame() && player.getUUID().equals(pet.getOwnerUUID()) && !pet.isOrderedToSit())
						|| (e instanceof Mob mob && mob.getLeashHolder() == player));
	}

	/** Two free blocks of height with solid ground, on one of the four sides, a little up or down. */
	static Optional<Vec3> findLanding(ServerLevel world, BlockPos lower) {
		BlockState state = world.getBlockState(lower);
		Direction front = state.hasProperty(ObeliskBlock.FACING) ? state.getValue(ObeliskBlock.FACING) : Direction.NORTH;
		List<Direction> sides = List.of(front, front.getClockWise(), front.getCounterClockWise(), front.getOpposite());
		for (int dy : new int[] {0, 1, -1, 2}) {
			for (Direction side : sides) {
				BlockPos feet = lower.relative(side).above(dy);
				if (world.getBlockState(feet).getCollisionShape(world, feet).isEmpty()
						&& world.getBlockState(feet.above()).getCollisionShape(world, feet.above()).isEmpty()
						&& world.getBlockState(feet.below()).isFaceSturdy(world, feet.below(), Direction.UP)) {
					return Optional.of(Vec3.atBottomCenterOf(feet));
				}
			}
		}
		return Optional.empty();
	}

	private static void effects(ServerLevel world, Vec3 at) {
		world.sendParticles(ParticleTypes.PORTAL, at.x, at.y + 1.0, at.z, 60, 0.4, 0.8, 0.4, 0.6);
		world.playSound(null, at.x, at.y, at.z, ObeliskSounds.TELEPORT.get(), SoundSource.PLAYERS, 1.0f, 1.0f);
	}

	/** Consumes the next tier's cost (free in creative) and raises the tier. Owner only. */
	public static boolean upgrade(ServerPlayer player, BlockPos sourcePos) {
		ServerLevel world = player.serverLevel();
		Optional<ObeliskInfo> found = at(world, sourcePos);
		if (found.isEmpty() || !isNear(player, sourcePos) || !isOwner(found.get(), player)) {
			return false;
		}
		ObeliskInfo info = found.get();
		List<ItemStack> cost = ObeliskTiers.upgradeCost(info.tier);
		if (info.tier >= ObeliskTiers.MAX_TIER) {
			return false;
		}
		if (!player.getAbilities().instabuild) {
			Inventory inventory = player.getInventory();
			if (!cost.stream().allMatch(stack -> inventory.countItem(stack.getItem()) >= stack.getCount())) {
				return false;
			}
			cost.forEach(stack -> take(inventory, stack.getItem(), stack.getCount()));
		}
		info.tier++;
		changed(world.getServer(), world, info);
		world.playSound(null, sourcePos, ObeliskSounds.UPGRADE.get(), SoundSource.BLOCKS, 1.0f, 1.0f);
		ObeliskCriteria.UPGRADED.get().trigger(player, info.tier);
		return true;
	}

	private static void take(Inventory inventory, Item item, int count) {
		for (int i = 0; i < inventory.getContainerSize() && count > 0; i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.is(item)) {
				int n = Math.min(count, stack.getCount());
				stack.shrink(n);
				count -= n;
			}
		}
		inventory.setChanged();
	}

	public static boolean rename(ServerPlayer player, BlockPos sourcePos, String name) {
		Optional<ObeliskInfo> found = at(player.serverLevel(), sourcePos);
		String clean = clampName(name);
		if (found.isEmpty() || !isNear(player, sourcePos) || !isOwner(found.get(), player) || clean.isBlank()) {
			return false;
		}
		found.get().name = clean;
		changed(player.getServer(), player.serverLevel(), found.get());
		return true;
	}

	public static boolean togglePublic(ServerPlayer player, BlockPos sourcePos) {
		Optional<ObeliskInfo> found = at(player.serverLevel(), sourcePos);
		if (found.isEmpty() || !isNear(player, sourcePos) || !isOwner(found.get(), player)) {
			return false;
		}
		found.get().isPublic = !found.get().isPublic;
		changed(player.getServer(), player.serverLevel(), found.get());
		return true;
	}

	public static boolean setColor(ServerPlayer player, BlockPos sourcePos, int color) {
		Optional<ObeliskInfo> found = at(player.serverLevel(), sourcePos);
		if (found.isEmpty() || !isNear(player, sourcePos) || !isOwner(found.get(), player)) {
			return false;
		}
		found.get().color = Math.floorMod(color, ObeliskColors.RGB.length);
		changed(player.getServer(), player.serverLevel(), found.get());
		return true;
	}

	/** Lets a player into a private obelisk, by name: someone online, or anyone who has played on this server. */
	public static boolean addFriend(ServerPlayer player, BlockPos sourcePos, String name) {
		Optional<ObeliskInfo> found = at(player.serverLevel(), sourcePos);
		if (found.isEmpty() || !isNear(player, sourcePos) || !isOwner(found.get(), player) || name.isBlank()) {
			return false;
		}
		MinecraftServer server = player.getServer();
		ServerPlayer online = server.getPlayerList().getPlayerByName(name.strip());
		Optional<GameProfile> profile = online != null ? Optional.of(online.getGameProfile())
				: server.getProfileCache() == null ? Optional.empty() : server.getProfileCache().get(name.strip());
		if (profile.isEmpty() || profile.get().getId().equals(found.get().owner)) {
			return false;
		}
		found.get().friends.put(profile.get().getId(), profile.get().getName());
		changed(server, player.serverLevel(), found.get());
		return true;
	}

	public static boolean removeFriend(ServerPlayer player, BlockPos sourcePos, UUID friend) {
		Optional<ObeliskInfo> found = at(player.serverLevel(), sourcePos);
		if (found.isEmpty() || !isNear(player, sourcePos) || !isOwner(found.get(), player)) {
			return false;
		}
		found.get().friends.remove(friend);
		changed(player.getServer(), player.serverLevel(), found.get());
		return true;
	}

	/** After an edit: save, update the block entity, tell every client. */
	private static void changed(MinecraftServer server, ServerLevel world, ObeliskInfo info) {
		ObeliskRegistry.get(server).setDirty();
		syncBlockEntity(world, info);
		ObeliskSync.broadcast(server);
	}

	/** Opens (or refreshes) the obelisk menu for a player standing at it. */
	public static void openMenu(ServerPlayer player, BlockPos sourcePos) {
		at(player.serverLevel(), sourcePos).ifPresent(source -> {
			boolean crossDimension = ObeliskConfig.get().crossDimension;
			List<ObeliskInfo> visible = ObeliskRegistry.get(player.getServer()).all().stream()
					.filter(o -> (crossDimension || o.dimension.equals(source.dimension)) && canUse(o, player))
					.toList();
			ObeliskPackets.sendMenu(player, source, isOwner(source, player), cooldownLeft(player), visible);
		});
	}

	public static String clampName(String name) {
		String trimmed = name.strip().replaceAll("\\p{Cntrl}", "");
		return trimmed.length() > MAX_NAME_LENGTH ? trimmed.substring(0, MAX_NAME_LENGTH) : trimmed;
	}

	/** Copies what the renderer needs (name, tier) into the loaded block entity and sends it to clients. */
	static void syncBlockEntity(ServerLevel world, ObeliskInfo info) {
		if (world.hasChunkAt(info.pos) && world.getBlockEntity(info.pos) instanceof ObeliskBlockEntity entity) {
			entity.update(info.id, info.name, info.tier, info.color);
		}
	}
}
