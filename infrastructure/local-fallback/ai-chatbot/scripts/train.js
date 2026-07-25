const fs = require('fs')
const path = require('path')
const { trainModel } = require('../src/chatbot')

const dataDir = process.env.AI_DATA_DIR || path.resolve(__dirname, '..', 'data-runtime')
const intentsFile = process.env.AI_INTENTS_FILE || path.resolve(__dirname, '..', 'data', 'intents.json')
const knowledgeFile = process.env.AI_KNOWLEDGE_FILE || path.resolve(
  __dirname,
  '..',
  'data',
  'knowledge.json',
)
const modelFile = process.env.AI_MODEL_FILE || path.join(dataDir, 'model.json')

fs.mkdirSync(path.dirname(modelFile), { recursive: true })
const intentsData = JSON.parse(fs.readFileSync(intentsFile, 'utf8'))
const knowledgeData = JSON.parse(fs.readFileSync(knowledgeFile, 'utf8'))
const startedAt = Date.now()
const model = trainModel(intentsData, { knowledgeData })
const temporaryFile = `${modelFile}.${process.pid}.tmp`
fs.writeFileSync(temporaryFile, JSON.stringify(model))
fs.renameSync(temporaryFile, modelFile)

console.log(
  JSON.stringify({
    ok: true,
    modelFile,
    modelType: model.modelType,
    intents: model.labels.length,
    vocabulary: model.vocabulary.length,
    hiddenNeurons: model.classifier.hiddenSize,
    parameters: model.training.parameters,
    trainingMs: Date.now() - startedAt,
    modelBytes: fs.statSync(modelFile).size,
    datasetFingerprint: model.datasetFingerprint,
    knowledgeCards: model.knowledge.labels.length,
    knowledgeTrainingSamples: model.knowledge.trainingSamples,
    knowledgeParameters: model.knowledge.training.parameters,
  }),
)
