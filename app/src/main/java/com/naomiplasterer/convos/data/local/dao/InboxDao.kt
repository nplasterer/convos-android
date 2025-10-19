package com.naomiplasterer.convos.data.local.dao

import androidx.room.*
import com.naomiplasterer.convos.data.local.entity.InboxEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface InboxDao {

    @Query("SELECT * FROM inboxes ORDER BY createdAt DESC")
    fun getAllInboxes(): Flow<List<InboxEntity>>

    @Query("SELECT * FROM inboxes WHERE inboxId = :inboxId")
    suspend fun getInbox(inboxId: String): InboxEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(inbox: InboxEntity)

    @Delete
    suspend fun delete(inbox: InboxEntity)

    @Query("DELETE FROM inboxes")
    suspend fun deleteAll()
}
