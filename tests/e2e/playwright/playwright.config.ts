// 🔥 CONÉCTATE PLAYWRIGHT CONFIGURATION - ENTERPRISE E2E TESTING
// Configuración de Playwright con mejores prácticas para testing E2E
// Implementación 100% realista con TypeScript, paralelización y reporting avanzado

import { defineConfig, devices, type PlaywrightTestConfig } from '@playwright/test'
import { type ConfigOptions } from '@playwright/test'
import * as path from 'path'
import * as fs from 'fs'

// 🔥 CONFIGURATION OPTIONS - Opciones de configuración
const config: PlaywrightTestConfig = {
  // 🔥 TEST DIRECTORY - Directorio de tests
  testDir: './tests',
  
  // 🔥 GLOBAL SETUP/TEARDOWN - Configuración global
  globalSetup: require.resolve('./global-setup.ts'),
  globalTeardown: require.resolve('./global-teardown.ts'),
  
  // 🔥 TIMEOUTS - Timeouts configurados
  timeout: 30000,           // 30 segundos por defecto
  expect: {
    timeout: 10000          // 10 segundos para aserciones
  },
  
  // 🔥 RETRY CONFIGURATION - Configuración de reintentos
  retries: {
    mode: 'retries-on-failure',
    retries: process.env.CI ? 2 : 0
  },
  
  // 🔥 WORKERS - Configuración de workers para paralelización
  workers: process.env.CI ? 4 : undefined, // 4 workers en CI, undefined localmente
  
  // 🔥 REPORTER - Configuración de reportes
  reporter: [
    ['html', { 
      outputFolder: 'playwright-report',
      open: process.env.CI ? 'never' : 'on-failure'
    }],
    ['json', { outputFile: 'test-results.json' }],
    ['junit', { outputFile: 'test-results-junit.xml' }],
    ['allure-playwright'], // Para reportes Allure
    ['@playwright/test/reporter', { 
      outputFile: 'matrix-results.txt' 
    }]
  ],
  
  // 🔥 USE - Configuración de uso
  use: {
    // 🔥 BROWSER CONFIGURATION - Configuración de navegador
    trace: 'on-first-retry',       // Trace en primer reintento
    screenshot: 'only-on-failure', // Screenshots solo en fallos
    video: 'retain-on-failure',    // Video en fallos
    
    // 🔥 VIEWPORT - Viewport configurado
    viewport: { width: 1280, height: 720 },
    
    // 🔥 PERFORMANCE - Configuración de rendimiento
    ignoreHTTPSErrors: true,
    bypassCSP: true,
    
    // 🔥 LOCALE - Configuración de locale
    locale: 'en-US',
    
    // 🔥 TIMEZONE - Configuración de timezone
    timezoneId: 'America/New_York',
    
    // 🔥 USER AGENT - User agent personalizado
    userAgent: 'Conectate-E2E-Testing/1.0.0',
    
    // 🔥 COLOR SCHEME - Esquema de colores
    colorScheme: 'light',
    
    // 🔥 GEO LOCATION - Geolocalización
    geolocation: { latitude: 40.7128, longitude: -74.0060 },
    
    // 🔥 PERMISSIONS - Permisos del navegador
    permissions: ['geolocation', 'notifications'],
    
    // 🔥 ACTION TIMEOUT - Timeout de acciones
    actionTimeout: 15000,
    
    // 🔥 NAVIGATION TIMEOUT - Timeout de navegación
    navigationTimeout: 30000
  },
  
  // 🔥 PROJECT CONFIGURATION - Configuración de proyectos
  projects: [
    // 🔥 CHROMIUM PROJECT - Proyecto Chromium
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
      grep: /@chromium/,
      grepInvert: /@mobile|@webkit/,
      testMatch: '**/chromium/**/*.spec.ts'
    },
    
    // 🔥 FIREFOX PROJECT - Proyecto Firefox
    {
      name: 'firefox',
      use: { ...devices['Desktop Firefox'] },
      grep: /@firefox/,
      grepInvert: /@mobile|@webkit/,
      testMatch: '**/firefox/**/*.spec.ts'
    },
    
    // 🔥 WEBKIT PROJECT - Proyecto WebKit (Safari)
    {
      name: 'webkit',
      use: { ...devices['Desktop Safari'] },
      grep: /@webkit/,
      grepInvert: /@mobile|@chromium|@firefox/,
      testMatch: '**/webkit/**/*.spec.ts'
    },
    
    // 🔥 MOBILE CHROME PROJECT - Proyecto móvil Chrome
    {
      name: 'mobile-chrome',
      use: { ...devices['Pixel 5'] },
      grep: /@mobile/,
      grepInvert: /@desktop|@webkit/,
      testMatch: '**/mobile/**/*.spec.ts'
    },
    
    // 🔥 MOBILE SAFARI PROJECT - Proyecto móvil Safari
    {
      name: 'mobile-safari',
      use: { ...devices['iPhone 12'] },
      grep: /@mobile/,
      grepInvert: /@desktop|@chromium|@firefox/,
      testMatch: '**/mobile/**/*.spec.ts'
    },
    
    // 🔥 TABLET PROJECT - Proyecto tablet
    {
      name: 'tablet',
      use: { ...devices['iPad Pro'] },
      grep: /@tablet/,
      testMatch: '**/tablet/**/*.spec.ts'
    },
    
    // 🔥 API TESTING PROJECT - Proyecto de testing API
    {
      name: 'api-testing',
      testMatch: '**/api/**/*.spec.ts',
      use: {
        ...devices['Desktop Chrome'],
        // No browser para API testing
        headless: true
      }
    },
    
    // 🔥 VISUAL TESTING PROJECT - Proyecto de testing visual
    {
      name: 'visual-testing',
      testMatch: '**/visual/**/*.spec.ts',
      use: {
        ...devices['Desktop Chrome'],
        screenshot: 'only-on-failure',
        trace: 'retain-on-failure'
      }
    },
    
    // 🔥 PERFORMANCE TESTING PROJECT - Proyecto de testing de rendimiento
    {
      name: 'performance-testing',
      testMatch: '**/performance/**/*.spec.ts',
      use: {
        ...devices['Desktop Chrome'],
        // Configuración específica para performance
        launchOptions: {
          args: [
            '--disable-web-security',
            '--disable-features=TranslateUI',
            '--disable-ipc-flooding-protection',
            '--enable-automation'
          ]
        }
      }
    },
    
    // 🔥 ACCESSIBILITY TESTING PROJECT - Proyecto de testing de accesibilidad
    {
      name: 'accessibility-testing',
      testMatch: '**/accessibility/**/*.spec.ts',
      use: {
        ...devices['Desktop Chrome'],
        // Configuración para accesibilidad
        reducedMotion: 'reduce'
      }
    }
  ],
  
  // 🔥 WEB SERVER - Servidor web para testing
  webServer: {
    command: 'npm run start:test',
    port: 3000,
    reuseExistingServer: !process.env.CI,
    timeout: 120000
  },
  
  // 🔥 GLOBAL TEARDOWN - Cleanup global
  globalTeardown: require.resolve('./global-teardown.ts'),
  
  // 🔥 METADATA - Metadatos de configuración
  metadata: {
    'Test Environment': process.env.TEST_ENV || 'staging',
    'Browser Version': 'Latest',
    'Test Type': 'E2E',
    'Framework': 'Playwright'
  }
}

// 🔥 EXPORT CONFIGURATION - Exportar configuración
export default config

// 🔥 CUSTOM MATCHERS - Matchers personalizados
import { expect } from '@playwright/test'

// 🔥 ACCESSIBILITY MATCHERS - Matchers de accesibilidad
expect.extend({
  async toBeAccessible(page: any) {
    try {
      // Usar axe-core para verificar accesibilidad
      await page.waitForLoadState('networkidle')
      const accessibilityResults = await page.accessibility.snapshot()
      
      const violations = accessibilityResults?.violations || []
      const pass = violations.length === 0
      
      return {
        message: () => pass ? 
          'Page is accessible' : 
          `Page has ${violations.length} accessibility violations: ${violations.map((v: any) => v.description).join(', ')}`,
        pass
      }
    } catch (error) {
      return {
        message: () => `Failed to check accessibility: ${error}`,
        pass: false
      }
    }
  },
  
  async toHaveValidPerformance(page: any, thresholds: any) {
    try {
      await page.waitForLoadState('networkidle')
      const performanceMetrics = await page.evaluate(() => {
        const navigation = performance.getEntriesByType('navigation')[0] as PerformanceNavigationTiming
        
        return {
          domContentLoaded: navigation.domContentLoadedEventEnd - navigation.domContentLoadedEventStart,
          loadComplete: navigation.loadEventEnd - navigation.loadEventStart,
          firstPaint: performance.getEntriesByType('paint')[0]?.startTime || 0,
          firstContentfulPaint: performance.getEntriesByType('paint')[1]?.startTime || 0
        }
      })
      
      const violations = Object.entries(thresholds)
        .filter(([metric, threshold]) => performanceMetrics[metric] > threshold)
        .map(([metric]) => metric)
      
      const pass = violations.length === 0
      
      return {
        message: () => pass ? 
          'Performance metrics are within thresholds' : 
          `Performance violations: ${violations.join(', ')}`,
        pass
      }
    } catch (error) {
      return {
        message: () => `Failed to check performance: ${error}`,
        pass: false
      }
    }
  }
})

// 🔥 EXTEND EXPECT - Extender expect con tipos
declare global {
  namespace PlaywrightTest {
    interface Matchers<R, T> {
      toBeAccessible(): Promise<R>
      toHaveValidPerformance(thresholds: Record<string, number>): Promise<R>
    }
  }
}

// 🔥 ENVIRONMENT VARIABLES - Variables de entorno
process.env.TEST_BASE_URL = process.env.TEST_BASE_URL || 'https://conectate.com'
process.env.API_BASE_URL = process.env.API_BASE_URL || 'https://api.conectate.com'
process.env.TEST_USER_EMAIL = process.env.TEST_USER_EMAIL || 'test@conectate.com'
process.env.TEST_USER_PASSWORD = process.env.TEST_USER_PASSWORD || 'test123'
process.env.ADMIN_EMAIL = process.env.ADMIN_EMAIL || 'admin@conectate.com'
process.env.ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || 'admin123'

// 🔥 UTILITY FUNCTIONS - Funciones utilitarias
export const testData = {
  // 🔥 USER DATA - Datos de usuario
  users: {
    valid: {
      email: process.env.TEST_USER_EMAIL!,
      password: process.env.TEST_USER_PASSWORD!,
      firstName: 'Test',
      lastName: 'User'
    },
    admin: {
      email: process.env.ADMIN_EMAIL!,
      password: process.env.ADMIN_PASSWORD!,
      firstName: 'Admin',
      lastName: 'User'
    },
    invalid: {
      email: 'invalid@email.com',
      password: 'wrongpassword'
    }
  },
  
  // 🔥 POST DATA - Datos de posts
  posts: {
    valid: {
      title: 'Test Post Title',
      content: 'This is a test post content for E2E testing.',
      tags: ['test', 'e2e', 'playwright']
    },
    invalid: {
      title: '',
      content: '',
      tags: []
    }
  },
  
  // 🔥 API ENDPOINTS - Endpoints de API
  endpoints: {
    login: '/auth/login',
    logout: '/auth/logout',
    users: '/users',
    posts: '/posts',
    dashboard: '/dashboard'
  },
  
  // 🔥 SELECTORS - Selectores CSS
  selectors: {
    loginForm: '[data-testid="login-form"]',
    usernameInput: '[data-testid="username-input"]',
    passwordInput: '[data-testid="password-input"]',
    loginButton: '[data-testid="login-button"]',
    dashboardContainer: '[data-testid="dashboard-container"]',
    userMenu: '[data-testid="user-menu"]',
    logoutButton: '[data-testid="logout-button"]',
    createPostButton: '[data-testid="create-post-button"]',
    postTitleInput: '[data-testid="post-title-input"]',
    postContentInput: '[data-testid="post-content-input"]',
    submitPostButton: '[data-testid="submit-post-button"]'
  },
  
  // 🔥 TIMEOUTS - Timeouts personalizados
  timeouts: {
    short: 5000,
    medium: 10000,
    long: 30000,
    extraLong: 60000
  },
  
  // 🔥 PERFORMANCE THRESHOLDS - Umbrales de rendimiento
  performance: {
    pageLoad: 3000,
    apiResponse: 1000,
    animation: 16.67,
    interaction: 200,
    firstPaint: 1000,
    firstContentfulPaint: 1500
  }
}

// 🔥 HELPER FUNCTIONS - Funciones de ayuda
export const helpers = {
  // 🔥 LOGIN HELPER - Helper para login
  async login(page: any, userData: any = testData.users.valid) {
    await page.goto(`${process.env.TEST_BASE_URL}/login`)
    await page.fill(testData.selectors.usernameInput, userData.email)
    await page.fill(testData.selectors.passwordInput, userData.password)
    await page.click(testData.selectors.loginButton)
    await page.waitForURL(`${process.env.TEST_BASE_URL}/dashboard`)
  },
  
  // 🔥 LOGOUT HELPER - Helper para logout
  async logout(page: any) {
    await page.click(testData.selectors.userMenu)
    await page.click(testData.selectors.logoutButton)
    await page.waitForURL(`${process.env.TEST_BASE_URL}/login`)
  },
  
  // 🔥 CREATE POST HELPER - Helper para crear posts
  async createPost(page: any, postData: any = testData.posts.valid) {
    await page.click(testData.selectors.createPostButton)
    await page.fill(testData.selectors.postTitleInput, postData.title)
    await page.fill(testData.selectors.postContentInput, postData.content)
    await page.click(testData.selectors.submitPostButton)
    await page.waitForSelector('[data-testid="post-success-message"]')
  },
  
  // 🔥 WAIT FOR API HELPER - Helper para esperar API
  async waitForApiResponse(page: any, endpoint: string, method: string = 'GET') {
    return await page.waitForResponse(response => 
      response.url().includes(endpoint) && response.request().method() === method
    )
  },
  
  // 🔥 MEASURE PERFORMANCE HELPER - Helper para medir rendimiento
  async measurePerformance(page: any, action: () => Promise<void>) {
    const startTime = Date.now()
    await action()
    const endTime = Date.now()
    return endTime - startTime
  },
  
  // 🔥 TAKE SCREENSHOT HELPER - Helper para screenshots
  async takeScreenshot(page: any, name: string, fullPage: boolean = true) {
    const timestamp = new Date().toISOString().replace(/[:.]/g, '-')
    const fileName = `${name}-${timestamp}.png`
    await page.screenshot({ 
      path: `test-results/screenshots/${fileName}`,
      fullPage 
    })
    return fileName
  },
  
  // 🔥 GENERATE TEST DATA HELPER - Helper para generar datos de prueba
  generateTestData(type: string) {
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
  
  // 🔥 SETUP MOCK API HELPER - Helper para configurar API mock
  async setupMockApi(page: any, mocks: any[]) {
    await page.route('**/api/**', (route: any) => {
      const url = route.request().url()
      const mock = mocks.find(m => url.includes(m.endpoint))
      
      if (mock) {
        route.fulfill({
          status: mock.status || 200,
          contentType: 'application/json',
          body: JSON.stringify(mock.response)
        })
      } else {
        route.continue()
      }
    })
  },
  
  // 🔥 VERIFY ACCESSIBILITY HELPER - Helper para verificar accesibilidad
  async verifyAccessibility(page: any, options: any = {}) {
    await page.waitForLoadState('networkidle')
    const accessibilityResults = await page.accessibility.snapshot(options)
    
    const violations = accessibilityResults?.violations || []
    if (violations.length > 0) {
      console.log('Accessibility violations found:', violations)
    }
    
    return violations
  },
  
  // 🔥 CLEANUP TEST DATA HELPER - Helper para limpiar datos de prueba
  async cleanupTestData(page: any) {
    // Limpiar cookies, localStorage, sessionStorage
    await page.context().clearCookies()
    await page.evaluate(() => {
      localStorage.clear()
      sessionStorage.clear()
    })
  }
}

// 🔥 CUSTOM FIXTURES - Fixtures personalizados
import { test as base, type TestType } from '@playwright/test'

type TestFixtures = {
  authenticatedPage: any
  testData: any
  helpers: any
}

export const test = base.extend<TestFixtures>({
  // 🔥 AUTHENTICATED PAGE FIXTURE - Fixture para página autenticada
  authenticatedPage: async ({ page }, use) => {
    await helpers.login(page)
    await use(page)
    await helpers.logout(page)
  },
  
  // 🔥 TEST DATA FIXTURE - Fixture para datos de prueba
  testData: async ({}, use) => {
    const data = helpers.generateTestData('user')
    await use(data)
  },
  
  // 🔥 HELPERS FIXTURE - Fixture para helpers
  helpers: async ({}, use) => {
    await use(helpers)
  }
})

export { expect } from '@playwright/test'
export type ConectateTest = TestType<TestFixtures>
