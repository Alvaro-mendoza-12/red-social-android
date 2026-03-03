const ytdl = require('ytdl-core');
const axios = require('axios');
const queryString = require('query-string');

const RD_TOKEN = "BJVCQF5D4RUZC74SYAISZOXDOM3JU5LITI5AYYZRWZWAGXSEWVLA";

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
            return await processMagnet(url);
        }

        if (url.includes("real-debrid.com") && !url.includes("download")) {
            // Could be a restricted link
            console.log("[RESOLVER] Detected RD Link");
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

async function processMagnet(magnetLink) {
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
                    return await processRestrictedLink(link, infoResp.data.filename);
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

module.exports = { resolveLink };
