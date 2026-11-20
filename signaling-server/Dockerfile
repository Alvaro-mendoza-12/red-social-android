FROM node:22.23.5-alpine@sha256:1314fb30ec37592d5845e5a160d522c0e680972aa534549940b8d73bde88481a

# Require the patched runtime libraries even when the base tag is cached.
RUN apk add --no-cache --upgrade 'zlib>=1.4-r0' 'libssl3>=3.5.9-r1' 'libcrypto3>=3.5.9-r1'

WORKDIR /usr/src/app

COPY package.json package-lock.json ./
RUN npm ci --omit=dev

COPY index.js resolver.js payload-policy.js socket-event-guard.js call-join-policy.js live-access-policy.js distributed-adapter.js distributed-scope.js distributed-live-state.js live-guest-media-state.js live-reaction-policy.js room-chat-policy.js room-chat-transport.js room-chat-stream-cleanup.js ./

USER node

EXPOSE 3003

CMD ["node", "index.js"]
