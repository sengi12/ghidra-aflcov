package resources;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.List;

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
 * The docked coverage window: a small toolbar (Load / Clear / status) over a
 * table of functions ranked by how much of each one the run exercised. Double
 * -clicking a row jumps the Listing there.
 *
 * The panel owns no Ghidra state of its own - it renders whatever
 * {@link CoveragePainter.Result} the entry script hands back and reports button
 * presses through {@link CoverageActions}.
 */
public class CoveragePanel extends JPanel {

    private final CoverageActions actions;
    private final FunctionTableModel tableModel = new FunctionTableModel();
    private final JTable table = new JTable(tableModel);
    private final JLabel status = new JLabel("No coverage loaded.");
    private File lastDir;

    public CoveragePanel(CoverageActions actions) {
        super(new BorderLayout());
        this.actions = actions;

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        JButton load = new JButton("Load drcov…");
        JButton clear = new JButton("Clear");
        load.addActionListener(e -> chooseFile());
        clear.addActionListener(e -> actions.clearCoverage());
        bar.add(load);
        bar.add(clear);
        bar.add(status);
        add(bar, BorderLayout.NORTH);

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

    private void chooseFile() {
        JFileChooser chooser = new JFileChooser(lastDir);
        chooser.setDialogTitle("Open drcov coverage file");
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File f = chooser.getSelectedFile();
            lastDir = f.getParentFile();
            status.setText("Loading " + f.getName() + "…");
            actions.applyCoverage(f);
        }
    }

    private void jumpToSelectedRow() {
        int view = table.getSelectedRow();
        if (view < 0) {
            return;
        }
        int model = table.convertRowIndexToModel(view);
        Address addr = tableModel.addressAt(model);
        if (addr != null) {
            actions.navigateTo(addr);
        }
    }

    // --- called by the entry script, always on the Swing thread ---

    public void showResult(CoveragePainter.Result result) {
        tableModel.setRows(result.functions);
        status.setText(String.format(
                "%s: %d/%d blocks mapped, %d functions touched",
                result.moduleName, result.blocksMapped, result.blocksInFile,
                result.functions.size()));
    }

    public void showCleared() {
        tableModel.setRows(java.util.Collections.emptyList());
        status.setText("Coverage cleared.");
    }

    public void showError(String message) {
        status.setText("Error: " + message);
    }

    /** Table backing model: one row per function that the run touched. */
    private static class FunctionTableModel extends AbstractTableModel {
        private final String[] cols = {"Function", "Coverage", "Blocks", "Address"};
        private List<CoveragePainter.FunctionCoverage> rows = java.util.Collections.emptyList();

        void setRows(List<CoveragePainter.FunctionCoverage> rows) {
            this.rows = rows;
            fireTableDataChanged();
        }

        Address addressAt(int row) {
            return rows.get(row).entry;
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
        public Class<?> getColumnClass(int c) {
            return c == 1 ? Double.class : String.class;
        }

        @Override
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
}
