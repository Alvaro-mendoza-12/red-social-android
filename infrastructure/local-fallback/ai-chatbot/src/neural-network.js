function seededRandom(seed) {
  let state = seed >>> 0
  return () => {
    state = (1664525 * state + 1013904223) >>> 0
    return state / 0xffffffff
  }
}

function createMatrix(rows, cols, random, scale = 0.2) {
  return Array.from({ length: rows }, () =>
    Array.from({ length: cols }, () => (random() * 2 - 1) * scale),
  )
}

function createVector(length, value = 0) {
  return Array.from({ length }, () => value)
}

function relu(value) {
  return value > 0 ? value : 0
}

function reluDerivative(value) {
  return value > 0 ? 1 : 0
}

function softmax(values) {
  const max = Math.max(...values)
  const exps = values.map((value) => Math.exp(value - max))
  const sum = exps.reduce((acc, value) => acc + value, 0)
  return exps.map((value) => value / sum)
}

class NeuralIntentClassifier {
  constructor({ inputSize, hiddenSize, outputSize, labels, seed = 42, weights } = {}) {
    if (!inputSize || !hiddenSize || !outputSize) {
      throw new Error('inputSize, hiddenSize and outputSize are required')
    }
    this.inputSize = inputSize
    this.hiddenSize = hiddenSize
    this.outputSize = outputSize
    this.labels = labels || []

    if (weights) {
      this.w1 = weights.w1
      this.b1 = weights.b1
      this.w2 = weights.w2
      this.b2 = weights.b2
    } else {
      const random = seededRandom(seed)
      this.w1 = createMatrix(hiddenSize, inputSize, random, 0.35)
      this.b1 = createVector(hiddenSize)
      this.w2 = createMatrix(outputSize, hiddenSize, random, 0.35)
      this.b2 = createVector(outputSize)
    }
  }

  forward(input) {
    const activeInputs = []
    for (let i = 0; i < input.length; i += 1) {
      if (input[i] !== 0) activeInputs.push(i)
    }
    const hiddenRaw = createVector(this.hiddenSize)
    const hidden = createVector(this.hiddenSize)
    for (let h = 0; h < this.hiddenSize; h += 1) {
      let value = this.b1[h]
      for (const i of activeInputs) value += this.w1[h][i] * input[i]
      hiddenRaw[h] = value
      hidden[h] = relu(value)
    }

    const outputRaw = createVector(this.outputSize)
    for (let o = 0; o < this.outputSize; o += 1) {
      let value = this.b2[o]
      for (let h = 0; h < this.hiddenSize; h += 1) value += this.w2[o][h] * hidden[h]
      outputRaw[o] = value
    }

    return {
      activeInputs,
      hiddenRaw,
      hidden,
      output: softmax(outputRaw),
    }
  }

  predict(input) {
    const { output } = this.forward(input)
    let bestIndex = 0
    for (let i = 1; i < output.length; i += 1) {
      if (output[i] > output[bestIndex]) bestIndex = i
    }
    return {
      label: this.labels[bestIndex] || String(bestIndex),
      confidence: output[bestIndex],
      probabilities: output,
    }
  }

  train(samples, { epochs = 900, learningRate = 0.08 } = {}) {
    const random = seededRandom(20260714)
    for (let epoch = 0; epoch < epochs; epoch += 1) {
      const shuffled = samples.slice()
      for (let index = shuffled.length - 1; index > 0; index -= 1) {
        const swapIndex = Math.floor(random() * (index + 1))
        const current = shuffled[index]
        shuffled[index] = shuffled[swapIndex]
        shuffled[swapIndex] = current
      }
      const epochRate = learningRate * (1 - (epoch / Math.max(epochs, 1)) * 0.35)
      for (const sample of shuffled) {
        this.trainOne(sample.input, sample.labelIndex, epochRate)
      }
    }
  }

  trainOne(input, labelIndex, learningRate) {
    const state = this.forward(input)
    const outputDelta = state.output.slice()
    outputDelta[labelIndex] -= 1

    const hiddenDelta = createVector(this.hiddenSize)
    for (let h = 0; h < this.hiddenSize; h += 1) {
      let value = 0
      for (let o = 0; o < this.outputSize; o += 1) value += outputDelta[o] * this.w2[o][h]
      hiddenDelta[h] = value * reluDerivative(state.hiddenRaw[h])
    }

    for (let o = 0; o < this.outputSize; o += 1) {
      for (let h = 0; h < this.hiddenSize; h += 1) {
        this.w2[o][h] -= learningRate * outputDelta[o] * state.hidden[h]
      }
      this.b2[o] -= learningRate * outputDelta[o]
    }

    for (let h = 0; h < this.hiddenSize; h += 1) {
      for (const i of state.activeInputs) {
        this.w1[h][i] -= learningRate * hiddenDelta[h] * input[i]
      }
      this.b1[h] -= learningRate * hiddenDelta[h]
    }
  }

  toJSON() {
    return {
      inputSize: this.inputSize,
      hiddenSize: this.hiddenSize,
      outputSize: this.outputSize,
      labels: this.labels,
      weights: {
        w1: this.w1,
        b1: this.b1,
        w2: this.w2,
        b2: this.b2,
      },
    }
  }

  static fromJSON(payload) {
    return new NeuralIntentClassifier(payload)
  }
}

module.exports = {
  NeuralIntentClassifier,
}
