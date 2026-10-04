package com.perry.nametagapi.impl;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 出站 SetPassengers 的重写。
 * <p>
 * 四条铁律（错一条就会让 nametag 掉下来或产生重复/悬空 id）：
 * <ol>
 *   <li><b>递归</b>：ServerEntity#sendPairingData 里的乘客包被包在 ClientboundBundlePacket 里，
 *       只看顶层会漏掉。</li>
 *   <li><b>幂等</b>：我们自己发的包也会经过这里，所以必须先滤掉自己的 id 再追加；如果算出来的结果
 *       和原数组一模一样，直接返回原包 —— 不然每个我们自己的包都要白做一次 varint 编解码。</li>
 *   <li><b>只追加到末尾</b>：座位下标 = 乘客数组下标，prepend 会改变真实乘客的座位。</li>
 *   <li><b>holder 没了也要清</b>：标签摘掉之后，残留在乘客表里的假 id 只剩这里能收拾。</li>
 * </ol>
 */
public final class PassengerMerger {
    /** 没有可见假实体时用的空数组（共享、只读）。 */
    private static final int[] NO_PASSENGERS = new int[0];

    private PassengerMerger() {
    }

    public static Packet<?> rewrite(ServerPlayer player, Packet<?> packet) {
        if (packet instanceof ClientboundSetPassengersPacket passengers) {
            return merge(player, passengers);
        }
        if (packet instanceof ClientboundBundlePacket bundle) {
            // subPackets() 只有 Iterable，没法按下标回填，所以先探测一遍：
            // 绝大多数 bundle 里根本没有乘客包，这条路径不该有任何 list 分配。
            boolean changed = false;
            for (Packet<? super ClientGamePacketListener> sub : bundle.subPackets()) {
                if (rewrite(player, sub) != sub) {
                    changed = true;
                    break;
                }
            }
            if (!changed) {
                return packet;
            }
            List<Packet<? super ClientGamePacketListener>> rewritten = new ArrayList<>(8);
            for (Packet<? super ClientGamePacketListener> sub : bundle.subPackets()) {
                @SuppressWarnings("unchecked")
                Packet<? super ClientGamePacketListener> cast =
                        (Packet<? super ClientGamePacketListener>) rewrite(player, sub);
                rewritten.add(cast);
            }
            return new ClientboundBundlePacket(rewritten);
        }
        return packet;
    }

    private static Packet<?> merge(ServerPlayer player, ClientboundSetPassengersPacket packet) {
        NameTagHolder holder = NameTagRegistry.holderFor(packet.getVehicle());
        int[] existing = packet.getPassengers();
        // holder 已经没了（标签全摘掉 / 实体没了）也要走一遍：残留的假 id 必须从乘客表里清掉，
        // 否则客户端会一直挂着一条指向不存在实体的乘客项，而再也没有人能修它。
        int[] mine = holder == null ? NO_PASSENGERS : holder.visibleIds(player);

        if (!containsFake(existing) && mine.length == 0) {
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

        int[] merged = kept.toIntArray();
        if (Arrays.equals(merged, existing)) {
            // 幂等：我们自己发的 SetPassengers 走到这里时结果和原数组一模一样，
            // 没必要白重建一次（那要多做一遍 varint 编码 + 解码，而且是在网络线程上）
            return packet;
        }
        return NameTagPackets.setPassengers(packet.getVehicle(), merged);
    }

    private static boolean containsFake(int[] ids) {
        for (int id : ids) {
            if (NameTagRegistry.isFakeId(id)) {
                return true;
            }
        }
        return false;
    }
}
