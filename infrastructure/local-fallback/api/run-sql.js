const { Client } = require('pg');
const fs = require('fs');
const path = require('path');

async function run() {
    const client = new Client({
        connectionString: 'postgres://conectate:change-this-local-postgres-password@127.0.0.1:5433/conectate_fallback'
    });
    try {
        await client.connect();
        console.log('Connected to Database.');
        
        const sql1Path = path.resolve(__dirname, '../../../../sql/migrations/fix_delete_cascades_and_rls.sql');
        const sql1 = fs.readFileSync(sql1Path, 'utf8');
        await client.query(sql1);
        console.log('Executed fix_delete_cascades_and_rls.sql successfully.');
        
        const sql2Path = path.resolve(__dirname, '../../../../sql/migrations/fix_community_update_rls.sql');
        const sql2 = fs.readFileSync(sql2Path, 'utf8');
        await client.query(sql2);
        console.log('Executed fix_community_update_rls.sql successfully.');
        
    } catch (err) {
        console.error('Error executing SQL:', err);
    } finally {
        await client.end();
    }
}
run();
