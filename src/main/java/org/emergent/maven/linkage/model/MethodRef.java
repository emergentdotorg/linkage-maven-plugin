package org.emergent.maven.linkage.model;

import lombok.Value;

/**
 * A method invocation site: owner internal name (never an array type; array owners are
 * normalized to {@code java/lang/Object} by the scanner), method name and JVM descriptor.
 */
@Value
public class MethodRef {
    String owner;
    String name;
    String descriptor;
}
