# Kubernetes images

One image per language puts a distribution into a pod without changing the application image. Mount the image as
an image volume, or, on clusters without image volumes, copy it into a shared `emptyDir` with an init container.

| Image | Contents of `/autoinstrumentation` |
| --- | --- |
| `ghcr.io/kloudmate/otel-distro-java:<version>` | `javaagent.jar` (upstream), `extensions/kloudmate-otel-extension.jar` |
| `ghcr.io/kloudmate/otel-distro-python:<version>` | `sitecustomize.py`, one package directory per CPython minor and libc (`3.12/`, `3.12-musl/`, ...) |
| `ghcr.io/kloudmate/otel-distro-nodejs:<version>` | `register.js`, `register.mjs`, `node_modules/` |
| `ghcr.io/kloudmate/otel-distro-dotnet:<version>` | upstream automatic instrumentation for glibc and musl, with the KloudMate plugin in `net/` |

Every image also carries the release's `km-manifest.json`, so the decision whether to instrument a pod is the same
one made on a host.

- Base: `busybox` pinned by digest, for the init container's `cp`. Nothing in the image runs as the application.
- Runs as uid 65532.
- amd64 and arm64 in one index. Reference images by digest, never by a moving tag.

## Use it

The distribution needs the same variables as on a host. With an init container, `<home>` is the `emptyDir`'s mount
path; with an image volume, it is the volume's mount path followed by `/autoinstrumentation`.

| Language | Variables |
| --- | --- |
| Java | `JAVA_TOOL_OPTIONS=-javaagent:<home>/javaagent.jar -Dotel.javaagent.extensions=<home>/extensions/kloudmate-otel-extension.jar` |
| Python | `PYTHONPATH=<home>` |
| Node.js | `NODE_OPTIONS=--require <home>/register.js` (`--import <home>/register.mjs` for ES modules) |
| .NET | `OTEL_DOTNET_AUTO_HOME=<home>`, `DOTNET_STARTUP_HOOKS=<home>/net/OpenTelemetry.AutoInstrumentation.StartupHook.dll`, `CORECLR_ENABLE_PROFILING=1`, `CORECLR_PROFILER={918728DD-259F-4A6A-AC2B-B85E1B658318}`, `CORECLR_PROFILER_PATH=<home>/linux-x64/OpenTelemetry.AutoInstrumentation.Native.so` (`linux-musl-x64`, `linux-arm64` or `linux-musl-arm64` to match the pod), `OTEL_DOTNET_AUTO_PLUGINS=KloudMate.OpenTelemetry.Plugin, KloudMate.OpenTelemetry` |

Set `KM_SERVICE_ID` and, where the agent's socket is mounted into the pod, `KM_OTLP_SOCKET`
([rules](../contract/README.md#the-agents-socket)). Otherwise point `OTEL_EXPORTER_OTLP_ENDPOINT` at the
node's agent.

An init container that copies the Node.js distribution:

```yaml
initContainers:
  - name: kloudmate-otel
    image: ghcr.io/kloudmate/otel-distro-nodejs@sha256:<digest>
    command: ["cp", "-r", "/autoinstrumentation/.", "/otel/"]
    volumeMounts:
      - { name: kloudmate-otel, mountPath: /otel }
containers:
  - name: app
    env:
      - { name: NODE_OPTIONS, value: "--require /otel/register.js" }
    volumeMounts:
      - { name: kloudmate-otel, mountPath: /otel, readOnly: true }
volumes:
  - name: kloudmate-otel
    emptyDir: {}
```

The same pod with an image volume (Kubernetes 1.33 and later), and no init container:

```yaml
containers:
  - name: app
    env:
      - { name: NODE_OPTIONS, value: "--require /otel/autoinstrumentation/register.js" }
    volumeMounts:
      - { name: kloudmate-otel, mountPath: /otel, readOnly: true }
volumes:
  - name: kloudmate-otel
    image:
      reference: ghcr.io/kloudmate/otel-distro-nodejs@sha256:<digest>
```

## Build and test

```sh
images/build.sh nodejs --load            # this machine's architecture into the local Docker
images/test.sh nodejs                    # init-container and image-volume modes, glibc and musl runtimes
images/build.sh nodejs                   # amd64 and arm64 as an OCI archive in images/build/
images/build.sh nodejs --push            # to ghcr.io/kloudmate (needs docker login)
```

`build.sh` builds the language's release first (`<language>/release.sh`) and lays it out per architecture; set
`BASE_IMAGE` to pull the base through a mirror. The `images` workflow does the same on the self-hosted runner and
pushes only when its `push` input is on.

Sizes per architecture: Java about 26 MB, Node.js 78 MB, .NET 130 MB, Python 190 MB (ten package directories:
CPython 3.10 to 3.14, glibc and musl).
