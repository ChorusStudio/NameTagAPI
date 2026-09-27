package com.perry.nametagapi.impl;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * 出站 SetPassengers 的重写。
 * <p>
 * 三条铁律（错一条就会让 nametag 掉下来或产生重复 id）：
 * <ol>
 *   <li><b>递归</b>：ServerEntity#sendPairingData 里的乘客包被包在 ClientboundBundlePacket 里，
 *       只看顶层会漏掉。</li>
 *   <li><b>幂等</b>：我们自己发的包也会经过这里，所以必须先滤掉自己的 id 再追加。</li>
 *   <li><b>只追加到末尾</b>：座位下标 = 乘客数组下标，prepend 会改变真实乘客的座位。</li>
 * </ol>
 */
public final class PassengerMerger {
    private PassengerMerger() {
    }

    public static Packet<?> rewrite(ServerPlayer player, Packet<?> packet) {
        if (packet instanceof ClientboundSetPassengersPacket passengers) {
            return merge(player, passengers);
        }
        if (packet instanceof ClientboundBundlePacket bundle) {
            List<Packet<? super ClientGamePacketListener>> rewritten = new ArrayList<>();
            boolean changed = false;
            for (Packet<? super ClientGamePacketListener> sub : bundle.subPackets()) {
                Packet<?> result = rewrite(player, sub);
                changed |= result != sub;
                @SuppressWarnings("unchecked")
                Packet<? super ClientGamePacketListener> cast = (Packet<? super ClientGamePacketListener>) result;
                rewritten.add(cast);
            }
            return changed ? new ClientboundBundlePacket(rewritten) : packet;
        }
        return packet;
    }

    private static Packet<?> merge(ServerPlayer player, ClientboundSetPassengersPacket packet) {
        NameTagHolder holder = NameTagRegistry.holderFor(packet.getVehicle());
        if (holder == null) {
            return packet;
        }
        int[] existing = packet.getPassengers();
        int[] mine = holder.visibleIds(player);

        boolean hasFake = false;
        for (int id : existing) {
            if (NameTagRegistry.isFakeId(id)) {
                hasFake = true;
                break;
            }
        }
        if (!hasFake && mine.length == 0) {
            return packet;
        }

        IntArrayList kept = new IntArrayList(existing.length + mine.length);
        for (int id : existing) {
            if (!NameTagRegistry.isFakeId(id)) {
                kept.add(id);
            }
        }
        for (int id : mine) {
            kept.add(id);
        }
        return NameTagPackets.setPassengers(packet.getVehicle(), kept.toIntArray());
    }
}
