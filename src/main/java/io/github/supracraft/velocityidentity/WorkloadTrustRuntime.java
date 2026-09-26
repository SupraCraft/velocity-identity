package io.github.supracraft.velocityidentity;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class WorkloadTrustRuntime {
    private final AtomicReference<WorkloadTrustStore> effective;

    public WorkloadTrustRuntime(WorkloadTrustStore initial) {
        this.effective = new AtomicReference<>(
                Objects.requireNonNull(initial, "initial"));
    }

    public WorkloadTrustStore current() {
        return effective.get();
    }

    public void apply(WorkloadTrustStore desired) {
        effective.set(Objects.requireNonNull(desired, "desired"));
    }
}
