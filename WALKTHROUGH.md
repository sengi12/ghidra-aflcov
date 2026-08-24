# Walkthrough: from a fuzzing crash to a highlighted path in Ghidra

This walks the whole pipeline end to end on macOS (Apple Silicon included):
fuzz a **MIPS** test binary with **AFL++ + unicornafl**, turn the results into
**drcov** coverage, and diff a crash against the corpus in ghidra-aflcov so the
code path unique to the crash lights up.

The target (`simple_target.bin` from the
[afl-unicorn fork](https://github.com/sengi12/afl-unicorn)) is raw MIPS 32-bit
big-endian code loaded at `0x100000` — a different architecture from an ARM Mac,
emulated by Unicorn.

## Prerequisites

- Fuzzing set up per the afl-unicorn fork's
  [INSTALL_MACOS.md](https://github.com/sengi12/afl-unicorn/blob/unicorn2-coverage/unicorn_mode/INSTALL_MACOS.md)
  (AFL++, unicornafl, a Python 3.12 venv).
- Ghidra 10.2+ and this repository.

Set your paths and activate the fuzzing venv:

```sh
export REPO="$HOME/path/to/afl-unicorn"
export AFL="$HOME/Applications/AFLplusplus"
source "$HOME/aflpp-venv/bin/activate"
cd "$REPO/unicorn_mode/samples/simple"
```

## 1 — Fuzz the MIPS target

```sh
export AFL_SKIP_CPUFREQ=1 AFL_NO_AFFINITY=1
"$AFL/afl-fuzz" -U -m none -t 5000 -i ./sample_inputs -o ./output \
  -- python simple_test_harness_aflpp.py @@
```

AFL++ brings up the fork server and fuzzes the emulated MIPS target. Let it run
until **`saved crashes`** is 1 or more, then press **Ctrl-C**.

![AFL++ fuzzing the MIPS target on macOS](./imgs/afl-fuzzing.png)

## 2 — Generate drcov coverage

`--coverage-dir` replays a directory of inputs and writes one drcov per input
plus a merged `_baseline.drcov`:

```sh
python simple_test_harness_aflpp.py --coverage-dir output/default/queue   --coverage-out cov/queue
python simple_test_harness_aflpp.py --coverage-dir output/default/crashes --coverage-out cov/crashes
```

**Want a guaranteed crash?** Craft the deterministic one (byte 20 non-zero) and a
benign input, and skip fuzzing:

```sh
mkdir -p demo/queue demo/crashes
python3 -c "open('demo/queue/benign','wb').write(b'AAAA')"
python3 -c "open('demo/crashes/crash','wb').write(b'A'*20+b'\xff')"
python simple_test_harness_aflpp.py --coverage-dir demo/queue   --coverage-out demo/cov_queue
python simple_test_harness_aflpp.py --coverage-dir demo/crashes --coverage-out demo/cov_crashes
```

## 3 — Import the binary into Ghidra

The plugin adds each drcov block offset to the program's image base, so the
program **must** be based at `0x100000`.

1. **File ▸ Import File** → `simple_target.bin`.
2. **Format:** `Raw Binary`.
3. **Language:** `MIPS:BE:32:default` (MIPS, 32-bit, big-endian).
4. **Options… ▸ Base Address:** `00100000`.
5. Open in the CodeBrowser. Go to `100000` (**G**), press **D** to disassemble,
   then **F** to create a function so there are basic blocks to colour.

## 4 — Load and diff in the plugin

1. Script Manager (green ▶) → **Manage Script Directories** → **+** → add this
   repo → refresh.
2. Run **`AflCoverage.java`** (or **Alt-A**). The **AFL Coverage** window docks.
3. **Baseline…** → `demo/cov_queue/_baseline.drcov` (corpus, painted green).
4. **Diff…** → `demo/cov_crashes/crash.drcov`.

## What you should see

![Crash-only block highlighted in the Ghidra Function Graph](./imgs/ghidra-diff.png)

The diff recolours every block by which set reached it:

| Colour | Meaning | In this example |
|---|---|---|
| **orange-red** | reached only by the crash | `0x00100028` — the `data[20] != 0` crash branch |
| **green** | reached by both | `0x00100000` — the shared entry block |
| **blue** | reached only by the baseline | the normal return path the crash skipped |

The panel summarises the counts ("Diff: 1 crash-only, 1 shared, 5 baseline-only
blocks"), and the table ranks functions by **Crash-only** blocks — double-click a
row to jump there. In the **Function Graph** the orange-red node is exactly where
the crash diverged from every corpus input.

## Troubleshooting

- **Nothing paints / wrong locations** — the program is not based at
  `0x00100000`. Re-import with that base (or rebase in the Memory Map).
- **Raw byte ranges instead of whole blocks** — the code is not disassembled;
  repeat step 3's disassemble / create-function and reload the coverage.
- **"No ColorizingService available"** — run the plugin from Ghidra's
  CodeBrowser tool (a stripped tool lacks the Colorizer).
- **Fuzzing setup errors** (`afl_fuzz` missing, no tuples) — see the fork's
  [INSTALL_MACOS.md](https://github.com/sengi12/afl-unicorn/blob/unicorn2-coverage/unicorn_mode/INSTALL_MACOS.md)
  troubleshooting table.
