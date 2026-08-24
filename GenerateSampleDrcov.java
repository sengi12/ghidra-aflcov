// Generate Sample drcov
// @author Michael Sengelmann
// @category Fuzzing
// @menupath Tools.AFL Coverage.Generate Sample drcov

import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressSetView;
import ghidra.program.model.block.BasicBlockModel;
import ghidra.program.model.block.CodeBlock;
import ghidra.program.model.block.CodeBlockIterator;

/**
 * Writes a drcov coverage file for the current program so ghidra-aflcov can be
 * tested without a real fuzzing run. It walks the program's actual basic blocks
 * and records a subset of them, so the offsets line up with this program by
 * construction and the AFL Coverage panel paints real blocks.
 *
 * If there is a selection in the Listing, only blocks touching the selection are
 * recorded - handy for "select a function, generate, watch it light up".
 * Otherwise a deterministic ~2/3 of all blocks are recorded, which gives a mix
 * of fully- and partially-covered functions in the table.
 *
 * This is a development/test fixture, not part of the coverage workflow; the
 * real collector lives in the afl-unicorn fork's Unicorn harness.
 */
public class GenerateSampleDrcov extends GhidraScript {

    @Override
    protected void run() throws Exception {
        if (currentProgram == null) {
            println("Open a program first.");
            return;
        }

        Address imageBase = currentProgram.getImageBase();
        long base = imageBase.getOffset();
        Address lastAddr = currentProgram.getMaxAddress();
        long end = lastAddr == null ? base : lastAddr.getOffset() + 1;

        AddressSetView selection = currentSelection;   // may be null
        boolean useSelection = selection != null && !selection.isEmpty();

        BasicBlockModel bbm = new BasicBlockModel(currentProgram);
        CodeBlockIterator it = bbm.getCodeBlocks(monitor);

        List<long[]> entries = new ArrayList<>();   // each: {offset, size}
        int index = 0;
        while (it.hasNext()) {
            CodeBlock b = it.next();
            Address start = b.getFirstStartAddress();
            long offset = start.subtract(imageBase);
            if (offset < 0 || offset > 0xFFFFFFFFL) {
                continue;                            // outside the module image
            }

            boolean take;
            if (useSelection) {
                take = selection.intersects(b);
            } else {
                take = (index % 3 != 0);             // deterministic ~66%
            }
            index++;
            if (!take) {
                continue;
            }

            long len = b.getMaxAddress().subtract(b.getMinAddress()) + 1;
            if (len < 1) {
                len = 1;
            }
            if (len > 0xFFFF) {
                len = 0xFFFF;
            }
            entries.add(new long[] {offset, len});
        }

        if (entries.isEmpty()) {
            println("No basic blocks selected - nothing to write. "
                    + "Make a selection or run auto-analysis first.");
            return;
        }

        java.io.File out = askFile("Save sample drcov file", "Save");
        String path = currentProgram.getName();      // so the plugin's name match picks this module

        StringBuilder header = new StringBuilder();
        header.append("DRCOV VERSION: 2\n");
        header.append("DRCOV FLAVOR: drcov\n");
        header.append("Module Table: version 2, count 1\n");
        header.append("Columns: id, base, end, entry, checksum, timestamp, path\n");
        header.append(String.format("  0, 0x%016x, 0x%016x, 0x%016x, 0x0, 0x0, %s\n",
                base, end, base, path));
        header.append(String.format("BB Table: %d bbs\n", entries.size()));

        ByteBuffer buf = ByteBuffer.allocate(entries.size() * 8).order(ByteOrder.LITTLE_ENDIAN);
        for (long[] e : entries) {
            buf.putInt((int) (e[0] & 0xFFFFFFFFL));  // u32 start (offset from base)
            buf.putShort((short) (e[1] & 0xFFFF));   // u16 size
            buf.putShort((short) 0);                 // u16 module id
        }

        try (OutputStream os = new FileOutputStream(out)) {
            os.write(header.toString().getBytes(StandardCharsets.UTF_8));
            os.write(buf.array());
        }

        println(String.format("Wrote %d blocks to %s (image base 0x%x, %s).",
                entries.size(), out.getAbsolutePath(), base,
                useSelection ? "from selection" : "deterministic subset"));
        println("Now run AflCoverage.java and load this file.");
    }
}
