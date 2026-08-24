package resources;

import javax.swing.JComponent;

import docking.ComponentProvider;
import docking.WindowPosition;
import ghidra.framework.plugintool.PluginTool;

/**
 * Hosts the coverage panel inside the Ghidra tool.
 *
 * Ghidra owns the window for a ComponentProvider, so it docks alongside the
 * Listing and the Function Graph and is remembered between sessions. Following
 * cantordust and ghidra-hexEditor, the provider is marked transient: a
 * script-created provider cannot be rebuilt by the tool on the next launch, so
 * it must stay out of the saved tool configuration.
 */
public class AflCoverageProvider extends ComponentProvider {

    public static final String NAME = "AFL Coverage";

    private final CoveragePanel panel;
    private final PluginTool tool;

    public AflCoverageProvider(PluginTool tool, CoveragePanel panel, String programName) {
        super(tool, NAME, NAME);
        this.tool = tool;
        this.panel = panel;
        setTitle(NAME);
        setSubTitle(programName);
        setDefaultWindowPosition(WindowPosition.RIGHT);
        setTransient();
        setWindowMenuGroup(NAME);
    }

    @Override
    public JComponent getComponent() {
        return panel;
    }

    @Override
    public void closeComponent() {
        super.closeComponent();
        // Nothing in the UI can reopen a script-created provider, so drop it from
        // the tool rather than leaving a dead Window-menu entry behind.
        tool.removeComponentProvider(this);
    }

    public CoveragePanel getPanel() {
        return panel;
    }
}
