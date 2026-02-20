package com.example.roonplayer.state.transition

interface CommittedPlaybackSnapshotRepository {
    fun read(): CommittedPlaybackSnapshot?

    fun write(snapshot: CommittedPlaybackSnapshot)
}

class InMemoryCommittedPlaybackSnapshotRepository(
    private var snapshot: CommittedPlaybackSnapshot? = null
) : CommittedPlaybackSnapshotRepository {

    override fun read(): CommittedPlaybackSnapshot? {
        return snapshot
    }

    override fun write(snapshot: CommittedPlaybackSnapshot) {
        this.snapshot = snapshot
    }
}
