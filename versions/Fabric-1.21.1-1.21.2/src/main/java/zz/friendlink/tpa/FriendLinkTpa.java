package zz.friendlink.tpa;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.RelativeMovement;

import java.lang.reflect.Constructor;
import java.time.Duration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class FriendLinkTpa implements ModInitializer {
    private static final long REQUEST_TIMEOUT_MILLIS = Duration.ofSeconds(60).toMillis();
    private static final long REQUEST_COOLDOWN_MILLIS = Duration.ofSeconds(10).toMillis();
    private static final Map<UUID, TpaRequest> REQUESTS_BY_TARGET = new HashMap<>();
    private static final Map<UUID, Long> LAST_REQUEST_AT = new HashMap<>();

    @Override
    public void onInitialize() {
        TpaNetworking.registerS2cPayload();
        ServerTickEvents.END_SERVER_TICK.register(server -> cleanupExpired(true, server.getPlayerList()));
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("tpa")
                .then(Commands.argument("target", EntityArgument.player())
                    .executes(context -> requestTeleport(
                        context.getSource(),
                        EntityArgument.getPlayer(context, "target")
                    )))
                .then(Commands.argument("targetName", StringArgumentType.word())
                    .executes(context -> requestTeleportByName(
                        context.getSource(),
                        StringArgumentType.getString(context, "targetName")
                    ))));
            dispatcher.register(Commands.literal("tpy")
                .executes(context -> answerTeleport(context.getSource(), true)));
            dispatcher.register(Commands.literal("tpn")
                .executes(context -> answerTeleport(context.getSource(), false)));
        });
    }

    private static int requestTeleportByName(CommandSourceStack source, String targetName) throws CommandSyntaxException {
        ServerPlayer target = source.getServer().getPlayerList().getPlayerByName(targetName);
        if (target == null) {
            source.sendFailure(message("tpa.error.player_offline", ChatFormatting.RED, targetName));
            return 0;
        }
        return requestTeleport(source, target);
    }

    private static int requestTeleport(CommandSourceStack source, ServerPlayer target) throws CommandSyntaxException {
        ServerPlayer requester = source.getPlayerOrException();
        cleanupExpired(false, source.getServer().getPlayerList());

        if (requester.getUUID().equals(target.getUUID())) {
            source.sendFailure(message("tpa.error.self", ChatFormatting.RED));
            return 0;
        }
        if (!supportsTpa(requester)) {
            source.sendFailure(message("tpa.error.client_missing", ChatFormatting.RED));
            return 0;
        }
        if (!supportsTpa(target)) {
            source.sendFailure(message("tpa.error.target_missing", ChatFormatting.RED));
            return 0;
        }

        long now = System.currentTimeMillis();
        long lastRequestAt = LAST_REQUEST_AT.getOrDefault(requester.getUUID(), 0L);
        long cooldownLeft = REQUEST_COOLDOWN_MILLIS - (now - lastRequestAt);
        if (cooldownLeft > 0L) {
            source.sendFailure(message("tpa.error.cooldown", ChatFormatting.RED, seconds(cooldownLeft)));
            return 0;
        }

        removeRequesterRequests(requester.getUUID());
        REQUESTS_BY_TARGET.put(target.getUUID(), new TpaRequest(requester.getUUID(), target.getUUID(), now));
        LAST_REQUEST_AT.put(requester.getUUID(), now);

        requester.displayClientMessage(message("tpa.sent", ChatFormatting.YELLOW, nameComponent(name(target))), false);
        target.displayClientMessage(requestMessage(requester), false);
        return 1;
    }

    private static int answerTeleport(CommandSourceStack source, boolean accepted) throws CommandSyntaxException {
        ServerPlayer target = source.getPlayerOrException();
        cleanupExpired(false, source.getServer().getPlayerList());

        TpaRequest request = REQUESTS_BY_TARGET.remove(target.getUUID());
        if (request == null) {
            source.sendFailure(message("tpa.error.no_pending", ChatFormatting.RED));
            return 0;
        }

        ServerPlayer requester = source.getServer().getPlayerList().getPlayer(request.requesterId());
        if (requester == null) {
            target.displayClientMessage(message("tpa.error.requester_offline", ChatFormatting.RED), false);
            return 0;
        }

        if (!accepted) {
            target.displayClientMessage(message("tpa.denied_target", ChatFormatting.RED, nameComponent(name(requester))), false);
            requester.displayClientMessage(message("tpa.denied_requester", ChatFormatting.RED, nameComponent(name(target))), false);
            return 1;
        }
        if (!supportsTpa(requester) || !supportsTpa(target)) {
            target.displayClientMessage(message("tpa.error.both_required", ChatFormatting.RED), false);
            requester.displayClientMessage(message("tpa.error.both_required", ChatFormatting.RED), false);
            return 0;
        }

        teleportRequester(requester, target);
        target.displayClientMessage(message("tpa.accepted_target", ChatFormatting.GREEN, nameComponent(name(requester))), false);
        requester.displayClientMessage(message("tpa.teleported", ChatFormatting.GREEN, nameComponent(name(target))), false);
        return 1;
    }

    private static void teleportRequester(ServerPlayer requester, ServerPlayer target) {
        ServerLevel level = target.serverLevel();
        if (tryTeleportRequester(requester, level, target.getX(), target.getY(), target.getZ(), target.getYRot(), target.getXRot())) {
            return;
        }
        requester.teleportTo(target.getX(), target.getY(), target.getZ());
    }

    private static boolean tryTeleportRequester(ServerPlayer requester, ServerLevel level, double x, double y, double z, float yaw, float pitch) {
        try {
            java.lang.reflect.Method method = ServerPlayer.class.getMethod(
                "teleportTo",
                ServerLevel.class,
                double.class,
                double.class,
                double.class,
                Set.class,
                float.class,
                float.class,
                boolean.class
            );
            Object result = method.invoke(requester, level, x, y, z, Set.<RelativeMovement>of(), yaw, pitch, false);
            return !(result instanceof Boolean success) || success;
        } catch (NoSuchMethodException ignored) {
            return tryLegacyTeleportRequester(requester, level, x, y, z, yaw, pitch);
        } catch (ReflectiveOperationException | LinkageError exception) {
            return false;
        }
    }

    private static boolean tryLegacyTeleportRequester(ServerPlayer requester, ServerLevel level, double x, double y, double z, float yaw, float pitch) {
        try {
            java.lang.reflect.Method method = ServerPlayer.class.getMethod(
                "teleportTo",
                ServerLevel.class,
                double.class,
                double.class,
                double.class,
                Set.class,
                float.class,
                float.class
            );
            Object result = method.invoke(requester, level, x, y, z, Set.<RelativeMovement>of(), yaw, pitch);
            return !(result instanceof Boolean success) || success;
        } catch (ReflectiveOperationException | LinkageError exception) {
            return false;
        }
    }

    private static MutableComponent requestMessage(ServerPlayer requester) {
        return prefix()
            .append(text("tpa.request.incoming", nameComponent(name(requester))).withStyle(ChatFormatting.YELLOW))
            .append(action(button("button.accept"), "/tpy", ChatFormatting.GREEN))
            .append(Component.literal(" "))
            .append(action(button("button.decline"), "/tpn", ChatFormatting.RED))
            .append(text("tpa.request.expires").withStyle(ChatFormatting.GOLD));
    }

    private static MutableComponent action(MutableComponent label, String command, ChatFormatting color) {
        return label
            .withStyle(style -> style
                .withColor(color)
                .withBold(true)
                .withClickEvent(runCommandClickEvent(command)));
    }

    private static ClickEvent runCommandClickEvent(String command) {
        ClickEvent event = newRunCommandClickEvent(command);
        return event != null ? event : legacyRunCommandClickEvent(command);
    }

    private static ClickEvent newRunCommandClickEvent(String command) {
        for (Class<?> nestedClass : ClickEvent.class.getDeclaredClasses()) {
            if (!ClickEvent.class.isAssignableFrom(nestedClass)) {
                continue;
            }
            try {
                Constructor<?> constructor = nestedClass.getConstructor(String.class);
                ClickEvent event = (ClickEvent) constructor.newInstance(command);
                if (isRunCommand(event)) {
                    return event;
                }
            } catch (ReflectiveOperationException | LinkageError ignored) {
                // Try the next nested click-event variant, then fall back to the old API.
            }
        }
        return null;
    }

    private static boolean isRunCommand(ClickEvent event) {
        Object action = invokeAction(event);
        return action instanceof Enum<?> actionEnum && "RUN_COMMAND".equals(actionEnum.name());
    }

    private static Object invokeAction(ClickEvent event) {
        for (java.lang.reflect.Method method : event.getClass().getMethods()) {
            if (method.getParameterCount() != 0 || !method.getReturnType().isEnum()) {
                continue;
            }
            try {
                return method.invoke(event);
            } catch (ReflectiveOperationException | LinkageError ignored) {
                // Try the next no-arg enum method.
            }
        }
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ClickEvent legacyRunCommandClickEvent(String command) {
        try {
            Class<?> actionClass = null;
            for (Class<?> nestedClass : ClickEvent.class.getDeclaredClasses()) {
                if (nestedClass.isEnum() && hasEnumConstant(nestedClass, "RUN_COMMAND")) {
                    actionClass = nestedClass;
                    break;
                }
            }
            if (actionClass == null) {
                throw new NoSuchMethodException("ClickEvent action enum not found");
            }
            Object action = Enum.valueOf((Class<? extends Enum>) actionClass.asSubclass(Enum.class), "RUN_COMMAND");
            Constructor<ClickEvent> constructor = ClickEvent.class.getConstructor(actionClass, String.class);
            return constructor.newInstance(action, command);
        } catch (ReflectiveOperationException | LinkageError exception) {
            throw new IllegalStateException("Cannot create run-command click event", exception);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean hasEnumConstant(Class<?> enumClass, String constantName) {
        try {
            Enum.valueOf((Class<? extends Enum>) enumClass.asSubclass(Enum.class), constantName);
            return true;
        } catch (IllegalArgumentException | ClassCastException exception) {
            return false;
        }
    }

    private static void cleanupExpired(boolean notifyPlayers, PlayerList playerList) {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, TpaRequest>> iterator = REQUESTS_BY_TARGET.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, TpaRequest> entry = iterator.next();
            if (now - entry.getValue().createdAtMillis() > REQUEST_TIMEOUT_MILLIS) {
                if (notifyPlayers) {
                    notifyExpired(entry.getValue(), playerList);
                }
                iterator.remove();
            }
        }
    }

    private static void notifyExpired(TpaRequest request, PlayerList playerList) {
        ServerPlayer requester = playerList.getPlayer(request.requesterId());
        ServerPlayer target = playerList.getPlayer(request.targetId());
        if (requester != null && target != null) {
            requester.displayClientMessage(message("tpa.expired_requester", ChatFormatting.GOLD, nameComponent(name(target))), false);
            target.displayClientMessage(message("tpa.expired_target", ChatFormatting.GOLD, nameComponent(name(requester))), false);
        } else if (requester != null) {
            requester.displayClientMessage(message("tpa.expired", ChatFormatting.GOLD), false);
        } else if (target != null) {
            target.displayClientMessage(message("tpa.expired", ChatFormatting.GOLD), false);
        }
    }

    private static void removeRequesterRequests(UUID requesterId) {
        REQUESTS_BY_TARGET.entrySet().removeIf(entry -> entry.getValue().requesterId().equals(requesterId));
    }

    private static long seconds(long millis) {
        return Math.max(1L, (millis + 999L) / 1000L);
    }

    private static String name(ServerPlayer player) {
        return player.getName().getString();
    }

    private static MutableComponent prefix() {
        return Component.literal("[FriendLink] ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
    }

    private static MutableComponent nameComponent(String playerName) {
        return Component.literal(playerName).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD);
    }

    private static MutableComponent button(String key) {
        return Component.literal("[")
            .append(text(key))
            .append(Component.literal("]"));
    }

    private static MutableComponent message(String key, ChatFormatting color, Object... args) {
        return prefix().append(text(key, args).withStyle(color));
    }

    private static MutableComponent text(String key, Object... args) {
        return Component.translatable("friendlink." + key, args);
    }

    private static boolean supportsTpa(ServerPlayer player) {
        return ServerPlayNetworking.canSend(player, TpaNetworking.INSTALLED_TYPE);
    }

    private record TpaRequest(UUID requesterId, UUID targetId, long createdAtMillis) {
    }
}
