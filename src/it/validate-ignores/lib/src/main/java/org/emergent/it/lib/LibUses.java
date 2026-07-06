package org.emergent.it.lib;

import java.io.IOException;
import java.io.Reader;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.function.Uncheck;

public class LibUses {

    /** IOUtils.consume(Reader) was added in commons-io 2.12.0. */
    public static long drain(Reader reader) throws IOException {
        return IOUtils.consume(reader);
    }

    /** org.apache.commons.io.function.Uncheck was added in commons-io 2.12.0. */
    public static String constant() {
        return Uncheck.get(() -> "x");
    }
}
