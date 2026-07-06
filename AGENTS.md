# AGENTS.md

This file provides guidance to AI agents when working with code in this repository.

## What this is

`linkage-maven-plugin` (`org.emergent.maven.plugins:linkage-maven-plugin`) — a Maven plugin that
validates dependencies at the bytecode level. The `linkage:validate` goal (default phase `verify`)
scans the project's `target/classes` plus all resolved dependencies with ASM and fails the build
when a referenced class or invoked method signature is not provided by the dependency graph or
the JDK (i.e. it predicts `NoSuchMethodError`/`NoClassDefFoundError` from version skew). See
`README.md` for the user-facing parameter reference.

## Architecture

Everything except the mojo is Maven-free and unit-testable without the plugin harness; the mojo
is a thin adapter over it. Class names are JVM internal names (`org/x/Y`) throughout the model,
converted to dotted form only at the reporting/ignore-pattern boundary.

- `model/` — Lombok `@Value` data types: `ClassSurface` (a class's declared surface incl.
  hierarchy links), `MethodInfo`/`MethodRef`, `ScanResult` (per-artifact provided + required
  surfaces), `ValidationReport`.
- `scan/BytecodeScanner` — ASM visitors extracting provided classes/methods and required
  classes/method refs (incl. invokedynamic handles, annotation values, try/catch types). Skips
  `module-info.class` and `META-INF/versions/**`; array method owners are normalized to
  `java/lang/Object`; corrupt entries warn and continue.
- `scan/JrtClassResolver` — resolves JDK classes from the running JVM's `jrt:/` image (never
  reflection, which would also see Maven's own classes and mask real misses).
- `validate/LinkageValidator` — builds a first-wins global class index over all scans, then does
  JVM-style method resolution (superclass chain, then BFS over superinterfaces;
  signature-polymorphic `MethodHandle`/`VarHandle` special-cased). A missing ancestor makes a
  reference unresolvable (skipped) rather than a finding, to avoid cascading false positives.
- `ValidateMojo` — filters `project.getArtifacts()` with `ScopeArtifactFilter`, orchestrates the
  above, logs findings, throws `MojoFailureException` per `failOnMissing`.

## Build & test

Always use the Maven wrapper (`./mvnw`). Bytecode targets Java 17 (`maven.compiler.release`);
`.sdkmanrc` pins the local JDK to Temurin 21, and CI builds on 17.

```bash
./mvnw verify                                # build + unit tests
./mvnw test -Dtest=LinkageValidatorTest      # one test class
./mvnw test -Dtest=LinkageValidatorTest#name # one test method
./mvnw verify -Prun-its                      # integration tests (see below)
```

Unit tests are JUnit 5 + AssertJ. Scanner tests assert against fixture bytecode compiled normally
from `src/test/java/.../fixtures/`; validator tests build synthetic `ClassSurface`/`ScanResult`
values with a stub `ClassResolver` — neither needs the plugin harness. `ValidateMojoTest` is a
descriptor-wiring smoke test using the harness's JUnit 5 style (`@MojoTest` / `@InjectMojo` /
`@MojoParameter` from `org.apache.maven.api.plugin.testing`), with its fixture pom in
`src/test/resources/project-to-test/`.

## Integration tests

The `run-its` profile runs `maven-invoker-plugin`: each scenario is a self-contained project under
`src/it/<name>/` with its own `pom.xml` (plugin coordinates use `@project.groupId@`-style
placeholders filtered at run time), an optional `setup.groovy` pre-build hook, and a
`verify.groovy` post-build assertion script. Invoker installs the freshly built plugin into a
throwaway repo at `target/local-invoker-repo`, clones scenarios to `target/its`, and runs
`mvn verify` against each with `HOME`/`user.home` redirected to `target/its/tools` (the
`src/it/tools` project provides that sandbox). When adding plugin behavior, add an `src/it/`
scenario, not just a unit test.

## Structure & conventions

- Single-module project, `maven-plugin` packaging, inheriting from the external parent
  `org.emergent.parent:maven-parent:0.0.4` (not in this repo — plugin/release config lives there).
- Mojos go in `org.emergent.maven.linkage`, annotation-driven (`@Mojo`, `@Parameter` from
  `maven-plugin-annotations`).
- All Maven runtime deps (`maven-core`, `maven-plugin-api`, etc.) are `provided` scope and
  version-managed in `dependencyManagement` — keep new Maven/Sisu deps `provided` so they aren't
  bundled into the plugin.
- Lombok and Sisu are wired as annotation processors in `maven-compiler-plugin`; `lombok.config`
  enables chained accessors. `.editorconfig`: 4-space indent for Java, 2-space for XML/YAML, LF.

## CI

- `maven-tests.yaml`: PRs run `./mvnw -B -ntp clean install -e -T 1C` on Java 17.
- `maven-release.yaml`: every push to `main` touching `pom.xml` or `src/**` triggers a release via
  the shared `emergentdotorg/github-actions` workflow (deploys to `emergent-nexus` by default) —
  merging to `main` publishes, so treat `main` as release-ready.
