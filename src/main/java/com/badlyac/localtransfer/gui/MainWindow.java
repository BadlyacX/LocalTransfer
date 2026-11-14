package com.badlyac.localtransfer.gui;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.badlyac.localtransfer.Fmt;
import com.badlyac.localtransfer.LocalTransferApp;
import com.badlyac.localtransfer.NetUtil;
import com.badlyac.localtransfer.Peer;
import com.badlyac.localtransfer.Protocol.FileEntry;
import com.badlyac.localtransfer.Settings;
import com.badlyac.localtransfer.TransferHub;
import com.badlyac.localtransfer.TransferServer;
import com.badlyac.localtransfer.TransferTask;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class MainWindow extends JFrame {
    private final LocalTransferApp app;
    private final DefaultListModel<Peer> peerModel = new DefaultListModel<>();
    private final JList<Peer> peerList = new JList<>(peerModel);
    private final DefaultListModel<Path> fileModel = new DefaultListModel<>();
    private final JList<Path> fileList;
    private final JButton sendBtn = new JButton("傳送給所選裝置");
    private final TaskModel taskModel = new TaskModel();
    private final JTable taskTable = new JTable(taskModel);
    private final JLabel infoLabel = new JLabel();

    public static void launch(Settings settings) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
            }
            try {
                LocalTransferApp app = new LocalTransferApp(settings);
                new MainWindow(app).setVisible(true);
            } catch (Exception e) {
                String msg = e instanceof java.net.BindException
                        ? "連接埠 " + settings.port + " 已被占用（是否已經開了另一個 LocalTransfer？）"
                        : String.valueOf(e);
                JOptionPane.showMessageDialog(null, msg, "無法啟動", JOptionPane.ERROR_MESSAGE);
                System.exit(1);
            }
        });
    }

    private MainWindow(LocalTransferApp app) throws Exception {
        super("LocalTransfer 區網傳檔");
        this.app = app;

        fileList = new JList<>(fileModel) {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                if (fileModel.isEmpty()) {
                    g.setColor(Color.GRAY);
                    String hint = "把檔案或資料夾拖放到這裡";
                    FontMetrics fm = g.getFontMetrics();
                    g.drawString(hint, (getWidth() - fm.stringWidth(hint)) / 2, getHeight() / 2);
                }
            }
        };

        app.hub.addListener(taskModel);
        app.discovery.setListener(peers -> SwingUtilities.invokeLater(() -> refreshPeers(peers)));
        app.shares.setOnChange(() -> {
        });
        if (!app.settings.autoAccept) {
            app.server.setConfirmer(offer -> {
                CompletableFuture<Boolean> f = new CompletableFuture<>();
                SwingUtilities.invokeLater(() -> {
                    toFront();
                    Toolkit.getDefaultToolkit().beep();
                    f.complete(confirmIncoming(offer));
                });
                return f;
            });
        }
        app.start();

        buildUi();
        refreshInfo();
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                app.close();
                dispose();
                System.exit(0);
            }
        });
        setSize(900, 640);
        setLocationRelativeTo(null);
    }

    // ------------------------------------------------------------ layout

    private void buildUi() {
        JPanel top = new JPanel(new BorderLayout(8, 0));
        top.setBorder(BorderFactory.createEmptyBorder(8, 10, 4, 10));
        top.add(infoLabel, BorderLayout.CENTER);
        JPanel topBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JButton rename = new JButton("改名");
        rename.addActionListener(e -> renameDevice());
        JButton folder = new JButton("下載資料夾");
        folder.addActionListener(e -> chooseDownloadDir());
        JButton qr = new JButton("手機 / 網頁連線 (QR)");
        qr.addActionListener(e -> showQrDialog());
        topBtns.add(rename);
        topBtns.add(folder);
        topBtns.add(qr);
        top.add(topBtns, BorderLayout.EAST);

        JSplitPane mid = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, peersPanel(), sendPanel());
        mid.setResizeWeight(0.4);
        JSplitPane main = new JSplitPane(JSplitPane.VERTICAL_SPLIT, mid, transfersPanel());
        main.setResizeWeight(0.55);

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(top, BorderLayout.NORTH);
        getContentPane().add(main, BorderLayout.CENTER);
    }

    private JPanel peersPanel() {
        JPanel p = new JPanel(new BorderLayout(4, 4));
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("區網裝置"), BorderFactory.createEmptyBorder(2, 4, 4, 4)));
        peerList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        peerList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean sel, boolean focus) {
                super.getListCellRendererComponent(list, value, index, sel, focus);
                Peer peer = (Peer) value;
                setText("<html><b>" + escape(peer.name()) + "</b><br><small>" + peer.host() + ":" + peer.port()
                        + "　指紋 " + peer.shortFingerprint() + "</small></html>");
                return this;
            }
        });
        peerList.addListSelectionListener(e -> updateSendEnabled());
        p.add(new JScrollPane(peerList), BorderLayout.CENTER);

        JPanel manual = new JPanel(new BorderLayout(4, 0));
        JTextField host = new JTextField();
        host.setToolTipText("例如 192.168.1.20 或 192.168.1.20:8443");
        JButton add = new JButton("手動加入 IP");
        Runnable doAdd = () -> addManualPeer(host.getText().trim(), host);
        add.addActionListener(e -> doAdd.run());
        host.addActionListener(e -> doAdd.run());
        manual.add(host, BorderLayout.CENTER);
        manual.add(add, BorderLayout.EAST);
        p.add(manual, BorderLayout.SOUTH);
        return p;
    }

    private JPanel sendPanel() {
        JPanel p = new JPanel(new BorderLayout(4, 4));
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("要傳送的檔案"), BorderFactory.createEmptyBorder(2, 4, 4, 4)));

        fileList.setTransferHandler(new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport s) {
                return s.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }

            @Override
            @SuppressWarnings("unchecked")
            public boolean importData(TransferSupport s) {
                if (!canImport(s)) return false;
                try {
                    List<File> files = (List<File>) s.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    files.forEach(f -> addPath(f.toPath()));
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }
        });
        p.add(new JScrollPane(fileList), BorderLayout.CENTER);

        JPanel btns = new JPanel(new GridLayout(2, 1, 0, 4));
        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton addFiles = new JButton("新增檔案");
        addFiles.addActionListener(e -> choose(JFileChooser.FILES_ONLY));
        JButton addDir = new JButton("新增資料夾");
        addDir.addActionListener(e -> choose(JFileChooser.DIRECTORIES_ONLY));
        JButton remove = new JButton("移除所選");
        remove.addActionListener(e -> fileList.getSelectedValuesList().forEach(fileModel::removeElement));
        JButton clear = new JButton("清空");
        clear.addActionListener(e -> fileModel.clear());
        row1.add(addFiles);
        row1.add(addDir);
        row1.add(remove);
        row1.add(clear);

        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        sendBtn.addActionListener(e -> sendSelected());
        JButton share = new JButton("分享清單給手機");
        share.addActionListener(e -> shareToPhone());
        row2.add(sendBtn);
        row2.add(share);
        btns.add(row1);
        btns.add(row2);
        p.add(btns, BorderLayout.SOUTH);

        fileModel.addListDataListener(new javax.swing.event.ListDataListener() {
            public void intervalAdded(javax.swing.event.ListDataEvent e) {
                updateSendEnabled();
            }

            public void intervalRemoved(javax.swing.event.ListDataEvent e) {
                updateSendEnabled();
            }

            public void contentsChanged(javax.swing.event.ListDataEvent e) {
                updateSendEnabled();
            }
        });
        updateSendEnabled();
        return p;
    }

    private JPanel transfersPanel() {
        JPanel p = new JPanel(new BorderLayout(4, 4));
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("傳輸紀錄"), BorderFactory.createEmptyBorder(2, 4, 4, 4)));
        taskTable.setRowHeight(24);
        taskTable.getColumnModel().getColumn(0).setMaxWidth(60);
        taskTable.getColumnModel().getColumn(1).setPreferredWidth(160);
        taskTable.getColumnModel().getColumn(2).setPreferredWidth(240);
        taskTable.getColumnModel().getColumn(3).setPreferredWidth(220);
        taskTable.getColumnModel().getColumn(3).setCellRenderer(new ProgressRenderer());
        taskTable.getColumnModel().getColumn(4).setPreferredWidth(160);
        p.add(new JScrollPane(taskTable), BorderLayout.CENTER);

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton cancel = new JButton("取消所選");
        cancel.addActionListener(e -> {
            for (int row : taskTable.getSelectedRows()) {
                TransferTask t = taskModel.at(taskTable.convertRowIndexToModel(row));
                if (!t.isFinished()) t.cancelRequested = true;
            }
        });
        JButton clearDone = new JButton("清除已結束");
        clearDone.addActionListener(e -> taskModel.removeFinished());
        JButton open = new JButton("開啟下載資料夾");
        open.addActionListener(e -> {
            try {
                Desktop.getDesktop().open(app.settings.downloadDir().toFile());
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "下載資料夾還不存在（收到第一個檔案後就會建立）");
            }
        });
        btns.add(cancel);
        btns.add(clearDone);
        btns.add(open);
        p.add(btns, BorderLayout.SOUTH);
        return p;
    }

    // ------------------------------------------------------------ actions

    private void refreshInfo() {
        List<String> ips = NetUtil.localIPv4();
        String warn = app.discoveryError() == null ? "" : "　<font color=red>（自動發現已停用：" + escape(app.discoveryError()) + "）</font>";
        infoLabel.setText("<html><b>" + escape(app.settings.deviceName()) + "</b>　IP: "
                + (ips.isEmpty() ? "（沒有區網連線）" : String.join(", ", ips)) + "　埠: " + app.settings.port
                + "<br><small>接收資料夾: " + escape(app.settings.downloadDir().toString()) + "</small>" + warn + "</html>");
    }

    private void refreshPeers(List<Peer> peers) {
        String selected = peerList.getSelectedValue() == null ? null : peerList.getSelectedValue().id();
        peerModel.clear();
        peers.forEach(peerModel::addElement);
        if (selected != null) {
            for (int i = 0; i < peerModel.size(); i++) {
                if (peerModel.get(i).id().equals(selected)) peerList.setSelectedIndex(i);
            }
        }
        updateSendEnabled();
    }

    private void updateSendEnabled() {
        sendBtn.setEnabled(!fileModel.isEmpty() && peerList.getSelectedValue() != null);
    }

    private void addPath(Path p) {
        if (!fileModel.contains(p)) fileModel.addElement(p);
    }

    private void choose(int mode) {
        JFileChooser fc = new JFileChooser();
        fc.setFileSelectionMode(mode);
        fc.setMultiSelectionEnabled(true);
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            for (File f : fc.getSelectedFiles()) addPath(f.toPath());
        }
    }

    private void sendSelected() {
        Peer peer = peerList.getSelectedValue();
        if (peer == null || fileModel.isEmpty()) return;
        app.client.sendAsync(peer, Collections.list(fileModel.elements()));
        fileModel.clear();
    }

    private void shareToPhone() {
        if (fileModel.isEmpty()) {
            JOptionPane.showMessageDialog(this, "請先把要分享的檔案加入清單");
            return;
        }
        try {
            app.shares.add(Collections.list(fileModel.elements()));
            fileModel.clear();
            showQrDialog();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "無法分享：" + e.getMessage(), "錯誤", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void addManualPeer(String text, JTextField field) {
        if (text.isEmpty()) return;
        String host = text;
        int port = Settings.DEFAULT_PORT;
        int colon = text.lastIndexOf(':');
        try {
            if (colon > 0) {
                host = text.substring(0, colon);
                port = Integer.parseInt(text.substring(colon + 1));
            }
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, "格式不正確，請輸入 IP 或 IP:埠");
            return;
        }
        String h = host;
        int pt = port;
        field.setEnabled(false);
        com.badlyac.localtransfer.Threads.start("lt-probe", () -> {
            try {
                Peer peer = app.client.probe(h, pt);
                app.discovery.addManual(peer);
                SwingUtilities.invokeLater(() -> field.setText(""));
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                        "連不到 " + h + ":" + pt + "\n" + e.getMessage(), "加入失敗", JOptionPane.ERROR_MESSAGE));
            } finally {
                SwingUtilities.invokeLater(() -> field.setEnabled(true));
            }
        });
    }

    private void renameDevice() {
        String name = JOptionPane.showInputDialog(this, "裝置名稱（其他裝置看到的名稱）", app.settings.deviceName());
        if (name != null && !name.isBlank()) {
            app.settings.setDeviceName(name.strip());
            refreshInfo();
        }
    }

    private void chooseDownloadDir() {
        JFileChooser fc = new JFileChooser(app.settings.downloadDir().toFile());
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            app.settings.setDownloadDir(fc.getSelectedFile().toPath());
            refreshInfo();
        }
    }

    // ------------------------------------------------------------ dialogs

    private boolean confirmIncoming(TransferServer.IncomingOffer offer) {
        JDialog d = new JDialog(this, "收到傳送請求", true);
        d.setLayout(new BorderLayout(8, 8));
        JLabel head = new JLabel("<html><b>" + escape(offer.sender()) + "</b> 想傳送 "
                + offer.files().size() + " 個檔案（" + Fmt.bytes(offer.totalBytes()) + "）給你</html>");
        head.setBorder(BorderFactory.createEmptyBorder(10, 12, 0, 12));
        d.add(head, BorderLayout.NORTH);

        DefaultListModel<String> names = new DefaultListModel<>();
        int shown = Math.min(offer.files().size(), 100);
        for (int i = 0; i < shown; i++) {
            FileEntry f = offer.files().get(i);
            names.addElement(f.path() + "　(" + Fmt.bytes(f.size()) + ")");
        }
        if (offer.files().size() > shown) names.addElement("…還有 " + (offer.files().size() - shown) + " 個檔案");
        JScrollPane sp = new JScrollPane(new JList<>(names));
        sp.setPreferredSize(new Dimension(460, 180));
        sp.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));
        d.add(sp, BorderLayout.CENTER);

        boolean[] result = {false};
        int[] left = {60};
        JLabel countdown = new JLabel("60 秒後自動拒絕");
        JButton yes = new JButton("接受");
        JButton no = new JButton("拒絕");
        yes.addActionListener(e -> {
            result[0] = true;
            d.dispose();
        });
        no.addActionListener(e -> d.dispose());
        JPanel south = new JPanel(new BorderLayout());
        south.setBorder(BorderFactory.createEmptyBorder(0, 12, 10, 12));
        south.add(countdown, BorderLayout.WEST);
        JPanel b = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        b.add(no);
        b.add(yes);
        south.add(b, BorderLayout.EAST);
        d.add(south, BorderLayout.SOUTH);

        Timer timer = new Timer(1000, e -> {
            left[0]--;
            countdown.setText(left[0] + " 秒後自動拒絕");
            if (left[0] <= 0) d.dispose();
        });
        timer.start();
        d.getRootPane().setDefaultButton(yes);
        d.pack();
        d.setLocationRelativeTo(this);
        d.setAlwaysOnTop(true);
        d.setVisible(true); // 模態：阻塞直到使用者按下按鈕
        timer.stop();
        return result[0];
    }

    private void showQrDialog() {
        List<String> ips = NetUtil.localIPv4();
        if (ips.isEmpty()) {
            JOptionPane.showMessageDialog(this, "找不到區網 IP，請先連上 Wi-Fi 或有線網路");
            return;
        }
        JDialog d = new JDialog(this, "手機 / 網頁連線", true);
        d.setLayout(new BorderLayout(8, 8));
        JComboBox<String> ipBox = new JComboBox<>(ips.toArray(String[]::new));
        JLabel qr = new JLabel();
        qr.setHorizontalAlignment(SwingConstants.CENTER);
        JTextField url = new JTextField();
        url.setEditable(false);
        Runnable refresh = () -> {
            String u = "https://" + ipBox.getSelectedItem() + ":" + app.settings.port + "/?t=" + app.shares.token();
            url.setText(u);
            qr.setIcon(new ImageIcon(renderQr(u, 260)));
        };
        ipBox.addActionListener(e -> refresh.run());
        refresh.run();

        JPanel top = new JPanel(new BorderLayout(6, 0));
        top.setBorder(BorderFactory.createEmptyBorder(10, 12, 0, 12));
        top.add(new JLabel("使用網路介面："), BorderLayout.WEST);
        top.add(ipBox, BorderLayout.CENTER);
        d.add(top, BorderLayout.NORTH);

        JPanel center = new JPanel(new BorderLayout(4, 6));
        center.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));
        center.add(qr, BorderLayout.CENTER);
        JTextArea tip = new JTextArea("""
                1. 手機連上和電腦同一個 Wi-Fi，用相機掃描上面的 QR Code
                2. 瀏覽器會警告「連線不安全」——這是因為使用自簽憑證，
                   選「進階 → 繼續前往」即可（連線仍然是加密的）
                3. 手機傳檔給電腦時，電腦會跳出視窗請你確認
                4. 電腦想傳給手機：先把檔案加入清單，按「分享清單給手機」""");
        tip.setEditable(false);
        tip.setOpaque(false);
        center.add(tip, BorderLayout.SOUTH);
        d.add(center, BorderLayout.CENTER);

        JPanel south = new JPanel(new BorderLayout(6, 0));
        south.setBorder(BorderFactory.createEmptyBorder(0, 12, 10, 12));
        south.add(url, BorderLayout.CENTER);
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        JButton clear = new JButton("清除分享（" + app.shares.list().size() + "）");
        clear.addActionListener(e -> {
            app.shares.clear();
            clear.setText("清除分享（0）");
        });
        JButton close = new JButton("關閉");
        close.addActionListener(e -> d.dispose());
        btns.add(clear);
        btns.add(close);
        south.add(btns, BorderLayout.EAST);
        d.add(south, BorderLayout.SOUTH);

        d.pack();
        d.setLocationRelativeTo(this);
        d.setVisible(true);
    }

    private static BufferedImage renderQr(String text, int size) {
        try {
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size,
                    Map.of(EncodeHintType.MARGIN, 1));
            BufferedImage img = new BufferedImage(m.getWidth(), m.getHeight(), BufferedImage.TYPE_INT_RGB);
            for (int x = 0; x < m.getWidth(); x++) {
                for (int y = 0; y < m.getHeight(); y++) img.setRGB(x, y, m.get(x, y) ? 0x000000 : 0xFFFFFF);
            }
            return img;
        } catch (Exception e) {
            return new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        }
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ------------------------------------------------------------ transfers table

    private static final class TaskModel extends AbstractTableModel implements TransferHub.Listener {
        private final String[] cols = {"方向", "對象", "內容", "進度", "狀態"};
        private final List<TransferTask> rows = new ArrayList<>();

        TransferTask at(int i) {
            return rows.get(i);
        }

        @Override
        public void taskAdded(TransferTask t) {
            SwingUtilities.invokeLater(() -> {
                rows.add(t);
                fireTableRowsInserted(rows.size() - 1, rows.size() - 1);
            });
        }

        @Override
        public void taskUpdated(TransferTask t) {
            SwingUtilities.invokeLater(() -> {
                int i = rows.indexOf(t);
                if (i >= 0) fireTableRowsUpdated(i, i);
            });
        }

        void removeFinished() {
            rows.removeIf(TransferTask::isFinished);
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return cols.length;
        }

        @Override
        public String getColumnName(int c) {
            return cols[c];
        }

        @Override
        public Object getValueAt(int r, int c) {
            TransferTask t = rows.get(r);
            return switch (c) {
                case 0 -> t.direction == TransferTask.Direction.SEND ? "傳送" : "接收";
                case 1 -> t.peerName;
                case 2 -> t.title;
                case 3 -> t;
                default -> statusText(t);
            };
        }

        private static String statusText(TransferTask t) {
            String s = switch (t.state) {
                case WAITING_CONFIRM -> t.direction == TransferTask.Direction.SEND ? "等待對方確認" : "等待你確認";
                case HASHING -> "計算校驗碼";
                case TRANSFERRING -> "傳輸中";
                case DONE -> "完成";
                case FAILED -> "失敗";
                case REJECTED -> "已拒絕";
                case CANCELLED -> "已取消";
            };
            return t.message.isEmpty() || t.state == TransferTask.State.WAITING_CONFIRM || t.state == TransferTask.State.HASHING
                    ? s : s + "：" + t.message;
        }
    }

    private static final class ProgressRenderer extends JProgressBar implements TableCellRenderer {
        ProgressRenderer() {
            super(0, 1000);
            setStringPainted(true);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean sel, boolean focus, int row, int col) {
            TransferTask t = (TransferTask) value;
            setValue((int) (t.progress() * 1000));
            String text = (int) (t.progress() * 100) + "%";
            double speed = t.bytesPerSecond();
            if (speed > 0) text += "　" + Fmt.bytes((long) speed) + "/s";
            if (t.totalBytes > 0) text += "　" + Fmt.bytes(t.done()) + " / " + Fmt.bytes(t.totalBytes);
            setString(text);
            return this;
        }
    }
}
