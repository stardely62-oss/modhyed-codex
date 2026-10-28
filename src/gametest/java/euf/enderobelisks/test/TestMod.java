package euf.enderobelisks.test;
@net.neoforged.fml.common.Mod("ender_obelisks_test")
public class TestMod {
 public TestMod(net.neoforged.bus.api.IEventBus bus) {
  new GenWorldServer().onInitializeServer();
  if (net.neoforged.fml.loading.FMLEnvironment.dist == net.neoforged.api.distmarker.Dist.CLIENT)
   bus.addListener((net.neoforged.fml.event.lifecycle.FMLClientSetupEvent event) -> event.enqueueWork(() -> new HarnessClient().onInitializeClient()));
 }
}
