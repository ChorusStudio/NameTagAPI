package com.perry.nametagapi.impl;

import com.perry.nametagapi.NameTagConfig;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/** 纯包构造，无状态。 */
public final class NameTagPackets {
    private NameTagPackets() {
    }

    public static ClientboundAddEntityPacket addDisplay(int id, UUID uuid, Vec3 at) {
        return new ClientboundAddEntityPacket(
                id, uuid,
                at.x, at.y + NameTagConfig.SPAWN_Y_OFFSET, at.z,
                0.0F, 0.0F,
                EntityTypes.TEXT_DISPLAY,
                0,
                Vec3.ZERO,
                0.0D
        );
    }

    public static ClientboundRemoveEntitiesPacket removeEntities(int... ids) {
        return new ClientboundRemoveEntitiesPacket(ids);
    }

    /**
     * ClientboundSetPassengersPacket 的 vehicle / passengers 都是 private，
     * 也没有「自定义乘客数组」的公开构造器，所以只能「编码 → 解码」走一趟。
     * <p>
     * 这里复用一块线程本地的临时缓冲区，避免每次都新建 ByteBuf（出站包 hook 也会走到这）。
     */
    private static final ThreadLocal<FriendlyByteBuf> SCRATCH =
            ThreadLocal.withInitial(() -> new FriendlyByteBuf(Unpooled.buffer(64)));

    public static ClientboundSetPassengersPacket setPassengers(int vehicleId, int[] passengers) {
        FriendlyByteBuf buffer = SCRATCH.get();
        if (buffer.capacity() > 1024) {
            // 乘客数组正常只有几个元素；万一有人写出超大的数组，别让常驻线程永久占着那块内存。
            buffer = new FriendlyByteBuf(Unpooled.buffer(64));
            SCRATCH.set(buffer);
        }
        buffer.clear();
        buffer.writeVarInt(vehicleId);
        buffer.writeVarIntArray(passengers);
        return ClientboundSetPassengersPacket.STREAM_CODEC.decode(buffer);
    }

    public static ClientboundBundlePacket bundle(List<Packet<? super ClientGamePacketListener>> packets) {
        return new ClientboundBundlePacket(packets);
    }
}
