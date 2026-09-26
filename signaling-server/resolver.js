const net = require('net');

const MAX_URL_LENGTH = 4096;
const SUPPORTED_MEDIA_SUFFIXES = ['.mp4', '.m4v', '.webm', '.m3u8', '.mpd'];
const configuredMediaHosts = new Set(
    (process.env.CINEMA_ALLOWED_MEDIA_HOSTS || '')
        .split(',')
        .map(value => normalizeHostname(value))
        .filter(Boolean)
);

function normalizeHostname(value) {
    return (value || '').toString().trim().toLowerCase().replace(/\.$/, '').replace(/^\[|\]$/g, '');
}

function isTrustedHostname(hostname, expectedDomain) {
    const normalized = normalizeHostname(hostname);
    const expected = normalizeHostname(expectedDomain);
    return Boolean(normalized && expected) && (normalized === expected || normalized.endsWith(`.${expected}`));
}

function isBlockedDirectHostname(hostname) {
    const normalized = normalizeHostname(hostname);
    if (!normalized || net.isIP(normalized)) return true;
    return normalized === 'localhost' ||
        normalized.endsWith('.localhost') ||
        normalized.endsWith('.local') ||
        normalized.endsWith('.internal') ||
        normalized.endsWith('.lan') ||
        normalized.endsWith('.home');
}

function isConfiguredMediaHost(hostname) {
    for (const allowed of configuredMediaHosts) {
        if (isTrustedHostname(hostname, allowed)) return true;
    }
    return false;
}

function isSupportedDirectMediaPath(pathname) {
    const normalized = (pathname || '').toString().toLowerCase();
    return SUPPORTED_MEDIA_SUFFIXES.some(suffix => normalized.endsWith(suffix));
}

function validateExternalCinemaUrl(rawUrl) {
    const value = (rawUrl || '').toString().trim();
    if (!value || value.length > MAX_URL_LENGTH) {
        return { ok: false, error: 'Invalid or oversized URL' };
    }

    let parsed;
    try {
        parsed = new URL(value);
    } catch {
        return { ok: false, error: 'Invalid URL' };
    }
    if (parsed.protocol !== 'https:' || parsed.username || parsed.password) {
        return { ok: false, error: 'Only credential-free HTTPS links are allowed' };
    }
    if (isBlockedDirectHostname(parsed.hostname)) {
        return { ok: false, error: 'Local, private, and IP-address links are not allowed' };
    }

    const archiveDownload = isTrustedHostname(parsed.hostname, 'archive.org') &&
        parsed.pathname.startsWith('/download/');
    const driveFile = isTrustedHostname(parsed.hostname, 'drive.google.com') &&
        /^\/file\/d\/[a-zA-Z0-9_-]+(?:\/|$)/.test(parsed.pathname);
    const directMedia = isSupportedDirectMediaPath(parsed.pathname);
    if (!archiveDownload && !driveFile && !directMedia && !isConfiguredMediaHost(parsed.hostname)) {
        return {
            ok: false,
            error: 'Only direct media, approved hosts, Google Drive files, or Archive.org downloads are allowed'
        };
    }

    return {
        ok: true,
        kind: driveFile ? 'drive' : (archiveDownload ? 'archive' : 'direct'),
        value: parsed.toString(),
        parsed
    };
}

async function resolveLink(url) {
    const validated = validateExternalCinemaUrl(url);
    if (!validated.ok) return { error: validated.error };

    if (validated.kind === 'drive') {
        const idMatch = validated.parsed.pathname.match(/^\/file\/d\/([a-zA-Z0-9_-]+)(?:\/|$)/);
        if (!idMatch?.[1]) return { error: 'Invalid Google Drive file URL' };
        return {
            stream_url: `https://drive.google.com/uc?export=download&id=${encodeURIComponent(idMatch[1])}`,
            title: 'Video de Google Drive',
            type: 'drive'
        };
    }

    return {
        stream_url: validated.value,
        title: validated.kind === 'archive' ? 'Contenido de dominio público' : 'Video externo',
        type: validated.kind
    };
}

module.exports = {
    isBlockedDirectHostname,
    isTrustedHostname,
    resolveLink,
    validateExternalCinemaUrl
};
