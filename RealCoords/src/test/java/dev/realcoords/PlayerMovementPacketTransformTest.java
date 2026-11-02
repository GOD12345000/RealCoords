package dev.realcoords;

import org.cloudburstmc.math.vector.Vector3d;
import org.cloudburstmc.math.vector.Vector3i;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftPacket;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PositionElement;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerAction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundAddEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundTeleportEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundEntityPositionSyncPacket;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockChangeEntry;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundBlockUpdatePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundSectionBlocksUpdatePacket;
import java.util.UUID;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundMoveVehiclePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPlayerActionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemOnPacket;
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

        MinecraftPacket output = PlayerMovementPacketTransform.toLocal(input, ORIGIN);

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
    void convertsEntityPositionSyncAbsoluteCoordinatesToLocalSpace() {
        ClientboundEntityPositionSyncPacket input = new ClientboundEntityPositionSyncPacket(
                30, false, Vector3d.from(25_000_005.5, 72, -17_000_006.25),
                null, 90, 15, true
        );

        ClientboundEntityPositionSyncPacket result = (ClientboundEntityPositionSyncPacket)
                PlayerMovementPacketTransform.toLocal(input, ORIGIN);

        assertEquals(Vector3d.from(5.5, 72, -6.25), result.getEndPosition());
        assertEquals(input.getId(), result.getId());
        assertEquals(input.getYRot(), result.getYRot());
        assertEquals(input.getXRot(), result.getXRot());
        assertTrue(result.isOnGround());
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
    void convertsInboundBlockAndSectionUpdatePositionsToLocalCoordinates() {
        ClientboundBlockUpdatePacket blockUpdate = new ClientboundBlockUpdatePacket(
                new BlockChangeEntry(Vector3i.from(25_000_012, 70, -17_000_007), 42)
        );
        ClientboundBlockUpdatePacket localBlockUpdate = (ClientboundBlockUpdatePacket)
                PlayerMovementPacketTransform.toLocal(blockUpdate, ORIGIN);
        assertEquals(Vector3i.from(12, 70, -7), localBlockUpdate.getEntry().getPosition());
        assertEquals(42, localBlockUpdate.getEntry().getBlock());

        ClientboundSectionBlocksUpdatePacket sectionUpdate = new ClientboundSectionBlocksUpdatePacket(
                1_562_500, 4, -1_062_500,
                new BlockChangeEntry(Vector3i.from(25_000_001, 65, -17_000_002), 99)
        );
        ClientboundSectionBlocksUpdatePacket localSectionUpdate = (ClientboundSectionBlocksUpdatePacket)
                PlayerMovementPacketTransform.toLocal(sectionUpdate, ORIGIN);
        assertEquals(0, localSectionUpdate.getChunkX());
        assertEquals(0, localSectionUpdate.getChunkZ());
        assertEquals(Vector3i.from(1, 65, -2), localSectionUpdate.getEntries()[0].getPosition());
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
    void restoresVehicleAbsolutePositionForJava() {
        ServerboundMoveVehiclePacket input = new ServerboundMoveVehiclePacket(
                Vector3d.from(2.5, 70, -3.5), 90, 10, true
        );

        ServerboundMoveVehiclePacket result = (ServerboundMoveVehiclePacket)
                PlayerMovementPacketTransform.toReal(input, ORIGIN);

        assertEquals(25_000_002.5, result.getPosition().getX());
        assertEquals(70, result.getPosition().getY());
        assertEquals(-17_000_003.5, result.getPosition().getZ());
        assertEquals(input.getYRot(), result.getYRot());
        assertEquals(input.getXRot(), result.getXRot());
        assertTrue(result.isOnGround());
    }

    @Test
    void restoresBlockInteractionCoordinatesForJava() {
        ServerboundUseItemOnPacket useItem = new ServerboundUseItemOnPacket(
                Vector3i.from(12, 64, -8), Direction.UP, Hand.MAIN_HAND,
                0.5f, 1.0f, 0.25f, false, false, 3
        );
        ServerboundUseItemOnPacket realUseItem = (ServerboundUseItemOnPacket)
                PlayerMovementPacketTransform.toReal(useItem, ORIGIN);
        assertEquals(Vector3i.from(25_000_012, 64, -17_000_008), realUseItem.getPosition());

        ServerboundPlayerActionPacket action = new ServerboundPlayerActionPacket(
                PlayerAction.START_DIGGING, Vector3i.from(-2, 70, 4), Direction.NORTH, 5
        );
        ServerboundPlayerActionPacket realAction = (ServerboundPlayerActionPacket)
                PlayerMovementPacketTransform.toReal(action, ORIGIN);
        assertEquals(Vector3i.from(24_999_998, 70, -16_999_996), realAction.getPosition());
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
    void restoresAbsoluteMovementCoordinatesAndLeavesInboundDirectionUntouched() {
        ServerboundMovePlayerPosPacket input =
                new ServerboundMovePlayerPosPacket(true, true, 1, 2, 3);

        ServerboundMovePlayerPosPacket real =
                (ServerboundMovePlayerPosPacket) PlayerMovementPacketTransform.toReal(input, ORIGIN);
        assertEquals(25_000_001, real.getX());
        assertEquals(2, real.getY());
        assertEquals(-16_999_997, real.getZ());
        assertTrue(real.isOnGround());
        assertTrue(real.isHorizontalCollision());

        assertSame(input, PlayerMovementPacketTransform.toLocal(input, ORIGIN));
    }
}
