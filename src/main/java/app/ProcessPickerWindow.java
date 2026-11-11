package app;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import javax.swing.Timer;

/**
 * The Uranium injector window, laid out like a one-click loader: a tab bar on top (Inject,
 * Advanced inject, Manage), then a big Inject button that finds Minecraft by itself.
 * Advanced inject lists every Java process to pick one by hand; Manage has Update, Unload
 * and Status for the game you picked. Near-black with a radioactive green glow.
 */
final class ProcessPickerWindow {
    private static final Color BAR = new Color(0x0D130E);
    private static final Color BODY = new Color(0x141C16);
    private static final Color PANEL = new Color(0x1B261D);
    private static final Color GLOW = new Color(0x39FF14);
    private static final Color GLOW_DIM = new Color(0x1F6B12);
    private static final Color TEXT = new Color(0xEAFFE4);
    private static final Color SOFT = new Color(0x86A882);
    private static final Color INK = new Color(0x0B110C);
    private static final int REFRESH_MS = 2000;

    private final JFrame frame = new JFrame("Uranium");
    private final Path agentJar = locateAgentJar();
    private final CardLayout cards = new CardLayout();
    private final JPanel body = new JPanel(cards);
    private final List<JLabel> tabs = new ArrayList<>();
    private final DefaultListModel<JavaProcess> processModel = new DefaultListModel<>();
    private final JList<JavaProcess> processList = new JList<>(processModel);
    private final JLabel found = label("Looking for Minecraft...", Font.BOLD, 20, TEXT);
    private final JLabel status = label(" ", Font.PLAIN, 14, SOFT);
    private final Pill injectButton = new Pill("Inject", 30, true);
    private final Pill openButton = new Pill("Open GUI", 16, true);
    private final Pill updateButton = new Pill("Update", 16, false);
    private final Pill unloadButton = new Pill("Unload", 16, false);
    private final Pill statusButton = new Pill("Status", 16, false);
    private JavaProcess minecraft;
    private boolean busy;
    private boolean refreshing;

    ProcessPickerWindow() {
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setMinimumSize(new Dimension(640, 460));
        frame.setSize(760, 560);
        frame.setLocationByPlatform(true);

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(BODY);
        root.add(tabBar(), BorderLayout.NORTH);
        body.setBackground(BODY);
        body.add(injectPage(), "Inject");
        body.add(advancedPage(), "Advanced inject");
        body.add(managePage(), "Manage");
        root.add(body, BorderLayout.CENTER);
        root.add(footer(), BorderLayout.SOUTH);
        frame.setContentPane(root);

        injectButton.addActionListener(event -> run(minecraft, "open", "Injecting into"));
        openButton.addActionListener(event -> run(processList.getSelectedValue(), "open", "Opening GUI in"));
        updateButton.addActionListener(event -> run(target(), "update", "Updating"));
        unloadButton.addActionListener(event -> run(target(), "unload", "Unloading from"));
        statusButton.addActionListener(event -> run(target(), "status", "Checking"));
        processList.addListSelectionListener(event -> updateButtons());
        selectTab(0);
        updateButtons();
    }

    void show() {
        frame.setVisible(true);
        refresh();
        new Timer(REFRESH_MS, event -> refresh()).start();
    }

    // ---- layout ----

    private JComponent tabBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(BAR);
        JPanel row = new JPanel(new GridLayout(1, 3));
        row.setOpaque(false);
        String[] names = {"Inject", "Advanced inject", "Manage"};
        for (int i = 0; i < names.length; i++) {
            int index = i;
            JLabel tab = label(names[i], Font.PLAIN, 22, TEXT);
            tab.setBorder(BorderFactory.createEmptyBorder(14, 10, 11, 10));
            tab.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            tab.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent event) {
                    selectTab(index);
                }
            });
            tabs.add(tab);
            row.add(tab);
        }
        bar.add(row, BorderLayout.CENTER);
        JLabel brand = label("☢ URANIUM", Font.BOLD, 18, GLOW);
        brand.setBorder(BorderFactory.createEmptyBorder(0, 16, 0, 18));
        bar.add(brand, BorderLayout.EAST);
        return bar;
    }

    private void selectTab(int index) {
        for (int i = 0; i < tabs.size(); i++) {
            JLabel tab = tabs.get(i);
            boolean on = i == index;
            tab.setForeground(on ? TEXT : SOFT);
            tab.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 3, 0, on ? GLOW : BAR),
                    BorderFactory.createEmptyBorder(14, 10, 8, 10)));
        }
        cards.show(body, tabs.get(index).getText());
    }

    private JComponent injectPage() {
        JPanel page = new JPanel(new GridBagLayout());
        page.setBackground(BODY);
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.anchor = GridBagConstraints.NORTH;
        c.weighty = 0.3;
        c.insets = new Insets(16, 0, 0, 0);
        page.add(found, c);
        c.gridy = 1;
        c.weighty = 0;
        c.anchor = GridBagConstraints.CENTER;
        c.insets = new Insets(0, 0, 0, 0);
        injectButton.setPreferredSize(new Dimension(420, 150));
        page.add(injectButton, c);
        c.gridy = 2;
        c.weighty = 0.5;
        c.anchor = GridBagConstraints.NORTH;
        c.insets = new Insets(26, 0, 0, 0);
        page.add(label("Restart Minecraft before you inject a new version", Font.BOLD, 15, SOFT), c);
        return page;
    }

    private JComponent advancedPage() {
        JPanel page = new JPanel(new BorderLayout(0, 12));
        page.setBackground(BODY);
        page.setBorder(BorderFactory.createEmptyBorder(18, 22, 12, 22));
        page.add(label("Every Java program running. Pick the game, then Open GUI.", Font.BOLD, 15, TEXT),
                BorderLayout.NORTH);
        processList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        processList.setBackground(PANEL);
        processList.setForeground(TEXT);
        processList.setSelectionBackground(GLOW);
        processList.setSelectionForeground(INK);
        processList.setFont(processList.getFont().deriveFont(15F));
        processList.setFixedCellHeight(30);
        JScrollPane scroll = new JScrollPane(processList);
        scroll.setBorder(BorderFactory.createLineBorder(GLOW_DIM));
        scroll.getViewport().setBackground(PANEL);
        page.add(scroll, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        actions.setOpaque(false);
        openButton.setPreferredSize(new Dimension(150, 40));
        actions.add(openButton);
        page.add(actions, BorderLayout.SOUTH);
        return page;
    }

    private JComponent managePage() {
        JPanel page = new JPanel(new GridBagLayout());
        page.setBackground(BODY);
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 3;
        c.insets = new Insets(0, 0, 22, 0);
        page.add(label("For the game you injected into", Font.BOLD, 17, TEXT), c);
        c.gridwidth = 1;
        c.gridy = 1;
        c.insets = new Insets(0, 8, 0, 8);
        Pill[] buttons = {updateButton, unloadButton, statusButton};
        for (int i = 0; i < buttons.length; i++) {
            buttons[i].setPreferredSize(new Dimension(170, 64));
            c.gridx = i;
            page.add(buttons[i], c);
        }
        c.gridx = 0;
        c.gridy = 2;
        c.gridwidth = 3;
        c.insets = new Insets(22, 0, 0, 0);
        page.add(label("Update loads this build in place of the one in the game. Unload switches it all off.",
                Font.PLAIN, 13, SOFT), c);
        return page;
    }

    private JComponent footer() {
        JPanel footer = new JPanel(new BorderLayout());
        footer.setBackground(BODY);
        footer.setBorder(BorderFactory.createEmptyBorder(6, 16, 10, 16));
        status.setHorizontalAlignment(SwingConstants.LEFT);
        footer.add(status, BorderLayout.CENTER);
        footer.add(label(version(), Font.BOLD, 15, GLOW), BorderLayout.EAST);
        return footer;
    }

    private static JLabel label(String text, int style, float size, Color color) {
        JLabel label = new JLabel(text, SwingConstants.CENTER);
        label.setFont(label.getFont().deriveFont(style, size));
        label.setForeground(color);
        return label;
    }

    /** The build's tag from the jar name (minecraft-gui-injector-<tag>.jar). */
    private String version() {
        String name = agentJar.getFileName().toString();
        int dash = name.lastIndexOf('-');
        return dash < 0 ? "" : "build " + name.substring(dash + 1).replace(".jar", "");
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
                return MinecraftProcessService.listProcesses();
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
                    found.setText("Could not list Java processes");
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
                found.setText(minecraft == null ? "Minecraft processes not found"
                        : "Minecraft found (PID " + minecraft.pid() + ")");
                found.setForeground(minecraft == null ? TEXT : GLOW);
                updateButtons();
            }
        }.execute();
    }

    /** What Manage acts on: your pick in Advanced inject, else the Minecraft found. */
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
                return MinecraftProcessService.attach(process, agentJar, command);
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

    private static Path locateAgentJar() {
        try {
            Path location = Path.of(ProcessPickerWindow.class
                    .getProtectionDomain().getCodeSource().getLocation().toURI());
            if (Files.isRegularFile(location)) {
                return location;
            }
        } catch (URISyntaxException | NullPointerException ignored) {
        }
        // Running from the IDE: use the newest built injector jar.
        Path fallback = Path.of("build", "libs", "minecraft-gui-injector.jar").toAbsolutePath();
        try (var jars = Files.list(Path.of("build", "libs"))) {
            return jars.filter(jar -> jar.getFileName().toString().startsWith("minecraft-gui-injector-"))
                    .max(Comparator.comparingLong(jar -> jar.toFile().lastModified()))
                    .map(Path::toAbsolutePath)
                    .orElse(fallback);
        } catch (IOException exception) {
            return fallback;
        }
    }

    /** A flat rounded button: glowing green when it's the main action, dark otherwise. */
    private static final class Pill extends JButton {
        private final boolean main;
        private boolean hover;

        Pill(String text, float size, boolean main) {
            super(text);
            this.main = main;
            setFont(getFont().deriveFont(Font.BOLD, size));
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
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Color fill;
            Color text;
            if (!isEnabled()) {
                fill = main ? GLOW_DIM.darker() : PANEL;
                text = SOFT.darker();
            } else if (main) {
                fill = hover ? GLOW.brighter() : GLOW;
                text = INK;
            } else {
                fill = hover ? GLOW_DIM : PANEL;
                text = TEXT;
            }
            int arc = Math.min(18, getHeight() / 2);
            if (isEnabled() && main) {
                g.setColor(new Color(0x39, 0xFF, 0x14, hover ? 70 : 40));
                g.fillRoundRect(0, 0, getWidth(), getHeight(), arc + 8, arc + 8);
            }
            g.setColor(fill);
            g.fillRoundRect(4, 4, getWidth() - 8, getHeight() - 8, arc, arc);
            if (!main) {
                g.setColor(GLOW_DIM);
                g.drawRoundRect(4, 4, getWidth() - 9, getHeight() - 9, arc, arc);
            }
            g.setColor(text);
            g.setFont(getFont());
            var metrics = g.getFontMetrics();
            int x = (getWidth() - metrics.stringWidth(getText())) / 2;
            int y = (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent();
            g.drawString(getText(), x, y);
            g.dispose();
        }
    }
}
