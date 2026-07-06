package org.emergent.it;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import org.apache.commons.io.IOUtils;

public class UsesIo {

    public static long drain() throws IOException {
        try (Reader reader = new StringReader("hello")) {
            return IOUtils.consume(reader);
        }
    }
}
