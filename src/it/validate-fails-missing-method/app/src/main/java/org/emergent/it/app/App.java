package org.emergent.it.app;

import java.io.StringReader;
import org.emergent.it.lib.LibUses;

public class App {

    public static void main(String[] args) throws Exception {
        System.out.println(LibUses.drain(new StringReader("x")));
    }
}
