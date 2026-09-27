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
import java.util.Collections;
import java.util.Comparator;
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

    private final Entity observee;
    private final ServerLevel level;
    private final List<NameTagLine> lines = new ArrayList<>();
    private final Set<ServerPlayer> observers = new LinkedHashSet<>();

    /** player uuid -> 该玩家当前可见的假实体 id（有序）。整体替换，读端无锁。 */
    private volatile Map<UUID, int[]> visible = Map.of();

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

        for (ServerPlayer player : this.observers) {
            int[] current = this.visibleIds(player);
            if (!contains(current, target.display().id)) {
                continue;
            }
            player.connection.send(NameTagPackets.removeEntities(target.display().id));
            this.setVisible(player, removeId(current, target.display().id));
            target.forget(player);
            player.connection.send(NameTagPackets.setPassengers(
                    this.observee.getId(), this.passengerIds(this.visibleIds(player))));
        }
    }

    // ------------------------------------------------------------------ 观察者

    void addObserver(ServerPlayer player) {
        this.observers.add(player);
    }

    /** 对应 ServerEntity#removePairing（HEAD）：客户端此刻还认识被观察者，先撤掉假实体。 */
    void removeObserver(ServerPlayer player) {
        if (!this.observers.remove(player)) {
            return;
        }
        int[] ids = this.visibleIds(player);
        if (ids.length > 0) {
            player.connection.send(NameTagPackets.removeEntities(ids));
        }
        for (NameTagLine line : this.lines) {
            line.forget(player);
        }
        this.setVisible(player, EMPTY);
    }

    /** 补发现：处理「nametag 在配对之后才 attach」的情况。 */
    void discoverObservers() {
        for (ServerPlayer player : this.level.players()) {
            if (this.observers.contains(player)) {
                continue;
            }
            if (this.isTrackedBy(player)) {
                this.observers.add(player);
            }
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

    public void tick(int tick, boolean discover) {
        if (discover) {
            this.discoverObservers();
        }
        this.refreshComponents(tick);
        for (ServerPlayer player : this.observers) {
            if (this.isValid(player)) {
                this.sync(player);
            }
        }
    }

    private void refreshComponents(int tick) {
        for (NameTagLine line : this.lines) {
            if (line.revision() == 0) {
                line.refresh(this.observee);
                continue;
            }
            int interval = this.minInterval(line);
            if (interval > 0 && tick % interval == 0) {
                line.refresh(this.observee);
            }
        }
    }

    /** 所有观察者里要求的最小刷新间隔；没人要求刷新时返回 0。 */
    private int minInterval(NameTagLine line) {
        int min = Integer.MAX_VALUE;
        for (ServerPlayer player : this.observers) {
            int interval = line.nametag().updateIntervalTicks(player);
            if (interval > 0 && interval < min) {
                min = interval;
            }
        }
        return min == Integer.MAX_VALUE ? 0 : min;
    }

    // ------------------------------------------------------------------ 同步

    /** 供 ServerEntity#sendPairingData 的 Consumer 使用：和实体自己的 spawn 包同一个 bundle。 */
    @SuppressWarnings("unchecked")
    public void sendInitial(ServerPlayer player, Consumer<Packet<ClientGamePacketListener>> out) {
        this.observers.add(player);
        for (Packet<? super ClientGamePacketListener> packet : this.buildSync(player)) {
            out.accept((Packet<ClientGamePacketListener>) packet);
        }
    }

    private void sync(ServerPlayer player) {
        List<Packet<? super ClientGamePacketListener>> packets = this.buildSync(player);
        if (!packets.isEmpty()) {
            player.connection.send(NameTagPackets.bundle(packets));
        }
    }

    private List<Packet<? super ClientGamePacketListener>> buildSync(ServerPlayer player) {
        UUID uuid = player.getUUID();
        int[] before = this.visible.getOrDefault(uuid, EMPTY);

        // 1) 先取出这个玩家能看到的所有行
        List<NameTagLine> visible = new ArrayList<>(this.lines.size());
        for (NameTagLine line : this.lines) {
            if (this.canSee(line, player)) {
                visible.add(line);
            }
        }
        // 2) 按优先级降序：数值越大越靠近头部（lift 越小）。
        //    List.sort 是稳定排序，所以同优先级保持 attach 顺序（先挂的在下面）。
        visible.sort(Comparator.comparingInt(
                (NameTagLine line) -> line.nametag().priority(player)).reversed());

        // 3) 自下而上累加抬升量：每条 lineHeight 决定它上面那条被顶多高
        List<Wanted> wanted = new ArrayList<>(visible.size());
        double lift = NameTagConfig.VANILLA_NAMETAG_GAP;
        for (NameTagLine line : visible) {
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
            Component text = line.componentOrRefresh(this.observee);
            NameTagLine.SentState state = new NameTagLine.SentState(
                    line.revision(), want.lift(), want.flags(), want.background(), want.textOpacity);
            if (state.equals(line.lastSent(player))) {
                continue;
            }
            packets.add(line.display().dataPacket(text, want.lift(), want.flags(), want.background(), want.textOpacity));
            line.markSent(player, state);
        }

        if (setChanged) {
            for (int id : before) {
                if (!contains(after, id)) {
                    packets.add(NameTagPackets.removeEntities(id));
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

    private boolean isTrackedBy(ServerPlayer player) {
        if (this.observee.level() != player.level()) {
            return false;
        }
        if (!this.observee.broadcastToPlayer(player)) {
            return false;
        }
        double range = Math.max(16.0D, this.observee.getType().clientTrackingRange() * 16.0D);
        return player.distanceToSqr(this.observee) <= range * range;
    }

    private boolean isValid(ServerPlayer player) {
        return !this.observee.isRemoved() && this.observee.level() == player.level();
    }

    private void setVisible(ServerPlayer player, int[] ids) {
        Map<UUID, int[]> next = new HashMap<>(this.visible);
        next.put(player.getUUID(), ids);
        this.visible = Collections.unmodifiableMap(next);
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
