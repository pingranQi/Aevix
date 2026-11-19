# External Dependencies

This directory contains all the external dependencies required to build the Aevix framework.
They are included as git submodules to ensure version consistency and timely updating.

## Native dependencies

-   [Dobby](https://github.com/JingMatrix/Dobby): 
    A lightweight, multi-platform inline hooking framework. It serves as the backend for all native function hooking (`HookInline`).

-   [ShadowHook](https://github.com/bytedance/android-inline-hook):
    Optional Android inline-hook backend for ARM ABIs; Dobby remains the fallback and the only backend on x86.

-   [fmt](https://github.com/fmtlib/fmt):
    A modern formatting library used for high-performance, type-safe logging throughout the native code.

-   [LSPlant](https://github.com/JingMatrix/LSPlant):
    A hooking framework for the Android Runtime (ART). It provides the core functionality for intercepting and modifying Java methods.

-   [YukiPLT](https://github.com/Rouyashiki/YukiPLT):
    A library for PLT (Procedure Linkage Table) hooking. It is used in the `dex2oat` sub-project to rewrite the command line recorded in the OAT header. **Note:** Built as a static library and linked into `oat_hook` there; it is not compiled into the `external` C++ library itself.

-   [xz-embedded](https://github.com/tukaani-project/xz-embedded):
    A lightweight data compression library with a small footprint. It is used by the ELF parser to decompress the `.gnu_debugdata` section of stripped native libraries.

## Java libraries

-   [apache/commons-lang](https://github.com/apache/commons-lang):
    A package of Java utility classes for the classes that are in java.lang's hierarchy. Some classes are renamed and then used to implement the `XposedHelpers` API.

-   [axml/manifest-editor](https://github.com/JingMatrix/ManifestEditor):
    A a tool used to modify Android Manifest binary file. It is to parse manifestation files of Xposed modules.


## Patches

-   [patches/shadowhook](patches/shadowhook): removes the mandatory dependency on `libshadowhook_nothing.so`.

## Inline hook backend selection

Dobby remains the default on every ABI. On ARM64 and ARM32, ShadowHook can be selected per
process with the persistent Android property:

```sh
su -c 'setprop persist.aevix.inline_hook_backend shadowhook'
```

Restore Dobby with:

```sh
su -c 'setprop persist.aevix.inline_hook_backend dobby'
```

The property is read once when each app process is specialized. Restart target apps after
changing it. If ShadowHook initialization or an individual hook fails, that hook falls back
to Dobby. x86 and x86_64 always use Dobby.
