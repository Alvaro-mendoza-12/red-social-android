const fs = require('fs');
const p = '/home/alvaro/signaling-server/index.js';
let code = fs.readFileSync(p, 'utf8');
code = code.replace(/socket\.on\(\"\sjoin-room.*?\{/g, "socket.on('join-room', async (rawData) => {");
code = code.replace(/socket\.on\(\"\sjoin-call.*?\{/g, "socket.on('join-call', (rawData) => {");
fs.writeFileSync(p, code);
