package org.emergent.maven.linkage.model;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.Value;
import org.objectweb.asm.Type;

/**
 * Outcome of a linkage validation run. Class names are in dotted (binary) form for display.
 */
@Value
public class ValidationReport {
    List<MissingClass> missingClasses;
    List<MissingMethod> missingMethods;

    public boolean isClean() {
        return missingClasses.isEmpty() && missingMethods.isEmpty();
    }

    /**
     * A referenced class that no analyzed artifact nor the JDK provides.
     */
    @Value
    public static class MissingClass {
        String className;
        Set<String> referencedBy;
    }

    /**
     * An invoked method that its (present) owner hierarchy does not declare.
     */
    @Value
    public static class MissingMethod {
        String owner;
        String name;
        String descriptor;
        Set<String> referencedBy;

        public String describe() {
            String args = Arrays.stream(Type.getMethodType(descriptor).getArgumentTypes())
                .map(Type::getClassName)
                .collect(Collectors.joining(", "));
            return owner + "." + name + "(" + args + ")";
        }
    }
}
