package io.github.supracraft.velocityidentity;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class RuntimeEvidenceWriter {
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final Path stateDirectory;

    public RuntimeEvidenceWriter(Path dataDirectory) {
        this.stateDirectory = dataDirectory.resolve("state");
    }

    public void writeObservation(EnvironmentObservation observation) throws IOException {
        write("observation.json", observation);
    }

    public void writePlan(PolicyPlan plan) throws IOException {
        write("plan.json", plan);
    }

    public void writeApply(ApplyReceipt receipt) throws IOException {
        write("apply.json", receipt);
    }

    public void clearRollback() throws IOException {
        Files.createDirectories(stateDirectory);
        Files.deleteIfExists(stateDirectory.resolve("rollback.json"));
    }

    public void writeRollback(ApplyReceipt receipt) throws IOException {
        write("rollback.json", receipt);
    }

    public void writeVerification(VerificationReport report) throws IOException {
        write("verification.json", report);
    }

    public void writeWorkloadTrust(
            WorkloadTrustSummary summary) throws IOException {
        write("workload-trust.json", summary);
    }

    private void write(String name, Object value) throws IOException {
        Files.createDirectories(stateDirectory);
        Path target = stateDirectory.resolve(name);
        Path temp = stateDirectory.resolve(name + ".tmp");
        Files.writeString(temp, gson.toJson(value) + System.lineSeparator(), StandardCharsets.UTF_8);
        try {
            Files.move(temp, target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
