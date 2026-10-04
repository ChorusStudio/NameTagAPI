package com.perry.nametagapi.impl;

import com.perry.nametagapi.NameTagConfig;
import com.perry.nametagapi.mixin.DisplayAccessor;
import com.perry.nametagapi.mixin.TextDisplayAccessor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityTypes;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 一个「服务端并不存在」的 TextDisplay。
 * <p>
 * 我们真的 new 一个 {@link Display.TextDisplay}，但<b>永不加入世界</b>：
 * 这样白拿了 id / uuid / 该类型的默认同步数据表，完全不需要 Unsafe 分配或
 * defineSynchedData 注入。真正的落点靠「骑乘」实现。
 */
public final class VirtualTextDisplay {

    public final int id;
    public final UUID uuid;

    public VirtualTextDisplay(ServerLevel level) {
        Display.TextDisplay entity = new Display.TextDisplay(EntityTypes.TEXT_DISPLAY, level);
        this.id = entity.getId();
        this.uuid = entity.getUUID();
    }

    /**
     * 逐玩家构造数据包：样式可以因人而异，所以不能用实体自身的
     * packDirty()/getNonDefaultValues()（那只能给出「一份」值）。
     */
    public ClientboundSetEntityDataPacket dataPacket(
            Component text,
            float lift,
            byte flags,
            int background,
            int textOpacity
    ) {
        List<SynchedEntityData.DataValue<?>> values = new ArrayList<>(7);
        values.add(SynchedEntityData.DataValue.create(TextDisplayAccessor.text(), text));
        values.add(SynchedEntityData.DataValue.create(TextDisplayAccessor.styleFlags(), flags));
        values.add(SynchedEntityData.DataValue.create(TextDisplayAccessor.backgroundColor(), background));
        values.add(SynchedEntityData.DataValue.create(
                DisplayAccessor.billboardRenderConstraints(), NameTagConfig.BILLBOARD));
        // DATA_HEIGHT 在这里的作用是渲染裁剪盒高度（不是抬升量！抬升走 translation）。
        // 写 0 的话，多行 nametag 会在屏幕边缘被整体裁掉。
        values.add(SynchedEntityData.DataValue.create(DisplayAccessor.height(), lift));
        values.add(SynchedEntityData.DataValue.create(
                DisplayAccessor.translation(), new Vector3f(0.0F, lift, 0.0F)));
        values.add(SynchedEntityData.DataValue.create(TextDisplayAccessor.textOpacity(), (byte) textOpacity));
        return new ClientboundSetEntityDataPacket(this.id, values);
    }

    public static byte styleFlags(boolean seeThrough) {
        return seeThrough ? Display.TextDisplay.FLAG_SEE_THROUGH : (byte) 0;
    }

    public static int backgroundOrDefault(Integer custom) {
        return custom != null ? custom : Display.TextDisplay.INITIAL_BACKGROUND;
    }
}
