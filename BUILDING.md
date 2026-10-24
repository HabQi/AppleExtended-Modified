# Building AppleExtended-Modified

Use a Java 8 JDK. The project retains Gradle 5.6.4, ForgeGradle 3.0.197, Kotlin 1.4.32 and the existing Minecraft mappings. Set JAVA_HOME or select Java 8 as the IDE's Gradle JVM; the repository does not assume a particular installation path.

```powershell
.\gradlew.bat build
```

The mod is written to `build/libs/`. A normal build does not copy files into a game instance or server. The inherited Test tasks remain disabled, so a successful build is not evidence of runtime tests.

Optional explicit copies:

```powershell
.\gradlew.bat copyJarToTest -PtestModsDir="D:/your-instance/mods"
.\gradlew.bat copyJarToServer -PserverModsDir="D:/your-server/mods"
```

`libs/` is an ignored optional location for compile-only JARs, not a bundled dependency directory. The current project does not require an always-present `libs/core.jar` entry.

Libraries explicitly declared as `bundledRuntime` and their transitive dependencies are included in the mod; Minecraft and Forge are not bundled through this configuration. License texts are preserved under `META-INF/licenses/` and service descriptors are merged. See `licenses/README.md` for provenance and the separate permissions that still require checking.

The `cr/` build is retained for reference and is not included by `settings.gradle`; it is not validated as part of the root build. The Kotlin source directory remains `src/main/java`; enabling `src/main/kotlin` would require resolving the existing duplicate CommandRTM declaration first.
