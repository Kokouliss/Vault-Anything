package com.kokouliss.vaultanything.core.domain.repository

import com.kokouliss.vaultanything.core.domain.model.ActivityEntry
import com.kokouliss.vaultanything.core.domain.model.Document
import kotlinx.coroutines.flow.Flow

/** Who added, opened and deleted which document in a space, and who reset whose PIN. */
interface ActivityRepository {
    /** The newest entries first. Only adults may read them: for a child the list stays empty. */
    fun observeActivity(spaceId: String): Flow<List<ActivityEntry>>

    /** Notes that the signed-in person looked at [document]. The app calls it for documents that are not their own. */
    suspend fun logOpened(document: Document)
}
