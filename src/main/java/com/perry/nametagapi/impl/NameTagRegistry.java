package com.perry.nametagapi.impl;

import com.perry.nametagapi.NameTagConfig;
import com.perry.nametagapi.api.NameTag;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.ints.IntSets;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** 全局状态与生命周期。 */
public final class NameTagRegistry {
    private static final Map<Integer, NameTagHolder> HOLDERS = new ConcurrentHashMap<>();

    /**
     * 我们自己造的假实体 id。
     * <p>
     * 出站包 hook 可能在非服务端线程执行，所以用 volatile 快照 + 写时复制，读端无锁。
     */
    private static volatile IntSet fakeIds = IntSets.emptySet();
    private static final Object FAKE_ID_LOCK = new Object();

    private NameTagRegistry() {
    }

    // ------------------------------------------------------------------ 公开 API

    public static void attach(Entity observee, NameTag nametag) {
        Objects.requireNonNull(observee, "observee");
        Objects.requireNonNull(nametag, "nametag");
        ServerLevel level = serverLevel(observee);
        checkThread(level);
        NameTagHolder holder = HOLDERS.computeIfAbsent(
                observee.getId(), id -> new NameTagHolder(observee, level));
        holder.addLine(nametag);
        holder.discoverObservers();
    }

    public static void detach(Entity observee, NameTag nametag) {
        NameTagHolder holder = HOLDERS.get(observee.getId());
        if (holder == null) {
            return;
        }
        holder.removeLine(nametag);
        if (holder.isEmpty()) {
            HOLDERS.remove(observee.getId(), holder);
        }
    }

    public static void clear(Entity observee) {
        NameTagHolder holder = HOLDERS.get(observee.getId());
        if (holder == null) {
            return;
        }
        holder.evictAll();
        holder.releaseFakeIds();
        HOLDERS.remove(observee.getId(), holder);
    }

    public static List<NameTag> of(Entity observee) {
        NameTagHolder holder = HOLDERS.get(observee.getId());
        return holder == null ? List.of() : holder.nametags();
    }

    // ------------------------------------------------------------------ 供 mixin 调用

    public static void onStartTracking(Entity observee, ServerPlayer player) {
        NameTagHolder holder = HOLDERS.get(observee.getId());
        if (holder != null) {
            holder.addObserver(player);
        }
    }

    public static void onStopTracking(Entity observee, ServerPlayer player) {
        NameTagHolder holder = HOLDERS.get(observee.getId());
        if (holder != null) {
            holder.removeObserver(player);
        }
    }

    /** 在 ServerEntity#sendPairingData 的 TAIL 调用：把初始包塞进原版同一个 bundle。 */
    public static void onPairingData(Entity observee, ServerPlayer player,
                                     Consumer<Packet<ClientGamePacketListener>> out) {
        NameTagHolder holder = HOLDERS.get(observee.getId());
        if (holder != null) {
            holder.sendInitial(player, out);
        }
    }

    /** 出站包 hook。可能非服务端线程 —— 这里只做纯读。 */
    public static Packet<?> rewrite(ServerPlayer player, Packet<?> packet) {
        return PassengerMerger.rewrite(player, packet);
    }

    // ------------------------------------------------------------------ 周期任务

    public static void tick(MinecraftServer server) {
        int tick = server.getTickCount();
        boolean discover = NameTagConfig.DISCOVER_INTERVAL_TICKS > 0
                && tick % NameTagConfig.DISCOVER_INTERVAL_TICKS == 0;

        for (NameTagHolder holder : HOLDERS.values()) {
            Entity observee = holder.observee();
            if (observee.isRemoved()) {
                holder.evictAll();
                holder.releaseFakeIds();
                HOLDERS.remove(observee.getId(), holder);
                continue;
            }
            holder.tick(tick, discover);
        }

        if (NameTagConfig.REASSERT_INTERVAL_TICKS > 0
                && tick % NameTagConfig.REASSERT_INTERVAL_TICKS == 0) {
            for (NameTagHolder holder : HOLDERS.values()) {
                for (ServerPlayer player : holder.observers()) {
                    if (holder.visibleIds(player).length > 0) {
                        holder.resendPassengers(player);
                    }
                }
            }
        }
    }

    public static void onDisconnect(ServerPlayer player) {
        for (NameTagHolder holder : HOLDERS.values()) {
            holder.removeObserver(player);
        }
    }

    public static void clearAll() {
        for (NameTagHolder holder : HOLDERS.values()) {
            holder.releaseFakeIds();
        }
        HOLDERS.clear();
        fakeIds = IntSets.emptySet();
    }

    // ------------------------------------------------------------------ 内部

    static NameTagHolder holderFor(int entityId) {
        return HOLDERS.get(entityId);
    }

    static boolean isFakeId(int id) {
        return fakeIds.contains(id);
    }

    static void registerFakeId(int id) {
        synchronized (FAKE_ID_LOCK) {
            IntOpenHashSet next = new IntOpenHashSet(fakeIds);
            next.add(id);
            fakeIds = next;
        }
    }

    static void unregisterFakeId(int id) {
        synchronized (FAKE_ID_LOCK) {
            if (!fakeIds.contains(id)) {
                return;
            }
            IntOpenHashSet next = new IntOpenHashSet(fakeIds);
            next.remove(id);
            fakeIds = next;
        }
    }

    private static ServerLevel serverLevel(Entity entity) {
        if (!(entity.level() instanceof ServerLevel level)) {
            throw new IllegalArgumentException("NameTagAPI 只能在服务端使用");
        }
        return level;
    }

    private static void checkThread(ServerLevel level) {
        MinecraftServer server = level.getServer();
        if (!server.isSameThread()) {
            throw new IllegalStateException("NameTagAPI 必须在服务端线程调用");
        }
    }
}
