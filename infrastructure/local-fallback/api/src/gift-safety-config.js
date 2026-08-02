function boundedPositiveIntegerEnv(name, fallback, maximum) {
  const value = Math.trunc(Number(process.env[name] || fallback) || fallback)
  return Math.max(1, Math.min(maximum, value))
}

function liveGiftLimitsConfiguration() {
  const maxCoinsPerGift = boundedPositiveIntegerEnv('LIVE_GIFT_MAX_COINS_PER_GIFT', 5_000, 1_000_000)
  const maxCoinsPerDay = Math.max(
    maxCoinsPerGift,
    boundedPositiveIntegerEnv('LIVE_GIFT_MAX_COINS_PER_DAY', 25_000, 10_000_000),
  )
  return {
    max_coins_per_gift: maxCoinsPerGift,
    max_coins_per_day: maxCoinsPerDay,
    max_gifts_per_minute: boundedPositiveIntegerEnv('LIVE_GIFT_MAX_GIFTS_PER_MINUTE', 20, 300),
    high_value_confirmation_coins: Math.min(
      maxCoinsPerGift,
      boundedPositiveIntegerEnv('LIVE_GIFT_HIGH_VALUE_CONFIRMATION_COINS', 900, 1_000_000),
    ),
  }
}

module.exports = { liveGiftLimitsConfiguration }
