package app;

import java.nio.file.Path;
import javax.swing.SwingUtilities;

public class Main {
    public static void main(String[] args) {
        if ((args.length == 3 || args.length == 4) && "attach".equals(args[0])) {
            String command = args.length == 4 ? args[3] : "open";
            try {
                System.out.println(MinecraftProcessService.attach(
                        new JavaProcess(args[1], "java", true), Path.of(args[2]), command));
            } catch (Exception exception) {
                throw new IllegalStateException("Could not run \"" + command + "\" in the game.", exception);
            }
            return;
        }

        if (args.length > 0) {
            System.err.println("Usage: attach <pid> <agent-jar> [open|update|unload|status]");
            return;
        }

        SwingUtilities.invokeLater(() -> new ProcessPickerWindow().show());
    }

    static String greeting() {
        return "Hello, Java " + Runtime.version().feature() + "!";
    }
}
