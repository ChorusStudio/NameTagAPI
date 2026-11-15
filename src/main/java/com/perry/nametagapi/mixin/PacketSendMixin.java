package com.perry.nametagapi.mixin;

import com.perry.nametagapi.impl.NameTagRegistry;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * 出站包唯一入口：把假乘客合并进 SetPassengers。
 * <p>
 * 这个回调可能在非服务端线程执行，所以 NameTagRegistry#rewrite 及其下游
 * 只能读并发快照，不能改游戏状态。
 * <p>
 * 按类型选择第一个 Packet 参数，不依赖混淆后的局部变量名。
 */
@Mixin(value = ServerCommonPacketListenerImpl.class, priority = 1100)
public abstract class PacketSendMixin {
    @ModifyVariable(
            method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V",
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0
    )
    private Packet<?> nametagapi$mergePassengers(Packet<?> packet) {
        if (!(((Object) this) instanceof ServerGamePacketListenerImpl connection)) {
            return packet;
        }
        return NameTagRegistry.rewrite(connection.player, packet);
    }
}
