package org.emergent.maven.linkage.validate;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import lombok.extern.slf4j.Slf4j;
import org.emergent.maven.linkage.model.ClassSurface;
import org.emergent.maven.linkage.model.MethodInfo;
import org.emergent.maven.linkage.model.MethodRef;
import org.emergent.maven.linkage.model.ScanResult;
import org.emergent.maven.linkage.model.ValidationReport;
import org.emergent.maven.linkage.model.ValidationReport.MissingClass;
import org.emergent.maven.linkage.model.ValidationReport.MissingMethod;
import org.emergent.maven.linkage.scan.ClassResolver;
import org.objectweb.asm.Opcodes;

/**
 * Cross-checks referenced classes and method signatures against the union of all analyzed
 * artifacts plus a fallback {@link ClassResolver} (the JDK).
 */
@Slf4j
public class LinkageValidator {

    private static final Comparator<MethodRef> METHOD_REF_ORDER = Comparator
        .comparing(MethodRef::getOwner)
        .thenComparing(MethodRef::getName)
        .thenComparing(MethodRef::getDescriptor);

    private enum Resolution {
        FOUND,
        NOT_FOUND,
        UNRESOLVABLE
    }

    /**
     * @param indexScans     artifacts whose provided classes form the resolution universe
     * @param referenceScans artifacts whose outgoing references are validated (a subset of
     *                       {@code indexScans}: all of them, or only the project's classes)
     */
    public ValidationReport validate(
        List<ScanResult> indexScans, List<ScanResult> referenceScans,
        ClassResolver fallbackResolver, IgnorePatterns ignores) {
        Lookup lookup = new Lookup(buildIndex(indexScans), fallbackResolver);

        Map<String, Set<String>> missingClasses = new TreeMap<>();
        Map<MethodRef, Set<String>> missingMethods = new TreeMap<>(METHOD_REF_ORDER);

        for (ScanResult scan : referenceScans) {
            for (String className : scan.getReferencedClasses()) {
                if (ignores.isIgnored(dotted(className))) {
                    continue;
                }
                if (lookup.find(className).isEmpty()) {
                    missingClasses.computeIfAbsent(dotted(className), k -> new TreeSet<>()).add(scan.getOriginLabel());
                }
            }
            for (MethodRef ref : scan.getReferencedMethods()) {
                if (ignores.isIgnored(dotted(ref.getOwner()))) {
                    continue;
                }
                Optional<ClassSurface> owner = lookup.find(ref.getOwner());
                if (owner.isEmpty()) {
                    continue; // already reported as a missing class
                }
                if (resolveMethod(owner.get(), ref, lookup) == Resolution.NOT_FOUND) {
                    missingMethods.computeIfAbsent(ref, k -> new TreeSet<>()).add(scan.getOriginLabel());
                }
            }
        }

        return new ValidationReport(
            missingClasses.entrySet().stream()
                .map(e -> new MissingClass(e.getKey(), e.getValue()))
                .toList(),
            missingMethods.entrySet().stream()
                .map(e -> new MissingMethod(
                    dotted(e.getKey().getOwner()), e.getKey().getName(),
                    e.getKey().getDescriptor(), e.getValue()))
                .toList());
    }

    private Map<String, ClassSurface> buildIndex(List<ScanResult> scans) {
        Map<String, ClassSurface> index = new HashMap<>();
        for (ScanResult scan : scans) {
            scan.getProvided().forEach((name, surface) -> {
                if (index.putIfAbsent(name, surface) != null && log.isDebugEnabled()) {
                    log.debug("Duplicate class {} also provided by {}", dotted(name), scan.getOriginLabel());
                }
            });
        }
        return index;
    }

    private record Lookup(Map<String, ClassSurface> index, ClassResolver fallback,
                          Map<String, Optional<ClassSurface>> cache) {

        Lookup(Map<String, ClassSurface> index, ClassResolver fallback) {
            this(index, fallback, new HashMap<>());
        }

        Optional<ClassSurface> find(String internalName) {
            ClassSurface fromIndex = index.get(internalName);
            if (fromIndex != null) {
                return Optional.of(fromIndex);
            }
            return cache.computeIfAbsent(internalName, fallback::resolve);
        }
    }

    /**
     * JVM-style resolution: the owner class, its superclass chain, then a breadth-first walk of
     * all superinterfaces. A missing ancestor makes the reference unresolvable rather than a
     * finding, so one absent optional superclass does not cascade into false positives.
     */
    private Resolution resolveMethod(ClassSurface owner, MethodRef ref, Lookup lookup) {
        if (isSignaturePolymorphic(owner, ref)) {
            return Resolution.FOUND;
        }
        Deque<String> interfaceQueue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        ClassSurface current = owner;
        while (true) {
            if (declares(current, ref)) {
                return Resolution.FOUND;
            }
            interfaceQueue.addAll(current.getInterfaces());
            String superName = current.getSuperName();
            if (superName == null) {
                break;
            }
            Optional<ClassSurface> superSurface = lookup.find(superName);
            if (superSurface.isEmpty()) {
                return Resolution.UNRESOLVABLE;
            }
            current = superSurface.get();
        }
        while (!interfaceQueue.isEmpty()) {
            String interfaceName = interfaceQueue.poll();
            if (!visited.add(interfaceName)) {
                continue;
            }
            Optional<ClassSurface> interfaceSurface = lookup.find(interfaceName);
            if (interfaceSurface.isEmpty()) {
                return Resolution.UNRESOLVABLE;
            }
            if (declares(interfaceSurface.get(), ref)) {
                return Resolution.FOUND;
            }
            interfaceQueue.addAll(interfaceSurface.get().getInterfaces());
        }
        return Resolution.NOT_FOUND;
    }

    private static boolean declares(ClassSurface surface, MethodRef ref) {
        return surface.getMethods().stream()
            .anyMatch(m -> m.getName().equals(ref.getName()) && m.getDescriptor().equals(ref.getDescriptor()));
    }

    /**
     * {@code MethodHandle.invoke}/{@code VarHandle.get} and friends accept any descriptor at the
     * call site; without this every such call would be a false positive.
     */
    private static boolean isSignaturePolymorphic(ClassSurface owner, MethodRef ref) {
        if (!owner.getInternalName().equals("java/lang/invoke/MethodHandle")
            && !owner.getInternalName().equals("java/lang/invoke/VarHandle")) {
            return false;
        }
        int required = Opcodes.ACC_VARARGS | Opcodes.ACC_NATIVE;
        for (MethodInfo method : owner.getMethods()) {
            if (method.getName().equals(ref.getName()) && (method.getAccess() & required) == required) {
                return true;
            }
        }
        return false;
    }

    private static String dotted(String internalName) {
        return internalName.replace('/', '.');
    }
}
