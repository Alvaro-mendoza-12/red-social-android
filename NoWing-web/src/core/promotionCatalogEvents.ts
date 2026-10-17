export const promotionCatalogEvent = 'novvin:promotion-catalog-changed'

export function notifyPromotionCatalogChanged(accountId: string) {
  window.dispatchEvent(new CustomEvent(promotionCatalogEvent, { detail: accountId }))
}
