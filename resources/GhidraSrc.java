package resources;

import ghidra.app.script.GhidraScript;

/**
 * Shared base for the ghidra-aflcov entry script.
 *
 * Ghidra will only hand a class that extends {@link GhidraScript} the live
 * program, tool and console. The classes under {@code resources/} are plain
 * objects that Ghidra never instantiates directly, so they reach the API through
 * a reference to this base - the same pattern cantordust and ghidra-hexEditor
 * use. Keeping the entry point thin (it only wires the UI together) means the
 * interesting code lives in ordinary, separately-testable classes.
 */
public class GhidraSrc extends GhidraScript {

    /** Absolute path of the directory this script was loaded from, with a trailing separator. */
    public String currentDirectory;

    protected void run() throws Exception {
    }

    public String getName() {
        return "";
    }

    public String getCurrentDirectory() {
        return this.currentDirectory;
    }

    /** Update the docked window's subtitle; overridden by the entry script. */
    public void changeTitle(String s) {
    }
}
