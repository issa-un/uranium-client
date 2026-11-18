package uranium;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import javax.swing.JOptionPane;

/**
 * What Uranium.exe starts: finds the newest Uranium build and runs it. Builds are the
 * minecraft-gui-injector-*.jar files in the app folder (the one packed with the exe) and in
 * the builds folder next to Uranium.exe (the ones the Update tab downloads). Newest by the
 * Build-Time in each jar's manifest. That way an update is just a new jar in builds: the
 * exe never has to change.
 */
public final class Launcher {
    private Launcher() {}

    public static void main(String[] args) throws Exception {
        Path app = Path.of(Launcher.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent();
        Path home = app.getParent();
        List<Path> jars = new ArrayList<>();
        collect(app, jars);
        collect(home.resolve("builds"), jars);
        Path newest = jars.stream().max(Comparator.comparing(Launcher::builtAt)).orElse(null);
        if (newest == null) {
            JOptionPane.showMessageDialog(null, "No Uranium build found in " + app, "Uranium",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }
        System.setProperty("uranium.home", home.toString());
        String mainClass;
        try (JarFile jar = new JarFile(newest.toFile())) {
            mainClass = jar.getManifest().getMainAttributes().getValue(Attributes.Name.MAIN_CLASS);
        }
        URLClassLoader loader = new URLClassLoader(new URL[] {newest.toUri().toURL()},
                Launcher.class.getClassLoader());
        Thread.currentThread().setContextClassLoader(loader);
        Method main = loader.loadClass(mainClass).getMethod("main", String[].class);
        main.invoke(null, (Object) args);
    }

    private static void collect(Path dir, List<Path> into) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(file -> {
                String name = file.getFileName().toString();
                return name.startsWith("minecraft-gui-injector-") && name.endsWith(".jar");
            }).forEach(into::add);
        } catch (Exception ignored) {
            // an unreadable folder has no builds
        }
    }

    /** The jar's Build-Time, or when the file was written if it has none. */
    static Instant builtAt(Path jar) {
        try (JarFile file = new JarFile(jar.toFile())) {
            String time = file.getManifest().getMainAttributes().getValue("Build-Time");
            if (time != null) {
                return Instant.parse(time);
            }
        } catch (Exception ignored) {
            // fall back to the file's time
        }
        try {
            return Files.getLastModifiedTime(jar).toInstant();
        } catch (Exception exception) {
            return Instant.EPOCH;
        }
    }
}
