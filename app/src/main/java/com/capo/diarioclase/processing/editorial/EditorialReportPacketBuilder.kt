package com.capo.diarioclase.processing.editorial

import com.capo.diarioclase.processing.evidence.EvidenceClaim
import com.capo.diarioclase.processing.evidence.EvidenceRef
import com.capo.diarioclase.processing.evidence.InterpretationMode
import com.capo.diarioclase.processing.evidence.InterpretationProjector
import java.security.MessageDigest

class EditorialReportPacketBuilder(
    private val projector: InterpretationProjector = InterpretationProjector(),
) {
    fun build(
        sessionId: String,
        mode: InterpretationMode,
        claims: List<EvidenceClaim>,
    ): EditorialReportRequest {
        val items = projector.project(claims, mode).accepted
            .sortedWith(claimOrder)
            .map(::toItem)
        return EditorialReportRequest(
            sessionId = sessionId,
            inputHash = hash(items),
            items = items,
        )
    }

    private fun toItem(claim: EvidenceClaim): EditorialEvidenceItem {
        val evidence = primaryEvidence(claim)
        return EditorialEvidenceItem(
            claimId = claim.id,
            category = claim.category,
            status = claim.status,
            value = claim.value,
            normalizedValue = claim.normalizedValue,
            excerpt = evidence.excerpt,
            blockOrdinal = evidence.blockOrdinal,
            segmentOrdinal = evidence.audioSegmentOrdinal,
            spanOrdinal = evidence.spanOrdinal,
            origin = claim.origin,
            provenance = claim.provenance,
            supersedesClaimKeys = claim.supersedesClaimKeys.sorted(),
        )
    }

    private fun hash(items: List<EditorialEvidenceItem>): String {
        val canonical = buildString {
            field(EditorialReportRequest.PROMPT_VERSION)
            field(EditorialReportRequest.SCHEMA_VERSION)
            items.forEach { item ->
                field(item.claimId)
                field(item.category.name)
                field(item.status.name)
                field(item.value)
                field(item.normalizedValue)
                field(item.excerpt)
                field(item.blockOrdinal)
                field(item.segmentOrdinal)
                field(item.spanOrdinal)
                field(item.origin.name)
                field(item.provenance.name)
                field(item.supersedesClaimKeys.size)
                item.supersedesClaimKeys.forEach { field(it) }
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun StringBuilder.field(value: Any?) {
        val text = value?.toString()
        if (text == null) {
            append("-1:")
        } else {
            append(text.length).append(':').append(text)
        }
        append('|')
    }

    private fun primaryEvidence(claim: EvidenceClaim): EvidenceRef =
        claim.evidences.minWithOrNull(evidenceOrder) ?: claim.evidence

    private val claimOrder = Comparator<EvidenceClaim> { left, right ->
        val evidenceComparison = evidenceOrder.compare(primaryEvidence(left), primaryEvidence(right))
        if (evidenceComparison != 0) evidenceComparison else {
            val ordinalComparison = left.claimOrdinal.compareTo(right.claimOrdinal)
            if (ordinalComparison != 0) ordinalComparison else left.id.compareTo(right.id)
        }
    }

    private val evidenceOrder = compareBy<EvidenceRef>(
        { it.blockOrdinal ?: Int.MAX_VALUE },
        { it.audioSegmentOrdinal ?: Int.MAX_VALUE },
        { it.spanOrdinal ?: Int.MAX_VALUE },
        { it.startMs },
        { it.blockId.value },
    )
}
