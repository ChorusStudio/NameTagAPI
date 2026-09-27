package com.perry.nametagapi.mixin;

import com.perry.nametagapi.impl.NameTagRegistry;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

/** 把「原版实体追踪」变成我们的观察者集合，并借配对流程塞入初始包。 */
@Mixin(ServerEntity.class)
public abstract class ServerEntityMixin {
    @Shadow
    @Final
    private Entity entity;

    /**
     * TAIL：原版此时已经把自己的 AddEntity / SetEntityData / SetPassengers 都塞进这个
     * Consumer 了，我们追加在后面 —— 于是和实体 spawn 在同一个 bundle 里按序下发，
     * 既保证「载具先于乘客」，也没有一帧的空档。
     */
    @Inject(method = "sendPairingData", at = @At("TAIL"))
    private void nametagapi$pairingData(ServerPlayer player,
                                        Consumer<Packet<ClientGamePacketListener>> out,
                                        CallbackInfo ci) {
        NameTagRegistry.onPairingData(this.entity, player, out);
    }

    @Inject(method = "addPairing", at = @At("TAIL"))
    private void nametagapi$addPairing(ServerPlayer player, CallbackInfo ci) {
        NameTagRegistry.onStartTracking(this.entity, player);
    }

    /** HEAD：客户端此刻还认识被观察者，先把假实体撤掉，避免 ghost。 */
    @Inject(method = "removePairing", at = @At("HEAD"))
    private void nametagapi$removePairing(ServerPlayer player, CallbackInfo ci) {
        NameTagRegistry.onStopTracking(this.entity, player);
    }
}
