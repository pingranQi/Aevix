# RecordTag DEX packaging regression test

This fixture extracts the actual `PrepareFrameworkDex` task and variant registration from
`zygisk/build.gradle.kts`, then builds a small Java-record APK through that production task.
It does not reimplement the DEX merge or integrity checks.

Run from the repository root:

```sh
python3 tests/recordtag/run.py --output /tmp/aevix-recordtag
```

Add `--offline` to use cached Gradle dependencies only. The test exercises Debug and
Release packaging, incremental rebuilds, single-DEX ZIP contents, missing global
synthetics, duplicate DEX classes, corrupt DEX input, atomic-move fallback, and
single-DEX capacity rejection. Build and failure logs are retained in the output
directory.

This is a host-side build regression test. It does not replace an on-device
`InMemoryDexClassLoader` check or a full Android build/test run.
