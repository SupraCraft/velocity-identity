package io.github.supracraft.velocityidentity;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class PolicyRuntime {
    private final AtomicReference<AdmissionPolicy> effective;

    public PolicyRuntime(AdmissionPolicy initialPolicy) {
        this.effective = new AtomicReference<>(Objects.requireNonNull(initialPolicy, "initialPolicy"));
    }

    public AdmissionPolicy current() {
        return effective.get();
    }

    public ApplyReceipt apply(PolicyPlan plan, EnvironmentObservation currentObservation) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(currentObservation, "currentObservation");

        AdmissionPolicy before = effective.get();

        if (!plan.applicable()) {
            return receipt(ApplyReceipt.ApplyStatus.BLOCKED, plan, currentObservation, before, before,
                    "Plan contains blockers.");
        }
        if (!plan.observationFingerprint().equals(currentObservation.fingerprint())) {
            return receipt(ApplyReceipt.ApplyStatus.STALE, plan, currentObservation, before, before,
                    "Runtime observation changed after planning.");
        }
        if (before.fingerprint().equals(plan.desiredPolicy().fingerprint())) {
            return receipt(ApplyReceipt.ApplyStatus.NOOP, plan, currentObservation, before, before,
                    "Desired policy is already effective.");
        }
        if (!plan.beforePolicyFingerprint().equals(before.fingerprint())) {
            return receipt(ApplyReceipt.ApplyStatus.STALE, plan, currentObservation, before, before,
                    "Effective policy changed after planning.");
        }
        if (!effective.compareAndSet(before, plan.desiredPolicy())) {
            AdmissionPolicy now = effective.get();
            return receipt(ApplyReceipt.ApplyStatus.STALE, plan, currentObservation, now, now,
                    "Effective policy changed concurrently.");
        }
        return receipt(ApplyReceipt.ApplyStatus.APPLIED, plan, currentObservation, before,
                plan.desiredPolicy(), "Desired policy atomically applied.");
    }

    public ApplyReceipt rollback(ApplyReceipt applied, EnvironmentObservation currentObservation) {
        Objects.requireNonNull(applied, "applied");
        Objects.requireNonNull(currentObservation, "currentObservation");
        AdmissionPolicy current = effective.get();

        if (applied.status() != ApplyReceipt.ApplyStatus.APPLIED) {
            return new ApplyReceipt(
                    ApplyReceipt.ApplyStatus.BLOCKED,
                    applied.planFingerprint(),
                    currentObservation.fingerprint(),
                    current,
                    current,
                    "Only a successful APPLIED receipt can be rolled back.");
        }
        if (!current.fingerprint().equals(applied.afterPolicy().fingerprint())) {
            return new ApplyReceipt(
                    ApplyReceipt.ApplyStatus.STALE,
                    applied.planFingerprint(),
                    currentObservation.fingerprint(),
                    current,
                    current,
                    "Effective policy no longer matches the applied receipt.");
        }
        if (!effective.compareAndSet(current, applied.beforePolicy())) {
            AdmissionPolicy now = effective.get();
            return new ApplyReceipt(
                    ApplyReceipt.ApplyStatus.STALE,
                    applied.planFingerprint(),
                    currentObservation.fingerprint(),
                    now,
                    now,
                    "Effective policy changed concurrently during rollback.");
        }
        return new ApplyReceipt(
                ApplyReceipt.ApplyStatus.ROLLED_BACK,
                applied.planFingerprint(),
                currentObservation.fingerprint(),
                current,
                applied.beforePolicy(),
                "Previous effective policy restored.");
    }

    private static ApplyReceipt receipt(
            ApplyReceipt.ApplyStatus status,
            PolicyPlan plan,
            EnvironmentObservation observation,
            AdmissionPolicy before,
            AdmissionPolicy after,
            String detail) {
        return new ApplyReceipt(
                status,
                plan.planFingerprint(),
                observation.fingerprint(),
                before,
                after,
                detail);
    }
}
