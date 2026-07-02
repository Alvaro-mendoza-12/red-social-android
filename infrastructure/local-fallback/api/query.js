const db = require('better-sqlite3')('../backend-database.sqlite');
console.log(db.prepare("SELECT sql FROM sqlite_master WHERE name='fallback_collection_members'").get().sql);
