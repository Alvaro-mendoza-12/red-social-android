FROM node:22.23.2-alpine

# Install the distribution zlib security fix even when the base tag is cached.
RUN apk add --no-cache --upgrade 'zlib>=1.3.2-r1'

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
