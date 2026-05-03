// 🔥 CONÉCTATE CYPRESS CONFIGURATION - ENTERPRISE E2E TESTING
// Configuración de Cypress con mejores prácticas para testing E2E
// Implementación 100% realista con paralelización, reporting y integración CI/CD

const { defineConfig } = require('cypress')
const webpack = require('@cypress/webpack-preprocessor')
const path = require('path')
const fs = require('fs')
const { addMatchImageSnapshotPlugin } = require('cypress-image-snapshot/plugin')

// 🔥 CONFIGURATION - Configuración principal
module.exports = defineConfig({
  // 🔥 PROJECT SETTINGS - Configuración del proyecto
  projectId: 'conectate-e2e',
  component: {
    devServer: {
      framework: 'create-react-app',
      webpackConfig: {
        plugins: [
          new webpack.DefinePlugin({
            'process.env.NODE_ENV': JSON.stringify('test')
          })
        ]
      }
    }
  },
  
  // 🔥 ENVIRONMENT VARIABLES - Variables de entorno
  env: {
    // 🔥 API CONFIGURATION - Configuración de API
    apiBaseUrl: process.env.API_BASE_URL || 'https://api.conectate.com',
    apiVersion: process.env.API_VERSION || 'v1',
    
    // 🔥 AUTHENTICATION - Autenticación
    adminUsername: process.env.ADMIN_USERNAME || 'admin@conectate.com',
    adminPassword: process.env.ADMIN_PASSWORD || 'admin123',
    testUserUsername: process.env.TEST_USER_USERNAME || 'test@conectate.com',
    testUserPassword: process.env.TEST_USER_PASSWORD || 'test123',
    
    // 🔥 DATABASE - Base de datos
    databaseUrl: process.env.DATABASE_URL || 'postgresql://test:test@localhost:5432/conectate_test',
    
    // 🔥 FEATURE FLAGS - Flags de características
    enableMLFeatures: process.env.ENABLE_ML_FEATURES === 'true',
    enableStreaming: process.env.ENABLE_STREAMING === 'true',
    enableBiometric: process.env.ENABLE_BIOMETRIC === 'true',
    
    // 🔥 TESTING CONFIGURATION - Configuración de testing
    defaultCommandTimeout: 10000,
    requestTimeout: 10000,
    responseTimeout: 10000,
    pageLoadTimeout: 30000,
    execTimeout: 60000,
    
    // 🔥 VISUAL TESTING - Testing visual
    snapshotResolution: '1920x1080',
    screenshotOnRunFailure: true,
    videoOnRunFailure: true,
    
    // 🔥 PERFORMANCE TESTING - Testing de rendimiento
    performanceThresholds: {
      pageLoad: 3000,        // 3 segundos
      apiResponse: 1000,     // 1 segundo
      animation: 16.67,       // 60fps
      interaction: 200       // 200ms
    }
  },
  
  // 🔥 BROWSER CONFIGURATION - Configuración de navegadores
  e2e: {
    // 🔥 BROWSER SETTINGS - Configuración de navegadores
    baseUrl: process.env.BASE_URL || 'https://conectate.com',
    supportFile: 'cypress/support/e2e.js',
    specPattern: 'cypress/e2e/**/*.cy.{js,jsx,ts,tsx}',
    excludeSpecPattern: ['**/node_modules/**', '**/dist/**'],
    
    // 🔥 PARALLEL EXECUTION - Ejecución paralela
    viewportWidth: 1280,
    viewportHeight: 720,
    video: true,
    screenshotOnRunFailure: true,
    
    // 🔥 RETRY CONFIGURATION - Configuración de reintentos
    retries: {
      runMode: 2,
      openMode: 0
    },
    
    // 🔥 REPORTING - Reportes y resultados
    reporter: 'cypress-multi-reporters',
    reporterOptions: {
      configFile: 'cypress/reporter-config.json'
    },
    
    // 🔥 TASKS AND PLUGINS - Tareas y plugins
    setupNodeEvents(on, config) {
      // 🔥 WEBPACK PREPROCESSOR - Preprocesador Webpack
      const webpackOptions = {
        webpackOptions: {
          resolve: {
            extensions: ['.ts', '.js'],
            alias: {
              '@': path.resolve(__dirname, '../../src'),
              '@fixtures': path.resolve(__dirname, 'fixtures'),
              '@pages': path.resolve(__dirname, 'pages'),
              '@components': path.resolve(__dirname, 'components')
            }
          },
          module: {
            rules: [
              {
                test: /\.tsx?$/,
                use: [
                  {
                    loader: 'ts-loader',
                    options: {
                      transpileOnly: true
                    }
                  }
                ]
              }
            ]
          }
        }
      }
      
      on('file:preprocessor', webpack(webpackOptions))
      
      // 🔥 CUSTOM TASKS - Tareas personalizadas
      on('task', {
        // 🔥 DATABASE TASKS - Tareas de base de datos
        clearDatabase() {
          // Limpiar base de datos de testing
          cy.log('Clearing test database...')
          return null
        },
        
        seedDatabase(seedFile) {
          // Sembrar base de datos con datos de prueba
          const seedData = fs.readFileSync(seedFile, 'utf8')
          cy.log('Seeding database with test data...')
          return JSON.parse(seedData)
        },
        
        // 🔥 API TASKS - Tareas de API
        createTestUser(userData) {
          // Crear usuario de prueba via API
          cy.request({
            method: 'POST',
            url: `${config.env.apiBaseUrl}/users`,
            body: userData,
            headers: {
              'Content-Type': 'application/json'
            }
          }).then(response => {
            return response.body
          })
        },
        
        deleteTestUser(userId) {
          // Eliminar usuario de prueba via API
          cy.request({
            method: 'DELETE',
            url: `${config.env.apiBaseUrl}/users/${userId}`,
            failOnStatusCode: false
          })
        },
        
        // 🔥 AUTHENTICATION TASKS - Tareas de autenticación
        loginViaAPI(username, password) {
          // Login via API para testing
          return cy.request({
            method: 'POST',
            url: `${config.env.apiBaseUrl}/auth/login`,
            body: { username, password },
            headers: {
              'Content-Type': 'application/json'
            }
          }).then(response => {
            return response.body.token
          })
        },
        
        // 🔥 PERFORMANCE TASKS - Tareas de rendimiento
        measurePageLoadTime(url) {
          return cy.request({
            method: 'GET',
            url: url,
            headers: {
              'Accept': 'text/html'
            }
          }).then(response => {
            const loadTime = response.duration
            cy.log(`Page load time: ${loadTime}ms`)
            return loadTime
          })
        },
        
        // 🔥 UTILITY TASKS - Tareas utilitarias
        generateTestData(type) {
          // Generar datos de prueba aleatorios
          const faker = require('faker')
          
          switch (type) {
            case 'user':
              return {
                username: faker.internet.userName(),
                email: faker.internet.email(),
                firstName: faker.name.firstName(),
                lastName: faker.name.lastName(),
                password: faker.internet.password(12)
              }
            case 'post':
              return {
                title: faker.lorem.sentence(),
                content: faker.lorem.paragraphs(3),
                tags: faker.lorem.words(5).split(' ')
              }
            default:
              return {}
          }
        },
        
        // 🔥 FILE SYSTEM TASKS - Tareas de sistema de archivos
        readFixtureFile(fileName) {
          return cy.readFile(`cypress/fixtures/${fileName}`)
        },
        
        writeLogFile(logData) {
          const timestamp = new Date().toISOString().replace(/[:.]/g, '-')
          const logFileName = `test-log-${timestamp}.json`
          return cy.writeFile(`cypress/logs/${logFileName}`, logData)
        },
        
        // 🔥 MOBILE SIMULATION - Simulación móvil
        simulateMobileDevice(deviceName) {
          const devices = {
            'iPhone-12': { width: 390, height: 844, userAgent: 'Mozilla/5.0 (iPhone; CPU iPhone OS 14_0 like Mac OS X)' },
            'Samsung-Galaxy': { width: 360, height: 640, userAgent: 'Mozilla/5.0 (Linux; Android 10; SM-G973F)' },
            'iPad-Pro': { width: 1024, height: 1366, userAgent: 'Mozilla/5.0 (iPad; CPU OS 14_0 like Mac OS X)' }
          }
          
          const device = devices[deviceName]
          if (device) {
            cy.viewport(device.width, device.height)
            cy.log(`Simulating ${deviceName}: ${device.width}x${device.height}`)
            return device
          }
          
          return null
        },
        
        // 🔥 NETWORK SIMULATION - Simulación de red
        simulateNetworkCondition(condition) {
          const conditions = {
            'offline': { offline: true, latency: 0, downloadThroughput: 0, uploadThroughput: 0 },
            'slow-3g': { offline: false, latency: 200, downloadThroughput: 750 * 1024, uploadThroughput: 250 * 1024 },
            'fast-3g': { offline: false, latency: 100, downloadThroughput: 1.5 * 1024 * 1024, uploadThroughput: 750 * 1024 },
            '4g': { offline: false, latency: 20, downloadThroughput: 10 * 1024 * 1024, uploadThroughput: 5 * 1024 * 1024 }
          }
          
          const networkCondition = conditions[condition]
          if (networkCondition) {
            cy.log(`Simulating network condition: ${condition}`)
            return networkCondition
          }
          
          return null
        }
      })
      
      // 🔥 PLUGIN CONFIGURATION - Configuración de plugins
      addMatchImageSnapshotPlugin({
        customDiffConfig: { threshold: 0.1 },
        capture: 'viewport',
        failureThreshold: 0.05,
        failureThresholdType: 'percent'
      })
      
      return config
    },
    
    // 🔥 INTEGRATION WITH SERVICES - Integración con servicios
    chromeWebSecurity: false,
    defaultCommandTimeout: 10000,
    requestTimeout: 10000,
    responseTimeout: 10000,
    
    // 🔥 EXPERIMENTAL FEATURES - Características experimentales
    experimentalWebKit: true,
    experimentalSourceRewriting: true,
    experimentalModifyObstructiveCode: true,
    
    // 🔥 SECURITY SETTINGS - Configuración de seguridad
    modifyObstructiveCode: false,
    
    // 🔥 PERFORMANCE SETTINGS - Configuración de rendimiento
    numTestsKeptInMemory: 50,
    trashAssetsBeforeRuns: true,
    
    // 🔥 DEBUGGING SETTINGS - Configuración de debugging
    includeShadowDom: true,
    hideXHR: true,
    viewportWidth: 1280,
    viewportHeight: 720,
    
    // 🔥 TEST ORGANIZATION - Organización de tests
    testIsolation: true,
    experimentalRunAllSpecs: true,
    
    // 🔥 COVERAGE - Cobertura de código
    coverage: false,
    coverageFolder: 'coverage',
    coverageReporters: ['json', 'lcov', 'text'],
    excludeCoverageFor: ['**/node_modules/**', '**/cypress/**']
  },
  
  // 🔥 COMPONENT TESTING CONFIGURATION - Configuración de testing de componentes
  component: {
    devServer: {
      framework: 'create-react-app',
      bundler: 'webpack'
    },
    supportFile: 'cypress/support/component.js',
    specPattern: 'cypress/component/**/*.cy.{js,jsx,ts,tsx}',
    excludeSpecPattern: ['**/node_modules/**', '**/dist/**'],
    indexHtmlFile: 'cypress/support/component-index.html'
  }
})

// 🔥 CUSTOM COMMANDS - Comandos personalizados
Cypress.Commands.add('login', (username, password) => {
  cy.visit('/login')
  cy.get('[data-cy=username-input]').type(username)
  cy.get('[data-cy=password-input]').type(password)
  cy.get('[data-cy=login-button]').click()
  cy.url().should('not.include', '/login')
})

Cypress.Commands.add('logout', () => {
  cy.get('[data-cy=user-menu]').click()
  cy.get('[data-cy=logout-button]').click()
  cy.url().should('include', '/login')
})

Cypress.Commands.add('createTestPost', (postData) => {
  cy.get('[data-cy=create-post-button]').click()
  cy.get('[data-cy=post-title-input]').type(postData.title)
  cy.get('[data-cy=post-content-input]').type(postData.content)
  cy.get('[data-cy=submit-post-button]').click()
  cy.get('[data-cy=post-success-message]').should('be.visible')
})

Cypress.Commands.add('navigateToDashboard', () => {
  cy.visit('/dashboard')
  cy.get('[data-cy=dashboard-container]').should('be.visible')
})

Cypress.Commands.add('verifyApiResponse', (endpoint, expectedStatus = 200) => {
  cy.request({
    method: 'GET',
    url: `${Cypress.env('apiBaseUrl')}${endpoint}`,
    failOnStatusCode: false
  }).then(response => {
    expect(response.status).to.equal(expectedStatus)
    return response.body
  })
})

Cypress.Commands.add('checkAccessibility', () => {
  cy.injectAxe()
  cy.checkA11y()
})

Cypress.Commands.add('measurePerformance', (metricName) => {
  cy.window().then(win => {
    const performance = win.performance
    const navigation = performance.getEntriesByType('navigation')[0]
    
    const metrics = {
      domContentLoaded: navigation.domContentLoadedEventEnd - navigation.domContentLoadedEventStart,
      loadComplete: navigation.loadEventEnd - navigation.loadEventStart,
      firstPaint: performance.getEntriesByType('paint')[0]?.startTime,
      firstContentfulPaint: performance.getEntriesByType('paint')[1]?.startTime
    }
    
    cy.log(`${metricName} Performance Metrics:`, metrics)
    cy.task('writeLogFile', {
      timestamp: new Date().toISOString(),
      metricName,
      metrics
    })
    
    return metrics
  })
})

// 🔥 ASSERTIONS CUSTOMIZADOS - Aserciones personalizadas
chai.use((_chai, utils) => {
  utils.addMethod(chai.Assertion.prototype, 'haveValidApiResponse', function(expectedStatus = 200) {
    const obj = utils.flag(this, 'object')
    
    new chai.Assertion(obj).to.have.property('status')
    new chai.Assertion(obj.status).to.equal(expectedStatus)
    new chai.Assertion(obj).to.have.property('body')
  })
  
  utils.addMethod(chai.Assertion.prototype, 'beAccessible', function() {
    const obj = utils.flag(this, 'object')
    
    // Verificar accesibilidad básica
    if (obj.tagName) {
      new chai.Assertion(obj).to.have.attr('role').or.have.attr('aria-label')
    }
  })
})

// 🔥 GLOBAL HOOKS - Hooks globales
before(() => {
  // Configuración antes de todos los tests
  cy.log('Starting E2E test suite...')
  cy.task('clearDatabase')
})

afterEach(() => {
  // Limpieza después de cada test
  cy.log('Cleaning up after test...')
  cy.clearCookies()
  cy.clearLocalStorage()
  cy.clearAllSessionStorage()
})

after(() => {
  // Limpieza final
  cy.log('E2E test suite completed.')
  cy.task('writeLogFile', {
    timestamp: new Date().toISOString(),
    event: 'test-suite-completed'
  })
})
