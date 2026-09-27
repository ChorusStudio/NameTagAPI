package com.perry.nametagapi.impl;

import com.perry.nametagapi.api.NameTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 一行 nametag = 一个 NameTag + 一个假实体。 */
final class NameTagLine {
    private final NameTag nametag;
    private final VirtualTextDisplay display;
    private final Map<UUID, SentState> sent = new HashMap<>();

    private Component component;
    private long revision;

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

    long revision() {
        return this.revision;
    }

    /** 重算内容；每次调用都会让 revision 前进，从而触发对所有观察者的重发。 */
    Component refresh(Entity observee) {
        this.component = this.nametag.content(observee);
        this.revision++;
        return this.component;
    }

    Component componentOrRefresh(Entity observee) {
        return this.component != null ? this.component : this.refresh(observee);
    }

    SentState lastSent(ServerPlayer player) {
        return this.sent.get(player.getUUID());
    }

    void markSent(ServerPlayer player, SentState state) {
        this.sent.put(player.getUUID(), state);
    }

    void forget(ServerPlayer player) {
        this.sent.remove(player.getUUID());
    }

    /**
     * 逐玩家的「已发送状态」。
     * <p>
     * 用 revision 而不是 Component 做比较：调用方完全可能返回一个就地修改的
     * MutableComponent，那种情况下 equals 会一直是 true，导致永远不再下发。
     */
    record SentState(long revision, float lift, byte flags, int background, byte textOpacity) {
    }
}
