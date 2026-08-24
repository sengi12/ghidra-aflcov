package resources;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The parsed contents of a drcov coverage file: the modules that were mapped
 * during the run, and the basic blocks that executed.
 *
 * Addresses here are deliberately kept as module-relative offsets, exactly as
 * drcov stores them. Turning an offset into a Ghidra address needs the program's
 * image base, which this model has no business knowing about - that rebasing
 * happens in {@link CoveragePainter}. Keeping the model free of any Ghidra type
 * is what lets the parser be exercised on its own.
 */
public class Coverage {

    /** One row of the drcov module table. */
    public static class Module {
        public final int id;
        public final long base;     // load address at collection time
        public final long end;      // end address (exclusive) at collection time
        public final String path;   // full path of the mapped module

        public Module(int id, long base, long end, String path) {
            this.id = id;
            this.base = base;
            this.end = end;
            this.path = path;
        }

        /** The file name only, e.g. "target.bin", for matching against a Ghidra program. */
        public String name() {
            if (path == null) {
                return "";
            }
            int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
            return slash >= 0 ? path.substring(slash + 1) : path;
        }
    }

    /** One executed basic block: an offset from its module's base, and its length. */
    public static class Block {
        public final int moduleId;
        public final long offset;   // start, relative to the module base
        public final int size;      // block length in bytes

        public Block(int moduleId, long offset, int size) {
            this.moduleId = moduleId;
            this.offset = offset;
            this.size = size;
        }
    }

    private final Map<Integer, Module> modules = new HashMap<>();
    private final List<Block> blocks = new ArrayList<>();

    public void addModule(Module m) {
        modules.put(m.id, m);
    }

    public void addBlock(Block b) {
        blocks.add(b);
    }

    public Map<Integer, Module> getModules() {
        return modules;
    }

    public List<Block> getBlocks() {
        return blocks;
    }

    /** Blocks belonging to one module, in file order. */
    public List<Block> blocksForModule(int moduleId) {
        List<Block> out = new ArrayList<>();
        for (Block b : blocks) {
            if (b.moduleId == moduleId) {
                out.add(b);
            }
        }
        return out;
    }
}
