FROM node:20.20.2-alpine

WORKDIR /app

COPY package.json ./
COPY data ./data
COPY scripts ./scripts
COPY src ./src
COPY tests ./tests

RUN apk add --no-cache su-exec && \
    mkdir -p /data/ai && \
    chown -R node:node /data/ai /app && \
    chmod +x /app/scripts/start-as-node.sh

# The entrypoint fixes the small persistent model directory and immediately
# drops privileges before Node starts.

ENV NODE_ENV=production
ENV PORT=3010
ENV AI_DATA_DIR=/data/ai

EXPOSE 3010

ENTRYPOINT ["/app/scripts/start-as-node.sh"]
CMD ["node", "src/server.js"]
