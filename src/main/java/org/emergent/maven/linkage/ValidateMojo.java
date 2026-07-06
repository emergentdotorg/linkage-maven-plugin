package org.emergent.maven.linkage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.resolver.filter.ArtifactFilter;
import org.apache.maven.artifact.resolver.filter.ScopeArtifactFilter;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.emergent.maven.linkage.model.ScanResult;
import org.emergent.maven.linkage.model.ValidationReport;
import org.emergent.maven.linkage.scan.BytecodeScanner;
import org.emergent.maven.linkage.scan.JrtClassResolver;
import org.emergent.maven.linkage.validate.IgnorePatterns;
import org.emergent.maven.linkage.validate.LinkageValidator;
import org.jspecify.annotations.Nullable;

/**
 * Validates dependencies at the bytecode level: scans the project's compiled classes and every
 * resolved dependency (transitives included) with ASM, then cross-checks all referenced classes
 * and invoked method signatures against what the analyzed artifacts plus the JDK actually
 * provide, failing the build on missing linkage.
 */
@Mojo(
    name = "validate",
    defaultPhase = LifecyclePhase.VERIFY,
    requiresDependencyResolution = ResolutionScope.TEST,
    threadSafe = true)
public class ValidateMojo extends AbstractMojo {

    private static final Set<String> VALID_SCOPES =
        Set.of(Artifact.SCOPE_COMPILE, Artifact.SCOPE_RUNTIME, Artifact.SCOPE_TEST);

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /**
     * Skips the validation entirely.
     */
    @Parameter(property = "linkage.skip", defaultValue = "false")
    private boolean skip;

    /**
     * Dependency scope to analyze: {@code compile}, {@code runtime} or {@code test}.
     */
    @Parameter(property = "linkage.scope", defaultValue = Artifact.SCOPE_RUNTIME)
    private String scope;

    /**
     * Whether missing linkage fails the build; when {@code false}, findings are only warned.
     */
    @Parameter(property = "linkage.failOnMissing", defaultValue = "true")
    private boolean failOnMissing;

    /**
     * Glob patterns over dotted class names ({@code javax.annotation.*}) whose findings are
     * suppressed, both as missing classes and as owners of missing methods.
     */
    @Parameter(property = "linkage.ignoredClassPatterns")
    private List<String> ignoredClassPatterns;

    /**
     * When {@code true}, only references made by the project's own classes are validated;
     * dependencies still contribute to the resolution universe but their own references are not
     * checked. Low-noise mode.
     */
    @Parameter(property = "linkage.projectReferencesOnly", defaultValue = "false")
    private boolean projectReferencesOnly;

    /**
     * Directory of the project's compiled classes to analyze.
     */
    @Parameter(defaultValue = "${project.build.outputDirectory}")
    private File classesDirectory;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Skipping linkage validation");
            return;
        }
        if (!VALID_SCOPES.contains(scope)) {
            throw new MojoExecutionException(
                "Unsupported scope '" + scope + "'; use one of " + VALID_SCOPES);
        }

        BytecodeScanner scanner = new BytecodeScanner();
        List<ScanResult> scans = new ArrayList<>();
        ScanResult projectScan = scanProjectClasses(scanner);
        if (projectScan != null) {
            scans.add(projectScan);
        }
        scans.addAll(scanDependencies(scanner));

        List<ScanResult> referenceScans = projectReferencesOnly
            ? (projectScan == null ? List.of() : List.of(projectScan))
            : scans;

        IgnorePatterns ignores = ignoredClassPatterns == null || ignoredClassPatterns.isEmpty()
            ? IgnorePatterns.NONE
            : IgnorePatterns.compile(ignoredClassPatterns);
        ValidationReport report =
            new LinkageValidator().validate(scans, referenceScans, new JrtClassResolver(), ignores);

        int classCount = scans.stream().mapToInt(s -> s.getProvided().size()).sum();
        getLog().info(String.format(
            "Linkage scanned %d artifacts (%d classes): %d missing classes, %d missing methods",
            scans.size(), classCount, report.getMissingClasses().size(), report.getMissingMethods().size()));

        report.getMissingClasses().forEach(missing ->
            logFinding("Missing class " + missing.getClassName() + " (referenced by " + String.join(
                ", ",
                missing.getReferencedBy()) + ")"));
        report.getMissingMethods().forEach(missing ->
            logFinding("Missing method " + missing.describe() + " (referenced by " + String.join(
                ", ",
                missing.getReferencedBy()) + ")"));

        if (!report.isClean()) {
            logFinding("Suppress known-benign findings with <ignoredClassPatterns> "
                + "(or -Dlinkage.ignoredClassPatterns=pattern1,pattern2), or set -Dlinkage.failOnMissing=false");
            if (failOnMissing) {
                throw new MojoFailureException(String.format(
                    "Linkage found %d missing classes and %d missing methods",
                    report.getMissingClasses().size(), report.getMissingMethods().size()));
            }
        }
    }

    private @Nullable ScanResult scanProjectClasses(BytecodeScanner scanner) throws MojoExecutionException {
        Path classesPath = classesDirectory.toPath();
        if (!Files.isDirectory(classesPath)) {
            getLog().warn("Classes directory " + classesDirectory + " does not exist; only dependencies are analyzed");
            return null;
        }
        try {
            return scanner.scanDirectory(classesPath, "project classes");
        } catch (IOException e) {
            throw new MojoExecutionException("Failed scanning " + classesDirectory, e);
        }
    }

    private List<ScanResult> scanDependencies(BytecodeScanner scanner) throws MojoExecutionException {
        ArtifactFilter filter = new ScopeArtifactFilter(scope);
        List<ScanResult> scans = new ArrayList<>();
        for (Artifact artifact : project.getArtifacts()) {
            if (!filter.include(artifact)) {
                continue;
            }
            File file = artifact.getFile();
            if (file == null || !file.exists()) {
                getLog().debug("Skipping " + artifact.getId() + ": no resolved file");
                continue;
            }
            try {
                if (file.isDirectory()) {
                    scans.add(scanner.scanDirectory(file.toPath(), artifact.getId()));
                } else if (file.getName().endsWith(".jar")) {
                    scans.add(scanner.scanJar(file.toPath(), artifact.getId()));
                } else {
                    getLog().debug("Skipping " + artifact.getId() + ": not a jar or directory");
                }
            } catch (IOException e) {
                throw new MojoExecutionException("Failed scanning " + artifact.getId(), e);
            }
        }
        return scans;
    }

    private void logFinding(String message) {
        if (failOnMissing) {
            getLog().error(message);
        } else {
            getLog().warn(message);
        }
    }
}
