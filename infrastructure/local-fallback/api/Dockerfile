FROM node:22.23.2-alpine

RUN apk add --no-cache ffmpeg clamav su-exec font-dejavu \
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
