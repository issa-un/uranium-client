package app;

import com.sun.tools.attach.AgentInitializationException;
import com.sun.tools.attach.AgentLoadException;
import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

public final class MinecraftProcessService {
    private MinecraftProcessService() {}

    public static List<JavaProcess> listProcesses() {
        return VirtualMachine.list().stream()
                .map(MinecraftProcessService::toProcess)
                .sorted(Comparator.comparing(JavaProcess::executable).thenComparing(JavaProcess::pid))
                .toList();
    }

    /**
     * Runs the agent in the process with a command: "open", "unload", "update" or "status".
     * Returns the agent's result message.
     */
    public static String attach(JavaProcess process, Path agentJar, String command)
            throws AttachNotSupportedException, IOException, AgentLoadException,
            AgentInitializationException {
        if (!Files.isRegularFile(agentJar)) {
            throw new IOException("Agent JAR not found: " + agentJar.toAbsolutePath());
        }

        VirtualMachine virtualMachine = VirtualMachine.attach(process.pid());
        try {
            String absoluteJarPath = agentJar.toAbsolutePath().toString();
            Path statusFile = Path.of(System.getProperty("java.io.tmpdir"),
                    "minecraft-gui-agent-" + process.pid() + ".log");
            Files.deleteIfExists(statusFile);
            try {
                virtualMachine.loadAgent(absoluteJarPath,
                        absoluteJarPath + "|" + statusFile + "|" + command);
            } catch (AgentInitializationException exception) {
                String details = Files.exists(statusFile)
                        ? Files.readAllLines(statusFile).stream().findFirst().orElse("See agent log")
                        : exception.getMessage();
                throw new IOException("Agent initialization failed: " + details
                        + " (details: " + statusFile + ")", exception);
            }
            String result = Files.exists(statusFile) ? String.join("\n", Files.readAllLines(statusFile)) : "";
            return result.isBlank() ? "Done." : result;
        } finally {
            virtualMachine.detach();
        }
    }

    private static JavaProcess toProcess(VirtualMachineDescriptor descriptor) {
        String executable = "Java process";
        try {
            long pid = Long.parseLong(descriptor.id());
            executable = ProcessHandle.of(pid)
                    .flatMap(handle -> handle.info().command())
                    .map(command -> Path.of(command).getFileName().toString())
                    .orElse(executable);
        } catch (RuntimeException ignored) {
        }
        String name = descriptor.displayName().toLowerCase(java.util.Locale.ROOT);
        boolean minecraft = !name.contains("injector") && !descriptor.id().equals(String.valueOf(ProcessHandle.current().pid()))
                && (name.contains("net.minecraft") || name.contains("knot") || name.contains("fabric")
                        || name.contains("minecraft"));
        return new JavaProcess(descriptor.id(), executable, minecraft);
    }
}