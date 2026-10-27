FROM node:22.23.4-alpine

# Install the distribution zlib security fix even when the base tag is cached.
RUN apk add --no-cache --upgrade 'zlib>=1.3.2-r1'

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
