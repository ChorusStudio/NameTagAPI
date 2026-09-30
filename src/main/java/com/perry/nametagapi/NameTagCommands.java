package com.perry.nametagapi;

import com.mojang.brigadier.CommandDispatcher;
import com.perry.nametagapi.api.NameTag;
import com.perry.nametagapi.api.NameTags;
import com.perry.nametagapi.api.SeeThroughStatus;
import com.perry.nametagapi.mixin.ClientboundEntityEventPacketAccessor;
import io.netty.util.internal.shaded.org.jctools.util.UnsafeAccess;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ComponentArgument;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.NoSuchElementException;

/** 调试命令，删掉不影响其它文件。 */
public final class NameTagCommands {
    private NameTagCommands() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, environment) ->
                register(dispatcher, buildContext));
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext) {
        dispatcher.register(Commands.literal("nametagapi")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("add")
                        .then(Commands.argument("targets", EntityArgument.entities())
                                .then(Commands.argument("text", ComponentArgument.textComponent(buildContext))
                                        .executes(context -> {
                                            Collection<? extends Entity> targets =
                                                    EntityArgument.getEntities(context, "targets");
                                            for (Entity entity : targets) {
                                                Component text = ComponentArgument.getResolvedComponent(
                                                        context, "text", entity);
                                                NameTags.attach(entity, NameTag.simple(text));
                                            }
                                            return targets.size();
                                        }))))
                .then(Commands.literal("demo1")
                        .then(Commands.argument("targets", EntityArgument.entities())
                                .executes(context -> {
                                    Collection<? extends Entity> targets =
                                            EntityArgument.getEntities(context, "targets");
                                    for (Entity entity : targets) {
                                        NameTags.attach(entity, new TextNameTag(
                                                Component.literal("[1] 第一行"), 0.275D, SeeThroughStatus.ALWAYS, null, (byte) 255));
                                        NameTags.attach(entity, new TextNameTag(
                                                Component.literal("[2] 潜行时不穿墙"), 0.275D, SeeThroughStatus.VANILLA, null, (byte) 255));
                                        NameTags.attach(entity, new TextNameTag(
                                                Component.literal("[3] 自定义背景, 永远不穿墙"), 0.4D, SeeThroughStatus.NEVER, 0x8000FF00, (byte) 255));
                                        NameTags.attach(entity, new TextNameTag(
                                                Component.literal("[4] 半透明文本"), 0.4D, SeeThroughStatus.NEVER, 0x8000FF00, (byte) 127));
                                    }
                                    return targets.size();
                                })))
                .then(Commands.literal("demo2")
                        .then(Commands.argument("targets", EntityArgument.entities())
                                .executes(context -> {
                                    Collection<? extends Entity> targets =
                                            EntityArgument.getEntities(context, "targets");
                                    for (Entity entity : targets) {
                                        NameTags.attach(entity, new TextNameTag(
                                                Component.literal("[1] 第一行\n以及第二行在同一个textdisplay"), 0.6D, SeeThroughStatus.ALWAYS, null, (byte) 255));
                                        NameTags.attach(entity, new TextNameTag(
                                                Component.literal("[2] 第三行\n以及第四行\n还有第五行在同一个textdisplay"), 0.9D, SeeThroughStatus.ALWAYS, null, (byte) 255));
                                        NameTags.attach(entity, new TextNameTag(
                                                Component.literal("[3] 6\n7\n8\n9\n10\n11行堆叠起来！"), 1.5D, SeeThroughStatus.ALWAYS, null, (byte) 255));
                                    }
                                    return targets.size();
                                })))
                .then(Commands.literal("demo3")
                        .then(Commands.argument("targets", EntityArgument.entities())
                                .executes(context -> {
                                    Collection<? extends Entity> targets =
                                            EntityArgument.getEntities(context, "targets");
                                    for (Entity entity : targets) {
                                        // 刻意乱序 attach：视觉顺序应完全由 priority 决定
                                        NameTags.attach(entity, new TextNameTag(
                                                Component.literal("[p=0] 中间"), 0.275D, SeeThroughStatus.ALWAYS, null, (byte) 255, 0));
                                        NameTags.attach(entity, new TextNameTag(
                                                Component.literal("[p=10] 最低（最贴近头部）"), 0.275D, SeeThroughStatus.ALWAYS, null, (byte) 255, 10));
                                        NameTags.attach(entity, new TextNameTag(
                                                Component.literal("[p=-10] 最高"), 0.275D, SeeThroughStatus.ALWAYS, null, (byte) 255, -10));
                                    }
                                    return targets.size();
                                })))
                .then(Commands.literal("demo4")
                        .then(Commands.argument("targets", EntityArgument.entities())
                                .executes( context -> {
                                    ServerPlayer player = context.getSource().getPlayerOrException();
                                    Collection<? extends Entity> targets = EntityArgument.getEntities(context, "targets");
                                    List<Packet<? super ClientGamePacketListener>> packets = new ArrayList<>();
                                    for (Entity entity : targets) {
                                        try {
                                            int clientEntityId = NameTags.displays(entity, player).getFirst().entityId();
                                            var packet = (ClientboundEntityEventPacket) UnsafeAccess.UNSAFE.allocateInstance(ClientboundEntityEventPacket.class);
                                            ((ClientboundEntityEventPacketAccessor) packet).setEntityId(clientEntityId);
                                            ((ClientboundEntityEventPacketAccessor) packet).setEventId(EntityEvent.PROTECTED_FROM_DEATH);
                                            packets.add(packet);
                                        } catch (NoSuchElementException | InstantiationException ignored) {

                                        }
                                    }
                                    player.connection.send(new ClientboundBundlePacket(packets));
                                    return 0;
                                })))
                .then(Commands.literal("clear")
                        .then(Commands.argument("targets", EntityArgument.entities())
                                .executes(context -> {
                                    Collection<? extends Entity> targets =
                                            EntityArgument.getEntities(context, "targets");
                                    for (Entity entity : targets) {
                                        NameTags.clear(entity);
                                    }
                                    return targets.size();
                                }))));
    }

    /** 逐玩家样式的示例实现。 */
    private record TextNameTag(Component text, double height, SeeThroughStatus seeThrough, Integer background,
                               byte opacity, int priority) implements NameTag {
        /** 不带优先级的便捷构造（默认 0）。 */
        TextNameTag(Component text, double height, SeeThroughStatus seeThrough, Integer background, byte opacity) {
            this(text, height, seeThrough, background, opacity, 0);
        }

        @Override
        public Component content(Entity observee, ServerPlayer observer) {
            return this.text;
        }

        @Override
        public int priority(ServerPlayer observer) {
            return this.priority;
        }

        @Override
        public byte textOpacity(ServerPlayer observer) {
            return this.opacity;
        }

        @Override
        public double lineHeight(ServerPlayer observer) {
            return this.height;
        }

        @Override
        public SeeThroughStatus seeThroughStatus(ServerPlayer observer) {
            return this.seeThrough;
        }

        @Override
        public Integer backgroundColor(ServerPlayer observer) {
            return this.background;
        }
    }
}
