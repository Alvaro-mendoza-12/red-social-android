FROM node:22.23.2-alpine

# Install the distribution zlib security fix even when the base tag is cached.
RUN apk add --no-cache --upgrade 'zlib>=1.3.2-r1'

WORKDIR /usr/src/app

COPY package.json package-lock.json ./
RUN npm ci --omit=dev

COPY index.js resolver.js payload-policy.js socket-event-guard.js call-join-policy.js live-access-policy.js distributed-adapter.js distributed-scope.js distributed-live-state.js live-guest-media-state.js ./

USER node

EXPOSE 3003

CMD ["node", "index.js"]
