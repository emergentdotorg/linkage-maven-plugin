package org.emergent.maven.linkage.scan;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.emergent.maven.linkage.fixtures.Concrete;
import org.emergent.maven.linkage.model.ClassSurface;
import org.emergent.maven.linkage.model.MethodInfo;
import org.emergent.maven.linkage.model.MethodRef;
import org.emergent.maven.linkage.model.ScanResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BytecodeScannerTest {

    private static final String FIXTURES = "org/emergent/maven/linkage/fixtures";

    private final BytecodeScanner scanner = new BytecodeScanner();

    @Test
    void scanDirectoryCollectsProvidedSurfaceAndReferences() throws Exception {
        ScanResult result = scanner.scanDirectory(testClassesRoot(), "test classes");

        ClassSurface greeter = result.getProvided().get(FIXTURES + "/Greeter");
        assertThat(greeter).isNotNull();
        assertThat(greeter.getMethods())
            .contains(new MethodInfo("greet", "()Ljava/lang/String;", 0x0001))
            .anyMatch(m -> m.getName().equals("name"));

        ClassSurface concrete = result.getProvided().get(FIXTURES + "/Concrete");
        assertThat(concrete).isNotNull();
        assertThat(concrete.getSuperName()).isEqualTo(FIXTURES + "/AbstractBase");
        assertThat(concrete.getMethods())
            .anyMatch(m -> m.getName().equals("run") && m.getDescriptor().equals("()V"))
            .anyMatch(m -> m.getName().startsWith("lambda$run$")); // synthetic lambda body kept

        // call to the inherited default method resolves against Concrete at the call site
        assertThat(result.getReferencedMethods())
            .contains(new MethodRef(FIXTURES + "/Concrete", "greet", "()Ljava/lang/String;"))
            // invokedynamic surfaces the bootstrap method and the implementation handle
            .anyMatch(r -> r.getOwner().equals("java/lang/invoke/LambdaMetafactory") && r.getName()
                .equals("metafactory"))
            // String[].clone() is normalized from the array owner to java.lang.Object
            .contains(new MethodRef("java/lang/Object", "clone", "()Ljava/lang/Object;"))
            .contains(new MethodRef("java/util/ArrayList", "<init>", "()V"));

        assertThat(result.getReferencedClasses())
            .contains("java/io/IOException")            // throws + try/catch
            .contains("java/lang/String")               // array element type
            .contains(FIXTURES + "/Marker")             // annotation descriptor
            .contains("java/lang/Integer")              // annotation Class value
            .contains("java/util/ArrayList");
    }

    @Test
    void scanJarSkipsModuleInfoAndMultiReleaseOverlays(@TempDir Path tempDir) throws Exception {
        Path jar = tempDir.resolve("fixture.jar");
        byte[] concreteBytes = Files.readAllBytes(testClassesRoot().resolve(FIXTURES + "/Concrete.class"));
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            putEntry(out, FIXTURES + "/Concrete.class", concreteBytes);
            putEntry(out, "module-info.class", new byte[] {1, 2, 3});
            putEntry(out, "META-INF/versions/17/" + FIXTURES + "/Concrete.class", new byte[] {4, 5, 6});
        }

        ScanResult result = scanner.scanJar(jar, "fixture.jar");

        assertThat(result.getProvided()).containsOnlyKeys(FIXTURES + "/Concrete");
    }

    @Test
    void corruptClassEntryIsSkippedNotFatal(@TempDir Path tempDir) throws Exception {
        Path jar = tempDir.resolve("corrupt.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            putEntry(out, "com/example/Broken.class", new byte[] {(byte) 0xCA, (byte) 0xFE});
        }

        ScanResult result = scanner.scanJar(jar, "corrupt.jar");

        assertThat(result.getProvided()).isEmpty();
    }

    private static Path testClassesRoot() throws URISyntaxException {
        return Paths.get(Concrete.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    private static void putEntry(JarOutputStream out, String name, byte[] bytes) throws IOException {
        out.putNextEntry(new JarEntry(name));
        out.write(bytes);
        out.closeEntry();
    }
}
