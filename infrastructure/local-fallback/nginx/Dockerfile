# Protected media is owned by the API runtime UID.
ARG NGINX_BASE_IMAGE=nginx:1.30.7-alpine@sha256:caf4e1e032a20f9c3c5e28fc7f25ffd768ad8677b4975c54193075997b3a97e3
FROM ${NGINX_BASE_IMAGE}
RUN apk add --no-cache --upgrade 'zlib>=1.4-r0' 'libssl3>=3.5.9-r1' 'libcrypto3>=3.5.9-r1' \
    && addgroup -S -g 10001 novvin \
    && adduser -S -D -H -u 10001 -G novvin novvin \
    && sed -i 's/^user[[:space:]].*;/user novvin;/' /etc/nginx/nginx.conf
