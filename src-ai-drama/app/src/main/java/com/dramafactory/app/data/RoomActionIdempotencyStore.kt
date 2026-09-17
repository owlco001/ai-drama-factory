package com.dramafactory.app.data

import com.dramafactory.core.orchestrate.ActionIdempotencyStore

/** Cross-process idempotency backed by Room's primary-key uniqueness. */
class RoomActionIdempotencyStore(private val dao: DramaDao) : ActionIdempotencyStore {
    override suspend fun reserve(key: String): Boolean =
        dao.reserveAction(ActionIdempotencyEntity(key, "RESERVED", System.currentTimeMillis())) != -1L

    override suspend fun complete(key: String) {
        dao.completeAction(key, System.currentTimeMillis())
    }

    override suspend fun release(key: String) {
        dao.releaseAction(key)
    }
}