# Kubernetes images (planned)

One small image per language, for injecting a distribution into pods without changing application images. Each
image is mounted as an image volume, or, on clusters without image volumes, copied by an init container into a
shared `emptyDir`.

| Image | Contents |
| --- | --- |
| `ghcr.io/kloudmate/otel-distro-java:<version>` | upstream agent jar, KloudMate extension jar, `km-manifest.json` |
| `ghcr.io/kloudmate/otel-distro-python:<version>` | per-CPython-minor package directories (glibc and musl), bootstrap |
| `ghcr.io/kloudmate/otel-distro-nodejs:<version>` | `node_modules` and the register hook |
| `ghcr.io/kloudmate/otel-distro-dotnet:<version>` | upstream automatic instrumentation (glibc and musl) and the KloudMate plugin |

Principles:

- `FROM scratch` (or `busybox` for the copy step). Nothing in the image runs as the application.
- Multi-arch (amd64, arm64), referenced by digest, never `:latest`.
- Each image carries the same `km-manifest.json` as the release, so the decision whether to inject into a pod
  is the same one made on a host.
