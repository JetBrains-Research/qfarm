package org.jetbrains.bio.qfarm.logger

object InitialRuntimeEstimator {

    private const val REFERENCE_ROWS =
        27_990.0

    private const val REFERENCE_RANDOM_AUC_COLUMNS =
        1_000.0

    private const val REFERENCE_RANDOM_AUC_SECONDS =
        2.15

    private const val REFERENCE_CHEAP_POPULATION =
        100.0

    private const val REFERENCE_CHEAP_GENERATIONS =
        100.0

    private const val REFERENCE_CHEAP_SECONDS =
        0.50

    private const val REFERENCE_FULL_POPULATION =
        500.0

    private const val REFERENCE_FULL_GENERATIONS =
        500.0

    private const val REFERENCE_FULL_SECONDS =
        2.20

    fun randomAucSeconds(
        datasetRows: Int,
        randomAucColumns: Int
    ): Double {
        require(datasetRows > 0) {
            "datasetRows must be > 0"
        }

        require(randomAucColumns >= 0) {
            "randomAucColumns must be >= 0"
        }

        if (randomAucColumns == 0) {
            return 0.0
        }

        val rowScale =
            datasetRows / REFERENCE_ROWS

        val columnScale =
            randomAucColumns /
                    REFERENCE_RANDOM_AUC_COLUMNS

        return (
                REFERENCE_RANDOM_AUC_SECONDS *
                        rowScale *
                        columnScale
                )
            .coerceAtLeast(0.05)
    }

    fun cheapEvolutionSeconds(
        datasetRows: Int,
        population: Int,
        generations: Int
    ): Double {
        require(datasetRows > 0) {
            "datasetRows must be > 0"
        }

        require(population > 0) {
            "Cheap population must be > 0"
        }

        require(generations > 0) {
            "Cheap generations must be > 0"
        }

        val rowScale =
            kotlin.math.sqrt(
                datasetRows / REFERENCE_ROWS
            )

        val populationScale =
            population /
                    REFERENCE_CHEAP_POPULATION

        val generationScale =
            generations /
                    REFERENCE_CHEAP_GENERATIONS

        return (
                REFERENCE_CHEAP_SECONDS *
                        rowScale *
                        populationScale *
                        generationScale
                )
            .coerceAtLeast(0.05)
    }

    fun fullEvolutionSeconds(
        datasetRows: Int,
        population: Int,
        generations: Int
    ): Double {
        require(datasetRows > 0) {
            "datasetRows must be > 0"
        }

        require(population > 0) {
            "Full population must be > 0"
        }

        require(generations > 0) {
            "Full generations must be > 0"
        }

        val rowScale =
            kotlin.math.sqrt(
                datasetRows / REFERENCE_ROWS
            )

        val populationScale =
            population /
                    REFERENCE_FULL_POPULATION

        val generationScale =
            generations /
                    REFERENCE_FULL_GENERATIONS

        return (
                REFERENCE_FULL_SECONDS *
                        rowScale *
                        populationScale *
                        generationScale
                )
            .coerceAtLeast(0.10)
    }
}
