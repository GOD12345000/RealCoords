package dev.realcoords;

import org.geysermc.mcprotocollib.network.packet.Packet;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.Set;

/**
 * Inspects packet coordinate payloads for diagnostics and fail-closed handling.
 * This class never changes packet data.
 */
final class PacketCoordinateInspector {
    private static final Set<String> SERVERBOUND_NON_WORLD_COORDINATE_PACKETS = Set.of(
            "ServerboundInteractPacket"
    );

    private static final Set<String> TRANSFORMED_SERVERBOUND_COORDINATE_PACKETS = Set.of(
            "ServerboundAcceptTeleportationPacket",
            "ServerboundUseItemOnPacket",
            "ServerboundPlayerActionPacket",
            "ServerboundPickItemFromBlockPacket",
            "ServerboundSetTestBlockPacket",
            "ServerboundSetCommandBlockPacket",
            "ServerboundSetStructureBlockPacket",
            "ServerboundSetJigsawBlockPacket",
            "ServerboundSignUpdatePacket",
            "ServerboundMoveVehiclePacket",
            "ServerboundMovePlayerPosPacket",
            "ServerboundMovePlayerPosRotPacket"
    );

    private PacketCoordinateInspector() {
    }

    static boolean isTransformedServerboundCoordinatePacket(Packet packet) {
        return packet != null
                && TRANSFORMED_SERVERBOUND_COORDINATE_PACKETS.contains(packet.getClass().getSimpleName());
    }

    static boolean hasUnrecognizedServerboundCoordinates(Packet packet) {
        if (packet == null || !packet.getClass().getSimpleName().startsWith("Serverbound")
                || isTransformedServerboundCoordinatePacket(packet)
                || SERVERBOUND_NON_WORLD_COORDINATE_PACKETS.contains(packet.getClass().getSimpleName())) {
            return false;
        }
        return describe(packet).isPresent();
    }

    static Optional<String> describe(Packet packet) {
        if (packet == null) return Optional.empty();

        if (packet.getClass().getSimpleName().equals("ServerboundInteractPacket")) {
            Optional<String> relative = describeVector(invoke(packet, "getLocation"));
            if (relative.isPresent()) return Optional.of("entityRelativeLocation=" + relative.get());
        }

        Optional<String> direct = describeTriplet(packet);
        if (direct.isPresent()) return direct;

        for (String getter : new String[]{
                "getPosition", "getPos", "getBlockPosition", "getBlockPos",
                "getCenter", "getEntry", "getTarget", "getGlobalPos"
        }) {
            Object value = invoke(packet, getter);
            Optional<String> nested = describeVector(value);
            if (nested.isPresent()) return Optional.of(getter + "=" + nested.get());
        }

        Optional<String> center = describeXZ(packet, "getNewCenterX", "getNewCenterZ");
        if (center.isPresent()) return center;

        return describeFields(packet);
    }

    private static Optional<String> describeTriplet(Object value) {
        Object x = invoke(value, "getX");
        Object y = invoke(value, "getY");
        Object z = invoke(value, "getZ");
        if (number(x) && number(z)) {
            return Optional.of("x=" + x + (number(y) ? ",y=" + y : "") + ",z=" + z);
        }
        return Optional.empty();
    }

    private static Optional<String> describeVector(Object value) {
        if (value == null) return Optional.empty();
        Optional<String> direct = describeTriplet(value);
        if (direct.isPresent()) return direct;
        for (String getter : new String[]{"getPosition", "getPos", "getBlockPosition", "getBlockPos"}) {
            Object nested = invoke(value, getter);
            Optional<String> result = describeTriplet(nested);
            if (result.isPresent()) return Optional.of(getter + "=" + result.get());
        }
        return Optional.empty();
    }

    private static Optional<String> describeXZ(Object value, String xGetter, String zGetter) {
        Object x = invoke(value, xGetter);
        Object z = invoke(value, zGetter);
        if (number(x) && number(z)) return Optional.of("x=" + x + ",z=" + z);
        return Optional.empty();
    }

    private static Optional<String> describeFields(Object value) {
        if (value == null) return Optional.empty();
        Object x = field(value, "x");
        Object y = field(value, "y");
        Object z = field(value, "z");
        if (number(x) && number(z)) {
            return Optional.of("x=" + x + (number(y) ? ",y=" + y : "") + ",z=" + z);
        }
        for (String name : new String[]{"position", "pos", "blockPosition", "blockPos", "center", "target"}) {
            Optional<String> nested = describeVector(field(value, name));
            if (nested.isPresent()) return Optional.of(name + "=" + nested.get());
        }
        return Optional.empty();
    }

    private static Object invoke(Object target, String name) {
        if (target == null) return null;
        try {
            Method method = target.getClass().getMethod(name);
            return method.invoke(target);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static Object field(Object target, String name) {
        if (target == null) return null;
        Class<?> type = target.getClass();
        while (type != null && type != Object.class) {
            try {
                Field field = type.getDeclaredField(name);
                if (!field.trySetAccessible()) return null;
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return null;
            }
        }
        return null;
    }

    private static boolean number(Object value) {
        return value instanceof Number;
    }
}
