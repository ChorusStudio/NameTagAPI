package com.perry.nametagapi.impl;

import com.perry.nametagapi.api.NameTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 一行 nametag = 一个 NameTag + 一个假实体。
 * <p>
 * <b>内容现在是逐观察者求值的</b>（{@code NameTag#content(observee, observer)}），
 * 所以内容缓存、revision、刷新间隔三者都必须逐玩家各存一份 —— 否则 A 的内容会
 * 覆盖 B 的缓存、A 的刷新会触发 B 的重发。
 */
final class NameTagLine {
    private final NameTag nametag;
    private final VirtualTextDisplay display;

    /** player uuid -> 该玩家的状态（缓存内容 / revision / 刷新间隔 / 已发送状态）。 */
    private final Map<UUID, PlayerState> states = new HashMap<>();

    NameTagLine(NameTag nametag, VirtualTextDisplay display) {
        this.nametag = nametag;
        this.display = display;
    }

    NameTag nametag() {
        return this.nametag;
    }

    VirtualTextDisplay display() {
        return this.display;
    }

    /** 该玩家这一 tick 要不要重算内容。没算过就一定要算。 */
    boolean isRefreshDue(PlayerState state, int tick) {
        return !state.initialized || (state.interval > 0 && tick % state.interval == 0);
    }

    /**
     * 缓存该玩家的刷新间隔（0 或负数 = 不主动刷新，只在首次算一次）。
     * <p>
     * 间隔属于配置、几乎不会逐 tick 变化，所以由 holder 在观察者集合变动时或每 N tick
     * 统一重算，避免每 tick 对每条行 × 每个观察者调用一次用户代码。
     */
    void recomputeRefreshInterval(PlayerState state, ServerPlayer player) {
        state.interval = this.nametag.updateIntervalTicks(player);
    }

    /**
     * 重算<b>该玩家</b>的内容。<b>只有内容真的变了才让 revision 前进</b>。
     * <p>
     * 否则默认 updateIntervalTicks = 1 的静态文本会每 tick 判定「脏了」，于是每个
     * 观察者每 tick 都收到一个携带完整 Component 的 SetEntityData。
     * <p>
     * 用 Component 的结构化 equals（MutableComponent 实现了 contents/style/siblings 比较），
     * 所以每次返回新构造的 Component 也能被正确识别为「没变」。
     */
    Component refresh(PlayerState state, Entity observee, ServerPlayer player) {
        Component next = Objects.requireNonNullElse(
                this.nametag.content(observee, player), Component.empty());
        state.initialized = true;
        if (state.component == null || !state.component.equals(next)) {
            state.component = next;
            state.revision++;
        }
        return state.component;
    }

    Component componentOrRefresh(PlayerState state, Entity observee, ServerPlayer player) {
        return state.component != null ? state.component : this.refresh(state, observee, player);
    }

    /** 逐玩家的内容版本号，用来做「已发送」差分。 */
    long revision(PlayerState state) {
        return state.revision;
    }

    /**
     * 强制下一次同步重发。
     * <p>
     * 只对一种实现方式有意义：{@code content()} 返回<b>同一个</b> MutableComponent 实例并
     * 就地修改 —— 那种情况下结构化 equals 恒为 true，检测不到变化。见 {@code NameTags#invalidate}。
     */
    void invalidate() {
        for (PlayerState state : this.states.values()) {
            state.component = null;
            state.revision++;
        }
    }

    SentState lastSent(PlayerState state) {
        return state.sent;
    }

    void markSent(PlayerState state, SentState sent) {
        state.sent = sent;
    }

    /**
     * 行对这个玩家不再可见（客户端那边的假实体马上会被删掉）。
     * <p>
     * 要丢两样东西：
     * <ul>
     *   <li>{@code sent}：客户端手里的同步数据随实体一起没了，下次可见必须整份重发；</li>
     *   <li>{@code component}：不可见期间我们不再刷新内容（见
     *       {@code NameTagHolder#refreshComponents}），若保留旧值，{@code interval = 0}
     *       的行会拿着「进入不可见之前的内容」永远不更新。代价只是再可见时多算一次
     *       {@code content()}。</li>
     * </ul>
     * 刻意保留 {@code interval} / {@code initialized}：它们只是配置和「算过没」的标记，
     * 留着可以让再次可见时不必重算刷新间隔。
     */
    void markHidden(ServerPlayer player) {
        PlayerState state = this.states.get(player.getUUID());
        if (state != null) {
            state.sent = null;
            state.component = null;
        }
    }

    /**
     * 玩家彻底不再观察这条行（removePairing / 断线）：整份状态一起清掉。
     */
    void forget(ServerPlayer player) {
        this.states.remove(player.getUUID());
    }

    /** 取（必要时创建）该玩家在本行上的状态。只有真正可见的行才会被取到。 */
    PlayerState state(ServerPlayer player) {
        return this.states.computeIfAbsent(player.getUUID(), uuid -> new PlayerState());
    }

    /** 逐观察者的可变状态。holder 侧拿到引用后直传，避免同一 tick 里反复查 map。 */
    static final class PlayerState {
        private boolean initialized;
        private Component component;
        private long revision;
        private int interval = 1;
        private SentState sent;
    }

    /**
     * 逐玩家的「已发送状态」。
     * <p>
     * revision 只在内容真的变化时前进（见 {@link #refresh}），所以直接比较它就能
     * 同时表达「文本变了」「抬升量变了（优先级/排序变化）」「样式变了」。
     */
    record SentState(long revision, float lift, byte flags, int background, byte textOpacity) {
    }
}
