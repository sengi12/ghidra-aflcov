package resources;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads a DynamoRIO drcov coverage file into a {@link Coverage}.
 *
 * drcov is a hybrid format: a text header (version line, module table) followed
 * by a binary basic-block table. Because the two halves share a byte stream, the
 * file is read whole and the split between text and binary is found by scanning
 * for the "BB Table:" line. This is the same format Lighthouse and Dragondance
 * consume, so files produced by DynamoRIO's drcov tool parse here too - not only
 * the ones our own Unicorn collector writes.
 *
 * Supported: versioned module tables (drcov v2 and later), which carry a
 * "Columns:" line naming the fields. That covers modern DynamoRIO output and the
 * format the afl-unicorn collector emits.
 */
public class DrcovParser {

    /** Each drcov basic-block table entry is 8 bytes: u32 start, u16 size, u16 module id. */
    private static final int BB_ENTRY_SIZE = 8;

    public static class ParseException extends Exception {
        public ParseException(String msg) {
            super(msg);
        }
    }

    public Coverage parse(File file) throws IOException, ParseException {
        byte[] raw = Files.readAllBytes(file.toPath());
        Coverage cov = new Coverage();

        int bbCount = 0;
        int cursor = 0;               // byte offset of the start of the binary BB table
        String[] columns = null;

        // Walk the header line by line until the BB table, then stop: everything
        // after the BB Table line's newline is binary and must not be decoded.
        int lineStart = 0;
        boolean inModuleTable = false;
        for (int i = 0; i < raw.length; i++) {
            if (raw[i] != '\n') {
                continue;
            }
            String line = new String(raw, lineStart, i - lineStart, StandardCharsets.UTF_8).trim();
            lineStart = i + 1;

            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("DRCOV VERSION") || line.startsWith("DRCOV FLAVOR")) {
                continue;
            }
            if (line.startsWith("Module Table")) {
                inModuleTable = true;
                columns = null;
                continue;
            }
            if (line.startsWith("Columns:")) {
                columns = splitCsv(line.substring("Columns:".length()));
                continue;
            }
            if (line.startsWith("BB Table:")) {
                bbCount = parseBbCount(line);
                cursor = lineStart;   // binary blob begins right after this newline
                break;
            }
            if (inModuleTable) {
                Coverage.Module m = parseModuleRow(line, columns);
                if (m != null) {
                    cov.addModule(m);
                }
            }
        }

        if (cursor == 0) {
            throw new ParseException("no 'BB Table:' section found - not a drcov file?");
        }

        long need = (long) bbCount * BB_ENTRY_SIZE;
        if (cursor + need > raw.length) {
            throw new ParseException("BB table truncated: header claims " + bbCount
                    + " blocks (" + need + " bytes) but only " + (raw.length - cursor)
                    + " bytes remain");
        }

        ByteBuffer buf = ByteBuffer.wrap(raw, cursor, (int) need).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < bbCount; i++) {
            long start = Integer.toUnsignedLong(buf.getInt());
            int size = Short.toUnsignedInt(buf.getShort());
            int modId = Short.toUnsignedInt(buf.getShort());
            cov.addBlock(new Coverage.Block(modId, start, size));
        }
        return cov;
    }

    private int parseBbCount(String line) throws ParseException {
        // "BB Table: 1234 bbs"
        String rest = line.substring("BB Table:".length()).trim();
        int space = rest.indexOf(' ');
        String num = space >= 0 ? rest.substring(0, space) : rest;
        try {
            return Integer.parseInt(num.trim());
        } catch (NumberFormatException e) {
            throw new ParseException("could not read basic-block count from: " + line);
        }
    }

    private Coverage.Module parseModuleRow(String line, String[] columns) {
        // A versioned module table needs its Columns header to know field order.
        if (columns == null) {
            return null;
        }
        String[] fields = splitCsv(line);
        Map<String, String> row = new HashMap<>();
        for (int i = 0; i < columns.length && i < fields.length; i++) {
            row.put(columns[i].toLowerCase(), fields[i]);
        }
        if (!row.containsKey("id") || !row.containsKey("base")) {
            return null;   // a header or blank row, not module data
        }
        try {
            int id = (int) parseNumber(row.get("id"));
            long base = parseNumber(row.get("base"));
            long end = row.containsKey("end") ? parseNumber(row.get("end")) : 0;
            String path = row.getOrDefault("path", "");
            return new Coverage.Module(id, base, end, path);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String[] splitCsv(String s) {
        String[] parts = s.split(",");
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].trim();
        }
        return parts;
    }

    private static long parseNumber(String s) {
        String t = s.trim();
        if (t.startsWith("0x") || t.startsWith("0X")) {
            return Long.parseUnsignedLong(t.substring(2), 16);
        }
        return Long.parseLong(t);
    }
}
