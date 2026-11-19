# RecordTag regression probes

These probes cover the AGP/D8 global-synthetics failure that can leave a framework DEX referring to
`com.android.tools.r8.RecordTag` without packaging its generated superclass.

## Packaging regression

Run from the repository root:

```sh
python3 tests/recordtag/run.py --output /tmp/aevix-recordtag
```

The runner extracts the production `:zygisk:` DEX preparation task and its variant registration into
a disposable Android fixture. It checks missing global synthetics, debug and release DEX merging,
incremental rebuilds, exact single-DEX ZIP contents, atomic-move fallback, corrupt/duplicate DEX
inputs, stale-output preservation, and single-DEX capacity rejection.

Use `--offline` to require cached Gradle dependencies.

## Native runtime regression

First build the module ZIP for the chosen variant, then select an explicit disposable arm64 test
device with root ADB access:

```sh
python3 tests/recordtag/runtime/run-native.py --variant debug --serial DEVICE_SERIAL --output /tmp/aevix-recordtag-native
```

The native probe loads the exact framework DEX from the built module ZIP in a disposable
`app_process`, installs a real native hook, verifies full/filtered/origin invoker paths and the
original return value, then unhooks. It does not install the module, alter root policy, reboot, or
persist hooks. The runner removes its temporary device directory on exit.
