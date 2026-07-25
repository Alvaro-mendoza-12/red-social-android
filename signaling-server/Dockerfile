FROM node:22-alpine

WORKDIR /usr/src/app

COPY package.json package-lock.json ./
RUN npm ci --omit=dev

COPY index.js resolver.js ./

EXPOSE 3003

CMD ["node", "index.js"]
