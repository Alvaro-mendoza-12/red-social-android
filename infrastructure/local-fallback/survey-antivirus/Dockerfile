FROM node:22.23.5-alpine@sha256:1314fb30ec37592d5845e5a160d522c0e680972aa534549940b8d73bde88481a

# Require the patched runtime libraries even when the base tag is cached.
RUN apk add --no-cache --upgrade 'zlib>=1.4-r0' 'libssl3>=3.5.9-r1' 'libcrypto3>=3.5.9-r1'

RUN apk add --no-cache clamav-daemon \
  && addgroup -S -g 10001 novvin \
  && adduser -S -D -H -u 10001 -G novvin novvin

COPY clamd.conf /etc/clamav/novvin-surveys.conf
COPY healthcheck.js /opt/novvin/healthcheck.js
COPY start.sh /opt/novvin/start.sh
RUN chmod 0555 /opt/novvin/start.sh
USER 10001:10001
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
  CMD ["node", "/opt/novvin/healthcheck.js"]
CMD ["/opt/novvin/start.sh"]
