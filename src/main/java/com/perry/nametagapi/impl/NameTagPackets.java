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
     * ClientboundSetPassengersPacket 没有「自定义乘客数组」的公开构造器，
     * 只能先手搓一个包再反序列化。
     */
    public static ClientboundSetPassengersPacket setPassengers(int vehicleId, int[] passengers) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeVarInt(vehicleId);
        buffer.writeVarIntArray(passengers);
        return ClientboundSetPassengersPacket.STREAM_CODEC.decode(buffer);
    }

    public static ClientboundBundlePacket bundle(List<Packet<? super ClientGamePacketListener>> packets) {
        return new ClientboundBundlePacket(packets);
    }
}
