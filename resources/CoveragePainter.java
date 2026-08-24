package resources;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

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
 * Maps a {@link Coverage} onto a Ghidra program and paints it.
 *
 * Each executed block is rebased onto the program's image base and snapped to
 * the basic block that contains it, so whole Function-Graph nodes light up (the
 * Listing and the Graph share the ColorizingService background).
 *
 * Two modes:
 *   - single: paint one coverage set green.
 *   - diff: compare a baseline set (e.g. the corpus) against a target set (e.g.
 *     a crash) and colour blocks by which sets reached them - the blocks a crash
 *     reached that the corpus never did are what you want to see.
 */
public class CoveragePainter {

    /** Reached (single mode) / reached by both sets (diff mode). */
    public static final Color COVERED_COLOR = new Color(120, 190, 120);   // green
    /** Diff: reached by the target only - the interesting, crash-unique blocks. */
    public static final Color TARGET_ONLY_COLOR = new Color(220, 120, 90); // orange-red
    /** Diff: reached by the baseline only. */
    public static final Color BASELINE_ONLY_COLOR = new Color(120, 150, 200); // muted blue

    /** Rebased, block-snapped coverage for one file - the input to painting and diffing. */
    public static class CoverageData {
        public final String moduleName;
        public final int blocksInFile;
        public final int blocksMapped;
        /** covered basic-block start address -> that block's address range */
        public final Map<Address, AddressSet> blocksByStart;
        /** function entry -> set of covered block-start addresses in it */
        public final Map<Address, Set<Address>> coveredByFunction;

        CoverageData(String moduleName, int blocksInFile, int blocksMapped,
                     Map<Address, AddressSet> blocksByStart,
                     Map<Address, Set<Address>> coveredByFunction) {
            this.moduleName = moduleName;
            this.blocksInFile = blocksInFile;
            this.blocksMapped = blocksMapped;
            this.blocksByStart = blocksByStart;
            this.coveredByFunction = coveredByFunction;
        }
    }

    /** Per-function rollup (single mode). */
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

    /** Per-function rollup (diff mode). */
    public static class FunctionDiff {
        public final String name;
        public final Address entry;
        public final int totalBlocks;
        public final int baselineBlocks;
        public final int targetBlocks;
        public final int targetOnlyBlocks;

        public FunctionDiff(String name, Address entry, int totalBlocks,
                            int baselineBlocks, int targetBlocks, int targetOnlyBlocks) {
            this.name = name;
            this.entry = entry;
            this.totalBlocks = totalBlocks;
            this.baselineBlocks = baselineBlocks;
            this.targetBlocks = targetBlocks;
            this.targetOnlyBlocks = targetOnlyBlocks;
        }
    }

    /** Result of a single-file paint. */
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

    /** Result of a diff paint. */
    public static class DiffResult {
        public final int bothBlocks;
        public final int targetOnlyBlocks;
        public final int baselineOnlyBlocks;
        public final List<FunctionDiff> functions;

        public DiffResult(int bothBlocks, int targetOnlyBlocks, int baselineOnlyBlocks,
                          List<FunctionDiff> functions) {
            this.bothBlocks = bothBlocks;
            this.targetOnlyBlocks = targetOnlyBlocks;
            this.baselineOnlyBlocks = baselineOnlyBlocks;
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
     * Rebase and snap one module's coverage to basic blocks, without painting.
     * This is the shared front end for both single and diff painting.
     */
    public CoverageData computeCoverage(Coverage cov, Coverage.Module module) throws Exception {
        Address imageBase = program.getImageBase();
        Map<Address, AddressSet> blocksByStart = new LinkedHashMap<>();
        Map<Address, Set<Address>> coveredByFunction = new LinkedHashMap<>();

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
            Address start;
            AddressSet range = new AddressSet();
            if (cb != null) {
                start = cb.getFirstStartAddress();
                range.add(cb);
            } else {
                // No defined block here; use the raw extent so the hit is visible.
                start = addr;
                try {
                    range.addRange(addr, addr.add(Math.max(0, b.size - 1)));
                } catch (Exception e) {
                    range.add(addr);
                }
            }
            blocksByStart.put(start, range);

            Function fn = functionManager.getFunctionContaining(addr);
            if (fn != null) {
                coveredByFunction
                        .computeIfAbsent(fn.getEntryPoint(), k -> new HashSet<>())
                        .add(start);
            }
        }

        String modName = module == null ? "(all modules)" : module.name();
        return new CoverageData(modName, blocks.size(), mapped, blocksByStart, coveredByFunction);
    }

    /** Paint one coverage set green. Clears any previous painting first. */
    public Result paintSingle(CoverageData data) throws Exception {
        clear();

        AddressSet toPaint = new AddressSet();
        for (AddressSet range : data.blocksByStart.values()) {
            toPaint.add(range);
        }
        if (!toPaint.isEmpty()) {
            colorizer.setBackgroundColor(toPaint, COVERED_COLOR);
            painted.add(toPaint);
        }

        List<FunctionCoverage> funcs = new ArrayList<>();
        for (Map.Entry<Address, Set<Address>> e : data.coveredByFunction.entrySet()) {
            Function fn = functionManager.getFunctionAt(e.getKey());
            if (fn == null) {
                continue;
            }
            int total = countBlocks(fn.getBody());
            int covered = Math.min(e.getValue().size(), total == 0 ? e.getValue().size() : total);
            funcs.add(new FunctionCoverage(fn.getName(), fn.getEntryPoint(), covered, total));
        }
        funcs.sort((a, b) -> Double.compare(b.percent(), a.percent()));
        return new Result(data.moduleName, data.blocksInFile, data.blocksMapped, funcs);
    }

    /**
     * Diff a baseline set against a target set and paint by membership: blocks in
     * both are green, target-only are orange-red (the crash-unique path), and
     * baseline-only are muted blue.
     */
    public DiffResult paintDiff(CoverageData baseline, CoverageData target) throws Exception {
        clear();

        AddressSet bothSet = new AddressSet();
        AddressSet targetOnlySet = new AddressSet();
        AddressSet baselineOnlySet = new AddressSet();

        for (Map.Entry<Address, AddressSet> e : target.blocksByStart.entrySet()) {
            if (baseline.blocksByStart.containsKey(e.getKey())) {
                bothSet.add(e.getValue());
            } else {
                targetOnlySet.add(e.getValue());
            }
        }
        for (Map.Entry<Address, AddressSet> e : baseline.blocksByStart.entrySet()) {
            if (!target.blocksByStart.containsKey(e.getKey())) {
                baselineOnlySet.add(e.getValue());
            }
        }

        if (!baselineOnlySet.isEmpty()) {
            colorizer.setBackgroundColor(baselineOnlySet, BASELINE_ONLY_COLOR);
            painted.add(baselineOnlySet);
        }
        if (!bothSet.isEmpty()) {
            colorizer.setBackgroundColor(bothSet, COVERED_COLOR);
            painted.add(bothSet);
        }
        if (!targetOnlySet.isEmpty()) {
            colorizer.setBackgroundColor(targetOnlySet, TARGET_ONLY_COLOR);
            painted.add(targetOnlySet);
        }

        List<FunctionDiff> funcs = buildDiffTable(baseline, target);
        int both = countStarts(target, baseline, true);
        int targetOnly = target.blocksByStart.size() - both;
        int baselineOnly = baseline.blocksByStart.size() - both;
        return new DiffResult(both, targetOnly, baselineOnly, funcs);
    }

    /** Count block starts in target that are (also) in baseline. */
    private int countStarts(CoverageData target, CoverageData baseline, boolean inBoth) {
        int n = 0;
        for (Address start : target.blocksByStart.keySet()) {
            if (baseline.blocksByStart.containsKey(start) == inBoth) {
                n++;
            }
        }
        return n;
    }

    private List<FunctionDiff> buildDiffTable(CoverageData baseline, CoverageData target) throws Exception {
        Set<Address> entries = new TreeSet<>();
        entries.addAll(baseline.coveredByFunction.keySet());
        entries.addAll(target.coveredByFunction.keySet());

        List<FunctionDiff> out = new ArrayList<>();
        for (Address entry : entries) {
            Function fn = functionManager.getFunctionAt(entry);
            if (fn == null) {
                continue;
            }
            Set<Address> baseStarts = baseline.coveredByFunction.getOrDefault(entry, java.util.Collections.emptySet());
            Set<Address> targStarts = target.coveredByFunction.getOrDefault(entry, java.util.Collections.emptySet());
            int targetOnly = 0;
            for (Address s : targStarts) {
                if (!baseStarts.contains(s)) {
                    targetOnly++;
                }
            }
            int total = countBlocks(fn.getBody());
            out.add(new FunctionDiff(fn.getName(), entry, total,
                    baseStarts.size(), targStarts.size(), targetOnly));
        }
        // Most interesting first: functions the target reached uniquely.
        out.sort((a, b) -> {
            if (b.targetOnlyBlocks != a.targetOnlyBlocks) {
                return Integer.compare(b.targetOnlyBlocks, a.targetOnlyBlocks);
            }
            return Integer.compare(b.targetBlocks, a.targetBlocks);
        });
        return out;
    }

    /** Backward-compatible single-file convenience: compute then paint green. */
    public Result apply(Coverage cov, Coverage.Module module) throws Exception {
        return paintSingle(computeCoverage(cov, module));
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
