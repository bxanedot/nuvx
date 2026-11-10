package com.bxane.nuvx.data.settings

enum class AutomixPerformanceMode(val inferenceThreads: Int) {
    EFFICIENT(1),
    BALANCED(2),
    PERFORMANCE(4),
    ;

    val yieldsToPlayback: Boolean get() = this == EFFICIENT
}
