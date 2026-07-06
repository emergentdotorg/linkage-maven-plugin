package org.emergent.maven.linkage.fixtures;

public interface Greeter {

    String name();

    default String greet() {
        return "Hello " + name();
    }
}
