FROM node:22.23.5-alpine@sha256:1314fb30ec37592d5845e5a160d522c0e680972aa534549940b8d73bde88481a

# Rebuilds must include the fixed compression and TLS libraries from Alpine.
RUN apk add --no-cache --upgrade 'zlib>=1.4-r0' 'libssl3>=3.5.9-r1' 'libcrypto3>=3.5.9-r1' ffmpeg 'clamav>=1.4.7-r0' su-exec font-dejavu \
  && addgroup -S -g 10001 novvin \
  && adduser -S -D -H -u 10001 -G novvin novvin

WORKDIR /usr/src/app

COPY package.json package-lock.json ./
RUN npm ci --omit=dev

COPY src ./src
COPY scripts ./scripts
RUN node ./scripts/verify-source-dependencies.js \
  && chmod +x ./scripts/start-with-antivirus.sh \
  && chown -R novvin:novvin /usr/src/app

EXPOSE 3001

CMD ["./scripts/start-with-antivirus.sh"]
