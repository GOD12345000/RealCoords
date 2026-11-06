package dev.realcoords;

import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundPlayerLoadedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SyntheticResetGateTest {
    @Test
    void suppressesOnlyPlayerLoadedAndTheMatchingReservedTeleportAck() {
        SyntheticResetGate gate = new SyntheticResetGate();
        int reservedId = Integer.MAX_VALUE;
        assertTrue(gate.begin(reservedId));

        assertTrue(gate.shouldSuppress(ServerboundPlayerLoadedPacket.INSTANCE));
        assertTrue(gate.active());

        assertFalse(gate.shouldSuppress(new ServerboundAcceptTeleportationPacket(
                reservedId - 1, 0, 64, 0, 0, 0)));
        assertTrue(gate.shouldSuppress(new ServerboundAcceptTeleportationPacket(
                reservedId, 0, 64, 0, 0, 0)));
        assertFalse(gate.active());
    }

    @Test
    void acceptsEitherResponseOrderAndDoesNotSuppressNormalPacketsAfterCompletion() {
        SyntheticResetGate gate = new SyntheticResetGate();
        assertTrue(gate.begin(123456));

        assertTrue(gate.shouldSuppress(new ServerboundAcceptTeleportationPacket(
                123456, 1, 70, 2, 90, 0)));
        assertTrue(gate.active());
        assertTrue(gate.shouldSuppress(ServerboundPlayerLoadedPacket.INSTANCE));
        assertFalse(gate.active());
        assertFalse(gate.shouldSuppress(ServerboundPlayerLoadedPacket.INSTANCE));
    }

    @Test
    void abortClearsAllSuppressionState() {
        SyntheticResetGate gate = new SyntheticResetGate();
        assertTrue(gate.begin(77));
        gate.abort();

        assertFalse(gate.active());
        assertFalse(gate.shouldSuppress(ServerboundAcceptTeleportationPacket.class.cast(
                new ServerboundAcceptTeleportationPacket(77, 0, 0, 0, 0, 0))));
        assertFalse(gate.shouldSuppress(ServerboundPlayerLoadedPacket.INSTANCE));
    }

    @Test
    void doesNotAllowOverlappingResetTransactions() {
        SyntheticResetGate gate = new SyntheticResetGate();
        assertTrue(gate.begin(10));
        assertFalse(gate.begin(11));
        gate.abort();
        assertTrue(gate.begin(11));
    }
}
