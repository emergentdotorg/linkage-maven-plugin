package org.emergent.maven.linkage.scan;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.emergent.maven.linkage.model.ClassSurface;
import org.junit.jupiter.api.Test;

class JrtClassResolverTest {

    private final JrtClassResolver resolver = new JrtClassResolver();

    @Test
    void resolvesJavaBaseClassWithMethodSurface() {
        Optional<ClassSurface> surface = resolver.resolve("java/lang/String");

        assertThat(surface).isPresent();
        assertThat(surface.get().getSuperName()).isEqualTo("java/lang/Object");
        assertThat(surface.get().getMethods())
            .anyMatch(m -> m.getName().equals("isEmpty") && m.getDescriptor().equals("()Z"));
    }

    @Test
    void resolvesClassOutsideJavaBase() {
        assertThat(resolver.resolve("java/sql/Connection")).isPresent();
    }

    @Test
    void unknownClassResolvesEmpty() {
        assertThat(resolver.resolve("java/lang/NoSuchClass123")).isEmpty();
        assertThat(resolver.resolve("com/example/NotInTheJdk")).isEmpty();
        assertThat(resolver.resolve("NoPackage")).isEmpty();
    }
}
