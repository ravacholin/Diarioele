package com.capo.diarioclase.processing.work

enum class InterpretationRunState {
    PENDING,
    RUNNING,
    REMOTE_OK,
    LOCAL_OK,
    MIXED_OK,
    FAILED,
    CANCELLED,
}

enum class InterpretationPacketState {
    PENDING,
    RUNNING,
    REMOTE_OK,
    LOCAL_OK,
    FAILED,
    CANCELLED,
}

enum class InterpretationFailure {
    DEADLINE,
    CANCELLED,
    TRANSPORT,
    INVALID_RESPONSE,
    INSUFFICIENT_RESPONSE,
    INTERNAL,
}
