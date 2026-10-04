package com.perry.nametagapi.impl;

import com.perry.nametagapi.NameTagAPI;
import com.perry.nametagapi.NameTagConfig;
import com.perry.nametagapi.api.NameTag;
import com.perry.nametagapi.api.NameTagDisplay;
import com.perry.nametagapi.mixin.ChunkMapAccessor;
import com.perry.nametagapi.mixin.TrackedEntityAccessor;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.world.entity.Entity;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** 全局状态与生命周期。 */
public final class NameTagRegistry {
    private static final Map<Integer, NameTagHolder> HOLDERS = new ConcurrentHashMap<>();

    /**
     * 我们自己造的假实体 id。
     * <p>
     * 出站包 hook 可能在非服务端线程执行，所以用并发集合：读无锁，写 O(1)。
     * 之前的「volatile + 写时复制」在批量 attach/detach 时是 O(n²)。
     * 查询频率本身很低 —— 只有原版真的发 SetPassengers 时才会被查到。
     */
    private static final Set<Integer> FAKE_IDS = ConcurrentHashMap.newKeySet();

    private NameTagRegistry() {
    }

    // ------------------------------------------------------------------ 公开 API

    /**
     * 给实体挂一条 nametag。
     * <p>
     * 必须在服务端线程调用。如果实体已经处于「已移除」状态（被 discard，<b>或者所在区块
     * 已被卸载</b> —— 两者都会走 {@code Entity#setRemoved}），这里会直接忽略并打一条
     * debug 日志，而不是抛异常：调用方此时往往还持有实体引用、以为它还活着。
     * <p>
     * {@code id} 是这条 nametag 在该实体上的标识：同一个 id 再次 attach 视为「同一条」，
     * 会在原来的行上原地替换实现（假实体 id/uuid 保持不变）。
     */
    public static void attach(Entity observee, NameTag nametag, Identifier id) {
        Objects.requireNonNull(observee, "observee");
        Objects.requireNonNull(nametag, "nametag");
        Objects.requireNonNull(id, "id");
        ServerLevel level = serverLevel(observee);
        checkThread(level);
        if (observee.isRemoved()) {
            // 实体已经进了移除队列（discard，或区块卸载时的 UNLOADED_TO_CHUNK）：
            // 此刻挂上去只会活到下一次 tick 清理就被整锅丢掉。忽略 + 留一条 debug 痕。
            NameTagAPI.LOGGER.debug("忽略了针对已移除/已卸载实体的 nametag attach: {} #{}",
                    observee.getType(), observee.getId());
            return;
        }
        NameTagHolder holder = HOLDERS.computeIfAbsent(
                observee.getId(), _ -> new NameTagHolder(observee));
        holder.addLine(nametag, id, level);
        // 标签往往挂得比「玩家开始追踪这个实体」晚，这里用原版自己的追踪表补一次。
        holder.syncObserversFrom(trackedPlayers(observee));
    }

    public static void detach(Entity observee, Identifier id) {
        Objects.requireNonNull(observee, "observee");
        Objects.requireNonNull(id, "id");
        checkServerThread(observee);
        NameTagHolder holder = HOLDERS.get(observee.getId());
        if (holder == null) {
            return;
        }
        holder.removeLine(id);
        if (holder.isEmpty()) {
            HOLDERS.remove(observee.getId(), holder);
        }
    }

    public static void clear(Entity observee) {
        Objects.requireNonNull(observee, "observee");
        checkServerThread(observee);
        NameTagHolder holder = HOLDERS.get(observee.getId());
        if (holder == null) {
            return;
        }
        holder.evictAll();
        holder.releaseFakeIds();
        HOLDERS.remove(observee.getId(), holder);
    }

    /** 强制重发该实体身上所有 nametag（用于就地修改 Component 的实现）。 */
    public static void invalidate(Entity observee) {
        Objects.requireNonNull(observee, "observee");
        checkServerThread(observee);
        NameTagHolder holder = HOLDERS.get(observee.getId());
        if (holder != null) {
            holder.invalidateAll();
        }
    }

    /**
     * 玩家重生时的交接：vanilla 会 {@code new ServerPlayer(...)}，entity id 变了，
     * 旧实例上的 holder 会被当作已移除清掉，标签就凭空消失了。这里把旧实例的
     * NameTag 连同它的 identifier 原样搬到新实例上。
     * <p>
     * 由 {@code ServerPlayerEvents.AFTER_RESPAWN} 触发。只处理「重生」，不处理重连
     * —— 重连开启的是新会话，标签不跨会话保留。
     */
    public static void carryOver(ServerPlayer oldPlayer, ServerPlayer newPlayer) {
        NameTagHolder previous = HOLDERS.get(oldPlayer.getId());
        if (previous == null || previous.isEmpty()) {
            return;
        }
        // nametags() 返回的是快照，遍历时往另一个 player 上挂是安全的
        for (Map.Entry<Identifier, NameTag> entry : previous.nametags().entrySet()) {
            attach(newPlayer, entry.getValue(), entry.getKey());
        }
    }

    /** 当前挂着的 nametag：identifier → NameTag，按 attach 顺序（快照）。 */
    public static LinkedHashMap<Identifier, NameTag> of(Entity observee) {
        Objects.requireNonNull(observee, "observee");
        // 和 displays 一样：读的也只在服务端线程维护的内部结构
        checkServerThread(observee);
        NameTagHolder holder = HOLDERS.get(observee.getId());
        return holder == null ? LinkedHashMap.newLinkedHashMap(0) : holder.nametags();
    }

    public static NameTagHolder ofHolder(Entity observee) {
        return holderFor(observee.getId());
    }

    /**
     * 某个观察者当前看到的 nametag 假实体：identifier → {@link NameTagDisplay}，
     * 按渲染顺序（自下而上，也就是 map 的遍历顺序）排列。
     * <p>
     * 详见 {@link NameTagHolder#displaysFor}：必须在服务端线程调用，且会实时求值
     * isVisible / priority / lineHeight 等用户代码。
     */
    public static LinkedHashMap<Identifier, NameTagDisplay> displays(Entity observee, ServerPlayer observer) {
        Objects.requireNonNull(observee, "observee");
        Objects.requireNonNull(observer, "observer");
        checkServerThread(observee);
        NameTagHolder holder = HOLDERS.get(observee.getId());
        return holder == null ? LinkedHashMap.newLinkedHashMap(0) : holder.displaysFor(observer);
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

        for (NameTagHolder holder : HOLDERS.values()) {
            Entity observee = holder.observee();
            // 区块卸载时 PersistentEntitySectionManager#unloadEntity 会调
            // setRemoved(UNLOADED_TO_CHUNK)，而 isRemoved() 就是 removalReason != null，
            // 所以这一条同时覆盖「discard」和「区块卸载」，不会泄漏 holder。
            if (observee.isRemoved()) {
                holder.evictAll();
                holder.releaseFakeIds();
                HOLDERS.remove(observee.getId(), holder);
                continue;
            }
            holder.tick(tick);
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
            // 连接已经没了：走 removeObserver 既会尝试发包，又会在「玩家不在 observers
            // 但 visible / sent 里还留着记录」的情况下提前 return，留下垃圾。
            holder.forgetPlayer(player);
        }
    }

    public static void clearAll() {
        for (NameTagHolder holder : HOLDERS.values()) {
            holder.releaseFakeIds();
        }
        HOLDERS.clear();
        FAKE_IDS.clear();
    }

    // ------------------------------------------------------------------ 内部

    static NameTagHolder holderFor(int entityId) {
        return HOLDERS.get(entityId);
    }

    static boolean isFakeId(int id) {
        return FAKE_IDS.contains(id);
    }

    static void registerFakeId(int id) {
        FAKE_IDS.add(id);
    }

    static void unregisterFakeId(int id) {
        FAKE_IDS.remove(id);
    }

    /**
     * 直接问原版「谁正在追踪这个实体」。
     * <p>
     * 走 {@code ChunkMap.entityMap -> TrackedEntity.seenBy}，也就是原版自己的追踪表，
     * 所以旁观者、{@code entity-broadcast-range-percentage}、玩家视距、区块追踪视图
     * 这些规则全部自动一致 —— 自己算距离一定会和原版跑偏。
     */
    public static Set<ServerPlayer> trackedPlayers(Entity observee) {
        if (!(observee.level() instanceof ServerLevel level)) {
            return Set.of();
        }
        ChunkMap chunkMap = level.getChunkSource().chunkMap;
        Object tracked = ((ChunkMapAccessor) chunkMap).nametagapi$entityMap().get(observee.getId());
        if (tracked == null) {
            return Set.of();
        }
        Set<ServerPlayer> players = new HashSet<>();
        for (ServerPlayerConnection connection : ((TrackedEntityAccessor) tracked).nametagapi$seenBy()) {
            players.add(connection.getPlayer());
        }
        return players;
    }

    /** 写入口统一做线程校验；客户端实体上本来就没有 holder，直接放过。 */
    private static void checkServerThread(Entity entity) {
        if (entity.level() instanceof ServerLevel level) {
            checkThread(level);
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
