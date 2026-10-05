#!/bin/sh
# Pulls the exact Scenario Lab sandbox images. The backend runs user code only in these, with --pull never, so they
# must be present before Scenario Lab can execute code. Pinned by digest (multi-architecture: x86 and ARM64).
# Keep in sync with LanguageRuntime.java (a test checks it).
set -eu
for image in \
  "python:3.12-slim@sha256:02108f5d322dd89f1c9e552442c25acb0543dfdbc455693a5599624f20d9155d" \
  "node:24-alpine@sha256:ebfe2f90462722a7a4de65e91990e97fe0d401c70e0e762c5b53302f905ec1c1" \
  "eclipse-temurin:21-jdk-alpine@sha256:0bfc69a4758a86710e5c474032d28400a8bd00874766f9e8b1642ac2fd293159"
do
  docker pull --quiet "$image"
done
