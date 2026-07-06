package org.emergent.maven.linkage.scan;

import java.util.Optional;
import org.emergent.maven.linkage.model.ClassSurface;

/**
 * Looks up the surface of a class not provided by any analyzed artifact (typically JDK classes).
 */
public interface ClassResolver {

    /**
     * Empty resolver for validations that should not consult any external source.
     */
    ClassResolver NONE = internalName -> Optional.empty();

    Optional<ClassSurface> resolve(String internalName);
}
