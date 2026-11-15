package com.github.noahshen.alcyone.context.memory.integration.evaluation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.readText

internal class EvaluationDatasetException(
    message: String,
) : IllegalArgumentException(message)

internal fun fail(message: String): Nothing = throw EvaluationDatasetException(message)

internal data class EvaluationSample(
    val id: String,
    val task: String,
    val route: String,
    val provenanceKind: String,
    val input: JsonObject,
    val oracle: JsonObject,
    val source: JsonObject,
)

internal data class EvaluationDataset(
    val id: String,
    val task: String,
    val path: String,
    val sha256: String,
    val importedCount: Int,
    val newCount: Int,
    val route: String,
    val samples: List<EvaluationSample>,
)

internal data class EvaluationStageRoute(
    val stage: String,
    val task: String,
    val profile: String,
    val model: String,
    val promptPath: String,
    val promptSha256: String,
    val schemaPath: String,
    val schemaSha256: String,
    val jsonMode: String,
    val maxInputTokens: Int,
    val maxOutputTokens: Int,
)

internal data class EvaluationRoute(
    val id: String,
    val task: String,
    val stages: List<EvaluationStageRoute>,
)

internal data class EvaluationManifest(
    val raw: JsonObject,
    val datasetVersion: String,
    val routes: Map<String, EvaluationRoute>,
    val datasets: List<EvaluationDatasetDescriptor>,
    val offlineReportPath: String,
    val realModelResultsPath: String,
)

internal data class EvaluationDatasetDescriptor(
    val id: String,
    val task: String,
    val path: String,
    val count: Int,
    val importedCount: Int,
    val newCount: Int,
    val sha256: String,
    val route: String,
)

internal data class EvaluationPackage(
    val root: Path,
    val manifest: EvaluationManifest,
    val datasets: List<EvaluationDataset>,
) {
    val samples: List<EvaluationSample> = datasets.flatMap { it.samples }
}

/** Exact loader contract; tests compare these sets with evals/schema/dataset-v1.schema.json. */
internal object EvaluationDatasetContract {
    val rootRequired =
        setOf(
            "id",
            "datasetVersion",
            "task",
            "split",
            "route",
            "synthetic",
            "containsPersonalData",
            "provenance",
            "input",
            "oracle",
            "review",
        )
    val provenanceRequired = setOf("kind", "sourcePath", "sourceSha256", "sourceSampleId", "transformNote")
    val reviewRequired = setOf("status", "notes")
    val riskTags =
        setOf(
            "NORMAL_PATH",
            "H1_FABRICATION",
            "H2_CRITICAL_CONFLICT_MISS",
            "H3_CORRECTION_REGRESSION",
            "EMPTY_RESULT",
            "DUPLICATE_REGRESSION",
            "PROTOCOL_BOUNDARY",
            "INJECTION_BOUNDARY",
            "BUDGET_BOUNDARY",
            "PRIVACY_BOUNDARY",
            "UNCERTAINTY",
            "TEMPORAL_SCOPE",
            "SOURCE_FIDELITY",
        )

    data class Shape(
        val required: Set<String>,
        val allowed: Set<String>,
    )

    val inputs =
        mapOf(
            "EXTRACTION" to Shape(setOf("turns"), setOf("turns")),
            "CANDIDATE_SCANNER" to Shape(setOf("raw", "candidates"), setOf("raw", "candidates")),
            "GOVERNANCE" to Shape(setOf("raw", "candidates"), setOf("raw", "candidates")),
            "MEMORY_SEARCH" to Shape(setOf("query", "domains", "candidates"), setOf("query", "domains", "candidates")),
            "MEMORY_MAP" to Shape(setOf("organizedMemories"), setOf("organizedMemories")),
            "DREAMING" to
                Shape(
                    setOf("turnIds", "turns", "organizedMemories", "rawMemories"),
                    setOf("turnIds", "turns", "organizedMemories", "rawMemories"),
                ),
        )

    val oracles =
        mapOf(
            "EXTRACTION" to
                Shape(
                    setOf(
                        "criticality",
                        "riskTags",
                        "requiredFacts",
                        "forbiddenFacts",
                        "sourceTurnIds",
                        "timeAnchors",
                        "expectedEmpty",
                    ),
                    setOf(
                        "criticality",
                        "riskTags",
                        "requiredFacts",
                        "forbiddenFacts",
                        "sourceTurnIds",
                        "timeAnchors",
                        "expectedEmpty",
                    ),
                ),
            "CANDIDATE_SCANNER" to
                Shape(
                    setOf("criticality", "riskTags", "relevantIds", "criticalIds", "forbiddenIds", "expectedEmpty"),
                    setOf("criticality", "riskTags", "relevantIds", "criticalIds", "forbiddenIds", "expectedEmpty"),
                ),
            "GOVERNANCE" to
                Shape(
                    setOf(
                        "criticality",
                        "riskTags",
                        "expectedAction",
                        "allowedTargets",
                        "forbiddenTargets",
                        "requiredContentPoints",
                        "forbiddenRegressions",
                        "uncertaintyRequired",
                        "expectedEmpty",
                    ),
                    setOf(
                        "criticality",
                        "riskTags",
                        "expectedAction",
                        "allowedTargets",
                        "forbiddenTargets",
                        "requiredContentPoints",
                        "forbiddenRegressions",
                        "uncertaintyRequired",
                        "expectedEmpty",
                    ),
                ),
            "MEMORY_SEARCH" to
                Shape(
                    setOf(
                        "criticality",
                        "riskTags",
                        "qrels",
                        "criticalIds",
                        "k",
                        "domains",
                        "expectedEmpty",
                        "scanner",
                        "rerank",
                        "resultMode",
                        "domainFilterMode",
                        "inputTrustBoundary",
                        "emptyCandidatesBehavior",
                        "uncertaintyRequired",
                        "sourceSemanticConstraints",
                        "sourceExpectedBehavior",
                        "sourceForbiddenErrors",
                    ),
                    setOf(
                        "criticality",
                        "riskTags",
                        "qrels",
                        "criticalIds",
                        "k",
                        "domains",
                        "expectedEmpty",
                        "scanner",
                        "rerank",
                        "resultMode",
                        "domainFilterMode",
                        "inputTrustBoundary",
                        "emptyCandidatesBehavior",
                        "uncertaintyRequired",
                        "sourceSemanticConstraints",
                        "sourceExpectedBehavior",
                        "sourceForbiddenErrors",
                    ),
                ),
            "MEMORY_MAP" to
                Shape(
                    setOf(
                        "criticality",
                        "riskTags",
                        "requiredFacts",
                        "forbiddenFacts",
                        "requiredDomains",
                        "requiredMemoryIds",
                        "navigationChecks",
                        "uncertaintyRequirements",
                        "expectedEmpty",
                        "inputTrustBoundary",
                        "emptyMapBehavior",
                        "sourceSemanticConstraints",
                        "sourceExpectedBehavior",
                        "sourceForbiddenErrors",
                    ),
                    setOf(
                        "criticality",
                        "riskTags",
                        "requiredFacts",
                        "forbiddenFacts",
                        "requiredDomains",
                        "requiredMemoryIds",
                        "navigationChecks",
                        "uncertaintyRequirements",
                        "expectedEmpty",
                        "inputTrustBoundary",
                        "emptyMapBehavior",
                        "sourceSemanticConstraints",
                        "sourceExpectedBehavior",
                        "sourceForbiddenErrors",
                    ),
                ),
            "DREAMING" to
                Shape(
                    setOf(
                        "criticality",
                        "riskTags",
                        "expectedInsights",
                        "forbiddenInsightPatterns",
                        "requiredEvidenceTurnIds",
                        "duplicateOf",
                        "correctionGuard",
                        "expectedEmpty",
                        "inputTrustBoundary",
                        "sourceScenarioExpected",
                        "sourceScenarioStageOutputs",
                    ),
                    setOf(
                        "criticality",
                        "riskTags",
                        "expectedInsights",
                        "forbiddenInsightPatterns",
                        "requiredEvidenceTurnIds",
                        "duplicateOf",
                        "correctionGuard",
                        "expectedEmpty",
                        "inputTrustBoundary",
                        "sourceScenarioExpected",
                        "sourceScenarioStageOutputs",
                    ),
                ),
        )
}

internal object EvalDatasetLoader {
    private val json = Json { ignoreUnknownKeys = false }
    private val sha256Pattern = Regex("^[0-9a-f]{64}$")
    private val routeTask =
        mapOf(
            "EXTRACTION" to "extraction-v1",
            "CANDIDATE_SCANNER" to "scanner-v1",
            "GOVERNANCE" to "governance-v1",
            "MEMORY_SEARCH" to "search-v1",
            "MEMORY_MAP" to "memory-map-v1",
            "DREAMING" to "dreaming-v1",
        )

    fun load(root: Path): EvaluationPackage {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val manifestPath = normalizedRoot.resolve("evals/manifest-v1.json")
        val manifest = parseManifest(normalizedRoot, manifestPath)
        val datasets = manifest.datasets.map { descriptor -> loadDataset(normalizedRoot, manifest, descriptor) }
        validatePackage(datasets)
        return EvaluationPackage(normalizedRoot, manifest, datasets)
    }

    fun parseSampleLines(
        root: Path,
        manifest: EvaluationManifest,
        descriptor: EvaluationDatasetDescriptor,
        lines: List<String>,
    ): List<EvaluationSample> {
        val samples =
            lines.mapIndexedNotNull { index, line ->
                if (line.isBlank()) null else parseSample(root, manifest, descriptor, line, index + 1)
            }
        if (samples.size != descriptor.count) {
            fail("${descriptor.path}: expected ${descriptor.count} samples, found ${samples.size}")
        }
        validateStableIds(samples)
        validateReferences(samples)
        val imported = samples.count { it.provenanceKind == "IMPORTED" }
        val new = samples.count { it.provenanceKind == "SYNTHESIZED" }
        if (imported != descriptor.importedCount || new != descriptor.newCount) {
            fail("${descriptor.path}: provenance counts imported=$imported new=$new do not match manifest")
        }
        return samples
    }

    private fun parseManifest(
        root: Path,
        path: Path,
    ): EvaluationManifest {
        if (!Files.isRegularFile(path)) fail("missing manifest: evals/manifest-v1.json")
        val raw = parseObject(Files.readString(path), "evals/manifest-v1.json")
        rejectUnknown(
            raw,
            setOf("datasetVersion", "schemaVersion", "routeDefinitions", "datasets", "results"),
            "manifest",
        )
        requireAll(raw, setOf("datasetVersion", "schemaVersion", "routeDefinitions", "datasets", "results"), "manifest")
        val version = raw.requiredString("datasetVersion", "manifest")
        if (version != "ct31-v1") fail("manifest.datasetVersion must be ct31-v1")
        if (raw.requiredInt("schemaVersion", "manifest") != 1) fail("manifest.schemaVersion must be 1")

        val routesRaw = raw.requiredObject("routeDefinitions", "manifest")
        if (routesRaw.size != 6) fail("manifest.routeDefinitions must contain exactly six routes")
        val routes = routesRaw.map { (id, value) -> id to parseRoute(root, id, value.requireObject("routeDefinitions.$id")) }.toMap()
        routeTask.forEach { (task, expectedRoute) ->
            val route = routes[expectedRoute] ?: fail("manifest.routeDefinitions is missing $expectedRoute")
            if (route.task != task) fail("route $expectedRoute task must be $task")
        }
        validateRouteBoundaries(root, routes)

        val datasetArray = raw.requiredArray("datasets", "manifest")
        if (datasetArray.size != 6) fail("manifest.datasets must contain exactly six datasets")
        val descriptors = datasetArray.mapIndexed { index, element -> parseDatasetDescriptor(element, index) }
        if (descriptors.map { it.id }.toSet().size != descriptors.size) fail("manifest.datasets contains duplicate IDs")
        if (descriptors.map { it.path }.toSet().size != descriptors.size) fail("manifest.datasets contains duplicate paths")
        descriptors.forEach { descriptor ->
            val route = routes[descriptor.route] ?: fail("${descriptor.id}: unknown route ${descriptor.route}")
            if (route.task != descriptor.task) fail("${descriptor.id}: dataset task does not match route task")
            if (descriptor.count != descriptor.importedCount + descriptor.newCount) {
                fail("${descriptor.id}: count must equal importedCount + newCount")
            }
            safeResolve(root, descriptor.path, descriptor.id)
        }

        val results = raw.requiredObject("results", "manifest")
        rejectUnknown(results, setOf("offlineReport", "realModelResults"), "manifest.results")
        requireAll(results, setOf("offlineReport", "realModelResults"), "manifest.results")
        val offline = results.requiredString("offlineReport", "manifest.results")
        val real = results.requiredString("realModelResults", "manifest.results")
        safeResolve(root, offline, "manifest.results.offlineReport")
        safeResolve(root, real, "manifest.results.realModelResults")
        return EvaluationManifest(raw, version, routes, descriptors, offline, real)
    }

    private fun parseRoute(
        root: Path,
        id: String,
        raw: JsonObject,
    ): EvaluationRoute {
        val path = "manifest.routeDefinitions.$id"
        rejectUnknown(raw, setOf("task", "stages"), path)
        requireAll(raw, setOf("task", "stages"), path)
        val task = raw.requiredString("task", path)
        if (task !in EvaluationDatasetContract.inputs) fail("$path.task is unknown: $task")
        val stages = raw.requiredArray("stages", path).mapIndexed { index, value -> parseStage(root, "$path.stages[$index]", value) }
        if (stages.isEmpty()) fail("$path.stages must not be empty")
        if (stages.any { it.task != task } && id != "search-v1") fail("$path contains a stage with a different task")
        if (id == "search-v1" && stages.map { it.stage } != listOf("MEMORY_SEARCH_SCANNER", "MEMORY_SEARCH_RERANK")) {
            fail("$path must freeze Scanner then Rerank")
        }
        return EvaluationRoute(id, task, stages)
    }

    private fun parseStage(
        root: Path,
        path: String,
        value: JsonElement,
    ): EvaluationStageRoute {
        val raw = value.requireObject(path)
        val keys =
            setOf(
                "stage",
                "task",
                "profile",
                "model",
                "promptPath",
                "promptSha256",
                "schemaPath",
                "schemaSha256",
                "jsonMode",
                "maxInputTokens",
                "maxOutputTokens",
            )
        rejectUnknown(raw, keys, path)
        requireAll(raw, keys, path)
        val stage =
            EvaluationStageRoute(
                stage = raw.requiredString("stage", path),
                task = raw.requiredString("task", path),
                profile = raw.requiredString("profile", path),
                model = raw.requiredString("model", path),
                promptPath = raw.requiredString("promptPath", path),
                promptSha256 = raw.requiredSha("promptSha256", "$path.promptSha256"),
                schemaPath = raw.requiredString("schemaPath", path),
                schemaSha256 = raw.requiredSha("schemaSha256", "$path.schemaSha256"),
                jsonMode = raw.requiredString("jsonMode", path),
                maxInputTokens = raw.requiredPositiveInt("maxInputTokens", path),
                maxOutputTokens = raw.requiredPositiveInt("maxOutputTokens", path),
            )
        if (stage.task !in EvaluationDatasetContract.inputs) fail("$path.task is unknown: ${stage.task}")
        if (stage.jsonMode !in setOf("JSON_SCHEMA", "JSON_OBJECT")) fail("$path.jsonMode must be structured JSON")
        requireFileHash(root, stage.promptPath, stage.promptSha256, "$path.prompt")
        requireFileHash(root, stage.schemaPath, stage.schemaSha256, "$path.schema")
        return stage
    }

    private fun validateRouteBoundaries(
        root: Path,
        routes: Map<String, EvaluationRoute>,
    ) {
        val scanner = routes.getValue("scanner-v1")
        if (scanner.stages.singleOrNull()?.task != "CANDIDATE_SCANNER") fail("scanner-v1 must route CANDIDATE_SCANNER")
        val governance = routes.getValue("governance-v1")
        if (governance.stages.singleOrNull()?.task != "GOVERNANCE") fail("governance-v1 must route GOVERNANCE")
        val dreaming = routes.getValue("dreaming-v1")
        val candidate =
            dreaming.stages.singleOrNull { it.stage == "CANDIDATE_SEARCH" }
                ?: fail("dreaming-v1 is missing CANDIDATE_SEARCH")
        if (candidate.task != "DREAMING") fail("Dreaming CANDIDATE_SEARCH must remain on the DREAMING route")
        if (candidate.profile != "fixture-memory-search") fail("Dreaming CANDIDATE_SEARCH profile drift")
        if (candidate.model != "fixture-memory-search-model") fail("Dreaming CANDIDATE_SEARCH model drift")
        requireFileHash(root, candidate.promptPath, candidate.promptSha256, "dreaming-v1.CANDIDATE_SEARCH.prompt")
        requireFileHash(root, candidate.schemaPath, candidate.schemaSha256, "dreaming-v1.CANDIDATE_SEARCH.schema")
    }

    private fun parseDatasetDescriptor(
        element: JsonElement,
        index: Int,
    ): EvaluationDatasetDescriptor {
        val path = "manifest.datasets[$index]"
        val raw = element.requireObject(path)
        val keys = setOf("id", "task", "path", "count", "importedCount", "newCount", "sha256", "route")
        rejectUnknown(raw, keys, path)
        requireAll(raw, keys, path)
        val count = raw.requiredPositiveInt("count", path)
        if (count < 12) fail("$path.count must be at least 12")
        val imported = raw.requiredNonNegativeInt("importedCount", path)
        val new = raw.requiredNonNegativeInt("newCount", path)
        return EvaluationDatasetDescriptor(
            id = raw.requiredString("id", path),
            task = raw.requiredString("task", path),
            path = raw.requiredString("path", path),
            count = count,
            importedCount = imported,
            newCount = new,
            sha256 = raw.requiredSha("sha256", "$path.sha256"),
            route = raw.requiredString("route", path),
        )
    }

    private fun loadDataset(
        root: Path,
        manifest: EvaluationManifest,
        descriptor: EvaluationDatasetDescriptor,
    ): EvaluationDataset {
        val path = safeResolve(root, descriptor.path, descriptor.id)
        if (!Files.isRegularFile(path)) fail("${descriptor.id}: missing ${descriptor.path}")
        if (sha256(path) != descriptor.sha256) fail("${descriptor.id}: dataset SHA-256 drift")
        val samples = parseSampleLines(root, manifest, descriptor, Files.readAllLines(path))
        return EvaluationDataset(
            descriptor.id,
            descriptor.task,
            descriptor.path,
            descriptor.sha256,
            descriptor.importedCount,
            descriptor.newCount,
            descriptor.route,
            samples,
        )
    }

    private fun parseSample(
        root: Path,
        manifest: EvaluationManifest,
        descriptor: EvaluationDatasetDescriptor,
        line: String,
        lineNumber: Int,
    ): EvaluationSample {
        val path = "${descriptor.path}:$lineNumber"
        val raw = parseObject(line, path)
        rejectUnknown(raw, EvaluationDatasetContract.rootRequired, path)
        requireAll(raw, EvaluationDatasetContract.rootRequired, path)
        val id = raw.requiredString("id", path)
        val task = raw.requiredString("task", path)
        val route = raw.requiredString("route", path)
        if (!Regex("^(ext|sc|gov|ret|map|dream)-[0-9]{3}$").matches(id)) fail("$path.id must be a stable three-digit ID")
        if (raw.requiredString("datasetVersion", path) != manifest.datasetVersion) fail("$path.datasetVersion drift")
        if (task != descriptor.task) fail("$path.task must be ${descriptor.task}")
        if (raw.requiredString("split", path) != "regression") fail("$path.split must be regression")
        if (route != descriptor.route) fail("$path.route must be ${descriptor.route}")
        val routeDefinition = manifest.routes[route] ?: fail("$path unknown route $route")
        if (routeDefinition.task != task) fail("$path Task/Profile route mismatch")
        if (!raw.requiredBoolean("synthetic", path)) fail("$path.synthetic must be true")
        if (raw.requiredBoolean("containsPersonalData", path)) fail("$path.containsPersonalData must be false")

        validateProvenance(root, raw.requiredObject("provenance", path), path)
        val review = raw.requiredObject("review", path)
        rejectUnknown(review, EvaluationDatasetContract.reviewRequired, "$path.review")
        requireAll(review, EvaluationDatasetContract.reviewRequired, "$path.review")
        if (review.requiredString("status", "$path.review") != "APPROVED_FOR_OFFLINE") {
            fail("$path.review.status must be APPROVED_FOR_OFFLINE")
        }
        review.requiredString("notes", "$path.review")

        val input = raw.requiredObject("input", path)
        val inputShape = EvaluationDatasetContract.inputs.getValue(task)
        rejectUnknown(input, inputShape.allowed, "$path.input")
        requireAll(input, inputShape.required, "$path.input")
        val oracle = raw.requiredObject("oracle", path)
        val oracleShape = EvaluationDatasetContract.oracles.getValue(task)
        rejectUnknown(oracle, oracleShape.allowed, "$path.oracle")
        requireAll(oracle, oracleShape.required, "$path.oracle")
        validateOracleCommon(oracle, "$path.oracle")
        validateTaskShape(task, input, oracle, path)
        return EvaluationSample(
            id,
            task,
            route,
            raw.requiredObject("provenance", path).requiredString("kind", "$path.provenance"),
            input,
            oracle,
            raw,
        )
    }

    private fun validateProvenance(
        root: Path,
        provenance: JsonObject,
        path: String,
    ) {
        rejectUnknown(provenance, EvaluationDatasetContract.provenanceRequired, "$path.provenance")
        requireAll(provenance, EvaluationDatasetContract.provenanceRequired, "$path.provenance")
        val kind = provenance.requiredString("kind", "$path.provenance")
        if (kind !in setOf("IMPORTED", "SYNTHESIZED")) fail("$path.provenance.kind is invalid")
        val sourcePath = provenance.nullableString("sourcePath", "$path.provenance")
        val sourceSha = provenance.nullableString("sourceSha256", "$path.provenance")
        val sourceSampleId = provenance.nullableString("sourceSampleId", "$path.provenance")
        provenance.requiredString("transformNote", "$path.provenance")
        if (kind == "IMPORTED") {
            if (sourcePath == null || sourceSha == null || sourceSampleId == null) fail("$path.provenance imported values are required")
            if (!sha256Pattern.matches(sourceSha)) fail("$path.provenance.sourceSha256 is invalid")
            requireFileHash(root, sourcePath, sourceSha, "$path.provenance.source")
        } else if (sourcePath != null || sourceSha != null || sourceSampleId != null) {
            fail("$path.provenance synthesized values must be null")
        }
    }

    private fun validateOracleCommon(
        oracle: JsonObject,
        path: String,
    ) {
        if (oracle.requiredString("criticality", path) !in setOf("NORMAL", "IMPORTANT", "CRITICAL")) {
            fail("$path.criticality is invalid")
        }
        val risks = oracle.requiredStrings("riskTags", path)
        if (risks.isEmpty()) fail("$path.riskTags must not be empty")
        if (risks.toSet().size != risks.size) fail("$path.riskTags contains duplicates")
        risks.filterNot { it in EvaluationDatasetContract.riskTags }.forEach { fail("$path.riskTags contains unknown tag $it") }
        oracle.requiredBoolean("expectedEmpty", path)
    }

    private fun validateTaskShape(
        task: String,
        input: JsonObject,
        oracle: JsonObject,
        path: String,
    ) {
        when (task) {
            "EXTRACTION" -> validateExtraction(input, oracle, path)
            "CANDIDATE_SCANNER" -> validateScanner(input, oracle, path)
            "GOVERNANCE" -> validateGovernance(input, oracle, path)
            "MEMORY_SEARCH" -> validateSearch(input, oracle, path)
            "MEMORY_MAP" -> validateMap(input, oracle, path)
            "DREAMING" -> validateDreaming(input, oracle, path)
            else -> fail("$path unsupported task $task")
        }
    }

    private fun validateExtraction(
        input: JsonObject,
        oracle: JsonObject,
        path: String,
    ) {
        val turns =
            input.requiredArray("turns", "$path.input").mapIndexed {
                index,
                value,
                ->
                value.requireObject("$path.input.turns[$index]")
            }
        if (turns.isEmpty()) fail("$path.input.turns must not be empty")
        val turnIds = turns.map { it.requiredPositiveInt("id", "$path.input.turns") }
        requireUnique(turnIds, "$path.input.turns.id")
        turns.forEachIndexed { index, turn ->
            turn.requiredString("timestamp", "$path.input.turns[$index]")
            turn.requiredString("role", "$path.input.turns[$index]")
            turn.requiredString("content", "$path.input.turns[$index]")
            turn.requiredBoolean("target", "$path.input.turns[$index]")
            rejectUnknown(turn, setOf("id", "timestamp", "role", "content", "target"), "$path.input.turns[$index]")
        }
        val targetIds =
            turns
                .filter {
                    it.requiredBoolean(
                        "target",
                        "$path.input.turns",
                    )
                }.map { it.requiredPositiveInt("id", path) }
                .toSet()
        val sources = oracle.requiredInts("sourceTurnIds", "$path.oracle")
        if (sources.toSet().size != sources.size) fail("$path.oracle.sourceTurnIds contains duplicates")
        if (!targetIds.containsAll(sources)) fail("$path.oracle.sourceTurnIds includes a non-target turn")
        val empty = oracle.requiredBoolean("expectedEmpty", path)
        if (!empty && sources.isEmpty()) fail("$path.oracle.sourceTurnIds must contain a target turn")
        if (empty && sources.isNotEmpty()) fail("$path oracle cannot be empty with source turns")
        oracle.requiredStrings("requiredFacts", "$path.oracle")
        oracle.requiredStrings("forbiddenFacts", "$path.oracle")
        oracle.requiredStrings("timeAnchors", "$path.oracle")
    }

    private fun validateScanner(
        input: JsonObject,
        oracle: JsonObject,
        path: String,
    ) {
        validateRaw(input.requiredObject("raw", "$path.input"), "$path.input.raw")
        val candidateIds = validateMemories(input.requiredArray("candidates", "$path.input"), "$path.input.candidates")
        val relevant = oracle.requiredStrings("relevantIds", "$path.oracle").also { requireUnique(it, "$path.oracle.relevantIds") }
        val critical = oracle.requiredStrings("criticalIds", "$path.oracle").also { requireUnique(it, "$path.oracle.criticalIds") }
        val forbidden = oracle.requiredStrings("forbiddenIds", "$path.oracle").also { requireUnique(it, "$path.oracle.forbiddenIds") }
        if (!candidateIds.containsAll(relevant + critical + forbidden)) fail("$path scanner oracle references outside candidates")
        if (!relevant.containsAll(critical)) fail("$path scanner criticalIds must be relevant")
        if (relevant.any { it in forbidden }) fail("$path scanner relevant and forbidden IDs overlap")
        if (oracle.requiredBoolean("expectedEmpty", path) != relevant.isEmpty()) fail("$path scanner expectedEmpty mismatch")
    }

    private fun validateGovernance(
        input: JsonObject,
        oracle: JsonObject,
        path: String,
    ) {
        validateRaw(input.requiredObject("raw", "$path.input"), "$path.input.raw")
        val candidateIds = validateMemories(input.requiredArray("candidates", "$path.input"), "$path.input.candidates")
        val action = oracle.requiredString("expectedAction", "$path.oracle")
        if (action !in setOf("NEW", "MERGE", "UPDATE")) fail("$path.oracle.expectedAction is invalid")
        val allowed = oracle.requiredStrings("allowedTargets", "$path.oracle").also { requireUnique(it, "$path.oracle.allowedTargets") }
        val forbidden =
            oracle
                .requiredStrings(
                    "forbiddenTargets",
                    "$path.oracle",
                ).also { requireUnique(it, "$path.oracle.forbiddenTargets") }
        if (!candidateIds.containsAll(allowed + forbidden)) fail("$path governance oracle references outside candidates")
        if (allowed.any { it in forbidden }) fail("$path governance allowed and forbidden targets overlap")
        if (action == "NEW" && allowed.isNotEmpty()) fail("$path NEW must not name a target")
        if (action in setOf("MERGE", "UPDATE") && allowed.isEmpty()) fail("$path $action must name a target")
        if (action in setOf("MERGE", "UPDATE") &&
            allowed.none { it in candidateIds }
        ) {
            fail("$path $action target is not an input candidate")
        }
        val contentPoints = oracle.requiredStrings("requiredContentPoints", "$path.oracle")
        oracle.requiredStrings("forbiddenRegressions", "$path.oracle")
        oracle.requiredBoolean("uncertaintyRequired", "$path.oracle")
        if (action in setOf("NEW", "UPDATE") && contentPoints.isEmpty()) fail("$path $action must require current content")
        if (oracle.requiredBoolean("expectedEmpty", path)) fail("$path governance decisions cannot be empty")
    }

    private fun validateSearch(
        input: JsonObject,
        oracle: JsonObject,
        path: String,
    ) {
        input.requiredString("query", "$path.input")
        val inputDomains = input.requiredStrings("domains", "$path.input")
        val candidates = input.requiredArray("candidates", "$path.input")
        val candidateIds = validateMemories(candidates, "$path.input.candidates")
        val qrelsRaw = oracle.requiredArray("qrels", "$path.oracle")
        val qrels =
            qrelsRaw.mapIndexed { index, element ->
                val qrel = element.requireObject("$path.oracle.qrels[$index]")
                rejectUnknown(qrel, setOf("id", "gain"), "$path.oracle.qrels[$index]")
                requireAll(qrel, setOf("id", "gain"), "$path.oracle.qrels[$index]")
                qrel.requiredString("id", "$path.oracle.qrels[$index]") to qrel.requiredPositiveInt("gain", "$path.oracle.qrels[$index]")
            }
        if (qrels.map { it.first }.toSet().size != qrels.size) fail("$path.oracle.qrels contains duplicate IDs")
        if (!candidateIds.containsAll(qrels.map { it.first })) fail("$path.oracle.qrels references outside candidates")
        val critical = oracle.requiredStrings("criticalIds", "$path.oracle").also { requireUnique(it, "$path.oracle.criticalIds") }
        val qrelIds = qrels.map { it.first }.toSet()
        if (!qrelIds.containsAll(critical)) fail("$path.oracle.criticalIds must be in qrels")
        val k = oracle.requiredPositiveInt("k", "$path.oracle")
        if (k != 3) fail("$path.oracle.k must be 3")
        val oracleDomains = oracle.requiredStrings("domains", "$path.oracle")
        if (oracleDomains != inputDomains) fail("$path oracle domains must match input domains")
        val scanner = oracle.requiredObject("scanner", "$path.oracle")
        rejectUnknown(scanner, setOf("expectedIds", "forbiddenIds"), "$path.oracle.scanner")
        requireAll(scanner, setOf("expectedIds", "forbiddenIds"), "$path.oracle.scanner")
        val scannerExpected = scanner.requiredStrings("expectedIds", "$path.oracle.scanner")
        val scannerForbidden = scanner.requiredStrings("forbiddenIds", "$path.oracle.scanner")
        val rerank = oracle.requiredObject("rerank", "$path.oracle")
        rejectUnknown(rerank, setOf("expectedOrder", "forbiddenIds"), "$path.oracle.rerank")
        requireAll(rerank, setOf("expectedOrder", "forbiddenIds"), "$path.oracle.rerank")
        val rerankExpected = rerank.requiredStrings("expectedOrder", "$path.oracle.rerank")
        val rerankForbidden = rerank.requiredStrings("forbiddenIds", "$path.oracle.rerank")
        (scannerExpected + scannerForbidden + rerankExpected + rerankForbidden).forEach { id ->
            if (id !in candidateIds) fail("$path search oracle references outside candidates: $id")
        }
        if (!qrelIds.containsAll(scannerExpected) || !qrelIds.containsAll(rerankExpected)) {
            fail("$path search expected IDs must be qrels")
        }
        if (scannerExpected.any { it in scannerForbidden } || rerankExpected.any { it in rerankForbidden }) {
            fail("$path search expected and forbidden IDs overlap")
        }
        if (oracle.requiredBoolean("expectedEmpty", path) != qrelIds.isEmpty()) fail("$path search expectedEmpty mismatch")
        val resultMode = oracle.requiredString("resultMode", "$path.oracle")
        if (resultMode !in setOf("REQUIRE_RESULTS", "REQUIRE_EMPTY", "ALLOW_RESULTS_OR_EMPTY")) fail("$path invalid resultMode")
        if (resultMode == "REQUIRE_EMPTY" != qrelIds.isEmpty()) fail("$path resultMode/qrels mismatch")
        if (oracle.requiredString("domainFilterMode", "$path.oracle") !in setOf("NONE", "STRICT")) fail("$path invalid domainFilterMode")
        if (inputDomains.isNotEmpty() &&
            oracle.requiredString("domainFilterMode", "$path.oracle") != "STRICT"
        ) {
            fail("$path non-empty domains require STRICT domainFilterMode")
        }
        if (oracle.requiredString("inputTrustBoundary", "$path.oracle") !in
            setOf("NORMAL", "DATA_ONLY")
        ) {
            fail("$path invalid inputTrustBoundary")
        }
        val emptyBehavior = oracle.requiredString("emptyCandidatesBehavior", "$path.oracle")
        if (emptyBehavior !in setOf("NORMAL", "RETURN_EMPTY_WITHOUT_MODEL_CALL")) fail("$path invalid emptyCandidatesBehavior")
        if (candidateIds.isEmpty() && (resultMode != "REQUIRE_EMPTY" || emptyBehavior != "RETURN_EMPTY_WITHOUT_MODEL_CALL")) {
            fail("$path empty candidates must return empty without model calls")
        }
        oracle.requiredBoolean("uncertaintyRequired", "$path.oracle")
        oracle.requiredStrings("sourceSemanticConstraints", "$path.oracle")
        oracle.nullableString("sourceExpectedBehavior", "$path.oracle")
        oracle.requiredStrings("sourceForbiddenErrors", "$path.oracle")
    }

    private fun validateMap(
        input: JsonObject,
        oracle: JsonObject,
        path: String,
    ) {
        val memories = input.requiredArray("organizedMemories", "$path.input")
        val ids = mutableListOf<String>()
        val domains = mutableSetOf<String>()
        memories.forEachIndexed { index, element ->
            val memory = element.requireObject("$path.input.organizedMemories[$index]")
            val keys = setOf("id", "content", "domains", "sourceUpdatedAt")
            rejectUnknown(memory, keys, "$path.input.organizedMemories[$index]")
            requireAll(memory, keys, "$path.input.organizedMemories[$index]")
            ids += memory.requiredString("id", "$path.input.organizedMemories[$index]")
            memory.requiredString("content", "$path.input.organizedMemories[$index]")
            domains += memory.requiredStrings("domains", "$path.input.organizedMemories[$index]")
            memory.nullableString("sourceUpdatedAt", "$path.input.organizedMemories[$index]")
        }
        requireUnique(ids, "$path.input.organizedMemories.id")
        val required = oracle.requiredStrings("requiredMemoryIds", "$path.oracle")
        if (!ids.containsAll(required)) fail("$path.oracle.requiredMemoryIds references outside input")
        val requiredDomains = oracle.requiredStrings("requiredDomains", "$path.oracle")
        if (!domains.containsAll(requiredDomains)) fail("$path.oracle.requiredDomains references unavailable domains")
        oracle.requiredStrings("requiredFacts", "$path.oracle")
        oracle.requiredStrings("forbiddenFacts", "$path.oracle")
        oracle.requiredStrings("uncertaintyRequirements", "$path.oracle")
        val navigation = oracle.requiredStrings("navigationChecks", "$path.oracle")
        requireUnique(navigation, "$path.oracle.navigationChecks")
        required.forEach { id ->
            if ("#$id" !in navigation) fail("$path.oracle.navigationChecks is missing #$id")
        }
        requiredDomains.forEach { domain ->
            if ("@$domain" !in navigation) fail("$path.oracle.navigationChecks is missing @$domain")
        }
        val empty = oracle.requiredBoolean("expectedEmpty", path)
        if (empty != ids.isEmpty()) fail("$path map expectedEmpty mismatch")
        if (oracle.requiredString("inputTrustBoundary", "$path.oracle") !in
            setOf("NORMAL", "DATA_ONLY")
        ) {
            fail("$path invalid inputTrustBoundary")
        }
        val emptyBehavior = oracle.requiredString("emptyMapBehavior", "$path.oracle")
        if (emptyBehavior !in setOf("NORMAL", "RETURN_DETERMINISTIC_EMPTY_WITHOUT_MODEL_CALL")) fail("$path invalid emptyMapBehavior")
        if (empty && emptyBehavior != "RETURN_DETERMINISTIC_EMPTY_WITHOUT_MODEL_CALL") fail("$path empty map must avoid model calls")
        oracle.requiredStrings("sourceSemanticConstraints", "$path.oracle")
        oracle.nullableString("sourceExpectedBehavior", "$path.oracle")
        oracle.requiredStrings("sourceForbiddenErrors", "$path.oracle")
    }

    private fun validateDreaming(
        input: JsonObject,
        oracle: JsonObject,
        path: String,
    ) {
        val turnIds = input.requiredInts("turnIds", "$path.input").also { requireUnique(it, "$path.input.turnIds") }
        val turns = input.requiredArray("turns", "$path.input")
        val actualTurnIds = mutableListOf<Int>()
        turns.forEachIndexed { index, element ->
            val turn = element.requireObject("$path.input.turns[$index]")
            rejectUnknown(turn, setOf("id", "role", "content"), "$path.input.turns[$index]")
            requireAll(turn, setOf("id", "role", "content"), "$path.input.turns[$index]")
            actualTurnIds += turn.requiredPositiveInt("id", "$path.input.turns[$index]")
            turn.requiredString("role", "$path.input.turns[$index]")
            turn.requiredString("content", "$path.input.turns[$index]")
        }
        if (actualTurnIds.toSet() != turnIds.toSet()) fail("$path.input.turnIds must match turns")
        val organized = input.requiredArray("organizedMemories", "$path.input")
        val memoryIds = mutableListOf<String>()
        organized.forEachIndexed { index, element ->
            val memory = element.requireObject("$path.input.organizedMemories[$index]")
            rejectUnknown(memory, setOf("id", "content", "domains"), "$path.input.organizedMemories[$index]")
            requireAll(memory, setOf("id", "content", "domains"), "$path.input.organizedMemories[$index]")
            memoryIds += memory.requiredString("id", "$path.input.organizedMemories[$index]")
            memory.requiredString("content", "$path.input.organizedMemories[$index]")
            memory.requiredStrings("domains", "$path.input.organizedMemories[$index]")
        }
        val raw = input.requiredArray("rawMemories", "$path.input")
        raw.forEachIndexed { index, element ->
            val memory = element.requireObject("$path.input.rawMemories[$index]")
            validateRaw(memory, "$path.input.rawMemories[$index]")
            memoryIds += memory.requiredString("id", "$path.input.rawMemories[$index]")
            val sources = memory.requiredInts("sourceTurnIds", "$path.input.rawMemories[$index]")
            if (!turnIds.containsAll(sources)) fail("$path.input.rawMemories source turn references outside input")
        }
        requireUnique(memoryIds, "$path.input memory IDs")
        val evidence =
            oracle.requiredInts("requiredEvidenceTurnIds", "$path.oracle").also {
                requireUnique(it, "$path.oracle.requiredEvidenceTurnIds")
            }
        if (!turnIds.containsAll(evidence)) fail("$path.oracle.requiredEvidenceTurnIds references outside input")
        val duplicates = oracle.requiredStrings("duplicateOf", "$path.oracle")
        if (!memoryIds.containsAll(duplicates)) fail("$path.oracle.duplicateOf references outside input memories")
        val insights = oracle.requiredStrings("expectedInsights", "$path.oracle")
        oracle.requiredStrings("forbiddenInsightPatterns", "$path.oracle").forEach { pattern ->
            try {
                Regex(pattern)
            } catch (failure: IllegalArgumentException) {
                fail("$path.oracle.forbiddenInsightPatterns contains an invalid regular expression")
            }
        }
        oracle.requiredString("correctionGuard", "$path.oracle")
        val empty = oracle.requiredBoolean("expectedEmpty", path)
        if (empty != insights.isEmpty()) fail("$path dream expectedEmpty mismatch")
        if (oracle.requiredString("inputTrustBoundary", "$path.oracle") !in
            setOf("NORMAL", "DATA_ONLY")
        ) {
            fail("$path invalid inputTrustBoundary")
        }
        val sourceExpected = oracle.getValue("sourceScenarioExpected")
        if (sourceExpected !is JsonObject && sourceExpected !is JsonNull) fail("$path sourceScenarioExpected must be object or null")
        val stageOutputs = oracle.requiredArray("sourceScenarioStageOutputs", "$path.oracle")
        stageOutputs.forEachIndexed { index, stage ->
            val value = stage.requireObject("$path.oracle.sourceScenarioStageOutputs[$index]")
            value.requiredString("stage", "$path.oracle.sourceScenarioStageOutputs[$index]")
            value.requiredString("task", "$path.oracle.sourceScenarioStageOutputs[$index]")
            value.requiredString("profile", "$path.oracle.sourceScenarioStageOutputs[$index]")
            value.requiredString("model", "$path.oracle.sourceScenarioStageOutputs[$index]")
        }
    }

    private fun validateMemories(
        values: JsonArray,
        path: String,
    ): Set<String> {
        val ids =
            values.mapIndexed { index, value ->
                val memory = value.requireObject("$path[$index]")
                rejectUnknown(memory, setOf("id", "content", "domains"), "$path[$index]")
                requireAll(memory, setOf("id", "content", "domains"), "$path[$index]")
                val id = memory.requiredString("id", "$path[$index]")
                memory.requiredString("content", "$path[$index]")
                memory.requiredStrings("domains", "$path[$index]")
                id
            }
        requireUnique(ids, "$path.id")
        return ids.toSet()
    }

    private fun validateRaw(
        raw: JsonObject,
        path: String,
    ) {
        rejectUnknown(raw, setOf("id", "content", "sourceTurnIds"), path)
        requireAll(raw, setOf("id", "content", "sourceTurnIds"), path)
        raw.requiredString("id", path)
        raw.requiredString("content", path)
        raw.requiredInts("sourceTurnIds", path)
    }

    private fun validateStableIds(samples: List<EvaluationSample>) {
        samples
            .map { it.id }
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
            .forEach { fail("duplicate sample ID: $it") }
        samples.mapIndexed { index, sample ->
            val expected =
                when (sample.task) {
                    "EXTRACTION" -> "ext-"
                    "CANDIDATE_SCANNER" -> "sc-"
                    "GOVERNANCE" -> "gov-"
                    "MEMORY_SEARCH" -> "ret-"
                    "MEMORY_MAP" -> "map-"
                    "DREAMING" -> "dream-"
                    else -> fail("unknown task ${sample.task}")
                }
            if (!sample.id.startsWith(expected)) fail("${sample.id}: ID prefix does not match task")
            if (sample.id != "${expected}${(index + 1).toString().padStart(3, '0')}") {
                fail("${sample.id}: IDs must be contiguous and ordered; expected $expected${(index + 1).toString().padStart(3, '0')}")
            }
        }
    }

    private fun validateReferences(samples: List<EvaluationSample>) {
        val risks = samples.flatMap { it.oracle.requiredStrings("riskTags", it.id) }.toSet()
        listOf("H1_FABRICATION", "H2_CRITICAL_CONFLICT_MISS", "H3_CORRECTION_REGRESSION").forEach { required ->
            if (required !in risks) fail("dataset is missing high-risk coverage: $required")
        }
        if (samples.isNotEmpty() && samples.all { it.task == "GOVERNANCE" }) {
            // Governance has no production NOOP action; upstream Scanner/Extraction owns no-candidate decisions.
        } else if (samples.none { it.oracle.requiredBoolean("expectedEmpty", it.id) }) {
            fail("dataset must cover an expected-empty result")
        }
        if (samples.none { it.oracle.requiredString("criticality", it.id) == "CRITICAL" }) fail("dataset must contain a critical sample")
    }

    private fun validatePackage(datasets: List<EvaluationDataset>) {
        if (datasets.size != 6) fail("package must contain all six datasets")
        datasets.forEach { dataset ->
            if (dataset.samples.size != 12) fail("${dataset.id}: canonical dataset must contain 12 samples")
            if (dataset.task != "GOVERNANCE" &&
                dataset.samples.none {
                    it.oracle.requiredBoolean(
                        "expectedEmpty",
                        it.id,
                    )
                }
            ) {
                fail("${dataset.id}: missing expected-empty sample")
            }
            if (dataset.samples.none {
                    it.oracle.requiredString(
                        "criticality",
                        it.id,
                    ) == "CRITICAL"
                }
            ) {
                fail("${dataset.id}: missing critical sample")
            }
        }
        val ids = datasets.flatMap { dataset -> dataset.samples.map { it.id } }
        if (ids.toSet().size != ids.size) fail("sample IDs must be globally unique")
    }

    private fun requireFileHash(
        root: Path,
        relativePath: String,
        expected: String,
        path: String,
    ) {
        val file = safeResolve(root, relativePath, path)
        if (!Files.isRegularFile(file)) fail("$path does not exist: $relativePath")
        if (sha256(file) != expected) fail("$path SHA-256 drift: $relativePath")
    }

    private fun safeResolve(
        root: Path,
        relativePath: String,
        path: String,
    ): Path {
        val relative = Path.of(relativePath)
        if (relative.isAbsolute || relative.normalize().startsWith("..")) fail("$path must be a relative in-repository path")
        val resolved = root.resolve(relative).normalize()
        if (!resolved.startsWith(root)) fail("$path escapes repository root")
        return resolved
    }

    private fun parseObject(
        text: String,
        path: String,
    ): JsonObject =
        try {
            json.parseToJsonElement(text).jsonObject
        } catch (failure: Exception) {
            fail("$path: invalid JSON object: ${failure.message}")
        }

    private fun rejectUnknown(
        value: JsonObject,
        allowed: Set<String>,
        path: String,
    ) {
        val unknown = value.keys - allowed
        if (unknown.isNotEmpty()) fail("$path contains unknown fields: ${unknown.sorted().joinToString()}")
    }

    private fun requireAll(
        value: JsonObject,
        required: Set<String>,
        path: String,
    ) {
        val missing = required - value.keys
        if (missing.isNotEmpty()) fail("$path is missing fields: ${missing.sorted().joinToString()}")
    }

    private fun JsonObject.requiredString(
        name: String,
        path: String,
    ): String {
        val primitive = requiredPrimitive(name, path)
        return try {
            primitive.content.takeIf { it.isNotEmpty() } ?: fail("$path.$name must be a non-empty string")
        } catch (failure: EvaluationDatasetException) {
            throw failure
        } catch (_: Exception) {
            fail("$path.$name must be a string")
        }
    }

    private fun JsonObject.nullableString(
        name: String,
        path: String,
    ): String? {
        val element = this[name] ?: fail("$path is missing fields: $name")
        if (element is JsonNull) return null
        return element.jsonPrimitive.content
    }

    private fun JsonObject.requiredBoolean(
        name: String,
        path: String,
    ): Boolean =
        try {
            requiredPrimitive(name, path).boolean
        } catch (failure: EvaluationDatasetException) {
            throw failure
        } catch (_: Exception) {
            fail("$path.$name must be a boolean")
        }

    private fun JsonObject.requiredInt(
        name: String,
        path: String,
    ): Int =
        try {
            requiredPrimitive(name, path).int
        } catch (failure: EvaluationDatasetException) {
            throw failure
        } catch (_: Exception) {
            fail("$path.$name must be an integer")
        }

    private fun JsonObject.requiredPositiveInt(
        name: String,
        path: String,
    ): Int = requiredInt(name, path).also { if (it < 1) fail("$path.$name must be positive") }

    private fun JsonObject.requiredNonNegativeInt(
        name: String,
        path: String,
    ): Int = requiredInt(name, path).also { if (it < 0) fail("$path.$name must be non-negative") }

    private fun JsonObject.requiredArray(
        name: String,
        path: String,
    ): JsonArray =
        try {
            this[name]?.jsonArray ?: fail("$path is missing fields: $name")
        } catch (failure: EvaluationDatasetException) {
            throw failure
        } catch (_: Exception) {
            fail("$path.$name must be an array")
        }

    private fun JsonObject.requiredObject(
        name: String,
        path: String,
    ): JsonObject =
        try {
            this[name]?.jsonObject ?: fail("$path is missing fields: $name")
        } catch (failure: EvaluationDatasetException) {
            throw failure
        } catch (_: Exception) {
            fail("$path.$name must be an object")
        }

    private fun JsonObject.requiredStrings(
        name: String,
        path: String,
    ): List<String> = requiredArray(name, path).mapIndexed { index, value -> value.requireString("$path.$name[$index]") }

    private fun JsonObject.requiredInts(
        name: String,
        path: String,
    ): List<Int> = requiredArray(name, path).mapIndexed { index, value -> value.requireInt("$path.$name[$index]") }

    private fun JsonObject.requiredSha(
        name: String,
        path: String,
    ): String = requiredString(name, path).also { if (!sha256Pattern.matches(it)) fail("$path must be lowercase SHA-256") }

    private fun JsonObject.requiredPrimitive(
        name: String,
        path: String,
    ): JsonPrimitive = (this[name] ?: fail("$path is missing fields: $name")) as? JsonPrimitive ?: fail("$path.$name is invalid")

    private fun JsonElement.requireObject(path: String): JsonObject = this as? JsonObject ?: fail("$path must be an object")

    private fun JsonElement.requireString(path: String): String =
        (this as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() } ?: fail("$path must be a non-empty string")

    private fun JsonElement.requireInt(path: String): Int =
        try {
            (this as? JsonPrimitive)?.int ?: fail("$path must be an integer")
        } catch (failure: EvaluationDatasetException) {
            throw failure
        } catch (_: Exception) {
            fail("$path must be an integer")
        }

    private fun requireUnique(
        values: List<*>,
        path: String,
    ) {
        if (values.toSet().size != values.size) fail("$path contains duplicates")
    }

    private fun sha256(path: Path): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(Files.readAllBytes(path))
            .joinToString("") { "%02x".format(it) }
}

internal object DefaultEvaluationRoot {
    fun find(start: Path = Path.of("").toAbsolutePath()): Path {
        var candidate: Path? = start.normalize()
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle.kts")) &&
                Files.isRegularFile(candidate.resolve("evals/manifest-v1.json"))
            ) {
                return candidate
            }
            candidate = candidate.parent
        }
        throw IllegalStateException("Cannot locate repository root from $start")
    }
}

/**
 * Strict, offline validator for the JSON-Schema subset used by CT31. Unsupported keywords fail
 * closed instead of silently weakening a schema. It is test infrastructure, not a public API.
 */
internal object EvaluationJsonSchemaSubsetValidator {
    private val supported =
        setOf(
            "\$schema",
            "\$id",
            "title",
            "description",
            "type",
            "properties",
            "required",
            "additionalProperties",
            "items",
            "minItems",
            "maxItems",
            "uniqueItems",
            "minLength",
            "minimum",
            "maximum",
            "pattern",
            "format",
            "enum",
            "const",
            "\$ref",
            "\$defs",
            "allOf",
            "if",
            "then",
            "else",
        )
    private val supportedTypes = setOf("object", "array", "string", "boolean", "integer", "number", "null")

    fun validateFile(
        schemaPath: Path,
        value: JsonElement,
    ) {
        val root = Json.parseToJsonElement(schemaPath.readText()).asObject("schema")
        validate(root, value, root, "#")
    }

    private fun validate(
        schema: JsonObject,
        value: JsonElement,
        root: JsonObject,
        path: String,
    ) {
        (schema.keys - supported).firstOrNull()?.let { fail("$path uses unsupported schema keyword $it") }
        schema["\$ref"]?.let {
            val ref = it.string("$path.\$ref")
            if (!ref.startsWith("#/")) fail("$path only local references are supported")
            var target: JsonElement = root
            ref.removePrefix("#/").split('/').forEach { part ->
                val key = part.replace("~1", "/").replace("~0", "~")
                target = target.asObject("$path reference").getValue(key)
            }
            validate(target.asObject("$path reference"), value, root, ref)
        }
        schema["const"]?.let { if (value != it) fail("$path const mismatch") }
        schema["enum"]?.let {
            if (value !in it.asArray("$path.enum")) fail("$path value is not in enum")
        }
        schema["type"]?.let {
            val types =
                when (it) {
                    is JsonPrimitive -> listOf(it.string("$path.type"))
                    is JsonArray -> it.map { type -> type.string("$path.type") }
                    else -> fail("$path.type must be a string or array")
                }
            if (types.any { type -> type !in supportedTypes } || types.none { type -> matchesType(value, type) }) {
                fail("$path type mismatch: $types")
            }
        }
        schema["minimum"]?.let {
            // JSON Schema numeric keywords only constrain numbers; a nullable field is simply not constrained.
            if (value is JsonNull) return@let
            val number = value.number("$path") ?: fail("$path minimum requires an integer")
            if (number < it.int("$path.minimum")) fail("$path below minimum")
        }
        schema["maximum"]?.let {
            if (value is JsonNull) return@let
            val number = value.number("$path") ?: fail("$path maximum requires an integer")
            if (number > it.int("$path.maximum")) fail("$path above maximum")
        }
        schema["minLength"]?.let {
            if (value is JsonNull) return@let
            if (value.string("$path").length < it.int("$path.minLength")) fail("$path below minLength")
        }
        schema["pattern"]?.let {
            if (!Regex(it.string("$path.pattern")).containsMatchIn(value.string("$path"))) fail("$path pattern mismatch")
        }
        schema["format"]?.let {
            if (it.string("$path.format") != "date-time") fail("$path uses unsupported format")
            val timestamp = value.string("$path")
            if (!Regex(
                    "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]+)?(?:Z|[+-][0-9]{2}:[0-9]{2})",
                ).matches(timestamp)
            ) {
                fail("$path is not a date-time")
            }
        }
        schema["properties"]?.asObject("$path.properties")?.forEach { (name, child) ->
            if (value is JsonObject && name in value) {
                validate(child.asObject("$path.properties.$name"), value.getValue(name), root, "$path.$name")
            }
        }
        schema["required"]?.asArray("$path.required")?.forEach {
            val name = it.string("$path.required")
            if (value !is JsonObject || name !in value) fail("$path missing required field $name")
        }
        schema["additionalProperties"]?.let { additional ->
            if (value is JsonObject) {
                val known = schema["properties"]?.asObject("$path.properties")?.keys.orEmpty()
                val unknown = value.keys - known
                if (unknown.isNotEmpty()) {
                    if (additional == JsonPrimitive(false)) fail("$path unknown fields: $unknown")
                    if (additional is JsonObject) {
                        unknown.forEach { name ->
                            validate(additional, value.getValue(name), root, "$path.$name")
                        }
                    }
                }
            }
        }
        schema["items"]?.let { itemSchema ->
            value.asArray("$path").forEachIndexed { index, item ->
                validate(itemSchema.asObject("$path.items"), item, root, "$path[$index]")
            }
        }
        schema["minItems"]?.let { if (value.asArray("$path").size < it.int("$path.minItems")) fail("$path too few items") }
        schema["maxItems"]?.let { if (value.asArray("$path").size > it.int("$path.maxItems")) fail("$path too many items") }
        schema["uniqueItems"]?.let {
            if (it.boolean("$path.uniqueItems") &&
                value.asArray("$path").toSet().size != value.asArray("$path").size
            ) {
                fail("$path items are not unique")
            }
        }
        schema["allOf"]?.asArray("$path.allOf")?.forEachIndexed { index, child ->
            validate(child.asObject("$path.allOf[$index]"), value, root, "$path.allOf[$index]")
        }
        schema["if"]?.let { condition ->
            val matches =
                condition.asObject("$path.if").entries.all { (keyword, expected) ->
                    when (keyword) {
                        "properties" ->
                            expected.asObject("$path.if.properties").all { (name, child) ->
                                value is JsonObject &&
                                    value.containsKey(name) &&
                                    matchesSchema(child.asObject("$path.if.$name"), value.getValue(name), root, "$path.if.$name")
                            }
                        "required" ->
                            expected.asArray("$path.if.required").all { name ->
                                value is JsonObject && value.containsKey(name.string("$path.if.required"))
                            }
                        else -> matchesSchema(condition.asObject("$path.if"), value, root, "$path.if")
                    }
                }
            val branch = if (matches) schema["then"] else schema["else"]
            if (branch != null) validate(branch.asObject("$path conditional"), value, root, "$path conditional")
        }
    }

    private fun matchesSchema(
        schema: JsonObject,
        value: JsonElement,
        root: JsonObject,
        path: String,
    ): Boolean =
        try {
            validate(schema, value, root, path)
            true
        } catch (_: EvaluationDatasetException) {
            false
        }

    private fun matchesType(
        value: JsonElement,
        type: String,
    ): Boolean =
        when (type) {
            "object" -> value is JsonObject
            "array" -> value is JsonArray
            "string" -> value is JsonPrimitive && value.isString
            "boolean" -> value is JsonPrimitive && value.booleanOrNull != null
            "integer" -> value is JsonPrimitive && !value.isString && value.intOrNull != null
            "number" -> value is JsonPrimitive && !value.isString && value.doubleOrNull != null
            "null" -> value is JsonNull
            else -> false
        }

    private fun JsonElement.asObject(path: String): JsonObject = this as? JsonObject ?: fail("$path must be an object")

    private fun JsonElement.asArray(path: String): JsonArray = this as? JsonArray ?: fail("$path must be an array")

    private fun JsonElement.string(path: String): String =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content ?: fail("$path must be a string")

    private fun JsonElement.int(path: String): Int = (this as? JsonPrimitive)?.intOrNull ?: fail("$path must be an integer")

    private fun JsonElement.boolean(path: String): Boolean = (this as? JsonPrimitive)?.booleanOrNull ?: fail("$path must be boolean")

    private fun JsonElement.number(path: String): Int? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

    private fun fail(message: String): Nothing = throw EvaluationDatasetException(message)
}
