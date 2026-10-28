package euf.enderobelisks.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import euf.enderobelisks.EnderObelisks;
import euf.enderobelisks.ObeliskColors;
import euf.enderobelisks.ObeliskTiers;
import euf.enderobelisks.block.ObeliskBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The animated parts of an obelisk, drawn from the lower half's block entity: the ender eye floating in the cage
 * (it turns to watch the nearest player, or idly looks around), one orbiting obsidian shard per tier, and the
 * name plate.
 */
public class ObeliskRenderer implements BlockEntityRenderer<ObeliskBlockEntity> {
	public static final net.minecraft.client.resources.model.ModelResourceLocation EYE_MODEL = net.minecraft.client.resources.model.ModelResourceLocation.standalone(EnderObelisks.id("block/obelisk_eye"));
	public static final net.minecraft.client.resources.model.ModelResourceLocation SHARD_MODEL = net.minecraft.client.resources.model.ModelResourceLocation.standalone(EnderObelisks.id("block/obelisk_shard"));
	public static final net.minecraft.client.resources.model.ModelResourceLocation SHARD_GLOW_MODEL = net.minecraft.client.resources.model.ModelResourceLocation.standalone(EnderObelisks.id("block/obelisk_shard_glow"));
	private static final double ORBIT_RADIUS = 0.62;
	private static final float SHARD_SCALE = 0.85f;
	private static final double EYE_Y = 26 / 16.0;
	private static final float EYE_SCALE = 1.3f;
	private static final double WATCH_DISTANCE = 12.0;
	private static final double NAME_DISTANCE = 8.0;
	private static final int FULL_BRIGHT = LightTexture.FULL_BRIGHT;

	private final BlockEntityRenderDispatcher dispatcher;
	private final Font textRenderer;

	public ObeliskRenderer(BlockEntityRendererProvider.Context context) {
		this.dispatcher = context.getBlockEntityRenderDispatcher();
		this.textRenderer = context.getFont();
	}

	@Override
	public void render(ObeliskBlockEntity entity, float tickDelta, PoseStack matrices, MultiBufferSource vertexConsumers,
			int light, int overlay) {
		Minecraft client = Minecraft.getInstance();
		BlockState state = entity.getBlockState();
		ModelManager models = client.getModelManager();
		ModelBlockRenderer renderer = client.getBlockRenderer().getModelRenderer();
		VertexConsumer cutout = vertexConsumers.getBuffer(Sheets.cutoutBlockSheet());
		float time = entity.getLevel().getGameTime() + tickDelta;

		// The upgrade tier shows as shards circling the cage: one per tier, each spinning and bobbing in its own rhythm
		int shards = Math.min(entity.getTier(), ObeliskTiers.MAX_TIER);
		for (int i = 0; i < shards; i++) {
			float angle = time * 0.9f + i * 360.0f / shards;
			double rad = Math.toRadians(angle);
			matrices.pushPose();
			matrices.translate(0.5 + Math.cos(rad) * ORBIT_RADIUS, EYE_Y + Mth.sin(time * 0.06f + i * 1.7f) * 0.07,
					0.5 + Math.sin(rad) * ORBIT_RADIUS);
			matrices.mulPose(new Quaternionf().rotationY((float) Math.toRadians(-angle * 2.0f + i * 40.0f)));
			matrices.scale(SHARD_SCALE, SHARD_SCALE, SHARD_SCALE);
			matrices.translate(-0.5, -0.5, -0.5);
			renderer.renderModel(matrices.last(), cutout, state, models.getModel(SHARD_MODEL), 1.0f, 1.0f, 1.0f, light,
					OverlayTexture.NO_OVERLAY);
			// The ember core glows faintly even in the dark
			renderer.renderModel(matrices.last(), cutout, state, models.getModel(SHARD_GLOW_MODEL), 1.0f, 1.0f, 1.0f, FULL_BRIGHT,
					OverlayTexture.NO_OVERLAY);
			matrices.popPose();
		}

		updateEye(entity, time);
		matrices.pushPose();
		matrices.translate(0.5, EYE_Y + Mth.sin(time * 0.08f) * 0.03, 0.5);
		// Turn the model's front (-Z) onto the look direction, keeping the slit pupil upright
		Vector3f dir = entity.eyeDirection;
		matrices.mulPose(new Quaternionf().lookAlong(dir.x, dir.y, dir.z, 0, 1, 0).invert());
		// A little bigger than the gap between the bars: the eye is wedged into its cage
		matrices.scale(EYE_SCALE, EYE_SCALE, EYE_SCALE);
		matrices.translate(-0.5, -0.5, -0.5);
		renderer.renderModel(matrices.last(), cutout, state, models.getModel(EYE_MODEL), 1.0f, 1.0f, 1.0f, FULL_BRIGHT,
				OverlayTexture.NO_OVERLAY);
		matrices.popPose();

		String name = entity.getName();
		Vec3 center = Vec3.atCenterOf(entity.getBlockPos()).add(0, 1, 0);
		if (!name.isEmpty() && client.player != null && client.player.position().closerThan(center, NAME_DISTANCE)) {
			matrices.pushPose();
			matrices.translate(0.5, 2.5, 0.5);
			matrices.mulPose(dispatcher.camera.rotation());
			matrices.scale(-0.025f, -0.025f, 0.025f);
			Matrix4f matrix = matrices.last().pose();
			int background = (int) (client.options.getBackgroundOpacity(0.25f) * 255.0f) << 24;
			// The name plate takes the obelisk's colour, softened toward white so it stays readable
			int rgb = ObeliskColors.rgb(entity.getColor());
			int plate = 0xFF000000 | ((((rgb >> 16) & 0xFF) + 0xE6) / 2 << 16) | ((((rgb >> 8) & 0xFF) + 0xE8) / 2 << 8) | (((rgb & 0xFF) + 0xEA) / 2);
			textRenderer.drawInBatch(name, -textRenderer.width(name) / 2.0f, 0, plate, false, matrix, vertexConsumers,
					Font.DisplayMode.NORMAL, background, FULL_BRIGHT);
			matrices.popPose();
		}
	}

	/** The pupil turns smoothly toward the nearest player's eyes; with nobody near it slowly looks around. */
	private static void updateEye(ObeliskBlockEntity entity, float time) {
		Vec3 eye = Vec3.atBottomCenterOf(entity.getBlockPos()).add(0, EYE_Y, 0);
		Player player = entity.getLevel().getNearestPlayer(eye.x, eye.y, eye.z, WATCH_DISTANCE, false);
		Vector3f target;
		if (player != null) {
			Vec3 d = player.getEyePosition().subtract(eye);
			target = new Vector3f((float) d.x, (float) d.y, (float) d.z);
		} else {
			target = new Vector3f(Mth.sin(time * 0.035f), Mth.sin(time * 0.05f) * 0.2f, -Mth.cos(time * 0.035f));
		}
		if (target.lengthSquared() < 1.0e-4f) {
			return;
		}
		target.normalize();
		// Never straight up or down, where "upright" is undefined
		target.y = Mth.clamp(target.y, -0.9f, 0.9f);
		entity.eyeDirection.lerp(target.normalize(), 0.12f).normalize();
	}

	@Override
	public boolean shouldRenderOffScreen(ObeliskBlockEntity entity) {
		return true;
	}
}
