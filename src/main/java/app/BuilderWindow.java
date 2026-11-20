package app;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.geom.Ellipse2D;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;

/**
 * Builder.exe, for players: keeps Uranium up to date and fixes things. Updates: the build
 * you have, the latest on GitHub with what's new, and Download & install (into the builds
 * folder, where Uranium.exe picks the newest up). Repair & clean: checks Minecraft 26.1.2,
 * Fabric Loader and Fabric API are installed, deletes old builds, resets your settings or
 * your hand (humans.txt), and opens the logs, config and Uranium folders. Nothing is
 * compiled here: updates are ready-made builds from GitHub releases.
 */
final class BuilderWindow {
    private static final Color RED = new Color(0xFF5252);
    private final JFrame frame = new JFrame("Uranium Builder");
    private final Path home = home();
    private final Path minecraftDir = Path.of(System.getenv().getOrDefault("APPDATA", ""), ".minecraft");
    private final JLabel installed = InjectorWindow.label(" ", Font.PLAIN, 13, InjectorWindow.SOFT);
    private final JLabel latestLine = InjectorWindow.label("Checking GitHub...", Font.BOLD, 16, InjectorWindow.TEXT);
    private final JTextArea notes = new JTextArea();
    private final JLabel status = InjectorWindow.label(" ", Font.PLAIN, 13, InjectorWindow.SOFT);
    private final InjectorWindow.Pill check = new InjectorWindow.Pill("Check", "", false);
    private final InjectorWindow.Pill install = new InjectorWindow.Pill("Download & install", "", true);
    private final JPanel checks = new JPanel();
    private Updater.Release release;

    BuilderWindow() {
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setMinimumSize(new Dimension(760, 560));
        frame.setSize(880, 620);
        frame.setLocationByPlatform(true);
        frame.setIconImages(List.of(InjectorWindow.mark(16), InjectorWindow.mark(32), InjectorWindow.mark(64)));

        JPanel root = new InjectorWindow.Backdrop(new BorderLayout());
        root.add(topBar(), BorderLayout.NORTH);
        JPanel columns = new JPanel(new GridLayout(1, 2, 16, 0));
        columns.setOpaque(false);
        columns.setBorder(BorderFactory.createEmptyBorder(18, 20, 6, 20));
        columns.add(updates());
        columns.add(repair());
        root.add(columns, BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        footer.setBorder(BorderFactory.createEmptyBorder(6, 20, 12, 20));
        status.setHorizontalAlignment(SwingConstants.LEFT);
        footer.add(status, BorderLayout.CENTER);
        root.add(footer, BorderLayout.SOUTH);
        frame.setContentPane(root);

        check.addActionListener(event -> checkForUpdate());
        install.addActionListener(event -> downloadUpdate());
        install.setVisible(false);
    }

    void show() {
        frame.setVisible(true);
        showInstalled();
        checkSetup();
        checkForUpdate();
    }

    // ---- layout ----

    private JComponent topBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0)) {
            @Override
            protected void paintComponent(Graphics graphics) {
                graphics.setColor(InjectorWindow.BAR);
                graphics.fillRect(0, 0, getWidth(), getHeight());
                graphics.setColor(InjectorWindow.GLOW_DIM);
                graphics.fillRect(0, getHeight() - 1, getWidth(), 1);
            }
        };
        bar.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 14));
        bar.add(new JLabel(new javax.swing.ImageIcon(InjectorWindow.mark(30))));
        JLabel name = InjectorWindow.label("URANIUM", Font.BOLD, 21, InjectorWindow.TEXT);
        name.setFont(name.getFont().deriveFont(java.util.Map.of(java.awt.font.TextAttribute.TRACKING, 0.12)));
        bar.add(name);
        JLabel tagline = InjectorWindow.label("builder", Font.PLAIN, 13, InjectorWindow.FAINT);
        tagline.setBorder(BorderFactory.createEmptyBorder(5, 0, 0, 0));
        bar.add(tagline);
        return bar;
    }

    private JComponent updates() {
        JPanel card = new InjectorWindow.Card(new BorderLayout(0, 10));
        card.setBorder(BorderFactory.createEmptyBorder(16, 18, 16, 18));
        JPanel head = new JPanel(new BorderLayout(0, 4));
        head.setOpaque(false);
        head.add(heading("Updates"), BorderLayout.NORTH);
        latestLine.setHorizontalAlignment(SwingConstants.LEFT);
        installed.setHorizontalAlignment(SwingConstants.LEFT);
        head.add(latestLine, BorderLayout.CENTER);
        head.add(installed, BorderLayout.SOUTH);
        card.add(head, BorderLayout.NORTH);
        notes.setEditable(false);
        notes.setLineWrap(true);
        notes.setWrapStyleWord(true);
        notes.setOpaque(false);
        notes.setForeground(InjectorWindow.TEXT);
        notes.setFont(notes.getFont().deriveFont(13F));
        JScrollPane scroll = new JScrollPane(notes);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setBorder(BorderFactory.createMatteBorder(1, 0, 1, 0, InjectorWindow.CARD_EDGE));
        card.add(scroll, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        check.setPreferredSize(new Dimension(100, 42));
        install.setPreferredSize(new Dimension(190, 42));
        buttons.add(check);
        buttons.add(install);
        card.add(buttons, BorderLayout.SOUTH);
        return card;
    }

    private JComponent repair() {
        JPanel card = new InjectorWindow.Card(new BorderLayout(0, 10));
        card.setBorder(BorderFactory.createEmptyBorder(16, 18, 16, 18));
        card.add(heading("Repair & clean"), BorderLayout.NORTH);
        JPanel middle = new JPanel(new BorderLayout(0, 12));
        middle.setOpaque(false);
        checks.setOpaque(false);
        checks.setLayout(new BoxLayout(checks, BoxLayout.Y_AXIS));
        middle.add(checks, BorderLayout.NORTH);
        JPanel tools = new JPanel(new GridLayout(3, 2, 8, 8));
        tools.setOpaque(false);
        tools.add(tool("Clean old builds", "Keep only the newest", this::cleanBuilds));
        tools.add(tool("Reset settings", "Every module back to default", this::resetSettings));
        tools.add(tool("Reset my hand", "Delete humans.txt", this::resetHand));
        tools.add(tool("Open logs", "Uranium's and the game's", () -> open(minecraftDir.resolve("logs"))));
        tools.add(tool("Open config", "Your saved settings", () -> open(minecraftDir.resolve("config"))));
        tools.add(tool("Open Uranium folder", "Builds, humans.txt", () -> open(home)));
        middle.add(tools, BorderLayout.CENTER);
        card.add(middle, BorderLayout.CENTER);
        return card;
    }

    private static JLabel heading(String text) {
        JLabel label = InjectorWindow.label(text, Font.BOLD, 13, InjectorWindow.GLOW);
        label.setHorizontalAlignment(SwingConstants.LEFT);
        label.setFont(label.getFont().deriveFont(java.util.Map.of(java.awt.font.TextAttribute.TRACKING, 0.08)));
        label.setText(text.toUpperCase(java.util.Locale.ROOT));
        return label;
    }

    private static InjectorWindow.Pill tool(String title, String caption, Runnable action) {
        InjectorWindow.Pill pill = new InjectorWindow.Pill(title, caption, false);
        pill.setFont(pill.getFont().deriveFont(Font.BOLD, 14F));
        pill.setPreferredSize(new Dimension(170, 74));
        pill.addActionListener(event -> action.run());
        return pill;
    }

    /** One line of the setup check: a coloured dot and what it found. */
    private static JComponent checkRow(boolean ok, boolean warn, String text) {
        Color dot = ok ? InjectorWindow.GLOW : warn ? InjectorWindow.AMBER : RED;
        JLabel label = new JLabel(text) {
            @Override
            protected void paintComponent(Graphics graphics) {
                Graphics2D g = InjectorWindow.smooth(graphics);
                g.setColor(dot);
                g.fill(new Ellipse2D.Double(2, getHeight() / 2.0 - 4, 8, 8));
                g.dispose();
                super.paintComponent(graphics);
            }
        };
        label.setFont(label.getFont().deriveFont(Font.PLAIN, 13F));
        label.setForeground(ok ? InjectorWindow.TEXT : InjectorWindow.SOFT);
        label.setBorder(BorderFactory.createEmptyBorder(3, 18, 3, 0));
        label.setAlignmentX(0);
        return label;
    }

    // ---- what it does ----

    /** Where Uranium.exe is (the launcher says), else up from this jar to Uranium.exe or Uranium.bat. */
    private static Path home() {
        String home = System.getProperty("uranium.home");
        if (home != null) {
            return Path.of(home);
        }
        Path jar = InjectorWindow.locateAgentJar().toAbsolutePath();
        for (Path dir = jar.getParent(); dir != null; dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve("Uranium.exe")) || Files.isRegularFile(dir.resolve("Uranium.bat"))) {
                return dir;
            }
        }
        return jar.getParent();
    }

    /** Every Uranium build there is: the one packed with the exe (app) and downloaded ones (builds). */
    private List<Path> builds() {
        List<Path> jars = new ArrayList<>();
        for (Path dir : List.of(home.resolve("app"), home.resolve("builds"), home.resolve("build").resolve("libs"))) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(file -> Updater.isBuild(file.getFileName().toString())).forEach(jars::add);
            } catch (IOException ignored) {
                // an unreadable folder has no builds
            }
        }
        return jars;
    }

    private Path newest() {
        return builds().stream().max(Comparator.comparing(Updater::builtAt)).orElse(null);
    }

    private void showInstalled() {
        Path newest = newest();
        installed.setText(newest == null ? "No Uranium build found here"
                : "You have " + tag(newest) + ", made " + InjectorWindow.day(Updater.builtAt(newest)));
    }

    private static String tag(Path jar) {
        String name = jar.getFileName().toString();
        return "build " + name.substring(name.lastIndexOf('-') + 1).replace(".jar", "");
    }

    /** Minecraft 26.1.2 (or 26.2/26.3), Fabric Loader and Fabric API installed; Minecraft running. */
    private void checkSetup() {
        checks.removeAll();
        Path versions = minecraftDir.resolve("versions");
        List<String> names = list(versions);
        boolean minecraft = names.stream().anyMatch(n -> n.equals("26.1.2") || n.equals("26.2") || n.equals("26.3"));
        boolean fabric = names.stream().anyMatch(n -> n.startsWith("fabric-loader-"));
        boolean api = list(minecraftDir.resolve("mods")).stream().anyMatch(n -> n.startsWith("fabric-api-"));
        checks.add(checkRow(Files.isDirectory(minecraftDir), false,
                Files.isDirectory(minecraftDir) ? "Minecraft folder found" : "No .minecraft folder: install Minecraft"));
        checks.add(checkRow(minecraft, false, minecraft ? "Minecraft 26.1.2 / 26.2 / 26.3 installed"
                : "Minecraft 26.1.2 not installed: play it once from the launcher"));
        checks.add(checkRow(fabric, false, fabric ? "Fabric Loader installed" : "Fabric Loader missing: install it from fabricmc.net"));
        checks.add(checkRow(api, false, api ? "Fabric API in your mods folder" : "Fabric API missing from your mods folder"));
        checks.add(checkRow(newest() != null, false, newest() != null ? "Uranium build ready" : "No Uranium build: download one"));
        checks.revalidate();
        checks.repaint();
        new SwingWorker<Boolean, Void>() {
            @Override
            protected Boolean doInBackground() {
                return ProcessService.listProcesses().stream().anyMatch(JavaProcess::minecraft);
            }

            @Override
            protected void done() {
                try {
                    boolean running = get();
                    checks.add(checkRow(running, true, running ? "Minecraft is running" : "Minecraft isn't running right now"));
                    checks.revalidate();
                    checks.repaint();
                } catch (InterruptedException | ExecutionException ignored) {
                    // no line then
                }
            }
        }.execute();
    }

    private static List<String> list(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(file -> file.getFileName().toString()).toList();
        } catch (IOException exception) {
            return List.of();
        }
    }

    private void checkForUpdate() {
        check.setEnabled(false);
        latestLine.setText("Checking GitHub...");
        latestLine.setForeground(InjectorWindow.TEXT);
        new SwingWorker<Object, Void>() {
            @Override
            protected Object doInBackground() {
                try {
                    return Updater.latest();
                } catch (Exception exception) {
                    return exception.getMessage() == null ? "Couldn't reach GitHub" : exception.getMessage();
                }
            }

            @Override
            protected void done() {
                check.setEnabled(true);
                Object result;
                try {
                    result = get();
                } catch (InterruptedException | ExecutionException exception) {
                    return;
                }
                if (!(result instanceof Updater.Release latest)) {
                    latestLine.setText("Couldn't check: " + result);
                    latestLine.setForeground(InjectorWindow.SOFT);
                    return;
                }
                release = latest;
                notes.setText(latest.notes().isBlank() ? "(no notes for this release)" : latest.notes());
                notes.setCaretPosition(0);
                Path newest = newest();
                boolean have = latest.jarName() != null && builds().stream()
                        .anyMatch(jar -> jar.getFileName().toString().equals(latest.jarName()));
                boolean newer = !have && latest.jarName() != null
                        && (newest == null || latest.published().isAfter(Updater.builtAt(newest)));
                install.setVisible(newer);
                install.setEnabled(true);
                if (have) {
                    latestLine.setText("You're up to date");
                    latestLine.setForeground(InjectorWindow.GLOW);
                } else if (newer) {
                    latestLine.setText("New: " + (latest.name().isBlank() ? latest.tag() : latest.name()));
                    latestLine.setForeground(InjectorWindow.AMBER);
                } else {
                    latestLine.setText("Nothing newer on GitHub (" + latest.tag() + ")");
                    latestLine.setForeground(InjectorWindow.SOFT);
                }
            }
        }.execute();
    }

    private void downloadUpdate() {
        if (release == null || release.jarUrl() == null) {
            return;
        }
        install.setEnabled(false);
        new SwingWorker<Path, Long>() {
            @Override
            protected Path doInBackground() throws Exception {
                return Updater.download(release, home.resolve("builds"), bytes -> publish(bytes));
            }

            @Override
            protected void process(List<Long> chunks) {
                long bytes = chunks.get(chunks.size() - 1);
                status.setText(release.jarSize() > 0
                        ? String.format(java.util.Locale.ROOT, "Downloading... %d%%", bytes * 100 / release.jarSize())
                        : String.format(java.util.Locale.ROOT, "Downloading... %d KB", bytes / 1024));
            }

            @Override
            protected void done() {
                try {
                    Path jar = get();
                    status.setText("Installed " + tag(jar) + ". Open Uranium.exe to use it.");
                    status.setForeground(InjectorWindow.GLOW);
                    install.setVisible(false);
                    latestLine.setText("You're up to date");
                    latestLine.setForeground(InjectorWindow.GLOW);
                    showInstalled();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException exception) {
                    status.setText("Download failed: " + exception.getCause().getMessage());
                    install.setEnabled(true);
                }
            }
        }.execute();
    }

    /** Deletes every downloaded build but the newest (the one packed with the exe stays). */
    private void cleanBuilds() {
        Path keep = newest();
        List<Path> old = builds().stream()
                .filter(jar -> !jar.equals(keep) && jar.getParent().getFileName().toString().equals("builds"))
                .toList();
        if (old.isEmpty()) {
            status.setText("No old builds to clean");
            return;
        }
        int deleted = 0;
        long bytes = 0;
        for (Path jar : old) {
            try {
                long size = Files.size(jar);
                Files.delete(jar);
                deleted++;
                bytes += size;
            } catch (IOException ignored) {
                // in use by a running game: it goes next time
            }
        }
        status.setText(String.format(java.util.Locale.ROOT, "Cleaned %d old build%s (%.1f MB)%s", deleted,
                deleted == 1 ? "" : "s", bytes / 1048576.0,
                deleted < old.size() ? ", " + (old.size() - deleted) + " in use by the game (close it first)" : ""));
        showInstalled();
    }

    private void resetSettings() {
        Path config = minecraftDir.resolve("config");
        // uranium.properties, and spear-injector.properties from before the rename (it would be
        // copied back in otherwise).
        List<Path> files = new ArrayList<>();
        for (String name : List.of("uranium.properties", "spear-injector.properties")) {
            if (Files.isRegularFile(config.resolve(name))) {
                files.add(config.resolve(name));
            }
        }
        if (files.isEmpty()) {
            status.setText("No settings saved yet: nothing to reset");
            return;
        }
        if (!confirm("Reset every Uranium setting to default?\nClose Minecraft first, or the game saves them back.")) {
            return;
        }
        try {
            for (Path settings : files) {
                Files.move(settings, settings.resolveSibling(settings.getFileName() + ".old"),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            status.setText("Settings reset (the old ones are kept as uranium.properties.old)");
        } catch (IOException exception) {
            status.setText("Couldn't reset: " + exception.getMessage());
        }
    }

    private void resetHand() {
        Path humans = home.resolve("humans.txt");
        if (!Files.isRegularFile(humans)) {
            status.setText("No humans.txt: you're on an average hand already");
            return;
        }
        if (!confirm("Delete humans.txt and go back to an average hand?\nTake the mouse test again in the game (Play Style).")) {
            return;
        }
        try {
            Files.delete(humans);
            status.setText("humans.txt deleted: an average hand from the next game start");
        } catch (IOException exception) {
            status.setText("Couldn't delete it: " + exception.getMessage());
        }
    }

    private boolean confirm(String question) {
        return JOptionPane.showConfirmDialog(frame, question, "Uranium Builder", JOptionPane.YES_NO_OPTION)
                == JOptionPane.YES_OPTION;
    }

    private void open(Path dir) {
        try {
            Files.createDirectories(dir);
            Desktop.getDesktop().open(dir.toFile());
        } catch (IOException | UnsupportedOperationException exception) {
            status.setText("Couldn't open " + dir);
        }
    }
}
