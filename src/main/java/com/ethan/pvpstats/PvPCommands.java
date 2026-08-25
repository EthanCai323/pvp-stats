package com.ethan.pvpstats;

import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.List;

/**
 * 命令树：
 * /pvp start &lt;name&gt;            创建组别
 * /pvp add &lt;group&gt; &lt;player&gt;  邀请玩家
 * /pvp accept &lt;group&gt;           接受邀请（聊天按钮）
 * /pvp decline &lt;group&gt;          拒绝邀请（聊天按钮）
 * /pvp quit &lt;group&gt;             退出组别
 * /pvp end &lt;group&gt;              发起结束请求
 * /pvp agree &lt;group&gt;            同意结束（聊天按钮）
 */
public class PvPCommands {

    private static final SuggestionProvider<ServerCommandSource> ALL_GROUPS =
            (context, builder) -> CommandSource.suggestMatching(PvPManager.groupNames(), builder);

    private static final SuggestionProvider<ServerCommandSource> MY_GROUP =
            (context, builder) -> {
                ServerPlayerEntity player = context.getSource().getPlayer();
                String group = player == null ? null : PvPManager.groupOf(player.getUuid());
                return CommandSource.suggestMatching(group == null ? List.of() : List.of(group), builder);
            };

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(CommandManager.literal("pvp")
                        .then(CommandManager.literal("start")
                                .then(CommandManager.argument("name", StringArgumentType.word())
                                        .executes(ctx -> PvPManager.cmdStart(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "name")))))
                        .then(CommandManager.literal("add")
                                .then(CommandManager.argument("group", StringArgumentType.word()).suggests(MY_GROUP)
                                        .then(CommandManager.argument("player", EntityArgumentType.player())
                                                .executes(ctx -> PvPManager.cmdAdd(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "group"),
                                                        EntityArgumentType.getPlayer(ctx, "player"))))))
                        .then(CommandManager.literal("accept")
                                .then(CommandManager.argument("group", StringArgumentType.word()).suggests(ALL_GROUPS)
                                        .executes(ctx -> PvPManager.cmdAccept(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "group")))))
                        .then(CommandManager.literal("decline")
                                .then(CommandManager.argument("group", StringArgumentType.word()).suggests(ALL_GROUPS)
                                        .executes(ctx -> PvPManager.cmdDecline(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "group")))))
                        .then(CommandManager.literal("quit")
                                .then(CommandManager.argument("group", StringArgumentType.word()).suggests(MY_GROUP)
                                        .executes(ctx -> PvPManager.cmdQuit(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "group")))))
                        .then(CommandManager.literal("end")
                                .then(CommandManager.argument("group", StringArgumentType.word()).suggests(MY_GROUP)
                                        .executes(ctx -> PvPManager.cmdEnd(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "group")))))
                        .then(CommandManager.literal("agree")
                                .then(CommandManager.argument("group", StringArgumentType.word()).suggests(ALL_GROUPS)
                                        .executes(ctx -> PvPManager.cmdAgree(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "group")))))
                ));
    }
}
