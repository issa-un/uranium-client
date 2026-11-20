package app;

import java.nio.file.Path;
import javax.swing.SwingUtilities;

public class Main {
    public static void main(String[] args) {
        if ((args.length == 3 || args.length == 4) && "attach".equals(args[0])) {
            String command = args.length == 4 ? args[3] : "open";
            try {
                System.out.println(ProcessService.attach(
                        new JavaProcess(args[1], "java", true), Path.of(args[2]), command));
            } catch (Exception exception) {
                throw new IllegalStateException("Could not run \"" + command + "\" in the game.", exception);
            }
            return;
        }

        if (args.length == 1 && "builder".equals(args[0])) {
            // Builder.exe: updates, repair and clean
            SwingUtilities.invokeLater(() -> new BuilderWindow().show());
            return;
        }

        if (args.length > 0) {
            System.err.println("Usage: attach <pid> <agent-jar> [open|update|unload|status], or builder");
            return;
        }

        SwingUtilities.invokeLater(() -> new InjectorWindow().show());
    }

    static String greeting() {
        return "Hello, Java " + Runtime.version().feature() + "!";
    }
}
