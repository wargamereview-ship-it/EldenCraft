package dev.eldencraft.items;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * Which Elden Ring item a stack is: its category (Elden Ring's own item category numbers), its param row and, for
 * weapons and spirit ashes, its upgrade level. Everything else about the stack follows from these.
 */
public record ErRef(int kind, int id, int level) {
	public static final int WEAPON = 0, ARMOUR = 1, TALISMAN = 2, GOODS = 4, ASH = 8;

	public static final Codec<ErRef> CODEC = RecordCodecBuilder.create(i -> i.group(
		Codec.INT.fieldOf("kind").forGetter(ErRef::kind),
		Codec.INT.fieldOf("id").forGetter(ErRef::id),
		Codec.INT.optionalFieldOf("level", 0).forGetter(ErRef::level)
	).apply(i, ErRef::new));

	public static final StreamCodec<ByteBuf, ErRef> STREAM_CODEC = StreamCodec.composite(
		ByteBufCodecs.VAR_INT, ErRef::kind, ByteBufCodecs.VAR_INT, ErRef::id, ByteBufCodecs.VAR_INT, ErRef::level, ErRef::new);

	public ErRef withLevel(int level) {
		return new ErRef(kind, id, level);
	}
}
