package io.github.grassified.wristprompt.ui

import io.github.grassified.wristprompt.openrouter.CatalogProblem
import io.github.grassified.wristprompt.openrouter.EndpointDetails
import io.github.grassified.wristprompt.openrouter.EndpointPrice
import io.github.grassified.wristprompt.openrouter.PublicEndpointMetadata

internal fun ShellListScope.endpointItems(
    candidateId: String,
    details: EndpointDetails,
    load: () -> Unit,
) {
    item { ShellText("Public endpoint metadata", heading = true) }
    paragraphs("Public model candidate · $candidateId")
    item { ShellText("Account eligibility · Unverified") }
    item { ShellText("Exact routing identity · Unverified") }
    when (details) {
        EndpointDetails.Unavailable -> {
            item { ShellText("Lookup unavailable. API access with an ordinary key is unverified.") }
            action("Load endpoint details", enabled = false, onClick = load)
        }
        EndpointDetails.Idle -> action("Load endpoint details", onClick = load)
        EndpointDetails.Loading -> item { ShellText("Loading endpoint details…") }
        is EndpointDetails.Failed -> {
            item { ShellText(details.problem.endpointMessage()) }
            action("Retry endpoint details", onClick = load)
        }
        is EndpointDetails.Loaded -> {
            if (details.rows.isEmpty()) item { ShellText("No public endpoint records returned.") }
            for ((index, row) in details.rows.withIndex()) endpointRecord(index + 1, row)
        }
    }
}

private fun ShellListScope.endpointRecord(number: Int, row: PublicEndpointMetadata) {
    item { ShellText("Endpoint record $number", heading = true) }
    paragraphs("Provider · ${row.providerName ?: "Unknown"}")
    paragraphs("Reported tag · ${row.reportedTag ?: "Unknown"}")
    paragraphs("Endpoint name · ${row.endpointName ?: "Unknown"}")
    paragraphs("Model ID · ${row.modelId ?: "Unknown"}")
    paragraphs("Context · ${row.contextTokens.tokens()}")
    paragraphs("Maximum input · ${row.maxPromptTokens.tokens()}")
    paragraphs("Maximum output · ${row.maxCompletionTokens.tokens()}")
    paragraphs("Parameters · ${row.supportedParameters?.joinToString()?.ifEmpty { "None reported" } ?: "Unknown"}")
    paragraphs("Quantization · ${row.quantization ?: "Unknown"}")
    paragraphs("Service tier · ${row.serviceTier ?: "Unknown"}")
    item { ShellText("Privacy and retention · Unknown") }
    item { ShellText("Public base prices · May change", heading = true) }
    for (key in listOf(EndpointPrice.INPUT, EndpointPrice.OUTPUT) + row.prices.keys.filter {
        it != EndpointPrice.INPUT && it != EndpointPrice.OUTPUT
    }) {
        val price = row.prices[key]?.let { "\$$it per ${key.unit}" } ?: "Unknown"
        paragraphs("${key.label} · $price")
    }
}

private fun CatalogProblem.endpointMessage(): String = when (this) {
    CatalogProblem.ACCESS_DENIED -> "OpenRouter denied access to endpoint metadata."
    CatalogProblem.NOT_FOUND -> "Endpoint metadata was unavailable for this model."
    CatalogProblem.RATE_LIMITED -> "Rate limit reached. Try again later."
    CatalogProblem.TIMEOUT -> "The endpoint request timed out."
    CatalogProblem.NETWORK, CatalogProblem.SERVICE -> "Could not load endpoint metadata."
    CatalogProblem.TOO_LARGE -> "Endpoint metadata exceeds the watch’s size limits."
    CatalogProblem.REDIRECT -> "OpenRouter redirected the endpoint request."
    else -> "OpenRouter returned invalid endpoint metadata."
}
