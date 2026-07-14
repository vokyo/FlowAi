export type CursorPage<T> = {
  items: T[]
  nextCursor: string | null
}

type Identifiable = {
  id: string
}

export function mergeCursorPageItems<T extends Identifiable>(
  pages: readonly CursorPage<T>[] | undefined,
) {
  const items = new Map<string, T>()

  for (const page of pages ?? []) {
    for (const item of page.items) {
      items.set(item.id, item)
    }
  }

  return Array.from(items.values())
}

export function compareCreatedAtAscending<T extends Identifiable & { createdAt: string }>(
  left: T,
  right: T,
) {
  const createdAtComparison = left.createdAt.localeCompare(right.createdAt)
  return createdAtComparison || left.id.localeCompare(right.id)
}
