package chat.simplex.common.model

import kotlinx.serialization.Serializable

enum class FeedType {
  RSS_ATOM,
  MASTODON_ACTIVITYPUB
}

@Serializable
data class FeedSubscription(
  val id: String,
  val targetGroupId: Long,
  val url: String,
  val type: FeedType,
  val title: String,
  val enabled: Boolean = true,
  val intervalMinutes: Int = 30,
  val lastFetchedTs: Long = 0L,
  val seenItemIds: Set<String> = emptySet()
)

data class FeedEntry(
  val id: String,
  val title: String,
  val contentText: String,
  val link: String?,
  val author: String?,
  val publishedTs: Long
)
