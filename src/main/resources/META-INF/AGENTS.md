# maven-timeline-extension

Guidance for AI coding agents working in a project that uses this extension.

Source and docs: https://github.com/maven-turbo-reactor/maven-timeline-extension

## What it does

A Maven **core extension** (not a plugin) that records the build timeline — per worker thread, module and goal — plus
sampled CPU, heap, threads, artifact resolver IO and docker container metrics. At the end of the build it writes an
interactive D3.js report. Works with Maven 3, Maven 4 and the Maven daemon (mvnd). It does not change build results.

## Setup

Register it in `.mvn/extensions.xml` in the root of the project (not in `pom.xml` `<build><plugins>`):

```xml
<extensions>
    <extension>
        <groupId>com.github.seregamorph</groupId>
        <artifactId>maven-timeline-extension</artifactId>
        <version><!-- latest from Maven Central --></version>
    </extension>
</extensions>
```

## Output

- `target/timeline/build-report.html` under the **execution root** directory (where `mvn` was started, or the `-f`
  project). A single self-contained file: build data and D3.js are inlined, no network access is needed to view it.
  The page uses inline scripts, so a Content Security Policy that blocks them prevents rendering.
- With `timelineJsonReport=true`: `build-report.html` plus a separate `build-data.json`, which the page fetches at
  runtime (so it must be served over HTTP rather than opened via `file://`). The JSON file is the easiest input for
  automated analysis of a build.

The report is written even when the build fails; the failed goal is highlighted in red.

## Configuration parameters

| Property             | Default | Where it can be set                                      | Effect                                                                                                                           |
|----------------------|---------|----------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------|
| `timelineJsonReport` | `false` | system property, user property (`-D`), root project property | `true` emits `build-report.html` + separate `build-data.json` instead of a single self-contained HTML file.                  |
| `timelineDocker`     | `true`  | system property, user property (`-D`)                    | `false` disables polling `docker ps` (once per second) for running containers. The docker chart is hidden if no container ran. |

Boolean values are case-sensitive: only the literal `true` / `false` is recognized. The properties can also be put
into `.mvn/maven.config`, e.g. `-DtimelineDocker=false`.

## Build data (`build-data.json`)

All times and durations are in seconds since the build start.

- `meta`: `threads`, `modules`, `totalGoals`, `duration` (wall clock), `serialDuration` (sum of all module durations).
- `tasks[]`: one per module — `module`, `thread` (worker thread index), `start`, `end`, `dur`, `goals[]`.
  - `goals[]`: `name`, `type`, `phase`, `start`, `end`, `prepareDur`, `execDur`, `failed`.
  - The first goal of every module is a synthetic `<prepare>` span (module start → first mojo start), mostly
    dependency resolution.
  - `type` is a coarse classification of the lifecycle phase: `generate-sources`, `compile`,
    `generate-test-sources`, `test-compile`, `test` (also `integration-test`), `deploy`, `other`.
- `metrics[]`: sampled every ~250 ms — `t`, `active` (running modules), `heapUsed` / `heapCommitted` (MB), `gc`,
  `processCpu` / `systemCpu` (percent), `totalThreads`, `daemonThreads`, `resolverDownload` / `resolverUpload`
  (MB/s), `dockerContainers` / `testcontainersContainers` (absent when docker is not tracked or no container ran).

Useful analysis: compare `meta.duration` with `meta.serialDuration` to estimate parallelism; long `<prepare>` spans
point at slow dependency resolution; gaps between tasks on a thread or low `active` values show reactor bottlenecks
(critical path) when building with `-T`.

## Notes

- Metrics collection is skipped during IntelliJ IDEA project import; regular IDEA-launched builds are recorded.
- The report reflects only the modules and goals of the current invocation (e.g. `-pl`, `-rf` narrow it).
- The report is overwritten on each build; it is written after the build finishes, so `mvn clean ...` still
  produces a fresh report.
