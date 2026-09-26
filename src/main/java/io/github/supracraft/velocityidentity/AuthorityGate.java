package io.github.supracraft.velocityidentity;

import java.util.concurrent.atomic.AtomicReference;

public final class AuthorityGate {
    private final AtomicReference<Readiness> readiness =
            new AtomicReference<>(Readiness.STARTING);
    private volatile boolean hasVerifiedPolicy;

    public Readiness readiness() {
        return readiness.get();
    }

    public boolean acceptsLogins() {
        Readiness current = readiness.get();
        return current == Readiness.READY || current == Readiness.DEGRADED;
    }

    public void beginReconciliation() {
        readiness.set(Readiness.STARTING);
    }

    public void markVerified() {
        hasVerifiedPolicy = true;
        readiness.set(Readiness.READY);
    }

    public void markFailure() {
        readiness.set(hasVerifiedPolicy ? Readiness.DEGRADED : Readiness.FAILED);
    }

    public void markUnrecoverableFailure() {
        hasVerifiedPolicy = false;
        readiness.set(Readiness.FAILED);
    }

    public enum Readiness {
        STARTING,
        READY,
        DEGRADED,
        FAILED
    }
}
