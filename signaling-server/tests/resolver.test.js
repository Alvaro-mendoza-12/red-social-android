const test = require('node:test');
const assert = require('node:assert/strict');

const {
    isTrustedHostname,
    validateExternalCinemaUrl
} = require('../resolver');

test('cinema links accept supported direct HTTPS media without credentials', () => {
    const result = validateExternalCinemaUrl('https://media.example.com/movie.mp4?quality=hd');
    assert.equal(result.ok, true);
    assert.equal(result.kind, 'direct');
});

test('cinema links reject client-side SSRF targets and unsafe schemes', () => {
    const rejected = [
        'http://example.com/movie.mp4',
        'https://localhost/admin',
        'https://127.0.0.1/private',
        'https://[::1]/private',
        'https://router.local/config',
        'https://user:secret@example.com/movie.mp4',
        'https://example.com/watch/123',
        'https://archive.org/details/not-a-direct-download',
        'magnet:?xt=urn:btih:ABC123',
        'file:///etc/passwd',
        'javascript:alert(1)'
    ];
    for (const value of rejected) {
        assert.equal(validateExternalCinemaUrl(value).ok, false, value);
    }
});

test('cinema links accept constrained Drive and Archive providers', () => {
    assert.equal(validateExternalCinemaUrl('https://drive.google.com/file/d/ABC_123/view').kind, 'drive');
    assert.equal(validateExternalCinemaUrl('https://archive.org/download/item/movie').kind, 'archive');
});

test('provider detection compares hostnames, not query text', () => {
    assert.equal(isTrustedHostname('cdn.media.example', 'media.example'), true);
    assert.equal(isTrustedHostname('evil.example', 'media.example'), false);
});
