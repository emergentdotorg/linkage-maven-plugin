package org.emergent.maven.linkage;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.maven.api.plugin.testing.Basedir;
import org.apache.maven.api.plugin.testing.InjectMojo;
import org.apache.maven.api.plugin.testing.MojoParameter;
import org.apache.maven.api.plugin.testing.MojoTest;
import org.junit.jupiter.api.Test;

/**
 * Descriptor/parameter-wiring smoke test; behavior is covered by unit tests of the scan and
 * validate packages plus the invoker ITs.
 */
@MojoTest
public class ValidateMojoTest {

    @Test
    @Basedir(value = "target/test-classes/project-to-test/")
    @InjectMojo(goal = "validate")
    @MojoParameter(name = "skip", value = "true")
    public void skipShortCircuits(ValidateMojo mojo) throws Exception {
        assertThat(mojo).isNotNull();
        mojo.execute();
    }
}
