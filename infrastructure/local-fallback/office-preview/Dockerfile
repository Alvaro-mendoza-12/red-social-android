FROM docker.io/gotenberg/gotenberg:9.9.1-libreoffice@sha256:3d5fd268be20a7935d3e624770161080b615d9086548a2b501f44f388710529b AS office

FROM office AS curl-build
USER root
WORKDIR /tmp/curl-build
COPY sources.sha256 .

# Keep the converter on Debian 13. The newer Debian binaries require a newer glibc.
RUN set -eu; \
    archive=https://snapshot.debian.org/archive/debian/20261118T000000Z/pool/main/c/curl; \
    while read -r digest file; do \
        curl --fail --silent --show-error --location --proto '=https' --retry 3 "$archive/$file" -o "$file"; \
    done < sources.sha256; \
    sha256sum --strict --check sources.sha256; \
    sed -i 's|http://deb.debian.org|https://deb.debian.org|g' /etc/apt/sources.list /etc/apt/sources.list.d/debian.sources; \
    apt-get -o Acquire::Retries=3 update; \
    DEBIAN_FRONTEND=noninteractive apt-get -y --no-install-recommends -t trixie-backports -Pnocheck build-dep ./curl_8.23.0-2.dsc; \
    dpkg-source --no-check -x curl_8.23.0-2.dsc; \
    cd curl-8.23.0; \
    sed -i '1s/(8.23.0-2)/(8.23.0-2~novvin13+1)/' debian/changelog; \
    DEB_BUILD_OPTIONS='parallel=2 nocheck' dpkg-buildpackage -B -us -uc -Pnocheck; \
    cd ..; \
    mkdir /out; \
    for package in curl libcurl4t64 libcurl4-gnutls libcurl3t64-gnutls; do \
        cp "${package}_8.23.0-2~novvin13+1_"*.deb /out/; \
    done

FROM office
USER root
RUN --mount=type=bind,from=curl-build,source=/out,target=/tmp/curl-packages \
    dpkg -i /tmp/curl-packages/*.deb && apt-get check && test -z "$(dpkg --audit)"
USER gotenberg
