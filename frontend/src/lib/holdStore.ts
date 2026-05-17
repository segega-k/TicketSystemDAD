import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';
import type { HoldResponse } from '../types';

interface HoldState {
  holds: Record<string, HoldResponse>;
  setHold: (hold: HoldResponse) => void;
  removeHold: (holdGroupId: string) => void;
  clearExpired: () => void;
}

export const useHoldStore = create<HoldState>()(
  persist(
    (set, get) => ({
      holds: {},
      setHold: (hold) => set((state) => ({ holds: { ...state.holds, [hold.hold_group_id]: hold } })),
      removeHold: (holdGroupId) =>
        set((state) => {
          const next = { ...state.holds };
          delete next[holdGroupId];
          return { holds: next };
        }),
      clearExpired: () =>
        set({
          holds: Object.fromEntries(
            Object.entries(get().holds).filter(([, hold]) => new Date(hold.expires_at).getTime() > Date.now())
          ),
        }),
    }),
    { name: 'ticket-system-dad-holds', storage: createJSONStorage(() => sessionStorage) }
  )
);
