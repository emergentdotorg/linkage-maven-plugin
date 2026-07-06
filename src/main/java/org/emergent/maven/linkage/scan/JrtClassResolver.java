package org.emergent.maven.linkage.scan;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.emergent.maven.linkage.model.ClassSurface;
import org.emergent.maven.linkage.model.MethodInfo;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Resolves JDK classes from the running JVM's {@code jrt:/} image, parsed with ASM.
 * <p>
 * This deliberately sees only the JDK: resolving via reflection in the plugin's class loader
 * would also see Maven and plugin classes and silently mask genuinely missing dependencies.
 * The surface is that of the JVM running Maven, which may differ from the project's target JDK
 * under toolchains.
 */
@Slf4j
public class JrtClassResolver implements ClassResolver {

    private final FileSystem jrtFs = FileSystems.getFileSystem(URI.create("jrt:/"));
    private final Map<String, Optional<ClassSurface>> cache = new ConcurrentHashMap<>();

    @Override
    public Optional<ClassSurface> resolve(String internalName) {
        return cache.computeIfAbsent(internalName, this::load);
    }

    private Optional<ClassSurface> load(String internalName) {
        int lastSlash = internalName.lastIndexOf('/');
        if (lastSlash < 0) {
            return Optional.empty(); // the JDK has no unnamed-package classes
        }
        String packageName = internalName.substring(0, lastSlash).replace('/', '.');
        Path packageDir = jrtFs.getPath("/packages", packageName);
        if (!Files.isDirectory(packageDir)) {
            return Optional.empty();
        }
        try (Stream<Path> moduleLinks = Files.list(packageDir)) {
            for (Path moduleLink : (Iterable<Path>) moduleLinks::iterator) {
                Path classFile =
                    jrtFs.getPath("/modules", moduleLink.getFileName().toString(), internalName + ".class");
                if (Files.exists(classFile)) {
                    return Optional.of(readSurface(Files.readAllBytes(classFile)));
                }
            }
        } catch (IOException e) {
            log.warn("Failed reading {} from the JDK image: {}", internalName, e.toString());
        }
        return Optional.empty();
    }

    private static ClassSurface readSurface(byte[] bytes) {
        SurfaceVisitor visitor = new SurfaceVisitor();
        new ClassReader(bytes).accept(
            visitor,
            ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return visitor.toSurface();
    }

    private static final class SurfaceVisitor extends ClassVisitor {
        private String internalName;
        private int access;
        private @Nullable String superName;
        private List<String> interfaces = List.of();
        private final Set<MethodInfo> methods = new LinkedHashSet<>();

        SurfaceVisitor() {
            super(Opcodes.ASM9);
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
        }

        @Override
        public MethodVisitor visitMethod(
            int access,
            String name,
            String descriptor,
            String signature,
            String[] exceptions) {
            methods.add(new MethodInfo(name, descriptor, access));
            return null;
        }

        ClassSurface toSurface() {
            return new ClassSurface(internalName, access, superName, interfaces, Set.copyOf(methods));
        }
    }
}
