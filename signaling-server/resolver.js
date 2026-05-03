const ytdl = require('ytdl-core');
const axios = require('axios');
const queryString = require('query-string');

// Optional: Real-Debrid support (token MUST come from environment, never hardcoded)
const RD_TOKEN = (process.env.RD_TOKEN || '').trim();
const hasRdToken = RD_TOKEN.length > 0;

function sleep(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
}

function cleanCinemaTitle(title) {
    return title
        .toString()
        .replace(/[^a-zA-Z0-9 ]/g, ' ')
        .replace(/\s+/g, ' ')
        .trim()
        .replace(/ /g, '+');
}

function isCinemaTvProvider(provider) {
    return ['tmdb_tv', 'drama', 'tv', 'series'].includes((provider || '').toString().trim().toLowerCase());
}

async function resolveLink(url) {
    try {
        console.log(`[RESOLVER] Processing URL: ${url}`);

        // 1. YouTube
        if (ytdl.validateURL(url)) {
            console.log("[RESOLVER] Detected YouTube");
            const info = await ytdl.getInfo(url);
            // Choose best format: HLS if available, else MP4
            // const format = ytdl.chooseFormat(info.formats, { quality: 'highest', filter: 'audioandvideo' });
            // Better strategy for ExoPlayer: Use the HLS manifest if present
            const hlsFormat = info.formats.find(f => f.mimeType && f.mimeType.includes("x-mpegURL"));
            const mp4Format = ytdl.chooseFormat(info.formats, { quality: 'highest', filter: 'audioandvideo' });

            const targetUrl = hlsFormat ? hlsFormat.url : mp4Format.url;

            return {
                stream_url: targetUrl,
                title: info.videoDetails.title,
                duration: parseInt(info.videoDetails.lengthSeconds) * 1000,
                type: 'youtube'
            };
        }

        // 2. Real-Debrid / Magnet
        if (url.startsWith("magnet:")) {
            console.log("[RESOLVER] Detected Magnet");
            if (!hasRdToken) {
                return { error: "RD_TOKEN not configured on server" };
            }
            return await processMagnet(url);
        }

        if (url.includes("real-debrid.com") && !url.includes("download")) {
            // Could be a restricted link
            console.log("[RESOLVER] Detected RD Link");
            if (!hasRdToken) {
                return { error: "RD_TOKEN not configured on server" };
            }
            return await processRestrictedLink(url);
        }

        // 3. Google Drive
        if (url.includes("drive.google.com")) {
            console.log("[RESOLVER] Detected Google Drive");
            // Transform view link to download link if possible
            // https://drive.google.com/file/d/VIDEO_ID/view?usp=sharing
            // -> https://drive.google.com/uc?export=download&id=VIDEO_ID
            const idMatch = url.match(/\/d\/([a-zA-Z0-9_-]+)/);
            if (idMatch && idMatch[1]) {
                const id = idMatch[1];
                const downloadUrl = `https://drive.google.com/uc?export=download&id=${id}`;
                return {
                    stream_url: downloadUrl,
                    title: "Google Drive Video",
                    type: 'drive'
                };
            }
        }

        // 4. Default / Direct
        console.log("[RESOLVER] Treating as Direct/Generic");
        return {
            stream_url: url,
            title: "Video Externo",
            type: 'direct'
        };

    } catch (e) {
        console.error("Link Resolution Error:", e);
        return { error: e.message };
    }
}

async function processMagnet(magnetLink, titleHint = "RD Video") {
    try {
        console.log("Adding Magnet to RD...");
        const addResp = await axios.post("https://api.real-debrid.com/rest/1.0/torrents/addMagnet",
            queryString.stringify({ magnet: magnetLink }),
            { headers: { Authorization: `Bearer ${RD_TOKEN}` } }
        );
        const rdId = addResp.data.id;

        console.log(`Magnet added. ID: ${rdId}. Selecting all files...`);

        // Select Files
        await axios.post(`https://api.real-debrid.com/rest/1.0/torrents/selectFiles/${rdId}`,
            queryString.stringify({ files: "all" }),
            { headers: { Authorization: `Bearer ${RD_TOKEN}` } }
        );

        // Poll
        let attempts = 0;
        while (attempts < 15) {
            await new Promise(r => setTimeout(r, 1000));
            attempts++;

            const infoResp = await axios.get(`https://api.real-debrid.com/rest/1.0/torrents/info/${rdId}`,
                { headers: { Authorization: `Bearer ${RD_TOKEN}` } }
            );
            const status = infoResp.data.status;
            console.log(`Polling RD ${attempts}/15 - Status: ${status}`);

            if (status === 'downloaded' || status === 'cached') {
                const links = infoResp.data.links;
                if (links && links.length > 0) {
                    const link = links[0];
                    return await processRestrictedLink(link, infoResp.data.filename || titleHint);
                }
            } else if (status === 'magnet_error' || status === 'error' || status === 'dead') {
                throw new Error(`Magnet Error: ${status}`);
            }
        }
    } catch (e) {
        console.error("RD Error:", e);
        throw e;
    }
    throw new Error("Timeout resolving Magnet");
}

async function resolveCinemaItem(item = {}) {
    try {
        if (!hasRdToken) {
            return { error: "RD_TOKEN not configured on server" };
        }

        const title = (item.title || '').toString().trim();
        const provider = (item.provider || '').toString().trim().toLowerCase();
        const imdbId = (item.imdbId || '').toString().trim();
        if (!title) {
            return { error: "Missing content title" };
        }

        const isTv = isCinemaTvProvider(provider);
        const torrentioPaths = [];

        if (imdbId.startsWith('tt')) {
            torrentioPaths.push(isTv ? `series/${imdbId}:1:1` : `movie/${imdbId}`);
        }

        const cleanedTitle = cleanCinemaTitle(title);
        if (cleanedTitle) {
            torrentioPaths.push(isTv ? `series/${cleanedTitle}:1:1` : `movie/${cleanedTitle}`);
        }

        for (const path of [...new Set(torrentioPaths)]) {
            try {
                const torrentioUrl = `https://torrentio.strem.fun/stream/${path}.json`;
                const response = await axios.get(torrentioUrl, { timeout: 10000 });
                const streams = Array.isArray(response.data?.streams) ? response.data.streams : [];
                if (streams.length === 0) {
                    continue;
                }

                const sortedStreams = streams
                    .filter(stream => stream?.infoHash)
                    .sort((a, b) => scoreTorrentioLanguage(a) - scoreTorrentioLanguage(b));

                for (const stream of sortedStreams.slice(0, 3)) {
                    const infoHash = stream.infoHash.toString().trim();
                    if (!infoHash) continue;

                    try {
                        const resolved = await processMagnet(`magnet:?xt=urn:btih:${infoHash}`, title);
                        if (resolved?.stream_url) {
                            return {
                                ...resolved,
                                title
                            };
                        }
                    } catch (error) {
                        console.warn(`[RESOLVER] Torrentio stream failed for "${title}":`, error.message || error);
                    }

                    await sleep(150);
                }
            } catch (error) {
                console.warn(`[RESOLVER] Torrentio lookup failed for "${title}":`, error.message || error);
            }
        }

        return { error: "No premium stream found for this title" };
    } catch (error) {
        console.error("[RESOLVER] Cinema item resolution error:", error);
        return { error: error.message || "Cinema resolution failed" };
    }
}

function scoreTorrentioLanguage(stream) {
    const title = (stream?.title || '').toString().toLowerCase();
    if (title.includes('latino') || title.includes('español') || title.includes('castellano')) return 0;
    if (title.includes('multi') || title.includes('spanish') || title.includes('subs') || title.includes('vose')) return 1;
    return 2;
}

async function processRestrictedLink(link, titleHint = "RD Video") {
    try {
        console.log(`Unrestricting link: ${link}`);
        const unrestrictResp = await axios.post("https://api.real-debrid.com/rest/1.0/unrestrict/link",
            queryString.stringify({ link: link }),
            { headers: { Authorization: `Bearer ${RD_TOKEN}` } }
        );
        return {
            stream_url: unrestrictResp.data.download,
            title: titleHint,
            type: 'real-debrid'
        };
    } catch (e) {
        console.error("Unrestrict Error:", e);
        throw e;
    }
}

module.exports = { resolveLink, resolveCinemaItem };
