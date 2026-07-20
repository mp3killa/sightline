# Third-party notices

Sightline itself is **source-available** — see [LICENSE](LICENSE). This file covers the third-party
components redistributed inside its plugin archive, which remain under their own licences.

Clause 9 of the licence says as much: those licences apply to their components, and nothing in
Sightline's licence restricts rights they grant you. **Sightline not being open source does not reduce
these obligations** — MIT and Apache-2.0 both require their notices be reproduced wherever the code is
redistributed, whatever licence the surrounding product carries.

None of the three jars ships a `LICENSE` or `NOTICE` entry of its own, so this file and the
[`licenses/`](licenses/) directory are how that requirement is met.

## Redistributed in the plugin archive

| Component | Version | Licence | Full text | Source |
|---|---|---|---|---|
| Gson | 2.11.0 | Apache-2.0 | [licenses/Apache-2.0.txt](licenses/Apache-2.0.txt) | https://github.com/google/gson |
| Java-WebSocket | 1.5.7 | MIT | [licenses/MIT-Java-WebSocket.txt](licenses/MIT-Java-WebSocket.txt) | https://github.com/TooTallNate/Java-WebSocket |
| Error Prone Annotations | 2.27.0 | Apache-2.0 | [licenses/Apache-2.0.txt](licenses/Apache-2.0.txt) | https://github.com/google/error-prone |

`error_prone_annotations` arrives as a transitive compile-time dependency of Gson and is annotations
only. It is listed because it is present in the archive, not because Sightline calls it.

The two directly used libraries are there because the IntelliJ Platform exposes no equivalent on the
plugin classpath: Gson parses the Claude Code CLI's streaming-JSON protocol, and Java-WebSocket runs the
loopback `ide` bridge the CLI connects to. `slf4j-api` is deliberately **excluded** from the
Java-WebSocket dependency because the platform already provides it.

## Java-WebSocket — MIT

Reproduced in full, as the licence requires:

```
Copyright (c) 2010-2020 Nathan Rajlich

Permission is hereby granted, free of charge, to any person
obtaining a copy of this software and associated documentation
files (the "Software"), to deal in the Software without
restriction, including without limitation the rights to use,
copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the
Software is furnished to do so, subject to the following
conditions:

The above copyright notice and this permission notice shall be
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
OTHER DEALINGS IN THE SOFTWARE.
```

## Gson and Error Prone Annotations — Apache-2.0

```
Copyright 2008 Google Inc.          (Gson)
Copyright 2015 The Error Prone Authors   (error_prone_annotations)

Licensed under the Apache License, Version 2.0 (the "License");
you may not use these files except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

Neither component has been modified. The complete Apache-2.0 text is at
[licenses/Apache-2.0.txt](licenses/Apache-2.0.txt), included because clause 4 of that licence requires
recipients be given a copy.

## Provided by the IntelliJ Platform (not redistributed)

The IntelliJ Platform, its bundled Java and Kotlin plugins, `org.intellij.markdown`, and — where
present — the Android plugin, are supplied by the host IDE. Sightline compiles against them and
declares them as dependencies; it does not ship copies. They remain licensed by JetBrains s.r.o. and
their respective authors under the terms accompanying the IDE.

## Not bundled and not required at build time

The **Claude Code CLI** is neither redistributed nor linked. Sightline launches whatever `claude`
binary you have installed and authenticated yourself, as a separate process. See
[PRIVACY.md](PRIVACY.md).

## Regenerating this file

The bundled set is whatever ends up in `build/distributions/*.zip` under `lib/`:

```bash
./gradlew buildPlugin
unzip -l build/distributions/*.zip | grep '\.jar'
```

If that list changes, update this file **and** `licenses/` in the same commit. A notices file that lags
the artifact is worse than none, because it is read as authoritative — and it is the only place these
obligations are discharged.
