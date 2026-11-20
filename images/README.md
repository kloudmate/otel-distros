# Kubernetes init images

One small image per language, used by km-injector (plan Phase 5) as an image volume or, on kubelets without
image volumes, as an init container that copies `/kloudmate/<lang>/` into a shared `emptyDir`.

| Image | Contents |
| --- | --- |
| `ghcr.io/kloudmate/otel-distro-java:<ver>` | upstream agent jar + KM extension jar + `km-manifest.json` |
| `ghcr.io/kloudmate/otel-distro-python:<ver>` | per-CPython-minor wheel cells (glibc and musl) + bootstrap |
| `ghcr.io/kloudmate/otel-distro-nodejs:<ver>` | `node_modules` + register wrapper |
| `ghcr.io/kloudmate/otel-distro-dotnet:<ver>` | upstream zip (glibc and musl) + KM plugin |
| `ghcr.io/kloudmate/otel-distro-php:<ver>` | ABI-matched `.so` matrix (official ext 8.2–8.5, `kloudmate_php.so` 7.0–8.6 incl. ZTS/musl) + scoped package |

Rules:

- `FROM scratch` (or `busybox` for the init-container copy), nothing that runs as the app.
- Multi-arch (amd64, arm64), pinned by digest in the km-kube-agent chart. No `:latest` (today's Node and Python
  images build from `:latest`, plan 0.4).
- The image carries the same `km-manifest.json` as the host bundle, so km-injector makes the same runtime,
  libc and deny decisions at admission as km-agent makes on a VM.

## Layout (planned)

```
images/
  java.Dockerfile  python.Dockerfile  nodejs.Dockerfile  dotnet.Dockerfile  php.Dockerfile
```
