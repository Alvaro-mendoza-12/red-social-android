FROM node:22-alpine

WORKDIR /usr/src/app

COPY package.json ./
RUN npm install --omit=dev

COPY src ./src

EXPOSE 3002

CMD ["node", "src/server.js"]
