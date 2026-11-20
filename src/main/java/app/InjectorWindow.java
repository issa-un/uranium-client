package app;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.font.TextAttribute;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import javax.swing.Timer;

/**
 * The Uranium injector window. A dark, glowing one-click loader: the Uranium mark and name
 * on the left of the top bar, three tabs on the right (Inject, Advanced, Manage). Inject has
 * a card that says whether Minecraft is running (a breathing green dot when it is) and one
 * big round Inject button with a slow glow. Advanced lists every Java program as cards,
 * Minecraft marked. Manage has Update, Unload and Status as tiles, and shows the hand your
 * aim moves like (from humans.txt). Near-black green with a radioactive glow.
 */
final class InjectorWindow {
    static final Color TOP = new Color(0x0C130E);
    static final Color BOTTOM = new Color(0x060A07);
    static final Color BAR = new Color(0x0A100B);
    static final Color CARD = new Color(0x131D15);
    static final Color CARD_EDGE = new Color(0x22362A);
    static final Color GLOW = new Color(0x39FF14);
    static final Color GLOW_DIM = new Color(0x1F6B12);
    static final Color AMBER = new Color(0xFFC14D);
    static final Color TEXT = new Color(0xEAFFE4);
    static final Color SOFT = new Color(0x86A882);
    static final Color FAINT = new Color(0x4C6650);
    static final Color INK = new Color(0x08100A);
    private static final int REFRESH_MS = 2000;
    private static final String[] TABS = {"Inject", "Advanced", "Manage", "Update"};

    private final JFrame frame = new JFrame("Uranium");
    private final Path agentJar = locateAgentJar();
    private final CardLayout cards = new CardLayout();
    private final JPanel body = new JPanel(cards);
    private final Tabs tabs = new Tabs();
    private final DefaultListModel<JavaProcess> processModel = new DefaultListModel<>();
    private final JList<JavaProcess> processList = new JList<>(processModel);
    private final StatusCard statusCard = new StatusCard();
    private final JLabel status = label(" ", Font.PLAIN, 13, SOFT);
    private final InjectButton injectButton = new InjectButton();
    private final Pill openButton = new Pill("Open GUI", "", true);
    private final Pill updateButton = new Pill("Update", "Load this build in place of the one in the game", false);
    private final Pill unloadButton = new Pill("Unload", "Switch Uranium off in the game", false);
    private final Pill statusButton = new Pill("Status", "Which builds are in the game", false);
    private final JLabel hand = label(" ", Font.PLAIN, 13, SOFT);
    // Update tab
    private final JLabel current = label(" ", Font.PLAIN, 13, SOFT);
    private final JLabel latestLine = label("Checking GitHub...", Font.BOLD, 16, TEXT);
    private final javax.swing.JTextArea notes = new javax.swing.JTextArea();
    private final JLabel progress = label(" ", Font.PLAIN, 13, SOFT);
    private final Pill checkButton = new Pill("Check again", "", false);
    private final Pill downloadButton = new Pill("Download update", "", true);
    private final Pill restartButton = new Pill("Restart Uranium", "", true);
    private final JLabel news = label(" ", Font.PLAIN, 13, AMBER);
    private Updater.Release release;
    private boolean checking;
    // breathing animations: the found dot and the Inject button's glow
    private final Timer pulse;
    private float phase;
    private JavaProcess minecraft;
    private boolean busy;
    private boolean refreshing;

    InjectorWindow() {
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setMinimumSize(new Dimension(700, 500));
        frame.setSize(820, 580);
        frame.setLocationByPlatform(true);
        frame.setIconImages(List.of(mark(16), mark(32), mark(64)));

        JPanel root = new Backdrop(new BorderLayout());
        root.add(topBar(), BorderLayout.NORTH);
        body.setOpaque(false);
        body.add(injectPage(), TABS[0]);
        body.add(advancedPage(), TABS[1]);
        body.add(managePage(), TABS[2]);
        body.add(updatePage(), TABS[3]);
        root.add(body, BorderLayout.CENTER);
        root.add(footer(), BorderLayout.SOUTH);
        frame.setContentPane(root);

        injectButton.addActionListener(event -> run(minecraft, "open", "Injecting into"));
        openButton.addActionListener(event -> run(processList.getSelectedValue(), "open", "Opening GUI in"));
        updateButton.addActionListener(event -> run(target(), "update", "Updating"));
        unloadButton.addActionListener(event -> run(target(), "unload", "Unloading from"));
        statusButton.addActionListener(event -> run(target(), "status", "Checking"));
        checkButton.addActionListener(event -> checkForUpdate());
        downloadButton.addActionListener(event -> downloadUpdate());
        restartButton.addActionListener(event -> {
            try {
                Updater.restart();
            } catch (IOException exception) {
                progress.setText("Couldn't restart by itself: close Uranium and open it again.");
            }
        });
        downloadButton.setVisible(false);
        restartButton.setVisible(false);
        processList.addListSelectionListener(event -> updateButtons());
        pulse = new Timer(40, event -> {
            phase = (phase + 0.04F) % 1F;
            statusCard.repaint();
            injectButton.repaint();
        });
        selectTab(0);
        updateButtons();
    }

    void show() {
        frame.setVisible(true);
        pulse.start();
        refresh();
        new Timer(REFRESH_MS, event -> refresh()).start();
        checkForUpdate(); // on startup: is there a newer Uranium on GitHub?
    }

    // ---- layout ----

    private JComponent topBar() {
        JPanel bar = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics graphics) {
                Graphics2D g = (Graphics2D) graphics;
                g.setColor(BAR);
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setPaint(new GradientPaint(0, 0, new Color(0x39, 0xFF, 0x14, 0), getWidth() / 2F, 0,
                        new Color(0x39, 0xFF, 0x14, 90), true));
                g.fillRect(0, getHeight() - 1, getWidth(), 1);
            }
        };
        bar.setBorder(BorderFactory.createEmptyBorder(12, 18, 12, 14));
        JPanel brand = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        brand.setOpaque(false);
        brand.add(new JLabel(new javax.swing.ImageIcon(mark(30))));
        JLabel name = label("URANIUM", Font.BOLD, 21, TEXT);
        name.setFont(name.getFont().deriveFont(Map.of(TextAttribute.TRACKING, 0.12)));
        brand.add(name);
        JLabel tagline = label("injector", Font.PLAIN, 13, FAINT);
        tagline.setBorder(BorderFactory.createEmptyBorder(5, 0, 0, 0));
        brand.add(tagline);
        bar.add(brand, BorderLayout.WEST);
        bar.add(tabs, BorderLayout.EAST);
        return bar;
    }

    private void selectTab(int index) {
        tabs.selected = index;
        tabs.repaint();
        cards.show(body, TABS[index]);
        if (index == 2) {
            readHand();
        }
    }

    private JComponent injectPage() {
        JPanel page = new JPanel(new GridBagLayout());
        page.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.weighty = 0.25;
        c.anchor = GridBagConstraints.SOUTH;
        news.setVisible(false);
        c.insets = new Insets(0, 0, 10, 0);
        page.add(news, c);
        c.gridy = 1;
        c.weighty = 0;
        c.insets = new Insets(0, 0, 0, 0);
        statusCard.setPreferredSize(new Dimension(440, 70));
        page.add(statusCard, c);
        c.gridy = 2;
        c.weighty = 0;
        c.anchor = GridBagConstraints.CENTER;
        c.insets = new Insets(26, 0, 0, 0);
        injectButton.setPreferredSize(new Dimension(220, 220));
        page.add(injectButton, c);
        c.gridy = 3;
        c.weighty = 0.4;
        c.anchor = GridBagConstraints.NORTH;
        c.insets = new Insets(18, 0, 0, 0);
        page.add(label("Restart Minecraft before you inject a new version", Font.PLAIN, 13, FAINT), c);
        return page;
    }

    private JComponent advancedPage() {
        JPanel page = new JPanel(new BorderLayout(0, 12));
        page.setOpaque(false);
        page.setBorder(BorderFactory.createEmptyBorder(20, 26, 12, 26));
        JLabel title = label("Every Java program running. Pick the game, then Open GUI.", Font.BOLD, 15, TEXT);
        title.setHorizontalAlignment(SwingConstants.LEFT);
        page.add(title, BorderLayout.NORTH);
        processList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        processList.setOpaque(false);
        processList.setBackground(new Color(0, 0, 0, 0));
        processList.setFixedCellHeight(46);
        processList.setCellRenderer(new ProcessRow());
        JScrollPane scroll = new JScrollPane(processList);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        page.add(scroll, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        actions.setOpaque(false);
        openButton.setPreferredSize(new Dimension(160, 44));
        actions.add(openButton);
        page.add(actions, BorderLayout.SOUTH);
        return page;
    }

    private JComponent managePage() {
        JPanel page = new JPanel(new GridBagLayout());
        page.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 3;
        c.insets = new Insets(0, 0, 18, 0);
        page.add(label("For the game you injected into", Font.BOLD, 17, TEXT), c);
        c.gridwidth = 1;
        c.gridy = 1;
        c.insets = new Insets(0, 7, 0, 7);
        Pill[] buttons = {updateButton, unloadButton, statusButton};
        for (int i = 0; i < buttons.length; i++) {
            buttons[i].setPreferredSize(new Dimension(190, 96));
            c.gridx = i;
            page.add(buttons[i], c);
        }
        c.gridx = 0;
        c.gridy = 2;
        c.gridwidth = 3;
        c.insets = new Insets(30, 0, 0, 0);
        JPanel handCard = new Card(new BorderLayout(10, 0));
        handCard.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
        JLabel handTitle = label("Your hand", Font.BOLD, 13, TEXT);
        handCard.add(handTitle, BorderLayout.WEST);
        hand.setHorizontalAlignment(SwingConstants.LEFT);
        handCard.add(hand, BorderLayout.CENTER);
        handCard.setPreferredSize(new Dimension(598, 46));
        page.add(handCard, c);
        return page;
    }

    private JComponent updatePage() {
        JPanel page = new JPanel(new BorderLayout(0, 12));
        page.setOpaque(false);
        page.setBorder(BorderFactory.createEmptyBorder(20, 26, 8, 26));
        JPanel head = new Card(new BorderLayout(0, 4));
        head.setBorder(BorderFactory.createEmptyBorder(14, 18, 14, 18));
        latestLine.setHorizontalAlignment(SwingConstants.LEFT);
        current.setHorizontalAlignment(SwingConstants.LEFT);
        head.add(latestLine, BorderLayout.NORTH);
        head.add(current, BorderLayout.SOUTH);
        page.add(head, BorderLayout.NORTH);
        notes.setEditable(false);
        notes.setLineWrap(true);
        notes.setWrapStyleWord(true);
        notes.setOpaque(false);
        notes.setForeground(TEXT);
        notes.setFont(notes.getFont().deriveFont(13F));
        notes.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
        JScrollPane scroll = new JScrollPane(notes) {
            @Override
            protected void paintComponent(Graphics graphics) {
                Graphics2D g = smooth(graphics);
                g.setColor(new Color(CARD.getRed(), CARD.getGreen(), CARD.getBlue(), 220));
                g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 16, 16);
                g.setColor(CARD_EDGE);
                g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 16, 16);
                g.dispose();
            }
        };
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        page.add(scroll, BorderLayout.CENTER);
        JPanel actions = new JPanel(new BorderLayout());
        actions.setOpaque(false);
        progress.setHorizontalAlignment(SwingConstants.LEFT);
        actions.add(progress, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        checkButton.setPreferredSize(new Dimension(140, 44));
        downloadButton.setPreferredSize(new Dimension(180, 44));
        restartButton.setPreferredSize(new Dimension(170, 44));
        buttons.add(checkButton);
        buttons.add(downloadButton);
        buttons.add(restartButton);
        actions.add(buttons, BorderLayout.EAST);
        page.add(actions, BorderLayout.SOUTH);
        current.setText("You have " + version() + ", made " + day(Updater.builtAt(agentJar)));
        return page;
    }

    static String day(java.time.Instant instant) {
        return java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")
                .withZone(java.time.ZoneId.systemDefault()).format(instant);
    }

    /** Asks GitHub for the latest release (and the news line), in the background. */
    private void checkForUpdate() {
        if (checking) {
            return;
        }
        checking = true;
        checkButton.setEnabled(false);
        latestLine.setText("Checking GitHub...");
        latestLine.setForeground(TEXT);
        new SwingWorker<Object[], Void>() {
            @Override
            protected Object[] doInBackground() {
                Object found;
                try {
                    found = Updater.latest();
                } catch (Exception exception) {
                    found = exception.getMessage() == null ? "Couldn't reach GitHub" : exception.getMessage();
                }
                return new Object[] {found, Updater.news()};
            }

            @Override
            protected void done() {
                checking = false;
                checkButton.setEnabled(true);
                Object[] result;
                try {
                    result = get();
                } catch (InterruptedException | ExecutionException exception) {
                    latestLine.setText("Couldn't check for updates");
                    return;
                }
                if (result[1] instanceof String line) {
                    news.setText("News: " + line);
                    news.setVisible(true);
                }
                if (!(result[0] instanceof Updater.Release latest)) {
                    latestLine.setText("Couldn't check: " + result[0]);
                    latestLine.setForeground(SOFT);
                    notes.setText("Uranium looks for new versions on GitHub (" + Updater.REPO + ") every time it opens.");
                    return;
                }
                release = latest;
                String mine = agentJar.getFileName().toString();
                notes.setText(latest.notes().isBlank() ? "(no notes for this release)" : latest.notes());
                notes.setCaretPosition(0);
                boolean same = mine.equals(latest.jarName());
                boolean newer = !same && latest.jarName() != null && latest.published().isAfter(Updater.builtAt(agentJar));
                updateReady = newer;
                tabs.repaint();
                downloadButton.setVisible(newer);
                if (same) {
                    latestLine.setText("You're up to date");
                    latestLine.setForeground(GLOW);
                } else if (newer) {
                    latestLine.setText("New: " + (latest.name().isBlank() ? latest.tag() : latest.name())
                            + "  ·  " + day(latest.published()));
                    latestLine.setForeground(AMBER);
                    status.setText("A new Uranium is out: see Update");
                } else {
                    latestLine.setText("Your build is newer than the latest release (" + latest.tag() + ")");
                    latestLine.setForeground(SOFT);
                }
            }
        }.execute();
    }

    /** Downloads the release's build into the builds folder next to Uranium.exe. */
    private void downloadUpdate() {
        if (release == null || release.jarUrl() == null) {
            return;
        }
        downloadButton.setEnabled(false);
        Path builds = homeFolder().resolve("builds");
        new SwingWorker<Path, Long>() {
            @Override
            protected Path doInBackground() throws Exception {
                return Updater.download(release, builds, bytes -> publish(bytes));
            }

            @Override
            protected void process(List<Long> chunks) {
                long bytes = chunks.get(chunks.size() - 1);
                progress.setText(release.jarSize() > 0
                        ? String.format(java.util.Locale.ROOT, "Downloading... %d%%", bytes * 100 / release.jarSize())
                        : String.format(java.util.Locale.ROOT, "Downloading... %d KB", bytes / 1024));
            }

            @Override
            protected void done() {
                try {
                    Path jar = get();
                    progress.setText("Downloaded " + jar.getFileName() + ". Restart Uranium to use it.");
                    progress.setForeground(GLOW);
                    downloadButton.setVisible(false);
                    restartButton.setVisible(true);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException exception) {
                    progress.setText("Download failed: " + exception.getCause().getMessage());
                    downloadButton.setEnabled(true);
                }
            }
        }.execute();
    }

    /** Where Uranium lives: next to Uranium.exe (or Uranium.bat when run from the source folder). */
    private Path homeFolder() {
        String home = System.getProperty("uranium.home");
        return home != null ? Path.of(home) : appFolder();
    }

    private JComponent footer() {
        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        footer.setBorder(BorderFactory.createEmptyBorder(6, 18, 12, 18));
        status.setHorizontalAlignment(SwingConstants.LEFT);
        footer.add(status, BorderLayout.CENTER);
        JLabel build = new JLabel(version(), SwingConstants.CENTER) {
            @Override
            protected void paintComponent(Graphics graphics) {
                Graphics2D g = smooth(graphics);
                g.setColor(new Color(0x39, 0xFF, 0x14, 28));
                g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, getHeight(), getHeight());
                g.setColor(GLOW_DIM);
                g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, getHeight(), getHeight());
                g.dispose();
                super.paintComponent(graphics);
            }
        };
        build.setFont(build.getFont().deriveFont(Font.BOLD, 12F));
        build.setForeground(GLOW);
        build.setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 12));
        footer.add(build, BorderLayout.EAST);
        return footer;
    }

    static JLabel label(String text, int style, float size, Color color) {
        JLabel label = new JLabel(text, SwingConstants.CENTER);
        label.setFont(label.getFont().deriveFont(style, size));
        label.setForeground(color);
        return label;
    }

    /** The build's tag from the jar name (Uranium-<tag>.jar). */
    private String version() {
        String name = agentJar.getFileName().toString();
        int dash = name.lastIndexOf('-');
        return dash < 0 ? "dev build" : "build " + name.substring(dash + 1).replace(".jar", "");
    }

    static Graphics2D smooth(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        return g;
    }

    /** The radiation trefoil: three blades and a hub, in green, this many pixels across. */
    static BufferedImage mark(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = smooth(image.getGraphics());
        double c = size / 2.0;
        double outer = size * 0.48;
        g.setColor(new Color(0x0C130E));
        g.fill(new Ellipse2D.Double(c - outer, c - outer, outer * 2, outer * 2));
        g.setColor(GLOW);
        g.setStroke(new BasicStroke(Math.max(1F, size / 22F)));
        g.draw(new Ellipse2D.Double(c - outer + 0.5, c - outer + 0.5, outer * 2 - 1, outer * 2 - 1));
        double blade = size * 0.40;
        for (int i = 0; i < 3; i++) {
            g.fill(new Arc2D.Double(c - blade, c - blade, blade * 2, blade * 2, 90 - 30 + i * 120, 60, Arc2D.PIE));
        }
        double gap = size * 0.15;
        g.setColor(new Color(0x0C130E));
        g.fill(new Ellipse2D.Double(c - gap, c - gap, gap * 2, gap * 2));
        double hub = size * 0.09;
        g.setColor(GLOW);
        g.fill(new Ellipse2D.Double(c - hub, c - hub, hub * 2, hub * 2));
        g.dispose();
        return image;
    }

    // ---- behaviour ----

    /** Lists the Java processes in the background and picks out Minecraft. */
    private void refresh() {
        if (refreshing) {
            return;
        }
        refreshing = true;
        new SwingWorker<List<JavaProcess>, Void>() {
            @Override
            protected List<JavaProcess> doInBackground() {
                return ProcessService.listProcesses();
            }

            @Override
            protected void done() {
                refreshing = false;
                List<JavaProcess> processes;
                try {
                    processes = get();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (ExecutionException exception) {
                    statusCard.set(StatusCard.ERROR, "Could not list Java programs", "Try running the injector again");
                    return;
                }
                JavaProcess selected = processList.getSelectedValue();
                if (!processModel.isEmpty() && processModel.size() == processes.size()
                        && processes.equals(java.util.Collections.list(processModel.elements()))) {
                    // unchanged: keep the list (and its selection) as it is
                } else {
                    processModel.clear();
                    processes.forEach(processModel::addElement);
                    if (selected != null && processes.contains(selected)) {
                        processList.setSelectedValue(selected, false);
                    }
                }
                minecraft = processes.stream().filter(JavaProcess::minecraft).findFirst().orElse(null);
                if (minecraft == null) {
                    statusCard.set(StatusCard.WAITING, "Waiting for Minecraft", "Start Minecraft 26.1.2 with Fabric");
                } else {
                    statusCard.set(StatusCard.FOUND, "Minecraft is running", "PID " + minecraft.pid() + "  ·  ready to inject");
                }
                updateButtons();
            }
        }.execute();
    }

    /** What Manage acts on: your pick in Advanced, else the Minecraft found. */
    private JavaProcess target() {
        JavaProcess picked = processList.getSelectedValue();
        return picked != null ? picked : minecraft;
    }

    private void updateButtons() {
        injectButton.setEnabled(!busy && minecraft != null);
        openButton.setEnabled(!busy && processList.getSelectedValue() != null);
        boolean manage = !busy && target() != null;
        updateButton.setEnabled(manage);
        unloadButton.setEnabled(manage);
        statusButton.setEnabled(manage);
    }

    private void run(JavaProcess process, String command, String working) {
        if (process == null) {
            status.setText("No game picked");
            return;
        }
        if (!Files.isRegularFile(agentJar)) {
            status.setText("Build Uranium first (gradle jar)");
            return;
        }
        busy = true;
        updateButtons();
        status.setText(working + " PID " + process.pid() + "...");
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                return ProcessService.attach(process, agentJar, command);
            }

            @Override
            protected void done() {
                try {
                    String result = get();
                    status.setText(result.lines().findFirst().orElse(result));
                    if (command.equals("status")) {
                        JOptionPane.showMessageDialog(frame, result,
                                "Versions in PID " + process.pid(), JOptionPane.INFORMATION_MESSAGE);
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    status.setText("Interrupted");
                } catch (ExecutionException exception) {
                    String message = exception.getCause().getMessage();
                    status.setText(message == null ? "Failed" : message);
                } finally {
                    busy = false;
                    updateButtons();
                }
            }
        }.execute();
    }

    /** The Uranium folder: up from the jar to the folder with Uranium.bat. */
    private Path appFolder() {
        for (Path dir = agentJar.toAbsolutePath().getParent(); dir != null; dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve("Uranium.bat")) || Files.isRegularFile(dir.resolve("Uranium.exe"))) {
                return dir;
            }
        }
        return Path.of("").toAbsolutePath();
    }

    /** The hand your aim moves like: from humans.txt if the mouse test was taken. */
    private void readHand() {
        Path file = appFolder().resolve("humans.txt");
        if (!Files.isRegularFile(file)) {
            hand.setText("An average hand. Take the mouse test in the game (Play Style) to use yours.");
            return;
        }
        Properties humans = new Properties();
        try (var reader = Files.newBufferedReader(file)) {
            humans.load(reader);
            hand.setText(String.format(java.util.Locale.ROOT,
                    "stops %.0f%% of the way, past %.0f%% of the time, fastest %.0f%% in  ·  humans.txt",
                    Double.parseDouble(humans.getProperty("stops_at", "0.91")) * 100,
                    Double.parseDouble(humans.getProperty("goes_past", "0.27")) * 100,
                    Double.parseDouble(humans.getProperty("fastest_at", "0.46")) * 100));
            hand.setForeground(TEXT);
        } catch (IOException | NumberFormatException exception) {
            hand.setText("humans.txt couldn't be read");
        }
    }

    static Path locateAgentJar() {
        try {
            Path location = Path.of(InjectorWindow.class
                    .getProtectionDomain().getCodeSource().getLocation().toURI());
            if (Files.isRegularFile(location)) {
                return location;
            }
        } catch (URISyntaxException | NullPointerException ignored) {
        }
        // Running from the IDE: use the newest built injector jar.
        Path fallback = Path.of("build", "libs", "Uranium.jar").toAbsolutePath();
        try (var jars = Files.list(Path.of("build", "libs"))) {
            return jars.filter(jar -> Updater.isBuild(jar.getFileName().toString()))
                    .max(Comparator.comparingLong(jar -> jar.toFile().lastModified()))
                    .map(Path::toAbsolutePath)
                    .orElse(fallback);
        } catch (IOException exception) {
            return fallback;
        }
    }

    // ---- pieces ----

    /** The window's background: a dark green fade, a faint dot grid, a soft glow in the middle. */
    static final class Backdrop extends JPanel {
        Backdrop(java.awt.LayoutManager layout) {
            super(layout);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = smooth(graphics);
            int w = getWidth();
            int h = getHeight();
            g.setPaint(new GradientPaint(0, 0, TOP, 0, h, BOTTOM));
            g.fillRect(0, 0, w, h);
            float radius = Math.max(w, h) * 0.55F;
            g.setPaint(new RadialGradientPaint(w / 2F, h * 0.48F, radius, new float[] {0F, 1F},
                    new Color[] {new Color(0x39, 0xFF, 0x14, 26), new Color(0x39, 0xFF, 0x14, 0)}));
            g.fillRect(0, 0, w, h);
            g.setColor(new Color(0x39, 0xFF, 0x14, 12));
            for (int x = 12; x < w; x += 24) {
                for (int y = 12; y < h; y += 24) {
                    g.fillRect(x, y, 1, 1);
                }
            }
            g.dispose();
        }
    }

    /** A rounded dark card with a thin edge. */
    static class Card extends JPanel {
        Card(java.awt.LayoutManager layout) {
            super(layout);
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = smooth(graphics);
            g.setColor(new Color(CARD.getRed(), CARD.getGreen(), CARD.getBlue(), 220));
            g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 16, 16);
            g.setColor(CARD_EDGE);
            g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 16, 16);
            g.dispose();
        }
    }

    /** The tabs: a dark rounded track with a glowing pill under the open one. */
    private boolean updateReady;

    private final class Tabs extends JComponent {
        private int selected;
        private int hover = -1;

        Tabs() {
            setPreferredSize(new Dimension(430, 38));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setFont(new JLabel().getFont().deriveFont(Font.BOLD, 14F));
            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent event) {
                    int index = index(event.getX());
                    if (index >= 0) {
                        selectTab(index);
                    }
                }

                @Override
                public void mouseMoved(MouseEvent event) {
                    int index = index(event.getX());
                    if (index != hover) {
                        hover = index;
                        repaint();
                    }
                }

                @Override
                public void mouseExited(MouseEvent event) {
                    hover = -1;
                    repaint();
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
        }

        private int index(int x) {
            int width = getWidth() / TABS.length;
            return x < 0 || x >= getWidth() ? -1 : Math.min(TABS.length - 1, x / width);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = smooth(graphics);
            int h = getHeight();
            g.setColor(CARD);
            g.fillRoundRect(0, 0, getWidth() - 1, h - 1, h, h);
            g.setColor(CARD_EDGE);
            g.drawRoundRect(0, 0, getWidth() - 1, h - 1, h, h);
            int width = getWidth() / TABS.length;
            g.setFont(getFont());
            var metrics = g.getFontMetrics();
            for (int i = 0; i < TABS.length; i++) {
                int x = i * width;
                if (i == selected) {
                    g.setColor(new Color(0x39, 0xFF, 0x14, 50));
                    g.fillRoundRect(x + 1, 1, width - 2, h - 2, h - 2, h - 2);
                    g.setColor(GLOW);
                    g.fillRoundRect(x + 4, 4, width - 8, h - 8, h - 8, h - 8);
                } else if (i == hover) {
                    g.setColor(CARD_EDGE);
                    g.fillRoundRect(x + 4, 4, width - 8, h - 8, h - 8, h - 8);
                }
                g.setColor(i == selected ? INK : i == hover ? TEXT : SOFT);
                int tx = x + (width - metrics.stringWidth(TABS[i])) / 2;
                int ty = (h - metrics.getHeight()) / 2 + metrics.getAscent();
                g.drawString(TABS[i], tx, ty);
                if (i == 3 && updateReady) {
                    // a new Uranium is out: a dot on the Update tab
                    g.setColor(i == selected ? INK : AMBER);
                    int dx = tx + metrics.stringWidth(TABS[i]) + 5;
                    g.fill(new Ellipse2D.Double(dx, ty - metrics.getAscent() + 2, 7, 7));
                }
            }
            g.dispose();
        }
    }

    /** Whether Minecraft is running: a dot (breathing green when found) and two lines. */
    private final class StatusCard extends Card {
        static final int WAITING = 0;
        static final int FOUND = 1;
        static final int ERROR = 2;
        private int state = WAITING;
        private String title = "Looking for Minecraft...";
        private String detail = " ";

        StatusCard() {
            super(new BorderLayout());
        }

        void set(int state, String title, String detail) {
            this.state = state;
            this.title = title;
            this.detail = detail;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = smooth(graphics);
            int h = getHeight();
            Color dot = state == FOUND ? GLOW : state == ERROR ? new Color(0xFF5252) : AMBER;
            double breathe = 0.5 + 0.5 * Math.sin(phase * Math.PI * 2);
            int cx = 34;
            int cy = h / 2;
            float halo = state == FOUND ? (float) (9 + 6 * breathe) : 9;
            g.setColor(new Color(dot.getRed(), dot.getGreen(), dot.getBlue(), state == FOUND ? (int) (40 + 40 * breathe) : 40));
            g.fill(new Ellipse2D.Double(cx - halo, cy - halo, halo * 2, halo * 2));
            g.setColor(dot);
            g.fill(new Ellipse2D.Double(cx - 5, cy - 5, 10, 10));
            g.setFont(getFont().deriveFont(Font.BOLD, 17F));
            g.setColor(state == FOUND ? TEXT : TEXT);
            g.drawString(title, 60, cy - 3);
            g.setFont(getFont().deriveFont(Font.PLAIN, 13F));
            g.setColor(SOFT);
            g.drawString(detail, 60, cy + 16);
            g.dispose();
        }
    }

    /** The big round Inject button: a slow glowing ring when Minecraft is there to inject into. */
    private final class InjectButton extends JButton {
        private boolean hover;
        private boolean down;

        InjectButton() {
            super("INJECT");
            setFont(getFont().deriveFont(Font.BOLD, 26F));
            setFocusPainted(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent event) {
                    hover = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent event) {
                    hover = false;
                    down = false;
                    repaint();
                }

                @Override
                public void mousePressed(MouseEvent event) {
                    down = true;
                    repaint();
                }

                @Override
                public void mouseReleased(MouseEvent event) {
                    down = false;
                    repaint();
                }
            });
        }

        @Override
        public boolean contains(int x, int y) {
            double r = Math.min(getWidth(), getHeight()) / 2.0 - 14;
            return Math.hypot(x - getWidth() / 2.0, y - getHeight() / 2.0) <= r;
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = smooth(graphics);
            double cx = getWidth() / 2.0;
            double cy = getHeight() / 2.0;
            double r = Math.min(getWidth(), getHeight()) / 2.0 - 16;
            boolean on = isEnabled();
            double breathe = 0.5 + 0.5 * Math.sin(phase * Math.PI * 2);
            if (on) {
                // the glow: soft rings fading out, breathing
                for (int i = 6; i >= 1; i--) {
                    double spread = i * (2.0 + 1.2 * breathe) + (hover ? 2 : 0);
                    g.setColor(new Color(0x39, 0xFF, 0x14, (int) (10 + 6 * breathe)));
                    g.fill(new Ellipse2D.Double(cx - r - spread, cy - r - spread, (r + spread) * 2, (r + spread) * 2));
                }
            }
            double press = down && on ? 3 : 0;
            Ellipse2D face = new Ellipse2D.Double(cx - r + press, cy - r + press, (r - press) * 2, (r - press) * 2);
            if (on) {
                g.setPaint(new GradientPaint((float) cx, (float) (cy - r), hover ? new Color(0x6BFF4F) : new Color(0x4DFF2A),
                        (float) cx, (float) (cy + r), new Color(0x1FA80B)));
            } else {
                g.setPaint(new GradientPaint((float) cx, (float) (cy - r), new Color(0x17261A), (float) cx,
                        (float) (cy + r), new Color(0x0E1710)));
            }
            g.fill(face);
            g.setStroke(new BasicStroke(2F));
            g.setColor(on ? new Color(0xB8FFA6) : CARD_EDGE);
            g.draw(face);
            // a thin inner ring
            g.setColor(on ? new Color(0x08, 0x10, 0x0A, 60) : new Color(0x39, 0xFF, 0x14, 18));
            g.draw(new Ellipse2D.Double(cx - r + 12, cy - r + 12, (r - 12) * 2, (r - 12) * 2));
            g.setFont(getFont());
            var metrics = g.getFontMetrics();
            String text = getText();
            g.setColor(on ? INK : FAINT);
            g.drawString(text, (float) (cx - metrics.stringWidth(text) / 2.0),
                    (float) (cy - metrics.getHeight() / 2.0 + metrics.getAscent()));
            g.setFont(getFont().deriveFont(Font.PLAIN, 12F));
            String under = on ? "into Minecraft" : "waiting for the game";
            var small = g.getFontMetrics();
            g.setColor(on ? new Color(0x08, 0x10, 0x0A, 170) : FAINT);
            g.drawString(under, (float) (cx - small.stringWidth(under) / 2.0), (float) (cy + metrics.getHeight() / 2.0 + 10));
            g.dispose();
        }
    }

    /** A rounded button: glowing green when it's the main action, a dark tile otherwise. */
    static final class Pill extends JButton {
        private final boolean main;
        private final String caption;
        private boolean hover;

        Pill(String text, String caption, boolean main) {
            super(text);
            this.main = main;
            this.caption = caption;
            setFont(getFont().deriveFont(Font.BOLD, main ? 15F : 17F));
            setFocusPainted(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent event) {
                    hover = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent event) {
                    hover = false;
                    repaint();
                }
            });
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = smooth(graphics);
            boolean on = isEnabled();
            int w = getWidth();
            int h = getHeight();
            int arc = main ? h - 8 : 18;
            if (main) {
                if (on) {
                    g.setColor(new Color(0x39, 0xFF, 0x14, hover ? 70 : 40));
                    g.fillRoundRect(0, 0, w, h, arc + 8, arc + 8);
                }
                g.setColor(!on ? GLOW_DIM.darker() : hover ? GLOW.brighter() : GLOW);
                g.fillRoundRect(4, 4, w - 8, h - 8, arc, arc);
            } else {
                g.setColor(new Color(CARD.getRed(), CARD.getGreen(), CARD.getBlue(), 230));
                g.fillRoundRect(2, 2, w - 4, h - 4, arc, arc);
                g.setColor(on && hover ? GLOW : CARD_EDGE);
                g.setStroke(new BasicStroke(on && hover ? 1.6F : 1F));
                g.drawRoundRect(2, 2, w - 5, h - 5, arc, arc);
                if (on && hover) {
                    g.setColor(new Color(0x39, 0xFF, 0x14, 22));
                    g.fillRoundRect(2, 2, w - 4, h - 4, arc, arc);
                }
            }
            g.setFont(getFont());
            var metrics = g.getFontMetrics();
            String text = getText();
            if (main || caption.isEmpty()) {
                g.setColor(main ? (on ? INK : FAINT) : (on ? TEXT : FAINT));
                g.drawString(text, (w - metrics.stringWidth(text)) / 2, (h - metrics.getHeight()) / 2 + metrics.getAscent());
            } else {
                g.setColor(on ? TEXT : FAINT);
                g.drawString(text, 18, 32);
                g.setFont(getFont().deriveFont(Font.PLAIN, 12F));
                g.setColor(on ? SOFT : FAINT);
                drawWrapped(g, caption, 18, 54, w - 34);
            }
            g.dispose();
        }

        private static void drawWrapped(Graphics2D g, String text, int x, int y, int width) {
            var metrics = g.getFontMetrics();
            StringBuilder line = new StringBuilder();
            for (String word : text.split(" ")) {
                String next = line.isEmpty() ? word : line + " " + word;
                if (metrics.stringWidth(next) > width && !line.isEmpty()) {
                    g.drawString(line.toString(), x, y);
                    y += metrics.getHeight();
                    line = new StringBuilder(word);
                } else {
                    line = new StringBuilder(next);
                }
            }
            if (!line.isEmpty()) {
                g.drawString(line.toString(), x, y);
            }
        }
    }

    /** One Java program in Advanced: a card with its name and PID, Minecraft marked in green. */
    private static final class ProcessRow extends JComponent implements ListCellRenderer<JavaProcess> {
        private JavaProcess process;
        private boolean selected;

        @Override
        public Component getListCellRendererComponent(JList<? extends JavaProcess> list, JavaProcess value, int index,
                boolean isSelected, boolean cellHasFocus) {
            process = value;
            selected = isSelected;
            setFont(list.getFont());
            return this;
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = smooth(graphics);
            int w = getWidth();
            int h = getHeight();
            g.setColor(selected ? new Color(0x39, 0xFF, 0x14, 40) : new Color(CARD.getRed(), CARD.getGreen(), CARD.getBlue(), 220));
            g.fillRoundRect(0, 3, w - 1, h - 7, 14, 14);
            g.setColor(selected ? GLOW : CARD_EDGE);
            g.drawRoundRect(0, 3, w - 1, h - 7, 14, 14);
            int x = 16;
            if (process.minecraft()) {
                g.setFont(getFont().deriveFont(Font.BOLD, 11F));
                var chip = g.getFontMetrics();
                String tag = "MINECRAFT";
                int cw = chip.stringWidth(tag) + 14;
                g.setColor(GLOW);
                g.fillRoundRect(x, h / 2 - 9, cw, 18, 18, 18);
                g.setColor(INK);
                g.drawString(tag, x + 7, h / 2 - 9 + (18 - chip.getHeight()) / 2 + chip.getAscent());
                x += cw + 10;
            }
            g.setFont(getFont().deriveFont(Font.PLAIN, 14F));
            var metrics = g.getFontMetrics();
            g.setColor(TEXT);
            g.drawString(process.executable(), x, h / 2 - 2 + metrics.getAscent() / 2);
            String pid = "PID " + process.pid();
            g.setColor(SOFT);
            g.drawString(pid, w - 16 - metrics.stringWidth(pid), h / 2 - 2 + metrics.getAscent() / 2);
            g.dispose();
        }
    }
}
