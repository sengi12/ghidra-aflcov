package resources;

import java.awt.Color;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ghidra.app.plugin.core.colorizer.ColorizingService;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressSet;
import ghidra.program.model.address.AddressSetView;
import ghidra.program.model.block.BasicBlockModel;
import ghidra.program.model.block.CodeBlock;
import ghidra.program.model.block.CodeBlockIterator;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.listing.Program;
import ghidra.util.task.TaskMonitor;

/**
 * Applies a {@link Coverage} onto a Ghidra program: rebases each executed block
 * onto the program's image base, snaps it to the containing basic block, and
 * paints that block's background. Colouring the whole basic block (rather than a
 * raw byte range) is what makes the Function Graph light up node-by-node, which
 * is the "highlight blocks in the block diagram" behaviour we are after - the
 * Listing and the Graph both read the same background colour from the
 * ColorizingService.
 */
public class CoveragePainter {

    /** Soft green, legible over both light and dark listing backgrounds. */
    public static final Color COVERED_COLOR = new Color(120, 190, 120);

    /** Per-function rollup shown in the panel's table. */
    public static class FunctionCoverage {
        public final String name;
        public final Address entry;
        public final int coveredBlocks;
        public final int totalBlocks;

        public FunctionCoverage(String name, Address entry, int coveredBlocks, int totalBlocks) {
            this.name = name;
            this.entry = entry;
            this.coveredBlocks = coveredBlocks;
            this.totalBlocks = totalBlocks;
        }

        public double percent() {
            return totalBlocks == 0 ? 0.0 : (100.0 * coveredBlocks) / totalBlocks;
        }
    }

    /** Outcome of applying a coverage file, for reporting back to the user. */
    public static class Result {
        public final String moduleName;
        public final int blocksInFile;
        public final int blocksMapped;
        public final List<FunctionCoverage> functions;

        public Result(String moduleName, int blocksInFile, int blocksMapped,
                      List<FunctionCoverage> functions) {
            this.moduleName = moduleName;
            this.blocksInFile = blocksInFile;
            this.blocksMapped = blocksMapped;
            this.functions = functions;
        }
    }

    private final Program program;
    private final ColorizingService colorizer;
    private final TaskMonitor monitor;
    private final BasicBlockModel blockModel;
    private final FunctionManager functionManager;

    /** Everything we have coloured, so we can take it back off cleanly. */
    private AddressSet painted = new AddressSet();

    public CoveragePainter(Program program, ColorizingService colorizer, TaskMonitor monitor) {
        this.program = program;
        this.colorizer = colorizer;
        this.monitor = monitor;
        this.blockModel = new BasicBlockModel(program);
        this.functionManager = program.getFunctionManager();
    }

    /**
     * Choose the drcov module that best corresponds to the open program: prefer a
     * name match against the program, otherwise the module with the most blocks.
     */
    public Coverage.Module chooseModule(Coverage cov) {
        String progName = program.getName();
        Coverage.Module best = null;
        int bestBlocks = -1;
        for (Coverage.Module m : cov.getModules().values()) {
            if (progName != null && !progName.isEmpty()
                    && (progName.equalsIgnoreCase(m.name())
                        || progName.equalsIgnoreCase(stripExt(m.name()))
                        || m.name().equalsIgnoreCase(stripExt(progName)))) {
                return m;
            }
            int n = cov.blocksForModule(m.id).size();
            if (n > bestBlocks) {
                bestBlocks = n;
                best = m;
            }
        }
        return best;
    }

    /**
     * Rebase and paint the coverage for one module. Clears any coverage this
     * painter applied previously first, so loading a new file replaces the old
     * picture rather than layering on top of it.
     */
    public Result apply(Coverage cov, Coverage.Module module) throws Exception {
        clear();

        Address imageBase = program.getImageBase();
        AddressSet toPaint = new AddressSet();
        // function entry address -> set of covered basic-block start addresses
        Map<Address, java.util.Set<Address>> coveredByFunction = new LinkedHashMap<>();

        List<Coverage.Block> blocks = module == null
                ? cov.getBlocks() : cov.blocksForModule(module.id);
        int mapped = 0;

        for (Coverage.Block b : blocks) {
            Address addr;
            try {
                addr = imageBase.add(b.offset);
            } catch (Exception e) {
                continue;   // offset lands outside the address space
            }
            if (!program.getMemory().contains(addr)) {
                continue;
            }
            mapped++;

            CodeBlock cb = blockModel.getFirstCodeBlockContaining(addr, monitor);
            if (cb != null) {
                toPaint.add(cb);
            } else {
                // No defined block here (undefined bytes); paint the raw extent so
                // the hit is still visible.
                try {
                    toPaint.addRange(addr, addr.add(Math.max(0, b.size - 1)));
                } catch (Exception e) {
                    toPaint.add(addr);
                }
            }

            Function fn = functionManager.getFunctionContaining(addr);
            if (fn != null) {
                Address blockStart = cb != null ? cb.getFirstStartAddress() : addr;
                coveredByFunction
                        .computeIfAbsent(fn.getEntryPoint(), k -> new java.util.HashSet<>())
                        .add(blockStart);
            }
        }

        if (!toPaint.isEmpty()) {
            colorizer.setBackgroundColor(toPaint, COVERED_COLOR);
            painted.add(toPaint);
        }

        List<FunctionCoverage> funcs = buildFunctionTable(coveredByFunction);
        String modName = module == null ? "(all modules)" : module.name();
        return new Result(modName, blocks.size(), mapped, funcs);
    }

    private List<FunctionCoverage> buildFunctionTable(
            Map<Address, java.util.Set<Address>> coveredByFunction) throws Exception {
        List<FunctionCoverage> funcs = new ArrayList<>();
        for (Map.Entry<Address, java.util.Set<Address>> e : coveredByFunction.entrySet()) {
            Function fn = functionManager.getFunctionAt(e.getKey());
            if (fn == null) {
                continue;
            }
            int total = countBlocks(fn.getBody());
            int covered = e.getValue().size();
            if (total > 0 && covered > total) {
                covered = total;   // guard against off-by-one from raw-range fallbacks
            }
            funcs.add(new FunctionCoverage(fn.getName(), fn.getEntryPoint(), covered, total));
        }
        funcs.sort((a, b) -> Double.compare(b.percent(), a.percent()));
        return funcs;
    }

    private int countBlocks(AddressSetView body) throws Exception {
        int n = 0;
        CodeBlockIterator it = blockModel.getCodeBlocksContaining(body, monitor);
        while (it.hasNext()) {
            it.next();
            n++;
        }
        return n;
    }

    /** Remove every colour this painter applied. */
    public void clear() {
        if (painted != null && !painted.isEmpty()) {
            colorizer.clearBackgroundColor(painted);
        }
        painted = new AddressSet();
    }

    private static String stripExt(String s) {
        int dot = s.lastIndexOf('.');
        return dot > 0 ? s.substring(0, dot) : s;
    }
}
