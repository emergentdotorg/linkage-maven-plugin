package org.emergent.maven.linkage.model;

import lombok.Value;

/**
 * A method declared by a class: name plus JVM descriptor and access flags.
 */
@Value
public class MethodInfo {
    String name;
    String descriptor;
    int access;
}
