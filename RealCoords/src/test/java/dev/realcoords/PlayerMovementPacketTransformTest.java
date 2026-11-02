package dev.realcoords;

import org.cloudburstmc.math.vector.Vector3d;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PositionElement;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundAddEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundTeleportEntityPacket;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import java.util.UUID;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlayerMovementPacketTransformTest {
    private static final CoordinateTransform ORIGIN = new CoordinateTransform(25_000_000, -17_000_000);

    @Test
    void convertsAbsoluteJavaTeleportToLocalCoordinates() {
        ClientboundPlayerPositionPacket input = new ClientboundPlayerPositionPacket(
                7, Vector3d.from(25_000_012.5, 80, -17_000_007.25),
                Vector3d.from(0.1, 0.2, 0.3), 90, 15, List.of()
        );

        Packet output = PlayerMovementPacketTransform.toLocal(input, ORIGIN);

        assertInstanceOf(ClientboundPlayerPositionPacket.class, output);
        ClientboundPlayerPositionPacket result = (ClientboundPlayerPositionPacket) output;
        assertEquals(12.5, result.getPosition().getX());
        assertEquals(80, result.getPosition().getY());
        assertEquals(-7.25, result.getPosition().getZ());
        assertEquals(input.getDeltaMovement(), result.getDeltaMovement());
        assertEquals(input.getId(), result.getId());
        assertEquals(input.getRelatives(), result.getRelatives());
    }

    @Test
    void preservesRelativeTeleportAxesAndConvertsOnlyAbsoluteAxis() {
        ClientboundPlayerPositionPacket input = new ClientboundPlayerPositionPacket(
                8, Vector3d.from(2.5, 4, -17_000_003.0),
                Vector3d.from(0, 0, 0), 0, 0,
                List.of(PositionElement.X, PositionElement.Y)
        );

        ClientboundPlayerPositionPacket result = (ClientboundPlayerPositionPacket)
                PlayerMovementPacketTransform.toLocal(input, ORIGIN);

        assertEquals(2.5, result.getPosition().getX());
        assertEquals(4, result.getPosition().getY());
        assertEquals(-3.0, result.getPosition().getZ());
        assertEquals(input.getRelatives(), result.getRelatives());
    }

    @Test
    void convertsEntitySpawnAbsolutePositionToLocalCoordinates() {
        ClientboundAddEntityPacket input = new ClientboundAddEntityPacket(
                21, UUID.randomUUID(), EntityType.ZOMBIE,
                25_000_010.5, 64, -17_000_004.25, 90, 0, 90
        );

        ClientboundAddEntityPacket result = (ClientboundAddEntityPacket)
                PlayerMovementPacketTransform.toLocal(input, ORIGIN);

        assertEquals(10.5, result.getX());
        assertEquals(64, result.getY());
        assertEquals(-4.25, result.getZ());
        assertEquals(input.getMovement(), result.getMovement());
        assertEquals(input.getEntityId(), result.getEntityId());
    }

    @Test
    void convertsEntityTeleportAbsoluteAxesAndPreservesRelativeAxes() {
        ClientboundTeleportEntityPacket input = new ClientboundTeleportEntityPacket(
                22, Vector3d.from(3.5, 5, -17_000_002.0),
                Vector3d.from(0.1, 0.2, 0.3), 90, 15,
                List.of(PositionElement.X), true
        );

        ClientboundTeleportEntityPacket result = (ClientboundTeleportEntityPacket)
                PlayerMovementPacketTransform.toLocal(input, ORIGIN);

        assertEquals(3.5, result.getPosition().getX());
        assertEquals(5, result.getPosition().getY());
        assertEquals(-2.0, result.getPosition().getZ());
        assertEquals(input.getDeltaMovement(), result.getDeltaMovement());
        assertEquals(input.getRelatives(), result.getRelatives());
        assertTrue(result.isOnGround());
    }

    @Test
    void restoresAbsoluteBedrockMovementPositionForJava() {
        ServerboundMovePlayerPosPacket input =
                new ServerboundMovePlayerPosPacket(true, false, 12.5, 70, -7.25);

        ServerboundMovePlayerPosPacket result = (ServerboundMovePlayerPosPacket)
                PlayerMovementPacketTransform.toReal(input, ORIGIN);

        assertEquals(25_000_012.5, result.getX());
        assertEquals(70, result.getY());
        assertEquals(-17_000_007.25, result.getZ());
        assertTrue(result.isOnGround());
        assertFalse(result.isHorizontalCollision());
    }

    @Test
    void restoresPositionAndRotationPacketWithoutChangingRotation() {
        ServerboundMovePlayerPosRotPacket input =
                new ServerboundMovePlayerPosRotPacket(false, true, 1.25, 90, -2.5, 135, -20);

        ServerboundMovePlayerPosRotPacket result = (ServerboundMovePlayerPosRotPacket)
                PlayerMovementPacketTransform.toReal(input, ORIGIN);

        assertEquals(25_000_001.25, result.getX());
        assertEquals(90, result.getY());
        assertEquals(-17_000_002.5, result.getZ());
        assertEquals(135, result.getYaw());
        assertEquals(-20, result.getPitch());
        assertFalse(result.isOnGround());
        assertTrue(result.isHorizontalCollision());
    }

    @Test
    void restoresTeleportAcknowledgementCoordinatesForJava() {
        ServerboundAcceptTeleportationPacket input =
                new ServerboundAcceptTeleportationPacket(12, 12.5, 70, -7.25, 45, 10);

        ServerboundAcceptTeleportationPacket result = (ServerboundAcceptTeleportationPacket)
                PlayerMovementPacketTransform.toReal(input, ORIGIN);

        assertEquals(12, result.getId());
        assertEquals(25_000_012.5, result.getX());
        assertEquals(70, result.getY());
        assertEquals(-17_000_007.25, result.getZ());
        assertEquals(45, result.getYRot());
        assertEquals(10, result.getXRot());
    }

    @Test
    void leavesUnrelatedAndRelativeMovementPacketsUntouched() {
        ServerboundMovePlayerPosPacket input =
                new ServerboundMovePlayerPosPacket(true, true, 1, 2, 3);

        assertSame(input, PlayerMovementPacketTransform.toReal(input, ORIGIN));
        assertSame(input, PlayerMovementPacketTransform.toLocal(input, ORIGIN));
    }
}
