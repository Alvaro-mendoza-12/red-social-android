const fs = require('fs');
const p = '/home/alvaro/signaling-server/index.js';
let code = fs.readFileSync(p, 'utf8');
code = code.replace(/console\.log\(\[SOCKET_DEBUG\]\sjoin-room\srawData:,\srawData,\stypeof\srawData\);/g, "console.log('[SOCKET_DEBUG] join-room rawData:', rawData, typeof rawData);");
fs.writeFileSync(p, code);
