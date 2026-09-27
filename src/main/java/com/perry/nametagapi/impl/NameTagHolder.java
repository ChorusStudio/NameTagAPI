package com.perry.nametagapi.impl;

import com.perry.nametagapi.NameTagConfig;
import com.perry.nametagapi.api.NameTag;
import com.perry.nametagapi.api.SeeThroughStatus;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 一个被观察者身上的全部 nametag 状态。
 * <p>
 * 线程模型：除了 {@link #visible}（volatile 快照，供出站包 hook 无锁读取）之外，
 * 其它字段都只在服务端线程读写。
 */
public final class NameTagHolder {
    private static final int[] EMPTY = new int[0];

    /** 刷新间隔的缓存时长（tick）：期间不会重复调用用户的 updateIntervalTicks()。 */
    private static final int INTERVAL_CACHE_TICKS = 20;

    private final Entity observee;
    private final ServerLevel level;
    private final List<NameTagLine> lines = new ArrayList<>();
    private final Set<ServerPlayer> observers = new LinkedHashSet<>();

    /** player uuid -> 该玩家当前可见的假实体 id（有序）。整体替换，读端无锁。 */
    private volatile Map<UUID, int[]> visible = Map.of();

    /** 观察者集合变动过（或首次），需要重算每行的刷新间隔。 */
    private boolean intervalsDirty = true;
    private int intervalRefreshAt;

    public NameTagHolder(Entity observee, ServerLevel level) {
        this.observee = observee;
        this.level = level;
    }

    public Entity observee() {
        return this.observee;
    }

    public ServerLevel level() {
        return this.level;
    }

    public boolean isEmpty() {
        return this.lines.isEmpty();
    }

    public List<NameTag> nametags() {
        List<NameTag> result = new ArrayList<>(this.lines.size());
        for (NameTagLine line : this.lines) {
            result.add(line.nametag());
        }
        return result;
    }

    public Set<ServerPlayer> observers() {
        return Collections.unmodifiableSet(this.observers);
    }

    /** 供出站包 hook 使用：纯读，可能发生在任意线程。 */
    public int[] visibleIds(ServerPlayer player) {
        return this.visible.getOrDefault(player.getUUID(), EMPTY);
    }

    // ------------------------------------------------------------------ 行管理

    void addLine(NameTag nametag) {
        NameTagLine line = new NameTagLine(nametag, new VirtualTextDisplay(this.level));
        this.lines.add(line);
        this.intervalsDirty = true;
        NameTagRegistry.registerFakeId(line.display().id);
    }

    void removeLine(NameTag nametag) {
        NameTagLine target = null;
        for (NameTagLine line : this.lines) {
            if (line.nametag() == nametag) {
                target = line;
                break;
            }
        }
        if (target == null) {
            return;
        }
        this.lines.remove(target);
        NameTagRegistry.unregisterFakeId(target.display().id);

        // 攒好新快照、循环外一次性发布：setVisible 是全量 map 复制，逐个玩家调是 O(n²)
        Map<UUID, int[]> next = new HashMap<>(this.visible);
        boolean changed = false;
        for (ServerPlayer player : this.observers) {
            int[] current = this.visibleIds(player);
            if (!contains(current, target.display().id)) {
                continue;
            }
            int[] remaining = removeId(current, target.display().id);
            // 先把乘客表修正成「不再包含这个假实体」，再删实体：客户端不会出现
            // 「乘客数组里引用一个已删除 id」的中间态。
            player.connection.send(NameTagPackets.setPassengers(
                    this.observee.getId(), this.passengerIds(remaining)));
            next.put(player.getUUID(), remaining);
            changed = true;
            target.forget(player);
            player.connection.send(NameTagPackets.removeEntities(target.display().id));
        }
        if (changed) {
            this.visible = Collections.unmodifiableMap(next);
        }
    }

    // ------------------------------------------------------------------ 观察者

    void addObserver(ServerPlayer player) {
        this.observers.add(player);
        this.intervalsDirty = true;
    }

    /** 对应 ServerEntity#removePairing（HEAD）：客户端此刻还认识被观察者，先撤掉假实体。 */
    void removeObserver(ServerPlayer player) {
        if (!this.observers.remove(player)) {
            return;
        }
        this.intervalsDirty = true;
        int[] ids = this.visibleIds(player);
        if (ids.length > 0) {
            player.connection.send(NameTagPackets.removeEntities(ids));
        }
        for (NameTagLine line : this.lines) {
            line.forget(player);
        }
        this.setVisible(player, EMPTY);
    }

    /**
     * 一次性补齐观察者：nametag 往往是「实体已经在被追踪之后」才挂上去的，那时
     * addPairing 早就过去了。集合由调用方从原版自己的追踪表里取
     * （见 {@code NameTagRegistry#trackedPlayers}），所以旁观者、广播范围百分比、
     * 视距、区块追踪视图这些规则都不需要在这里复刻。
     */
    void syncObserversFrom(Collection<ServerPlayer> tracked) {
        this.observers.addAll(tracked);
        this.intervalsDirty = true;
    }

    /** 玩家断开连接：无条件清掉所有痕迹（不发包，连接已经没了）。 */
    void forgetPlayer(ServerPlayer player) {
        this.observers.remove(player);
        this.intervalsDirty = true;
        for (NameTagLine line : this.lines) {
            line.forget(player);
        }
        UUID uuid = player.getUUID();
        if (this.visible.containsKey(uuid)) {
            Map<UUID, int[]> next = new HashMap<>(this.visible);
            next.remove(uuid);
            this.visible = Collections.unmodifiableMap(next);
        }
    }

    /** 自愈：把这条被观察者的乘客表重新下发一次。 */
    public void resendPassengers(ServerPlayer player) {
        if (!this.observers.contains(player)) {
            return;
        }
        player.connection.send(NameTagPackets.setPassengers(
                this.observee.getId(), this.passengerIds(this.visibleIds(player))));
    }

    // ------------------------------------------------------------------ tick

    public void tick(int tick) {
        // 间隔只在「观察者集合变了」或每隔 INTERVAL_CACHE_TICKS 重算，避免每 tick
        // 对每条行 × 每个观察者调用一次用户的 updateIntervalTicks()。
        boolean recomputeIntervals = this.intervalsDirty || tick >= this.intervalRefreshAt;
        if (recomputeIntervals) {
            this.intervalsDirty = false;
            this.intervalRefreshAt = tick + INTERVAL_CACHE_TICKS;
        }

        for (ServerPlayer player : this.observers) {
            if (!this.isValid(player)) {
                continue;
            }
            // 一趟走完：可见性 / 优先级（都是用户代码）对每个观察者每 tick 只求值一次，
            // 内容刷新与数据同步共用这份结果。分成两趟会把它们各调两遍。
            List<RankedLine> visible = this.rankVisibleLines(player);

            for (RankedLine ranked : visible) {
                NameTagLine line = ranked.line();
                NameTagLine.PlayerState state = line.state(player);
                if (recomputeIntervals) {
                    line.recomputeRefreshInterval(state, player);
                }
                // 看不到的行根本不在 visible 里，所以这里天然只刷新「他真的看得到」的行：
                // 既省掉用户代码，也避免实现的副作用波及看不到这条 nametag 的玩家。
                // 再次可见时会走 markHidden 留下的 component == null 路径重算一次。
                if (line.isRefreshDue(state, tick)) {
                    line.refresh(state, this.observee, player);
                }
            }

            List<Packet<? super ClientGamePacketListener>> packets = this.buildSync(player, visible);
            if (!packets.isEmpty()) {
                player.connection.send(NameTagPackets.bundle(packets));
            }
        }
    }

    /**
     * 该玩家可见的行，按优先级降序（数值越大越靠近头部）。
     * <p>
     * {@code isVisible} / {@code priority} 都是用户代码，所以每个观察者每 tick 只求值一次，
     * 刷新与同步共用这份结果。
     */
    private List<RankedLine> rankVisibleLines(ServerPlayer player) {
        List<RankedLine> visible = new ArrayList<>(this.lines.size());
        for (NameTagLine line : this.lines) {
            if (this.canSee(line, player)) {
                visible.add(new RankedLine(line, line.nametag().priority(player)));
            }
        }
        // List.sort 是稳定排序，所以同优先级保持 attach 顺序（先挂的在下面）
        visible.sort((a, b) -> Integer.compare(b.priority(), a.priority()));
        return visible;
    }

    // ------------------------------------------------------------------ 同步

    /** 供 ServerEntity#sendPairingData 的 Consumer 使用：和实体自己的 spawn 包同一个 bundle。 */
    @SuppressWarnings("unchecked")
    public void sendInitial(ServerPlayer player, Consumer<Packet<ClientGamePacketListener>> out) {
        // 走 addObserver 而不是直接 add：它会置 intervalsDirty。
        this.addObserver(player);
        List<RankedLine> visible = this.rankVisibleLines(player);
        for (RankedLine ranked : visible) {
            // 新观察者的刷新间隔立刻算出来，而不是以默认值 1 撑到下一次批量重算
            NameTagLine line = ranked.line();
            line.recomputeRefreshInterval(line.state(player), player);
        }
        for (Packet<? super ClientGamePacketListener> packet : this.buildSync(player, visible)) {
            out.accept((Packet<ClientGamePacketListener>) packet);
        }
    }

    /** {@code visible} 由调用方（tick / sendInitial）算好，避免重复求值 isVisible / priority。 */
    private List<Packet<? super ClientGamePacketListener>> buildSync(ServerPlayer player, List<RankedLine> visible) {
        UUID uuid = player.getUUID();
        int[] before = this.visible.getOrDefault(uuid, EMPTY);

        // 自下而上累加抬升量：每条 lineHeight 决定它上面那条被顶多高
        List<Wanted> wanted = new ArrayList<>(visible.size());
        double lift = NameTagConfig.VANILLA_NAMETAG_GAP;
        for (RankedLine ranked : visible) {
            NameTagLine line = ranked.line();
            wanted.add(new Wanted(line, (float) lift, this.flagsFor(line, player), this.backgroundFor(line, player), line.nametag().textOpacity(player)));
            lift += Math.max(NameTagConfig.MIN_LINE_HEIGHT, line.nametag().lineHeight(player));
        }

        int[] after = new int[wanted.size()];
        for (int i = 0; i < wanted.size(); i++) {
            after[i] = wanted.get(i).line().display().id;
        }
        boolean setChanged = !Arrays.equals(before, after);

        List<Packet<? super ClientGamePacketListener>> packets = new ArrayList<>();
        if (setChanged) {
            // 顺序很重要：载具（被观察者）必须已经存在于客户端，乘客包才不会被丢弃。
            Vec3 at = this.observee.position();
            for (Wanted want : wanted) {
                if (!contains(before, want.line().display().id)) {
                    packets.add(NameTagPackets.addDisplay(
                            want.line().display().id, want.line().display().uuid, at));
                }
            }
        }

        for (Wanted want : wanted) {
            NameTagLine line = want.line();
            NameTagLine.PlayerState lineState = line.state(player);
            Component text = line.componentOrRefresh(lineState, this.observee, player);
            NameTagLine.SentState sent = new NameTagLine.SentState(
                    line.revision(lineState), want.lift(), want.flags(), want.background(), want.textOpacity);
            if (sent.equals(line.lastSent(lineState))) {
                continue;
            }
            packets.add(line.display().dataPacket(text, want.lift(), want.flags(), want.background(), want.textOpacity));
            line.markSent(lineState, sent);
        }

        if (setChanged) {
            for (int id : before) {
                if (!contains(after, id)) {
                    packets.add(NameTagPackets.removeEntities(id));
                    // 客户端把这个假实体删了，它那份同步数据也一起没了。必须清掉
                    // 「已发送状态」，否则它下次再可见时 state.equals(lastSent) 仍然成立
                    // （静态文本 revision 不变）→ 跳过 SetEntityData →
                    // 客户端挂着一个「刚 AddEntity、还是默认空文本」的 display。
                    this.markLineHidden(id, player);
                }
            }
            packets.add(NameTagPackets.setPassengers(this.observee.getId(), this.passengerIds(after)));
            this.setVisible(player, after);
        }

        return packets;
    }

    /** 乘客数组 = 真实乘客（在前）+ 本玩家可见的假实体（在后，只 append）。 */
    private int[] passengerIds(int[] fakeIds) {
        List<Entity> real = this.observee.getPassengers();
        if (real.isEmpty()) {
            return fakeIds;
        }
        int[] result = new int[real.size() + fakeIds.length];
        for (int i = 0; i < real.size(); i++) {
            result[i] = real.get(i).getId();
        }
        System.arraycopy(fakeIds, 0, result, real.size(), fakeIds.length);
        return result;
    }

    private boolean canSee(NameTagLine line, ServerPlayer player) {
        if (!NameTagConfig.SHOW_SELF_NAMETAG && player == this.observee) {
            return false;
        }
        return line.nametag().isVisible(this.observee, player);
    }

    private byte flagsFor(NameTagLine line, ServerPlayer player) {
        SeeThroughStatus status = line.nametag().seeThroughStatus(player);
        boolean seeThrough = switch (status) {
            case ALWAYS -> true;
            case NEVER -> false;
            case VANILLA -> !this.observee.isShiftKeyDown();
        };
        return VirtualTextDisplay.styleFlags(seeThrough);
    }

    private int backgroundFor(NameTagLine line, ServerPlayer player) {
        return VirtualTextDisplay.backgroundOrDefault(line.nametag().backgroundColor(player));
    }

    private boolean isValid(ServerPlayer player) {
        return !this.observee.isRemoved() && this.observee.level() == player.level();
    }

    private void setVisible(ServerPlayer player, int[] ids) {
        Map<UUID, int[]> next = new HashMap<>(this.visible);
        next.put(player.getUUID(), ids);
        this.visible = Collections.unmodifiableMap(next);
    }

    /** 找出 display id 对应的行，标记它对这个玩家已隐藏（丢内容缓存 + 丢已发送状态）。 */
    private void markLineHidden(int displayId, ServerPlayer player) {
        for (NameTagLine line : this.lines) {
            if (line.display().id == displayId) {
                line.markHidden(player);
                return;
            }
        }
    }

    /** 强制重发所有行的同步数据（见 NameTags#invalidate）。 */
    void invalidateAll() {
        for (NameTagLine line : this.lines) {
            line.invalidate();
        }
    }

    /** 被观察者要消失时调用：把所有假实体撤掉。 */
    void evictAll() {
        if (this.lines.isEmpty()) {
            return;
        }
        int[] ids = new int[this.lines.size()];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = this.lines.get(i).display().id;
        }
        for (ServerPlayer player : this.observers) {
            player.connection.send(NameTagPackets.removeEntities(ids));
        }
        this.visible = Map.of();
    }

    /** 释放假实体 id 并清空行。 */
    void releaseFakeIds() {
        for (NameTagLine line : this.lines) {
            NameTagRegistry.unregisterFakeId(line.display().id);
        }
        this.lines.clear();
    }

    private record Wanted(NameTagLine line, float lift, byte flags, int background, byte textOpacity) {
    }

    /** 排序用的临时载体：把 priority 物化，避免比较器反复调用用户代码。 */
    private record RankedLine(NameTagLine line, int priority) {
    }

    private static boolean contains(int[] ids, int id) {
        for (int value : ids) {
            if (value == id) {
                return true;
            }
        }
        return false;
    }

    private static int[] removeId(int[] ids, int id) {
        int[] result = new int[ids.length - 1];
        int index = 0;
        for (int value : ids) {
            if (value != id) {
                result[index++] = value;
            }
        }
        return result;
    }
}
