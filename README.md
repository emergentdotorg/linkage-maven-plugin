# Linkage Maven Plugin

Validates dependencies at the bytecode level. The `linkage:validate` goal scans the project's
compiled classes and every resolved dependency (transitives included) with ASM, extracts what each
artifact **provides** (classes and declared methods) and what it **requires** (referenced classes
and invoked method signatures, including constructors and `invokedynamic`), then cross-checks the
two against each other and the JDK. Missing linkage — the `NoSuchMethodError`/
`NoClassDefFoundError` you would otherwise discover at runtime, typically caused by dependency
version skew — fails the build.

## Usage

```xml
<plugin>
  <groupId>org.emergent.maven.plugins</groupId>
  <artifactId>linkage-maven-plugin</artifactId>
  <version>${linkage.version}</version>
  <executions>
    <execution>
      <goals>
        <goal>validate</goal>
      </goals>
    </execution>
  </executions>
</plugin>
```

The goal binds to the `verify` phase by default. Run it ad hoc with:

```bash
mvn org.emergent.maven.plugins:linkage-maven-plugin:validate
```

## Configuration

| Parameter | Property | Default | Description |
|---|---|---|---|
| `skip` | `linkage.skip` | `false` | Skip validation entirely. |
| `scope` | `linkage.scope` | `runtime` | Dependency scope to analyze: `compile`, `runtime` or `test`. |
| `failOnMissing` | `linkage.failOnMissing` | `true` | Fail the build on findings; when `false` they are logged as warnings. |
| `ignoredClassPatterns` | `linkage.ignoredClassPatterns` | — | Glob patterns over dotted class names (`javax.annotation.*`) whose findings are suppressed, both as missing classes and as owners of missing methods. |
| `projectReferencesOnly` | `linkage.projectReferencesOnly` | `false` | Validate only references made by the project's own classes (low-noise mode); dependencies still contribute to the resolution universe. |
| `classesDirectory` | — | `${project.build.outputDirectory}` | The project classes analyzed alongside the dependencies. |

Real-world jars routinely reference optional dependencies (logging bindings, annotation-only
artifacts such as `javax.annotation`). Suppress known-benign findings with `ignoredClassPatterns`,
narrow the check with `projectReferencesOnly`, or downgrade to warnings with
`failOnMissing=false`.

## Limitations

- Reflection, `ServiceLoader` and DI-container wiring are invisible to static bytecode analysis
  (same stance as `jdeps`).
- JDK classes are resolved from the JVM running Maven, which may differ from the project's target
  JDK under toolchains.
- Multi-release jar overlays (`META-INF/versions/**`) are not scanned; the base entries of a
  multi-release jar are required to be API-complete, so the provided surface is still correct.
- Field accesses are not checked; classes and method invocations are.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
