package resources;

import java.io.File;

import ghidra.program.model.address.Address;

/**
 * The panel talks back to the entry script through this narrow interface rather
 * than holding a reference to the script's full API. It lives in the resources
 * package so both sides can name it - the entry script sits in the default
 * package and cannot be imported from here.
 */
public interface CoverageActions {

    /** Parse and paint one drcov file green (single-set view). */
    void applyCoverage(File file);

    /** Load a drcov file as the diff baseline (painted green) and remember it. */
    void loadBaseline(File file);

    /**
     * Load a drcov file as the diff target and paint it against the baseline:
     * blocks reached only by this file (e.g. a crash) stand out. Requires a
     * baseline to have been loaded first.
     */
    void diffWith(File file);

    /** Remove all coverage colouring and forget any baseline. */
    void clearCoverage();

    /** Navigate the Listing (and Graph) to an address. */
    void navigateTo(Address address);
}
