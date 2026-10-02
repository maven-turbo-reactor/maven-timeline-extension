# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A Maven core extension (loaded via `.mvn/extensions.xml`, not a plugin) that records a build's timeline (per worker
thread, module and goal) plus sampled CPU/heap/threads/resolver-IO metrics, and writes an interactive D3.js report to
`target/timeline/build-report.html` under the execution root. It must work on both Maven 3 and Maven 4.

## Commands

```bash
./mvnw clean install                                   # build + tests (Maven 3.9.x via wrapper)
./mvnw test -Dtest=TimelineHelperTest                  # single test class
./mvnw test -Dtest=TimelineHelperTest#shouldClassifyGoalType   # single test method
```

- `.mvn/maven.config` sets `-T1 --strict-checksums -e`.
- Bytecode target is Java 1.8 (inherited from parent `maven-turbo-reactor-parent`) — don't use newer language/API
  features. Tests use JUnit Jupiter.
- To try the extension on a real build, `install` it and reference the `-SNAPSHOT` version from another project's
  `.mvn/extensions.xml`. The `Debug 8000` IDEA run config attaches to `mvnDebug` (port 8000).
- Set `-DtimelineJsonReport=true` (system/user/root-project property) to emit `build-report.html` + a separate
  `build-data.json` instead of the self-contained single file — handy when iterating on the HTML.

## Architecture

All components are JSR-330 beans discovered by Sisu. **Any new `@Named` component must be listed in
`src/main/resources/META-INF/sisu/javax.inject.Named`** (the index is hand-maintained, not generated).

`TimelineHelper` (singleton) is the central state holder; the other components are thin adapters feeding it:

- `TimelineLifecycleParticipant` — `afterSessionStart` calls `TimelineHelper.init()` (starts `MetricsCollector`
  daemon thread); `afterSessionEnd` calls `complete()` to build `BuildData`, serializes it with Jackson
  (`JsonSerializers`) and writes the report. Init is skipped during IntelliJ IDEA import (`IdeaImportSupport`,
  detected via `idea.maven.embedder.version`) because `afterSessionEnd` is never called there.
- `TimelineProjectExecutionListener` / `TimelineMojoExecutionListener` — project/mojo lifecycle callbacks. Every
  callback is guarded by `timelineHelper.isInitialized()`.
- `TimelineEventSpy` — receives `ExecutionEvent`s (MojoStarted/Succeeded/Failed drive goal spans) and Aether
  `RepositoryEvent`s (download/deploy transfers recorded into `ResolverIoStats`).

Key details in `TimelineHelper`:
- Worker thread ids are assigned lazily via a `ThreadLocal` counter; state is reset in `init()` so the extension
  stays compatible with the Maven daemon (mvnd) where the singleton survives across builds.
- Each module's first span is a synthetic `<prepare>` goal (project start → first mojo start, mostly dependency
  resolution).
- Goals forking a lifecycle may arrive with `startedGoal == null`; failed ones are still recorded as zero-length
  markers so failures are never dropped.
- `goalType(phase)` maps lifecycle phases to coarse categories used for coloring in the report; keep it in sync with
  the legend/colors in `build-report.html` and with `TimelineHelperTest`.

### Report (`src/main/resources/static/build-report.html`)

A single static HTML/JS page (D3 v7 from CDN). The Java side replaces the `__TIMELINE_BUILD_DATA__` placeholder in
`<script id="buildData">` with the JSON; if the placeholder is left intact, the page falls back to fetching
`build-data.json`. The JSON shape is defined by `BuildData` (`meta`, `tasks[].goals[]`, `metrics[]`) — changes to
`BuildData` fields must be mirrored in the page's JS.

`docs/` is the GitHub Pages site (`index.html` landing page, `build-report.html` sample report, `timeline.png`); it is a
separately updated copy, not generated from the resource.
