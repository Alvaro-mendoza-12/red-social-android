function classifyLiveLookupFailure(error) {
    if (error?.status === 403 && error?.message === 'LIVE_BANNED') return 'banned';
    if (error?.status === 404) return 'missing';
    return 'unavailable';
}

module.exports = { classifyLiveLookupFailure };
