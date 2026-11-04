'use strict'

const { postShareProjection } = require('./profile-post-shares')
const key = value => String(value ?? '').trim().toLowerCase()
const postKey = row => key(row?.post_id || row?.postId)
const deleted = row => [true, 1, 'true', 't', '1'].includes(row?.is_deleted ?? row?.isDeleted ?? row?.deleted)

/** Bounded batches shared by public and personalized feed projections. */
function createPostEngagementReader(dependencies) {
  const { readImported, readReactions, readComments, readShares, mergeReactions, projectReactions } = dependencies || {}
  if ([readImported, readReactions, readComments, readShares, mergeReactions, projectReactions].some(fn => typeof fn !== 'function')) {
    throw new TypeError('Complete post engagement readers and projectors are required')
  }
  return async function readPostEngagement(postIds, identity) {
    if (!Array.isArray(postIds) || postIds.length > 200 || !(identity?.ids instanceof Set)) throw new TypeError('Invalid post engagement page')
    const ids = [...new Set(postIds.map(key).filter(Boolean))], wanted = new Set(ids)
    const likeCounts = new Map(), commentCounts = new Map(), likedPostIds = new Set()
    const reactionCounts = new Map(), myReactions = new Map(), shareCounts = new Map(), sharedPostIds = new Set()
    if (!ids.length) return { likeCounts, commentCounts, likedPostIds, reactionCounts, myReactions, shareCounts, sharedPostIds }
    const batches = await Promise.all([
      readImported('post_likes', ids), readImported('post_reactions', ids), readImported('post_comments', ids),
      readReactions(ids), readComments(ids), readImported('shares', ids), readShares(ids),
    ])
    if (batches.some(rows => !Array.isArray(rows))) throw new TypeError('Incomplete post engagement read')
    const [likes, reactions, comments, localReactions, localComments, importedShares, localShares] = batches
    const relevant = rows => rows.filter(row => wanted.has(postKey(row)))
    const state = projectReactions(mergeReactions(relevant([...likes, ...reactions]), relevant(localReactions)), identity)
    for (const [id, total] of state.totals) likeCounts.set(id, total)
    for (const [id, counts] of state.counts) reactionCounts.set(id, counts)
    for (const [id, type] of state.mine) { myReactions.set(id, type); likedPostIds.add(id) }
    // An unlike overrides a count embedded in a historical post as well as its reaction row.
    for (const row of relevant(localReactions)) if (deleted(row) && !likeCounts.has(postKey(row))) likeCounts.set(postKey(row), 0)
    const commentsById = new Map()
    for (const [index, row] of relevant([...comments, ...localComments]).entries()) {
      commentsById.set(key(row.id) || `unkeyed:${index}`, row)
      if (deleted(row) && !commentCounts.has(postKey(row))) commentCounts.set(postKey(row), 0)
    }
    for (const row of commentsById.values()) if (!deleted(row)) {
      const id = postKey(row)
      commentCounts.set(id, (commentCounts.get(id) || 0) + 1)
    }
    const shares = postShareProjection(relevant([...importedShares, ...localShares]), identity.ids)
    for (const [id, total] of shares.counts) shareCounts.set(id, total)
    for (const id of shares.mine) sharedPostIds.add(id)
    return { likeCounts, commentCounts, likedPostIds, reactionCounts, myReactions, shareCounts, sharedPostIds }
  }
}

module.exports = { createPostEngagementReader }
