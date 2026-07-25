FROM node:20-alpine

WORKDIR /app

COPY package.json ./
COPY data ./data
COPY scripts ./scripts
COPY src ./src
COPY tests ./tests

RUN mkdir -p /data/ai && chown -R node:node /data/ai /app

# The persistent bind mount is created by Docker on the host and can be owned
# by root. Keep this isolated service able to create its first trained model.
USER root

ENV NODE_ENV=production
ENV PORT=3010
ENV AI_DATA_DIR=/data/ai

EXPOSE 3010

CMD ["node", "src/server.js"]
