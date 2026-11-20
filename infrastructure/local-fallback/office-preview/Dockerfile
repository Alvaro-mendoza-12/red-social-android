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

FROM curl-build AS xml-build
WORKDIR /tmp/libxml2-build
COPY libxml2-sources.sha256 libxml2-input-ownership.patch ./

# Build against the converter's own Debian ABI; keep its distributor patches.
RUN set -eu; \
    archive=https://snapshot.debian.org/archive/debian/20261118T000000Z/pool/main/libx/libxml2; \
    while read -r digest file; do \
        curl --fail --silent --show-error --location --proto '=https' --retry 3 "$archive/$file" -o "$file"; \
    done < libxml2-sources.sha256; \
    sha256sum --strict --check libxml2-sources.sha256; \
    DEBIAN_FRONTEND=noninteractive apt-get -y --no-install-recommends -Pnocheck,nopython build-dep ./*.dsc; \
    dpkg-source --no-check -x ./*.dsc libxml2; \
    cd libxml2; \
    test "$(dpkg-parsechangelog -S Version)" = "$(dpkg-query -W -f='${Version}' libxml2)"; \
    patch --dry-run --batch --fuzz=0 -p1 < ../libxml2-input-ownership.patch; \
    cp ../libxml2-input-ownership.patch debian/patches/; \
    echo libxml2-input-ownership.patch >> debian/patches/series; \
    sed -i '1s/+deb13u3)/+deb13u3+novvin232.1)/' debian/changelog; \
    DEB_BUILD_OPTIONS='parallel=2 nocheck' dpkg-buildpackage -B -us -uc -Pnocheck,nopython; \
    mkdir /xml-out; \
    cp ../libxml2_*+novvin232.1_*.deb /xml-out/

FROM office AS eot-package
USER root
WORKDIR /eot-packages

# Download only libeot for the native architecture from the signed Debian snapshot.
RUN set -eu; \
    echo 'deb [signed-by=/usr/share/keyrings/debian-archive-keyring.gpg check-valid-until=no] https://snapshot.debian.org/archive/debian/20261120T060000Z sid main' > /tmp/eot.list; \
    snapshot_apt() { apt-get -o Dir::Etc::sourcelist=/tmp/eot.list -o Dir::Etc::sourceparts=- -o Dir::State::lists=/tmp/eot-lists -o Acquire::Retries=3 -o APT::Update::Error-Mode=any "$@"; }; \
    mkdir -p /tmp/eot-lists/partial; \
    snapshot_apt update; \
    snapshot_apt download 'libeot0=1:0.0.4-1'; \
    test "$(dpkg-deb -f ./*.deb Architecture)" = "$(dpkg --print-architecture)"; \
    test "$(dpkg-deb -f ./*.deb Version)" = '1:0.0.4-1'

FROM office
USER root
RUN --mount=type=bind,from=curl-build,source=/out,target=/tmp/curl-packages \
    --mount=type=bind,from=xml-build,source=/xml-out,target=/tmp/xml-packages \
    --mount=type=bind,from=eot-package,source=/eot-packages,target=/tmp/eot-packages \
    dpkg -i /tmp/curl-packages/*.deb /tmp/xml-packages/*.deb /tmp/eot-packages/*.deb && apt-get check && test -z "$(dpkg --audit)"
USER gotenberg
