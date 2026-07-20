# Third-party notices

Sightline redistributes the components below inside its plugin archive. Everything else it uses is
provided by the IntelliJ Platform at runtime and is not redistributed here.

Sightline itself is licensed under the Apache License 2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE).

## Redistributed in the plugin archive

| Component | Version | License | Source |
|---|---|---|---|
| Gson | 2.11.0 | Apache-2.0 | https://github.com/google/gson |
| Java-WebSocket | 1.5.7 | MIT | https://github.com/TooTallNate/Java-WebSocket |
| Error Prone Annotations | 2.27.0 | Apache-2.0 | https://github.com/google/error-prone |

`error_prone_annotations` arrives as a transitive compile-time dependency of Gson and is annotations
only; it is listed because it is present in the archive, not because Sightline calls it.

Both directly used libraries are there because the IntelliJ Platform does not expose an equivalent on
the plugin classpath: Gson parses the Claude Code CLI's streaming-JSON protocol, and Java-WebSocket
runs the loopback `ide` bridge the CLI connects to. `slf4j-api` is deliberately **excluded** from the
Java-WebSocket dependency because the platform already provides it.

## Provided by the IntelliJ Platform (not redistributed)

The IntelliJ Platform, its bundled Java and Kotlin plugins, `org.intellij.markdown`, and — where
present — the Android plugin, are supplied by the host IDE. Sightline compiles against them and
declares them as dependencies; it does not ship copies. They remain licensed by JetBrains s.r.o. and
their respective authors under the terms accompanying the IDE.

## Not bundled and not required at build time

The **Claude Code CLI** is neither redistributed nor linked. Sightline launches whatever `claude`
binary the user has installed and authenticated themselves, as a separate process. See
[PRIVACY.md](PRIVACY.md) and [docs/DATA-FLOW.md](docs/DATA-FLOW.md).

## Regenerating this file

The bundled set is whatever ends up in `build/distributions/*.zip` under `lib/`:

```bash
./gradlew buildPlugin
unzip -l build/distributions/*.zip | grep '\.jar'
```

If that list changes, update this file in the same commit. A licence notice that lags the artifact is
worse than none, because it is read as authoritative.
