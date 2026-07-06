package org.emergent.maven.linkage.model;

import java.util.List;
import java.util.Set;
import lombok.Value;
import org.jspecify.annotations.Nullable;

/**
 * The declared surface of a single class: identity, hierarchy links and declared methods
 * (including constructors, static initializers, synthetic and bridge methods).
 * All class names are JVM internal names ({@code java/lang/String}).
 */
@Value
public class ClassSurface {
    String internalName;
    int access;
    @Nullable String superName;
    List<String> interfaces;
    Set<MethodInfo> methods;
}
