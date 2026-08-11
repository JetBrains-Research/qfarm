package org.jetbrains.bio.qfarm.evolution

enum class EvolutionPhase(
    val seedTag: Long
) {
    CHEAP(1L),
    FULL(2L)
}

fun seedForEvolution(
    rootSeed: Long,
    attributes: List<Int>,
    phase: EvolutionPhase
): Long {

    var state =
        mix64(
            rootSeed xor phase.seedTag
        )

    attributes.forEachIndexed { position, attributeIndex ->
        val component =
            (position.toLong() shl 32) xor
                    (attributeIndex.toLong() and 0xFFFF_FFFFL)

        state =
            mix64(
                state xor component
            )
    }

    return mix64(
        state xor attributes.size.toLong()
    )
}

private fun mix64(
    value: Long
): Long {

    var z =
        value - 7046029254386353131L

    z =
        (z xor (z ushr 30)) *
                -4658895280553007687L

    z =
        (z xor (z ushr 27)) *
                -7723592293110705685L

    return z xor (z ushr 31)
}
