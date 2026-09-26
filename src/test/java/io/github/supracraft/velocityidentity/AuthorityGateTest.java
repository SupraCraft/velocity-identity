package io.github.supracraft.velocityidentity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthorityGateTest {
    @Test
    void firstFailureRemainsFailClosed() {
        AuthorityGate gate = new AuthorityGate();

        assertFalse(gate.acceptsLogins());
        assertEquals(AuthorityGate.Readiness.STARTING, gate.readiness());

        gate.markFailure();

        assertFalse(gate.acceptsLogins());
        assertEquals(AuthorityGate.Readiness.FAILED, gate.readiness());
    }

    @Test
    void laterFailureRetainsLastKnownGoodPolicy() {
        AuthorityGate gate = new AuthorityGate();
        gate.markVerified();
        assertTrue(gate.acceptsLogins());
        assertEquals(AuthorityGate.Readiness.READY, gate.readiness());

        gate.markFailure();

        assertTrue(gate.acceptsLogins());
        assertEquals(AuthorityGate.Readiness.DEGRADED, gate.readiness());
    }
}
