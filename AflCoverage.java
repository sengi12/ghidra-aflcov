// AFL Coverage
// @author Michael Sengelmann
// @category Fuzzing
// @keybinding alt A
// @menupath Tools.AFL Coverage

import java.io.File;

import javax.swing.SwingUtilities;

import docking.ComponentProvider;
import ghidra.app.plugin.core.colorizer.ColorizingService;
import ghidra.app.services.GoToService;
import ghidra.framework.plugintool.PluginTool;
import ghidra.program.model.address.Address;
import ghidra.util.task.TaskMonitor;

import resources.AflCoverageProvider;
import resources.CoverageActions;
import resources.CoveragePainter;
import resources.CoveragePanel;
import resources.Coverage;
import resources.DrcovParser;
import resources.GhidraSrc;

/**
 * ghidra-aflcov entry point.
 *
 * Opens a docked panel that loads a drcov coverage file, paints the executed
 * basic blocks onto the Listing and the Function Graph, and lists the functions
 * the run reached. Built as a GhidraScript-style plugin - installed by adding
 * this directory to Ghidra's script directories - in the same mould as the
 * cantordust and ghidra-hexEditor plugins.
 */
public class AflCoverage extends GhidraSrc implements CoverageActions {

    private PluginTool tool;
    private ColorizingService colorizer;
    private GoToService goToService;
    private CoveragePainter painter;
    private CoveragePanel panel;
    private AflCoverageProvider provider;
    private CoveragePainter.CoverageData baseline;

    @Override
    protected void run() throws Exception {
        this.currentDirectory = sourceFile.getParentFile().getAbsolutePath() + File.separator;

        if (currentProgram == null) {
            printf("Open a program before running AFL Coverage.\n");
            return;
        }
        this.tool = state.getTool();
        if (tool == null) {
            printf("AFL Coverage needs a Ghidra tool window; run it from the GUI.\n");
            return;
        }

        this.colorizer = tool.getService(ColorizingService.class);
        if (colorizer == null) {
            printf("No ColorizingService available - is the Function ID/Colorizer plugin enabled?\n");
            return;
        }
        this.goToService = tool.getService(GoToService.class);
        this.painter = new CoveragePainter(currentProgram, colorizer, TaskMonitor.DUMMY);

        String programName = currentProgram.getName();
        SwingUtilities.invokeLater(() -> showProvider(programName));
    }

    private void showProvider(String programName) {
        // Replace a provider left by an earlier run: it holds classes from that
        // run's compile and a stale painter bound to the old program.
        ComponentProvider existing = tool.getComponentProvider(AflCoverageProvider.NAME);
        if (existing != null) {
            tool.removeComponentProvider(existing);
        }
        this.panel = new CoveragePanel(this);
        this.provider = new AflCoverageProvider(tool, panel, programName);
        tool.addComponentProvider(provider, true);
    }

    // --- CoverageActions, called from the panel on the Swing thread ---

    @Override
    public void applyCoverage(File file) {
        // Parsing and block-model walking can take a moment on a large program,
        // so keep it off the event thread and marshal the result back.
        new Thread(() -> {
            try {
                Coverage cov = new DrcovParser().parse(file);
                Coverage.Module module = painter.chooseModule(cov);
                CoveragePainter.Result result = inTransaction(() -> painter.apply(cov, module));
                SwingUtilities.invokeLater(() -> panel.showResult(result));
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                SwingUtilities.invokeLater(() -> panel.showError(msg));
            }
        }, "aflcov-load").start();
    }

    @Override
    public void loadBaseline(File file) {
        new Thread(() -> {
            try {
                Coverage cov = new DrcovParser().parse(file);
                Coverage.Module module = painter.chooseModule(cov);
                CoveragePainter.CoverageData data = painter.computeCoverage(cov, module);
                CoveragePainter.Result result = inTransaction(() -> painter.paintSingle(data));
                this.baseline = data;
                SwingUtilities.invokeLater(() -> panel.showBaselineLoaded(result));
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                SwingUtilities.invokeLater(() -> panel.showError(msg));
            }
        }, "aflcov-baseline").start();
    }

    @Override
    public void diffWith(File file) {
        if (baseline == null) {
            panel.showError("load a baseline first (Baseline…), then diff a crash against it");
            return;
        }
        new Thread(() -> {
            try {
                Coverage cov = new DrcovParser().parse(file);
                Coverage.Module module = painter.chooseModule(cov);
                CoveragePainter.CoverageData target = painter.computeCoverage(cov, module);
                CoveragePainter.DiffResult diff = inTransaction(() -> painter.paintDiff(baseline, target));
                SwingUtilities.invokeLater(() -> panel.showDiff(diff));
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                SwingUtilities.invokeLater(() -> panel.showError(msg));
            }
        }, "aflcov-diff").start();
    }

    @Override
    public void clearCoverage() {
        try {
            inTransaction(() -> {
                painter.clear();
                return null;
            });
            this.baseline = null;
            panel.showCleared();
        } catch (Exception e) {
            panel.showError(e.toString());
        }
    }

    @Override
    public void navigateTo(Address address) {
        if (goToService != null) {
            goToService.goTo(address);
        }
    }

    /** Run program-modifying work inside a single undoable transaction. */
    private <T> T inTransaction(TxBody<T> body) throws Exception {
        int tx = currentProgram.startTransaction("AFL coverage");
        boolean ok = false;
        try {
            T result = body.run();
            ok = true;
            return result;
        } finally {
            currentProgram.endTransaction(tx, ok);
        }
    }

    private interface TxBody<T> {
        T run() throws Exception;
    }

    @Override
    public String getName() {
        return currentProgram == null ? "AFL Coverage" : currentProgram.getName();
    }

    @Override
    public void changeTitle(String s) {
        if (provider != null) {
            provider.setSubTitle(s);
        }
    }
}
