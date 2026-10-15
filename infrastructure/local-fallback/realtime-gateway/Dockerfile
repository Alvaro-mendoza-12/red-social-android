FROM node:22.23.2-alpine

# Install the distribution zlib security fix even when the base tag is cached.
RUN apk add --no-cache --upgrade 'zlib>=1.3.2-r1'

WORKDIR /usr/src/app

COPY package.json package-lock.json ./
RUN npm ci --omit=dev

COPY src ./src

USER node

EXPOSE 3002

CMD ["node", "src/server.js"]
