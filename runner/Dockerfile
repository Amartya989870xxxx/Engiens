# Engiens scenario runner: runs Scenario Lab code where Docker containers can't be started (Railway).
# Python 3.12 (Ubuntu 24.04), Node 24 and JDK 21: the same language versions as the local Docker sandbox images.
# Base images pinned by multi-architecture digest (x86 and ARM64).
#
# It runs as root only so each run can be started as the unprivileged user "nobody" (see engiens_runner.py).
# No public port: the backend reaches it over Railway's private network, with a shared RUNNER_TOKEN.

FROM node:24-bookworm-slim@sha256:d6aa754f16b3197301076f047b5def2f02ea1dbbc2ca920407d46d7ec7f87b20 AS node

FROM eclipse-temurin:21-jdk-noble@sha256:b468c3fc688b14450571494f588bd939378e7fd542ed5a73f8efc13f17872a87
RUN apt-get update \
 && apt-get install -y --no-install-recommends python3 \
 && rm -rf /var/lib/apt/lists/*
COPY --from=node /usr/local/bin/node /usr/local/bin/node
WORKDIR /opt/engiens-runner
COPY engiens_runner.py .
ENV PORT=8090 PYTHONUNBUFFERED=1
EXPOSE 8090
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD python3 -c "import os, urllib.request; urllib.request.urlopen('http://127.0.0.1:%s/health' % os.environ.get('PORT', '8090'), timeout=5)"
CMD ["python3", "engiens_runner.py"]
