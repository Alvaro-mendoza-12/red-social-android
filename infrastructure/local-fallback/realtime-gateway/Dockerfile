FROM node:22.23.5-alpine@sha256:1314fb30ec37592d5845e5a160d522c0e680972aa534549940b8d73bde88481a

# Require the patched runtime libraries even when the base tag is cached.
RUN apk add --no-cache --upgrade 'zlib>=1.4-r0' 'libssl3>=3.5.9-r1' 'libcrypto3>=3.5.9-r1'

WORKDIR /usr/src/app

COPY package.json package-lock.json ./
RUN npm ci --omit=dev

COPY src ./src

USER node

EXPOSE 3002

CMD ["node", "src/server.js"]
