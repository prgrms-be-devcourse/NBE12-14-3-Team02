package com.prgms.backend.domain.content.entity

import com.prgms.backend.global.entity.BaseTimeEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table

@Entity
@Table(name = "content_candidates")
class ContentCandidate(
    contentPoll: ContentPoll,
    createdByMemberId: Long,
    title: String,
    description: String?,
) : BaseTimeEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0
        protected set

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "content_poll_id", nullable = false)
    var contentPoll: ContentPoll = contentPoll
        protected set

    @Column(name = "created_by_member_id", nullable = false)
    var createdByMemberId: Long = createdByMemberId
        protected set

    @Column(nullable = false, length = 100)
    var title: String = title
        protected set

    @Column(length = 500)
    var description: String? = description
        protected set

    fun update(
        title: String,
        description: String?,
    ) {
        this.title = title
        this.description = description
    }
}
