package org.emergent.maven.linkage.scan;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.emergent.maven.linkage.model.ClassSurface;
import org.emergent.maven.linkage.model.MethodInfo;
import org.emergent.maven.linkage.model.MethodRef;
import org.emergent.maven.linkage.model.ScanResult;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.TypePath;

/**
 * Extracts a {@link ScanResult} from the bytecode of a jar or a classes directory: the classes
 * and methods it provides, and the classes and method signatures its code references.
 */
@Slf4j
public class BytecodeScanner {

    private static final int PARSING_OPTIONS = ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG;

    public ScanResult scanJar(Path jar, String originLabel) throws IOException {
        Collector collector = new Collector();
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (!isScannableClass(entry.getName())) {
                    continue;
                }
                try (InputStream in = jarFile.getInputStream(entry)) {
                    scanClass(in.readAllBytes(), collector, jar + "!" + entry.getName());
                }
            }
        }
        return collector.toResult(originLabel);
    }

    public ScanResult scanDirectory(Path classesDir, String originLabel) throws IOException {
        Collector collector = new Collector();
        try (Stream<Path> files = Files.walk(classesDir)) {
            for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                String relative =
                    classesDir.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/");
                if (isScannableClass(relative)) {
                    scanClass(Files.readAllBytes(file), collector, file.toString());
                }
            }
        }
        return collector.toResult(originLabel);
    }

    private static boolean isScannableClass(String path) {
        // META-INF/ covers multi-release overlays (META-INF/versions/**); the base entries of a
        // multi-release jar are required to be API-complete, so skipping the overlays is sound.
        return path.endsWith(".class")
            && !path.startsWith("META-INF/")
            && !path.endsWith("module-info.class");
    }

    private static void scanClass(byte[] bytes, Collector collector, String source) {
        try {
            new ClassReader(bytes).accept(new SurfaceAndRefsVisitor(collector), PARSING_OPTIONS);
        } catch (RuntimeException e) {
            log.warn("Skipping unreadable class {}: {}", source, e.toString());
        }
    }

    /**
     * Mutable accumulation of provided classes and outgoing references while visiting one
     * artifact's class files.
     */
    static final class Collector {
        private final Map<String, ClassSurface> provided = new LinkedHashMap<>();
        private final Set<String> referencedClasses = new TreeSet<>();
        private final Set<MethodRef> referencedMethods = new HashSet<>();

        void addProvided(ClassSurface surface) {
            provided.putIfAbsent(surface.getInternalName(), surface);
        }

        void refType(Type type) {
            switch (type.getSort()) {
                case Type.ARRAY -> refType(type.getElementType());
                case Type.OBJECT -> referencedClasses.add(type.getInternalName());
                case Type.METHOD -> {
                    for (Type argument : type.getArgumentTypes()) {
                        refType(argument);
                    }
                    refType(type.getReturnType());
                }
                default -> { /* primitives and void */ }
            }
        }

        void refInternalName(@Nullable String internalName) {
            if (internalName == null) {
                return;
            }
            // visitTypeInsn and handle owners may carry array descriptors instead of class names
            if (internalName.charAt(0) == '[') {
                refType(Type.getType(internalName));
            } else {
                referencedClasses.add(internalName);
            }
        }

        void refDescriptor(String descriptor) {
            refType(Type.getType(descriptor));
        }

        void refMethodDescriptor(String descriptor) {
            refType(Type.getMethodType(descriptor));
        }

        void refMethod(String owner, String name, String descriptor) {
            refMethodDescriptor(descriptor);
            refInternalName(owner);
            // JVM resolution of methods on array types (clone() etc.) lands on java.lang.Object
            String normalizedOwner = owner.charAt(0) == '[' ? "java/lang/Object" : owner;
            referencedMethods.add(new MethodRef(normalizedOwner, name, descriptor));
        }

        void refHandle(Handle handle) {
            switch (handle.getTag()) {
                case Opcodes.H_INVOKEVIRTUAL, Opcodes.H_INVOKESTATIC, Opcodes.H_INVOKESPECIAL,
                     Opcodes.H_NEWINVOKESPECIAL, Opcodes.H_INVOKEINTERFACE ->
                    refMethod(handle.getOwner(), handle.getName(), handle.getDesc());
                default -> {
                    refInternalName(handle.getOwner());
                    refDescriptor(handle.getDesc());
                }
            }
        }

        void refConstant(Object value) {
            if (value instanceof Type type) {
                refType(type);
            } else if (value instanceof Handle handle) {
                refHandle(handle);
            } else if (value instanceof ConstantDynamic condy) {
                refDescriptor(condy.getDescriptor());
                refHandle(condy.getBootstrapMethod());
                for (int i = 0; i < condy.getBootstrapMethodArgumentCount(); i++) {
                    refConstant(condy.getBootstrapMethodArgument(i));
                }
            }
        }

        ScanResult toResult(String originLabel) {
            return ScanResult.builder()
                .originLabel(originLabel)
                .provided(Map.copyOf(provided))
                .referencedClasses(Set.copyOf(referencedClasses))
                .referencedMethods(Set.copyOf(referencedMethods))
                .build();
        }
    }

    static final class SurfaceAndRefsVisitor extends ClassVisitor {
        private final Collector collector;
        private String internalName;
        private int access;
        private @Nullable String superName;
        private List<String> interfaces = List.of();
        private final Set<MethodInfo> methods = new LinkedHashSet<>();

        SurfaceAndRefsVisitor(Collector collector) {
            super(Opcodes.ASM9);
            this.collector = collector;
        }

        @Override
        public void visit(
            int version,
            int access,
            String name,
            String signature,
            String superName,
            String[] interfaces) {
            this.internalName = name;
            this.access = access;
            this.superName = superName;
            this.interfaces = interfaces == null ? List.of() : List.of(interfaces);
            collector.refInternalName(superName);
            this.interfaces.forEach(collector::refInternalName);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            collector.refDescriptor(descriptor);
            return new RefAnnotationVisitor(collector);
        }

        @Override
        public AnnotationVisitor visitTypeAnnotation(
            int typeRef,
            TypePath typePath,
            String descriptor,
            boolean visible) {
            collector.refDescriptor(descriptor);
            return new RefAnnotationVisitor(collector);
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            collector.refDescriptor(descriptor);
            return new FieldVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
                    collector.refDescriptor(annotationDescriptor);
                    return new RefAnnotationVisitor(collector);
                }

                @Override
                public AnnotationVisitor visitTypeAnnotation(
                    int typeRef,
                    TypePath typePath,
                    String annotationDescriptor,
                    boolean visible) {
                    collector.refDescriptor(annotationDescriptor);
                    return new RefAnnotationVisitor(collector);
                }
            };
        }

        @Override
        public MethodVisitor visitMethod(
            int access,
            String name,
            String descriptor,
            String signature,
            String[] exceptions) {
            methods.add(new MethodInfo(name, descriptor, access));
            collector.refMethodDescriptor(descriptor);
            if (exceptions != null) {
                for (String exception : exceptions) {
                    collector.refInternalName(exception);
                }
            }
            return new RefMethodVisitor(collector);
        }

        @Override
        public void visitEnd() {
            collector.addProvided(new ClassSurface(internalName, access, superName, interfaces, Set.copyOf(methods)));
        }
    }

    static final class RefMethodVisitor extends MethodVisitor {
        private final Collector collector;

        RefMethodVisitor(Collector collector) {
            super(Opcodes.ASM9);
            this.collector = collector;
        }

        @Override
        public AnnotationVisitor visitAnnotationDefault() {
            return new RefAnnotationVisitor(collector);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            collector.refDescriptor(descriptor);
            return new RefAnnotationVisitor(collector);
        }

        @Override
        public AnnotationVisitor visitTypeAnnotation(
            int typeRef,
            TypePath typePath,
            String descriptor,
            boolean visible) {
            collector.refDescriptor(descriptor);
            return new RefAnnotationVisitor(collector);
        }

        @Override
        public AnnotationVisitor visitParameterAnnotation(int parameter, String descriptor, boolean visible) {
            collector.refDescriptor(descriptor);
            return new RefAnnotationVisitor(collector);
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            collector.refInternalName(type);
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            collector.refInternalName(owner);
            collector.refDescriptor(descriptor);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            collector.refMethod(owner, name, descriptor);
        }

        @Override
        public void visitInvokeDynamicInsn(
            String name,
            String descriptor,
            Handle bootstrapMethodHandle,
            Object... bootstrapMethodArguments) {
            collector.refMethodDescriptor(descriptor);
            collector.refHandle(bootstrapMethodHandle);
            for (Object argument : bootstrapMethodArguments) {
                collector.refConstant(argument);
            }
        }

        @Override
        public void visitLdcInsn(Object value) {
            collector.refConstant(value);
        }

        @Override
        public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
            collector.refInternalName(type);
        }

        @Override
        public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
            collector.refDescriptor(descriptor);
        }
    }

    static final class RefAnnotationVisitor extends AnnotationVisitor {
        private final Collector collector;

        RefAnnotationVisitor(Collector collector) {
            super(Opcodes.ASM9);
            this.collector = collector;
        }

        @Override
        public void visit(String name, Object value) {
            collector.refConstant(value);
        }

        @Override
        public void visitEnum(String name, String descriptor, String value) {
            collector.refDescriptor(descriptor);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String name, String descriptor) {
            collector.refDescriptor(descriptor);
            return this;
        }

        @Override
        public AnnotationVisitor visitArray(String name) {
            return this;
        }
    }
}
