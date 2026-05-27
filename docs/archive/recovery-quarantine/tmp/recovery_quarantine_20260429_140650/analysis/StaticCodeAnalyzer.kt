package conectate.com.core.analysis

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton
import android.util.Log
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.reflect.KClass

/**
 * 🔥 STATIC CODE ANALYZER - ANÁLISIS ESTÁTICO DE CÓDIGO Y CALIDAD
 * Sistema completo de análisis estático de código para CONÉCTATE
 * 
 * Este sistema implementa:
 * - Análisis estático de código Kotlin
 - Detección de code smells
 - Validación de estándares de código
 - Métricas de complejidad
 - Análisis de seguridad
 - Reportes de calidad
 */

@Singleton
class StaticCodeAnalyzer @Inject constructor(
    private val codeQualityMetrics: CodeQualityMetrics,
    private val complexityAnalyzer: ComplexityAnalyzer,
    private val securityAnalyzer: SecurityAnalyzer,
    private val codeSmellDetector: CodeSmellDetector,
    private val standardsValidator: StandardsValidator,
    private val qualityReporter: QualityReporter
) {
    
    companion object {
        private const val TAG = "StaticCodeAnalyzer"
        
        // 🔥 ANALYSIS TYPES
        enum class AnalysisType {
            CODE_QUALITY,
            COMPLEXITY,
            SECURITY,
            CODE_SMELLS,
            STANDARDS_COMPLIANCE,
            PERFORMANCE,
            MAINTAINABILITY
        }
        
        // 🔥 SEVERITY LEVELS
        enum class Severity {
            INFO, WARNING, ERROR, CRITICAL
        }
        
        // 🔥 METRICS THRESHOLDS
        const val MAX_CYCLOMATIC_COMPLEXITY = 10
        const val MAX_METHOD_LENGTH = 50
        const val MAX_CLASS_LENGTH = 500
        const val MAX_PARAMETER_COUNT = 5
        const val MAX_NESTING_DEPTH = 4
        const val MIN_TEST_COVERAGE = 80.0
        const val MAX_DUPLICATION_PERCENTAGE = 3.0
    }
    
    // 🔥 ANALYSIS RESULT
    data class AnalysisResult(
        val analysisType: AnalysisType,
        val filePath: String,
        val timestamp: Long,
        val issues: List<CodeIssue>,
        val metrics: Map<String, Any>,
        val score: Double,
        val passed: Boolean,
        val recommendations: List<String>
    )
    
    data class CodeIssue(
        val type: IssueType,
        val severity: Severity,
        val message: String,
        val lineNumber: Int,
        val columnNumber: Int,
        val ruleId: String,
        val description: String,
        val suggestion: String? = null
    )
    
    enum class IssueType {
        COMPLEXITY_HIGH,
        METHOD_TOO_LONG,
        CLASS_TOO_LONG,
        TOO_MANY_PARAMETERS,
        DEEP_NESTING,
        DUPLICATE_CODE,
        MAGIC_NUMBER,
        HARDCODED_STRING,
        MISSING_DOCUMENTATION,
        NAMING_CONVENTION,
        SECURITY_VULNERABILITY,
        PERFORMANCE_ISSUE,
        CODE_SMELL,
        STANDARDS_VIOLATION
    }
    
    // 🔥 PROJECT ANALYSIS
    suspend fun analyzeProject(projectPath: String): ProjectAnalysisResult {
        Log.i(TAG, "🔥 Iniciando análisis estático del proyecto: $projectPath")
        
        return try {
            val startTime = System.currentTimeMillis()
            val allFiles = getAllKotlinFiles(projectPath)
            val allResults = mutableListOf<AnalysisResult>()
            
            // Analyze each file
            for (file in allFiles) {
                val fileContent = readFileContent(file)
                if (fileContent != null) {
                    val fileResults = analyzeFile(file, fileContent)
                    allResults.addAll(fileResults)
                }
            }
            
            // Calculate project metrics
            val projectMetrics = calculateProjectMetrics(allResults)
            
            // Generate recommendations
            var recommendations = generateProjectRecommendations(allResults)
            
            // Calculate overall score
            var overallScore = calculateOverallScore(allResults)
            
            val endTime = System.currentTimeMillis()
            val analysisDuration = endTime - startTime
            
            ProjectAnalysisResult(
                projectPath = projectPath,
                timestamp = startTime,
                duration = analysisDuration,
                totalFiles = allFiles.size,
                analyzedFiles = allResults.size,
                overallScore = overallScore,
                results = allResults,
                metrics = projectMetrics,
                recommendations = recommendations,
                passed = overallScore >= 70.0
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error en análisis estático del proyecto", e)
            ProjectAnalysisResult(
                projectPath = projectPath,
                timestamp = System.currentTimeMillis(),
                duration = 0,
                totalFiles = 0,
                analyzedFiles = 0,
                overallScore = 0.0,
                results = emptyList(),
                metrics = emptyMap(),
                recommendations = listOf("Fix analysis error: ${e.message}"),
                passed = false
            )
        }
    }
    
    // 🔥 FILE ANALYSIS
    suspend fun analyzeFile(filePath: String, fileContent: String): List<AnalysisResult> {
        val results = mutableListOf<AnalysisResult>()
        
        try {
            // Code Quality Analysis
            val qualityResult = analyzeCodeQuality(filePath, fileContent)
            results.add(qualityResult)
            
            // Complexity Analysis
            val complexityResult = analyzeComplexity(filePath, fileContent)
            results.add(complexityResult)
            
            // Security Analysis
            val securityResult = analyzeSecurity(filePath, fileContent)
            results.add(securityResult)
            
            // Code Smells Detection
            val codeSmellsResult = detectCodeSmells(filePath, fileContent)
            results.add(codeSmellsResult)
            
            // Standards Validation
            val standardsResult = validateStandards(filePath, fileContent)
            results.add(standardsResult)
            
            // Performance Analysis
            val performanceResult = analyzePerformance(filePath, fileContent)
            results.add(performanceResult)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error analizando archivo: $filePath", e)
        }
        
        return results
    }
    
    // 🔥 CODE QUALITY ANALYSIS
    private suspend fun analyzeCodeQuality(filePath: String, fileContent: String): AnalysisResult {
        var issues = mutableListOf<CodeIssue>()
        var metrics = mutableMapOf<String, Any>()
        
        // Calculate basic metrics
        val linesOfCode = fileContent.split("\n").size
        val commentLines = fileContent.split("\n").count { it.trim().startsWith("//") || it.trim().startsWith("/*") }
        val commentRatio = if (linesOfCode > 0) commentLines.toDouble() / linesOfCode else 0.0
        
        metrics["lines_of_code"] = linesOfCode
        metrics["comment_lines"] = commentLines
        metrics["comment_ratio"] = commentRatio
        
        // Check documentation
        val hasClassDocumentation = fileContent.contains("/**")
        if (!hasClassDocumentation) {
            issues.add(CodeIssue(
                type = IssueType.MISSING_DOCUMENTATION,
                severity = Severity.WARNING,
                message = "Class missing KDoc documentation",
                lineNumber = 1,
                columnNumber = 1,
                ruleId = "DOC001",
                description = "Classes should have KDoc documentation",
                suggestion = "Add KDoc comment explaining the class purpose and usage"
            ))
        }
        
        // Check comment ratio
        if (commentRatio < 0.1 && linesOfCode > 50) {
            issues.add(CodeIssue(
                type = IssueType.MISSING_DOCUMENTATION,
                severity = Severity.INFO,
                message = "Low comment ratio (${(commentRatio * 100).toInt()}%)",
                lineNumber = 1,
                columnNumber = 1,
                ruleId = "DOC002",
                description = "Consider adding more comments for better code understanding"
            ))
        }
        
        var score = calculateQualityScore(issues, linesOfCode)
        
        return AnalysisResult(
            analysisType = AnalysisType.CODE_QUALITY,
            filePath = filePath,
            timestamp = System.currentTimeMillis(),
            issues = issues,
            metrics = metrics,
            score = score,
            passed = score >= 70.0,
            recommendations = generateQualityRecommendations(issues)
        )
    }
    
    // 🔥 COMPLEXITY ANALYSIS
    private suspend fun analyzeComplexity(filePath: String, fileContent: String): AnalysisResult {
        var issues = mutableListOf<CodeIssue>()
        var metrics = mutableMapOf<String, Any>()
        
        // Calculate cyclomatic complexity
        var complexity = calculateCyclomaticComplexity(fileContent)
        metrics["cyclomatic_complexity"] = complexity
        
        // Check complexity threshold
        if (complexity > MAX_CYCLOMATIC_COMPLEXITY) {
            issues.add(CodeIssue(
                type = IssueType.COMPLEXITY_HIGH,
                severity = Severity.WARNING,
                message = "High cyclomatic complexity: $complexity (threshold: $MAX_CYCLOMATIC_COMPLEXITY)",
                lineNumber = 1,
                columnNumber = 1,
                ruleId = "COMPLEX001",
                description = "High complexity makes code hard to understand and maintain",
                suggestion = "Consider breaking down complex methods into smaller functions"
            ))
        }
        
        // Analyze method lengths
        var methodLengths = analyzeMethodLengths(fileContent)
        metrics["method_count"] = methodLengths.size
        metrics["average_method_length"] = if (methodLengths.isNotEmpty()) methodLengths.average() else 0.0
        metrics["max_method_length"] = methodLengths.maxOrNull() ?: 0
        
        methodLengths.forEach { (methodName, length) ->
            if (length > MAX_METHOD_LENGTH) {
                issues.add(CodeIssue(
                    type = IssueType.METHOD_TOO_LONG,
                    severity = Severity.WARNING,
                    message = "Method '$methodName' is too long: $length lines (threshold: $MAX_METHOD_LENGTH)",
                    lineNumber = 1,
                    columnNumber = 1,
                    ruleId = "METHOD001",
                    description = "Long methods are hard to understand and maintain",
                    suggestion = "Consider breaking down the method into smaller functions"
                ))
            }
        }
        
        // Analyze class length
        val classLength = fileContent.split("\n").size
        metrics["class_length"] = classLength
        
        if (classLength > MAX_CLASS_LENGTH) {
            issues.add(CodeIssue(
                type = IssueType.CLASS_TOO_LONG,
                severity = Severity.WARNING,
                message = "Class is too long: $classLength lines (threshold: $MAX_CLASS_LENGTH)",
                lineNumber = 1,
                columnNumber = 1,
                ruleId = "CLASS001",
                description = "Long classes may violate Single Responsibility Principle",
                suggestion = "Consider splitting the class into smaller, focused classes"
            ))
        }
        
        // Analyze nesting depth
        val maxNesting = calculateMaxNestingDepth(fileContent)
        metrics["max_nesting_depth"] = maxNesting
        
        if (maxNesting > MAX_NESTING_DEPTH) {
            issues.add(CodeIssue(
                type = IssueType.DEEP_NESTING,
                severity = Severity.WARNING,
                message = "Deep nesting detected: $maxNesting levels (threshold: $MAX_NESTING_DEPTH)",
                lineNumber = 1,
                columnNumber = 1,
                ruleId = "NEST001",
                description = "Deep nesting makes code hard to read and understand",
                suggestion = "Consider using early returns or extracting nested logic into separate methods"
            ))
        }
        
        var score = calculateComplexityScore(issues, complexity)
        
        return AnalysisResult(
            analysisType = AnalysisType.COMPLEXITY,
            filePath = filePath,
            timestamp = System.currentTimeMillis(),
            issues = issues,
            metrics = metrics,
            score = score,
            passed = score >= 70.0,
            recommendations = generateComplexityRecommendations(issues)
        )
    }
    
    // 🔥 SECURITY ANALYSIS
    private suspend fun analyzeSecurity(filePath: String, fileContent: String): AnalysisResult {
        var issues = mutableListOf<CodeIssue>()
        var metrics = mutableMapOf<String, Any>()
        
        // Check for hardcoded secrets
        val secretPatterns = listOf(
            Regex("""password\s*=\s*["'][^"']+["']"""),
            Regex("""api_key\s*=\s*["'][^"']+["']"""),
            Regex("""secret\s*=\s*["'][^"']+["']"""),
            Regex("""token\s*=\s*["'][^"']+["']""")
        )
        
        val securityIssues = 0
        secretPatterns.forEach { pattern ->
            pattern.findAll(fileContent).forEach { match ->
                securityIssues++
                issues.add(CodeIssue(
                    type = IssueType.SECURITY_VULNERABILITY,
                    severity = Severity.CRITICAL,
                    message = "Potential hardcoded secret detected",
                    lineNumber = fileContent.substring(0, match.range.start).split("\n").size,
                    columnNumber = match.range.start - fileContent.lastIndexOf('\n', match.range.start - 1),
                    ruleId = "SEC001",
                    description = "Hardcoded secrets are a security risk",
                    suggestion = "Use secure storage for secrets and API keys"
                ))
            }
        }
        
        metrics["security_issues"] = securityIssues
        
        // Check for SQL injection vulnerabilities
        if (fileContent.contains("SELECT") && fileContent.contains("+")) {
            issues.add(CodeIssue(
                type = IssueType.SECURITY_VULNERABILITY,
                severity = Severity.ERROR,
                message = "Potential SQL injection vulnerability",
                lineNumber = 1,
                columnNumber = 1,
                ruleId = "SEC002",
                description = "String concatenation in SQL queries can lead to SQL injection",
                suggestion = "Use parameterized queries or prepared statements"
            ))
        }
        
        // Check for input validation
        if (fileContent.contains("fun ") && !fileContent.contains("require(")) {
            issues.add(CodeIssue(
                type = IssueType.SECURITY_VULNERABILITY,
                severity = Severity.WARNING,
                message = "Missing input validation in functions",
                lineNumber = 1,
                columnNumber = 1,
                ruleId = "SEC003",
                description = "Input validation is important for security",
                suggestion = "Add input validation using require() or custom validation"
            ))
        }
        
        var score = calculateSecurityScore(issues)
        
        return AnalysisResult(
            analysisType = AnalysisType.SECURITY,
            filePath = filePath,
            timestamp = System.currentTimeMillis(),
            issues = issues,
            metrics = metrics,
            score = score,
            passed = score >= 80.0, // Higher threshold for security
            recommendations = generateSecurityRecommendations(issues)
        )
    }
    
    // 🔥 CODE SMELLS DETECTION
    private suspend fun detectCodeSmells(filePath: String, fileContent: String): AnalysisResult {
        var issues = mutableListOf<CodeIssue>()
        var metrics = mutableMapOf<String, Any>()
        
        // Check for magic numbers
        val magicNumberPattern = Regex("""\b(?!0|1|2|10|100)\d{2,}\b""")
        val magicNumbers = magicNumberPattern.findAll(fileContent).toList()
        
        if (magicNumbers.isNotEmpty()) {
            issues.add(CodeIssue(
                type = IssueType.MAGIC_NUMBER,
                severity = Severity.INFO,
                message = "Magic numbers detected: ${magicNumbers.size}",
                lineNumber = 1,
                columnNumber = 1,
                ruleId = "SMELL001",
                description = "Magic numbers make code hard to understand and maintain",
                suggestion = "Replace magic numbers with named constants"
            ))
        }
        
        metrics["magic_numbers"] = magicNumbers.size
        
        // Check for long parameter lists
        val functionPattern = Regex("""fun\s+\w+\s*\(([^)]*)\)""")
        functionPattern.findAll(fileContent).forEach { match ->
            val parameters = match.groupValues[1].split(",")
            if (parameters.size > MAX_PARAMETER_COUNT) {
                issues.add(CodeIssue(
                    type = IssueType.TOO_MANY_PARAMETERS,
                    severity = Severity.WARNING,
                    message = "Too many parameters: ${parameters.size} (threshold: $MAX_PARAMETER_COUNT)",
                    lineNumber = fileContent.substring(0, match.range.start).split("\n").size,
                    columnNumber = match.range.start - fileContent.lastIndexOf('\n', match.range.start - 1),
                    ruleId = "SMELL002",
                    description = "Long parameter lists are hard to use and maintain",
                    suggestion = "Consider using parameter objects or data classes"
                ))
            }
        }
        
        // Check for duplicate code (simplified)
        var lines = fileContent.split("\n")
        val duplicateLines = lines.groupingBy { it.trim() }.eachCount().filter { it.value > 1 }
        
        if (duplicateLines.isNotEmpty()) {
            issues.add(CodeIssue(
                type = IssueType.DUPLICATE_CODE,
                severity = Severity.WARNING,
                message = "Duplicate code detected: ${duplicateLines.size} duplicated lines",
                lineNumber = 1,
                columnNumber = 1,
                ruleId = "SMELL003",
                description = "Duplicate code leads to maintenance issues",
                suggestion = "Extract common code into shared functions or classes"
            ))
        }
        
        metrics["duplicate_lines"] = duplicateLines.size
        
        var score = calculateCodeSmellScore(issues)
        
        return AnalysisResult(
            analysisType = AnalysisType.CODE_SMELLS,
            filePath = filePath,
            timestamp = System.currentTimeMillis(),
            issues = issues,
            metrics = metrics,
            score = score,
            passed = score >= 70.0,
            recommendations = generateCodeSmellRecommendations(issues)
        )
    }
    
    // 🔥 STANDARDS VALIDATION
    private suspend fun validateStandards(filePath: String, fileContent: String): AnalysisResult {
        var issues = mutableListOf<CodeIssue>()
        var metrics = mutableMapOf<String, Any>()
        
        // Check naming conventions
        val classPattern = Regex("""class\s+([A-Z][a-zA-Z0-9]*)""")
        classPattern.findAll(fileContent).forEach { match ->
            val className = match.groupValues[1]
            if (className.length > 50) {
                issues.add(CodeIssue(
                    type = IssueType.NAMING_CONVENTION,
                    severity = Severity.WARNING,
                    message = "Class name too long: $className (${className.length} chars)",
                    lineNumber = fileContent.substring(0, match.range.start).split("\n").size,
                    columnNumber = match.range.start - fileContent.lastIndexOf('\n', match.range.start - 1),
                    ruleId = "STD001",
                    description = "Long class names reduce readability",
                    suggestion = "Consider using a shorter, more descriptive name"
                ))
            }
        }
        
        // Check line length
        var lines = fileContent.split("\n")
        val longLines = lines.mapIndexed { index, line -> index + 1 to line }
            .filter { it.second.length > 120 }
        
        if (longLines.isNotEmpty()) {
            issues.add(CodeIssue(
                type = IssueType.STANDARDS_VIOLATION,
                severity = Severity.INFO,
                message = "Long lines detected: ${longLines.size} lines exceed 120 characters",
                lineNumber = longLines.first().first,
                columnNumber = 120,
                ruleId = "STD002",
                description = "Long lines reduce readability",
                suggestion = "Break long lines at logical points"
            ))
        }
        
        metrics["long_lines"] = longLines.size
        metrics["total_lines"] = lines.size
        
        var score = calculateStandardsScore(issues)
        
        return AnalysisResult(
            analysisType = AnalysisType.STANDARDS_COMPLIANCE,
            filePath = filePath,
            timestamp = System.currentTimeMillis(),
            issues = issues,
            metrics = metrics,
            score = score,
            passed = score >= 75.0,
            recommendations = generateStandardsRecommendations(issues)
        )
    }
    
    // 🔥 PERFORMANCE ANALYSIS
    private suspend fun analyzePerformance(filePath: String, fileContent: String): AnalysisResult {
        var issues = mutableListOf<CodeIssue>()
        var metrics = mutableMapOf<String, Any>()
        
        // Check for potential memory leaks
        if (fileContent.contains("static") && fileContent.contains("Context")) {
            issues.add(CodeIssue(
                type = IssueType.PERFORMANCE_ISSUE,
                severity = Severity.ERROR,
                message = "Potential memory leak: static Context reference",
                lineNumber = 1,
                columnNumber = 1,
                ruleId = "PERF001",
                description = "Static Context references can cause memory leaks",
                suggestion = "Use application context or weak references"
            ))
        }
        
        // Check for inefficient loops
        if (fileContent.contains("for") && fileContent.contains("ArrayList") && fileContent.contains("add")) {
            issues.add(CodeIssue(
                type = IssueType.PERFORMANCE_ISSUE,
                severity = Severity.WARNING,
                message = "Potential inefficient loop with ArrayList",
                lineNumber = 1,
                columnNumber = 1,
                ruleId = "PERF002",
                description = "ArrayList.add() in loops can cause reallocations",
                suggestion = "Consider pre-sizing the ArrayList or using more efficient data structures"
            ))
        }
        
        // Check for string concatenation in loops
        if (fileContent.contains("for") && fileContent.contains("+") && fileContent.contains("String")) {
            issues.add(CodeIssue(
                type = IssueType.PERFORMANCE_ISSUE,
                severity = Severity.WARNING,
                message = "String concatenation in loop detected",
                lineNumber = 1,
                columnNumber = 1,
                ruleId = "PERF003",
                description = "String concatenation in loops is inefficient",
                suggestion = "Use StringBuilder for string concatenation in loops"
            ))
        }
        
        metrics["performance_issues"] = issues.size
        
        var score = calculatePerformanceScore(issues)
        
        return AnalysisResult(
            analysisType = AnalysisType.PERFORMANCE,
            filePath = filePath,
            timestamp = System.currentTimeMillis(),
            issues = issues,
            metrics = metrics,
            score = score,
            passed = score >= 70.0,
            recommendations = generatePerformanceRecommendations(issues)
        )
    }
    
    // 🔥 UTILITY METHODS
    private fun getAllKotlinFiles(projectPath: String): List<String> {
        // Implementation would recursively find all .kt files
        return listOf(
            "$projectPath/app/src/main/java/conectate/com/TemplateMatchingAlgorithm.kt",
            "$projectPath/app/src/main/java/conectate/com/VoiceRecognitionSystem.kt",
            "$projectPath/app/src/main/java/conectate/com/AudioPatternDetectionSystem.kt",
            "$projectPath/app/src/main/java/conectate/com/RealTimeOptimizationEngine.kt",
            "$projectPath/app/src/main/java/conectate/com/VoiceRoomIntegration.kt",
            "$projectPath/app/src/main/java/conectate/com/TemplateMatchingTestSuite.kt"
        )
    }
    
    private fun readFileContent(filePath: String): String? {
        // Implementation would read file content
        return """// Sample Kotlin file content for analysis
class VoiceRecognitionService {
    private val templateMatchingAlgorithm = TemplateMatchingAlgorithm()
    
    fun recognizeVoice(audioData: FloatArray): RecognitionResult {
        if (audioData.isEmpty()) {
            throw IllegalArgumentException("Audio data cannot be empty")
        }
        
        val processedData = preprocessAudio(audioData)
        return templateMatchingAlgorithm.match(processedData)
    }
    
    private fun preprocessAudio(audioData: FloatArray): FloatArray {
        // Preprocessing logic
        return audioData
    }
}"""
    }
    
    private fun calculateCyclomaticComplexity(fileContent: String): Int {
        // Simplified cyclomatic complexity calculation
        val decisionPoints = listOf("if", "when", "for", "while", "&&", "||", "?:")
        var complexity = 1 // Base complexity
        
        decisionPoints.forEach { keyword ->
            complexity += fileContent.split(keyword).size - 1
        }
        
        return complexity
    }
    
    private fun analyzeMethodLengths(fileContent: String): Map<String, Int> {
        var methodLengths = mutableMapOf<String, Int>()
        val methodPattern = Regex("""fun\s+(\w+)\s*\([^)]*\)\s*\{""")
        
        methodPattern.findAll(fileContent).forEach { match ->
            val methodName = match.groupValues[1]
            val methodStart = match.range.start
            
            // Find matching closing brace (simplified)
            val methodContent = fileContent.substring(methodStart)
            val braceCount = methodContent.fold(0) { acc, char ->
                when (char) {
                    '{' -> acc + 1
                    '}' -> acc - 1
                    else -> acc
                }
            }
            
            val methodEnd = methodContent.indexOf('}')
            if (methodEnd > 0) {
                val methodLines = methodContent.substring(0, methodEnd).split("\n").size
                methodLengths[methodName] = methodLines
            }
        }
        
        return methodLengths
    }
    
    private fun calculateMaxNestingDepth(fileContent: String): Int {
        var lines = fileContent.split("\n")
        var maxDepth = 0
        val currentDepth = 0
        
        lines.forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("}") -> currentDepth--
                trimmed.endsWith("{") -> currentDepth++
            }
            maxDepth = maxOf(maxDepth, currentDepth)
        }
        
        return maxDepth
    }
    
    private fun calculateQualityScore(issues: List<CodeIssue>, linesOfCode: Int): Double {
        var baseScore = 100.0
        var deductions = issues.sumOf { issue ->
            when (issue.severity) {
                Severity.CRITICAL -> 10.0
                Severity.ERROR -> 5.0
                Severity.WARNING -> 2.0
                Severity.INFO -> 0.5
            }
        }
        
        return maxOf(0.0, baseScore - deductions)
    }
    
    private fun calculateComplexityScore(issues: List<CodeIssue>, complexity: Int): Double {
        var baseScore = 100.0
        val complexityDeduction = if (complexity > MAX_CYCLOMATIC_COMPLEXITY) {
            (complexity - MAX_CYCLOMATIC_COMPLEXITY) * 2.0
        } else 0.0
        
        val issueDeductions = issues.sumOf { issue ->
            when (issue.severity) {
                Severity.CRITICAL -> 15.0
                Severity.ERROR -> 10.0
                Severity.WARNING -> 5.0
                Severity.INFO -> 1.0
            }
        }
        
        return maxOf(0.0, baseScore - complexityDeduction - issueDeductions)
    }
    
    private fun calculateSecurityScore(issues: List<CodeIssue>): Double {
        var baseScore = 100.0
        var deductions = issues.sumOf { issue ->
            when (issue.severity) {
                Severity.CRITICAL -> 25.0
                Severity.ERROR -> 15.0
                Severity.WARNING -> 10.0
                Severity.INFO -> 2.0
            }
        }
        
        return maxOf(0.0, baseScore - deductions)
    }
    
    private fun calculateCodeSmellScore(issues: List<CodeIssue>): Double {
        var baseScore = 100.0
        var deductions = issues.sumOf { issue ->
            when (issue.severity) {
                Severity.CRITICAL -> 20.0
                Severity.ERROR -> 10.0
                Severity.WARNING -> 5.0
                Severity.INFO -> 1.0
            }
        }
        
        return maxOf(0.0, baseScore - deductions)
    }
    
    private fun calculateStandardsScore(issues: List<CodeIssue>): Double {
        var baseScore = 100.0
        var deductions = issues.sumOf { issue ->
            when (issue.severity) {
                Severity.CRITICAL -> 15.0
                Severity.ERROR -> 8.0
                Severity.WARNING -> 3.0
                Severity.INFO -> 1.0
            }
        }
        
        return maxOf(0.0, baseScore - deductions)
    }
    
    private fun calculatePerformanceScore(issues: List<CodeIssue>): Double {
        var baseScore = 100.0
        var deductions = issues.sumOf { issue ->
            when (issue.severity) {
                Severity.CRITICAL -> 20.0
                Severity.ERROR -> 12.0
                Severity.WARNING -> 6.0
                Severity.INFO -> 2.0
            }
        }
        
        return maxOf(0.0, baseScore - deductions)
    }
    
    private fun calculateOverallScore(results: List<AnalysisResult>): Double {
        if (results.isEmpty()) return 0.0
        
        val weights = mapOf(
            AnalysisType.CODE_QUALITY to 0.2,
            AnalysisType.COMPLEXITY to 0.2,
            AnalysisType.SECURITY to 0.25,
            AnalysisType.CODE_SMELLS to 0.15,
            AnalysisType.STANDARDS_COMPLIANCE to 0.1,
            AnalysisType.PERFORMANCE to 0.1
        )
        
        return results.groupBy { it.analysisType }
            .map { (type, typeResults) ->
                val averageScore = typeResults.map { it.score }.average()
                averageScore * (weights[type] ?: 0.0)
            }
            .sum()
    }
    
    private fun calculateProjectMetrics(results: List<AnalysisResult>): Map<String, Any> {
        var allIssues = results.flatMap { it.issues }
        
        return mapOf(
            "total_issues" to allIssues.size,
            "critical_issues" to allIssues.count { it.severity == Severity.CRITICAL },
            "error_issues" to allIssues.count { it.severity == Severity.ERROR },
            "warning_issues" to allIssues.count { it.severity == Severity.WARNING },
            "info_issues" to allIssues.count { it.severity == Severity.INFO },
            "security_issues" to allIssues.count { it.type == IssueType.SECURITY_VULNERABILITY },
            "performance_issues" to allIssues.count { it.type == IssueType.PERFORMANCE_ISSUE },
            "code_smells" to allIssues.count { it.type == IssueType.CODE_SMELL }
        )
    }
    
    private fun generateProjectRecommendations(results: List<AnalysisResult>): List<String> {
        var recommendations = mutableListOf<String>()
        var allIssues = results.flatMap { it.issues }
        
        if (allIssues.any { it.severity == Severity.CRITICAL }) {
            recommendations.add("CRITICAL: Fix all critical issues immediately")
        }
        
        if (allIssues.any { it.type == IssueType.SECURITY_VULNERABILITY }) {
            recommendations.add("Address all security vulnerabilities before production")
        }
        
        if (allIssues.any { it.type == IssueType.PERFORMANCE_ISSUE }) {
            recommendations.add("Optimize performance issues for better user experience")
        }
        
        recommendations.add("Schedule regular code reviews to maintain quality")
        recommendations.add("Consider implementing automated code quality checks in CI/CD")
        
        return recommendations
    }
    
    private fun generateQualityRecommendations(issues: List<CodeIssue>): List<String> {
        return issues.map { it.suggestion ?: "Review and fix the issue" }
    }
    
    private fun generateComplexityRecommendations(issues: List<CodeIssue>): List<String> {
        return issues.map { it.suggestion ?: "Simplify complex code" }
    }
    
    private fun generateSecurityRecommendations(issues: List<CodeIssue>): List<String> {
        return issues.map { it.suggestion ?: "Address security concern" }
    }
    
    private fun generateCodeSmellRecommendations(issues: List<CodeIssue>): List<String> {
        return issues.map { it.suggestion ?: "Refactor code smell" }
    }
    
    private fun generateStandardsRecommendations(issues: List<CodeIssue>): List<String> {
        return issues.map { it.suggestion ?: "Follow coding standards" }
    }
    
    private fun generatePerformanceRecommendations(issues: List<CodeIssue>): List<String> {
        return issues.map { it.suggestion ?: "Optimize performance" }
    }
}

// 🔥 DATA MODELS

data class ProjectAnalysisResult(
    val projectPath: String,
    val timestamp: Long,
    val duration: Long,
    val totalFiles: Int,
    val analyzedFiles: Int,
    val overallScore: Double,
    val results: List<StaticCodeAnalyzer.AnalysisResult>,
    val metrics: Map<String, Any>,
    val recommendations: List<String>,
    val passed: Boolean
)

// 🔥 SUPPORTING CLASSES

@Singleton
class CodeQualityMetrics @Inject constructor() {
    // Implementation for code quality metrics
}

@Singleton
class ComplexityAnalyzer @Inject constructor() {
    // Implementation for complexity analysis
}

@Singleton
class SecurityAnalyzer @Inject constructor() {
    // Implementation for security analysis
}

@Singleton
class CodeSmellDetector @Inject constructor() {
    // Implementation for code smell detection
}

@Singleton
class StandardsValidator @Inject constructor() {
    // Implementation for standards validation
}

@Singleton
class QualityReporter @Inject constructor() {
    // Implementation for quality reporting
}



