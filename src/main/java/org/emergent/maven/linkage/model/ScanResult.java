package org.emergent.maven.linkage.model;

import java.util.Map;
import java.util.Set;
import lombok.Builder;
import lombok.Value;

/**
 * Everything extracted from one analyzed artifact (a dependency jar or a classes directory):
 * the classes it provides, keyed by internal name, and the classes and methods its bytecode
 * references.
 */
@Value
@Builder
public class ScanResult {
    String originLabel;
    Map<String, ClassSurface> provided;
    Set<String> referencedClasses;
    Set<MethodRef> referencedMethods;
}
