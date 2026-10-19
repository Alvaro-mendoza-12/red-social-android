export function createSpellingWorker(){return new Worker(new URL('../workers/spelling.worker.ts',import.meta.url),{type:'module'})}
