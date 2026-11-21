# syntax=docker/dockerfile:1
# One image per language (images/build.sh). The distribution sits in /autoinstrumentation, per architecture from
# the build context. busybox gives the init container a `cp`; mounted as an image volume, nothing in it runs.
ARG BASE=busybox:1.37.0-musl@sha256:f6a95d611e0a8780fabdb2aec43e31275b2720dfefa3e41b16835bfc42454478
FROM ${BASE}
ARG TARGETARCH
COPY ${TARGETARCH}/ /autoinstrumentation/
USER 65532:65532
