package euf.enderobelisks;

import euf.enderobelisks.block.ObeliskBlock;
import euf.enderobelisks.block.ObeliskBlockEntity;
import euf.enderobelisks.block.RuinedObeliskBlock;
import euf.enderobelisks.net.ObeliskPackets;
import euf.enderobelisks.platform.Network;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(EnderObelisks.MOD_ID)
public class EnderObelisks {
    public static final String MOD_ID = "ender_obelisks";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MOD_ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MOD_ID);
    public static final Supplier<ObeliskBlock> OBELISK = BLOCKS.register("obelisk", () -> new ObeliskBlock(
        BlockBehaviour.Properties.ofFullCopy(Blocks.OBSIDIAN).noOcclusion().pushReaction(PushReaction.BLOCK)
            .lightLevel(state -> state.getValue(ObeliskBlock.HALF) == DoubleBlockHalf.UPPER ? 10 : 3)));
    public static final Supplier<Item> OBELISK_ITEM = ITEMS.register("obelisk", () -> new ObeliskItem(OBELISK.get(), new Item.Properties().stacksTo(16)));
    public static final Supplier<BlockEntityType<ObeliskBlockEntity>> OBELISK_BLOCK_ENTITY = BLOCK_ENTITIES.register("obelisk",
        () -> BlockEntityType.Builder.of(ObeliskBlockEntity::new, OBELISK.get()).build(null));
    public static final Supplier<Item> TIME_SHARD = ITEMS.register("time_shard", () -> new Item(new Item.Properties()));
    public static final Supplier<Item> RETURN_SHARD = ITEMS.register("return_shard", () -> new ReturnShardItem(new Item.Properties().stacksTo(16)));
    public static final Supplier<RuinedObeliskBlock> RUINED_OBELISK = BLOCKS.register("ruined_obelisk", () -> new RuinedObeliskBlock(
        BlockBehaviour.Properties.ofFullCopy(Blocks.OBSIDIAN).noOcclusion().pushReaction(PushReaction.BLOCK)));
    public static final Supplier<Item> RUINED_OBELISK_ITEM = ITEMS.register("ruined_obelisk", () -> new BlockItem(RUINED_OBELISK.get(), new Item.Properties()));

    public static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath(MOD_ID, path); }
    public EnderObelisks(IEventBus bus) {
        BLOCKS.register(bus); ITEMS.register(bus); BLOCK_ENTITIES.register(bus);
        ObeliskSounds.REGISTER.register(bus);
        ObeliskCriteria.REGISTER.register(bus);
        ObeliskConfig.load(FMLPaths.CONFIGDIR.get().resolve(MOD_ID + ".json"));
        ObeliskPackets.registerServerReceivers();
        bus.addListener((RegisterPayloadHandlersEvent event) -> Network.register(event, MOD_ID, "menu", "sync"));
        bus.addListener((BuildCreativeModeTabContentsEvent event) -> {
            if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) { event.accept(OBELISK_ITEM.get()); event.accept(RUINED_OBELISK_ITEM.get()); }
            if (event.getTabKey() == CreativeModeTabs.INGREDIENTS) event.accept(TIME_SHARD.get());
            if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) event.accept(RETURN_SHARD.get());
        });
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> ObeliskService.tick(event.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> ObeliskService.reset());
        NeoForge.EVENT_BUS.addListener((LivingIncomingDamageEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) ObeliskService.onDamaged(player);
        });
        NeoForge.EVENT_BUS.addListener((BlockEvent.BreakEvent event) -> {
            if (event.getState().is(OBELISK.get()) && !ObeliskBlock.mayBreak(event.getState(), event.getPlayer(), event.getLevel(), event.getPos())) {
                event.setCanceled(true);
                event.getPlayer().displayClientMessage(Component.translatable("ender_obelisks.message.not_owner"), true);
            }
        });
        LOGGER.info("Ender Obelisks loaded on NeoForge 1.21.1");
    }
}
