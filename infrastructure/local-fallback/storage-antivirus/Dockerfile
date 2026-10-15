FROM node:22.23.2-alpine

RUN apk add --no-cache --upgrade 'zlib>=1.3.2-r1' clamav-daemon clamav \
  && addgroup -S -g 10001 novvin \
  && adduser -S -D -H -u 10001 -G novvin novvin

COPY clamd.conf /policy/clamd-storage.conf
COPY storage-scan-policy.json /policy/storage-scan-policy.json
COPY freshclam.conf /policy/freshclam.conf
COPY healthcheck.js /opt/novvin/healthcheck.js
COPY start.sh /opt/novvin/start.sh
RUN chmod 0555 /opt/novvin/start.sh \
  && install -d -m 0750 -o 10001 -g 10001 /var/lib/clamav
USER 10001:10001
ENV TZ=UTC
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=3 \
  CMD ["node", "/opt/novvin/healthcheck.js"]
CMD ["/opt/novvin/start.sh"]
