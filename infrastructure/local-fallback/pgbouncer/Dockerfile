FROM edoburu/pgbouncer:v1.26.1-p0@sha256:488422e38cff912895d0d65d8963fcd306e6ac589a724398b6eb8e294e94d624
USER root
# Update the inherited PostgreSQL client and its Alpine runtime libraries.
RUN apk upgrade --no-cache
USER postgres
