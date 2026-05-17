import type { SeatMapResponse } from '../types';

export function getSelectedSeats(map: SeatMapResponse | null, selectedIds: string[]) {
  if (!map) return [];
  const selected = new Set(selectedIds);
  return map.rows.flatMap((row) =>
    row.seats.filter((seat) => selected.has(seat.id)).map((seat) => ({ ...seat, row: row.label }))
  );
}

export function validateAdjacentSelection(
  map: SeatMapResponse | null,
  selectedIds: string[]
): { ok: boolean; message?: string } {
  if (!selectedIds.length) return { ok: false, message: 'Choose at least one seat.' };
  if (selectedIds.length > 6) return { ok: false, message: 'You can hold at most 6 seats per event.' };
  const seats = getSelectedSeats(map, selectedIds);
  if (seats.length !== selectedIds.length) return { ok: false, message: 'One or more selected seats no longer exist.' };
  if (seats.some((seat) => seat.status !== 'AVAILABLE'))
    return { ok: false, message: 'Only available seats can be selected.' };
  const row = seats[0]?.row;
  if (!row || seats.some((seat) => seat.row !== row)) return { ok: false, message: 'Seats must be in the same row.' };
  const sorted = [...seats].sort((a, b) => a.number - b.number);
  for (let i = 1; i < sorted.length; i += 1) {
    if (sorted[i].number !== sorted[i - 1].number + 1)
      return { ok: false, message: 'Seats must be adjacent with no gaps.' };
  }
  return { ok: true };
}
