import { describe, expect, it } from 'vitest';
import { validateAdjacentSelection } from './adjacentSeats';
import type { SeatMapResponse } from '../types';

const map: SeatMapResponse = {
  event_id: 'e1',
  fetched_at: new Date().toISOString(),
  rows: [
    {
      label: 'A',
      seats: [1, 2, 3, 4, 5, 6, 7].map((number) => ({
        id: `A${number}`,
        number,
        tier: 'STANDARD',
        price: '10.00',
        status: 'AVAILABLE',
      })),
    },
    {
      label: 'B',
      seats: [1, 2].map((number) => ({ id: `B${number}`, number, tier: 'VIP', price: '20.00', status: 'AVAILABLE' })),
    },
  ],
};

describe('validateAdjacentSelection', () => {
  it('accepts one to six adjacent seats in the same row', () => {
    expect(validateAdjacentSelection(map, ['A2', 'A3', 'A4']).ok).toBe(true);
  });
  it('rejects gaps', () => {
    expect(validateAdjacentSelection(map, ['A2', 'A4'])).toMatchObject({ ok: false });
  });
  it('rejects multiple rows', () => {
    expect(validateAdjacentSelection(map, ['A1', 'B1'])).toMatchObject({ ok: false });
  });
  it('rejects more than six seats', () => {
    expect(validateAdjacentSelection(map, ['A1', 'A2', 'A3', 'A4', 'A5', 'A6', 'A7'])).toMatchObject({ ok: false });
  });
});
