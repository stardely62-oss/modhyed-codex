package euf.enderobelisks.block;

import euf.enderobelisks.EnderObelisks;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

/**
 * Lives in the lower half. Keeps a copy of the registry entry's name and tier for rendering (name plate, lit rune
 * rings) and, on the client, the eye's current look direction.
 */
public class ObeliskBlockEntity extends BlockEntity {
	@Nullable
	private UUID obeliskId;
	private String name = "";
	private int tier;
	private int color;

	/** Client-side animation state, never saved: where the eye's pupil currently points (unit vector). */
	public final Vector3f eyeDirection = new Vector3f(0, 0, -1);

	public ObeliskBlockEntity(BlockPos pos, BlockState state) {
		super(EnderObelisks.OBELISK_BLOCK_ENTITY.get(), pos, state);
	}

	public void update(UUID id, String name, int tier, int color) {
		this.obeliskId = id;
		this.name = name;
		this.tier = tier;
		this.color = color;
		setChanged();
		if (level != null) {
			level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
		}
	}

	@Nullable
	public UUID getObeliskId() {
		return obeliskId;
	}

	public String getName() {
		return name;
	}

	public int getTier() {
		return tier;
	}

	public int getColor() {
		return color;
	}

	@Override
	protected void saveAdditional(CompoundTag nbt, net.minecraft.core.HolderLookup.Provider registries) {
		super.saveAdditional(nbt, registries);
		if (obeliskId != null) {
			nbt.putUUID("id", obeliskId);
		}
		nbt.putString("name", name);
		nbt.putInt("tier", tier);
		nbt.putInt("color", color);
	}

	@Override
	protected void loadAdditional(CompoundTag nbt, net.minecraft.core.HolderLookup.Provider registries) {
		super.loadAdditional(nbt, registries);
		obeliskId = nbt.hasUUID("id") ? nbt.getUUID("id") : null;
		name = nbt.getString("name");
		tier = nbt.getInt("tier");
		color = nbt.getInt("color");
	}

	@Override
	public CompoundTag getUpdateTag(net.minecraft.core.HolderLookup.Provider registries) {
		return saveWithoutMetadata(registries);
	}

	@Override
	public Packet<ClientGamePacketListener> getUpdatePacket() {
		return ClientboundBlockEntityDataPacket.create(this);
	}
}
