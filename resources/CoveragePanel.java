package resources;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;

import ghidra.program.model.address.Address;

/**
 * The docked coverage window: a toolbar (single-load / baseline / diff / clear),
 * a colour legend, and a table of functions. Double-clicking a row jumps the
 * Listing there.
 *
 * In single mode the table ranks functions by how much of each was reached. In
 * diff mode it ranks by "crash-only" blocks - the ones the target reached that
 * the baseline never did - so the most interesting functions float to the top.
 */
public class CoveragePanel extends JPanel {

    private final CoverageActions actions;
    private final SingleTableModel singleModel = new SingleTableModel();
    private final DiffTableModel diffModel = new DiffTableModel();
    private final JTable table = new JTable(singleModel);
    private final JLabel status = new JLabel("No coverage loaded.");
    private final JLabel legend = new JLabel(" ");
    private File lastDir;

    public CoveragePanel(CoverageActions actions) {
        super(new BorderLayout());
        this.actions = actions;

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        bar.add(button("Load…", () -> choose("Open drcov coverage file", actions::applyCoverage)));
        bar.add(button("Baseline…", () -> choose("Open baseline drcov (corpus)", actions::loadBaseline)));
        bar.add(button("Diff…", () -> choose("Open target drcov (e.g. a crash)", actions::diffWith)));
        bar.add(button("Clear", actions::clearCoverage));

        JPanel top = new JPanel(new BorderLayout());
        top.add(bar, BorderLayout.NORTH);
        JPanel info = new JPanel(new BorderLayout());
        info.setBorder(BorderFactory.createEmptyBorder(0, 8, 4, 8));
        info.add(status, BorderLayout.NORTH);
        info.add(legend, BorderLayout.SOUTH);
        top.add(info, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoCreateRowSorter(true);
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    jumpToSelectedRow();
                }
            }
        });
        add(new JScrollPane(table), BorderLayout.CENTER);
    }

    private JButton button(String text, Runnable action) {
        JButton b = new JButton(text);
        b.addActionListener(e -> action.run());
        return b;
    }

    private interface FileSink {
        void accept(File f);
    }

    private void choose(String title, FileSink sink) {
        JFileChooser chooser = new JFileChooser(lastDir);
        chooser.setDialogTitle(title);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File f = chooser.getSelectedFile();
            lastDir = f.getParentFile();
            status.setText("Loading " + f.getName() + "…");
            sink.accept(f);
        }
    }

    private void jumpToSelectedRow() {
        int view = table.getSelectedRow();
        if (view < 0) {
            return;
        }
        int model = table.convertRowIndexToModel(view);
        Address addr = table.getModel() == diffModel
                ? diffModel.addressAt(model) : singleModel.addressAt(model);
        if (addr != null) {
            actions.navigateTo(addr);
        }
    }

    private static String swatch(Color c, String label) {
        return String.format("<span style='background:#%02x%02x%02x'>&nbsp;&nbsp;</span> %s",
                c.getRed(), c.getGreen(), c.getBlue(), label);
    }

    // --- called by the entry script on the Swing thread ---

    public void showResult(CoveragePainter.Result result) {
        singleModel.setRows(result.functions);
        if (table.getModel() != singleModel) {
            table.setModel(singleModel);
        }
        legend.setText(" ");
        status.setText(String.format("%s: %d/%d blocks mapped, %d functions touched",
                result.moduleName, result.blocksMapped, result.blocksInFile, result.functions.size()));
    }

    public void showBaselineLoaded(CoveragePainter.Result result) {
        showResult(result);
        status.setText(status.getText() + "  — baseline set; use Diff… against a crash");
    }

    public void showDiff(CoveragePainter.DiffResult diff) {
        diffModel.setRows(diff.functions);
        if (table.getModel() != diffModel) {
            table.setModel(diffModel);
        }
        legend.setText("<html>"
                + swatch(CoveragePainter.TARGET_ONLY_COLOR, "crash-only") + " &nbsp; "
                + swatch(CoveragePainter.COVERED_COLOR, "both") + " &nbsp; "
                + swatch(CoveragePainter.BASELINE_ONLY_COLOR, "baseline-only") + "</html>");
        status.setText(String.format("Diff: %d crash-only, %d shared, %d baseline-only blocks",
                diff.targetOnlyBlocks, diff.bothBlocks, diff.baselineOnlyBlocks));
    }

    public void showCleared() {
        singleModel.setRows(java.util.Collections.emptyList());
        diffModel.setRows(java.util.Collections.emptyList());
        table.setModel(singleModel);
        legend.setText(" ");
        status.setText("Coverage cleared.");
    }

    public void showError(String message) {
        status.setText("Error: " + message);
    }

    /** Single-set table: one row per function reached. */
    private static class SingleTableModel extends AbstractTableModel {
        private final String[] cols = {"Function", "Coverage", "Blocks", "Address"};
        private List<CoveragePainter.FunctionCoverage> rows = java.util.Collections.emptyList();

        void setRows(List<CoveragePainter.FunctionCoverage> rows) {
            this.rows = rows;
            fireTableDataChanged();
        }

        Address addressAt(int row) {
            return rows.get(row).entry;
        }

        public int getRowCount() { return rows.size(); }
        public int getColumnCount() { return cols.length; }
        public String getColumnName(int c) { return cols[c]; }
        public Class<?> getColumnClass(int c) { return c == 1 ? Double.class : String.class; }

        public Object getValueAt(int r, int c) {
            CoveragePainter.FunctionCoverage f = rows.get(r);
            switch (c) {
                case 0: return f.name;
                case 1: return Math.round(f.percent() * 10.0) / 10.0;
                case 2: return f.coveredBlocks + "/" + f.totalBlocks;
                case 3: return f.entry == null ? "" : f.entry.toString();
                default: return "";
            }
        }
    }

    /** Diff table: crash-only / crash / baseline / total per function. */
    private static class DiffTableModel extends AbstractTableModel {
        private final String[] cols = {"Function", "Crash-only", "Crash", "Baseline", "Total", "Address"};
        private List<CoveragePainter.FunctionDiff> rows = java.util.Collections.emptyList();

        void setRows(List<CoveragePainter.FunctionDiff> rows) {
            this.rows = rows;
            fireTableDataChanged();
        }

        Address addressAt(int row) {
            return rows.get(row).entry;
        }

        public int getRowCount() { return rows.size(); }
        public int getColumnCount() { return cols.length; }
        public String getColumnName(int c) { return cols[c]; }
        public Class<?> getColumnClass(int c) { return (c >= 1 && c <= 4) ? Integer.class : String.class; }

        public Object getValueAt(int r, int c) {
            CoveragePainter.FunctionDiff f = rows.get(r);
            switch (c) {
                case 0: return f.name;
                case 1: return f.targetOnlyBlocks;
                case 2: return f.targetBlocks;
                case 3: return f.baselineBlocks;
                case 4: return f.totalBlocks;
                case 5: return f.entry == null ? "" : f.entry.toString();
                default: return "";
            }
        }
    }
}
