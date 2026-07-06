package org.emergent.maven.linkage.fixtures;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public abstract class AbstractBase implements Greeter {

    protected final List<String> items = new ArrayList<>();

    public int count() {
        return items.size();
    }

    public abstract void run() throws IOException;
}
