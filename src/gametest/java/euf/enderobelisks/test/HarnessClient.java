package euf.enderobelisks.test;

import euf.enderobelisks.EnderObelisks;
import euf.enderobelisks.ObeliskInfo;
import euf.enderobelisks.ObeliskRegistry;
import euf.enderobelisks.ObeliskService;
import euf.enderobelisks.client.ObeliskClientCache;
import euf.enderobelisks.client.screen.ObeliskScreen;
import euf.enderobelisks.net.MenuData;
import euf.enderobelisks.net.ObeliskPackets;
import euf.enderobelisks.net.ObeliskSync;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import euf.enderobelisks.platform.Network.Client;
import euf.enderobelisks.platform.Network;
import net.minecraft.advancements.Advancement;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * With -Dender_obelisks.harness=true (runHarness): plays the obelisk scenario in a real client on a generated world.
 * It places an obelisk with a real click, builds a few more around it, photographs the model, opens the menu,
 * teleports, checks range, cooldown, ownership and upgrades, and photographs the map. Writes harness-result.txt;
 * scripts/ci-harness.sh fails the build unless it says PASS and every screenshot exists.
 */
public class HarnessClient {
	private static final Logger LOGGER = LoggerFactory.getLogger("ender_obelisks-harness");

	private record Step(int waitAfter, boolean needsTerrain, Consumer<Minecraft> action) {}

	private final Deque<Step> steps = new ArrayDeque<>();
	private final List<String> results = new ArrayList<>();
	private int ticks;
	private int nextStepTick = 60;

	// Obelisk sites, worked out on the server in the first step
	private volatile BlockPos home;
	private volatile BlockPos village;
	private volatile BlockPos outpost;
	private volatile BlockPos hideout;
	private volatile BlockPos secret;
	private volatile BlockPos ruin;

	public void onInitializeClient() {
		if (!Boolean.getBoolean("ender_obelisks.harness")) {
			return;
		}
		step(20, false, c -> {
			commands(c, "gamerule sendCommandFeedback false", "gamerule doDaylightCycle false", "gamerule doWeatherCycle false",
					"gamerule doMobSpawning false", "time set 1000", "weather clear", "gamemode creative @a",
					"give @a ender_obelisks:obelisk 4");
			onServer(c, server -> {
				ServerLevel world = server.overworld();
				BlockPos spawn = world.getSharedSpawnPos();
				home = prepareSite(world, spawn.getX() + 4, spawn.getZ());
				village = prepareSite(world, home.getX() + 300, home.getZ() + 40);
				outpost = prepareSite(world, home.getX() + 1400, home.getZ() - 300);
				hideout = prepareSite(world, home.getX() - 220, home.getZ() + 260);
				secret = prepareSite(world, home.getX() + 150, home.getZ() - 200);
				return null;
			});
		});
		// Stand west of the home site looking east, then place the obelisk with a real click on the ground
		step(40, true, c -> {
			c.player.getAbilities().flying = false;
			tp(c, Vec3.atBottomCenterOf(home).add(-2.5, 0, 0), Vec3.atCenterOf(home));
		});
		step(20, false, c -> {
			check("obelisk_item_in_hotbar", select(c));
			for (Item item : List.of(EnderObelisks.OBELISK_ITEM.get(), EnderObelisks.RUINED_OBELISK_ITEM.get(), EnderObelisks.TIME_SHARD.get(), EnderObelisks.RETURN_SHARD.get())) {
				List<Component> tooltip = new ItemStack(item).getTooltipLines(c.player, TooltipFlag.NORMAL);
				String key = "ender_obelisks.lore." + BuiltInRegistries.ITEM.getKey(item).getPath() + ".1";
				check("lore_" + BuiltInRegistries.ITEM.getKey(item).getPath(), tooltip.size() > 1
						&& tooltip.get(1).getContents() instanceof TranslatableContents t && t.getKey().equals(key));
			}
		});
		step(20, false, c -> {
			BlockPos ground = home.below();
			BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(ground).add(0, 0.5, 0), Direction.UP, ground, false);
			check("place_click_accepted", c.gameMode.useItemOn(c.player, InteractionHand.MAIN_HAND, hit).consumesAction());
		});
		step(20, false, c -> {
			UUID me = c.player.getUUID();
			check("placed_two_halves", onServer(c, s -> s.overworld().getBlockState(home).is(EnderObelisks.OBELISK.get())
					&& s.overworld().getBlockState(home.above()).is(EnderObelisks.OBELISK.get())));
			check("registered_with_owner", onServer(c, s -> ObeliskService.at(s.overworld(), home)
					.map(o -> o.owner.equals(me)).orElse(false)));
			check("advancement_place", advancementDone(c, "place_obelisk"));
			// The rest of the network: a public village in range, a public outpost out of range,
			// my own private hideout, and someone else's private obelisk that must stay hidden
			onServer(c, s -> {
				ServerLevel world = s.overworld();
				build(world, village, null, "Деревня", "Steve", true);
				build(world, outpost, null, "Далёкий форпост", "Alex", true);
				build(world, hideout, c.player.getUUID(), "Мой тайник", c.player.getGameProfile().getName(), false);
				build(world, secret, UUID.randomUUID(), "Чужой секрет", "Notch", false);
				return null;
			});
		});

		// The model by day: the eye watches the player, so the pupil looks into the camera
		step(60, true, c -> {
			c.options.hideGui = true;
			tp(c, Vec3.atBottomCenterOf(home).add(-3.2, 0, 0.6), Vec3.atBottomCenterOf(home).add(0, 1.2, 0));
		});
		step(40, true, c -> screenshot(c, "obelisk_front"));
		// Cameras sit off the diagonals: on a diagonal a corner bar of the cage hides the pupil
		step(40, false, c -> fly(c, Vec3.atBottomCenterOf(home).add(-2.0, 0.7, -0.8), Vec3.atBottomCenterOf(home).add(0, 1.6, 0)));
		step(30, true, c -> screenshot(c, "obelisk_cage"));
		// Walk around it: the eye should have turned to follow
		step(40, false, c -> fly(c, Vec3.atBottomCenterOf(home).add(0.9, 0.5, 2.4), Vec3.atBottomCenterOf(home).add(0, 1.6, 0)));
		step(30, true, c -> screenshot(c, "obelisk_eye_follows"));
		step(30, false, c -> {
			commands(c, "time set 18000");
			fly(c, Vec3.atBottomCenterOf(home).add(-3.5, 0.4, 1.5), Vec3.atBottomCenterOf(home).add(0, 1.2, 0));
		});
		step(60, true, c -> screenshot(c, "obelisk_night"));

		// Open the menu with a real right click on the obelisk
		step(20, false, c -> {
			commands(c, "time set 1000");
			c.player.getAbilities().flying = false;
			c.player.onUpdateAbilities();
			c.options.hideGui = false;
			tp(c, Vec3.atBottomCenterOf(home).add(-2.5, 0, 0.5), Vec3.atCenterOf(home));
		});
		step(40, true, c -> rightClick(c, home));
		step(40, false, c -> {
			ObeliskScreen screen = screen(c);
			check("menu_opened", screen != null);
			if (screen != null) {
				MenuData data = screen.getData();
				check("menu_lists_visible_obelisks", names(data).containsAll(List.of("Деревня", "Далёкий форпост", "Мой тайник")));
				check("menu_hides_foreign_private", !names(data).contains("Чужой секрет"));
			}
			screenshot(c, "menu");
		});
		step(10, false, c -> selectByName(c, "Деревня"));
		step(20, false, c -> screenshot(c, "menu_selected"));

		// Out of range: the outpost is ~1430 blocks away, tier 0 reaches 500
		step(10, false, c -> {
			selectByName(c, "Далёкий форпост");
			ObeliskScreen screen = screen(c);
			if (screen != null) {
				screen.teleportSelected();
			}
		});
		step(40, false, c -> check("too_far_refused", c.player.position().closerThan(Vec3.atCenterOf(home), 6)));

		// Start a jump, then step away during the warmup: it must break off
		step(10, false, c -> selectByName(c, "Деревня"));
		step(20, false, c -> {
			ObeliskScreen screen = screen(c);
			if (screen != null) {
				screen.teleportSelected();
			}
		});
		// Checked well after the warmup would have ended (20 + 60 ticks > 3 s), so a jump that wasn't broken off shows
		step(60, false, c -> tp(c, Vec3.atBottomCenterOf(home).add(-2.5, 0, 2.0), Vec3.atCenterOf(home)));
		step(10, false, c -> {
			check("moving_breaks_the_jump", c.player.position().closerThan(Vec3.atCenterOf(home), 6));
			tp(c, Vec3.atBottomCenterOf(home).add(-2.5, 0, 0.5), Vec3.atCenterOf(home));
		});
		step(20, false, c -> rightClick(c, home));

		// A real jump to the village: the menu closes, the warmup runs (3 s), then the jump.
		// (A step's wait comes after its action, so the wait for the jump sits on the step that starts it.)
		step(20, false, c -> selectByName(c, "Деревня"));
		step(100, false, c -> {
			ObeliskScreen screen = screen(c);
			if (screen != null) {
				screen.teleportSelected();
			}
		});
		step(20, true, c -> {
			check("teleported_to_village", c.player.position().closerThan(Vec3.atCenterOf(village), 4));
			check("advancement_first_jump", advancementDone(c, "first_jump"));
			check("no_far_jump_yet", !advancementDone(c, "far_jump"));
			c.options.hideGui = true;
			// Step back a little and face the village obelisk: it should be looking at us
			tp(c, c.player.position().add(c.player.position().subtract(Vec3.atBottomCenterOf(village)).normalize().scale(1.5)),
					Vec3.atBottomCenterOf(village).add(0, 1.3, 0));
		});
		step(30, true, c -> screenshot(c, "arrived"));
		// Straight back is refused: the cooldown is running. The village is not mine, so no upgrades there either.
		step(10, false, c -> {
			c.options.hideGui = false;
			look(c, Vec3.atCenterOf(village));
		});
		step(10, false, c -> rightClick(c, village));
		step(30, false, c -> {
			ObeliskScreen screen = screen(c);
			check("menu_at_village", screen != null);
			if (screen != null) {
				selectByName(c, nameAt(c, home));
				screen.teleportSelected();
			}
		});
		step(40, false, c -> {
			check("cooldown_blocks_jump", c.player.position().closerThan(Vec3.atCenterOf(village), 4));
			ObeliskScreen screen = screen(c);
			if (screen != null) {
				screen.upgrade();
			}
		});
		step(30, false, c -> {
			check("stranger_cannot_upgrade", onServer(c, s -> ObeliskService.at(s.overworld(), village).map(o -> o.tier == 0).orElse(false)));
			onServer(c, s -> {
				ObeliskService.clearCooldown(s.getPlayerList().getPlayer(c.player.getUUID()));
				return null;
			});
		});
		step(100, false, c -> {
			ObeliskScreen screen = screen(c);
			if (screen != null) {
				selectByName(c, nameAt(c, home));
				screen.teleportSelected();
			}
		});
		step(20, true, c -> check("teleported_home", c.player.position().closerThan(Vec3.atCenterOf(home), 4)));

		// Home is mine: rename, toggle access, upgrade to the top tier (free in creative)
		step(10, false, c -> look(c, Vec3.atCenterOf(home)));
		step(10, false, c -> {
			sendRename(c, home, "Дом");
			sendSimple(c, ObeliskPackets.TOGGLE_PUBLIC, home);
		});
		step(20, false, c -> {
			// The client keeps a list of visible obelisks for map mods: ours, the public ones, never someone else's private one
			List<String> synced = ObeliskClientCache.entries().stream().map(ObeliskSync.Entry::name).toList();
			check("client_list_synced", synced.containsAll(List.of("Деревня", "Далёкий форпост", "Мой тайник")) && !synced.contains("Чужой секрет"));
			sendColor(c, home, 4);
			check("renamed", onServer(c, s -> ObeliskService.at(s.overworld(), home).map(o -> o.name.equals("Дом")).orElse(false)));
			check("made_private", onServer(c, s -> ObeliskService.at(s.overworld(), home).map(o -> !o.isPublic).orElse(false)));
			sendSimple(c, ObeliskPackets.TOGGLE_PUBLIC, home);
			for (int i = 0; i < 5; i++) {
				sendSimple(c, ObeliskPackets.UPGRADE, home);
			}
		});
		step(30, false, c -> {
			check("upgraded_to_max", onServer(c, s -> ObeliskService.at(s.overworld(), home).map(o -> o.tier == 4 && o.isPublic).orElse(false)));
			check("advancement_max_tier", advancementDone(c, "max_tier"));
			check("colour_set", onServer(c, s -> ObeliskService.at(s.overworld(), home).map(o -> o.color == 4).orElse(false)));
			if (c.screen != null) {
				c.screen.onClose();
			}
			c.options.hideGui = true;
			tp(c, Vec3.atBottomCenterOf(home).add(-3.2, 0, 0.6), Vec3.atBottomCenterOf(home).add(0, 1.2, 0));
		});
		step(40, true, c -> screenshot(c, "obelisk_tier4"));
		step(10, false, c -> {
			c.options.hideGui = false;
			rightClick(c, home);
		});
		step(30, false, c -> {
			ObeliskScreen screen = screen(c);
			check("menu_after_upgrade", screen != null && screen.getData().tier() == 4);
			if (screen != null) {
				screen.zoomToRange();
				selectByName(c, "Далёкий форпост");
			}
		});
		step(20, false, c -> screenshot(c, "menu_tier4"));
		step(10, false, c -> {
			ObeliskScreen screen = screen(c);
			if (screen != null) {
				screen.zoomTo(1.2);
				screen.select(null);
			}
		});
		step(20, false, c -> screenshot(c, "menu_zoomed"));
		// A ruin, placed the way world generation would; find its ruined obelisk, photograph it, restore it with a real click
		step(10, false, c -> {
			if (c.screen != null) {
				c.screen.onClose();
			}
			BlockPos at = home.offset(14, 0, 10);
			commands(c, "place structure ender_obelisks:ruined_obelisk " + at.getX() + " " + at.getY() + " " + at.getZ(),
					"give @a ender_obelisks:time_shard 2");
		});
		step(20, false, c -> {
			ruin = onServer(c, s -> {
				// /place snaps the structure to its chunk and the surface, so search a generous box around the spot
				for (BlockPos p : BlockPos.betweenClosed(home.offset(-16, -24, -16), home.offset(40, 24, 36))) {
					if (s.overworld().getBlockState(p).is(EnderObelisks.RUINED_OBELISK.get())) {
						return p.immutable();
					}
				}
				return null;
			});
			check("ruin_placed", ruin != null);
			if (ruin != null) {
				c.options.hideGui = true;
				tp(c, Vec3.atBottomCenterOf(ruin).add(-3.5, 0.5, -2.5), Vec3.atCenterOf(ruin));
			}
		});
		step(40, true, c -> screenshot(c, "ruin"));
		step(10, false, c -> {
			if (ruin == null) {
				return;
			}
			c.options.hideGui = false;
			tp(c, Vec3.atBottomCenterOf(ruin).add(-2.0, 0, 0.5), Vec3.atCenterOf(ruin));
		});
		step(20, false, c -> {
			for (int i = 0; i < 9; i++) {
				if (c.player.getInventory().getItem(i).is(EnderObelisks.TIME_SHARD.get())) {
					c.player.getInventory().selected = i;
				}
			}
		});
		step(10, false, c -> {
			if (ruin != null) {
				rightClick(c, ruin);
			}
		});
		step(30, false, c -> {
			UUID me = c.player.getUUID();
			check("ruin_restored", ruin != null && onServer(c, s -> s.overworld().getBlockState(ruin).is(EnderObelisks.OBELISK.get())
					&& ObeliskService.at(s.overworld(), ruin).map(o -> o.owner.equals(me)).orElse(false)));
			check("advancement_restore_ruin", advancementDone(c, "restore_ruin"));
			c.options.hideGui = true;
			if (ruin != null) {
				tp(c, Vec3.atBottomCenterOf(ruin).add(-3.5, 0.5, -2.5), Vec3.atBottomCenterOf(ruin).add(0, 1.2, 0));
			}
		});
		step(40, true, c -> screenshot(c, "ruin_restored"));

		// The return shard the way a player uses it: sneak + right click the obelisk to bind, walk off, hold right click
		step(10, false, c -> {
			c.options.hideGui = false;
			commands(c, "gamemode survival @a", "effect give @a minecraft:resistance 120 4 true", "give @a ender_obelisks:return_shard 1");
			tp(c, Vec3.atBottomCenterOf(home).add(-2.5, 0, 0.5), Vec3.atCenterOf(home));
		});
		step(10, false, c -> {
			check("shard_in_hotbar", selectItem(c, EnderObelisks.RETURN_SHARD.get()));
			c.options.keyShift.setDown(true);
		});
		step(10, false, c -> rightClick(c, home));
		step(10, false, c -> {
			c.options.keyShift.setDown(false);
			UUID homeId = onServer(c, s -> ObeliskService.at(s.overworld(), home).map(o -> o.id).orElse(null));
			ItemStack held = c.player.getMainHandItem();
			CompoundTag bound = held.getTagElement("Obelisk");
			LOGGER.info("shard after binding: {} nbt {}", held, held.getTag());
			check("shard_bound", bound != null && homeId != null && bound.hasUUID("id") && bound.getUUID("id").equals(homeId));
		});
		step(20, true, c -> tp(c, Vec3.atBottomCenterOf(home).add(60.5, 0, 0.5), Vec3.atBottomCenterOf(home).add(60.5, 30, 0.5)));
		step(10, false, c -> {
			// A real right click held down: one press, then the key stays down
			KeyMapping.click(c.options.keyUse.getDefaultKey());
			c.options.keyUse.setDown(true);
		});
		// The shard needs returnShardSeconds (5 s) of holding; the wait after this step covers it with room to spare
		step(120, false, c -> {
			LOGGER.info("shard hold: using={} item={} left={}", c.player.isUsingItem(), c.player.getUseItem(), c.player.getUseItemRemainingTicks());
			check("shard_channeling", c.player.isUsingItem() && c.player.getUseItem().is(EnderObelisks.RETURN_SHARD.get()));
		});
		step(20, false, c -> {
			c.options.keyUse.setDown(false);
			LOGGER.info("after the hold: at {} home {} hand {}", c.player.blockPosition(), home, c.player.getMainHandItem());
			check("shard_returned_home", c.player.position().closerThan(Vec3.atCenterOf(home), 4));
			check("shard_used_up", c.player.getInventory().countItem(EnderObelisks.RETURN_SHARD.get()) == 0);
		});
		step(40, false, this::finish);

		ClientTickEvents.END_CLIENT_TICK.register(this::tick);
	}

	// ---- server-side helpers (run on the integrated server thread) ----

	/** A 7x7 polished deepslate pad on the surface with room above it; returns the block above the pad's centre. */
	private static BlockPos prepareSite(ServerLevel world, int x, int z) {
		// Heightmaps of unloaded chunks read as the bottom of the world: generate the chunk first
		world.getChunk(x >> 4, z >> 4);
		BlockPos top = world.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z));
		for (int dx = -3; dx <= 3; dx++) {
			for (int dz = -3; dz <= 3; dz++) {
				world.setBlockAndUpdate(top.offset(dx, -1, dz), Blocks.POLISHED_DEEPSLATE.defaultBlockState());
				for (int dy = 0; dy < 4; dy++) {
					world.setBlockAndUpdate(top.offset(dx, dy, dz), Blocks.AIR.defaultBlockState());
				}
			}
		}
		return top;
	}

	private static void build(ServerLevel world, BlockPos lower, UUID owner, String name, String ownerName, boolean isPublic) {
		ObeliskGameTests.setObelisk(world, lower, Direction.WEST);
		ObeliskInfo registered = ObeliskService.register(world, lower, null, name);
		ObeliskInfo info = owner == null ? registered
				: new ObeliskInfo(registered.id, registered.dimension, lower, owner, ownerName, name, isPublic, 0);
		info.ownerName = ownerName;
		info.isPublic = isPublic;
		ObeliskRegistry.get(world.getServer()).put(info);
	}

	private static <T> T onServer(Minecraft client, Function<MinecraftServer, T> action) {
		MinecraftServer server = client.getSingleplayerServer();
		return server.submit(() -> action.apply(server)).join();
	}

	private static void commands(Minecraft client, String... commands) {
		onServer(client, server -> {
			for (String command : commands) {
				server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
			}
			return null;
		});
	}

	// ---- client-side helpers ----

	private void step(int waitAfter, boolean needsTerrain, Consumer<Minecraft> action) {
		steps.add(new Step(waitAfter, needsTerrain, action));
	}

	private void tick(Minecraft client) {
		if (client.player == null || client.level == null || client.getSingleplayerServer() == null || steps.isEmpty()) {
			return;
		}
		ticks++;
		if (ticks < nextStepTick) {
			return;
		}
		Step next = steps.peek();
		if (next.needsTerrain() && !client.levelRenderer.hasRenderedAllChunks() && ticks < nextStepTick + 400) {
			return;
		}
		steps.poll();
		try {
			next.action().accept(client);
		} catch (RuntimeException e) {
			LOGGER.error("[harness] step failed", e);
			check("step_without_exception", false);
		}
		nextStepTick = ticks + next.waitAfter();
	}

	/** Teleports the player (server side) to {@code from}, looking at {@code at}. */
	private static void tp(Minecraft client, Vec3 from, Vec3 at) {
		float[] angles = angles(from.add(0, client.player.getEyeHeight(), 0), at);
		commands(client, String.format(Locale.ROOT, "tp @a %.2f %.2f %.2f %.1f %.1f", from.x, from.y, from.z, angles[0], angles[1]));
	}

	/** Like tp, but hovering in creative flight so the camera can sit anywhere. */
	private static void fly(Minecraft client, Vec3 from, Vec3 at) {
		client.player.getAbilities().flying = true;
		client.player.onUpdateAbilities();
		tp(client, from, at);
	}

	private static void look(Minecraft client, Vec3 at) {
		float[] angles = angles(client.player.getEyePosition(), at);
		client.player.setYRot(angles[0]);
		client.player.setXRot(angles[1]);
	}

	private static float[] angles(Vec3 eye, Vec3 at) {
		Vec3 d = at.subtract(eye);
		float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
		float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
		return new float[] {yaw, pitch};
	}

	private static boolean select(Minecraft client) {
		for (int i = 0; i < 9; i++) {
			if (client.player.getInventory().getItem(i).is(EnderObelisks.OBELISK_ITEM.get())) {
				client.player.getInventory().selected = i;
				return true;
			}
		}
		return false;
	}

	private static boolean selectItem(Minecraft client, Item item) {
		for (int i = 0; i < 9; i++) {
			if (client.player.getInventory().getItem(i).is(item)) {
				client.player.getInventory().selected = i;
				return true;
			}
		}
		return false;
	}

	private static void rightClick(Minecraft client, BlockPos lower) {
		Vec3 eye = client.player.getEyePosition();
		Vec3 center = Vec3.atCenterOf(lower);
		Direction side = Direction.getNearest(eye.x - center.x, 0, eye.z - center.z);
		BlockHitResult hit = new BlockHitResult(center.add(Vec3.atLowerCornerOf(side.getNormal()).scale(0.3)), side, lower, false);
		client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hit);
	}

	private static ObeliskScreen screen(Minecraft client) {
		return client.screen instanceof ObeliskScreen screen ? screen : null;
	}

	private static List<String> names(MenuData data) {
		return data.entries().stream().map(MenuData.Entry::name).toList();
	}

	private static boolean advancementDone(Minecraft client, String name) {
		UUID me = client.player.getUUID();
		return onServer(client, s -> {
			Advancement advancement = s.getAdvancements().getAdvancement(EnderObelisks.id(name));
			ServerPlayer player = s.getPlayerList().getPlayer(me);
			return advancement != null && player != null && player.getAdvancements().getOrStartProgress(advancement).isDone();
		});
	}

	private static String nameAt(Minecraft client, BlockPos pos) {
		return onServer(client, s -> ObeliskService.at(s.overworld(), pos).map(o -> o.name).orElse("?"));
	}

	private void selectByName(Minecraft client, String name) {
		ObeliskScreen screen = screen(client);
		Optional<MenuData.Entry> entry = screen == null ? Optional.empty()
				: screen.getData().entries().stream().filter(e -> e.name().equals(name)).findFirst();
		entry.ifPresent(e -> screen.select(e.id()));
		if (entry.isEmpty()) {
			LOGGER.error("[harness] no obelisk named {} in the menu", name);
		}
	}

	private static void sendRename(Minecraft client, BlockPos source, String name) {
		FriendlyByteBuf buf = Network.create();
		buf.writeBlockPos(source);
		buf.writeUtf(name);
		Client.send(ObeliskPackets.RENAME, buf);
	}

	private static void sendColor(Minecraft client, BlockPos source, int color) {
		FriendlyByteBuf buf = Network.create();
		buf.writeBlockPos(source);
		buf.writeVarInt(color);
		Client.send(ObeliskPackets.SET_COLOR, buf);
	}

	private static void sendSimple(Minecraft client, ResourceLocation channel, BlockPos source) {
		FriendlyByteBuf buf = Network.create();
		buf.writeBlockPos(source);
		Client.send(channel, buf);
	}

	private void screenshot(Minecraft client, String name) {
		Screenshot.grab(client.gameDirectory, name + ".png", client.getMainRenderTarget(), message -> {});
		LOGGER.info("[harness] screenshot {}.png", name);
	}

	private void finish(Minecraft client) {
		boolean pass = !results.isEmpty() && results.stream().allMatch(line -> line.endsWith(": OK"));
		results.add("RESULT " + (pass ? "PASS" : "FAIL"));
		try {
			Files.write(client.gameDirectory.toPath().resolve("harness-result.txt"), results, StandardCharsets.UTF_8);
		} catch (IOException e) {
			LOGGER.error("[harness] could not write the result file", e);
		}
		LOGGER.info("[harness] {}", results.get(results.size() - 1));
		client.stop();
	}

	private void check(String name, boolean ok) {
		String line = name + ": " + (ok ? "OK" : "FAIL");
		results.add(line);
		LOGGER.info("[harness] {}", line);
	}
}
