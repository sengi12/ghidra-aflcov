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

    /** Parse and paint the given drcov file, then refresh the table. */
    void applyCoverage(File file);

    /** Remove all coverage colouring. */
    void clearCoverage();

    /** Navigate the Listing (and Graph) to an address. */
    void navigateTo(Address address);
}
