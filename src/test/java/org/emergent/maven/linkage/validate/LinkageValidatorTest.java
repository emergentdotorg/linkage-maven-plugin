package org.emergent.maven.linkage.validate;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.emergent.maven.linkage.model.ClassSurface;
import org.emergent.maven.linkage.model.MethodInfo;
import org.emergent.maven.linkage.model.MethodRef;
import org.emergent.maven.linkage.model.ScanResult;
import org.emergent.maven.linkage.model.ValidationReport;
import org.emergent.maven.linkage.scan.ClassResolver;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;

class LinkageValidatorTest {

    private static final String OBJECT = "java/lang/Object";

    private final LinkageValidator validator = new LinkageValidator();
    private final ClassResolver jdkStub = stubResolver(
        surface(
            OBJECT, null, List.of(),
            method("<init>", "()V"),
            method("toString", "()Ljava/lang/String;"),
            method("clone", "()Ljava/lang/Object;")));

    @Test
    void cleanWhenEverythingResolves() {
        ScanResult app = scanOf(
            "app",
            List.of(surface("com/example/App", OBJECT, List.of())),
            Set.of("com/example/Lib"),
            Set.of(new MethodRef("com/example/Lib", "doIt", "()V")));
        ScanResult lib = scanOf(
            "lib",
            List.of(surface("com/example/Lib", OBJECT, List.of(), method("doIt", "()V"))),
            Set.of(), Set.of());

        ValidationReport report =
            validator.validate(List.of(app, lib), List.of(app, lib), jdkStub, IgnorePatterns.NONE);

        assertThat(report.isClean()).isTrue();
    }

    @Test
    void missingClassReportedWithOrigin() {
        ScanResult app = scanOf("app", List.of(), Set.of("com/example/Gone"), Set.of());

        ValidationReport report = validator.validate(List.of(app), List.of(app), jdkStub, IgnorePatterns.NONE);

        assertThat(report.getMissingClasses()).hasSize(1);
        assertThat(report.getMissingClasses().get(0).getClassName()).isEqualTo("com.example.Gone");
        assertThat(report.getMissingClasses().get(0).getReferencedBy()).containsExactly("app");
    }

    @Test
    void missingMethodReportedWhenOwnerPresent() {
        ScanResult app = scanOf(
            "app", List.of(),
            Set.of("com/example/Lib"),
            Set.of(new MethodRef("com/example/Lib", "newMethod", "(Ljava/io/Reader;)V")));
        ScanResult lib = scanOf(
            "lib",
            List.of(surface("com/example/Lib", OBJECT, List.of(), method("oldMethod", "()V"))),
            Set.of(), Set.of());

        ValidationReport report =
            validator.validate(List.of(app, lib), List.of(app, lib), jdkStub, IgnorePatterns.NONE);

        assertThat(report.getMissingClasses()).isEmpty();
        assertThat(report.getMissingMethods()).hasSize(1);
        assertThat(report.getMissingMethods().get(0).describe())
            .isEqualTo("com.example.Lib.newMethod(java.io.Reader)");
        assertThat(report.getMissingMethods().get(0).getReferencedBy()).containsExactly("app");
    }

    @Test
    void methodFoundOnSuperclassAndInterfaceDefault() {
        ScanResult lib = scanOf(
            "lib",
            List.of(
                surface("com/example/Base", OBJECT, List.of(), method("inherited", "()V")),
                surface("com/example/Iface", OBJECT, List.of(), method("defaulted", "()V")),
                surface("com/example/Impl", "com/example/Base", List.of("com/example/Iface"))),
            Set.of(),
            Set.of(
                new MethodRef("com/example/Impl", "inherited", "()V"),
                new MethodRef("com/example/Impl", "defaulted", "()V"),
                new MethodRef("com/example/Impl", "toString", "()Ljava/lang/String;")));

        ValidationReport report = validator.validate(List.of(lib), List.of(lib), jdkStub, IgnorePatterns.NONE);

        assertThat(report.isClean()).isTrue();
    }

    @Test
    void missingOwnerYieldsMissingClassButNoMethodFinding() {
        ScanResult app = scanOf(
            "app", List.of(),
            Set.of("com/example/Gone"),
            Set.of(new MethodRef("com/example/Gone", "anything", "()V")));

        ValidationReport report = validator.validate(List.of(app), List.of(app), jdkStub, IgnorePatterns.NONE);

        assertThat(report.getMissingClasses()).hasSize(1);
        assertThat(report.getMissingMethods()).isEmpty();
    }

    @Test
    void unresolvableAncestorSuppressesMethodFinding() {
        ScanResult lib = scanOf(
            "lib",
            List.of(surface("com/example/Sub", "com/example/GoneSuper", List.of())),
            Set.of(),
            Set.of(new MethodRef("com/example/Sub", "notDeclaredHere", "()V")));

        ValidationReport report = validator.validate(List.of(lib), List.of(lib), jdkStub, IgnorePatterns.NONE);

        assertThat(report.getMissingMethods()).isEmpty();
    }

    @Test
    void ignorePatternsSuppressBothFindingKinds() {
        ScanResult app = scanOf(
            "app", List.of(),
            Set.of("com/example/Gone"),
            Set.of(new MethodRef("java/lang/Object", "notAMethod", "()V")));

        ValidationReport report = validator.validate(
            List.of(app), List.of(app), jdkStub,
            IgnorePatterns.compile(List.of("com.example.*", "java.lang.Object")));

        assertThat(report.isClean()).isTrue();
    }

    @Test
    void onlyReferenceScansAreValidated() {
        ScanResult project = scanOf("project classes", List.of(), Set.of(), Set.of());
        ScanResult dep = scanOf("dep", List.of(), Set.of("com/example/Gone"), Set.of());

        ValidationReport report =
            validator.validate(List.of(project, dep), List.of(project), jdkStub, IgnorePatterns.NONE);

        assertThat(report.isClean()).isTrue();
    }

    @Test
    void signaturePolymorphicInvokeMatchesAnyDescriptor() {
        ClassSurface methodHandle = surface(
            "java/lang/invoke/MethodHandle", OBJECT, List.of(),
            new MethodInfo(
                "invoke", "([Ljava/lang/Object;)Ljava/lang/Object;",
                Opcodes.ACC_PUBLIC | Opcodes.ACC_VARARGS | Opcodes.ACC_NATIVE | Opcodes.ACC_FINAL));
        ClassResolver resolver = stubResolver(
            surface(OBJECT, null, List.of(), method("<init>", "()V")), methodHandle);

        ScanResult app = scanOf(
            "app", List.of(),
            Set.of("java/lang/invoke/MethodHandle"),
            Set.of(new MethodRef("java/lang/invoke/MethodHandle", "invoke", "(Ljava/lang/String;I)J")));

        ValidationReport report = validator.validate(List.of(app), List.of(app), resolver, IgnorePatterns.NONE);

        assertThat(report.isClean()).isTrue();
    }

    private static ClassResolver stubResolver(ClassSurface... surfaces) {
        Map<String, ClassSurface> byName = java.util.Arrays.stream(surfaces)
            .collect(Collectors.toMap(ClassSurface::getInternalName, Function.identity()));
        return internalName -> Optional.ofNullable(byName.get(internalName));
    }

    private static ClassSurface surface(
        String internalName, String superName, List<String> interfaces,
        MethodInfo... methods) {
        return new ClassSurface(internalName, Opcodes.ACC_PUBLIC, superName, interfaces, Set.of(methods));
    }

    private static MethodInfo method(String name, String descriptor) {
        return new MethodInfo(name, descriptor, Opcodes.ACC_PUBLIC);
    }

    private static ScanResult scanOf(
        String label, List<ClassSurface> provided,
        Set<String> referencedClasses, Set<MethodRef> referencedMethods) {
        return ScanResult.builder()
            .originLabel(label)
            .provided(provided.stream().collect(Collectors.toMap(ClassSurface::getInternalName, Function.identity())))
            .referencedClasses(referencedClasses)
            .referencedMethods(referencedMethods)
            .build();
    }
}
