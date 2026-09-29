FROM node:22.23.2-alpine

WORKDIR /usr/src/app

COPY package.json package-lock.json ./
RUN npm ci --omit=dev

COPY index.js resolver.js payload-policy.js live-access-policy.js distributed-adapter.js distributed-scope.js distributed-live-state.js ./

USER node

EXPOSE 3003

CMD ["node", "index.js"]
