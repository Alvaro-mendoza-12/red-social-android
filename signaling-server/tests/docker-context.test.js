const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');

const root = path.resolve(__dirname, '..');

test('Docker image copies every local runtime module required by index.js', () => {
    const indexSource = fs.readFileSync(path.join(root, 'index.js'), 'utf8');
    const dockerfile = fs.readFileSync(path.join(root, 'Dockerfile'), 'utf8');
    const requiredModules = Array.from(
        indexSource.matchAll(/require\(['"]\.\/(.+?)['"]\)/g),
        (match) => `${match[1]}.js`.replace(/\.js\.js$/, '.js')
    );

    assert.ok(requiredModules.length > 0, 'index.js should have local runtime modules');
    for (const moduleName of requiredModules) {
        assert.ok(
            fs.existsSync(path.join(root, moduleName)),
            `required local module is missing from source: ${moduleName}`
        );
        assert.match(
            dockerfile,
            new RegExp(`(?:COPY|ADD)[^\\n]*\\b${moduleName.replace('.', '\\.')}\\b`),
            `Dockerfile does not copy required runtime module: ${moduleName}`
        );
    }
});
