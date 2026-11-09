// SPDX-License-Identifier: AGPL-3.0-only
import { openDB, type DBSchema, type IDBPDatabase } from 'idb';
import type { TrackAnalysis } from './analysis/types';

interface AutomixDB extends DBSchema {
  /** Keyed by YouTube video id (TrackAnalysis.fileKey). */
  tracks: { key: string; value: TrackAnalysis };
}

let dbPromise: Promise<IDBPDatabase<AutomixDB>> | null = null;

function getDb(): Promise<IDBPDatabase<AutomixDB>> {
  dbPromise ??= openDB<AutomixDB>('nuvx-automix', 1, {
    upgrade(db) {
      db.createObjectStore('tracks');
    },
  });
  return dbPromise;
}

export async function getAnalysis(videoId: string): Promise<TrackAnalysis | undefined> {
  return (await getDb()).get('tracks', videoId);
}

export async function putAnalysis(analysis: TrackAnalysis): Promise<void> {
  await (await getDb()).put('tracks', analysis, analysis.fileKey);
}
