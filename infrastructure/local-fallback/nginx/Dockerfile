# Keep the reviewed Nginx base; protected media is owned by the API runtime UID.
ARG NGINX_BASE_IMAGE=nginx@sha256:65645c7bb6a0661892a8b03b89d0743208a18dd2f3f17a54ef4b76fb8e2f2a10
FROM ${NGINX_BASE_IMAGE}
RUN addgroup -S -g 10001 novvin \
    && adduser -S -D -H -u 10001 -G novvin novvin \
    && sed -i 's/^user[[:space:]].*;/user novvin;/' /etc/nginx/nginx.conf
