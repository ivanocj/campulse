package com.ivanocj.cameras.availability.presentation.swing;

import com.ivanocj.cameras.availability.application.logging.LogContext;
import com.ivanocj.cameras.availability.application.model.BatchEntry;
import com.ivanocj.cameras.availability.application.model.BatchResult;
import com.ivanocj.cameras.availability.application.model.ProbeRequest;
import com.ivanocj.cameras.availability.application.model.ProbeResult;
import com.ivanocj.cameras.availability.application.model.ServerProfile;
import com.ivanocj.cameras.availability.application.port.ProbeRunner;
import com.ivanocj.cameras.availability.application.port.ServerConfigurationProvider;
import com.ivanocj.cameras.availability.application.service.BatchAvailabilityService;
import com.ivanocj.cameras.availability.domain.model.TransportProtocol;
import com.ivanocj.cameras.availability.presentation.AvailabilityPresenter;
import com.ivanocj.cameras.availability.presentation.AvailabilityPresenter.FormInput;
import com.ivanocj.cameras.availability.presentation.AvailabilityPresenter.InputField;
import com.ivanocj.cameras.availability.presentation.AvailabilityPresenter.InvalidInput;
import com.ivanocj.cameras.availability.presentation.AvailabilityPresenter.ResultView;
import com.ivanocj.cameras.availability.presentation.swing.FormMemory.FormState;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.Scrollable;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.text.JTextComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AvailabilityPanel extends JPanel implements AutoCloseable, Scrollable {
    private static final Logger LOG = LoggerFactory.getLogger(AvailabilityPanel.class);
    private static final int MAX_LOG_LINES = 2000;
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final ProbeRunner runner;
    private final AvailabilityPresenter presenter;
    private final BatchAvailabilityService batchService;
    private final FormMemory memory;
    private final Consumer<Optional<Path>> credentialSelection;
    private final JButton credentialsFile = new JButton("Select Yoosee account file…");
    private final JButton clearCredentials = new JButton("Clear account file");
    private final JTextField did = new JTextField(24);
    private final JTextArea identifierHelp = hint("identifierHelp", "", 2);
    private final JTextArea accountHelp = hint("accountHelp",
            "Selecting a file does not validate it. Use authorized account credentials, not a camera password.", 2);
    private final JTextArea batchHelp = hint("batchHelp",
            "Batch matches a known profile per identifier; single-check servers are ignored.\n"
                    + "UTF-8 CSV: Serial,Description · TXT: one identifier per line.\n"
                    + "Yoosee: GWELL:01234 or IOTVIDEO:device-key.", 4);
    private final JTextArea servers = new JTextArea(3, 32);
    private final JTextArea connectionSummary = new JTextArea(2, 28);
    private final JComboBox<ServerProfile> serverProfile;
    private final JComboBox<TransportProtocol> transport = new JComboBox<>(TransportProtocol.values());
    private final JSpinner timeout = new JSpinner(new SpinnerNumberModel(10, 1, 120, 1));
    private final JSpinner batchParallelism = new JSpinner(new SpinnerNumberModel(
            BatchAvailabilityService.DEFAULT_PARALLELISM, 1, BatchAvailabilityService.MAX_PARALLELISM, 1));
    private final JCheckBox lanSearch = new JCheckBox("Search local network", true);
    private final JButton check = new JButton("Check availability");
    private final Action openBatchAction = new AbstractAction("Open batch file & test…") {
        @Override
        public void actionPerformed(ActionEvent event) {
            if (!busy) {
                chooseBatchFile.get().ifPresent(AvailabilityPanel.this::startBatch);
            }
        }
    };
    private final JButton batchCheck = new JButton(openBatchAction);
    private final JMenuBar menuBar = new JMenuBar();
    private final Action cancelAction = new AbstractAction("Cancel") {
        @Override
        public void actionPerformed(ActionEvent event) {
            if (busy) {
                LOG.info("event=cancellation_requested");
                cancellation.set(true);
                show(AvailabilityPresenter.cancelling());
            }
        }
    };
    private final JButton cancel = new JButton(cancelAction);
    private final BatchResultsTableModel batchTable = new BatchResultsTableModel();
    private final JTable batchResults = new JTable(batchTable) {
        @Override
        public String getToolTipText(MouseEvent event) {
            int row = rowAtPoint(event.getPoint());
            int column = columnAtPoint(event.getPoint());
            return row >= 0 && column >= 0 ? getValueAt(row, column).toString() : null;
        }
    };
    private final BatchResultsInspection batchInspection = new BatchResultsInspection(batchResults, batchTable);
    private final JLabel batchSummary = new JLabel("Batch results");
    private final JPanel batchCard = card("03 / BATCH RESULTS")
            .add(batchInspection, 8).add(new JScrollPane(batchResults), 8).add(batchSummary, 8).panel;
    private final JTextField uidPrefix = new JTextField("FFFF", 8);
    private final JTextField uidSerial = new JTextField(10);
    private final JButton uidGenerate = new JButton("Build HiChip DID");
    private final JTextArea resultContext = new JTextArea("No check submitted", 3, 30);
    private final JLabel status = new JLabel();
    private final JProgressBar progress = new JProgressBar();
    private final JTextArea details = new JTextArea(3, 30);
    private final JTextArea recoveryHelp = hint("recoveryHelp", "", 0);
    private final JLabel network = new JLabel();
    private final JLabel deviceEndpoint = new JLabel();
    private final JLabel peer = new JLabel();
    private final JLabel publicEndpoint = new JLabel();
    private final JLabel elapsed = new JLabel();
    private final JTextArea p2pDetails = new JTextArea(6, 30);
    private final JTextArea log = new JTextArea(8, 80);
    private final JButton clearLog = new JButton("Clear");
    private final JLabel profile = new JLabel();
    private final FieldFeedback identifierFeedback;
    private final FieldFeedback serversFeedback;
    private final FieldFeedback prefixFeedback;
    private final FieldFeedback serialFeedback;
    private CollapsibleSection connectionSettings;
    private CollapsibleSection hichipSection;
    /** Asks before a chosen profile overwrites a server list the user edited. */
    Predicate<ServerProfile> confirmServerReplacement = this::askToReplaceServers;
    /** File selection is separate from background import/testing so headless views can supply a path. */
    Supplier<Optional<Path>> chooseBatchFile = this::askForBatchFile;
    Supplier<Optional<Path>> chooseCredentialsFile = this::askForCredentialsFile;
    private AtomicBoolean cancellation = new AtomicBoolean();
    private ServerProfile selectedProfile;
    private String appliedServerText = "";
    private String batchFileName = "";
    private boolean adjustingProfile;
    private boolean busy;
    private boolean displayingBatchRow;
    private ResultView batchOverview = AvailabilityPresenter.idle();

    public AvailabilityPanel(ProbeRunner runner, ServerConfigurationProvider configurations) {
        this(runner, configurations, FormMemory.NONE);
    }

    public AvailabilityPanel(ProbeRunner runner, ServerConfigurationProvider configurations, FormMemory memory) {
        this(runner, configurations, memory, ignored -> { });
    }

    public AvailabilityPanel(ProbeRunner runner, ServerConfigurationProvider configurations, FormMemory memory,
                             Consumer<Optional<Path>> credentialSelection) {
        super(new BorderLayout(0, 8));
        this.runner = runner;
        this.presenter = new AvailabilityPresenter(configurations);
        this.batchService = new BatchAvailabilityService(runner, configurations);
        this.memory = memory;
        this.credentialSelection = credentialSelection;
        serverProfile = new JComboBox<>(configurations.profiles().toArray(ServerProfile[]::new));
        setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
        setBackground(AvailabilityTheme.BACKGROUND);
        nameComponents();
        configureComponents();
        identifierFeedback = new FieldFeedback(did, did);
        serversFeedback = new FieldFeedback(servers, new JScrollPane(servers));
        prefixFeedback = new FieldFeedback(uidPrefix, uidPrefix);
        serialFeedback = new FieldFeedback(uidSerial, uidSerial);
        add(hero(), BorderLayout.NORTH);
        add(body(), BorderLayout.CENTER);
        add(diagnostics(), BorderLayout.SOUTH);
        new KeyboardScrollSupport(this).attach(this);
        bindActions();
        if (serverProfile.getItemCount() > 0) {
            applyProfile(serverProfile.getItemAt(0));
        }
        restore(memory.load());
        updateProtocolHelp();
        show(AvailabilityPresenter.idle());
        setBusy(false);
    }

    @Override
    public void addNotify() {
        super.addNotify();
        var root = SwingUtilities.getRootPane(this);
        if (root != null) {
            root.setDefaultButton(check);
        }
    }

    @Override
    public void close() {
        if (busy) { LOG.info("event=window_close_requested active_operation=true"); }
        cancellation.set(true);
    }

    /** Menu actions share the same enabled state and file-opening behavior as the form controls. */
    public JMenuBar menuBar() {
        return menuBar;
    }

    /** Requests initial focus only after the containing window has opened. */
    public void focusIdentifier() {
        did.requestFocusInWindow();
    }

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return new Dimension(960, 600);
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return false;
    }

    @Override
    public void setBounds(int x, int y, int width, int height) {
        boolean widthChanged = width != getWidth();
        super.setBounds(x, y, width, height);
        if (widthChanged) {
            invalidateLayout(this);
            revalidate();
        }
    }

    private static void invalidateLayout(Container container) {
        container.invalidate();
        for (var child : container.getComponents()) {
            if (child instanceof Container nested) { invalidateLayout(nested); }
        }
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
        return getFontMetrics(getFont()).getHeight();
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
        int unit = getScrollableUnitIncrement(visibleRect, orientation, direction);
        return Math.max(unit, (orientation == SwingConstants.VERTICAL ? visibleRect.height : visibleRect.width) - unit);
    }

    private void nameComponents() {
        did.setName("did");
        servers.setName("servers");
        connectionSummary.setName("connectionSummary");
        serverProfile.setName("serverProfile");
        transport.setName("transport");
        timeout.setName("timeout");
        batchParallelism.setName("batchParallelism");
        lanSearch.setName("lanSearch");
        credentialsFile.setName("credentialsFile");
        clearCredentials.setName("clearCredentials");
        check.setName("check");
        batchCheck.setName("batchCheck");
        menuBar.setName("menuBar");
        cancel.setName("cancel");
        batchResults.setName("batchResults");
        batchSummary.setName("batchSummary");
        batchCard.setName("batchCard");
        uidPrefix.setName("uidPrefix");
        uidSerial.setName("uidSerial");
        uidGenerate.setName("uidGenerate");
        resultContext.setName("resultContext");
        status.setName("status");
        progress.setName("progress");
        details.setName("details");
        network.setName("network");
        deviceEndpoint.setName("deviceEndpoint");
        peer.setName("peer");
        publicEndpoint.setName("publicEndpoint");
        elapsed.setName("elapsed");
        p2pDetails.setName("p2pDetails");
        log.setName("log");
        clearLog.setName("clearLog");
        profile.setName("profile");
    }

    private void configureComponents() {
        servers.setLineWrap(true);
        servers.setWrapStyleWord(true);
        connectionSummary.setEditable(false);
        connectionSummary.setLineWrap(true);
        connectionSummary.setWrapStyleWord(false);
        connectionSummary.setOpaque(false);
        connectionSummary.setFont(UIManager.getFont("Label.font"));
        connectionSummary.getAccessibleContext().setAccessibleName("Selected connection settings");
        if (serverProfile.getItemCount() > 0) { serverProfile.setPrototypeDisplayValue(serverProfile.getItemAt(0)); }
        serverProfile.setMinimumSize(new Dimension(0, serverProfile.getPreferredSize().height));
        for (var input : List.of(did, servers, timeout, transport, batchParallelism, uidPrefix, uidSerial)) {
            input.setMinimumSize(new Dimension(0, input.getPreferredSize().height));
        }
        resultContext.setRows(2);
        details.setRows(2);
        log.setRows(5);
        batchHelp.setRows(3);
        servers.setToolTipText("Blank allows PPCS/XQ/GWell LAN-only checks. IoTVideo is remote only. Yoosee list servers default to port 51701 and require authorized account credentials.");
        credentialsFile.setToolTipText("Select a private account properties file; credentials are read during remote checks. Contents and selection are not saved to preferences or logs.");
        check.setBackground(AvailabilityTheme.ACCENT);
        check.setForeground(AvailabilityTheme.ON_ACCENT);
        openBatchAction.putValue(Action.SHORT_DESCRIPTION,
                "Open a UTF-8 CSV or TXT file and immediately start testing. Each identifier uses its known profile, not the single-check servers.");
        batchParallelism.setToolTipText("Concurrent devices in a batch (1–16). Use 1 for sequential checks; repeated DIDs never overlap.");
        int shortcutMask = GraphicsEnvironment.isHeadless() ? InputEvent.CTRL_DOWN_MASK
                : Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        openBatchAction.putValue(Action.ACCELERATOR_KEY, KeyStroke.getKeyStroke(KeyEvent.VK_O, shortcutMask));
        openBatchAction.putValue(Action.MNEMONIC_KEY, KeyEvent.VK_O);
        cancelAction.putValue(Action.ACCELERATOR_KEY, KeyStroke.getKeyStroke(KeyEvent.VK_PERIOD, shortcutMask));
        cancelAction.putValue(Action.MNEMONIC_KEY, KeyEvent.VK_A);
        cancelAction.putValue(Action.SHORT_DESCRIPTION,
                "Request cancellation (Ctrl/Cmd+.); inputs stay locked until cleanup finishes.");
        getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
                (KeyStroke) cancelAction.getValue(Action.ACCELERATOR_KEY), "cancelOperation");
        getActionMap().put("cancelOperation", cancelAction);
        lanSearch.setMnemonic(KeyEvent.VK_N);
        credentialsFile.setMnemonic(KeyEvent.VK_E);
        clearCredentials.setMnemonic(KeyEvent.VK_U);
        check.setMnemonic(KeyEvent.VK_C);
        uidGenerate.setMnemonic(KeyEvent.VK_B);
        clearLog.setMnemonic(KeyEvent.VK_L);
        var fileMenu = new JMenu("File");
        fileMenu.setMnemonic(KeyEvent.VK_F);
        var open = new JMenuItem(openBatchAction);
        open.setName("openBatchFile");
        fileMenu.add(open);
        menuBar.add(fileMenu);
        batchResults.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        batchResults.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        batchResults.setPreferredScrollableViewportSize(new Dimension(0, batchResults.getRowHeight() * 5));
        int[] widths = {55, 190, 200, 235, 85, 210, 210, 190, 200, 200, 330, 100};
        for (int column = 0; column < widths.length; column++) {
            batchResults.getColumnModel().getColumn(column).setPreferredWidth(widths[column]);
        }
        // Keep returned device addresses visible beside the outcome; deployment/diagnostic columns can scroll.
        var leadingColumns = List.of("Line", "Device identifier", "Description", "Result", "Device IP / port", "Connection mode");
        for (int column = 0; column < leadingColumns.size(); column++) {
            batchResults.moveColumn(batchResults.getColumnModel().getColumnIndex(leadingColumns.get(column)), column);
        }
        batchSummary.setFont(batchSummary.getFont().deriveFont(12f));
        batchSummary.setForeground(AvailabilityTheme.MUTED);
        batchCard.setVisible(false);
        lanSearch.setOpaque(false);
        resultContext.setEditable(false);
        resultContext.setLineWrap(true);
        resultContext.setWrapStyleWord(true);
        resultContext.setOpaque(false);
        var contextFont = UIManager.getFont("Label.font");
        resultContext.setFont(contextFont.deriveFont(Font.BOLD, contextFont.getSize2D() + 2f));
        resultContext.getAccessibleContext().setAccessibleName("Displayed result owner");
        status.setFont(status.getFont().deriveFont(Font.BOLD, 23f));
        details.setEditable(false);
        details.setLineWrap(true);
        details.setWrapStyleWord(true);
        details.setOpaque(false);
        deviceEndpoint.setToolTipText("Returned device addresses. Reachability still requires a DID-matched alive acknowledgement.");
        peer.setToolTipText("The selected session endpoint is a relay IP in UDP relay mode, not the device IP.");
        publicEndpoint.setToolTipText("This client's public IP and UDP port, as observed by the server; not the camera's IP.");
        p2pDetails.setEditable(false);
        p2pDetails.setFont(new Font(Font.MONOSPACED, Font.PLAIN, UIManager.getFont("Label.font").getSize()));
        p2pDetails.setLineWrap(true);
        p2pDetails.setWrapStyleWord(true);
        log.setEditable(false);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, UIManager.getFont("Label.font").getSize()));
        profile.setFont(profile.getFont().deriveFont(12f));
        profile.setForeground(AvailabilityTheme.MUTED);
    }

    private void bindActions() {
        credentialsFile.addActionListener(event -> chooseCredentialsFile.get().ifPresent(path -> {
            credentialSelection.accept(Optional.of(path));
            credentialsFile.setText("Account file selected");
        }));
        clearCredentials.addActionListener(event -> {
            credentialSelection.accept(Optional.empty());
            credentialsFile.setText("Select Yoosee account file…");
        });
        check.addActionListener(event -> startCheck());
        batchInspection.onFilterChanged(() -> {
            if (displayingBatchRow) { onBatchSelectionChanged(); }
        });
        batchResults.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && !batchInspection.isFiltering()) {
                onBatchSelectionChanged();
            }
        });
        uidGenerate.addActionListener(event -> buildHichipDid());
        uidSerial.addActionListener(event -> buildHichipDid());
        clearLog.addActionListener(event -> log.setText(""));
        serverProfile.addActionListener(event -> onProfileChosen());
        transport.addActionListener(event -> {
            identifierFeedback.clear();
            serversFeedback.clear();
            updateProtocolHelp();
        });
        timeout.addChangeListener(event -> updateConnectionSummary());
        lanSearch.addItemListener(event -> {
            serversFeedback.clear();
            updateConnectionSummary();
        });
        onEdit(did, identifierFeedback::clear);
        onEdit(servers, () -> {
            serversFeedback.clear();
            identifierFeedback.clear(); // An encoded configuration can change the identifier's effective protocol.
            updateConnectionSummary();
        });
        Runnable clearBuilderErrors = () -> {
            prefixFeedback.clear();
            serialFeedback.clear();
        };
        onEdit(uidPrefix, clearBuilderErrors);
        onEdit(uidSerial, clearBuilderErrors);
    }

    private static void onEdit(JTextComponent input, Runnable edited) {
        input.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { edited.run(); }
            @Override public void removeUpdate(DocumentEvent event) { edited.run(); }
            @Override public void changedUpdate(DocumentEvent event) { edited.run(); }
        });
    }

    private void onBatchSelectionChanged() {
        if (busy) { return; }
        int selected = batchResults.getSelectedRow();
        var result = selected < 0 ? Optional.<BatchResult>empty()
                : batchTable.resultAt(batchResults.convertRowIndexToModel(selected));
        if (result.isPresent()) {
            var row = result.orElseThrow();
            setResultContext(AvailabilityPresenter.batchRowContext(batchFileName, row.entry()));
            show(AvailabilityPresenter.batchResult(row));
            displayingBatchRow = true;
        } else if (displayingBatchRow) {
            setResultContext(AvailabilityPresenter.batchContext(batchFileName));
            show(batchOverview);
        }
    }

    private void updateProtocolHelp() {
        profile.setText(AvailabilityPresenter.transportDescription(selectedTransport()));
        var help = AvailabilityPresenter.identifierHelp(selectedTransport());
        identifierHelp.setText(help);
        did.setToolTipText(help);
        did.getAccessibleContext().setAccessibleDescription(help);
        updateConnectionSummary();
    }

    private void updateConnectionSummary() {
        String text = "Selected: " + selectedTransport() + " · " + timeout.getValue() + " s/device · LAN search "
                + (lanSearch.isSelected() ? "on" : "off") + "\nServers: "
                + (servers.getText().isBlank() ? "blank" : servers.getText().strip());
        connectionSummary.setText(text);
        connectionSummary.setCaretPosition(0);
        connectionSummary.setToolTipText("Selected settings; encoded server configurations can override protocol when submitted. " + text);
        connectionSummary.getAccessibleContext().setAccessibleDescription(text);
    }

    private JComponent hero() {
        var hero = new Column();
        var heading = new JLabel("Device availability");
        heading.setName("heading");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 28f));
        var subtitle = hint("subtitle", "Camera checks · reachability, LAN discovery, and server-reported presence.", 1);
        return hero.add(heading, 0).add(subtitle, 4).panel;
    }

    private JComponent cards() {
        return new ResponsiveCards(connectionCard(), resultCard(),
                () -> getWidth() - getInsets().left - getInsets().right);
    }

    private JComponent connectionCard() {
        var overrides = new Column()
                .field("Servers · single check", servers, serversFeedback, 0, 0, KeyEvent.VK_S)
                .add(pair(new Column().field("Timeout per device (seconds)", timeout, 0, KeyEvent.VK_T).panel,
                        new Column().field("Protocol / device family", transport, 0, KeyEvent.VK_R).panel), 8)
                .add(lanSearch, 8).add(profile, 6).panel;
        var profileRow = new JPanel(new BorderLayout(8, 0));
        profileRow.setOpaque(false);
        profileRow.add(inputLabel("Server profile", serverProfile, KeyEvent.VK_P), BorderLayout.WEST);
        profileRow.add(serverProfile, BorderLayout.CENTER);
        connectionSettings = new CollapsibleSection("connectionSettings", "Connection settings", overrides);
        var connection = card("01 / SINGLE CHECK")
                .field("Device identifier", did, identifierFeedback, 8, 0, KeyEvent.VK_D)
                .add(identifierHelp, 4).add(profileRow, 8)
                .add(textScroll(connectionSummary), 8)
                .add(pair(check, cancel), 8)
                .add(connectionSettings, 8).panel;
        connection.setName("connectionCard");
        return connection;
    }

    private JComponent resultCard() {
        var evidence = new Column()
                .field("Session endpoint · device or relay", peer, 0)
                .field("Client public endpoint", publicEndpoint, 8)
                .field("P2P information · select a batch row to inspect", p2pDetails, new JScrollPane(p2pDetails), 8, 0).panel;
        var results = card("02 / RESULT")
                .add(textScroll(resultContext), 8).add(status, 8).add(progress, 8)
                .add(textScroll(details), 8).add(recoveryHelp, 0)
                .add(pair(new Column().field("Server health", network, 0).panel,
                        new Column().field("Elapsed", elapsed, 0).panel), 8)
                .field("Device IP / port", deviceEndpoint, 8)
                .add(new CollapsibleSection("evidence", "Detailed evidence", evidence), 8).panel;
        results.setName("resultCard");
        return results;
    }

    private JComponent batchControls() {
        var row = new JPanel(new BorderLayout(12, 0));
        row.setOpaque(false);
        row.add(batchCheck, BorderLayout.CENTER);
        row.add(new Column().field("Parallel batch checks", batchParallelism, 0, KeyEvent.VK_K).panel, BorderLayout.EAST);
        var account = new Column().add(pair(credentialsFile, clearCredentials), 0).add(accountHelp, 6).panel;
        return card("BATCH & SHARED ACCOUNT")
                .add(row, 8).add(batchHelp, 6)
                .add(new CollapsibleSection("accountSettings", "Yoosee account · single & batch", account), 6).panel;
    }

    private JComponent hichipTools() {
        var tools = new Column()
                .add(pair(new Column().field("HiChip prefix", uidPrefix, prefixFeedback, 0, 0, KeyEvent.VK_H).panel,
                         new Column().field("HiChip serial", uidSerial, serialFeedback, 0, 0, KeyEvent.VK_I).panel), 0)
                .add(uidGenerate, 8).panel;
        hichipSection = new CollapsibleSection("hichipTools", "HiChip DID tools", tools);
        return hichipSection;
    }

    private JComponent body() {
        return new Column().add(cards(), 0).add(batchControls(), 12).add(batchCard, 12)
                .add(hichipTools(), 8).glue().panel;
    }

    private JComponent diagnostics() {
        var header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.add(new JLabel("04 / DIAGNOSTICS"), BorderLayout.WEST);
        header.add(clearLog, BorderLayout.EAST);
        var diagnostics = new JPanel(new BorderLayout(0, 8));
        diagnostics.setOpaque(false);
        diagnostics.add(header, BorderLayout.NORTH);
        diagnostics.add(new JScrollPane(log), BorderLayout.CENTER);
        return new CollapsibleSection("diagnostics", "Diagnostics", diagnostics);
    }

    private static JScrollPane textScroll(JTextArea text) {
        var scroll = new JScrollPane(text);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        return scroll;
    }

    private void onProfileChosen() {
        if (adjustingProfile) {
            return;
        }
        var chosen = (ServerProfile) serverProfile.getSelectedItem();
        if (chosen == null) {
            return;
        }
        if (serversEdited() && !confirmServerReplacement.test(chosen)) {
            adjustingProfile = true;
            serverProfile.setSelectedItem(selectedProfile);
            adjustingProfile = false;
            return;
        }
        applyProfile(chosen);
    }

    private void applyProfile(ServerProfile chosen) {
        selectedProfile = chosen;
        appliedServerText = chosen.serverText();
        servers.setText(appliedServerText);
        transport.setSelectedItem(chosen.protocol());
    }

    private boolean serversEdited() {
        return !servers.getText().strip().equals(appliedServerText.strip());
    }

    private boolean askToReplaceServers(ServerProfile chosen) {
        return GraphicsEnvironment.isHeadless() || JOptionPane.showConfirmDialog(this,
                "Replace the edited server list with “" + chosen.name() + "”?", "Replace servers",
                JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION;
    }

    private void restore(Optional<FormState> saved) {
        saved.ifPresent(state -> {
            did.setText(state.did());
            for (int i = 0; i < serverProfile.getItemCount(); i++) {
                if (serverProfile.getItemAt(i).name().equals(state.profile())) {
                    adjustingProfile = true;
                    serverProfile.setSelectedIndex(i);
                    adjustingProfile = false;
                    selectedProfile = serverProfile.getItemAt(i);
                    appliedServerText = selectedProfile.serverText();
                }
            }
            servers.setText(state.servers());
            transport.setSelectedItem(state.protocol());
            timeout.setValue(Math.clamp(state.timeoutSeconds(), 1, 120));
            lanSearch.setSelected(state.lanSearch());
        });
    }

    private void buildHichipDid() {
        prefixFeedback.clear();
        serialFeedback.clear();
        try {
            did.setText(AvailabilityPresenter.hichipDid(uidPrefix.getText(), uidSerial.getText()));
        } catch (InvalidInput invalid) {
            setResultContext("HiChip DID builder");
            show(AvailabilityPresenter.invalid(invalid.getMessage()));
            showInputError(invalid);
        }
    }

    private void startCheck() {
        if (busy) {
            return;
        }
        identifierFeedback.clear();
        serversFeedback.clear();
        ProbeRequest request;
        try {
            request = presenter.request(new FormInput(did.getText(), servers.getText(),
                    ((Number) timeout.getValue()).intValue(), lanSearch.isSelected(), selectedTransport()));
        } catch (InvalidInput invalid) {
            LOG.warn("event=input_rejected operation=single_check");
            setResultContext("Single check · input not submitted");
            show(AvailabilityPresenter.invalid(invalid.getMessage()));
            showInputError(invalid);
            return;
        }
        transport.setSelectedItem(request.protocol());
        memory.save(new FormState(request.identifier().value(), selectedProfile == null ? "" : selectedProfile.name(),
                servers.getText().strip(), request.protocol(), (int) request.timeout().toSeconds(), request.lanSearch()));
        setBusy(true);
        setResultContext(AvailabilityPresenter.singleContext(request));
        show(AvailabilityPresenter.checking());
        appendLog("── " + LocalTime.now().format(LOG_TIME) + " · " + request.identifier().value() + " · "
                + request.protocol() + " ──");
        cancellation = new AtomicBoolean();
        var token = cancellation;
        new SwingWorker<ProbeResult, String>() {
            private final EdtEventDelivery<String> delivery = new EdtEventDelivery<>(messages ->
                    appendLogs(messages.stream().map(message -> LocalTime.now().format(LOG_TIME) + "  " + message).toList()));

            @Override
            protected ProbeResult doInBackground() throws Exception {
                try (var operation = LogContext.probe()) {
                    LOG.debug("event=single_check_worker_started");
                    try {
                        return runner.check(request, token::get, this::publish);
                    } catch (Exception | Error failed) {
                        logWorkerFailure("single_check", failed);
                        throw failed;
                    }
                }
            }

            @Override
            protected void process(List<String> messages) {
                delivery.submit(messages);
            }

            @Override
            protected void done() {
                delivery.finish(this::showCompleted);
            }

            private void showCompleted() {
                try {
                    show(AvailabilityPresenter.completed(get()));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    show(AvailabilityPresenter.failed(interrupted));
                } catch (ExecutionException failed) {
                    show(AvailabilityPresenter.failed(failed.getCause()));
                } finally {
                    setBusy(false);
                }
            }
        }.execute();
    }

    private Optional<Path> askForBatchFile() {
        var chooser = new JFileChooser();
        chooser.setDialogTitle("Open serials for batch testing");
        chooser.setFileFilter(new FileNameExtensionFilter("Batch files (*.csv, *.txt)", "csv", "txt"));
        return chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION
                ? Optional.of(chooser.getSelectedFile().toPath()) : Optional.empty();
    }

    private Optional<Path> askForCredentialsFile() {
        var chooser = new JFileChooser();
        chooser.setDialogTitle("Select user-authorized Yoosee account credentials (not a camera password)");
        chooser.setFileFilter(new FileNameExtensionFilter("Private account file (*.properties)", "properties"));
        return chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION
                ? Optional.of(chooser.getSelectedFile().toPath()) : Optional.empty();
    }

    private void startBatch(Path file) {
        long started = System.nanoTime();
        batchFileName = file.getFileName().toString();
        boolean csv = file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".csv");
        var perCodeTimeout = Duration.ofSeconds(((Number) timeout.getValue()).intValue());
        int parallelism = ((Number) batchParallelism.getValue()).intValue();
        boolean searchLan = lanSearch.isSelected();
        cancellation = new AtomicBoolean();
        var token = cancellation;
        setBusy(true);
        setResultContext(AvailabilityPresenter.batchContext(batchFileName));
        batchTable.setEntries(List.of());
        batchCard.setVisible(true);
        revalidate();
        batchSummary.setText("Loading " + file.getFileName() + "… · up to " + parallelism + " parallel");
        show(AvailabilityPresenter.batchLoading());
        appendLog("── " + LocalTime.now().format(LOG_TIME) + " · Batch · " + file.getFileName()
                + " · up to " + parallelism + " parallel · " + perCodeTimeout.toSeconds() + " s/device ──");
        new SwingWorker<List<BatchResult>, BatchEvent>() {
            private int total;
            private int processed;
            private final EdtEventDelivery<BatchEvent> delivery = new EdtEventDelivery<>(this::renderEvents);

            @Override
            protected List<BatchResult> doInBackground() throws Exception {
                try (var operation = LogContext.batch()) {
                    LOG.info("event=batch_import_started format={}", csv ? "CSV" : "TEXT");
                    try {
                        List<BatchEntry> entries;
                        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                            entries = csv ? batchService.readCsv(reader, token::get) : batchService.read(reader, token::get);
                        }
                        LOG.info("event=batch_import_completed rows={} testable_rows={}", entries.size(),
                                entries.stream().filter(BatchEntry::canCheck).count());
                        publish(new BatchLoaded(entries));
                        return batchService.check(entries, perCodeTimeout, searchLan, parallelism, token::get,
                                result -> publish(new BatchCompleted(result)), message -> publish(new BatchDiagnostic(message)));
                    } catch (Exception | Error failed) {
                        logWorkerFailure("batch", failed);
                        throw failed;
                    }
                }
            }

            @Override
            protected void process(List<BatchEvent> events) {
                delivery.submit(events);
            }

            private void renderEvents(List<BatchEvent> events) {
                var results = new ArrayList<BatchResult>();
                var lines = new ArrayList<String>();
                boolean imported = false;
                for (var event : events) {
                    switch (event) {
                        case BatchLoaded loaded -> {
                            batchTable.setEntries(loaded.entries());
                            total = loaded.entries().size();
                            progress.setIndeterminate(false);
                            progress.setMaximum(total);
                            progress.setStringPainted(true);
                            imported = true;
                        }
                        case BatchCompleted completed -> {
                            results.add(completed.result());
                            processed++;
                            var result = completed.result();
                            lines.add(LocalTime.now().format(LOG_TIME) + "  Line " + result.entry().lineNumber()
                                    + " · " + result.entry().code() + " · " + AvailabilityPresenter.batchOutcome(result)
                                    + " · " + result.detail());
                        }
                        case BatchDiagnostic diagnostic ->
                                lines.add(LocalTime.now().format(LOG_TIME) + "  " + diagnostic.message());
                    }
                }
                batchTable.updateAll(results);
                if (imported || !results.isEmpty()) { updateProgress(); }
                appendLogs(lines);
            }

            private void updateProgress() {
                progress.setValue(processed);
                progress.setString(processed + " / " + total);
                batchSummary.setText(file.getFileName() + " · " + processed + " / " + total
                        + " processed · up to " + parallelism + " parallel");
                // Counts/rows keep updating, but progress must not obscure the cancellation acknowledgment.
                if (!token.get()) {
                    show(AvailabilityPresenter.batchChecking(processed, total));
                }
            }

            @Override
            protected void done() {
                delivery.finish(this::showCompleted);
            }

            private void showCompleted() {
                try {
                    var results = get();
                    var summary = AvailabilityPresenter.batchCompleted(results,
                            Duration.ofNanos(System.nanoTime() - started));
                    batchOverview = summary;
                    batchSummary.setText(file.getFileName() + " · up to " + parallelism + " parallel · " + summary.detail());
                    show(summary);
                    appendLog(LocalTime.now().format(LOG_TIME) + "  " + summary.headline() + " · " + summary.detail());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    showBatchProblem(interrupted);
                } catch (ExecutionException failed) {
                    showBatchProblem(failed.getCause());
                } finally {
                    setBusy(false);
                }
            }
        }.execute();
    }

    private void showBatchProblem(Throwable cause) {
        var view = cause instanceof CancellationException ? AvailabilityPresenter.batchCancelled(cause.getMessage())
                : cause instanceof IllegalArgumentException ? AvailabilityPresenter.batchInvalid(cause.getMessage())
                : AvailabilityPresenter.batchFailed(cause);
        batchSummary.setText(cause instanceof CancellationException ? "Batch cancelled. No codes were tested."
                : "Batch could not be completed.");
        batchOverview = view;
        show(view);
        appendLog(LocalTime.now().format(LOG_TIME) + "  " + view.headline() + " · " + view.detail());
    }

    private static void logWorkerFailure(String operation, Throwable failure) {
        if (failure instanceof CancellationException) {
            LOG.info("event=worker_cancelled operation={}", operation);
        } else if (failure instanceof IllegalArgumentException) {
            LOG.warn("event=input_rejected operation={}", operation, failure);
        } else {
            LOG.error("event=worker_failed operation={}", operation, failure);
        }
    }

    private sealed interface BatchEvent permits BatchLoaded, BatchCompleted, BatchDiagnostic {}
    private record BatchLoaded(List<BatchEntry> entries) implements BatchEvent {}
    private record BatchCompleted(BatchResult result) implements BatchEvent {}
    private record BatchDiagnostic(String message) implements BatchEvent {}

    private void showInputError(InvalidInput invalid) {
        for (var field : invalid.fields()) {
            switch (field) {
                case SERVERS -> connectionSettings.expand();
                case HICHIP_PREFIX, HICHIP_SERIAL -> hichipSection.expand();
                default -> { }
            }
            feedbackFor(field).showError(invalid.getMessage());
        }
        feedbackFor(invalid.fields().getFirst()).focusInput();
    }

    private FieldFeedback feedbackFor(InputField field) {
        return switch (field) {
            case IDENTIFIER -> identifierFeedback;
            case SERVERS -> serversFeedback;
            case HICHIP_PREFIX -> prefixFeedback;
            case HICHIP_SERIAL -> serialFeedback;
        };
    }

    private void show(ResultView view) {
        status.setText(view.headline());
        var tone = AvailabilityTheme.color(view.tone());
        status.setForeground(tone != null ? tone : UIManager.getColor("Label.foreground"));
        details.setText(view.detail());
        recoveryHelp.setText(view.recovery().isEmpty() ? "" : "Next step: " + view.recovery());
        recoveryHelp.setVisible(!view.recovery().isEmpty());
        network.setText(view.serverHealth());
        deviceEndpoint.setText(view.deviceEndpoint());
        peer.setText(view.peer());
        publicEndpoint.setText(view.publicEndpoint());
        elapsed.setText(view.elapsed());
        p2pDetails.setText(view.p2pInformation());
        p2pDetails.setCaretPosition(0);
    }

    private void setResultContext(String context) {
        displayingBatchRow = false;
        resultContext.setText(context);
        resultContext.setCaretPosition(0);
        resultContext.setToolTipText(context);
        resultContext.getAccessibleContext().setAccessibleDescription(context);
    }

    private void appendLog(String line) {
        appendLogs(List.of(line));
    }

    private void appendLogs(List<String> lines) {
        if (lines.isEmpty()) { return; }
        log.append(String.join("\n", lines) + "\n");
        var root = log.getDocument().getDefaultRootElement();
        // The document always ends with an empty line after the final newline.
        int excess = root.getElementCount() - 1 - MAX_LOG_LINES;
        if (excess > 0) {
            log.replaceRange("", 0, root.getElement(excess - 1).getEndOffset());
        }
        log.setCaretPosition(log.getDocument().getLength());
    }

    private TransportProtocol selectedTransport() {
        return (TransportProtocol) transport.getSelectedItem();
    }

    private void setBusy(boolean busy) {
        this.busy = busy;
        openBatchAction.setEnabled(!busy);
        for (JComponent input : List.of(did, servers, transport, check, timeout, batchParallelism, lanSearch, uidPrefix, uidSerial,
                uidGenerate, credentialsFile, clearCredentials)) {
            input.setEnabled(!busy);
        }
        serverProfile.setEnabled(!busy && serverProfile.getItemCount() > 0);
        cancelAction.setEnabled(busy);
        progress.setIndeterminate(busy);
        progress.setStringPainted(false);
        progress.setValue(0);
        progress.setVisible(busy);
    }

    private static Column card(String title) {
        var card = new Column();
        card.panel.setOpaque(true);
        card.panel.setBackground(AvailabilityTheme.CARD);
        card.panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(AvailabilityTheme.CARD_BORDER, 1, true),
                BorderFactory.createEmptyBorder(12, 12, 12, 12)));
        var label = new JLabel(title);
        label.setForeground(AvailabilityTheme.SECTION_TITLE);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 12f));
        return card.add(label, 0);
    }

    private static JPanel pair(Component left, Component right) {
        var pair = new JPanel(new GridLayout(1, 2, 12, 0));
        pair.setOpaque(false);
        pair.add(left);
        pair.add(right);
        return pair;
    }

    private static JLabel inputLabel(String text, JComponent input, int mnemonic) {
        var label = new JLabel(text);
        label.setName(input.getName() + "Label");
        label.setLabelFor(input);
        label.setDisplayedMnemonic(mnemonic);
        label.setForeground(AvailabilityTheme.MUTED);
        return label;
    }

    private static JTextArea hint(String name, String text, int rows) {
        var hint = new JTextArea(text, rows, 28);
        hint.setName(name);
        hint.setEditable(false);
        hint.setFocusable(false);
        hint.setOpaque(false);
        hint.setLineWrap(true);
        hint.setWrapStyleWord(true);
        hint.setFont(UIManager.getFont("Label.font"));
        hint.setForeground(AvailabilityTheme.MUTED);
        return hint;
    }

    /** A single-column form that stretches its rows horizontally and sizes them from their content. */
    private static final class Column {
        final JPanel panel = new JPanel();

        Column() {
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
            panel.setOpaque(false);
        }

        Column add(Component component, int gap) {
            return add(component, gap, 0);
        }

        Column add(Component component, int gap, double weighty) {
            if (gap > 0) { panel.add(Box.createVerticalStrut(gap)); }
            if (weighty > 0) {
                panel.add(component);
            } else {
                var row = new JPanel(new BorderLayout()) {
                    @Override public Dimension getMinimumSize() {
                        return new Dimension(0, getPreferredSize().height);
                    }
                    @Override public Dimension getMaximumSize() {
                        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
                    }
                };
                row.setOpaque(false);
                row.setAlignmentX(Component.LEFT_ALIGNMENT);
                row.add(component, BorderLayout.CENTER);
                panel.add(row);
            }
            return this;
        }

        Column field(String label, JComponent input, int gap) {
            return field(label, input, input, gap, 0);
        }

        Column field(String label, JComponent input, int gap, int mnemonic) {
            return field(label, input, input, gap, 0, mnemonic);
        }

        Column field(String label, JComponent input, Component view, int gap, double weighty) {
            return field(label, input, view, gap, weighty, 0);
        }

        Column field(String label, JComponent input, Component view, int gap, double weighty, int mnemonic) {
            add(inputLabel(label, input, mnemonic), gap);
            return add(view, 6, weighty);
        }

        Column glue() {
            return add(Box.createGlue(), 0, 1);
        }
    }
}
